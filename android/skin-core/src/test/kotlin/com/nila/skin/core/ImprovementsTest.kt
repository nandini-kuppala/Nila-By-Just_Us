package com.nila.skin.core

import com.nila.skin.core.color.Lab
import com.nila.skin.core.food.Allergen
import com.nila.skin.core.food.AllergenUpkeep
import com.nila.skin.core.food.Exposure
import com.nila.skin.core.food.ReactionEvent
import com.nila.skin.core.food.ReactionPatterns
import com.nila.skin.core.food.Sign
import com.nila.skin.core.food.fisherGreater
import com.nila.skin.core.food.oneIn
import com.nila.skin.core.image.NCircle
import com.nila.skin.core.image.NPoint
import com.nila.skin.core.measure.EyeAngle
import com.nila.skin.core.measure.EyePairReading
import com.nila.skin.core.measure.PupilReading
import com.nila.skin.core.measure.Reflex
import com.nila.skin.core.measure.SkinMeasure
import com.nila.skin.core.measure.SpotFinder
import com.nila.skin.core.measure.StoolCard
import com.nila.skin.core.measure.StoolGroup
import com.nila.skin.core.measure.Trend
import com.nila.skin.core.measure.TrendPoint
import com.nila.skin.core.measure.TrendSummary
import com.nila.skin.core.rules.EyeRules
import com.nila.skin.core.rules.Step
import com.nila.skin.core.synth.Synth
import com.nila.skin.core.vlm.Agreement
import com.nila.skin.core.vlm.VisionForms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/** Uneven light: the plane fit against the old single-median reference. */
class LightingModelTest {

    private val spot = Synth.Spot(0.5, 0.5, 0.12)
    private val circle = NCircle(0.5, 0.5, 0.22)
    private val trueCov = PI * 0.12 * 0.12 / (PI * 0.22 * 0.22)

    @Test fun planeFitHoldsUnderUnevenLightOnEveryTone() {
        for ((i, t) in Synth.TONES.withIndex()) {
            val (name, tone) = t
            val dA = Synth.rednessFor(tone, 14.0)
            val img = Synth.skin(400, tone, listOf(spot), deltaA = dA, seed = 200 + i, gradient = 0.35)
            val plane = SkinMeasure.measure(img, circle)
            val flat = SkinMeasure.measure(img, circle, lightingModel = false)
            println("tone $name  true ${"%.3f".format(trueCov)}  plane cov ${"%.3f".format(plane.coverage)} spots ${plane.spots} dA ${"%.1f".format(plane.deltaA)}" +
                "  |  flat cov ${"%.3f".format(flat.coverage)} spots ${flat.spots} dA ${"%.1f".format(flat.deltaA)} dL ${"%.1f".format(flat.deltaL)}")
            assertTrue("tone $name coverage ${plane.coverage}", abs(plane.coverage - trueCov) < 0.06)
            assertEquals("tone $name spots", 1, plane.spots)
            assertTrue("tone $name redness ${plane.deltaA} vs $dA", abs(plane.deltaA - dA) < 3)
        }
    }

    @Test fun aDarkerMarkUnderSideLightIsMissedByTheFlatReference() {
        // A brown mark (darker, barely redder) with a window to one side: the
        // light slope inflates the flat reference's spread until the mark
        // hides inside it. The plane takes the slope out first.
        for ((i, t) in Synth.TONES.withIndex()) {
            val img = Synth.skin(400, t.second, listOf(spot), deltaA = 1.0, deltaL = -12.0, seed = 7 + i, gradient = 0.4)
            val plane = SkinMeasure.measure(img, circle)
            val flat = SkinMeasure.measure(img, circle, lightingModel = false)
            println("tone ${t.first} brown mark, side light: plane ${"%.3f".format(plane.coverage)}/${plane.spots}, flat ${"%.3f".format(flat.coverage)}/${flat.spots}")
            assertTrue(abs(plane.coverage - trueCov) < 0.06)
            assertEquals(1, plane.spots)
            assertTrue(plane.words.first().contains("Darker"))
            assertTrue(flat.coverage < 0.05)
        }
    }

