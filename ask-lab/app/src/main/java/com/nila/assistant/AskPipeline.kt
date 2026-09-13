package com.nila.assistant

import android.util.Log

/**
 * The Ask pipeline: guardrail, memory, retrieval, then one model call at most.
 *
 * ### Shape
 *
 * One pass, four leaves, no loops and no agents. Every extra hop in a pipeline
 * like this is a second of latency and a place for a small model to drift, and
 * neither buys anything here: the corpus is 27 guidance entries, which fits in
 * a prompt several times over, so there is nothing for a re-ranker or a second
 * retrieval round to improve on.
 *
 * ```
 * question
 *   |
 *   +-- GUARD        emergency / distress / dose / diagnosis / off-topic
 *   |                -> a written-in-advance reply, no model, no retrieval
 *   |
 *   +-- EXACT        "when did she last feed" -> the log, to the minute
 *   |                "is amoxicillin safe"    -> the medicine entry, verbatim
 *   |
 *   +-- RETRIEVE     BM25 over the sourced corpus
 *   +-- REMEMBER     profile + today's log + the records this question reaches
 *   |                + the last turns of this conversation
 *   |
 *   +-- one of:
 *        A FAQ_DIRECT   strong hit, nothing personal asked
 *                       -> the sourced passage itself. No model. ~0.2 s.
 *        B GROUNDED     hit + something personal
 *                       -> model(question, passages, facts), grounded against both
 *        C MEMORY_ONLY  no hit, but this family's facts bear on it
 *                       -> model(question, -, facts), no invented quantities
 *        D GENERAL      no hit, nothing personal
 *                       -> model(question, -, -), general knowledge, said as such
 * ```
 *
 * ### Why the fork at A exists
 *
 * A general question has a general answer and the corpus holds it word for
 * word with a source attached. Putting a model between the reader and that text
 * costs ten seconds and can only lose information. The model earns its place
 * exactly where the corpus cannot go: joining a sourced fact to something only
 * this family's records know.
 *
 * ### What happens when the model is wrong
 *
 * Every branch has a complete answer *before* the model is asked for anything,
 * and the guards in [Grounding] can only send the pipeline back to it. A
 * rejected generation costs latency and nothing else.
 */
