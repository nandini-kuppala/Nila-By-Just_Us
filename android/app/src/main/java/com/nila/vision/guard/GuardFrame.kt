package com.nila.vision.guard

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import kotlin.math.ceil

/**
 * One camera frame, from the main lens (CameraX), the ultra-wide lens ([WideCamera]) or a CCTV
 * stream ([CctvSource]), as the guard pipeline needs it. Ported unchanged from Baby Guard CCTV,
 * where the same three sources feed one pipeline.
 */
interface GuardFrame {
    val width: Int
    val height: Int

    /** Clockwise rotation that makes the frame upright on the portrait screen. */
    val rotation: Int

    /** Brightness plane. */
    val yBuffer: ByteBuffer
    val yRowStride: Int
    val yPixelStride: Int

    /** Upright colour picture, at most [maxSide] px on the long side. */
    fun uprightBitmap(maxSide: Int): Bitmap

    fun close()
}

/** A CameraX YUV frame (the main lens). */
class CameraXFrame(private val image: ImageProxy) : GuardFrame {
    override val width get() = image.width
    override val height get() = image.height
    override val rotation get() = image.imageInfo.rotationDegrees
    override val yBuffer: ByteBuffer get() = image.planes[0].buffer
    override val yRowStride get() = image.planes[0].rowStride
    override val yPixelStride get() = image.planes[0].pixelStride

    override fun uprightBitmap(maxSide: Int): Bitmap {
        val raw = image.toBitmap()
        val scale = minOf(1f, maxSide.toFloat() / maxOf(raw.width, raw.height))
        val m = Matrix()
        m.postScale(scale, scale)
        if (rotation != 0) m.postRotate(rotation.toFloat())
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (out !== raw) raw.recycle()
        return out
    }

    override fun close() = image.close()
}

/** A Camera2 YUV_420_888 image as a frame; colour conversion done here (BT.601, full range). */
class Camera2Frame(
    private val image: Image,
    override val rotation: Int,
) : GuardFrame {
    override val width get() = image.width
    override val height get() = image.height
    override val yBuffer: ByteBuffer get() = image.planes[0].buffer
    override val yRowStride get() = image.planes[0].rowStride
    override val yPixelStride get() = image.planes[0].pixelStride

    override fun uprightBitmap(maxSide: Int): Bitmap {
        val step = maxOf(1, ceil(maxOf(width, height).toDouble() / maxSide).toInt())
        val w = width / step
        val h = height / step
        val yP = image.planes[0]
        val uP = image.planes[1]
        val vP = image.planes[2]
        val yb = yP.buffer
        val ub = uP.buffer
        val vb = vP.buffer
        val px = IntArray(w * h)
        for (j in 0 until h) {
            val sy = j * step
            val yRow = sy * yP.rowStride
            val uvRow = (sy / 2) * uP.rowStride
            val vRow = (sy / 2) * vP.rowStride
            for (i in 0 until w) {
                val sx = i * step
                val y = (yb.get(yRow + sx * yP.pixelStride).toInt() and 0xFF)
                val u = (ub.get(uvRow + (sx / 2) * uP.pixelStride).toInt() and 0xFF) - 128
                val v = (vb.get(vRow + (sx / 2) * vP.pixelStride).toInt() and 0xFF) - 128
                val r = (y + 1.402f * v).toInt().coerceIn(0, 255)
                val g = (y - 0.344f * u - 0.714f * v).toInt().coerceIn(0, 255)
                val b = (y + 1.772f * u).toInt().coerceIn(0, 255)
                px[j * w + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        if (rotation == 0) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, w, h, Matrix().apply { postRotate(rotation.toFloat()) }, true)
    }

    override fun close() = image.close()
}

/** A decoded CCTV picture as a frame: already upright; the brightness plane is computed from the colours. */
class BitmapFrame(
    private val bmp: Bitmap,
    private val onClose: () -> Unit = {},
) : GuardFrame {
    override val width get() = bmp.width
    override val height get() = bmp.height
    override val rotation = 0
    private val pixels: IntArray by lazy { IntArray(width * height).also { bmp.getPixels(it, 0, width, 0, 0, width, height) } }
    override val yBuffer: ByteBuffer by lazy {
        val b = ByteBuffer.allocate(width * height)
        for (c in pixels) b.put((((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000).toByte())
        b.rewind()
        b
    }
    override val yRowStride get() = width
    override val yPixelStride = 1

    override fun uprightBitmap(maxSide: Int): Bitmap {
        val s = minOf(1f, maxSide.toFloat() / maxOf(width, height))
        return if (s >= 1f) bmp.copy(Bitmap.Config.ARGB_8888, false)
        else Bitmap.createScaledBitmap(bmp, (width * s).toInt(), (height * s).toInt(), true)
    }

    override fun close() = onClose()
}