    @Test fun evenLightIsUnchanged() {
        val tone = Synth.TONES[3].second
        val img = Synth.skin(400, tone, listOf(spot), deltaA = Synth.rednessFor(tone, 14.0), seed = 9)
        val plane = SkinMeasure.measure(img, circle)
        val flat = SkinMeasure.measure(img, circle, lightingModel = false)
        assertEquals(flat.coverage, plane.coverage, 0.03)
        assertTrue(plane.lightSlope < 2.0)
    }

    @Test fun spotFinderIgnoresUnevenLight() {
        val tone = Synth.TONES[4].second
        val img = Synth.skin(400, tone, listOf(Synth.Spot(0.62, 0.4, 0.1)), deltaA = Synth.rednessFor(tone, 15.0), seed = 3, gradient = 0.35)
        val c = SpotFinder.suggest(img)
        assertNotNull(c)
        assertTrue("found $c", abs(c!!.cx - 0.62) < 0.06 && abs(c.cy - 0.4) < 0.06)
    }
}

class RobustTrendTest {

    private val day = 86_400_000L

    @Test fun oneBadFirstPhotoDoesNotMakeASteadySpotLookBigger() {
        // Five days of the same size; the first photo was framed from further away.
        val sizes = listOf(0.010, 0.020, 0.021, 0.019, 0.020)
        val pts = sizes.mapIndexed { i, s -> TrendPoint(i * day, s, false, 30.0, 1) }
        val firstLast = sizes.last() / sizes.first()
        assertTrue("first vs last alone says ${firstLast}x", firstLast >= 1.25)
        val t = Trend.summarise(pts)
        assertTrue(t.robust)
        assertEquals(t.sentence, TrendSummary.Direction.SAME, t.size)
    }

    @Test fun steadyGrowthIsSeenWithARate() {
        val pts = listOf(0.01, 0.014, 0.0196, 0.0274).mapIndexed { i, s -> TrendPoint(i * day, s, false, 30.0, 3) }
        val t = Trend.summarise(pts)
        assertEquals(TrendSummary.Direction.BIGGER, t.size)
        assertEquals(1.4, t.sizePerDay!!, 0.02)
    }

    @Test fun theilSenIgnoresOneOutlier() {
        val slope = Trend.theilSen(listOf(0.0, 1.0, 2.0, 3.0, 4.0), listOf(0.0, 1.0, 2.0, 30.0, 4.0))
        assertEquals(1.0, slope, 1e-9)
    }
}

class FisherTest {

    @Test fun matchesAHandWorkedTable() {
        // 2 of 3 egg exposures followed; 2 of 67 others. Hypergeometric tail by hand:
        // [C(4,2)C(66,1) + C(4,3)C(66,0)] / C(70,3) = 400 / 54740.
        assertEquals(400.0 / 54740.0, fisherGreater(2, 3, 2, 67), 1e-12)
    }

    @Test fun noDifferenceIsNotRare() {
        assertTrue(fisherGreater(1, 10, 10, 100) > 0.3)
        assertEquals(1.0, fisherGreater(0, 5, 3, 50), 1e-12)
    }

    @Test fun plainWords() {
        assertEquals("about 1 in 135", oneIn(400.0 / 54740.0))
        assertEquals("about half the time", oneIn(0.7))
    }

    @Test fun twoReactionsInManyTriesIsNotAPattern() {
        // A food given 4 times with 2 followed, but reactions are common after everything.
        val h = 3_600_000L
        val ex = ArrayList<Exposure>()
        val reactions = ArrayList<ReactionEvent>()
        for (d in 0 until 8) {
            val t = d * 24 * h
            ex += Exposure("rice", t, d == 0)
            if (d % 2 == 0) ex += Exposure("egg_yolk", t, d == 0)
            if (d % 2 == 0 || d == 3) reactions += ReactionEvent(t + h, setOf(Sign.REDNESS))
        }
        val egg = ReactionPatterns.analyse(ex, reactions).first { it.foodId == "egg_yolk" }
        assertTrue("chance ${egg.chance}", egg.chance > 0.05)
        assertFalse(egg.strength == ReactionPatterns.Pattern.Strength.PATTERN)
    }
}

