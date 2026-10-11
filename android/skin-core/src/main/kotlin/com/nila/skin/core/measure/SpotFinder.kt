package com.nila.skin.core.measure

import com.nila.skin.core.color.ColorMath
import com.nila.skin.core.image.LabPlane
import com.nila.skin.core.image.NCircle
import com.nila.skin.core.image.PixelImage
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where is the spot? A first guess for the circle, so the parent adjusts
 * rather than places it.
 *
 * Finds the largest connected area that differs clearly from the photo's
 * typical skin colour (a robust plane fit, so uneven light is not a spot),
 * weighting redness, and returns a circle
 * that covers it with a margin. Returns null when nothing stands out -- the
 * screen then keeps the centred default.
 */
object SpotFinder {

    /**
     * A suggestion is only made when it passes three checks; otherwise null,
     * and the parent taps the spot. A confidently wrong circle is worse than
     * none.
     */
    fun suggest(photo: PixelImage): NCircle? {
        val c = candidate(photo) ?: return null
        return c.takeIf { confident(photo, it) }
    }

    /** The ring is mostly skin, the circle is not jammed at the edge, and the measurement shows a clear spot. */
    fun confident(photo: PixelImage, c: NCircle): Boolean {
        if (c.cx < 0.1 || c.cx > 0.9 || c.cy < 0.1 || c.cy > 0.9) return false
        val img = photo.downscaled(200)
        var ring = 0; var skin = 0
        com.nila.skin.core.image.Regions.forEachInRing(img, c, 1.7, 2) { x, y -> ring++; if (skinLike(ColorMath.lab(img[x, y]))) skin++ }
        if (ring == 0 || skin < ring * 0.7) return false
        val r = SkinMeasure.measure(photo, c)
        return r.coverage in 0.06..0.85 && r.spots in 1..12 && (r.deltaA >= 4.5 || r.deltaL <= -6 || r.deltaL >= 6)
    }

    private fun candidate(photo: PixelImage): NCircle? {
        val img = photo.downscaled(200)
        val w = img.width; val h = img.height
        val labs = Array(w * h) { ColorMath.lab(img.argb[it]) }
        // Real photos have sheets, nappies and hands in them. The body is the
        // skin-coloured pixels plus anything they enclose (a spot inside the
        // skin is part of the body even when it is not skin-coloured itself);
        // only the body is searched.
        val body = body(labs, w, h)
        if (body.count { it } < w * h / 20) return null
        // Typical skin as a gentle slope across the photo (uneven window light),
        // fitted on every third skin pixel with outliers -- the spot itself -- trimmed.
        val pick = labs.indices.filter { it % 3 == 0 && body[it] && skinLike(labs[it]) }.ifEmpty { labs.indices.filter { it % 3 == 0 } }
        val plane = LabPlane.fit(
            DoubleArray(pick.size) { (pick[it] % w).toDouble() }, DoubleArray(pick.size) { (pick[it] / w).toDouble() },
            pick.map { labs[it] }, w / 2.0, h / 2.0, min(w, h) / 2.0,
        )
        // Clearly redder than this skin (a* beyond 3x its own spread), or clearly
        // different overall: the same two tests as the measurement itself.
        val t = max(9.0, 3.0 * plane.spread)
        val tA = max(5.0, 3.0 * plane.spreadA)
        val m = BooleanArray(w * h) { i ->
            val l = labs[i]
            val e = plane.at((i % w).toDouble(), (i / w).toDouble())
            body[i] && (ColorMath.deltaE76(l, e) > t || l.a - e.a > tA)
        }
        // Ignore a thin border: edges of the frame are often background.
        val bx = (w * 0.04).toInt(); val by = (h * 0.04).toInt()
        for (y in 0 until h) for (x in 0 until w) if (x < bx || y < by || x >= w - bx || y >= h - by) m[y * w + x] = false
        val comps = SkinMeasure.components(m, w, h, max(8, w * h / 800))
        if (comps.isEmpty()) return null
        // The main spot is big and, as parents frame photos, near the middle.
        // Components near it are merged: scattered spots belong together.
        val far = kotlin.math.hypot(w / 2.0, h / 2.0)
        val biggest = comps.maxBy { c -> val (x, y) = centroid(c, w); c.size * (1 - 0.6 * kotlin.math.hypot(x - w / 2.0, y - h / 2.0) / far) }
        val (bcx, bcy) = centroid(biggest, w)
        val near = comps.filter { c -> val (cx, cy) = centroid(c, w); sqrt((cx - bcx) * (cx - bcx) + (cy - bcy) * (cy - bcy)) < min(w, h) * 0.3 }
        val all = near.flatMap { it.asIterable() }
        if (all.size > w * h * 0.6) return null // "everything differs": no single spot
        var sx = 0.0; var sy = 0.0
        all.forEach { sx += it % w; sy += it / w }
        val cx = sx / all.size; val cy = sy / all.size
        var rMax = 0.0
        val dists = all.map { val dx = it % w - cx; val dy = it / w - cy; sqrt(dx * dx + dy * dy) }.sorted()
        rMax = dists[(dists.size * 0.95).toInt().coerceAtMost(dists.size - 1)]
        val rArea = sqrt(all.size / PI)
        val r = max(rMax, rArea) * 1.15 + 3
        val short = min(w, h).toDouble()
        return NCircle(cx / (w - 1), cy / (h - 1), (r / short).coerceIn(0.05, 0.42))
    }

    /** Skin of any tone in CIELAB: warm hue (a*, b* both positive), moderate chroma. */
    fun skinLike(l: com.nila.skin.core.color.Lab): Boolean =
        l.a > 2 && l.b > 2 && l.l in 18.0..95.0 && l.chroma in 6.0..70.0 && l.hue in 10.0..90.0

    /** Skin pixels plus the non-skin pixels they enclose (not reachable from the frame's edge). */
    fun body(labs: Array<com.nila.skin.core.color.Lab>, w: Int, h: Int): BooleanArray {
        val skin = BooleanArray(w * h) { skinLike(labs[it]) }
        val outside = BooleanArray(w * h)
        val stack = IntArray(w * h); var sp = 0
        fun push(i: Int) { if (!skin[i] && !outside[i]) { outside[i] = true; stack[sp++] = i } }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (sp > 0) {
            val i = stack[--sp]; val x = i % w; val y = i / w
            if (x > 0) push(i - 1); if (x < w - 1) push(i + 1); if (y > 0) push(i - w); if (y < h - 1) push(i + w)
        }
        return BooleanArray(w * h) { !outside[it] }
    }

    private fun centroid(c: IntArray, w: Int): Pair<Double, Double> {
        var sx = 0.0; var sy = 0.0
        c.forEach { sx += it % w; sy += it / w }
        return sx / c.size to sy / c.size
    }

}
