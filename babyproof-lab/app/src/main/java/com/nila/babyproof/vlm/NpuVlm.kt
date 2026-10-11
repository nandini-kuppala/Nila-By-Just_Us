/*
 * Copied from Nila (android/app/.../vision/NpuVlm.kt) for the babyproofing lab.
 * Changes: no NpuArbiter calls (the lab app has no chat model to share the NPU
 * with) and a longer reply budget (160 tokens) for the room-wide list.
 * Comments that mention SceneDescriber or NpuArbiter refer to the main app.
 */
package com.nila.babyproof.vlm

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.VlmWrapper
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ModelPullInput
import com.geniex.sdk.bean.ModelType
import com.geniex.sdk.bean.SamplerConfig
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import com.geniex.sdk.bean.VlmCreateInput
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * The scene model, on the Hexagon NPU.
 *
 * Intern3.5-VL-2B, as Qualcomm compiles it for AI Hub: a 448x448 vision encoder
 * feeding a 2B language model in four shards, quantised to w4a16 and run through
 * GenieX's QAIRT plugin. It is the same SDK the chat model already uses, on the
 * same accelerator, with a different plugin -- `qairt` reads these compiled
 * bundles where `llama.cpp` reads GGUF.
 *
 * ### Why this and not the describer that already exists
 *
 * [SceneDescriber] runs FastVLM-0.5B through LiteRT-LM on the CPU with a GPU
 * vision backend. It works, and it stays as the fallback, but it is 1.7 GB
 * resident and it cannot touch the NPU -- LiteRT-LM's NPU rung needs QNN
 * delegate libraries that are not in the published release. Measured on the
 * phone this app targets:
 *
 * | | first token | whole answer |
 * |---|---|---|
 * | Intern3.5-VL-2B, NPU, this class | ~490 ms | ~1.2 s |
 * | FastVLM-0.5B, LiteRT GPU/CPU | ~550 ms | several seconds |
 *
 * The number that matters more is the second one, because the lane asks for four
 * short fields and reads none of them until the answer is complete.
 *
 * ### The load, and why it is a loop
 *
 * The first `create()` in a process reliably fails on this phone. The bundle
 * wants the image encoder, four LLM shards and 277 MB of working buffers in one
 * DSP session, and the first session cannot map all of it: the NPU-side process
 * dies while growing its heap and QNN reports err 1007. Retrying lands on a
 * fresh session, which holds the lot. Over every cold start measured, the model
 * loaded on attempt 2 or attempt 4, in 7.5 to 23 seconds. None of the obvious
 * knobs changed it -- mmap budget, file-read budget, encoder mmap, load order --
 * so the loop is the fix, and it is why [start] reports progress rather than
 * blocking a caller silently for twenty seconds.
 *
 * ### The frame on disk
 *
 * The QAIRT pipeline takes an image by path, so one JPEG is written to the app's
 * own cache for the length of one look and deleted immediately after, in a
 * `finally`. [SceneDescriber] deliberately keeps its frame in memory; this path
 * cannot, and the difference is worth stating rather than hiding.
 */
class NpuVlm(private val context: Context) : Closeable {

    companion object {
        private const val TAG = "NpuVlm"

        /** What GenieX registers the bundle as. */
        const val MODEL_NAME = "local/Intern3.5-VL-2B"
        const val PRECISION = "w4a16"

        /** The directory name under files/local or the external models folder. */
        const val BUNDLE_DIR = "intern-vl"

        /** Files that have to be there before the bundle is worth handing to the SDK. */
        private val REQUIRED = listOf(
            "metadata.json", "vision_encoder.bin", "part1_of_4.bin",
            "part4_of_4.bin", "tokenizer.json",
        )

        /**
         * The waits between load attempts.
         *
         * The first attempt is immediate and expected to fail; the rest are the
         * ladder measured to work. Eight attempts rather than four because a
         * phone with the chat model loaded has a busier DSP than the bench did.
         */
        private val RETRY_WAITS_MS = longArrayOf(0, 2_000, 4_000, 8_000, 8_000, 8_000, 8_000, 8_000)

        /** Set while a retry is inside the native create call. */
        private const val RETRY_IN_FLIGHT = "retry_in_flight"

        /** Generated tokens. The form is four short lines; 120 is already generous. */
        private const val MAX_TOKENS = 160
    }

    sealed interface State {
        data object Absent : State
        data class Loading(val attempt: Int, val of: Int) : State
        data class Ready(val loadMs: Long, val attempts: Int) : State
        data class Failed(val reason: String) : State
    }

    @Volatile
    var state: State = State.Absent
        private set

    private var vlm: VlmWrapper? = null
    private val lock = Mutex()

    /** Survives a native crash; see the retry note in [start]. */
    private val guard = context.getSharedPreferences("npu_vlm", Context.MODE_PRIVATE)