class AllergenUpkeepTest {

    private val day = 86_400_000L

    @Test fun nudgesWhenAnAllergenDropsOutAndPausesAfterAReaction() {
        val now = 30 * day
        val given = listOf(
            AllergenUpkeep.Given(setOf(Allergen.PEANUT), now - 2 * day),
            AllergenUpkeep.Given(setOf(Allergen.PEANUT), now - 5 * day),
            AllergenUpkeep.Given(setOf(Allergen.WHEAT), now - 12 * day),
            AllergenUpkeep.Given(setOf(Allergen.EGG), now - 3 * day),
        )
        val s = AllergenUpkeep.of(given, setOf(Allergen.EGG), now).associateBy { it.allergen }
        assertEquals(2, s[Allergen.PEANUT]!!.lastWeek)
        assertTrue(s[Allergen.PEANUT]!!.note(now)!!.contains("Keep it going"))
        assertTrue(s[Allergen.WHEAT]!!.note(now)!!.contains("12 days"))
        assertTrue(s[Allergen.EGG]!!.note(now)!!.contains("Paused"))
        assertNull(s[Allergen.SESAME]!!.note(now))
    }
}

class EyeAngleTest {

    private fun p(x: Double, y: Double = 0.4) = NPoint(x, y)

    @Test fun straightAndSideways() {
        val straight = EyeAngle.of(p(0.35), p(0.30), p(0.40), p(0.65), p(0.60), p(0.70), p(0.50, 0.55), 1.0)
        assertTrue(straight.toString(), straight.straight)
        // Both irises shifted towards the photo's right by a fifth of an eye width.
        val side = EyeAngle.of(p(0.37), p(0.30), p(0.40), p(0.67), p(0.60), p(0.70), p(0.52, 0.55), 1.0)
        assertFalse(side.straight)
        assertTrue(side.words().contains("side"))
        val turned = EyeAngle.of(p(0.35), p(0.30), p(0.40), p(0.65), p(0.60), p(0.70), p(0.62, 0.55), 1.0)
        assertTrue(turned.words().contains("Head turned"))
    }

    private fun pupil(r: Reflex) = PupilReading(r, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

    @Test fun offAxisWhiteExplainsButNeverLowersTheAdvice() {
        val side = EyeAngle(0.2, 0.2, 0.0)
        val one = EyeRules.advise(EyeRules.Input(EyePairReading(pupil(Reflex.WHITE), pupil(Reflex.RED)), 1, 0, emptyMap(), side))
        assertEquals(Step.RECHECK, one.step)
        assertTrue(one.reasons.any { it.text.contains("optic nerve") })
        // Seen twice in the same eye: still "book an eye check", angle or not.
        val twice = EyeRules.advise(EyeRules.Input(EyePairReading(pupil(Reflex.WHITE), pupil(Reflex.RED)), 2, 0, emptyMap(), side))
        assertEquals(Step.DOCTOR_SOON, twice.step)
        // Looking straight: no angle note.
        val straight = EyeRules.advise(EyeRules.Input(EyePairReading(pupil(Reflex.WHITE), pupil(Reflex.RED)), 1, 0, emptyMap(), EyeAngle(0.0, 0.0, 0.0)))
        assertFalse(straight.reasons.any { it.text.contains("optic nerve") })
    }
}

class AgreementTest {

