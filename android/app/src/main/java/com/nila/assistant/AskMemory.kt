package com.nila.assistant

/**
 * What the assistant knows about *this* family, and what it remembers saying.
 *
 * This interface is the whole port boundary between the lab and the app. The
 * lab fills it from the bundled `demo_seed.json`; the app fills it from Room.
 * Everything above it -- routing, retrieval, prompting, the guardrails -- is
 * the same code in both, which is the only way a result measured here means
 * anything there.
 *
 * Four kinds of memory, because they age differently and are trusted
 * differently:
 *
 *  * **Profile** -- who they are. Slow-moving, always relevant, small.
 *  * **Log** -- what happened today. Fast-moving, relevant to a narrow class of
 *    question, and exactly answerable, so it is never paraphrased by a model.
 *  * **Records** -- filed documents. Large, mostly irrelevant to any one
 *    question, so they are *retrieved* rather than supplied.
 *  * **Turns** -- what was already said in this conversation. This is what
 *    makes a follow-up like "and at night?" mean anything.
 */
interface AskMemory {

    /**
     * Everything known about this family, kept in separate piles.
     *
     * Separate piles rather than one list of sentences, because a 2B model
     * handed a flat list loses track of whose fact is whose. Given
     *
     *     - Mother is allergic to: Penicillin
     *     - Noted for Meera: no known allergies
     *
     * it answered a question about the baby's vaccinations with "she is not
     * allergic to penicillin" -- the mother's allergy, moved to the baby, and
     * inverted on the way. Given
     *
     *     - Mother's notes: Six-week check normal
     *     - Last feed 8 minutes ago
     *
     * it produced "with only six weeks and eight minutes passed since your last
     * feed". Both of those are the same mistake, and neither is reachable once
     * the facts arrive under headings that say who they belong to.
     */
    suspend fun facts(): Facts

    /** Who the baby and the mother are. Short lines, prompt-ready. */
    suspend fun profileLines(): List<String>

    /** What has happened recently: last feed, last nappy, today's crying. */
    suspend fun logLines(): List<String>

    /** The exact time of the last event of a kind, or null if none is logged. */
    suspend fun lastEvent(kind: String): Long?

    /** Filed documents this particular question actually reaches. */
    suspend fun recordsFor(question: String, subject: QueryRouter.Subject): List<RecordHit>

    /** The last few exchanges, oldest first. */
    suspend fun recentTurns(limit: Int): List<Turn>

    /** Add an exchange to the conversation memory. */
    suspend fun remember(turn: Turn)

    /** Forget the conversation, keeping the profile, log and records. */
    suspend fun forgetConversation()

    /**
     * One filed document, with whose document it is.
     *
     * The subject is carried because leaving it out cost an answer: asked
     * whether the baby's vaccinations were up to date, the model read the
     * mother's six-week postnatal check in the same list and reported that the
     * baby was "recovering well from her previous postpartum period".
     */
    data class RecordHit(val subject: String, val title: String, val extract: String) {
        val isMother: Boolean get() = subject.equals("MOTHER", ignoreCase = true)
    }

    /**
     * The family, in the three piles the prompt keeps apart.
     *
     * [records] is left out here and retrieved per question instead: the
     * documents are long and mostly irrelevant to any one question, so
     * supplying them all would spend the context budget on noise.
     */
    data class Facts(
        val babyName: String,
        val babyAgeMonths: Int?,
        /** The baby: health notes, last feed, when she woke, today's crying. */
        val baby: List<String>,
        /** The mother: breastfeeding, conditions, allergies, medicines. */
        val mother: List<String>,
    ) {
        val isEmpty: Boolean get() = baby.isEmpty() && mother.isEmpty()

        /** Every line, flat, for the guards -- which care what was said, not by whom. */
        val all: List<String> get() = baby + mother
    }

    data class Turn(val question: String, val answer: String)

    companion object {

        /**
         * Words that appear in every medical document and identify none of them.
         *
         * "How much crying is normal" was opening with the mother's six-week
         * postnatal check, because that report contains the word "normal" -- as
         * every report does. A record quoted inside an answer reads as being
         * *about* the question, so surfacing the wrong one is worse than
         * surfacing none.
         */
        private val GENERIC = setOf(
            "normal", "check", "result", "results", "level", "levels", "report",
            "review", "visit", "value", "range", "within", "test", "tests",
            "patient", "advice", "advised", "follow", "history", "noted", "given",
            "months", "weeks", "clinic", "hospital", "doctor",
        )

        /**
         * Two-term coverage, the rule guidance retrieval already uses.
         *
         * A record has to match more than one distinctive thing about the
         * question before it is quoted. One shared word is a coincidence, and a
         * coincidence presented as "from your records" is worse than a generic
         * answer.
         */
        fun search(
            question: String,
            subject: QueryRouter.Subject,
            records: List<Record>,
            limit: Int = 2,
        ): List<RecordHit> {
            val terms = KnowledgeIndex.tokenize(question)
                .filter { it.length >= 5 && it !in GENERIC }
                .distinct()
                .take(4)
            if (terms.isEmpty()) return emptyList()

            val wanted = when (subject) {
                QueryRouter.Subject.MOTHER -> "MOTHER"
                QueryRouter.Subject.BABY -> "BABY"
                QueryRouter.Subject.EITHER -> null
            }

            val scored = records
                .filter { wanted == null || it.subject.equals(wanted, ignoreCase = true) }
                .map { record ->
                    val haystack = KnowledgeIndex.tokenize(record.searchable).toSet()
                    record to terms.count { it in haystack }
                }
                .filter { (_, hits) -> hits >= if (terms.size >= 3) 2 else 1 }
                .sortedByDescending { (_, hits) -> hits }

            val seen = mutableSetOf<String>()
            return scored
                .map { (record, _) -> record }
                .filter { seen.add(it.text.take(200).ifBlank { it.title }) }
                .take(limit)
                .map { RecordHit(it.subject, it.title, it.text.ifBlank { it.notes }.take(220)) }
        }

        data class Record(
            val subject: String,
            val title: String,
            val notes: String,
            val text: String,
        ) {
            val searchable: String get() = "$title $notes $text"
        }
    }
}

/** How long ago, in the words a tired parent reads fastest. */
internal fun humanAgo(thenMs: Long): String {
    val minutes = ((System.currentTimeMillis() - thenMs) / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> "less than a minute"
        minutes < 60 -> "$minutes minutes"
        minutes < 60 * 24 -> {
            val h = minutes / 60
            val m = minutes % 60
            if (m == 0L) "${h}h" else "${h}h ${m}m"
        }
        else -> "${minutes / (60 * 24)} days"
    }
}

internal fun clockTime(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ms))
