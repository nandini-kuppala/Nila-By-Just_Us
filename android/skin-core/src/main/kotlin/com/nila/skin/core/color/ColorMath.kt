package com.nila.skin.core.color

import com.nila.skin.core.image.blue
import com.nila.skin.core.image.green
import com.nila.skin.core.image.red
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CIELAB colour, D65 white.
 *
 * Lab is used for every skin and stool reading because its axes mean something
 * a doctor would recognise: L* is lightness, a* runs green to red (redness),
 * b* runs blue to yellow (yellowness). Clinical erythema and jaundice studies
 * report a* and b* for exactly this reason.
 */
data class Lab(val l: Double, val a: Double, val b: Double) {
    val chroma: Double get() = hypot(a, b)
    /** Hue angle in degrees, 0..360. */
    val hue: Double get() = (Math.toDegrees(atan2(b, a)) + 360.0) % 360.0
}

object ColorMath {

    private val LINEAR = DoubleArray(256) { i ->
        val c = i / 255.0
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    fun linear(channel: Int): Double = LINEAR[channel.coerceIn(0, 255)]

    fun toSrgbChannel(linear: Double): Int {
        val v = linear.coerceIn(0.0, 1.0)
        val c = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055
        return (c * 255.0 + 0.5).toInt().coerceIn(0, 255)
    }

    fun lab(argb: Int): Lab = labLinear(linear(argb.red()), linear(argb.green()), linear(argb.blue()))

    fun labLinear(r: Double, g: Double, b: Double): Lab {
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
        val y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b)
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883
        val fx = f(x); val fy = f(y); val fz = f(z)
        return Lab(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    /** Lab back to sRGB, for drawing swatches and for the synthetic tests. */
    fun toArgb(lab: Lab): Int {
        val fy = (lab.l + 16) / 116
        val fx = fy + lab.a / 500
        val fz = fy - lab.b / 200
        val x = finv(fx) * 0.95047
        val y = finv(fy)
        val z = finv(fz) * 1.08883
        val r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
        val g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
        val b = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z
        return (0xFF shl 24) or (toSrgbChannel(r) shl 16) or (toSrgbChannel(g) shl 8) or toSrgbChannel(b)
    }

    private fun f(t: Double) = if (t > 216.0 / 24389.0) cbrt(t) else (24389.0 / 27.0 * t + 16) / 116
    private fun finv(t: Double) = if (t * t * t > 216.0 / 24389.0) t * t * t else (116 * t - 16) / (24389.0 / 27.0)

    fun deltaE76(p: Lab, q: Lab): Double {
        val dl = p.l - q.l; val da = p.a - q.a; val db = p.b - q.b
        return sqrt(dl * dl + da * da + db * db)
    }

    /**
     * CIEDE2000, the colour difference that best matches what people see.
     * Implementation follows Sharma, Wu and Dalal (2005), whose test pairs are
     * in the unit tests.
     */
    fun deltaE2000(p: Lab, q: Lab): Double {
        val c1 = hypot(p.a, p.b); val c2 = hypot(q.a, q.b)
        val cBar = (c1 + c2) / 2
        val g = 0.5 * (1 - sqrt(cBar.pow(7) / (cBar.pow(7) + 25.0.pow(7))))
        val a1 = (1 + g) * p.a; val a2 = (1 + g) * q.a
        val c1p = hypot(a1, p.b); val c2p = hypot(a2, q.b)
        val h1p = hp(a1, p.b); val h2p = hp(a2, q.b)
        val dLp = q.l - p.l
        val dCp = c2p - c1p
        var dhp = 0.0
        if (c1p * c2p != 0.0) {
            dhp = h2p - h1p
            if (dhp > 180) dhp -= 360 else if (dhp < -180) dhp += 360
        }
        val dHp = 2 * sqrt(c1p * c2p) * sin(Math.toRadians(dhp / 2))
        val lBarP = (p.l + q.l) / 2
        val cBarP = (c1p + c2p) / 2
        val hBarP = if (c1p * c2p == 0.0) h1p + h2p else {
            val sum = h1p + h2p
            when {
                abs(h1p - h2p) <= 180 -> sum / 2
                sum < 360 -> (sum + 360) / 2
                else -> (sum - 360) / 2
            }
        }
        val t = 1 - 0.17 * cos(Math.toRadians(hBarP - 30)) + 0.24 * cos(Math.toRadians(2 * hBarP)) +
            0.32 * cos(Math.toRadians(3 * hBarP + 6)) - 0.20 * cos(Math.toRadians(4 * hBarP - 63))
        val dTheta = 30 * exp(-((hBarP - 275) / 25).pow(2))
        val rc = 2 * sqrt(cBarP.pow(7) / (cBarP.pow(7) + 25.0.pow(7)))
        val sl = 1 + 0.015 * (lBarP - 50).pow(2) / sqrt(20 + (lBarP - 50).pow(2))
        val sc = 1 + 0.045 * cBarP
        val sh = 1 + 0.015 * cBarP * t
        val rt = -sin(Math.toRadians(2 * dTheta)) * rc
        return sqrt((dLp / sl).pow(2) + (dCp / sc).pow(2) + (dHp / sh).pow(2) + rt * (dCp / sc) * (dHp / sh))
    }

    private fun hp(a: Double, b: Double): Double {
        if (a == 0.0 && b == 0.0) return 0.0
        val h = Math.toDegrees(atan2(b, a))
        return if (h < 0) h + 360 else h
    }

    /**
     * Erythema index from the red and green channels: 100 * log10(R/G).
     * Melanin absorbs red and green similarly, so the ratio tracks blood
     * (redness) more than pigment -- the reason clinical erythema meters use it.
     */
    fun erythemaIndex(argb: Int): Double {
        val r = linear(argb.red()).coerceAtLeast(1e-4)
        val g = linear(argb.green()).coerceAtLeast(1e-4)
        return 100.0 * kotlin.math.log10(r / g)
    }
}

/**
 * Grey-world correction from a known white thing in the photo -- a tissue, the
 * nappy, a sheet of paper.
 *
 * Phone cameras guess the white balance, and a warm bulb makes skin look
 * yellow and stool look darker. Scaling each linear channel so the reference
 * becomes neutral removes most of that error: a smartphone colour study found
 * white-standard normalisation kept colorimetric accuracy (Cugmas & Struc, 2020).
 */
class WhiteBalance private constructor(val gainR: Double, val gainG: Double, val gainB: Double) {

    fun lab(argb: Int): Lab = ColorMath.labLinear(
        (ColorMath.linear(argb.red()) * gainR).coerceIn(0.0, 1.0),
        (ColorMath.linear(argb.green()) * gainG).coerceIn(0.0, 1.0),
        (ColorMath.linear(argb.blue()) * gainB).coerceIn(0.0, 1.0),
    )

    fun apply(argb: Int): Int {
        val r = ColorMath.toSrgbChannel(ColorMath.linear(argb.red()) * gainR)
        val g = ColorMath.toSrgbChannel(ColorMath.linear(argb.green()) * gainG)
        val b = ColorMath.toSrgbChannel(ColorMath.linear(argb.blue()) * gainB)
        return (argb and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }

    /** How strongly the camera's colour was off, as the reference's own chroma. */
    var castChroma: Double = 0.0
        private set

    companion object {
        val NONE = WhiteBalance(1.0, 1.0, 1.0)

        /** Linear luminance of L* 92, a white nappy or tissue in good light. */
        const val WHITE_Y = 0.8061

        /**
         * @param refLinear mean linear RGB of the white reference.
         * Keeps the reference's own brightness (green channel) so exposure is
         * not changed, only colour.
         */
        fun fromReference(refLinear: DoubleArray, exposure: Boolean = false): WhiteBalance {
            val (r, g, b) = Triple(refLinear[0].coerceAtLeast(1e-3), refLinear[1].coerceAtLeast(1e-3), refLinear[2].coerceAtLeast(1e-3))
            // With [exposure], the reference is also brought to the brightness of
            // a white nappy in good light (L* 92), within limits, so a dim photo
            // does not make pale poo look darker and more coloured.
            val target = if (exposure) (WHITE_Y / g).coerceIn(0.8, 2.5) * g else g
            val wb = WhiteBalance(target / r, target / g, target / b)
            wb.castChroma = ColorMath.labLinear(r, g, b).chroma
            return wb
        }
    }
}
