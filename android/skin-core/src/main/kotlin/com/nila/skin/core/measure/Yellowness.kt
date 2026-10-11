package com.nila.skin.core.measure

import com.nila.skin.core.color.Lab
import com.nila.skin.core.color.WhiteBalance
import com.nila.skin.core.image.NCircle
import com.nila.skin.core.image.NPoint
import com.nila.skin.core.image.PixelImage
import com.nila.skin.core.image.Regions

/**
 * How yellow the white of the eye, a palm or a sole looks, after correcting the
 * colour against something white in the same photo.
 *
 * This is **not** a bilirubin estimate and the app never presents it as one.
 * Even purpose-built apps struggle: Picterus caught about 64% of high bilirubin
 * levels in dark-skinned newborns in Botswana, against 95% for a hospital
 * skin meter (Acta Paediatrica, 2026). What a camera can do honestly is keep a
 * consistent, colour-corrected record day to day, and put the published check
 * -- yellow palms or soles means see a doctor urgently (WHO IMNCI) -- in front
 * of the parent at the right moment. The white of the eye is used first
 * because it is white on every skin tone (the approach of neoSCB, UCL 2022).
 */
data class YellowReading(
    val site: Site,
    val lab: Lab,
    /** b* after white correction. Higher = yellower. */
    val yellowness: Double,
    val whiteCorrected: Boolean,
    /** Where the white reference was found, when found automatically. */
    val whitePoints: List<NPoint> = emptyList(),
    /**
     * The automatically found "white" is itself tinted. Usually a warm bulb,
     * but it can be the other eye's yellow white, which would hide the yellow:
     * the screen then asks the parent to mark the tissue themselves.
     */
    val whiteTinted: Boolean = false,
) {
    enum class Site(val label: String) { EYE_WHITE("White of the eye"), PALM("Palm"), SOLE("Sole of the foot"), FOREHEAD_PRESSED("Pressed forehead or nose") }

    /**
     * Words, not a score. The thresholds are where a sclera visibly stops
     * looking white (b* ~ 12) and starts looking clearly yellow (b* ~ 22) on our
     * colour-corrected test set; they describe the photo, they do not grade
     * jaundice.
     */
    val looks: String get() = when {
        !whiteCorrected -> "No white reference in the photo, so the colour can't be trusted"
        site == Site.EYE_WHITE && yellowness >= 22 -> "Looks clearly yellow"
        site == Site.EYE_WHITE && yellowness >= 12 -> "Looks slightly yellow"
        site == Site.EYE_WHITE -> "Looks white"
        else -> "Recorded for comparison over the next days"
    }
}

object Yellowness {

    fun read(photo: PixelImage, site: YellowReading.Site, spot: NCircle, white: NCircle? = null): YellowReading {
        val img = photo.downscaled(640)
        val auto = if (white == null) Regions.autoWhiteRef(img, exclude = spot, exposure = true) else null
        val wb = when {
            white != null -> WhiteBalance.fromReference(Regions.meanLinear(img, white), exposure = true)
            else -> auto?.wb
        }
        val s = Regions.circleStats(img, spot, wb ?: WhiteBalance.NONE)
        return YellowReading(site, s.median, s.median.b, wb != null, auto?.points ?: emptyList(), auto != null && auto.wb.castChroma > 15)
    }

    /**
     * Where is the white of the eye? A first guess for the circle.
     *
     * The white of the eye is bright and, unlike skin, has almost no red (a*
     * near zero) -- even when it is yellow. Pixels that are bright for this
     * photo and low in a* are grouped into connected regions; the region that
     * is large and near the middle wins; and the circle is the largest one
     * that fits wholly inside it (a two-pass chamfer distance transform), so
     * it never spills onto the iris or the eyelid.
     *
     * @return null when nothing looks like an eye white; the parent places it.
     */
    fun suggestSpot(photo: PixelImage): NCircle? {
        val img = photo.downscaled(220)
        val w = img.width; val h = img.height
        val labs = Array(w * h) { com.nila.skin.core.color.ColorMath.lab(img.argb[it]) }
        val ls = labs.map { it.l }.sorted()
        val bright = maxOf(52.0, ls[(ls.size * 0.70).toInt().coerceAtMost(ls.size - 1)])
        val m = BooleanArray(w * h) { i -> val l = labs[i]; l.l >= bright && l.a in -8.0..8.0 && l.l < 97 }
        val comps = SkinMeasure.components(m, w, h, maxOf(12, w * h / 600))
        if (comps.isEmpty()) return null
        val cx = w / 2.0; val cy = h / 2.0; val far = kotlin.math.hypot(cx, cy)
        val best = comps.maxBy { c ->
            var sx = 0.0; var sy = 0.0
            c.forEach { sx += it % w; sy += it / w }
            val d = kotlin.math.hypot(sx / c.size - cx, sy / c.size - cy) / far
            kotlin.math.sqrt(c.size.toDouble()) * (1 - d) * (1 - d)
        }
        val inside = BooleanArray(w * h); best.forEach { inside[it] = true }
        val (top, d) = Regions.inscribed(inside, w, h)
        val r = d * 0.8
        if (r < 2.5) return null
        return NCircle((top % w) / (w - 1.0), (top / w) / (h - 1.0), r / minOf(w, h))
    }
}
