package com.nila.skin.core

import com.nila.skin.core.food.Allergen
import com.nila.skin.core.food.AllergyRules
import com.nila.skin.core.food.DietDiversity
import com.nila.skin.core.food.Exposure
import com.nila.skin.core.food.FoodDb
import com.nila.skin.core.food.FoodGroup
import com.nila.skin.core.food.FoodSafety
import com.nila.skin.core.food.Nutrition
import com.nila.skin.core.food.PlateItem
import com.nila.skin.core.food.ReactionEvent
import com.nila.skin.core.food.ReactionPatterns
import com.nila.skin.core.food.Sign
import com.nila.skin.core.measure.EyePairReading
import com.nila.skin.core.measure.PupilReading
import com.nila.skin.core.measure.Reflex
import com.nila.skin.core.measure.StoolCard
import com.nila.skin.core.rules.Answer
import com.nila.skin.core.rules.BodyArea
import com.nila.skin.core.rules.ConcernKind
import com.nila.skin.core.rules.EyeRules
import com.nila.skin.core.rules.JaundiceRules
import com.nila.skin.core.rules.SkinRules
import com.nila.skin.core.rules.Source
import com.nila.skin.core.rules.Step
import com.nila.skin.core.rules.StoolRules
import com.nila.skin.core.vlm.VisionForms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinRulesTest {
    private fun advise(kind: ConcernKind = ConcernKind.RASH, area: BodyArea = BodyArea.CHEST_BELLY, age: Int = 200,
                       answers: Map<String, Answer> = emptyMap(), first: Boolean = true) =
        SkinRules.advise(SkinRules.Input(kind, area, age, answers, null, null, firstPhoto = first))

    @Test fun nothingWorryingMeansCheckAgain() = assertEquals(Step.RECHECK, advise().step)

    @Test fun nonFadingRashIsUrgent() {
        val a = advise(answers = mapOf("glass" to Answer.YES))
        assertEquals(Step.URGENT, a.step)
        assertTrue(Source.NHS_RASH in a.sources)
    }

    @Test fun feverUnderThreeMonthsIsUrgentButNotAtSixMonths() {
        assertEquals(Step.URGENT, advise(age = 60, answers = mapOf("fever" to Answer.YES)).step)
        assertEquals(Step.DOCTOR_TODAY, advise(age = 180, answers = mapOf("fever" to Answer.YES)).step)
    }

    @Test fun blistersMeanToday() = assertEquals(Step.DOCTOR_TODAY, advise(answers = mapOf("blisters" to Answer.YES)).step)

    @Test fun birthmarkNearTheEyeIsSeenEarly() {
        val a = advise(kind = ConcernKind.BIRTHMARK, area = BodyArea.NEAR_EYE, age = 30)
        assertEquals(Step.DOCTOR_SOON, a.step)
        assertTrue(Source.AAP_HEMANGIOMA in a.sources)
    }

    @Test fun everyRaisedStepHasASourceOrIsMeasured() {
        val a = advise(answers = SkinRules.questions(ConcernKind.RASH).associate { it.id to Answer.YES })
        assertTrue(a.reasons.all { it.source != null || it.text.contains("photos") })
    }
}

class OtherRulesTest {
    @Test fun yellowInFirstDayOrPalmsIsUrgent() {
        assertEquals(Step.URGENT, JaundiceRules.advise(JaundiceRules.Input(10, emptyMap(), true, emptyList())).step)
        assertEquals(Step.URGENT, JaundiceRules.advise(JaundiceRules.Input(100, mapOf("palms" to Answer.YES), true, emptyList())).step)
        assertEquals(Step.RECHECK, JaundiceRules.advise(JaundiceRules.Input(100, mapOf("eyes" to Answer.YES), true, emptyList())).step)
        assertEquals(Step.DOCTOR_SOON, JaundiceRules.advise(JaundiceRules.Input(15 * 24, mapOf("eyes" to Answer.YES), true, emptyList())).step)
    }

    @Test fun paleStoolMeansDoctorToday() {
        assertEquals(Step.DOCTOR_TODAY, StoolRules.advise(StoolRules.Input(StoolCard.byId("2"), null, 20)).step)
        assertEquals(Step.WATCH, StoolRules.advise(StoolRules.Input(StoolCard.byId("7"), null, 20)).step)
        assertEquals(Step.WATCH, StoolRules.advise(StoolRules.Input(StoolCard.byId("K"), null, 2)).step) // meconium
        assertEquals(Step.DOCTOR_TODAY, StoolRules.advise(StoolRules.Input(StoolCard.byId("K"), null, 20)).step)
    }

