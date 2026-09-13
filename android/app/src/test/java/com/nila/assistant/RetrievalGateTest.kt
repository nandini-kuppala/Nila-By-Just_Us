package com.nila.assistant

import org.junit.Test

/**
 * Choosing the rule that decides whether a retrieved passage may be used.
 *
 * Two sets, both real. POSITIVE is the app's own retrieval cases, every one of
 * which retrieves the right document today -- a rule that rejects any of these
 * is worse than no rule, because the corpus answer is sourced and instant and
 * the alternative is a 2B model guessing.
 *
 * NEGATIVE is the questions the corpus has no answer to, taken from the probe
 * runs. Retrieval still returns its nearest neighbour for each, and what came
 * back was a page on infant sleep for a question about asthma and a page on
 * colic for a question about a hearing screen. These have to be refused.
 *
 * The two sets overlap in score -- a correct hit at 5.50, a wrong one at 5.53 --
 * so score alone cannot be the rule. This prints what each candidate does to
 * both sets so the choice is made on the numbers.
 */
class RetrievalGateTest {

    private val index = Corpus.index

    private val positive = FloorCalibrationTest.CASES.map { it.first }

    /** Questions the corpus genuinely cannot answer. */
    private val negative = listOf(
        "Is my asthma a problem for breastfeeding?",
        "Did she pass her hearing test?",
        "Why do babies get hiccups so often?",
        "Is she growing well?",
        "What is a good bedtime routine for a four month old?",
        "My iron tablets upset my stomach, what can I do?",
        "Is it ok to take my baby swimming?",
        "How do I clean a baby's tongue?",
        "And at night?",
        "What about during the day?",
        "how do i clip her fingernails",
        "should i be worried about a flat head",
        "what is cradle cap",
        "can i take my baby on a plane",
        "how do i get her to take a bottle",
    )

    private data class Signals(
        val score: Double,
        /** Query terms the document contains, anywhere. */
        val matched: List<String>,
        /** Query terms in the document's title or hand-written keywords. */
        val topical: List<String>,
        /** Fraction of the query's terms the document matches. */
        val coverage: Double,
        /** Document frequency of the rarest topical term. */
        val rarestTopicalDf: Int,
    )

    private fun signals(question: String): Signals? {
        val hit = index.retrieveGuidance(question, null, null).firstOrNull() ?: return null
        val queryTerms = KnowledgeIndex.tokenize(question).distinct()
        val all = hit.doc.fields.flatMapTo(HashSet()) { KnowledgeIndex.tokenize(it.first) }
        val topicalField = KnowledgeIndex.tokenize(hit.doc.title) +
            KnowledgeIndex.tokenize(hit.doc.keywords)
        val matched = queryTerms.filter { it in all }
        val topical = queryTerms.filter { it in topicalField.toSet() }
        return Signals(
            score = hit.score,
            matched = matched,
            topical = topical,
            coverage = if (queryTerms.isEmpty()) 0.0 else matched.size.toDouble() / queryTerms.size,
            rarestTopicalDf = topical.minOfOrNull { index.documentFrequency(it) } ?: index.size,
        )
    }

    @Test
    fun `compare candidate gates`() {
        val pos = positive.mapNotNull { q -> signals(q)?.let { q to it } }
        val neg = negative.mapNotNull { q -> signals(q)?.let { q to it } }

        println("\npositive retrieved: ${pos.size} / ${positive.size}")
        println("negative retrieved: ${neg.size} / ${negative.size}   (the rest already return nothing)")

        println("\n--- NEGATIVE cases that retrieved something ---")
        println("%6s %5s %4s  %-42s %s".format("score", "cov", "dfT", "question", "topical terms"))
        neg.sortedBy { it.second.score }.forEach { (q, s) ->
            println("%6.2f %5.2f %4d  %-42s %s".format(
                s.score, s.coverage, s.rarestTopicalDf, q.take(42), s.topical))
        }

        println("\n--- POSITIVE, the five weakest ---")
        pos.sortedBy { it.second.score }.take(8).forEach { (q, s) ->
            println("%6.2f %5.2f %4d  %-42s %s".format(
                s.score, s.coverage, s.rarestTopicalDf, q.take(42), s.topical))
        }

        val gates = listOf<Pair<String, (Signals) -> Boolean>>(
            "score >= 5.0" to { it.score >= 5.0 },
            "score >= 6.5" to { it.score >= 6.5 },
            "topical >= 1" to { it.topical.isNotEmpty() },
            "topical >= 2" to { it.topical.size >= 2 },
            "topical >= 2 or rare topical (df<=3)" to
                { it.topical.size >= 2 || it.rarestTopicalDf <= 3 },
            "coverage >= 0.40" to { it.coverage >= 0.40 },
            "coverage >= 0.50" to { it.coverage >= 0.50 },
            "score>=5 and (topical>=2 or df<=3)" to
                { it.score >= 5.0 && (it.topical.size >= 2 || it.rarestTopicalDf <= 3) },
            "score>=5 and coverage>=0.40" to { it.score >= 5.0 && it.coverage >= 0.40 },
        )

        println("\n%-42s %-12s %s".format("gate", "keeps good", "refuses bad"))
        gates.forEach { (name, gate) ->
            val kept = pos.count { gate(it.second) }
            val refused = neg.count { !gate(it.second) }
            println("%-42s %3d / %-6d %3d / %d".format(name, kept, pos.size, refused, neg.size))
        }
    }
}
