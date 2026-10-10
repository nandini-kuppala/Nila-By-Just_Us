package com.nila.skin.core.measure

import com.nila.skin.core.color.Lab
import com.nila.skin.core.color.WhiteBalance
import com.nila.skin.core.image.NCircle
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
        val wb = when {
            white != null -> WhiteBalance.fromReference(Regions.meanLinear(img, white))
            else -> Regions.autoWhite(img, exclude = spot)
        }
        val s = Regions.circleStats(img, spot, wb ?: WhiteBalance.NONE)
        return YellowReading(site, s.median, s.median.b, wb != null)
    }
}