class AskPipeline(
    private val index: KnowledgeIndex,
    private val memory: AskMemory,
    private val llm: NpuLlm?,
) {

    companion object {
        private const val TAG = "AskPipeline"

        /** Passages given to the model, and how much of each. */
        private const val MAX_PASSAGES = 2
        private const val PASSAGE_CHARS = 600

        /** Fact lines given to the model, and how much of each. */
        private const val MAX_FACTS = 10

        /**
         * A profile or log line is one short sentence; a filed record is not.
         *
         * These were one number at 140 characters, and the immunisation card
         * was cut off before the line that answers "are her vaccinations up to
         * date". The model filled the hole from its own weights and produced a
         * schedule nobody had written down -- rotavirus at six weeks, measles
         * at nine months, and an age for the baby that was three weeks wrong.
         */
        private const val FACT_CHARS = 140
        private const val RECORD_CHARS = 320

        /**
         * How rare a single matched term has to be to carry a hit on its own.
         *
         * Three documents out of sixty-six. "colic", "honey", "grapes" and
         * "smile" are each in one or two entries and each is the whole subject
         * of its question; "night" is in seven and "so" is in six, and neither
         * says anything about what a question is about.
         */
        private const val RARE_DF = 3

        /** Turns of conversation carried into the prompt, and how much of each. */
        private const val MAX_TURNS = 2
        private const val TURN_ANSWER_CHARS = 220

        /**
         * An answer, not an essay.
         *
         * Cut from 300 after reading where the errors were: the first three
         * sentences of these answers were sound and the padding that followed
         * was where the model reached for a fact it had been given and had to
         * find a use for. A shorter budget is a quality setting, not only a
         * latency one.
         */
        private const val MAX_NEW_TOKENS = 220

        /**
         * Shorter still when nothing was retrieved.
         *
         * With a passage behind it the model has something to say and the
         * length is the length of the answer. With no passage every sentence
         * after the second was where it went looking for something else to
         * add, and what it found was invented: that iron supplements interfere
         * with milk supply, that a breastfeeding mother might switch formula.
         */
        private const val MAX_NEW_TOKENS_UNSOURCED = 140

        /**
         * Vocabulary-overlap floor for a grounded rewrite.
         *
         * The app uses 0.5, set against a 0.5B model that stayed close to its
         * source because it could not do much else. A 2B model writing for a
         * particular family legitimately brings in words that are in neither the
         * passage nor the facts, and at 0.5 it rejected every personalised
         * answer it produced, including the good ones.
         *
         * Measured over the probe set rather than guessed: answers that used
         * their source scored 0.30 to 0.82, and the one that had stopped using
         * it -- six sentences of hedging about records it had not been given --
         * scored 0.26. The floor is set at the bottom of the first group.
         *
         * It is a weaker check than it was, and it is worth being clear about
         * why that is acceptable: it is no longer the only thing standing
         * between the model and the screen. Retrieval now refuses to supply a
         * passage it is not confident in, and the facts are selected per
         * question rather than handed over whole, so the two failure modes this
         * number used to absorb mostly do not arise.
         */
        const val GROUNDING_FLOOR = 0.30
    }

    /** Which leaf answered, which is the single most useful thing to log. */
    enum class Tier {
        /** Routed away before retrieval: emergency, dose, diagnosis, off-topic. */
        REFUSED,
        /** This baby's own log, exactly. */
        LOG,
        /** A medicine entry, verbatim, because a verdict must not be paraphrased. */
        MEDICINE,
        /** A sourced passage, verbatim. No model ran. */
        FAQ_DIRECT,
        /** The model, over passages plus this family's facts. */
        GROUNDED,
        /** The model, over this family's facts with nothing retrieved. */
        MEMORY_ONLY,
        /** The model, from general knowledge. */
        GENERAL,
        /** Nothing retrieved and no model: the honest floor. */
        NO_ANSWER,
    }

    /**
     * What the winning document matched, and how distinctive it was.
     *
     * [topicalTerms] are the query terms found in the document's **title or
     * keywords** -- fields written by hand to say what the entry is about.
     * Terms found only in its body text are not evidence of topic, which is
     * the whole finding: an asthma question returned the page on infant sleep
     * because both contain the word "problem", and a question about a hearing
     * screen returned colic because colic's text contains "test".
     */
    data class Specificity(
        val matchedTerms: List<String>,
        val topicalTerms: List<String>,
        val rarestTopicalDf: Int,
        val corpusSize: Int,
    )

    data class Trace(
        val route: String,
        val hits: Int,
        val topScore: Double,
        val personal: Boolean,
        val recordHits: Int,
        val turnsUsed: Int,
        val factsUsed: Int,
        val retrievalMs: Long,
        val totalMs: Long,
        val generation: NpuLlm.Generation? = null,
        /** Why a generation was thrown away, or "ok", or "-" if none ran. */
        val guard: String = "-",
        val specificity: Specificity? = null,
        /** How much of the generated answer came from its source. */
        val groundingOverlap: Double = -1.0,
    )

    data class Reply(
        val text: String,
        val tier: Tier,
        val sources: List<String> = emptyList(),
        val cautions: List<String> = emptyList(),
        val trace: Trace,
    ) {
        /** True when the words on screen were written by the model. */
        val generated: Boolean
            get() = tier == Tier.GROUNDED || tier == Tier.MEMORY_ONLY || tier == Tier.GENERAL
    }

    /**
     * Answer [question], streaming any generated text to [onToken].
     *
     * [onToken] fires only on the branches where a model runs; the direct
     * branches return whole, which is the point of them.
     */
    suspend fun ask(question: String, onToken: (String) -> Unit = {}): Reply {
        val startedAt = System.currentTimeMillis()

        // 1. The guardrail, before retrieval. Some questions must never be
        //    answered with the nearest matching passage, however well it scores.
        // Letters rather than words. Checked here rather than in the router
        // because telling the two apart needs the corpus's vocabulary, and the
        // router is deliberately free of it.
        val route = QueryRouter.route(question).let {
            if (it is QueryRouter.Route.Answerable && index.isUnintelligible(question)) {
                QueryRouter.Route.Unintelligible
            } else it
        }
        QueryRouter.refusal(route)?.let { text ->
            return Reply(
                text = text,
                tier = Tier.REFUSED,
                trace = Trace(
                    route = route.label(), hits = 0, topScore = 0.0, personal = false,
                    recordHits = 0, turnsUsed = 0, factsUsed = 0,
                    retrievalMs = 0, totalMs = System.currentTimeMillis() - startedAt,
                ),
            ).also { remember(question, it) }
        }
        val subject = (route as QueryRouter.Route.Answerable).subject

        // 2. Questions with one correct answer, which is not in any corpus and
        //    must not be paraphrased by anything.
        exactLogAnswer(question)?.let { reply ->
            return reply.copy(
                trace = reply.trace.copy(
                    route = route.label(),
                    totalMs = System.currentTimeMillis() - startedAt,
                )
            ).also { remember(question, it) }
        }

        val medicine = index.findMedicine(question)
        if (medicine != null && looksLikeMedicineQuestion(question)) {
            return Reply(
                text = medicine.doc.text,
                tier = Tier.MEDICINE,
                sources = listOf("${medicine.doc.title} - ${medicine.doc.source}"),
                cautions = listOfNotNull(medicine.doc.caution.takeIf(String::isNotBlank)),
                trace = Trace(
                    route = route.label(), hits = 1, topScore = medicine.score,
                    personal = false, recordHits = 0, turnsUsed = 0, factsUsed = 0,
                    retrievalMs = System.currentTimeMillis() - startedAt,
                    totalMs = System.currentTimeMillis() - startedAt,
                ),
            ).also { remember(question, it) }
        }

        // 3. Retrieval and memory, together: both are cheap, and the decision
        //    about which leaf to take needs the result of each.
        val retrievalStarted = System.currentTimeMillis()
        val babyAge = if (QueryRouter.isForwardLooking(question)) null else babyAgeMonths()
        val audience = when (subject) {
            QueryRouter.Subject.MOTHER -> "mother"
            QueryRouter.Subject.BABY -> "baby"
            QueryRouter.Subject.EITHER -> null
        }
        val retrieved = index.retrieveGuidance(question, audience, babyAge, limit = MAX_PASSAGES)
        val specificity = retrieved.firstOrNull()?.let { specificityOf(question, it) }
        // An off-topic neighbour is not a weak answer, it is the wrong
        // document, and that is true of the second hit as well as the first.
        // Asked whether a milk supply was enough, retrieval returned the page
        // on supply and, underneath it, the page on whether what a mother eats
        // makes her baby fussy -- which was shown as part of the answer.
        val hits = retrieved.filter { isTopical(question, it) }
        val records = memory.recordsFor(question, subject)
        val retrievalMs = System.currentTimeMillis() - retrievalStarted

        // A matched record used to be enough on its own, and "is it safe for a
        // baby to sleep on their front" became a personal question because the
        // four-month review mentions rolling "front to back". A record only
        // makes a question personal when nothing in the corpus answers it.
        val personal = QueryRouter.isPersonal(question) ||
            mentionsThisBaby(question) ||
            (records.isNotEmpty() && hits.isEmpty())
        val corpusText = hits.joinToString("\n\n") { it.doc.text }
        val sources = records.map { "Your record: ${it.title}" } +
            hits.map { "${it.doc.title} - ${it.doc.source}" }
        val cautions = hits.mapNotNull { it.doc.caution.takeIf(String::isNotBlank) }

        fun trace(
            tier: Tier,
            facts: Int,
            turns: Int,
            gen: NpuLlm.Generation? = null,
            guard: String = "-",
        ) = Trace(
            route = route.label(),
            hits = hits.size,
            topScore = hits.firstOrNull()?.score ?: 0.0,
            personal = personal,
            recordHits = records.size,
            turnsUsed = turns,
            factsUsed = facts,
            retrievalMs = retrievalMs,
            totalMs = System.currentTimeMillis() - startedAt,
            generation = gen,
            guard = guard,
            specificity = specificity,
        )

        // A. The frequently-asked case. Retrieval vetted this hit against its
        //    own relevance floor and its coverage rule, the question asks
        //    nothing about this family, and the corpus sentence is already the
        //    answer with a source on it. Returning it is both the fastest path
        //    and the one with no failure mode.
        if (hits.isNotEmpty() && !personal) {
            return Reply(corpusText, Tier.FAQ_DIRECT, sources, cautions, trace(Tier.FAQ_DIRECT, 0, 0))
                .also { remember(question, it) }
        }

        // Everything below wants the model. Build the facts once.
        // Retrieval over the memory, not a dump of it. See [FactSelector].
        val facts = FactSelector.select(question, subject, memory.facts()).truncated()
        val shownRecords = records.map {
            it.copy(extract = it.extract.trim().take(RECORD_CHARS))
        }
        val factLinesForGuard = facts.all + shownRecords.map { "${it.title}: ${it.extract}" }
        val turns = memory.recentTurns(MAX_TURNS)

        val engine = llm?.takeIf { it.isReady }
        if (engine == null) {
            // No model. Retrieval still answers when it found something, and
            // when it did not the floor is an honest sentence rather than a
            // guess -- which is exactly where this app stood before.
            return if (hits.isNotEmpty()) {
                Reply(corpusText, Tier.FAQ_DIRECT, sources, cautions, trace(Tier.FAQ_DIRECT, 0, 0))
                    .also { remember(question, it) }
            } else {
                Reply(NO_MODEL_FLOOR, Tier.NO_ANSWER, trace = trace(Tier.NO_ANSWER, 0, 0))
                    .also { remember(question, it) }
            }
        }

        val tier = when {
            hits.isNotEmpty() -> Tier.GROUNDED
            personal && !facts.isEmpty -> Tier.MEMORY_ONLY
            else -> Tier.GENERAL
        }

        val passages = hits.map { it.doc.text.take(PASSAGE_CHARS) }
        val withFacts = tier != Tier.GENERAL
        val factLines = if (withFacts) factLinesForGuard else emptyList()

        val generation = engine.generate(
            messages = Prompt.build(
                question = question,
                passages = passages,
                facts = facts,
                records = shownRecords,
                turns = turns,
                withFacts = withFacts,
            ),
            maxTokens = if (passages.isEmpty()) MAX_NEW_TOKENS_UNSOURCED else MAX_NEW_TOKENS,
            onToken = onToken,
        )

        val fallback = when {
            hits.isNotEmpty() -> Reply(corpusText, Tier.FAQ_DIRECT, sources, cautions,
                                       trace(Tier.FAQ_DIRECT, factLines.size, turns.size, generation))
            else -> Reply(REJECTED_FLOOR, Tier.NO_ANSWER,
                          trace = trace(Tier.NO_ANSWER, factLines.size, turns.size, generation))
        }

        if (generation == null) {
            Log.w(TAG, "generation returned nothing; keeping the retrieved answer")
            return fallback.also { remember(question, it) }
        }

        val cleaned = ModelText.tidy(generation.text)
        val verdict = judge(
            cleaned, passages, factLines,
            records = shownRecords.map { "${it.title}. ${it.extract}" },
        )
        if (verdict == "refused" && hits.isEmpty()) {
            // The model declined rather than answering, which on this pipeline
            // means the question did not read as one. Sending the reader to a
            // doctor with it says the question was serious and unanswerable,
            // when what actually happened is that nobody understood it.
            return Reply(
                text = UNREADABLE_FLOOR,
                tier = Tier.NO_ANSWER,
                trace = trace(Tier.NO_ANSWER, factLines.size, turns.size, generation, verdict),
            ).also { remember(question, it) }
        }
        val overlap = Grounding.overlap(cleaned, (passages + factLines).joinToString("\n"))
        if (verdict != "ok") {
            Log.w(TAG, "rejected ($verdict): ${cleaned.take(120)}")
            return fallback.copy(
                trace = fallback.trace.copy(
                    generation = generation, guard = verdict, groundingOverlap = overlap,
                )
            ).also { remember(question, it) }
        }

        return Reply(
            text = cleaned,
            tier = tier,
            sources = sources,
            cautions = cautions,
            trace = trace(tier, factLines.size, turns.size, generation, "ok")
                .copy(groundingOverlap = overlap),
        ).also { remember(question, it) }
    }

    /**
     * Which query terms the winning document actually contains, and how common
     * the rarest of them is across the corpus.
     */
    private fun specificityOf(question: String, hit: Retrieved): Specificity {
        val docTerms = hit.doc.fields.flatMapTo(HashSet()) { KnowledgeIndex.tokenize(it.first) }
        val queryTerms = KnowledgeIndex.tokenize(question).distinct()
        val topical = topicalTerms(question, hit)
        return Specificity(
            matchedTerms = queryTerms.filter { it in docTerms },
            topicalTerms = topical,
            rarestTopicalDf = topical.minOfOrNull { index.documentFrequency(it) } ?: index.size,
            corpusSize = index.size,
        )
    }

    private fun topicalTerms(question: String, hit: Retrieved): List<String> {
        val topicalField = (KnowledgeIndex.tokenize(hit.doc.title) +
            KnowledgeIndex.tokenize(hit.doc.keywords)).toSet()
        return KnowledgeIndex.tokenize(question).distinct().filter { it in topicalField }
    }

    /**
     * Is the winning document about what was asked, or merely near it?
     *
     * BM25 over twenty-seven entries always returns a nearest neighbour, and
     * its score cannot tell the two apart: measured over the app's own
     * retrieval cases, a correct hit scores as low as 5.50 ("when should I
     * take my baby to hospital") while a wrong one reaches 5.51 ("what is a
     * good bedtime routine"). A floor set between them does not exist.
     *
     * What does separate them is *which field* matched. Every entry carries a
     * title and a hand-written keyword line that say what it is about; the
     * wrong hits all matched on body text alone -- "problem", "test", "well",
     * "so". So a hit is usable when the question meets its topic words twice,
     * or once on a term rare enough to be a subject rather than scenery.
     *
     * Measured: keeps 46 of 46 correct retrievals, refuses 8 of 8 questions
     * the corpus has no answer to.
     */
    private fun isTopical(question: String, hit: Retrieved): Boolean {
        val topical = topicalTerms(question, hit)
        if (topical.size >= 2) return true
        return topical.any { index.documentFrequency(it) <= RARE_DF }
    }

    /**
     * Is this generation safe to show?
     *
     * Returns "ok", or the name of the rule that rejected it -- a string rather
     * than a boolean because *which* guard fired is the whole value of a run.
     *
     * The checks differ by branch, and they have to. With passages behind it a
     * generation is a rewrite, and can be held to the source word by word. With
     * nothing behind it a generation is general knowledge, which is allowed to
     * range over its own vocabulary; what is never allowed is a number it
     * invented or an all-clear on a condition belonging to the person asking.
     */
    private fun judge(
        text: String,
        passages: List<String>,
        facts: List<String>,
        records: List<String>,
    ): String {
        if (text.isBlank()) return "empty"
        if (ModelText.isRefusal(text)) return "refused"

        val source = (passages + facts).joinToString("\n")
        if (!Grounding.inventsNoQuantities(text, source)) return "invented-quantity"
        // The reassurance rule exists to stop an all-clear on a condition
        // belonging to the person asking. With no facts in the prompt there is
        // no such condition, and the rule was rejecting ordinary language --
        // "you don't need to worry" in an answer about why babies hiccup,
        // which names nothing and clears nobody.
        if (facts.isNotEmpty() && !Grounding.reassuresNobody(text, facts)) {
            return "unfounded-reassurance"
        }
        if (passages.isNotEmpty() && !Grounding.isGrounded(
                generated = text,
                source = source,
                floor = GROUNDING_FLOOR,
                // Prose only. A filed record is prose and can be inverted --
                // that is the failure this check exists for -- but a profile
                // line cannot.
                claims = (passages + records).joinToString("\n"),
            )
        ) return "ungrounded"
        return "ok"
    }

    /**
     * Does this question ask about *this* baby rather than about babies?
     *
     * A third-person pronoun is the tell, and a reliable one. The corpus is
     * written in the general voice -- "babies", "a baby", "an infant" -- so
     * "she", "her" and "him" never refer to anything it contains. They can
     * only mean the baby in the room, which is the baby the log and the
     * records are about.
     *
     * [QueryRouter.isPersonal] deliberately does not fire on a bare possessive,
     * because "my baby" opens almost every question on this screen and routing
     * all of them through a model bought nothing when the model was a 0.5B that
     * only rephrased. It buys something now: "is that normal for her" has an
     * answer that names how long she actually cried today, and no corpus entry
     * can give it.
     */
    private fun mentionsThisBaby(question: String): Boolean =
        Regex("""\b(she|her|hers|he|him|his)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(question)

    /** Keep each pile inside its share of the context budget. */
    private fun AskMemory.Facts.truncated() = copy(
        baby = baby.map { it.trim().take(FACT_CHARS) }.take(MAX_FACTS),
        mother = mother.map { it.trim().take(FACT_CHARS) }.take(MAX_FACTS),
    )

    private suspend fun babyAgeMonths(): Int? = memory.facts().babyAgeMonths

    /**
     * Questions the log answers exactly.
     *
     * Matched by keyword rather than by a model, because getting "when did she
     * last feed" wrong is worse than not answering it, and a model asked to
     * read a timestamp will eventually read it wrong.
     */
    private suspend fun exactLogAnswer(question: String): Reply? {
        val q = question.lowercase()
        if ("last" !in q && "when" !in q && "how long" !in q) return null

        val kind = when {
            "feed" in q || "fed" in q || "milk" in q -> "FEED"
            "nappy" in q || "diaper" in q || "change" in q -> "DIAPER"
            "sleep" in q || "slept" in q || "nap" in q -> "SLEEP_START"
            else -> return null
        }

        val emptyTrace = Trace("log", 0, 0.0, true, 0, 0, 0, 0, 0)
        val at = memory.lastEvent(kind) ?: return Reply(
            text = "Nothing logged for that yet. Once you start tapping the quick " +
                "buttons on the home screen, I can answer this exactly.",
            tier = Tier.LOG,
            trace = emptyTrace,
        )

        return Reply(
            text = "Last ${kind.lowercase().replace('_', ' ')} was " +
                "${DemoSeedMemory.humanAgo(at)} ago, at ${DemoSeedMemory.clockTime(at)}.",
            tier = Tier.LOG,
            sources = listOf("Your own log"),
            trace = emptyTrace,
        )
    }

    /**
     * Is this question about the medicine that was named in it?
     *
     * [KnowledgeIndex.findMedicine] only fires on a real drug name or brand
     * alias above its own floor, so a hit is already strong evidence. This is
     * the second half: someone can mention a medicine while asking something
     * else about it, and the corpus entry answers exactly one question --
     * whether a breastfeeding parent can take it.
     */
    private fun looksLikeMedicineQuestion(question: String): Boolean {
        val q = question.lowercase()
        return SAFETY_WORDING.any { it in q }
    }

    /**
     * Wording that asks whether a medicine may be taken.
     *
     * The corpus entry answers exactly one question -- whether a breastfeeding
     * parent can take this drug -- so it may only be returned to that question.
     * The list used to include "take", "tablets", "feeding" and "while", and
     * "my iron tablets upset my stomach, what can I do" came back with a
     * compatibility verdict, which is a true statement and not an answer.
     */
    private val SAFETY_WORDING = listOf(
        "safe", "safety", "ok to", "okay to", "alright to", "allowed",
        "can i take", "can i use", "should i take", "can i have",
        "compatible", "harmful", "harm my", "harm the", "affect my milk",
        "affect the milk", "affect my baby", "pass into", "while breastfeeding",
        "while nursing", "when breastfeeding", "avoid taking",
    )

    private suspend fun remember(question: String, reply: Reply) {
        memory.remember(AskMemory.Turn(question, reply.text.take(TURN_ANSWER_CHARS)))
    }

    private fun QueryRouter.Route.label(): String = when (this) {
        is QueryRouter.Route.Emergency -> "emergency"
        QueryRouter.Route.CaregiverDistress -> "distress"
        QueryRouter.Route.Greeting -> "greeting"
        QueryRouter.Route.TooVague -> "vague"
        QueryRouter.Route.DoseRequest -> "dose"
        QueryRouter.Route.DiagnosisRequest -> "diagnosis"
        QueryRouter.Route.OutOfScope -> "off-topic"
        QueryRouter.Route.Unintelligible -> "unintelligible"
        is QueryRouter.Route.Answerable -> "answerable/${subject.name.lowercase()}"
    }
}

/**
 * What is said when a generation was produced and then thrown away.
 *
 * Distinct from [NO_MODEL_FLOOR] because it is a different fact about the
 * world, and because it is the line a reader will see on exactly the questions
 * where the model tried hardest to be helpful and got it wrong.
 */
/** Said when the model declined to answer at all. Nothing to escalate. */
private const val UNREADABLE_FLOOR =
    "I could not make sense of that one. Try asking it in a few plain words - " +
    "what you want to know about the baby, or about yourself."

private const val REJECTED_FLOOR =
    "I don't have a sourced entry on that, and what I drafted did not hold up " +
    "against what I actually know about you, so I am not going to show it. " +
    "This is one to put to your doctor or health visitor."

private const val NO_MODEL_FLOOR =
    "I don't have a sourced entry on that, and there is no model loaded to " +
    "answer it from general knowledge. For anything urgent or unusual, ask " +
    "your doctor rather than guessing."
