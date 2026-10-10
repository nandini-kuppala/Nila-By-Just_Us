package com.nila.skin.core

import com.nila.skin.core.color.ColorMath
import com.nila.skin.core.color.Lab
import com.nila.skin.core.image.NCircle
import com.nila.skin.core.image.Quality
import com.nila.skin.core.image.PhotoQuality
import com.nila.skin.core.measure.EyeReflex
import com.nila.skin.core.measure.Reflex
import com.nila.skin.core.measure.SizeReference
import com.nila.skin.core.measure.SkinMeasure
import com.nila.skin.core.measure.StoolCard
import com.nila.skin.core.measure.StoolGroup
import com.nila.skin.core.measure.Trend
import com.nila.skin.core.measure.TrendPoint
import com.nila.skin.core.measure.TrendSummary
import com.nila.skin.core.measure.YellowReading
import com.nila.skin.core.measure.Yellowness
import com.nila.skin.core.synth.Synth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class ColorMathTest {

    /** Test pairs from Sharma, Wu & Dalal (2005), the CIEDE2000 reference paper. */
    @Test fun ciede2000MatchesSharmaPairs() {
        assertEquals(2.0425, ColorMath.deltaE2000(Lab(50.0, 2.6772, -79.7751), Lab(50.0, 0.0, -82.7485)), 1e-4)
        assertEquals(2.3669, ColorMath.deltaE2000(Lab(50.0, 0.0, 0.0), Lab(50.0, -1.0, 2.0)), 1e-4)
        assertEquals(27.1492, ColorMath.deltaE2000(Lab(50.0, 2.5, 0.0), Lab(73.0, 25.0, -18.0)), 1e-4)
        assertEquals(1.2644, ColorMath.deltaE2000(Lab(60.2574, -34.0099, 36.2677), Lab(60.4626, -34.1751, 39.4387)), 1e-4)
    }

    @Test fun labRoundTrip() {
        for (lab in listOf(Lab(50.0, 20.0, 30.0), Lab(80.0, -5.0, 60.0), Lab(30.0, 8.0, 11.0))) {
            val back = ColorMath.lab(ColorMath.toArgb(lab))
            assertTrue("$lab -> $back", ColorMath.deltaE76(lab, back) < 1.0)
        }
    }

    @Test fun whiteIsNeutral() {
        val w = ColorMath.lab(0xFFFFFFFF.toInt())
        assertEquals(100.0, w.l, 0.01); assertEquals(0.0, w.a, 0.01); assertEquals(0.0, w.b, 0.01)
    }
}

class SkinMeasureTest {

    private val spot = Synth.Spot(0.5, 0.5, 0.12)
    private val circle = NCircle(0.5, 0.5, 0.22)

    /**
     * The fairness test. The same patch, at the redness real erythema shows on
     * each tone, is found on all six skin tones with similar accuracy.
     */
    @Test fun findsThePatchOnAllSixSkinTones() {
        val truthCoverage = PI * 0.12 * 0.12 / (PI * 0.22 * 0.22) // ~0.30
        for ((name, tone) in Synth.TONES) {
            val dA = Synth.rednessFor(tone, 14.0)
            val img = Synth.skin(400, tone, listOf(spot), deltaA = dA, seed = name.length)
            val r = SkinMeasure.measure(img, circle)
            assertTrue("$name coverage ${r.coverage}", abs(r.coverage - truthCoverage) < 0.06)
            assertEquals("$name spots", 1, r.spots)
            assertTrue("$name deltaA ${r.deltaA} vs $dA", abs(r.deltaA - dA) < 3.0)
            assertTrue("$name words ${r.words}", r.words.any { it.contains("Redder") || it.contains("Much redder") })
        }
    }

    @Test fun plainSkinShowsNoPatchOnAnyTone() {
        for ((name, tone) in Synth.TONES) {
            val r = SkinMeasure.measure(Synth.skin(400, tone, emptyList(), deltaA = 0.0, seed = 7), circle)
            assertTrue("$name coverage ${r.coverage}", r.coverage < 0.01)
        }
    }

