package com.nila.skin.core.measure

import com.nila.skin.core.color.ColorMath
import com.nila.skin.core.image.NCircle
import com.nila.skin.core.image.PixelImage
import com.nila.skin.core.image.Regions
import com.nila.skin.core.image.blue
import com.nila.skin.core.image.green
import com.nila.skin.core.image.red

/**
 * The pupil in a flash photo.
 *
 * A healthy eye photographed with a flash glows red (or dark, when the pupil is
 * small or the angle is off). A white or yellow glow -- leukocoria -- can be the
 * first sign of retinoblastoma, cataract or Coats' disease. Baylor's CRADLE
 * study found white-eye in ordinary family photos for 80% of affected children,
 * about 1.3 years before diagnosis (Munson et al., Science Advances 2019).
 *
 * It also flags white-eye in some healthy eyes (an off-axis flash on the optic
 * nerve), which is why one white photo means "take a few more", and only a
 * repeated white reflex means "ask for a red reflex check".
 */
enum class Reflex(val word: String) {
    RED("Red glow (the usual flash reflex)"),
    DARK("Dark pupil (no glow caught)"),
    WHITE("White or yellow glow"),
    UNCLEAR("Couldn't read this pupil"),
}

data class PupilReading(
    val reflex: Reflex,
    val meanR: Double, val meanG: Double, val meanB: Double,
    val lightness: Double,
    val saturation: Double,
    /** Share of pupil pixels that are bright and colourless (white/yellow). */
    val whiteShare: Double,
    val redShare: Double,
)

data class EyePairReading(val left: PupilReading, val right: PupilReading) {
    val anyWhite: Boolean get() = left.reflex == Reflex.WHITE || right.reflex == Reflex.WHITE
    /** One red and one dark (or white): unequal reflexes are worth a re-take. */
    val unequal: Boolean get() = left.reflex != right.reflex &&
        left.reflex != Reflex.UNCLEAR && right.reflex != Reflex.UNCLEAR
}

object EyeReflex {

    /**
     * @param pupil circle over the pupil (from face landmarks, or tapped).
     */
    fun read(photo: PixelImage, pupil: NCircle): PupilReading {
        var n = 0; var sr = 0.0; var sg = 0.0; var sb = 0.0; var white = 0; var red = 0; var sumL = 0.0; var sumS = 0.0
        // Inner 70%: the iris edge and lashes stay out.
        Regions.forEachInCircle(photo, pupil.copy(r = pupil.r * 0.7), 1) { x, y ->
            val p = photo[x, y]
            val r = p.red(); val g = p.green(); val b = p.blue()
            sr += r; sg += g; sb += b; n++
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            val sat = if (mx == 0) 0.0 else (mx - mn).toDouble() / mx
            val l = ColorMath.lab(p).l
            sumL += l; sumS += sat
            // Bright and not red-dominated: white, cream or yellow.
            if (l > 68 && (r - g) < 60 && g > 150) white++
            // Red-dominated glow.
            if (r > 90 && r > g * 1.6 && r > b * 1.6) red++
        }
        if (n < 8) return PupilReading(Reflex.UNCLEAR, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val ws = white.toDouble() / n; val rs = red.toDouble() / n
        val meanL = sumL / n
        val reflex = when {
            ws >= 0.30 -> Reflex.WHITE
            rs >= 0.30 -> Reflex.RED
            meanL < 35 -> Reflex.DARK
            else -> Reflex.UNCLEAR
        }
        return PupilReading(reflex, sr / n, sg / n, sb / n, meanL, sumS / n, ws, rs)
    }

    fun readPair(photo: PixelImage, left: NCircle, right: NCircle) =
        EyePairReading(read(photo, left), read(photo, right))
}
