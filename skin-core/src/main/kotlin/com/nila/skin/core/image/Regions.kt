package com.nila.skin.core.image

import com.nila.skin.core.color.ColorMath
import com.nila.skin.core.color.Lab
import com.nila.skin.core.color.WhiteBalance

/** Robust statistics over a set of Lab samples. */
data class LabStats(val median: Lab, val madL: Double, val madA: Double, val madB: Double, val count: Int)

object Regions {

    /** Pixels inside a circle, sampled every [step] pixels. */
    inline fun forEachInCircle(img: PixelImage, c: NCircle, step: Int = 1, block: (x: Int, y: Int) -> Unit) {
        val (cx, cy) = c.centrePx(img)
        val r = c.radiusPx(img)
        val r2 = r * r
        val x0 = (cx - r).toInt().coerceAtLeast(0); val x1 = (cx + r).toInt().coerceAtMost(img.width - 1)
        val y0 = (cy - r).toInt().coerceAtLeast(0); val y1 = (cy + r).toInt().coerceAtMost(img.height - 1)
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val dx = x - cx; val dy = y - cy
                if (dx * dx + dy * dy <= r2) block(x, y)
                x += step
            }
            y += step
        }
    }

    /** Pixels in the ring between [inner] and [outerScale] times its radius. */
    inline fun forEachInRing(img: PixelImage, inner: NCircle, outerScale: Double, step: Int = 1, block: (x: Int, y: Int) -> Unit) {
        val (cx, cy) = inner.centrePx(img)
        val r = inner.radiusPx(img)
        val ro = r * outerScale
        val r2 = r * r; val ro2 = ro * ro
        val x0 = (cx - ro).toInt().coerceAtLeast(0); val x1 = (cx + ro).toInt().coerceAtMost(img.width - 1)
        val y0 = (cy - ro).toInt().coerceAtLeast(0); val y1 = (cy + ro).toInt().coerceAtMost(img.height - 1)
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val dx = x - cx; val dy = y - cy
                val d2 = dx * dx + dy * dy
                if (d2 > r2 && d2 <= ro2) block(x, y)
                x += step
            }
            y += step
        }
    }

    fun stats(samples: List<Lab>): LabStats {
        require(samples.isNotEmpty()) { "no samples" }
        val ls = samples.map { it.l }.sorted()
        val asx = samples.map { it.a }.sorted()
        val bs = samples.map { it.b }.sorted()
        val m = Lab(med(ls), med(asx), med(bs))
        return LabStats(
            m,
            med(samples.map { kotlin.math.abs(it.l - m.l) }.sorted()),
            med(samples.map { kotlin.math.abs(it.a - m.a) }.sorted()),
            med(samples.map { kotlin.math.abs(it.b - m.b) }.sorted()),
            samples.size,
        )
    }

    fun med(sorted: List<Double>): Double {
        if (sorted.isEmpty()) return 0.0
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }

    fun circleStats(img: PixelImage, c: NCircle, wb: WhiteBalance = WhiteBalance.NONE, step: Int = 1): LabStats {
        val out = ArrayList<Lab>()
        forEachInCircle(img, c, step) { x, y -> out += wb.lab(img[x, y]) }
        return stats(out)
    }

    /** Mean linear RGB inside a circle: what a white reference is made of. */
    fun meanLinear(img: PixelImage, c: NCircle): DoubleArray {
        var r = 0.0; var g = 0.0; var b = 0.0; var n = 0
        forEachInCircle(img, c, 1) { x, y ->
            val p = img[x, y]
            r += ColorMath.linear(p.red()); g += ColorMath.linear(p.green()); b += ColorMath.linear(p.blue()); n++
        }
        return if (n == 0) doubleArrayOf(1.0, 1.0, 1.0) else doubleArrayOf(r / n, g / n, b / n)
    }

    /**
     * Find something white in the picture without asking: the brightest pixels
     * that are also nearly colourless (a nappy, a tissue, paper).
     *
     * @return the white balance, or null when nothing bright and neutral enough
     *   is in the frame -- the caller then says "put a white tissue in the photo"
     *   rather than pretending.
     */
    fun autoWhite(img0: PixelImage, exclude: NCircle? = null): WhiteBalance? {
        val img = img0.downscaled(320)
        val candidates = ArrayList<Triple<Double, Double, Int>>() // L, chroma, argb
        val ex = exclude?.let { Triple(it.cx * (img.width - 1), it.cy * (img.height - 1), it.r * minOf(img.width, img.height) * 1.2) }
        for (y in 0 until img.height step 2) for (x in 0 until img.width step 2) {
            if (ex != null) {
                val dx = x - ex.first; val dy = y - ex.second
                if (dx * dx + dy * dy < ex.third * ex.third) continue
            }
            val p = img[x, y]
            if (p.red() >= 252 && p.green() >= 252 && p.blue() >= 252) continue // clipped: no colour info
            val lab = ColorMath.lab(p)
            candidates += Triple(lab.l, lab.chroma, p)
        }
        if (candidates.size < 50) return null
        candidates.sortByDescending { it.first }
        // Top 6% brightest, then keep those with modest chroma (a warm bulb can
        // tint white paper to chroma 20-30, which is exactly what we correct).
        val top = candidates.take((candidates.size * 0.06).toInt().coerceAtLeast(20))
            .filter { it.second < 35 && it.first > 55 }
        if (top.size < 12) return null
        var r = 0.0; var g = 0.0; var b = 0.0
        for (t in top) {
            r += ColorMath.linear(t.third.red()); g += ColorMath.linear(t.third.green()); b += ColorMath.linear(t.third.blue())
        }
        val n = top.size.toDouble()
        return WhiteBalance.fromReference(doubleArrayOf(r / n, g / n, b / n))
    }
}
