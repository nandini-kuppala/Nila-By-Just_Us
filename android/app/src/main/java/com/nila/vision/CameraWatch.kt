package com.nila.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Drives the camera lane: CameraX frames into [FaceWatcher], results back out.
 *
 * Analysis is throttled to roughly 5 fps rather than run at the camera's native
 * rate. Face presence and motion energy do not get meaningfully better above
 * that, and the difference over an eight-hour night is the difference between a
 * phone that is still watching in the morning and one that is flat.
 */
class CameraWatch(
    private val context: Context,
    private val onState: (Frame) -> Unit,
) {

    /**
     * Everything one analysed frame produced.
     *
     * One callback carrying all of it rather than three, because the rules
     * combine them: "no face" means something different depending on whether
     * there is a body in frame and whether it is flat, and a caller that
     * receives those separately has to reassemble the frame they came from.
     */
    data class Frame(
        val face: FaceWatcher.State,
        val motion: MotionEnergy.Reading,
        val pose: PoseReading,
    )
    /** The last few seconds, kept in memory and only written when a rule fires. */
    val preRoll = PreRoll(context)

    companion object {
        private const val TAG = "CameraWatch"
        private const val TARGET_INTERVAL_MS = 200L      // ~5 fps
        /**
         * Wide enough for both models.
         *
         * 480 was chosen for BlazeFace alone. The pose landmarker wants more of
         * the body resolved than that -- a baby occupying a third of the frame
         * has a torso about 60 px tall at 480, which is where landmark noise
         * starts to swamp the trunk angle. 640 costs about a millisecond more
         * per frame at 5 fps.
         */
        private const val ANALYSIS_WIDTH = 640
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val lastAnalysedAt = AtomicLong(0)
    private var watcher: FaceWatcher? = null
    private var pose: PoseWatcher? = null
    private var provider: ProcessCameraProvider? = null

    /**
     * The bound camera, kept for one reason: the torch.
     *
     * CameraX owns the camera while the watch is running, so the torch cannot be
     * driven through `CameraManager` -- that call is refused while another
     * client holds the device. The control that does work is the one belonging
     * to the binding, which is this.
     */
    private var camera: androidx.camera.core.Camera? = null

    /** Dark-room processing, shared so its frame average survives between frames. */
    val nightVision = NightVision()

    /**
     * Mean brightness of the most recent analysed frame, 0..255.
     *
     * Measured here rather than in the service because this is where the bitmap
     * already exists; [DayNight] reads it as the second opinion to the phone's
     * light sensor, which faces the ceiling while the camera faces the cot.
     */
    @Volatile var lastLuma: Float = 0f; private set

    /** Which delegate the pose model landed on, for the settings screen. */
    val poseDelegate: String get() = pose?.delegateName ?: "-"
    val poseAvailable: Boolean get() = pose?.isAvailable == true

    @Volatile var framesAnalysed: Long = 0; private set
    @Volatile var lastError: String? = null; private set
    @Volatile var lastPreRoll: java.io.File? = null; private set
    @Volatile private var lastDump: Long = 0L

    /**
     * Build the two models, off the main thread, exactly once.
     *
     * Between them this is a MediaPipe face detector, a 9.2 MB pose landmarker,
     * and a real probe inference through each to check the delegate actually
     * works -- one to two seconds on a good day and far longer on a cold start.
     * It used to run on the main thread *before* the camera was bound, which
     * is both halves of the bug it caused: the UI could not draw, and there was
     * nothing to draw yet anyway.
     */
    @Synchronized
    private fun ensureWatchers() {
        if (watcher == null) watcher = FaceWatcher(context)
        if (pose == null) pose = PoseWatcher(context)
    }

    /**
     * Bind the camera first, load the models behind it.
     *
     * The preview is the thing somebody is waiting to see, and it needs no
     * model to start -- so it is bound immediately and the detectors are built
     * afterwards on the analysis executor. [analyse] already drops frames while
     * they are null, which for the second or two of loading means a live
     * picture with the assessment card still saying it is warming up. Before
     * this the screen was simply black until both models had loaded.
     */
    fun start(owner: LifecycleOwner, preview: Preview?) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { it.setAnalyzer(executor, ::analyse) }

                cameraProvider.unbindAll()
                val useCases = listOfNotNull(preview, analysis).toTypedArray()
                camera = cameraProvider.bindToLifecycle(
                    owner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases
                )
                Log.i(TAG, "camera bound")
                // Now the picture is live, load what reads it.
                executor.execute {
                    val started = System.currentTimeMillis()
                    runCatching { ensureWatchers() }
                        .onFailure { Log.e(TAG, "detectors failed to load", it) }
                    Log.i(TAG, "detectors ready in " +
                        "${System.currentTimeMillis() - started}ms")
                }
            } catch (t: Throwable) {
                lastError = t.message ?: t::class.java.simpleName
                Log.e(TAG, "camera failed to start", t)
                onState(
                    Frame(
                        FaceWatcher.State.Unavailable(lastError!!),
                        MotionEnergy.Reading(0f, 0f, 1f),
                        PoseReading.ABSENT,
                    )
                )
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * The most recent analysed frame, for the vision model.
     *
     * Held as a single copy rather than a queue: the describer takes seconds,
     * and by the time it finishes the interesting frame is the one it started
     * with, not whichever arrived while it was thinking.
     */
    @Volatile private var latestFrame: Bitmap? = null

    /** A copy of the last frame, or null if none has been analysed yet. */
    fun snapshot(): Bitmap? = latestFrame?.copy(Bitmap.Config.ARGB_8888, false)

    /**
     * The last frame, lifted for a dark room when the lane is in night mode.
     *
     * The caller owns the result either way. In [LightMode.DAY] this is exactly
     * [snapshot]; in [LightMode.NIGHT] it is the enhanced copy, which is what
     * the vision model is given -- a model handed the raw frame of an unlit
     * nursery answers about a black rectangle.
     */
    fun snapshotFor(mode: LightMode): Bitmap? {
        val raw = snapshot() ?: return null
        if (mode == LightMode.DAY) return raw
        return try {
            nightVision.enhance(raw).also { raw.recycle() }
        } catch (t: Throwable) {
            Log.w(TAG, "night enhancement failed; using the raw frame", t)
            raw
        }
    }

    /** A night-vision rendering of the last frame, for the screen. Caller owns it. */
    fun nightView(): Bitmap? {
        val raw = snapshot() ?: return null
        return try {
            nightVision.nightView(raw).also { raw.recycle() }
        } catch (t: Throwable) {
            Log.w(TAG, "night view failed", t)
            raw.recycle()
            null
        }
    }

    /**
     * Pulse the torch, through the binding that owns the camera.
     *
     * Returns false when there is no camera bound or the device has no flash, so
     * the caller can report what actually happened rather than assume.
     */
    fun torch(on: Boolean): Boolean {
        val control = camera ?: return false
        if (control.cameraInfo.hasFlashUnit().not()) return false
        return runCatching { control.cameraControl.enableTorch(on) }.isSuccess
    }

    val hasTorch: Boolean get() = camera?.cameraInfo?.hasFlashUnit() == true

    /**
     * Put one bitmap through the pipeline as if the camera had produced it.
     *
     * Used by the demo footage. It is the same call `analyse` makes, so the
     * face detector, the motion comparison and the escalation rules cannot
     * tell the difference -- and neither can anyone reviewing the demo.
     */
    fun offerFrame(bitmap: Bitmap) {
        val w = watcher ?: return
        val state = w.analyse(bitmap)
        val reading = pose?.analyse(bitmap) ?: PoseReading.ABSENT
        framesAnalysed++
        latestFrame?.recycle()
        latestFrame = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        onState(Frame(state, w.lastMotion, reading))
    }

    /**
     * Start the watcher without binding a camera.
     *
     * The demo needs the face detector and the motion baseline, and needs the
     * camera to stay off -- on an emulator it would otherwise show a rendered
     * living room underneath a video of a baby.
     */
    fun startHeadless() = ensureWatchers()

    private fun analyse(proxy: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastAnalysedAt.get() < TARGET_INTERVAL_MS) return
            lastAnalysedAt.set(now)

            val watcher = watcher ?: return
            val bitmap = downscale(proxy.toBitmap(), proxy.imageInfo.rotationDegrees)
            val state = watcher.analyse(bitmap)
            // Pose after face, on the same bitmap, on this same background
            // executor. Two models per frame at 5 fps rather than one; both are
            // small, and running them on the same frame is what lets "no face"
            // and "flat torso" be combined into "rolled onto their front".
            val reading = pose?.analyse(bitmap) ?: PoseReading.ABSENT
            framesAnalysed++

            // One sampled pass for brightness, while the bitmap is here. Every
            // eighth pixel in both directions, so it costs a fraction of a
            // millisecond and still answers the only question asked of it: is
            // this a picture of a lit room or of a dark one.
            lastLuma = nightVision.meanLuma(bitmap)

            // Buffered before the frame is released, so when a rule fires the
            // seconds leading up to it are already in hand.
            preRoll.offer(bitmap, now)

            // And one kept aside for the vision model, which is asked to look
            // on demand rather than per frame. A copy, because the analysis
            // bitmap is recycled at the end of this block and the describer
            // runs on another thread seconds later.
            latestFrame?.recycle()
            latestFrame = bitmap.copy(Bitmap.Config.ARGB_8888, false)

            bitmap.recycle()

            if (state is FaceWatcher.State.FaceMissing && lastDump == 0L) {
                lastDump = now
                lastPreRoll = preRoll.dump("face_missing")
            } else if (state !is FaceWatcher.State.FaceMissing) {
                lastDump = 0L
            }

            onState(Frame(state, watcher.lastMotion, reading))
        } catch (t: Throwable) {
            lastError = t.message
            Log.w(TAG, "frame analysis failed", t)
        } finally {
            // Must always close, or the pipeline stalls after a few frames.
            proxy.close()
        }
    }

    /** Rotate upright and shrink -- BlazeFace gains nothing from a 4K frame. */
    private fun downscale(source: Bitmap, rotationDegrees: Int): Bitmap {
        val scale = ANALYSIS_WIDTH.toFloat() / source.width.coerceAtLeast(1)
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height,
                                   matrix, true)
    }

    fun stop() {
        preRoll.clear()
        // The torch belongs to the binding that is about to go away; leaving it
        // lit would strand it on until something else bound the camera.
        runCatching { camera?.cameraControl?.enableTorch(false) }
        runCatching { provider?.unbindAll() }
        runCatching { watcher?.close() }
        runCatching { pose?.close() }
        nightVision.reset()
        watcher = null
        pose = null
        provider = null
        camera = null
    }

    fun shutdown() {
        latestFrame?.recycle()
        latestFrame = null
        stop()
        executor.shutdown()
    }
}
