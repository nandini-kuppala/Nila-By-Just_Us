package com.nila.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard rails, written from the cases that actually failed.
 *
 * Every test below is a photograph from the eighteen-image set the vision work
 * was measured on, and each one is a case where the model's own verdict was
 * wrong in a way that would have cost the feature its credibility: a bath called
 * a drowning, a teething toy called a choking hazard, a cable the model could
 * not name and a staircase it looked straight at and called safe.
 */
class HazardRulesTest {

    @Test
    fun `nothing named is safe`() {
        val verdict = HazardRules.assess("none", context = "The baby is crawling on the grass.")
        assertEquals(HazardRules.Level.SAFE, verdict.level)
    }

    @Test
    fun `a bath with an adult holding the baby is not a drowning`() {
        val verdict = HazardRules.assess(
            hazardLine = "Open water (bathtub)",
            context = "The baby is in a bathtub being held by an adult.",
            modelSaidDanger = true,
        )
        assertEquals(HazardRules.Level.SAFE, verdict.level)
        assertEquals(HazardRules.Kind.WATER, verdict.kind)
    }

    @Test
    fun `a small object that is a toy is not a choking hazard`() {
        val verdict = HazardRules.assess(
            hazardLine = "The baby is holding a small object near the mouth.",
            context = "The baby is lying on a blanket and holding a colourful toy.",
            modelSaidDanger = true,
        )
        assertEquals(HazardRules.Level.UNSURE, verdict.level)
    }

    @Test
    fun `a small object during a meal is not a choking hazard`() {
        val verdict = HazardRules.assess(
            hazardLine = "The baby is holding a small object near their mouth.",
            context = "The baby is eating something with their hands.",
            modelSaidDanger = true,
        )
        assertEquals(HazardRules.Level.UNSURE, verdict.level)
    }

    @Test
    fun `a hazard the model cannot name is still a hazard`() {
        // The photograph is a baby holding a mains cable; the model called it a
        // remote control. Trusting its danger verdict is what keeps that one.
        val verdict = HazardRules.assess(
            hazardLine = "The baby is touching the white remote control.",
            context = "The baby is standing on a rug holding a white object.",
            modelSaidDanger = true,
        )
        assertEquals(HazardRules.Level.DANGER, verdict.level)
        assertEquals(HazardRules.Kind.OTHER, verdict.kind)
    }

    @Test
    fun `stairs are a hazard even when the model says it is safe`() {
        val verdict = HazardRules.assess(
            hazardLine = "The baby is touching the stairs.",
            context = "The baby is crawling up a wooden staircase.",
            modelSaidDanger = false,
        )
        assertEquals(HazardRules.Level.DANGER, verdict.level)
        assertEquals(HazardRules.Kind.STAIRS, verdict.kind)
    }

    @Test
    fun `a cable is electrical`() {
        val verdict = HazardRules.assess(
            hazardLine = "The baby is near an electrical cable and a computer.",
            modelSaidDanger = true,
        )
        assertEquals(HazardRules.Kind.ELECTRICAL, verdict.kind)
        assertTrue(verdict.isDanger)
    }

    @Test
    fun `a model that says danger and then names nothing is not believed`() {
        val verdict = HazardRules.assess(
            hazardLine = "none",
            context = "The baby is sitting on the sand.",
            modelSaidDanger = true,
        )
        assertEquals(HazardRules.Level.UNSURE, verdict.level)
    }

    @Test
    fun `a negated hazard line is read as no hazard`() {
        val verdict = HazardRules.assess(
            hazardLine = "The baby is holding a toy, but there is no immediate danger.",
            modelSaidDanger = true,
        )
        assertEquals(HazardRules.Level.UNSURE, verdict.level)
    }

    @Test
    fun `two looks are needed before anybody is woken`() {
        val confirmer = HazardRules.Confirmer()
        val danger = HazardRules.assess("stairs", modelSaidDanger = true)
        val safe = HazardRules.assess("none")

        assertEquals(false, confirmer.add(danger))
        assertEquals(true, confirmer.add(danger))

        confirmer.clear()
        assertEquals(false, confirmer.add(danger))
        assertEquals(false, confirmer.add(safe))
        assertEquals(true, confirmer.add(danger))
    }
}
