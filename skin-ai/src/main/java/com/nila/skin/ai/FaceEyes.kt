package com.nila.skin.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.nila.skin.core.image.NCircle
import kotlin.math.hypot
import kotlin.math.min

/**
 * Finds both pupils in a photo with MediaPipe Face Landmarker (478 points,
 * including 5 per iris), on the GPU with a CPU fallback.
 *
 * The iris landmarks give the pupil centre and the iris radius; the pupil is
 * taken as the inner half of the iris, which is where a flash reflex shows.
 * When no face is found -- a baby turned away, a very close crop -- the screen
 * falls back to the parent tapping each pupil.
 */
class FaceEyes(private val context: Context) {

    data class Eyes(val left: NCircle, val right: NCircle, val faceFound: Boolean)

    private var landmarker: FaceLandmarker? = null
    var delegate: String = "none"
        private set

    private fun get(): FaceLandmarker? {
        landmarker?.let { return it }
        for (d in listOf(Delegate.GPU, Delegate.CPU)) {
            val made = runCatching {
                FaceLandmarker.createFromOptions(context, FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").setDelegate(d).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumFaces(1)
                    .setMinFaceDetectionConfidence(0.3f)
                    .setMinFacePresenceConfidence(0.3f)
                    .build())
            }.onFailure { Log.i("FaceEyes", "$d delegate failed: ${it.message}") }.getOrNull()
            if (made != null) { landmarker = made; delegate = d.name; return made }
        }
        return null
    }

    fun find(bitmap: Bitmap): Eyes? {
        val lm = get() ?: return null
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val result = runCatching { lm.detect(BitmapImageBuilder(argb).build()) }.getOrNull() ?: return null
        val face = result.faceLandmarks().firstOrNull() ?: return null
        if (face.size < 478) return null
        val w = bitmap.width.toDouble(); val h = bitmap.height.toDouble()
        val short = min(w, h)
        fun iris(center: Int): NCircle {
            val c = face[center]
            val cx = c.x() * w; val cy = c.y() * h
            val r = (1..4).map { k -> val p = face[center + k]; hypot(p.x() * w - cx, p.y() * h - cy) }.average()
            return NCircle(c.x().toDouble(), c.y().toDouble(), (r * 0.5) / short)
        }
        // 468-472 is the subject's right iris (left in the photo), 473-477 the subject's left.
        val a = iris(468); val b = iris(473)
        val (photoLeft, photoRight) = if (a.cx <= b.cx) a to b else b to a
        return Eyes(photoLeft, photoRight, true)
    }

    fun close() { runCatching { landmarker?.close() }; landmarker = null }
}
