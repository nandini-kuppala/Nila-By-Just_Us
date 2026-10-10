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
     *
     * Read by contrast with the iris around it, the way a person reads it:
     * a glow is a pupil clearly brighter than its iris, red when a* is high,
     * white or yellow when it isn't. Absolute thresholds failed on real flash
     * photos taken in bright rooms, where a healthy dark pupil is mid-grey.
     */
    fun read(photo: PixelImage, pupil: NCircle): PupilReading {
        val inner = ArrayList<Pair<Int, com.nila.skin.core.color.Lab>>()
        Regions.forEachInCircle(photo, pupil.copy(r = pupil.r * 0.75), 1) { x, y -> val p = photo[x, y]; inner += p to ColorMath.lab(p) }
        val ring = ArrayList<Double>()
        // The ring can overlap a very dilated glowing pupil, or catch the white
        // of the eye; its darker quarter is reliably iris.
        Regions.forEachInRing(photo, pupil.copy(r = pupil.r * 1.2), 2.2 / 1.2, 1) { x, y -> ring += ColorMath.lab(photo[x, y]).l }
        if (inner.size < 8 || ring.size < 8) return PupilReading(Reflex.UNCLEAR, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val sortedRing = ring.sorted()
        val irisL = sortedRing[(sortedRing.size * 0.25).toInt()]
        val pupilL = Regions.med(inner.map { it.second.l }.sorted())
        var red = 0; var white = 0; var sr = 0.0; var sg = 0.0; var sb = 0.0; var sumS = 0.0
        for ((p, lab) in inner) {
            val r = p.red(); val g = p.green(); val b = p.blue()
            sr += r; sg += g; sb += b
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            sumS += if (mx == 0) 0.0 else (mx - mn).toDouble() / mx
            if (lab.a > 25 && r > g * 1.4 && r > 70) red++
            else if (lab.l > irisL + 12 && lab.a < 20) white++
        }
        val n = inner.size.toDouble()
        val ws = white / n; val rs = red / n
        val reflex = when {
            rs >= 0.3 -> Reflex.RED
            ws >= 0.3 && pupilL > irisL + 10 -> Reflex.WHITE
            pupilL <= irisL + 8 -> Reflex.DARK
            else -> Reflex.UNCLEAR
        }
        return PupilReading(reflex, sr / n, sg / n, sb / n, pupilL, sumS / n, ws, rs)
    }

    fun readPair(photo: PixelImage, left: NCircle, right: NCircle) =
        EyePairReading(read(photo, left), read(photo, right))
}