    private val tone = Synth.TONES[2].second
    private val red = SkinMeasure.measure(Synth.skin(400, tone, listOf(Synth.Spot(0.5, 0.5, 0.12)), deltaA = 14.0, seed = 1), NCircle(0.5, 0.5, 0.22))
    private val plain = SkinMeasure.measure(Synth.skin(400, tone, emptyList(), deltaA = 0.0, seed = 2), NCircle(0.5, 0.5, 0.22))

    private fun ai(colour: String, pattern: String) = VisionForms.parseSkin("COLOUR: $colour\nSURFACE: flat\nPATTERN: $pattern\nPHOTO: clear")

    @Test fun levels() {
        assertEquals(Agreement.Level.AGREE, Agreement.skin(red, ai("red", "patch")).level)
        assertEquals(Agreement.Level.PARTLY, Agreement.skin(red, ai("red", "many small spots")).level)
        assertEquals(Agreement.Level.DISAGREE, Agreement.skin(red, ai("skin-coloured", "none")).level)
        assertEquals(Agreement.Level.AGREE, Agreement.skin(plain, ai("skin-coloured", "none")).level)
        assertEquals(Agreement.Level.NO_AI, Agreement.skin(red, null).level)
    }
}

class VerifyTest {
    @Test fun yesNo() {
        assertEquals(true, VisionForms.parseYesNo("Yes."))
        assertEquals(false, VisionForms.parseYesNo("No, there is not."))
        assertEquals(true, VisionForms.parseYesNo("I can see it, yes"))
        assertEquals(false, VisionForms.parseYesNo("There is not any chutney"))
        assertNull(VisionForms.parseYesNo("Maybe"))
        assertTrue(VisionForms.verifyPrompt("idli").contains("idli"))
    }
}

class WhiteCorrectionTest {

    @Test fun paleStoolInADimWarmRoomIsMissedWithoutCorrection() {
        val dimWarm = doubleArrayOf(0.4, 0.32, 0.22)
        var rawRight = 0; var fixedRight = 0
        for (s in StoolCard.swatches) {
            val img = Synth.onNappy(320, s.lab, dimWarm)
            val spot = NCircle(0.5, 0.5, 0.15)
            if (StoolCard.read(img, spot, correct = false).group == s.group) rawRight++
            if (StoolCard.read(img, spot).group == s.group) fixedRight++
        }
        println("dim warm room: uncorrected $rawRight/13, corrected $fixedRight/13")
        val five = Synth.onNappy(320, StoolCard.byId("5").lab, dimWarm)
        assertEquals(StoolGroup.NORMAL, StoolCard.read(five, NCircle(0.5, 0.5, 0.15), correct = false).group)
        assertEquals(StoolGroup.PALE, StoolCard.read(five, NCircle(0.5, 0.5, 0.15)).group)
        assertEquals(13, fixedRight)
    }

    @Test fun showsWhereTheWhiteCameFrom() {
        val img = Synth.onNappy(320, StoolCard.byId("8").lab, Synth.NEUTRAL)
        val r = StoolCard.read(img, NCircle(0.5, 0.5, 0.15))
        assertTrue(r.whitePoints.isNotEmpty())
        // None of the white pixels is inside the poo (radius 0.22 of the picture).
        assertTrue(r.whitePoints.all { kotlin.math.hypot(it.x - 0.5, it.y - 0.5) > 0.21 })
    }

    @Test fun yellowEyeUnderWarmLightNeedsTheReference() {
        val sclera = Lab(85.0, 1.0, 26.0)
        val img = Synth.skin(300, sclera, emptyList(), deltaA = 0.0, noise = 0.8, lighting = Synth.NEUTRAL, white = Synth.Spot(0.15, 0.15, 0.1))
        val r = com.nila.skin.core.measure.Yellowness.read(img, com.nila.skin.core.measure.YellowReading.Site.EYE_WHITE, NCircle(0.6, 0.6, 0.15), NCircle(0.15, 0.15, 0.08))
        assertTrue(r.whiteCorrected)
        assertEquals(26.0, r.yellowness, 3.0)
    }
}