    /**
     * One thread for the engine, for the reason [com.nila.assistant.NpuLlm] gives:
     * generation reaches a blocking JNI call that cancellation cannot interrupt.
     */
    private val worker: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "babyproof-vlm") }.asCoroutineDispatcher()

    val isReady: Boolean get() = vlm != null

    /** Where the bundle is, if it is anywhere. Downloaded copy first, adb copy second. */
    fun bundleDir(): File {
        val external = File(context.getExternalFilesDir("models"), BUNDLE_DIR)
        if (external.isDirectory) return external
        return File(File(context.filesDir, "local"), BUNDLE_DIR)
    }

    fun isInstalled(): Boolean {
        val dir = bundleDir()
        if (!dir.isDirectory) return false
        return REQUIRED.all { File(dir, it).let { f -> f.isFile && f.length() > 0 } }
    }

    fun describeInstall(): String = when {
        !isInstalled() -> "Not installed"
        isReady -> "Intern3.5-VL-2B, on the NPU"
        state is State.Failed -> "Failed: ${(state as State.Failed).reason}"
        else -> "Installed, loads when the watch first looks"
    }

    /**
     * Register the bundle and load it onto the NPU.
     *
     * Safe to call repeatedly: the first caller does the work under [lock] and
     * the rest return its result. Every failure lands in [state] rather than
     * being thrown, because the camera lane has to keep watching with the cheap
     * detectors whatever the model does.
     */
    suspend fun start(onStep: (State) -> Unit = {}): Boolean = lock.withLock {
        if (vlm != null) return@withLock true
        if (!isInstalled()) {
            update(State.Failed("no bundle in ${bundleDir().absolutePath}"), onStep)
            return@withLock false
        }
        // runCatching, not try/catch on Exception: on a device without the
        // Hexagon libraries -- an x86 emulator, say -- the SDK fails to link
        // and throws an Error rather than an Exception. The watch has to carry
        // on with the cheap lane either way.
        val initialised = runCatching { initSdk() }
            .onFailure { Log.i(TAG, "GenieX is not available on this device: ${it.message}") }
            .getOrDefault(false)
        if (!initialised) {
            update(State.Failed("GenieX did not initialise"), onStep)
            return@withLock false
        }
        val paths = runCatching { registerBundle() }
            .onFailure { Log.w(TAG, "registration failed", it) }
            .getOrNull()
        if (paths == null) {
            update(State.Failed("the bundle could not be registered"), onStep)
            return@withLock false
        }


        // A retry that took the process down last time is not tried again.
        //
        // On an iQOO 15, every retry after a failed first attempt crashed the
        // app: SIGSEGV in QnnApi::createDevice, twice out of twice -- once with a
        // bundle built for a newer QAIRT, once with the NPU still held by a
        // process that had just been killed. The restarted process then loaded
        // the model at the first attempt. A native crash cannot be caught, so the
        // guard is a note written before each retry and cleared after it: found
        // still set, the last retry never returned, and this start makes one
        // attempt only. The bench phone that needed the ladder keeps it.
        val retryCrashed = guard.getBoolean(RETRY_IN_FLIGHT, false)
        if (retryCrashed) {
            guard.edit().putBoolean(RETRY_IN_FLIGHT, false).commit()
            Log.w(TAG, "a load retry crashed the app last time; one attempt only")
        }
        val waits = if (retryCrashed) RETRY_WAITS_MS.copyOf(1) else RETRY_WAITS_MS

        val started = System.currentTimeMillis()
        for ((index, wait) in waits.withIndex()) {
            if (wait > 0) delay(wait)
            update(State.Loading(index + 1, waits.size), onStep)
            // Committed, not applied: the write has to be on disk before a
            // crash in the call below, or the next start cannot see it.
            if (index > 0) guard.edit().putBoolean(RETRY_IN_FLIGHT, true).commit()
            val attempt = runCatching { create(paths.first, paths.second) }
                .onFailure { Log.i(TAG, "load attempt ${index + 1} failed: ${it.message}") }
                .getOrNull()
            if (index > 0) guard.edit().putBoolean(RETRY_IN_FLIGHT, false).commit()
            if (attempt != null) {
                vlm = attempt
                update(State.Ready(System.currentTimeMillis() - started, index + 1), onStep)
                return@withLock true
            }
        }
        update(State.Failed("the NPU would not take the model after ${waits.size} " +
            if (waits.size == 1) "attempt" else "attempts"), onStep)
        return@withLock false
    }

    /**
     * Look at one frame and return the model's raw reply.
     *
     * Parsing is deliberately somebody else's job: [SceneDescriber.parse] already
     * reads this form, and keeping one parser means the NPU path and the LiteRT
     * path cannot drift into answering differently.
     *
     * @return null when the model is not loaded, the NPU is busy with a chat
     *   reply, or generation failed -- in each case the caller shows nothing
     *   rather than a guess.
     */
    suspend fun look(frame: Bitmap, systemPrompt: String, userPrompt: String): String? {
        val model = vlm ?: return null
        var file: File? = null
        return try {
            lock.withLock {
                file = writeFrame(frame)
                val user = VlmChatMessage(
                    "user",
                    listOf(
                        VlmContent("image", file!!.absolutePath),
                        VlmContent("text", userPrompt),
                    ),
                )
                val system = VlmChatMessage("system", listOf(VlmContent("text", systemPrompt)))
                model.reset()
                val template = model.applyChatTemplate(arrayOf(system, user), null, false).getOrThrow()
                val config = model
                    .injectMediaPathsToConfig(arrayOf(user), GenerationConfig(maxTokens = MAX_TOKENS))
                    .apply { samplerConfig = greedy() }

                val reply = StringBuilder()
                var failure: Throwable? = null
                model.generateStreamFlow(template.formattedText, config).collect { result ->
                    when (result) {
                        is LlmStreamResult.Token -> reply.append(result.text)
                        is LlmStreamResult.Error -> failure = result.throwable
                        else -> Unit
                    }
                }
                failure?.let {
                    Log.w(TAG, "generation failed", it)
                    return@withLock null
                }
                reply.toString().trim().ifBlank { null }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "look failed", t)
            null
        } finally {
            // The frame is a picture of someone's baby. It exists for one
            // inference and is removed whether or not that inference worked.
            runCatching { file?.delete() }
        }
    }

    /**
     * Temperature zero.
     *
     * The form has closed vocabularies and the lane compares consecutive looks
     * to decide whether a hazard is real; sampling would make the same frame
     * answer differently on two looks and defeat the comparison.
     */
    private fun greedy() = SamplerConfig(
        temperature = 0f,
        topP = 1f,
        topK = 1,
        minP = 0f,
        repetitionPenalty = 1f,
        presencePenalty = 0f,
        seed = 42,
    )

    private fun writeFrame(frame: Bitmap): File {
        val dir = File(context.cacheDir, "vision").apply { mkdirs() }
        val file = File(dir, "look.jpg")
        FileOutputStream(file).use { frame.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return file
    }

    private suspend fun initSdk(): Boolean = suspendCancellableCoroutine { cont ->
        GenieXSdk.getInstance().init(context, object : GenieXSdk.InitCallback {
            override fun onSuccess() {
                if (cont.isActive) cont.resume(true)
            }

            override fun onFailure(reason: String) {
                Log.w(TAG, "GenieX init failed: $reason")
                if (cont.isActive) cont.resume(false)
            }
        })
    }

    /**
     * Point GenieX at the bundle already on the device.
     *
     * `local_path` is the directory, not a file. The SDK writes its own
     * `geniex.json` beside the copy it keeps, and [ModelManagerWrapper.getPaths]
     * is what resolves afterwards.
     *
     * @return model path and mmproj path, as the create call wants them.
     */
    private suspend fun registerBundle(): Pair<String, String?> {
        ModelManagerWrapper.getPaths(MODEL_NAME)?.let { return it.model_path to it.mmproj_path }
        var error: String? = null
        ModelManagerWrapper.pullFlow(
            ModelPullInput(
                model_name = MODEL_NAME,
                precision = PRECISION,
                hub = HubSource.LOCALFS,
                local_path = bundleDir().absolutePath,
                model_type = ModelType.VLM,
            )
        ).collect { event ->
            if (event is ModelManagerWrapper.PullEvent.Error) error = "${event.code}: ${event.message}"
        }
        error?.let { throw IllegalStateException(it) }
        val paths = ModelManagerWrapper.getPaths(MODEL_NAME)
            ?: throw IllegalStateException("no paths for $MODEL_NAME after registering")
        return paths.model_path to paths.mmproj_path
    }

    /**
     * One attempt at putting the bundle on the NPU.
     *
     * `nCtx` and `nGpuLayers` are zero deliberately: a QAIRT bundle has its
     * context length and its placement compiled in, and the plugin rejects a
     * create that tries to override either.
     */
    private suspend fun create(modelPath: String, mmprojPath: String?): VlmWrapper =
        VlmWrapper.builder()
            .vlmCreateInput(
                VlmCreateInput(
                    model_path = modelPath,
                    mmproj_path = mmprojPath,
                    config = ModelConfig(nCtx = 0, nGpuLayers = 0),
                    runtime_id = GenieXSdk.PLUGIN_ID_QAIRT,
                    compute_unit = ComputeUnitValue.NPU.value ?: "npu",
                )
            )
            .dispatcher(worker)
            .build()
            .getOrThrow()

    private fun update(next: State, onStep: (State) -> Unit) {
        state = next
        Log.i(TAG, "state -> $next")
        onStep(next)
    }

    override fun close() {
        runCatching { vlm?.destroy() }
        vlm = null
        state = State.Absent
        runCatching { worker.close() }
    }
}