    private fun pupil(r: Reflex) = PupilReading(r, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

    @Test fun oneWhiteEyePhotoMeansRetakeTwoMeanCheck() {
        val one = EyePairReading(pupil(Reflex.WHITE), pupil(Reflex.RED))
        assertEquals(Step.RECHECK, EyeRules.advise(EyeRules.Input(one, 1, 0, emptyMap())).step)
        assertEquals(Step.DOCTOR_SOON, EyeRules.advise(EyeRules.Input(one, 2, 0, emptyMap())).step)
        assertEquals(Step.WATCH, EyeRules.advise(EyeRules.Input(EyePairReading(pupil(Reflex.RED), pupil(Reflex.RED)), 0, 0, emptyMap())).step)
    }
}

class FoodTest {
    private val db = FoodDb.bundled()

    @Test fun tableLoadsWithSources() {
        assertTrue(db.foods.size >= 70)
        assertTrue(db.foods.all { it.source.isNotBlank() && it.unitGrams > 0 })
        assertEquals(3.9, db["ragi_porridge"]!!.per100g.ironMg / 0.12, 0.05)
    }

    @Test fun matchesEverydayNames() {
        assertEquals("khichdi", db.match("moong dal khichdi")?.id)
        assertEquals("roti", db.match("Chapati")?.id)
        assertEquals("curd", db.match("dahi")?.id)
        assertEquals("banana", db.match("sliced banana")?.id)
        assertEquals("ragi_porridge", db.match("ragi")?.id)
        assertNull(db.match("xyzzy plate"))
    }

    @Test fun plateTotalsAndDiversity() {
        val plate = listOf(PlateItem(db["khichdi"]!!, 60.0), PlateItem(db["carrot"]!!, 30.0), PlateItem(db["curd"]!!, 30.0))
        val total = Nutrition.total(plate)
        assertEquals(60 * 1.4038 + 30 * 0.35 + 30 * 0.61, total.kcal, 2.0)
        val d = DietDiversity.of(plate.map { it.food }, breastfedToday = true)
        assertEquals(setOf(FoodGroup.BREASTMILK, FoodGroup.GRAINS, FoodGroup.PULSES, FoodGroup.VITA_FV, FoodGroup.DAIRY), d.groups)
        assertTrue(d.meets)
    }

    @Test fun honeyIsBlockedUnderOne() {
        assertNotNull(FoodSafety.blockedReason(db["honey"]!!, 200))
        assertNull(FoodSafety.blockedReason(db["honey"]!!, 400))
    }

    @Test fun icmrTargets() {
        val t = Nutrition.targets(240)
        assertEquals(3.0, t.ironMg, 0.0); assertEquals(200.0, t.kcalFromFood!!, 0.0)
        assertEquals(8.0, Nutrition.targets(400).ironMg, 0.0)
    }
}

class ReactionTest {
    private val h = 3_600_000L
    private val d = 24 * h

    @Test fun repeatedReactionAfterEggIsAPattern() {
        val ex = listOf(
            Exposure("egg", 0, true), Exposure("rice", 0, false),
            Exposure("egg", 3 * d, false), Exposure("banana", 3 * d + 5 * h, false),
            Exposure("egg", 6 * d, false), Exposure("rice", 7 * d, false), Exposure("banana", 8 * d, false),
        )
        val re = listOf(ReactionEvent(1 * h, setOf(Sign.HIVES)), ReactionEvent(3 * d + h, setOf(Sign.HIVES)))
        val p = ReactionPatterns.analyse(ex, re)
        assertEquals("egg", p.first().foodId)
        assertEquals(ReactionPatterns.Pattern.Strength.PATTERN, p.first().strength)
        val adv = AllergyRules.advise(re.last(), p) { it }
        assertEquals(Step.DOCTOR_SOON, adv.step)
    }

    @Test fun swellingIsAnEmergency() {
        val adv = AllergyRules.advise(ReactionEvent(0, setOf(Sign.SWELLING)), emptyList()) { it }
        assertEquals(Step.URGENT, adv.step)
    }

    @Test fun severeEczemaPromptsDoctorBeforePeanut() {
        val r = AllergyRules.introductionAdvice(150, Answer.YES, setOf(Allergen.EGG))
        assertEquals(Step.DOCTOR_SOON, r.single().step)
    }
}

class VisionFormsTest {
    @Test fun readsTheSkinForm() {
        val d = VisionForms.parseSkin("COLOUR: red\nSURFACE: raised bumps, crusts\nPATTERN: many small spots\nPHOTO: clear")
        assertEquals("red", d.colour)
        assertTrue(VisionForms.Surface.RAISED in d.surfaces && VisionForms.Surface.CRUSTS in d.surfaces)
        assertEquals("many small spots", d.pattern)
        assertTrue(d.suggestsBlistersOrCrusts && d.photoOk)
    }

    @Test fun readsAPlate() {
        val g = VisionForms.parsePlate("1. Rice\n2. Dal\n- carrot pieces\nbanana, dahi", FoodDb.bundled())
        assertEquals(listOf("rice", "dal", "carrot", "banana", "curd"), g.mapNotNull { it.food?.id })
    }

    @Test fun readsKind() = assertEquals(VisionForms.Kind.NAPPY, VisionForms.parseKind("Diaper"))
}
