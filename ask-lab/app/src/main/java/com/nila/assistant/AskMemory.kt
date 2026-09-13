package com.nila.assistant

import android.content.Context
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

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

/**
 * The lab's memory, read from the same `demo_seed.json` the app ships.
 *
 * Using the shipped demo family rather than invented fixtures matters: the
 * answers this lab produces are the answers the app will produce on a fresh
 * install, so a bad one is visible here rather than in a demo.
 */
class DemoSeedMemory(context: Context) : AskMemory {

    private val installedAt = System.currentTimeMillis()
    private val seed: JSONObject = context.assets.open("demo_seed.json")
        .bufferedReader().use { JSONObject(it.readText()) }

    private val turns = mutableListOf<AskMemory.Turn>()

    private val baby = seed.getJSONObject("baby")
    private val mother = seed.getJSONObject("mother")

    private val babyAgeMonths: Int get() = (baby.optInt("ageDays") / 30.44).toInt()

    /** Absolute time for a seed entry expressed as a day offset and a clock time. */
    private fun at(daysAgo: Int, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = installedAt
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        // The seed is authored as offsets so an APK built today still describes
        // this week. Anything that lands after the moment of install is a
        // future event and is dropped rather than reported.
        return if (cal.timeInMillis > installedAt) 0L else cal.timeInMillis
    }

    private val care: List<Pair<String, Long>> = run {
        val arr = seed.getJSONArray("care")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val ms = at(o.optInt("daysAgo"), o.optInt("atHour"), o.optInt("atMinute"))
            if (ms == 0L) null else o.getString("kind") to ms
        }.sortedByDescending { it.second }
    }

    private val cries: List<Triple<Long, Int, String?>> = run {
        val arr = seed.getJSONArray("cries")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val ms = at(o.optInt("daysAgo"), o.optInt("atHour"), o.optInt("atMinute"))
            if (ms == 0L) null
            else Triple(ms, o.optInt("seconds"), o.optString("cause").ifBlank { null })
        }
    }

    private val records: List<AskMemory.Companion.Record> = run {
        val arr = seed.getJSONArray("records")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            AskMemory.Companion.Record(
                subject = o.getString("subject"),
                title = o.getString("title"),
                notes = o.optString("notes"),
                text = o.optString("text"),
            )
        }
    }

    override suspend fun facts(): AskMemory.Facts {
        val name = baby.optString("name").ifBlank { "your baby" }
        return AskMemory.Facts(
            babyName = name,
            babyAgeMonths = babyAgeMonths,
            baby = buildList {
                add("$name is $babyAgeMonths months old")
                baby.optString("healthNotes").takeIf { it.isNotBlank() }?.let { add(it) }
                addAll(logLines())
            },
            mother = buildList {
                if (mother.optBoolean("isBreastfeeding", true)) add("She is breastfeeding")
                mother.optString("conditions").takeIf { it.isNotBlank() }
                    ?.let { add("Her conditions: $it") }
                mother.optString("allergies").takeIf { it.isNotBlank() }
                    ?.let { add("She is allergic to: $it") }
                mother.optString("currentMedicines").takeIf { it.isNotBlank() }
                    ?.let { add("She currently takes: $it") }
                mother.optString("notes").takeIf { it.isNotBlank() }
                    ?.let { add("Her notes: $it") }
            },
        )
    }

    override suspend fun profileLines(): List<String> = buildList {
        val name = baby.optString("name").ifBlank { "The baby" }
        add("$name is $babyAgeMonths months old")
        baby.optString("healthNotes").takeIf { it.isNotBlank() }?.let { add("Noted for $name: $it") }

        if (mother.optBoolean("isBreastfeeding", true)) add("The mother is breastfeeding")
        mother.optString("conditions").takeIf { it.isNotBlank() }
            ?.let { add("Mother's conditions: $it") }
        mother.optString("allergies").takeIf { it.isNotBlank() }
            ?.let { add("Mother is allergic to: $it") }
        mother.optString("currentMedicines").takeIf { it.isNotBlank() }
            ?.let { add("Mother currently takes: $it") }
        mother.optString("notes").takeIf { it.isNotBlank() }
            ?.let { add("Mother's notes: $it") }
    }

    override suspend fun logLines(): List<String> = buildList {
        val name = baby.optString("name").ifBlank { "The baby" }
        listOf(
            "FEED" to "$name last fed",
            "DIAPER" to "$name was last changed",
            "SLEEP_END" to "$name woke",
        ).forEach { (kind, label) ->
            lastEvent(kind)?.let { add("$label ${humanAgo(it)} ago") }
        }

        val dayAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        val recent = cries.filter { it.first >= dayAgo }
        if (recent.isNotEmpty()) {
            val minutes = recent.sumOf { it.second } / 60
            add("$name has had ${recent.size} crying episodes in the last 24 hours, " +
                "$minutes minutes in total")
        }
    }

    override suspend fun lastEvent(kind: String): Long? =
        care.firstOrNull { it.first == kind }?.second

    override suspend fun recordsFor(
        question: String,
        subject: QueryRouter.Subject,
    ): List<AskMemory.RecordHit> = AskMemory.search(question, subject, records)

    override suspend fun recentTurns(limit: Int): List<AskMemory.Turn> =
        turns.takeLast(limit)

    override suspend fun remember(turn: AskMemory.Turn) {
        turns += turn
        // A conversation is a window, not a transcript. Two turns is what a
        // follow-up needs and what the context budget can afford beside the
        // passages and the facts.
        if (turns.size > 12) turns.removeAt(0)
    }

    override suspend fun forgetConversation() = turns.clear()

    companion object {
        fun humanAgo(thenMs: Long): String {
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

        fun clockTime(ms: Long): String =
            java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(ms))
    }
}
