package com.nila.assistant

import org.junit.Test

/**
 * What score does a *correct* retrieval actually get?
 *
 * The pipeline's retrieval floor decides when a passage is good enough to show
 * a reader or hand a model, and it was first set from forty-two probes. These
 * are the app's own retrieval cases -- questions written the way people type
 * them, each with the document that ought to win -- which is a much better
 * sample of what "correct" scores like.
 *
 * Prints a distribution rather than asserting a number, because the number is
 * the thing being chosen.
 */
class FloorCalibrationTest {

    private val index = Corpus.index

    private val cases get() = CASES

    companion object {
        /** Shared with [RetrievalGateTest]; these are the app's own cases. */
        val CASES = listOf(
        "why does my baby cry so much" to "cry-normal",
        "is it normal for a newborn to cry for hours" to "cry-normal",
        "baby wont stop crying at night" to "cry-soothe",
        "how do i settle a crying baby" to "cry-soothe",
        "what is colic" to "cry-colic",
        "my baby cries for 3 hours every evening" to "cry-colic",
        "how much should a newborn sleep" to "sleep-amount",
        "how many hours do babies sleep" to "sleep-amount",
        "what position should my baby sleep in" to "sleep-safe",
        "is it safe to share a bed with my baby" to "sleep-safe",
        "can my baby sleep on her tummy" to "sleep-safe",
        "when can i start solid food" to "feed-solids",
        "what age do babies start eating" to "feed-solids",
        "what foods should i avoid giving my baby" to "feed-avoid",
        "can babies have honey" to "feed-avoid",
        "can i give cows milk to my baby" to "feed-avoid",
        "what foods are a choking risk" to "feed-choking",
        "are grapes safe for a baby" to "feed-choking",
        "how do i know my baby is hungry" to "feed-hunger-cues",
        "how often should a newborn feed" to "feed-exclusive",
        "does my baby need water" to "feed-exclusive",
        "my baby has a fever" to "health-fever",
        "temperature of 38 in a 2 month old" to "health-fever",
        "when should i take my baby to hospital" to "health-redflags",
        "what are the warning signs in a baby" to "health-redflags",
        "how do i know if my baby is dehydrated" to "health-dehydration",
        "baby has fewer wet nappies" to "health-dehydration",
        "what vaccines does my baby need" to "health-vaccines",
        "immunisation schedule india" to "health-vaccines",
        "how warm should the room be" to "health-temperature",
        "how many layers should my baby wear" to "health-temperature",
        "when do babies start rolling over" to "dev-milestones",
        "when should my baby smile" to "dev-milestones",
        "how much tummy time does a baby need" to "safety-tummy",
        "is this toy safe for my baby" to "safety-toys",
        "how do i know if a toy is a choking hazard" to "safety-toys",
        "what should i eat while breastfeeding" to "mum-diet",
        "can i drink coffee while breastfeeding" to "mum-diet",
        "does spicy food make my baby fussy" to "mum-fussy-baby",
        "my breast is red and painful" to "mum-mastitis",
        "i have a blocked duct" to "mum-mastitis",
        "i dont think i have enough milk" to "mum-supply",
        "how can i increase my milk supply" to "mum-supply",
        "i feel low and tearful since the birth" to "mum-mood",
        "when will i feel normal again after delivery" to "mum-recovery",
            "how do i cope with no sleep" to "mum-sleep",
        )
    }

    @Test
    fun `score distribution of correct and incorrect top hits`() {
        val right = mutableListOf<Pair<Double, String>>()
        val wrong = mutableListOf<Triple<Double, String, String>>()

        for ((question, expected) in cases) {
            val hits = index.retrieveGuidance(question, audience = null, babyAgeMonths = null)
            val top = hits.firstOrNull()
            if (top == null) {
                wrong += Triple(0.0, question, "nothing retrieved")
                continue
            }
            if (top.doc.id == expected) right += top.score to question
            else wrong += Triple(top.score, question, "got ${top.doc.id}, wanted $expected")
        }

        right.sortBy { it.first }
        println("\n=== CORRECT top hits (${right.size}) ===")
        right.forEach { (score, q) -> println("  %6.2f  %s".format(score, q)) }
        println("\n=== WRONG top hits (${wrong.size}) ===")
        wrong.sortedBy { it.first }.forEach { (score, q, why) ->
            println("  %6.2f  %-46s %s".format(score, q, why))
        }

        val scores = right.map { it.first }
        listOf(2.8, 4.0, 5.0, 6.0, 6.5, 7.0).forEach { floor ->
            val kept = scores.count { it >= floor }
            println("floor %.1f keeps %d / %d correct hits".format(floor, kept, scores.size))
        }
    }
}
