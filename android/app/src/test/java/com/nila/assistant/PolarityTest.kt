package com.nila.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The polarity check has to catch an inversion and only an inversion.
 *
 * Both halves of that matter and they pull against each other. The first case
 * is the failure that took generation out of this app once: a sentence built
 * entirely from words in the source, saying the opposite of it. The other two
 * are answers a 2B model produced that were correct and honest, and that a
 * sentence-level version of this check threw away because the model had merged
 * two source sentences and put a "no" in the other half.
 */
class PolarityTest {

    private val hearingRecord =
        "Newborn hearing screening was passed. Development: social smile present, " +
        "good head control, rolling front to back."

    private val dietPassage =
        "There is no list of foods a breastfeeding parent must avoid. Eat a varied " +
        "diet and drink to thirst. You need roughly 300-500 extra calories a day. " +
        "Continue a vitamin D supplement."

    @Test
    fun `an inverted record is still rejected`() {
        assertFalse(
            Grounding.isGrounded(
                "The baby has not passed the newborn hearing screening, which is a " +
                    "sign of potential hearing issues.",
                hearingRecord,
                floor = 0.0,
            )
        )
    }

    @Test
    fun `a negation in another clause is not an inversion`() {
        assertTrue(
            Grounding.isGrounded(
                "You should eat a varied diet that includes iron-rich foods. While " +
                    "there is no specific list of foods you must avoid, it is important " +
                    "to drink plenty of fluids and consume roughly 300 to 500 extra " +
                    "calories a day.",
                dietPassage,
                floor = 0.0,
            )
        )
    }

    @Test
    fun `saying what the records do not contain is not an inversion`() {
        assertTrue(
            Grounding.isGrounded(
                "While she passed a newborn hearing screen, there are no records " +
                    "provided regarding other developmental milestones.",
                hearingRecord,
                floor = 0.0,
            )
        )
    }
}
