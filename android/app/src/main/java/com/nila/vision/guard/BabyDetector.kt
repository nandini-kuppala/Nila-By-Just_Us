package com.nila.vision.guard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/**
 * Finds the baby (COCO "person") with MediaPipe EfficientDet-Lite0, on the GPU, else the CPU. The NPU stays
 * with the vision model. Ported unchanged from Baby Guard CCTV.
 *
 * Chosen by test: on the 18 baby test photos EfficientDet-Lite0 found the baby in 18/18 (pose landmarks
 * 17/18, faces 16/18); on the same photos darkened and night-enhanced 13/18 (pose 9/18). The tracker
 * covers the misses with motion.
 */
class BabyDetector(
    context: Context,
    private val log: (String) -> Unit,
) {
    data class Hit(
        /** Normalised 0..1 in the upright frame. */
        val box: RectF,
        val score: Float,
    )

    private var detector: ObjectDetector? = null
    var backend = "none"
        private set

    init {
        for (delegate in listOf(Delegate.GPU, Delegate.CPU)) {
            try {
                val options =
                    ObjectDetector.ObjectDetectorOptions
                        .builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).setDelegate(delegate).build())
                        .setRunningMode(RunningMode.IMAGE)
                        .setScoreThreshold(MIN_SCORE)
                        .setMaxResults(8)
                        // No setCategoryAllowlist: with this model it aborts MediaPipe 0.10.18
                        // (tensors_to_detections_calculator "Only all classes >= class 0"). Filter below.
                        .build()
                detector = ObjectDetector.createFromOptions(context, options)
                backend = delegate.name.lowercase()
                break
            } catch (e: Throwable) {
                log("DETECTOR ${delegate.name} failed: ${e.message}")
            }
        }
        log("DETECTOR ready=${detector != null} backend=$backend model=$MODEL")
    }

    val ready get() = detector != null

    fun detect(bmp: Bitmap): List<Hit> {
        val d = detector ?: return emptyList()
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        return d.detect(BitmapImageBuilder(bmp).build()).detections().filter { det ->
            det.categories().firstOrNull()?.categoryName() == "person"
        }.map { det ->
            val b = det.boundingBox()
            Hit(
                RectF((b.left / w).coerceIn(0f, 1f), (b.top / h).coerceIn(0f, 1f),
                      (b.right / w).coerceIn(0f, 1f), (b.bottom / h).coerceIn(0f, 1f)),
                det.categories().firstOrNull()?.score() ?: 0f,
            )
        }
    }

    fun close() {
        detector?.close()
        detector = null
    }

    companion object {
        private const val MODEL = "efficientdet_lite0.tflite"
        private const val MIN_SCORE = 0.3f
    }
}
