package com.nila.vision.guard

import android.graphics.Bitmap
import kotlin.math.pow

/**
 * Software night enhancement for the picture the vision model is shown: light denoise, auto levels and
 * gamma, colours kept (the model can only say "a red cable" if red survives). The same algorithm as Baby
 * Guard CCTV's NightEnhancer, run on a bitmap instead of a JPEG file.
 */
object NightEnhancer {
    fun enhance(bmp: Bitmap): Bitmap {
        val w = bmp.width
        val h = bmp.height
        var px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        val hist = IntArray(256)
        var sum = 0L
        for (c in px) {
            val y = luma(c)
            hist[y]++
            sum += y
        }
        val mean = sum.toDouble() / px.size
        if (mean < 50) px = boxBlur3(px, w, h) // very dark photos are mostly noise: smooth first
        val lo = percentile(hist, px.size * 0.005)
        var hi = percentile(hist, px.size * 0.995)
        if (hi < lo + 12) hi = lo + 12
        val gamma =
            when {
                mean < 40 -> 0.5
                mean < 90 -> 0.7
                else -> 1.0
            }
        val lut =
            IntArray(256) { v ->
                val t = ((v - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)
                (t.toDouble().pow(gamma) * 255).toInt()
            }
        for (i in px.indices) {
            val c = px[i]
            val y = luma(c)
            val gain = (lut[y].toFloat() / maxOf(y, 1)).coerceAtMost(MAX_GAIN)
            val r = ((c shr 16 and 255) * gain).toInt().coerceIn(0, 255)
            val g = ((c shr 8 and 255) * gain).toInt().coerceIn(0, 255)
            val b = ((c and 255) * gain).toInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun luma(c: Int) = ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000

    private fun percentile(hist: IntArray, target: Double): Int {
        var cumulative = 0
        for (i in hist.indices) {
            cumulative += hist[i]
            if (cumulative >= target) return i
        }
        return 255
    }

    private fun boxBlur3(px: IntArray, w: Int, h: Int): IntArray {
        val out = px.copyOf()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                var r = 0
                var g = 0
                var b = 0
                for (dy in -1..1) {
                    val row = (y + dy) * w
                    for (dx in -1..1) {
                        val c = px[row + x + dx]
                        r += c shr 16 and 255
                        g += c shr 8 and 255
                        b += c and 255
                    }
                }
                out[y * w + x] = (0xFF shl 24) or ((r / 9) shl 16) or ((g / 9) shl 8) or (b / 9)
            }
        }
        return out
    }

    private const val MAX_GAIN = 12f
}
