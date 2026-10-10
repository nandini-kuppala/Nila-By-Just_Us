package com.nila.skin.core.image

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A plain ARGB picture.
 *
 * Every measurement in this module runs on this rather than on an Android
 * Bitmap, so the same code is tested on the JVM with synthetic pictures whose
 * true answer is known, and runs unchanged on the phone.
 */
class PixelImage(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0) { "empty image" }
        require(argb.size == width * height) { "pixel count ${argb.size} != $width x $height" }
    }

    operator fun get(x: Int, y: Int): Int = argb[y * width + x]

    fun contains(x: Int, y: Int) = x in 0 until width && y in 0 until height

    /** Nearest-neighbour downscale so the longest side is at most [maxSide]. */
    fun downscaled(maxSide: Int): PixelImage {
        val longest = max(width, height)
        if (longest <= maxSide) return this
        val s = maxSide.toDouble() / longest
        val w = max(1, (width * s).roundToInt())
        val h = max(1, (height * s).roundToInt())
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val sy = min(height - 1, (y / s).toInt())
            for (x in 0 until w) {
                val sx = min(width - 1, (x / s).toInt())
                out[y * w + x] = argb[sy * width + sx]
            }
        }
        return PixelImage(w, h, out)
    }

    companion object {
        fun filled(width: Int, height: Int, color: Int) =
            PixelImage(width, height, IntArray(width * height) { color })
    }
}

/** A point in normalised picture coordinates, 0..1 on both axes. */
data class NPoint(val x: Double, val y: Double) {
    fun toPixel(img: PixelImage): Pair<Int, Int> =
        (x * (img.width - 1)).roundToInt() to (y * (img.height - 1)).roundToInt()
}

/** A circle in normalised coordinates; the radius is a fraction of the shorter side. */
data class NCircle(val cx: Double, val cy: Double, val r: Double) {
    fun radiusPx(img: PixelImage) = r * min(img.width, img.height)
    fun centrePx(img: PixelImage) = cx * (img.width - 1) to cy * (img.height - 1)
}

fun argb(r: Int, g: Int, b: Int): Int =
    (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

fun Int.red() = (this shr 16) and 0xFF
fun Int.green() = (this shr 8) and 0xFF
fun Int.blue() = this and 0xFF
