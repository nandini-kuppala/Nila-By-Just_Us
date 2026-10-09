package com.nila.actions

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.Closeable

/**
 * What the phone does when the camera sees the baby reaching for something.
 *
 * ### Why act at all, rather than only notify
 *
 * This is the same argument the cry ladder is built on. A monitor that only
 * notifies hands the parent a problem that is already happening and asks them to
 * run. The baby is going to touch the cable before anybody crosses the room, so
 * the phone does the only useful thing it can do in those seconds: it makes
 * itself more interesting than the cable.
 *
 * A baby's attention is caught by sound and by changing light, and the phone has
 * both. The soother is the same player the cry ladder uses -- including, when
 * one has been recorded, the caregiver's own voice, which is the sound measured
 * to work best in that ladder. The ring light cycles saturated colours, and the
 * torch pulses. Together they buy the seconds the parent needs.
 *
 * ### What it will not do
 *
 * It does not run on a guess. The caller only reaches here on a *confirmed*
 * hazard -- two looks, not one -- and never on the cheap lane's posture rules
 * alone. It stops by itself after [Config.durationMs], it will not run again
 * inside [Config.cooldownMs], and the whole feature sits behind a setting that
 * is off until a parent turns it on.
 *
 * The torch is deliberately the last component and the first to be dropped: it
 * is a bright light aimed into a cot, and on a sleeping baby it is the wrong
 * tool. [Config.useTorch] exists so that choice is the parent's, and the default
 * is the ring and the sound.
 */
class Distraction(
    private val context: Context,
    private val player: SoothePlayer,
    private val ring: VivoRingLight = VivoRingLight(context),
    private val config: Config = Config(),
) : Closeable {

    data class Config(
        val durationMs: Long = 20_000L,
        val cooldownMs: Long = 90_000L,
        /** Torch half-period. Faster than this and the camera service rejects the calls. */
        val torchPeriodMs: Long = 400L,
        val useTorch: Boolean = false,
    )

    /** What actually ran, so the log says what the baby was offered. */
    data class Outcome(
        val sound: String?,
        val ringLight: Boolean,
        val torch: Boolean,
    ) {
        val summary: String
            get() = listOfNotNull(
                sound?.let { "played $it" },
                if (ringLight) "lit the ring" else null,
                if (torch) "pulsed the torch" else null,
            ).joinToString(", ").ifBlank { "nothing available" }
    }

    /** Set by the camera lane while it holds the camera; null when it does not. */
    @Volatile
    var torchControl: ((Boolean) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var runningUntil = 0L

    @Volatile private var lastRunAt = 0L

    @Volatile private var torchOn = false

    val isRunning: Boolean get() = System.currentTimeMillis() < runningUntil

    private val torchTick = object : Runnable {
        override fun run() {
            if (!isRunning) {
                setTorch(false)
                return
            }
            setTorch(!torchOn)
            handler.postDelayed(this, config.torchPeriodMs)
        }
    }

    private val stopTick = Runnable { stop() }

    /**
     * Try to hold the baby's attention for a few seconds.
     *
     * @param soother which sound to play, usually the one [SootheMemory] says has
     *   worked for this baby before.
     * @return what ran, or null when the cooldown blocked it.
     */
    fun start(soother: Soother?, now: Long = System.currentTimeMillis()): Outcome? {
        if (isRunning) return null
        if (now - lastRunAt < config.cooldownMs) {
            Log.i(TAG, "distraction suppressed: inside the cooldown")
            return null
        }
        lastRunAt = now
        runningUntil = now + config.durationMs

        val played = soother?.let { runCatching { player.play(it, loop = true) }.getOrDefault(false) } == true
        val lit = runCatching { ring.start(playful = true, durationMs = config.durationMs.toInt()) }
            .getOrDefault(false)
        val torch = if (config.useTorch && torchControl != null) {
            handler.post(torchTick)
            true
        } else {
            false
        }

        handler.postDelayed(stopTick, config.durationMs)
        val outcome = Outcome(if (played) soother?.displayName else null, lit, torch)
        Log.i(TAG, "distraction: ${outcome.summary}")
        return outcome
    }

    fun stop() {
        runningUntil = 0
        handler.removeCallbacks(stopTick)
        handler.removeCallbacks(torchTick)
        setTorch(false)
        runCatching { ring.stop() }
        runCatching { player.stop() }
    }

    private fun setTorch(on: Boolean) {
        torchOn = on
        runCatching { torchControl?.invoke(on) }
            .onFailure { Log.i(TAG, "torch refused: ${it.message}") }
    }

    fun describeRingLight(): String = ring.describe()

    override fun close() {
        stop()
        torchControl = null
    }

    companion object {
        private const val TAG = "Distraction"
    }
}
