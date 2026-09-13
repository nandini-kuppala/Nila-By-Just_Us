package com.nila.assistant

import com.nila.data.CareKind
import com.nila.data.ConversationRecord
import com.nila.data.NilaDatabase
import com.nila.data.RecordSubject
import java.util.concurrent.TimeUnit

/**
 * [AskMemory] over the app's own database.
 *
 * The lab that designed this pipeline filled the same interface from the
 * bundled demo seed. Everything above the interface -- routing, retrieval,
 * fact selection, the prompt, the guards -- is the identical code in both,
 * which is the only reason a result measured there means anything here.
 *
 * Conversation memory is read back from the table the Ask screen has been
 * writing to since the first release and nothing has ever read. That table is
 * what turns a search box into something a follow-up can be asked of.
 */
class RoomAskMemory(private val db: NilaDatabase) : AskMemory {

    override suspend fun facts(): AskMemory.Facts {
        val baby = db.baby().get()
        val mother = db.mother().get()
        val name = baby?.name?.takeIf { it.isNotBlank() } ?: "your baby"
        val age = baby?.takeIf { it.birthDateMs > 0 }?.ageMonths

        return AskMemory.Facts(
            babyName = name,
            babyAgeMonths = age,
            baby = buildList {
                if (age != null) add("$name is $age months old")
                baby?.healthNotes?.takeIf { it.isNotBlank() }?.let { add(it) }
                addAll(logLines())
            },
            mother = buildList {
                mother ?: return@buildList
                if (mother.isBreastfeeding) add("She is breastfeeding")
                mother.conditions.takeIf { it.isNotBlank() }?.let { add("Her conditions: $it") }
                mother.allergies.takeIf { it.isNotBlank() }?.let { add("She is allergic to: $it") }
                mother.currentMedicines.takeIf { it.isNotBlank() }
                    ?.let { add("She currently takes: $it") }
                mother.notes.takeIf { it.isNotBlank() }?.let { add("Her notes: $it") }
            },
        )
    }

    override suspend fun profileLines(): List<String> = facts().all

    /**
     * Today, in plain language, with the baby named in every line.
     *
     * Named rather than implied because an unnamed line is a line a model can
     * hand to the wrong person: "Woke 18 minutes ago" came back as "you have
     * been awake for 18 minutes", addressed to the mother.
     */
    override suspend fun logLines(): List<String> = buildList {
        val name = db.baby().get()?.name?.takeIf { it.isNotBlank() } ?: "The baby"
        listOf(
            CareKind.FEED to "$name last fed",
            CareKind.DIAPER to "$name was last changed",
            CareKind.SLEEP_END to "$name woke",
        ).forEach { (kind, label) ->
            db.care().latestOf(kind.name)?.let { add("$label ${humanAgo(it.atMs)} ago") }
        }

        val now = System.currentTimeMillis()
        val dayAgo = now - TimeUnit.DAYS.toMillis(1)
        val episodes = db.events().cryEpisodesBetween(dayAgo, now)
        if (episodes > 0) {
            val minutes = db.events().cryingSecondsBetween(dayAgo, now) / 60
            add("$name has had $episodes crying episodes in the last 24 hours, " +
                "$minutes minutes in total")
        }
    }

    override suspend fun lastEvent(kind: String): Long? = db.care().latestOf(kind)?.atMs

    override suspend fun recordsFor(
        question: String,
        subject: QueryRouter.Subject,
    ): List<AskMemory.RecordHit> {
        val terms = KnowledgeIndex.tokenize(question)
            .filter { it.length >= 5 }
            .distinct()
            .take(4)
        if (terms.isEmpty()) return emptyList()

        val whose = when (subject) {
            QueryRouter.Subject.MOTHER -> RecordSubject.MOTHER.name
            QueryRouter.Subject.BABY -> RecordSubject.BABY.name
            QueryRouter.Subject.EITHER -> null
        }

        // Searched per term through the database, then scored by the same
        // two-term coverage rule the lab used, so the behaviour matches what
        // was measured rather than whatever SQL's LIKE happens to return.
        val found = LinkedHashMap<Long, com.nila.data.HealthRecord>()
        terms.forEach { term ->
            db.healthRecords().search(term, whose, 3).forEach { found[it.id] = it }
        }
        if (found.isEmpty()) return emptyList()

        return AskMemory.search(
            question = question,
            subject = subject,
            records = found.values.map {
                AskMemory.Companion.Record(
                    subject = it.subject,
                    title = it.title,
                    notes = it.notes,
                    text = it.extractedText,
                )
            },
        )
    }

    override suspend fun recentTurns(limit: Int): List<AskMemory.Turn> =
        db.conversations().latest(limit)
            .asReversed()
            .map { AskMemory.Turn(it.question, it.answer) }

    override suspend fun remember(turn: AskMemory.Turn) {
        // The Ask screen already writes every exchange with its sources; this
        // interface is only asked to remember when nothing else did.
    }

    override suspend fun forgetConversation() {
        // Deliberately not wired to a delete. The conversation table is the
        // user's own history and is shown in the app; forgetting a window of
        // it is a UI concern, not something an answer may do as a side effect.
    }
}
