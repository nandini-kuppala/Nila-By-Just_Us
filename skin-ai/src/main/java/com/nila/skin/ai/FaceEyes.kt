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
import com.nila.skin.core.image.NPoint
import com.nila.skin.core.measure.EyeAngle
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

    /** Pupils in photo order, plus where the baby was looking (for the off-angle note). */
    data class Eyes(val left: NCircle, val right: NCircle, val faceFound: Boolean, val angle: EyeAngle? = null)

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
        fun pt(i: Int) = NPoint(face[i].x().toDouble(), face[i].y().toDouble())
        // Eye corners: 33/133 round one eye, 362/263 round the other. Pair each
        // set with the iris nearest to it rather than trusting left/right names.
        val setA = pt(33) to pt(133); val setB = pt(362) to pt(263)
        val midA = (setA.first.x + setA.second.x) / 2
        val (leftCorners, rightCorners) = if (kotlin.math.abs(midA - photoLeft.cx) <= kotlin.math.abs(midA - photoRight.cx)) setA to setB else setB to setA
        val angle = runCatching {
            EyeAngle.of(NPoint(photoLeft.cx, photoLeft.cy), leftCorners.first, leftCorners.second,
                NPoint(photoRight.cx, photoRight.cy), rightCorners.first, rightCorners.second, pt(1), w / h)
        }.getOrNull()
        return Eyes(photoLeft, photoRight, true, angle)
    }

    /**
     * A circle on the white of an eye, from face landmarks: between the iris
     * edge and an eye corner, no bigger than fits between the lids. Of the four
     * places (two eyes, two corners each) the roomiest wins. Null without a face.
     */
    fun sclera(bitmap: Bitmap): NCircle? {
        val lm = get() ?: return null
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val face = runCatching { lm.detect(BitmapImageBuilder(argb).build()) }.getOrNull()?.faceLandmarks()?.firstOrNull() ?: return null
        if (face.size < 478) return null
        val w = bitmap.width.toDouble(); val h = bitmap.height.toDouble(); val short = min(w, h)
        fun px(i: Int) = face[i].x() * w to face[i].y() * h
        // iris centre, iris ring, corners, upper lid, lower lid
        val eyes = listOf(Triple(468, intArrayOf(33, 133), intArrayOf(159, 145)), Triple(473, intArrayOf(362, 263), intArrayOf(386, 374)))
        var best: NCircle? = null; var bestR = 0.0
        for ((iris, corners, lids) in eyes) {
            val (cx, cy) = px(iris)
            val ri = (1..4).map { k -> val (x, y) = px(iris + k); hypot(x - cx, y - cy) }.average()
            val lidGap = hypot(px(lids[0]).first - px(lids[1]).first, px(lids[0]).second - px(lids[1]).second)
            for (c in corners) {
                val (kx, ky) = px(c)
                val d = hypot(kx - cx, ky - cy)
                val gap = d - ri
                if (gap <= 2) continue
                val t = (ri + gap * 0.45) / d
                val r = minOf(gap * 0.3, lidGap * 0.28)
                if (r > bestR) { bestR = r; best = NCircle((cx + (kx - cx) * t) / w, (cy + (ky - cy) * t) / h, r / short) }
            }
        }
        return best?.takeIf { bestR >= 2.0 }
    }

    fun close() { runCatching { landmarker?.close() }; landmarker = null }
}
