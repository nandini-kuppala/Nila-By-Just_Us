package com.nila.skin.core.measure

import com.nila.skin.core.color.ColorMath
import com.nila.skin.core.color.Lab
import com.nila.skin.core.color.WhiteBalance
import com.nila.skin.core.image.NCircle
import com.nila.skin.core.image.NPoint
import com.nila.skin.core.image.PixelImage
import com.nila.skin.core.image.Regions

/**
 * The infant stool colour card, on the phone.
 *
 * Biliary atresia is rare, but its first visible sign is pale poo, and timing
 * is everything: Taiwan put a stool colour card in every baby's health booklet
 * in 2004, and 5-year survival rose from 55.7% to 89.3% (Lien et al.,
 * Hepatology 2011). Swatches 1-9 are sampled from Newborn Screening Ontario's
 * printed card (2024): 1-6 pale = abnormal, 7-9 = normal. Brown and green are
 * added because they are normal after the first weeks, and red and black
 * because blood in a nappy needs a doctor the same day (NHS).
 */
enum class StoolGroup { PALE, NORMAL, RED, BLACK }

data class StoolSwatch(val id: String, val label: String, val lab: Lab, val group: StoolGroup) {
    val argb: Int get() = ColorMath.toArgb(lab)
}

object StoolCard {

    val swatches = listOf(
        StoolSwatch("1", "Very pale, almost white", Lab(95.2, -4.3, 25.4), StoolGroup.PALE),
        StoolSwatch("2", "Pale grey-beige (clay)", Lab(78.9, -0.1, 19.0), StoolGroup.PALE),
        StoolSwatch("3", "Pale lemon", Lab(86.2, -5.8, 33.4), StoolGroup.PALE),
        StoolSwatch("4", "Pale grey-green", Lab(79.5, -7.4, 21.5), StoolGroup.PALE),
        StoolSwatch("5", "Pale sand", Lab(79.8, 2.4, 38.3), StoolGroup.PALE),
        StoolSwatch("6", "Pale tan", Lab(73.4, 5.0, 39.2), StoolGroup.PALE),
        StoolSwatch("7", "Bright yellow", Lab(85.2, -4.6, 72.8), StoolGroup.NORMAL),
        StoolSwatch("8", "Mustard yellow", Lab(73.3, 6.3, 68.1), StoolGroup.NORMAL),
        StoolSwatch("9", "Yellow-green", Lab(66.5, -10.9, 59.2), StoolGroup.NORMAL),
        StoolSwatch("B", "Brown", Lab(42.0, 14.0, 38.0), StoolGroup.NORMAL),
        StoolSwatch("G", "Dark green", Lab(38.0, -12.0, 30.0), StoolGroup.NORMAL),
        StoolSwatch("R", "Red or red streaks", Lab(42.0, 52.0, 32.0), StoolGroup.RED),
        StoolSwatch("K", "Black", Lab(14.0, 1.0, 3.0), StoolGroup.BLACK),
    )

    fun byId(id: String) = swatches.first { it.id == id }

    data class Reading(
        val stool: Lab,
        val nearest: StoolSwatch,
        val distance: Double,
        val ranked: List<Pair<StoolSwatch, Double>>,
        /** True when the photo had a white nappy/tissue to correct colour with. */
        val whiteCorrected: Boolean,
        /** The chroma rule on its own: light and weakly coloured = pale. */
        val paleByChroma: Boolean,
        /** Where the white reference was found, when found automatically. */
        val whitePoints: List<NPoint> = emptyList(),
    ) {
        val group: StoolGroup get() = if (paleByChroma) StoolGroup.PALE else nearest.group
    }

    /**
     * Pale stool on the card is light AND weakly coloured: the pale swatches
     * have b* 19-39, the normal yellows 59-73. Chroma below 46 with lightness
     * above 62 is pale whichever swatch happens to be nearest -- unless it is
     * reddish-brown (a* 8 or more; every pale card colour has a* below 8),
     * which is how a well-lit brown stool looks in a real photo.
     */
    fun isPale(lab: Lab) = lab.l >= 62 && lab.chroma < 46 && lab.a < 8

    fun classify(lab: Lab, whiteCorrected: Boolean): Reading {
        val ranked = swatches.map { it to ColorMath.deltaE2000(lab, it.lab) }.sortedBy { it.second }
        return Reading(lab, ranked[0].first, ranked[0].second, ranked, whiteCorrected, isPale(lab))
    }

    /**
     * Where is the poo? The most strongly coloured region near the middle of
     * the photo, in poo hues (the nappy is white or a coloured cloth, the poo is
     * red-brown-yellow-green), and the largest circle
     * that fits inside it. Null when nothing stands out.
     */
    fun suggestSpot(photo: PixelImage): NCircle? {
        val img = photo.downscaled(200)
        val w = img.width; val h = img.height
        val m = BooleanArray(w * h) { i -> val l = ColorMath.lab(img.argb[i])
            // Poo runs red to brown to yellow to green, never blue or purple (a blue cloth nappy).
            l.chroma > 22 && l.l in 18.0..92.0 && (l.hue < 150 || l.hue > 345) }
        val comps = com.nila.skin.core.measure.SkinMeasure.components(m, w, h, maxOf(15, w * h / 400))
        if (comps.isEmpty()) return null
        val far = kotlin.math.hypot(w / 2.0, h / 2.0)
        val best = comps.maxBy { c ->
            var sx = 0.0; var sy = 0.0
            c.forEach { sx += it % w; sy += it / w }
            c.size * (1 - 0.7 * kotlin.math.hypot(sx / c.size - w / 2.0, sy / c.size - h / 2.0) / far)
        }
        val inside = BooleanArray(w * h); best.forEach { inside[it] = true }
        val (top, d) = Regions.inscribed(inside, w, h)
        if (d < 3) return null
        return NCircle((top % w) / (w - 1.0), (top / w) / (h - 1.0), (d * 0.85 / minOf(w, h)).coerceAtMost(0.3))
    }

    /**
     * Read the stool colour from a photo.
     *
     * The white of the nappy corrects both the colour of the light and its
     * brightness. Brightness matters most: in a dim, warm room the pale card
     * colours read darker and more coloured, and 5 of the 6 were taken for
     * normal yellow-brown before exposure was corrected (synthetic test, all 13
     * right after).
     *
     * @param spot circle the parent drew on the poo.
     * @param white circle on the nappy or a tissue; found automatically when null.
     */
    fun read(photo: PixelImage, spot: NCircle, white: NCircle? = null, correct: Boolean = true): Reading {
        val img = photo.downscaled(640)
        val auto = if (correct && white == null) Regions.autoWhiteRef(img, exclude = spot, exposure = true) else null
        val wb = when {
            !correct -> null
            white != null -> WhiteBalance.fromReference(Regions.meanLinear(img, white), exposure = true)
            else -> auto?.wb
        }
        val stats = Regions.circleStats(img, spot.copy(r = spot.r * 0.8), wb ?: WhiteBalance.NONE)
        return classify(stats.median, wb != null).copy(whitePoints = auto?.points ?: emptyList())
    }
}
