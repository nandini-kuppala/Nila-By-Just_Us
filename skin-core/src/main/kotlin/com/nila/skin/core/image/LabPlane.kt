package com.nila.skin.core.image

import com.nila.skin.core.color.ColorMath
import com.nila.skin.core.color.Lab
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The baby's own skin colour as a gentle slope across the picture, not one
 * number.
 *
 * Window light falls off across a cheek or an arm, so the skin on one side of
 * a spot is lighter than on the other. Comparing every pixel with a single
 * median then calls the bright side "lighter" and the dark side "darker" when
 * nothing is wrong. Fitting a plane -- L*, a* and b* each as c0 + c1*x + c2*y,
 * least squares on the surrounding skin -- gives the expected skin colour at
 * every point, so a pixel is compared with the skin right next to it.
 *
 * Fitted robustly: outliers (a sleeve, the spot's own edge) are dropped and the
 * plane refitted, twice.
 */
class LabPlane private constructor(
    private val c: DoubleArray,
    private val ox: Double,
    private val oy: Double,
    private val scale: Double,
    /** Robust spread of the skin around the plane: 1.4826 x MAD, per channel. */
    val spreadL: Double,
    val spreadA: Double,
    val spreadB: Double,
    val samples: Int,
) {
    fun at(x: Double, y: Double): Lab {
        val u = (x - ox) / scale; val v = (y - oy) / scale
        return Lab(c[0] + c[1] * u + c[2] * v, c[3] + c[4] * u + c[5] * v, c[6] + c[7] * u + c[8] * v)
    }

    val centre: Lab get() = at(ox, oy)

    /** Change in L* across one [scale] distance: how uneven the light is. */
    val lightSlope: Double get() = hypot(c[1], c[2])

    /** Combined spread, the "normal wobble" of this baby's skin in this photo. */
    val spread: Double get() = sqrt(spreadL * spreadL + spreadA * spreadA + spreadB * spreadB)

    companion object {

        /**
         * @param ox, oy, scale centre and unit for the coordinates, so the
         *   numbers stay well-conditioned (use the circle centre and radius).
         * @param flat true fits only the constant term: the old single-median
         *   reference, kept for the comparison in the demo lab.
         */
        fun fit(
            xs: DoubleArray, ys: DoubleArray, labs: List<Lab>,
            ox: Double, oy: Double, scale: Double,
            flat: Boolean = false, floor: Double = 10.0,
        ): LabPlane {
            require(labs.isNotEmpty()) { "no skin samples" }
            var keep = labs.indices.toList()
            // Start from the median so the first trim is not pulled by outliers.
            val med = Regions.stats(labs).median
            keep = trim(keep, labs) { med }
            var plane = solve(xs, ys, labs, keep, ox, oy, scale, flat || keep.size < 30)
            repeat(2) {
                val p = plane
                val next = trim(keep.ifEmpty { labs.indices.toList() }, labs, floor) { i -> p.at(xs[i], ys[i]) }
                if (next.size >= 20) { keep = next; plane = solve(xs, ys, labs, keep, ox, oy, scale, flat || keep.size < 30) }
            }
            return plane
        }

        private inline fun trim(idx: List<Int>, labs: List<Lab>, floor: Double = 14.0, expected: (Int) -> Lab): List<Int> {
            val res = idx.map { i -> val e = expected(i); Triple(labs[i].l - e.l, labs[i].a - e.a, labs[i].b - e.b) }
            val mL = Regions.med(res.map { abs(it.first) }.sorted())
            val mA = Regions.med(res.map { abs(it.second) }.sorted())
            val mB = Regions.med(res.map { abs(it.third) }.sorted())
            val spread = 1.4826 * sqrt(mL * mL + mA * mA + mB * mB)
            val limit = max(floor, 3.0 * spread)
            return idx.filterIndexed { k, _ -> val r = res[k]; sqrt(r.first * r.first + r.second * r.second + r.third * r.third) <= limit }
        }

        private fun solve(xs: DoubleArray, ys: DoubleArray, labs: List<Lab>, idx: List<Int>,
                          ox: Double, oy: Double, scale: Double, flat: Boolean): LabPlane {
            val use = idx.ifEmpty { labs.indices.toList() }
            val c = DoubleArray(9)
            if (flat) {
                val m = Regions.stats(use.map { labs[it] }).median
                c[0] = m.l; c[3] = m.a; c[6] = m.b
            } else {
                // Normal equations for v = c0 + c1 u + c2 w, shared by the three channels.
                var n = 0.0; var su = 0.0; var sw = 0.0; var suu = 0.0; var suw = 0.0; var sww = 0.0
                val rhs = Array(3) { DoubleArray(3) }
                for (i in use) {
                    val u = (xs[i] - ox) / scale; val w = (ys[i] - oy) / scale
                    n += 1; su += u; sw += w; suu += u * u; suw += u * w; sww += w * w
                    val v = doubleArrayOf(labs[i].l, labs[i].a, labs[i].b)
                    for (ch in 0..2) { rhs[ch][0] += v[ch]; rhs[ch][1] += u * v[ch]; rhs[ch][2] += w * v[ch] }
                }
                val m = arrayOf(doubleArrayOf(n, su, sw), doubleArrayOf(su, suu, suw), doubleArrayOf(sw, suw, sww))
                for (ch in 0..2) {
                    val sol = solve3(m, rhs[ch])
                    if (sol == null) {
                        val med = Regions.stats(use.map { labs[it] }).median
                        c[ch * 3] = doubleArrayOf(med.l, med.a, med.b)[ch]
                    } else sol.copyInto(c, ch * 3)
                }
            }
            val tmp = LabPlane(c, ox, oy, scale, 0.0, 0.0, 0.0, use.size)
            val rl = ArrayList<Double>(use.size); val ra = ArrayList<Double>(use.size); val rb = ArrayList<Double>(use.size)
            for (i in use) { val e = tmp.at(xs[i], ys[i]); rl += abs(labs[i].l - e.l); ra += abs(labs[i].a - e.a); rb += abs(labs[i].b - e.b) }
            rl.sort(); ra.sort(); rb.sort()
            return LabPlane(c, ox, oy, scale, 1.4826 * Regions.med(rl), 1.4826 * Regions.med(ra), 1.4826 * Regions.med(rb), use.size)
        }

        /** Gaussian elimination with partial pivoting; null when singular. */
        private fun solve3(m0: Array<DoubleArray>, b0: DoubleArray): DoubleArray? {
            val m = Array(3) { r -> doubleArrayOf(m0[r][0], m0[r][1], m0[r][2], b0[r]) }
            for (col in 0..2) {
                val p = (col..2).maxBy { abs(m[it][col]) }
                if (abs(m[p][col]) < 1e-9) return null
                val t = m[p]; m[p] = m[col]; m[col] = t
                for (r in 0..2) if (r != col) {
                    val f = m[r][col] / m[col][col]
                    for (k in col..3) m[r][k] -= f * m[col][k]
                }
            }
            return DoubleArray(3) { m[it][3] / m[it][it] }
        }

        /** Skin samples in the ring around [spot], with their positions. */
        fun ringSamples(img: PixelImage, spot: NCircle, outerScale: Double, lab: (Int) -> Lab = ColorMath::lab):
            Triple<DoubleArray, DoubleArray, List<Lab>> {
            val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val labs = ArrayList<Lab>()
            Regions.forEachInRing(img, spot, outerScale, 1) { x, y -> xs += x.toDouble(); ys += y.toDouble(); labs += lab(img[x, y]) }
            return Triple(xs.toDoubleArray(), ys.toDoubleArray(), labs)
        }
    }
}
