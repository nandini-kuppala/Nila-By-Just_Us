package com.nila.skin.core.synth

import com.nila.skin.core.color.ColorMath
import com.nila.skin.core.color.Lab
import com.nila.skin.core.image.PixelImage
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Synthetic test pictures with a known right answer.
 *
 * Real infant skin photos with ground-truth outlines do not exist in the open,
 * and they would be the wrong first test anyway: to know whether the maths
 * measures correctly you need pictures where you *know* how big and how red
 * the patch is. These are used by the unit tests, the fairness table across
 * six skin tones, and the demo mode.
 */
object Synth {

    /**
     * Six reference skin tones spanning very light to very dark, in Lab. Values
     * sit inside the ranges published for Fitzpatrick I-VI skin colorimetry
     * (L* ~70 down to ~30), so the test covers the whole range the app meets.
     */
    val TONES = listOf(
        "I" to Lab(70.0, 9.0, 15.0),
        "II" to Lab(64.0, 11.0, 17.0),
        "III" to Lab(57.0, 12.0, 19.0),
        "IV" to Lab(48.0, 12.0, 20.0),
        "V" to Lab(39.0, 11.0, 17.0),
        "VI" to Lab(30.0, 8.0, 11.0),
    )

    data class Spot(val cx: Double, val cy: Double, val r: Double)

    /** Visible redness on darker skin is a smaller a* shift; scale it like real erythema. */
    fun rednessFor(tone: Lab, base: Double): Double = base * (0.55 + 0.45 * (tone.l - 30) / 40.0).coerceIn(0.5, 1.0)

    /**
     * Skin with round patches.
     *
     * @param deltaA, deltaL, deltaB how the patch differs from the skin.
     * @param lighting multiplies linear R, G, B: (1.1, 1.0, 0.8) is a warm bulb.
     * @param gradient uneven light: brightness runs from (1 - g) at the left
     *   edge to (1 + g) at the right, like a window to one side.
     */
    fun skin(
        size: Int, tone: Lab, spots: List<Spot>, deltaA: Double, deltaL: Double = -2.0, deltaB: Double = 0.0,
        seed: Int = 1, noise: Double = 1.6, lighting: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0),
        white: Spot? = null,
        gradient: Double = 0.0,
    ): PixelImage {
        val rnd = Random(seed)
        // Low-frequency mottling: a few broad blobs, like real skin unevenness.
        val blobs = List(6) { Triple(rnd.nextDouble() * size, rnd.nextDouble() * size, size * (0.15 + rnd.nextDouble() * 0.25)) }
        val out = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            var mott = 0.0
            for ((bx, by, br) in blobs) { val d2 = (x - bx) * (x - bx) + (y - by) * (y - by); mott += exp(-d2 / (2 * br * br)) }
            var l = tone.l + (mott - 1.0) * 1.2 + gauss(rnd) * noise
            var a = tone.a + gauss(rnd) * noise * 0.4
            var b = tone.b + gauss(rnd) * noise * 0.4
            var w = 0.0
            for (s in spots) {
                val d = sqrt((x - s.cx * size) * (x - s.cx * size) + (y - s.cy * size) * (y - s.cy * size))
                val edge = s.r * size
                w = maxOf(w, smooth(edge + 1.5, edge - 1.5, d))
            }
            l += deltaL * w; a += deltaA * w; b += deltaB * w
            var argb = ColorMath.toArgb(Lab(l, a, b))
            if (white != null) {
                val d = sqrt((x - white.cx * size) * (x - white.cx * size) + (y - white.cy * size) * (y - white.cy * size))
                if (d <= white.r * size) argb = ColorMath.toArgb(Lab(93.0 + gauss(rnd) * 0.6, 0.0, 1.5))
            }
            val g = 1.0 + gradient * (2.0 * x / size - 1.0)
            out[y * size + x] = light(argb, if (gradient == 0.0) lighting else doubleArrayOf(lighting[0] * g, lighting[1] * g, lighting[2] * g))
        }
        return PixelImage(size, size, out)
    }

    /** A patch of one colour on a white nappy, under coloured light. */
    fun onNappy(size: Int, colour: Lab, lighting: DoubleArray, seed: Int = 3): PixelImage {
        val rnd = Random(seed)
        val out = IntArray(size * size)
        val c = size / 2.0
        for (y in 0 until size) for (x in 0 until size) {
            val d = sqrt((x - c) * (x - c) + (y - c) * (y - c))
            val lab = if (d < size * 0.22) Lab(colour.l + gauss(rnd) * 1.5, colour.a + gauss(rnd) * 0.8, colour.b + gauss(rnd) * 0.8)
                      else Lab(92.0 + gauss(rnd) * 1.0, 0.5, 2.0)
            out[y * size + x] = light(ColorMath.toArgb(lab), lighting)
        }
        return PixelImage(size, size, out)
    }

    /** A dark iris with a pupil glowing in [pupil] colour. */
    fun eye(size: Int, pupil: Lab, seed: Int = 5): PixelImage {
        val rnd = Random(seed)
        val out = IntArray(size * size)
        val c = size / 2.0
        for (y in 0 until size) for (x in 0 until size) {
            val d = sqrt((x - c) * (x - c) + (y - c) * (y - c))
            val lab = when {
                d < size * 0.18 -> Lab(pupil.l + gauss(rnd), pupil.a + gauss(rnd) * 0.5, pupil.b + gauss(rnd) * 0.5)
                d < size * 0.38 -> Lab(30.0 + gauss(rnd) * 2, 6.0, 14.0)
                else -> Lab(88.0 + gauss(rnd), 1.0, 4.0)
            }
            out[y * size + x] = ColorMath.toArgb(lab)
        }
        return PixelImage(size, size, out)
    }

    fun boxBlur(img: PixelImage, radius: Int): PixelImage {
        val w = img.width; val h = img.height
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0; var g = 0; var b = 0; var n = 0
            for (dy in -radius..radius) for (dx in -radius..radius) {
                val xx = (x + dx).coerceIn(0, w - 1); val yy = (y + dy).coerceIn(0, h - 1)
                val p = img[xx, yy]; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
            }
            out[y * w + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        return PixelImage(w, h, out)
    }

    fun light(argb: Int, gain: DoubleArray): Int {
        if (gain[0] == 1.0 && gain[1] == 1.0 && gain[2] == 1.0) return argb
        val r = ColorMath.toSrgbChannel(ColorMath.linear((argb shr 16) and 0xFF) * gain[0])
        val g = ColorMath.toSrgbChannel(ColorMath.linear((argb shr 8) and 0xFF) * gain[1])
        val b = ColorMath.toSrgbChannel(ColorMath.linear(argb and 0xFF) * gain[2])
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    val WARM = doubleArrayOf(1.0, 0.86, 0.62)
    val COOL = doubleArrayOf(0.82, 0.92, 1.0)
    val NEUTRAL = doubleArrayOf(1.0, 1.0, 1.0)

    private fun smooth(e0: Double, e1: Double, x: Double): Double {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    private fun gauss(r: Random): Double {
        // Box-Muller
        val u = r.nextDouble().coerceAtLeast(1e-12); val v = r.nextDouble()
        return sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * v)
    }
}
