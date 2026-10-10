package com.nila.skin.core.image

import com.nila.skin.core.color.ColorMath
import com.nila.skin.core.color.WhiteBalance
import kotlin.math.abs

/**
 * Is this photo good enough to measure?
 *
 * Patient-taken photos are the weak link in remote skin care: a review of
 * teledermatology found their quality and diagnostic agreement are lower and
 * more variable than clinician photos (JMIR Dermatology, 2022). So before any
 * number is shown, the photo itself is checked, and the parent is told how to
 * take a better one instead of being given a reading from a bad one.
 */
data class PhotoQuality(
    val meanLightness: Double,
    val clippedFraction: Double,
    val darkFraction: Double,
    /** 95th percentile of |Laplacian| on the grey picture, 0..255 scale. */
    val sharpness: Double,
    val problems: List<Problem>,
) {
    enum class Problem(val advice: String) {
        TOO_DARK("It's too dark. Move near a window in daylight, and don't use the flash."),
        TOO_BRIGHT("Part of the photo is washed out. Step out of direct sun or turn the flash off."),
        BLURRY("It looks blurry. Hold the phone steady about a hand-span away and tap the screen to focus."),
        COLOUR_CAST("The light is very coloured. Daylight from a window gives the truest colour."),
    }

    val ok: Boolean get() = problems.isEmpty()
}

object Quality {

    fun check(img0: PixelImage, whiteBalance: WhiteBalance? = null): PhotoQuality {
        val img = img0.downscaled(480)
        val w = img.width; val h = img.height
        val grey = DoubleArray(w * h)
        var sumL = 0.0; var clipped = 0; var dark = 0
        var i = 0
        for (y in 0 until h) for (x in 0 until w) {
            val p = img[x, y]
            val r = p.red(); val g = p.green(); val b = p.blue()
            grey[i] = 0.299 * r + 0.587 * g + 0.114 * b
            if (r >= 250 && g >= 250 && b >= 250) clipped++
            if (grey[i] < 18) dark++
            i++
        }
        // Lightness on a sparse grid is plenty for an average.
        var n = 0
        for (y in 0 until h step 4) for (x in 0 until w step 4) { sumL += ColorMath.lab(img[x, y]).l; n++ }
        val meanL = sumL / n

        val lap = ArrayList<Double>((w - 2) * (h - 2))
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val c = y * w + x
            lap += abs(4 * grey[c] - grey[c - 1] - grey[c + 1] - grey[c - w] - grey[c + w])
        }
        lap.sort()
        val sharp = if (lap.isEmpty()) 0.0 else lap[(lap.size * 0.95).toInt().coerceAtMost(lap.size - 1)]

        val total = (w * h).toDouble()
        val problems = buildList {
            if (meanL < 28 || dark / total > 0.45) add(PhotoQuality.Problem.TOO_DARK)
            if (clipped / total > 0.12) add(PhotoQuality.Problem.TOO_BRIGHT)
            if (sharp < SHARPNESS_MIN) add(PhotoQuality.Problem.BLURRY)
            if (whiteBalance != null && whiteBalance.castChroma > 25) add(PhotoQuality.Problem.COLOUR_CAST)
        }
        return PhotoQuality(meanL, clipped / total, dark / total, sharp, problems)
    }

    /**
     * Below this the picture has no fine detail at all. Set from the synthetic
     * tests: a skin patch with pores and a lesion edge scores 6-20 sharp, and a
     * 9-pixel box blur of the same patch scores under 2.
     */
    const val SHARPNESS_MIN = 2.5
}
