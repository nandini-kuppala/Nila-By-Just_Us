package com.nila.skin.core.measure

import com.nila.skin.core.image.PixelImage
import com.nila.skin.core.image.argb
import kotlin.math.abs

/**
 * What the texture measure sees, as a picture: fine detail (|Laplacian| of
 * brightness, lightly smoothed) mapped from dark blue (smooth) through teal to
 * yellow (rough). Scales, crusts and bumps light up; smooth skin stays dark.
 */
object TextureMap {

    fun of(photo: PixelImage, maxSide: Int = 360): PixelImage {
        val img = photo.downscaled(maxSide)
        val w = img.width; val h = img.height
        val grey = DoubleArray(w * h) { val p = img.argb[it]; 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF) }
        val lap = DoubleArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            lap[i] = abs(4 * grey[i] - grey[i - 1] - grey[i + 1] - grey[i - w] - grey[i + w])
        }
        // 3x3 box smoothing so single noisy pixels don't dominate.
        val sm = DoubleArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            var s = 0.0
            for (dy in -1..1) for (dx in -1..1) s += lap[(y + dy) * w + x + dx]
            sm[y * w + x] = s / 9
        }
        val sorted = sm.sorted()
        val top = sorted[(sorted.size * 0.97).toInt().coerceAtMost(sorted.size - 1)].coerceAtLeast(1.0)
        val out = IntArray(w * h) { i -> colour((sm[i] / top).coerceIn(0.0, 1.0)) }
        return PixelImage(w, h, out)
    }

    /** Dark blue -> teal -> yellow. */
    private fun colour(t: Double): Int {
        val stops = listOf(Triple(16, 24, 48), Triple(15, 140, 130), Triple(250, 220, 90))
        val (a, b, u) = if (t < 0.5) Triple(stops[0], stops[1], t / 0.5) else Triple(stops[1], stops[2], (t - 0.5) / 0.5)
        return argb((a.first + (b.first - a.first) * u).toInt(), (a.second + (b.second - a.second) * u).toInt(), (a.third + (b.third - a.third) * u).toInt())
    }
}
