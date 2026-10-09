package com.nila.vision.guard

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs the vision model on live frames, back to back, and reports every look at once. Ported from Baby
 * Guard CCTV; the only change is the engine: Nila's NpuVlm in its own process ([RemoteVlm]; same
 * Intern3.5-VL-2B bundle, same GenieX call, greedy decoding), which also takes the NPU from the chat model
 * through the arbiter first.
 *
 * Latency is what this class is about:
 *  - the next look starts as soon as the previous one ends (no fixed interval);
 *  - the frame comes from the camera stream already in memory;
 *  - the caller raises the alert on the first DANGER answer.
 */
class VisionLoop(
    private val scope: CoroutineScope,
    /** One look: the frame and the two prompts in, the model's raw answer (or null) out. */
    private val engine: suspend (Bitmap, String, String) -> String?,
    private val frameSource: suspend () -> Bitmap?,
    /** Optional change to the frame before the model sees it (night: the brightened copy). */
    private val preprocess: (Bitmap) -> Bitmap = { it },
    private val onLook: suspend (Look) -> Unit,
    /** The model stopped answering (seen on this phone as a generate call that never returns). */
    private val onStuck: (String) -> Unit,
    private val log: (String) -> Unit,
) {
    data class Look(
        val n: Int,
        val source: String,
        val assessment: SafetyGuard.Assessment,
        val text: String,
        val frameMs: Long,
        val prepMs: Long,
        val modelMs: Long,
        /** System.currentTimeMillis() when the answer was parsed: the alert latency is measured from here. */
        val answeredAt: Long,
    )

    private var job: Job? = null
    private var lookCount = 0
    private var failuresInARow = 0
    private val poke = Channel<Unit>(Channel.CONFLATED)

    /** Extra wait between looks (set by the caller when the phone runs hot). */
    @Volatile var pauseMs = 0L

    /**
     * Gap between looks while the scene is still. Looking non-stop took the phone from thermal status 0 to 3
     * (severe) in about a minute, so a still scene is checked every few seconds and any change in the picture
     * ([poke]) starts the next look at once.
     */
    @Volatile var idleGapMs = 0L

    /** While true (e.g. an alert is on), look back to back. */
    @Volatile var urgent = false

    /** Something changed in the picture: start the next look now. */
    fun poke() {
        poke.trySend(Unit)
    }

    val running get() = job?.isActive == true

    @Volatile var busy = false
        private set

    /** Continuous looks until [stop]. */
    fun start() {
        if (running) return
        job =
            scope.launch {
                log("VISION_LOOP_START")
                var reason = "first"
                while (isActive) {
                    poke.tryReceive() // changes during the previous look are covered by this look
                    lookOnce("loop:$reason")
                    if (pauseMs > 0) delay(pauseMs)
                    reason =
                        if (urgent) {
                            "urgent"
                        } else {
                            withTimeoutOrNull(idleGapMs) { poke.receive() }?.let { "motion" } ?: "idle"
                        }
                }
            }
    }

    fun stop() {
        if (job == null) return
        job?.cancel()
        job = null
        log("VISION_LOOP_STOP")
    }

    /**
     * One look on demand ("Look now"). When the loop is running it is already looking back to back,
     * so the next loop answer is the fresh description and nothing extra is started.
     */
    fun lookNow(): Job? = if (running || busy) null else scope.launch { lookOnce("look_now") }

    private suspend fun lookOnce(source: String) {
        busy = true
        try {
            val t0 = System.currentTimeMillis()
            val bmp = withTimeoutOrNull(3_000) { frameSource() }
            val label = "$source:camera"
            if (bmp == null) {
                log("VISION no frame ($label)")
                delay(500)
                return
            }
            val tFrame = System.currentTimeMillis()
            val input = runCatching { preprocess(bmp) }.getOrDefault(bmp)
            val tPrep = System.currentTimeMillis()
            // The model call runs on its own job so a hung NPU call cannot hang this loop as well.
            val call = scope.async(Dispatchers.IO) { engine(input, SafetyGuard.SYSTEM_PROMPT, SafetyGuard.USER_PROMPT) }
            val out = withTimeoutOrNull(LOOK_TIMEOUT_MS) { call.await() }
            val tModel = System.currentTimeMillis()
            if (input !== bmp) runCatching { input.recycle() }
            runCatching { bmp.recycle() }
            if (!call.isCompleted) {
                log("VISION_STUCK no answer from the model after ${LOOK_TIMEOUT_MS / 1000} s ($label)")
                job?.cancel()
                onStuck("the model did not answer for ${LOOK_TIMEOUT_MS / 1000} s")
                return
            }
            if (out.isNullOrBlank()) {
                failuresInARow++
                // Nila's engine also answers nothing while the chat model is mid-reply on the NPU, which
                // is a pause, not a fault: only a long run of empty answers stops the loop.
                log("VISION model gave no answer ($failuresInARow in a row) ($label)")
                if (failuresInARow >= 8) {
                    job?.cancel()
                    onStuck("the model failed $failuresInARow looks in a row")
                }
                delay(800)
                return
            }
            failuresInARow = 0
            val a = SafetyGuard.assess(out)
            val answeredAt = System.currentTimeMillis()
            lookCount++
            onLook(
                Look(
                    n = lookCount,
                    source = if (input !== bmp) "$label:night_enhanced" else label,
                    assessment = a,
                    text = out,
                    frameMs = tFrame - t0,
                    prepMs = tPrep - tFrame,
                    modelMs = tModel - tPrep,
                    answeredAt = answeredAt,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("VISION look failed: ${e.message}")
            delay(1_000)
        } finally {
            busy = false
        }
    }

    companion object {
        /** A normal look takes ~1.2 s; the first look after a load ~2 s. */
        const val LOOK_TIMEOUT_MS = 10_000L
    }
}