    @Test fun countsSeparateSpots() {
        val spots = listOf(Synth.Spot(0.42, 0.42, 0.03), Synth.Spot(0.58, 0.42, 0.03), Synth.Spot(0.5, 0.58, 0.03), Synth.Spot(0.4, 0.6, 0.025))
        val img = Synth.skin(400, Synth.TONES[2].second, spots, deltaA = 14.0)
        assertEquals(4, SkinMeasure.measure(img, circle).spots)
    }

    @Test fun lighterDryPatchIsFoundToo() {
        val img = Synth.skin(400, Synth.TONES[4].second, listOf(spot), deltaA = -2.0, deltaL = 14.0)
        val r = SkinMeasure.measure(img, circle)
        assertTrue(r.coverage > 0.2)
        assertTrue(r.words.toString(), r.words.any { it.contains("Lighter") })
    }

    @Test fun coinGivesAreaInMm2() {
        // A 20 mm coin drawn as a circle of radius 0.05 of a 400 px side = 40 px across, 2 px/mm.
        val img = Synth.skin(400, Synth.TONES[1].second, listOf(spot), deltaA = 14.0)
        val r = SkinMeasure.measure(img, circle, SizeReference(NCircle(0.1, 0.1, 0.05), 20.0))
        val truthMm2 = PI * (0.12 * 400 / 2.0) * (0.12 * 400 / 2.0)
        assertTrue("area ${r.areaMm2} vs $truthMm2", abs(r.areaMm2!! - truthMm2) / truthMm2 < 0.15)
    }

    @Test fun reMeasuringTheSameSpotIsStable() {
        val tone = Synth.TONES[3].second
        val a = SkinMeasure.measure(Synth.skin(400, tone, listOf(spot), 12.0, seed = 1), circle)
        val b = SkinMeasure.measure(Synth.skin(400, tone, listOf(spot), 12.0, seed = 2), circle)
        assertTrue(abs(a.areaOfPhoto - b.areaOfPhoto) / a.areaOfPhoto < Trend.SIZE_MARGIN)
    }
}

class QualityTest {
    @Test fun blurAndDarknessAreCaught() {
        val sharp = Synth.skin(400, Synth.TONES[2].second, listOf(Synth.Spot(0.5, 0.5, 0.1)), 14.0)
        assertTrue(Quality.check(sharp).problems.toString(), Quality.check(sharp).ok)
        val blurred = Synth.boxBlur(sharp, 4)
        assertTrue(PhotoQuality.Problem.BLURRY in Quality.check(blurred).problems)
        val dark = Synth.skin(400, Lab(12.0, 3.0, 4.0), emptyList(), 0.0)
        assertTrue(PhotoQuality.Problem.TOO_DARK in Quality.check(dark).problems)
    }
}

class StoolCardTest {
    private val spot = NCircle(0.5, 0.5, 0.15)

    /** Every swatch, under warm, neutral and cool light, lands in its group once the nappy is used as white. */
    @Test fun everySwatchLandsInItsGroupUnderThreeLights() {
        for (s in StoolCard.swatches) for (light in listOf(Synth.WARM, Synth.NEUTRAL, Synth.COOL)) {
            val img = Synth.onNappy(300, s.lab, light)
            val r = StoolCard.read(img, spot)
            assertTrue("white ref found", r.whiteCorrected)
            assertEquals("${s.id} ${s.label} under ${light.toList()} read ${r.stool} nearest ${r.nearest.id}", s.group, r.group)
        }
    }

    @Test fun warmLightWithoutCorrectionWouldMislead() {
        // The reason the white reference matters: a pale swatch under a warm bulb reads yellow.
        val pale = StoolCard.byId("2")
        val img = Synth.onNappy(300, pale.lab, Synth.WARM)
        val raw = com.nila.skin.core.image.Regions.circleStats(img, spot).median
        assertTrue("uncorrected chroma ${raw.chroma}", raw.chroma > StoolCard.byId("2").lab.chroma + 10)
        assertEquals(StoolGroup.PALE, StoolCard.read(img, spot).group)
    }
}

