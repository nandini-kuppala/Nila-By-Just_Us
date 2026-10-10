package com.nila.skin.core.measure

import com.nila.skin.core.color.ColorMath
import com.nila.skin.core.image.NCircle
import com.nila.skin.core.image.PixelImage
import com.nila.skin.core.image.Regions
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where is the spot? A first guess for the circle, so the parent adjusts
 * rather than places it.
 *
 * Finds the largest connected area that differs clearly from the photo's
 * typical skin colour (median Lab), weighting redness, and returns a circle
 * that covers it with a margin. Returns null when nothing stands out -- the
 * screen then keeps the centred default.
 */
object SpotFinder {

    fun suggest(photo: PixelImage): NCircle? {
        val img = photo.downscaled(200)
        val w = img.width; val h = img.height
        val labs = Array(w * h) { ColorMath.lab(img.argb[it]) }
        val stats = Regions.stats(labs.filterIndexed { i, _ -> i % 3 == 0 })
        val spread = 1.4826 * sqrt(stats.madL * stats.madL + stats.madA * stats.madA + stats.madB * stats.madB)
        val t = max(9.0, 3.5 * spread)
        val m = BooleanArray(w * h) { i ->
            val l = labs[i]
            val dA = l.a - stats.median.a
            ColorMath.deltaE76(l, stats.median) + max(0.0, dA) > t
        }
        // Ignore a thin border: edges of the frame are often background.
        val bx = (w * 0.04).toInt(); val by = (h * 0.04).toInt()
        for (y in 0 until h) for (x in 0 until w) if (x < bx || y < by || x >= w - bx || y >= h - by) m[y * w + x] = false
        val comps = SkinMeasure.components(m, w, h, max(8, w * h / 800))
        if (comps.isEmpty()) return null
        // Merge components near the biggest one: scattered spots belong together.
        val biggest = comps.maxBy { it.size }
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

    private fun centroid(c: IntArray, w: Int): Pair<Double, Double> {
        var sx = 0.0; var sy = 0.0
        c.forEach { sx += it % w; sy += it / w }
        return sx / c.size to sy / c.size
    }

}
