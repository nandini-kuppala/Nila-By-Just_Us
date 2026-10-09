package com.nila.vision.guard

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.nila.actions.VivoRingLight

/**
 * The danger alert's lights, ported from Baby Guard CCTV's AlarmController for a service:
 *  - torch: rear flash strobe at the maximum torch strength level (through the camera binding while the
 *    camera is in use, else CameraManager)
 *  - ring light: fast colour flashing on the rear ring through the vivo light service ([VivoRingLight])
 *  - screen: the full-screen red/white flash is drawn by the Watch screen from [active] (a service has no
 *    window); the notification the service posts covers a phone whose screen is elsewhere
 * Stops by itself after [MAX_ALARM_MS]. Call from any thread; work is posted to the main thread.
 */
class AlarmController(
    context: Context,
    private val log: (String) -> Unit,
) {
    /** Set while a camera binding owns the back camera: the torch must then be driven through it. */
    @Volatile var cameraTorch: ((Boolean) -> Unit)? = null

    /** Off by the parent's switch: the strobe is skipped, the other parts still run. */
    @Volatile var torchEnabled = true

    @Volatile var active = false
        private set

    @Volatile var reason = ""
        private set

    /** Called when the alarm stops by itself (the 60 s cap), so the owner can tidy up. */
    var onAutoStop: (() -> Unit)? = null

    private val ring = VivoRingLight(context)
    private val handler = Handler(Looper.getMainLooper())
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val torchCameraId: String? =
        runCatching {
            cameraManager.cameraIdList.firstOrNull { id ->
                val c = cameraManager.getCameraCharacteristics(id)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        }.getOrNull()
    private val torchMaxLevel: Int =
        torchCameraId?.let {
            if (Build.VERSION.SDK_INT >= 33) {
                runCatching {
                    cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL)
                }.getOrNull() ?: 1
            } else {
                1
            }
        } ?: 0

    private var torchOn = false
    private var torchErrorLogged = false
    private var useStrength = true
    private var torchOk = 0
    private var torchFailed = 0
    private var strobing = false
    private val report = linkedMapOf<String, String>()

    // Faster toggling made the camera service reject torch calls on this phone.
    private val torchTick =
        object : Runnable {
            override fun run() {
                if (!strobing) return
                setTorch(!torchOn)
                handler.postDelayed(this, TORCH_HALF_PERIOD_MS)
            }
        }

    private val autoStop =
        Runnable {
            log("alarm auto-stop after ${MAX_ALARM_MS / 1000} s")
            stop()
            onAutoStop?.invoke()
        }

    fun componentReport(): String = report.entries.joinToString(" ") { "${it.key}=${it.value}" }

    fun start(why: String) {
        if (active) return
        active = true
        reason = why
        report.clear()
        report["screen"] = "watch_screen"
        handler.post {
            part("ring_light") {
                if (ring.start(playful = true, durationMs = MAX_ALARM_MS.toInt())) "neon" else "unavailable"
            }
            part("torch") { if (torchEnabled) startStrobe() else "off_by_setting" }
            handler.postDelayed(autoStop, MAX_ALARM_MS)
            log("alert lights on: ${componentReport()}")
        }
    }

    fun stop() {
        if (!active) return
        active = false
        handler.removeCallbacks(autoStop)
        handler.post {
            stopStrobe()
            runCatching { ring.stop() }
            log("alert lights off: torch_calls_ok=$torchOk torch_calls_failed=$torchFailed")
        }
    }

    /**
     * Distraction without an alarm: the ring light (and the torch if allowed) for [durationMs], to draw the
     * baby's eyes. Used by the "Distract" button.
     */
    fun distract(durationMs: Long) {
        handler.post {
            runCatching { ring.start(playful = true, durationMs = durationMs.toInt()) }
            if (torchEnabled && !active) startStrobe()
            handler.postDelayed({
                if (!active) {
                    stopStrobe()
                    runCatching { ring.stop() }
                }
            }, durationMs)
        }
    }

    fun stopDistract() {
        if (active) return
        handler.post {
            stopStrobe()
            runCatching { ring.stop() }
        }
    }

    fun ringDescription(): String = runCatching { ring.describe() }.getOrDefault("unknown")

    private fun part(name: String, block: () -> String) {
        report[name] =
            try {
                block()
            } catch (e: Exception) {
                "failed(${e.javaClass.simpleName}: ${e.message})"
            }
    }

    private fun startStrobe(): String {
        if (cameraTorch == null && torchCameraId == null) return "none"
        if (strobing) return "strobe"
        strobing = true
        torchErrorLogged = false
        useStrength = true
        torchOk = 0
        torchFailed = 0
        torchTick.run()
        return if (cameraTorch != null) "strobe(camera)" else "strobe(level $torchMaxLevel)"
    }

    private fun stopStrobe() {
        strobing = false
        handler.removeCallbacks(torchTick)
        setTorch(false)
    }

    private fun setTorch(on: Boolean) {
        torchOn = on
        cameraTorch?.let {
            runCatching { it(on) }.onSuccess { torchOk++ }.onFailure { torchFailed++ }
            return
        }
        val id = torchCameraId ?: return
        try {
            if (on && useStrength && Build.VERSION.SDK_INT >= 33 && torchMaxLevel > 1) {
                try {
                    cameraManager.turnOnTorchWithStrengthLevel(id, torchMaxLevel)
                } catch (e: Exception) {
                    // Fall back to the plain torch (default strength) for the rest of this alert.
                    useStrength = false
                    log("torch strength level failed (${e.message}), using plain torch")
                    cameraManager.setTorchMode(id, true)
                }
            } else {
                cameraManager.setTorchMode(id, on)
            }
            torchOk++
        } catch (e: Exception) {
            torchFailed++
            if (!torchErrorLogged) log("torch failed: ${e.message}")
            torchErrorLogged = true
        }
    }

    companion object {
        const val MAX_ALARM_MS = 60_000L
        private const val TORCH_HALF_PERIOD_MS = 400L
    }
}
