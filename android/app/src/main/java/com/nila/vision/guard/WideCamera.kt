package com.nila.vision.guard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * The phone's ultra-wide lens, opened directly with Camera2. Ported unchanged from Baby Guard CCTV.
 *
 * vivo hides camera 3 (ultra-wide, 2.31 mm, f/2.05, autofocus, flash) from the camera list that apps see
 * (only "0, 1"), so CameraX cannot select it; opening it by id works. Frames come from one YUV ImageReader
 * and go through the same pipeline as CameraX frames, as [GuardFrame]s. Needs no window, so it runs in the
 * watch service with the screen off.
 */
class WideCamera(
    private val context: Context,
    private val onFrame: (GuardFrame) -> Unit,
    private val log: (String) -> Unit,
) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var request: CaptureRequest.Builder? = null

    @Volatile var iso = 0
        private set

    @Volatile var exposureMs = 0.0
        private set

    var sensorRotation = 90
        private set

    var hasFlash = false
        private set

    val running get() = device != null

    @SuppressLint("MissingPermission")
    fun start(id: String = ULTRA_WIDE_ID): Boolean {
        if (running) return true
        val chars =
            try {
                manager.getCameraCharacteristics(id)
            } catch (e: Exception) {
                log("WIDE camera $id not available: ${e.message}")
                return false
            }
        sensorRotation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
        // 4:3, closest to 1280x960 (enough for the model at 448 and the detector at 480).
        val size =
            sizes.filter { abs(it.width * 3 - it.height * 4) < 8 }.minByOrNull { abs(it.width - 1280) }
                ?: sizes.minByOrNull { abs(it.width - 1280) } ?: Size(1280, 960)
        val t = HandlerThread("wide-camera").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        reader = r
        r.setOnImageAvailableListener({ rd ->
            val img = runCatching { rd.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            onFrame(Camera2Frame(img, sensorRotation))
        }, h)
        log("WIDE opening camera $id ${size.width}x${size.height} rotation=$sensorRotation flash=$hasFlash")
        try {
            manager.openCamera(
                id,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) {
                        device = d
                        createSession(d, r, h)
                    }

                    override fun onDisconnected(d: CameraDevice) {
                        log("WIDE camera disconnected")
                        d.close()
                        device = null
                    }

                    override fun onError(d: CameraDevice, error: Int) {
                        log("WIDE camera error $error")
                        d.close()
                        device = null
                    }
                },
                h,
            )
        } catch (e: Exception) {
            log("WIDE open failed: ${e.message}")
            stop()
            return false
        }
        return true
    }

    private fun createSession(d: CameraDevice, r: ImageReader, h: Handler) {
        val exec = Executor { h.post(it) }
        val cfg =
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(r.surface)),
                exec,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        b.addTarget(r.surface)
                        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        request = b
                        repeat()
                        log("WIDE camera streaming")
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        log("WIDE session configuration failed")
                    }
                },
            )
        runCatching { d.createCaptureSession(cfg) }.onFailure { log("WIDE session failed: ${it.message}") }
    }

    private fun repeat() {
        val s = session ?: return
        val b = request ?: return
        try {
            s.setRepeatingRequest(
                b.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { iso = it }
                        result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { exposureMs = it / 1e6 }
                    }
                },
                handler,
            )
        } catch (e: Exception) {
            log("WIDE repeating request failed: ${e.message}")
        }
    }

    /** The alert's torch strobe, through this camera's own flash (the torch cannot be used by others meanwhile). */
    fun setTorch(on: Boolean) {
        val b = request ?: return
        handler?.post {
            b.set(CaptureRequest.FLASH_MODE, if (on) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            repeat()
        }
    }

    fun stop() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        session = null
        device = null
        reader = null
        request = null
        thread?.quitSafely()
        thread = null
        handler = null
        log("WIDE camera stopped")
    }

    companion object {
        const val ULTRA_WIDE_ID = "3"
    }
}