class EyeReflexTest {
    private val pupil = NCircle(0.5, 0.5, 0.18)

    @Test fun readsRedWhiteAndDark() {
        assertEquals(Reflex.RED, EyeReflex.read(Synth.eye(200, Lab(45.0, 60.0, 45.0)), pupil).reflex)
        assertEquals(Reflex.WHITE, EyeReflex.read(Synth.eye(200, Lab(88.0, 2.0, 18.0)), pupil).reflex)
        assertEquals(Reflex.DARK, EyeReflex.read(Synth.eye(200, Lab(10.0, 1.0, 1.0)), pupil).reflex)
    }

    @Test fun yellowGlowCountsAsWhite() {
        assertEquals(Reflex.WHITE, EyeReflex.read(Synth.eye(200, Lab(82.0, 4.0, 45.0)), pupil).reflex)
    }
}

class YellownessTest {
    @Test fun eyeWhiteYellownessSurvivesWarmLight() {
        val sclera = Synth.Spot(0.5, 0.5, 0.15)
        val white = Synth.Spot(0.15, 0.15, 0.1)
        val normal = Synth.skin(300, Lab(85.0, 1.0, 6.0), emptyList(), 0.0, lighting = Synth.WARM, white = white)
        val yellow = Synth.skin(300, Lab(82.0, 0.0, 28.0), emptyList(), 0.0, lighting = Synth.WARM, white = white)
        val c = NCircle(sclera.cx, sclera.cy, sclera.r)
        val w = NCircle(white.cx, white.cy, white.r * 0.8)
        val rn = Yellowness.read(normal, YellowReading.Site.EYE_WHITE, c, w)
        val ry = Yellowness.read(yellow, YellowReading.Site.EYE_WHITE, c, w)
        assertTrue("normal ${rn.yellowness}", rn.yellowness < 12)
        assertTrue("yellow ${ry.yellowness}", ry.yellowness > 22)
        assertEquals("Looks white", rn.looks)
        assertEquals("Looks clearly yellow", ry.looks)
    }
}

class TrendTest {
    private val day = 86_400_000L

    @Test fun growthIsCalledOnlyPastTheMargin() {
        val s = Trend.summarise(listOf(TrendPoint(0, 1.0, false, 20.0, 3), TrendPoint(day, 1.1, false, 21.0, 3)))
        assertEquals(TrendSummary.Direction.SAME, s.size)
        val g = Trend.summarise(listOf(TrendPoint(0, 1.0, false, 20.0, 3), TrendPoint(2 * day, 1.7, false, 28.0, 8)))
        assertEquals(TrendSummary.Direction.BIGGER, g.size)
        assertEquals(TrendSummary.Direction.BIGGER, g.redness)
        assertTrue(g.spreadingFast)
        val f = Trend.summarise(listOf(TrendPoint(0, 1.0, false, 20.0, 3), TrendPoint(5 * day, 0.5, false, 12.0, 1)))
        assertEquals(TrendSummary.Direction.SMALLER, f.size)
        assertTrue(f.sentence, f.sentence.contains("50% smaller"))
    }

    @Test fun singlePhotoAsksForAnother() {
        assertEquals(TrendSummary.Direction.UNKNOWN, Trend.summarise(listOf(TrendPoint(0, 1.0, false, 20.0, 3))).size)
    }

    @Test fun doesNotCompareCoinAndNoCoinSizes() {
        val s = Trend.summarise(listOf(TrendPoint(0, 300.0, true, 20.0, 3), TrendPoint(day, 0.02, false, 20.0, 3)))
        assertEquals(TrendSummary.Direction.UNKNOWN, s.size)
    }

    @Suppress("unused") private fun unusedFalse() = assertFalse(false)
}
