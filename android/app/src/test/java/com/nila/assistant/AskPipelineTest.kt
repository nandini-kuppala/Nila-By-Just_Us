package com.nila.assistant

import com.nila.assistant.Grounding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two pure decisions the new Ask pipeline turns on.
 *
 * Which questions go to the model, and what the model is allowed to have said.
 * Both are answered without a phone or a half-gigabyte of weights, which is
 * the only reason they can be checked at all.
 */
class AskPipelineTest {

    // --------------------------------------------------- the personal fork

    @Test
    fun `general questions stay with the corpus`() {
        listOf(
            "How much crying is normal?",
            "What can I eat while breastfeeding?",
            "Is my milk supply enough?",
            "When should I worry about a fever?",
            "how do i burp a baby",
        ).forEach {
            assertFalse(it, QueryRouter.isPersonal(it))
        }
    }

    @Test
    fun `questions reaching for their own history go to the model`() {
        listOf(
            "Is it safe for me given my asthma?",
            "Can I take this with my allergies?",
            "What did my blood test results say?",
            "Is paracetamol ok for me?",
            "what does her hearing screening mean",
            "is this normal at her age",
        ).forEach {
            assertTrue(it, QueryRouter.isPersonal(it))
        }
    }

    /**
     * "My baby" is in almost every question on this screen. A rule that fires
     * on it sends everything through a 0.5B model for no benefit.
     */
    @Test
    fun `a bare possessive is not personalisation`() {
        assertFalse(QueryRouter.isPersonal("how much should my baby sleep"))
        assertFalse(QueryRouter.isPersonal("why does she cry in the evening"))
    }

    // ------------------------------------------------------- the guardrail

    @Test
    fun `an invented dose is rejected with no corpus behind it`() {
        assertFalse(
            Grounding.inventsNoQuantities(
                "Give 5 ml of paracetamol every four hours.",
                "Mother is allergic to: Penicillin",
            )
        )
    }

    @Test
    fun `a quantity that was given is allowed through`() {
        assertTrue(
            Grounding.inventsNoQuantities(
                "Two to three hours of crying a day is within the normal range.",
                "Normal crying peaks at 2-3 hours a day around six weeks.",
            )
        )
    }

    /**
     * The failure that took generation out of this app once before: a sentence
     * built entirely from source words that says the opposite of the source.
     * It is caught now because the family's own facts are part of what the
     * answer is checked against, not just the corpus passages.
     */
    @Test
    fun `a flipped fact about her own record is still caught`() {
        val source = """
            Newborn hearing screening: passed, both ears.
            Babies who fail a hearing screen are referred for further testing.
        """.trimIndent()
        assertFalse(
            Grounding.isGrounded(
                "Your baby has not passed the newborn hearing screening, " +
                    "which is a sign of potential hearing issues.",
                source,
            )
        )
    }

    @Test
    fun `a faithful answer over the same facts passes`() {
        val source = """
            Newborn hearing screening: passed, both ears.
            Babies who fail a hearing screen are referred for further testing.
        """.trimIndent()
        assertTrue(
            Grounding.isGrounded(
                "Her newborn hearing screening passed in both ears.",
                source,
            )
        )
    }

    // --------------------------------------------------- what reaches the card

    /** The one that reached the screen: backslash-n printed as two characters. */
    @Test
    fun `a literal backslash-n becomes a real break`() {
        val out = ModelText.tidy("Feed her more often.\\nIf it continues, ask your doctor.")
        assertFalse(out, out.contains("\\n"))
        assertEquals(2, out.lines().size)
    }

    @Test
    fun `real line breaks survive`() {
        val out = ModelText.tidy("One paragraph.\n\nAnother paragraph.")
        assertEquals(2, out.split("\n\n").size)
    }

    @Test
    fun `the scaffolding it repeats back is stripped`() {
        assertEquals(
            "Two to three hours a day is normal.",
            ModelText.tidy("ANSWER: Two to three hours a day is normal."),
        )
    }

    @Test
    fun `it does not carry on into a fresh prompt`() {
        val out = ModelText.tidy(
            "Two to three hours a day is normal.\nQUESTION: what about sleep?"
        )
        assertFalse(out, out.contains("QUESTION"))
    }

    @Test
    fun `markdown asked against and supplied anyway is cleaned up`() {
        val out = ModelText.tidy("## Heading\n**Bold** advice here.\n* a point")
        assertFalse(out, out.contains("*"))
        assertFalse(out, out.contains("#"))
        // Bullets are normalised, not flattened: the card draws them.
        assertTrue(out, out.contains("- a point"))
        assertTrue(out, out.startsWith("Heading"))
    }

    @Test
    fun `runs of blank lines collapse`() {
        assertEquals("One.\n\nTwo.", ModelText.tidy("One.\n\n\n\nTwo."))
    }

    // ------------------------------------------- the all-clear it cannot give

    private val herFacts = listOf(
        "Mother's conditions: Asthma, Iron deficiency anaemia",
        "Mother is allergic to: Penicillin",
        "Baby is 4 months old",
    )

    /** Verbatim, from the first time the ungrounded path ran on a phone. */
    @Test
    fun `an invented all-clear about her own condition is refused`() {
        assertFalse(
            Grounding.reassuresNobody(
                "In this case, your baby is not experiencing any health issues, " +
                    "and your asthma is not a problem while breastfeeding.",
                herFacts,
            )
        )
    }

    @Test
    fun `it will not clear an allergy either`() {
        assertFalse(
            Grounding.reassuresNobody(
                "Your penicillin allergy is not a concern here.",
                herFacts,
            )
        )
    }

    @Test
    fun `describing what is generally known is still allowed`() {
        assertTrue(
            Grounding.reassuresNobody(
                "Most mothers with asthma can breastfeed. Inhalers are usually " +
                    "continued, but your own doctor should confirm what is right " +
                    "for you.",
                herFacts,
            )
        )
    }

    @Test
    fun `an answer with no reassurance in it passes untouched`() {
        assertTrue(
            Grounding.reassuresNobody(
                "Babies this age often feed every two to three hours.",
                herFacts,
            )
        )
    }
}
