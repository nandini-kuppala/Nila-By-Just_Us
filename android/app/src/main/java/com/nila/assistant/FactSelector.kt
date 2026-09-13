package com.nila.assistant

/**
 * Which of this family's facts bear on *this* question.
 *
 * The pipeline used to hand the model everything it knew on every question,
 * and a 2B model given a fact will use it. Asked whether the baby passed her
 * hearing screen it answered correctly and then added "I also know that you
 * have asthma and iron deficiency anaemia, so please take care with your diet".
 * Asked whether her iron tablets could be upsetting her stomach it reached for
 * the crying log and concluded the tablets were "likely unrelated to your baby
 * Meera" -- an answer to a question nobody asked, because the baby was in the
 * prompt and so the baby had to be in the answer.
 *
 * Retrieval is the answer to that, and it is the same answer the corpus
 * already gets: select what is relevant, do not supply the rest. The selection
 * is keyword-driven rather than learned, for the same reason the router is --
 * it has to be auditable, and being wrong about which facts to show is cheaper
 * than being slow about it.
 *
 * What survives selection is small on purpose. Three relevant lines beat ten
 * lines with three relevant ones in them.
 */
object FactSelector {

    /** Topics a log line belongs to, and the words that ask about them. */
    private val FEEDING = setOf(
        "feed", "fed", "feeding", "feeds", "milk", "supply", "hungry", "hunger",
        "breastfeed", "breastfeeding", "nursing", "latch", "eat", "eating",
        "solids", "weight", "growing", "grow", "enough",
    )
    private val NAPPIES = setOf(
        "nappy", "nappies", "diaper", "diapers", "wee", "wet", "poo", "poop",
        "stool", "stools", "constipated", "constipation", "changed", "change",
    )
    private val SLEEPING = setOf(
        "sleep", "sleeping", "slept", "asleep", "nap", "naps", "wake", "waking",
        "woke", "night", "nights", "bedtime", "tired", "settle", "settling",
    )
    private val CRYING = setOf(
        "cry", "cries", "crying", "cried", "colic", "colicky", "fussy", "fuss",
        "unsettled", "scream", "screaming", "worried", "worry", "upset",
    )

    /** Words that mean the question is about the mother's own body or health. */
    private val ABOUT_HER = setOf(
        "i", "me", "my", "myself", "mine", "mum", "mom", "mother",
        "breastfeeding", "breast", "breasts", "nipple", "supply", "milk",
        "medicine", "medicines", "medication", "tablet", "tablets", "supplement",
        "supplements", "asthma", "anaemia", "anemia", "iron", "allergy",
        "allergic", "recovery", "postnatal", "mood", "period", "bleeding",
    )

    /**
     * Cut [facts] down to what this question reaches.
     *
     * The baby's name and age always survive: they are not a fact the answer
     * is about, they are the frame it has to be written in. Everything else
     * has to earn its place in the prompt.
     */
    fun select(
        question: String,
        subject: QueryRouter.Subject,
        facts: AskMemory.Facts,
    ): AskMemory.Facts {
        val words = question.lowercase().split(Regex("[^a-z]+")).filterNot(String::isEmpty).toSet()

        fun asks(topic: Set<String>) = words.any { it in topic }

        val aboutBaby = subject != QueryRouter.Subject.MOTHER
        val aboutMother = subject == QueryRouter.Subject.MOTHER ||
            subject == QueryRouter.Subject.EITHER && words.any { it in ABOUT_HER }

        val baby = facts.baby.filter { line ->
            when {
                // The frame.
                line.startsWith("${facts.babyName} is ") -> true
                line.contains("last fed") -> aboutBaby && asks(FEEDING)
                line.contains("was last changed") -> aboutBaby && asks(NAPPIES)
                line.contains("woke") -> aboutBaby && asks(SLEEPING)
                line.contains("crying episodes") -> aboutBaby && (asks(CRYING) || asks(SLEEPING))
                // Health notes: allergies, feeding method, screening results.
                else -> aboutBaby
            }
        }

        return facts.copy(
            baby = baby,
            mother = if (aboutMother) facts.mother else emptyList(),
        )
    }
}
