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
     * The largest circle that fits wholly inside a region: a two-pass chamfer
     * distance transform (3-4 weights) to the region's edge, then its peak.
     * @return the centre index and the radius in pixels.
     */
    fun inscribed(inside: BooleanArray, w: Int, h: Int): Pair<Int, Double> {
        val big = 1_000_000
        val dist = IntArray(w * h) { if (inside[it]) big else 0 }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x; if (dist[i] == 0) continue
            var v = dist[i]
            if (x > 0) v = minOf(v, dist[i - 1] + 3)
            if (y > 0) v = minOf(v, dist[i - w] + 3)
            if (x > 0 && y > 0) v = minOf(v, dist[i - w - 1] + 4)
            if (x < w - 1 && y > 0) v = minOf(v, dist[i - w + 1] + 4)
            if (x == 0 || y == 0 || x == w - 1 || y == h - 1) v = minOf(v, 3)
            dist[i] = v
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x; if (dist[i] == 0) continue
            var v = dist[i]
            if (x < w - 1) v = minOf(v, dist[i + 1] + 3)
            if (y < h - 1) v = minOf(v, dist[i + w] + 3)
            if (x < w - 1 && y < h - 1) v = minOf(v, dist[i + w + 1] + 4)
            if (x > 0 && y < h - 1) v = minOf(v, dist[i + w - 1] + 4)
            dist[i] = v
        }
        val top = dist.indices.maxBy { dist[it] }
        return top to dist[top] / 3.0
    }

    /** A white reference and where in the picture it came from. */
    data class WhiteRef(val wb: WhiteBalance, val points: List<NPoint>)

    /**
     * Find something white in the picture without asking: the brightest pixels
     * that are also nearly colourless (a nappy, a tissue, paper).
     *
     * @return the white balance, or null when nothing bright and neutral enough
     *   is in the frame -- the caller then says "put a white tissue in the photo"
     *   rather than pretending.
     */
    fun autoWhite(img0: PixelImage, exclude: NCircle? = null): WhiteBalance? = autoWhiteRef(img0, exclude)?.wb

    /** As [autoWhite], also returning the pixels used, so the screen can show them. */
    fun autoWhiteRef(img0: PixelImage, exclude: NCircle? = null, exposure: Boolean = false): WhiteRef? {
        val img = img0.downscaled(320)
        data class C(val l: Double, val chroma: Double, val argb: Int, val x: Int, val y: Int)
        val candidates = ArrayList<C>()
        val ex = exclude?.let { Triple(it.cx * (img.width - 1), it.cy * (img.height - 1), it.r * minOf(img.width, img.height) * 1.2) }
        for (y in 0 until img.height step 2) for (x in 0 until img.width step 2) {
            if (ex != null) {
                val dx = x - ex.first; val dy = y - ex.second
                if (dx * dx + dy * dy < ex.third * ex.third) continue
            }
            val p = img[x, y]
            if (p.red() >= 252 && p.green() >= 252 && p.blue() >= 252) continue // clipped: no colour info
            val lab = ColorMath.lab(p)
            candidates += C(lab.l, lab.chroma, p, x, y)
        }
        if (candidates.size < 50) return null
        candidates.sortByDescending { it.l }
        // Top 6% brightest, then keep those with modest chroma (a warm bulb can
        // tint white paper to chroma 20-30, which is exactly what we correct).
        val bright = candidates.take((candidates.size * 0.06).toInt().coerceAtLeast(20))
            .filter { it.chroma < 35 && it.l > 55 }
        if (bright.size < 12) return null
        // White is the least coloured of the bright things: a yellow eye white
        // or pale poo can be bright too, and must not be averaged into the
        // reference. Keep the most neutral of the bright pixels.
        val minC = bright.minOf { it.chroma }
        val top = bright.filter { it.chroma <= minC + 8 }.let { if (it.size >= 12) it else bright.sortedBy { c -> c.chroma }.take(12) }
        var r = 0.0; var g = 0.0; var b = 0.0
        for (t in top) {
            r += ColorMath.linear(t.argb.red()); g += ColorMath.linear(t.argb.green()); b += ColorMath.linear(t.argb.blue())
        }
        val n = top.size.toDouble()
        val pts = top.map { NPoint(it.x / (img.width - 1.0).coerceAtLeast(1.0), it.y / (img.height - 1.0).coerceAtLeast(1.0)) }
        return WhiteRef(WhiteBalance.fromReference(doubleArrayOf(r / n, g / n, b / n), exposure), pts)
    }
}
