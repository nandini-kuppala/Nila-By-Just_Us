package com.nila.assistant

import android.util.Log

/*
 * Lifted verbatim from the app's LlmBridge.kt, because every rule in here came
 * from something a model actually put on a screen: a softened do-not-take
 * verdict for a penicillin-allergic mother, an invented failed hearing screen,
 * and an all-clear on somebody's asthma. Rewriting them for a bigger model
 * would throw away the evidence that produced them.
 *
 * The one thing that genuinely changes with a 2B model instead of a 0.5B is
 * how tight the vocabulary-overlap floor can be; that is measured in the lab
 * rather than guessed at here.
 */

object Grounding {

    private const val TAG = "Grounding"

    /**
     * Does the rewrite say only what the source said?
     */
    private val QUANTITY = Regex(
        """\b\d+(?:\.\d+)?\s?(?:mg|ml|mcg|g|kg|hours?|hrs?|days?|weeks?|months?|years?|times?|%)\b"""
    )

    /**
     * No number-with-a-unit that is not in the source.
     *
     * The most dangerous single thing this can produce, and the only check that
     * still applies when there is no corpus passage to be grounded in -- a
     * general-knowledge answer may range over what it knows, and may not invent
     * a dose while doing it.
     */
    fun inventsNoQuantities(generated: String, source: String): Boolean {
        val src = source.lowercase().replace(" ", "")
        val invented = QUANTITY.findAll(generated.lowercase())
            .map { it.value.replace(" ", "") }
            .filter { !src.contains(it) }
            .toList()
        if (invented.isNotEmpty()) {
            Log.w(TAG, "rejected: invented quantities $invented")
            return false
        }
        return true
    }

    /**
     * Conditions named in the facts, as words worth watching for.
     *
     * Pulled out of the fact lines rather than listed, so it covers whatever
     * this particular mother actually has on file.
     */
    private fun conditionWords(facts: List<String>): Set<String> = facts
        .filter {
            val l = it.lowercase()
            "condition" in l || "allerg" in l || "takes" in l || "notes" in l
        }
        .flatMap { contentWords(it) }
        .toSet()

    private val REASSURANCE = listOf(
        "is not a problem", "are not a problem", "is not a concern",
        "is not an issue", "are not an issue", "no need to worry",
        "nothing to worry", "don't need to worry", "do not need to worry",
        "you are fine", "you're fine", "is perfectly safe", "is safe for you",
        "will not affect", "won't affect", "does not affect", "poses no risk",
        "is not experiencing any", "has no health issues",
        "there is no risk", "no cause for concern",
    )

    /**
     * Refuse an all-clear the model has no standing to give.
     *
     * This exists because of what came back the first time the ungrounded path
     * ran on a phone. Asked "is my asthma a problem while breastfeeding", with
     * her own profile in the prompt and nothing retrieved behind it, a 0.5B
     * model replied:
     *
     *   "In this case, your baby is not experiencing any health issues, and
     *    your asthma is not a problem while breastfeeding."
     *
     * Fluent, confident, and invented. It is the same failure that took
     * generation out of this app once before, in a new place: with no passages
     * there is nothing to be grounded in, so [isGrounded] cannot see it and
     * [inventsNoQuantities] has no number to object to.
     *
     * A model with no source may describe what is generally known. It may not
     * clear a named condition belonging to the person asking. When it tries,
     * the answer is dropped and the caller falls back to saying it has nothing
     * -- which is the honest thing it was trying to avoid saying.
     */
    fun reassuresNobody(generated: String, facts: List<String>): Boolean {
        val lower = generated.lowercase()
        val phrase = REASSURANCE.firstOrNull { it in lower } ?: return true
        // A general reassurance with nothing personal attached is ordinary
        // language. It is the combination -- an all-clear naming something off
        // her own record -- that this refuses.
        val conditions = conditionWords(facts)
        val named = conditions.firstOrNull { it in lower }
        if (named == null && "your" !in lower && "you " !in lower) return true
        Log.w(TAG, "rejected: unfounded reassurance (\"$phrase\"" +
            (named?.let { ", about '$it'" } ?: "") + ")")
        return false
    }

    /**
     * What fraction of the answer's content words come from the source.
     *
     * Exposed so a caller can record it. The floor that rejects an answer is a
     * number about a particular model writing for a particular purpose, and a
     * number like that has to be set from a measured distribution rather than
     * inherited from the model it was set for.
     */
    fun overlap(generated: String, source: String): Double {
        val out = contentWords(generated)
        if (out.isEmpty()) return 0.0
        return out.count { it in contentWords(source) }.toDouble() / out.size
    }

    /**
     * @param source everything the answer was allowed to draw on, used for the
     *   quantity and vocabulary checks.
     * @param claims the *prose* among that -- retrieved passages and the text of
     *   filed records. Only prose is compared for polarity, because polarity is
     *   a property of a proposition and a one-line fact is not one. Matching a
     *   sentence against "Meera is 4 months old" found an overlap on the name
     *   and a "not" somewhere else in the sentence, and rejected two correct
     *   answers for inverting a fact that has nothing to invert.
     */
    fun isGrounded(
        generated: String,
        source: String,
        floor: Double = 0.5,
        claims: String = source,
    ): Boolean {
        // 1. Invented quantities.
        if (!inventsNoQuantities(generated, source)) return false

        // 2. Vocabulary drift.
        val out = contentWords(generated)
        if (out.isEmpty()) return false
        val overlap = overlap(generated, source)
        if (overlap < floor) {
            Log.w(TAG, "rejected: ${(overlap * 100).toInt()}% of content words " +
                "come from the source")
            return false
        }

        // 3. Polarity. The failure that took this feature out of the app was a
        //    sentence built entirely from source words that said the opposite
        //    of the source. Vocabulary overlap is blind to it by construction,
        //    so each piece of the output is matched to the piece of the source
        //    it most resembles and the two are required to agree about negation.
        //
        //    Matched by *clause*, not by sentence. A bigger model merges two
        //    source sentences into one, and a sentence-level match then reads
        //    the negation from the wrong half of it:
        //
        //      "While there is no specific list of foods you must avoid, it is
        //       important to drink plenty of fluids and consume roughly 300 to
        //       500 extra calories a day"
        //
        //    matched "You need roughly 300-500 extra calories a day", found a
        //    "no" in the other clause, and called it an inversion. Split at the
        //    comma and each half matches the source sentence it is actually a
        //    rewrite of, and both agree. The inversion this check exists for --
        //    "the baby has not passed the newborn hearing screening" against
        //    "newborn hearing screening was passed" -- is still caught, because
        //    that one is a whole clause saying the opposite.
        if (claims.isBlank()) return true
        for (clause in clauses(generated)) {
            val words = contentWords(clause)
            if (words.size < 4) continue
            val best = clauses(claims).maxByOrNull { overlapOf(words, it) } ?: continue
            if (overlapOf(words, best) >= 0.6 &&
                isNegated(clause) != isNegated(best)
            ) {
                Log.w(TAG, "rejected: polarity flip on '${clause.take(60)}'")
                return false
            }
        }
        return true
    }

    private val stop = setOf(
        "the", "and", "for", "you", "your", "that", "this", "with", "are", "was",
        "were", "have", "has", "had", "can", "but", "she", "her", "his", "him",
        "they", "them", "there", "then", "from", "into", "about", "would",
        "could", "should", "very", "will", "what", "when", "which", "their",
    )

    private fun contentWords(text: String): Set<String> = text.lowercase()
        .split(Regex("[^a-z]+"))
        .filter { it.length > 3 && it !in stop }
        .toSet()

    /**
     * How much two sentences say the same thing, as a fraction of the shorter.
     *
     * Measuring against the output alone was the bug: the invented sentence
     * "the baby has not passed the newborn hearing screening, which is a sign
     * of potential hearing issues" adds enough words of its own that it covers
     * only half of itself, and the polarity check skipped it. Against the six
     * content words of the source sentence it is paraphrasing, the overlap is
     * two thirds -- which is what "this sentence is a rewrite of that one"
     * actually means.
     */
    private fun overlapOf(words: Set<String>, candidate: String): Double {
        val other = contentWords(candidate)
        if (other.isEmpty() || words.isEmpty()) return 0.0
        val shared = words.count { it in other }.toDouble()
        return maxOf(shared / words.size, shared / other.size)
    }

    private fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }

    /** Where one claim ends and the next begins inside a sentence. */
    private val CLAUSE_BREAK = Regex(
        """[,;:]\s+|\s+(?:but|while|although|whereas|however|though)\s+""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The sentences, split again at clause boundaries.
     *
     * A clause too short to carry a claim is dropped rather than matched, and
     * if nothing survives the split the sentences are used as they were.
     */
    private fun clauses(text: String): List<String> {
        val out = sentences(text)
            .flatMap { it.split(CLAUSE_BREAK) }
            .filter { contentWords(it).size >= 3 }
        return out.ifEmpty { sentences(text) }
    }

    private val negations = setOf(
        "not", "no", "never", "cannot", "cant", "dont", "doesnt", "didnt",
        "isnt", "arent", "wasnt", "wont", "shouldnt", "without", "neither",
        "nor", "none", "nothing",
        // "avoid" and "rarely" were here and came out again: they are ordinary
        // content words in this corpus ("foods to avoid before one year"), and
        // treating them as negation markers rejected faithful rewrites.
    )

    private fun isNegated(sentence: String): Boolean = sentence.lowercase()
        .replace("'", "")
        .split(Regex("[^a-z]+"))
        .any { it in negations }
    }

/**
 * Turning what a small model emits into something a card can render.
 *
 * Pure, and separate from [LlmBridge], because every rule in it came from
 * something that reached the screen and none of them can be checked on a
 * device without loading half a gigabyte of weights first.
 */
object ModelText {

    /** Prefixes the model repeats back before starting its answer. */
    private val LEAD_INS = listOf("ANSWER:", "Answer:", "ANSWER", "Rules:", "NOTES:")

    /** Where it stops answering and starts writing itself a fresh prompt. */
    private val RUN_ONS = listOf("\nQUESTION:", "\nNOTES:", "\nRules:", "\nQ:")

    /** Openings that mean the model declined instead of answering. */
    private val REFUSALS = listOf(
        "i'm sorry", "im sorry", "i am sorry", "i cannot assist",
        "i can't assist", "i cant assist", "i cannot help with",
        "i can't help with", "i'm unable to", "i am unable to",
        "as an ai", "as a language model", "i do not have the ability",
        "i don't have the ability",
    )

    /**
     * Did it decline rather than answer?
     *
     * Checked on the first stretch only. A refusal is how these models open;
     * the same words later in a paragraph are usually an ordinary hedge, and
     * hedging is exactly what this app wants them to do.
     */
    fun isRefusal(text: String): Boolean {
        val opening = text.take(80).lowercase()
        return REFUSALS.any { it in opening }
    }

    fun tidy(raw: String): String {
        var text = raw.trim()
        LEAD_INS.forEach { if (text.startsWith(it)) text = text.removePrefix(it).trim() }
        RUN_ONS.forEach {
            val cut = text.indexOf(it)
            if (cut > 0) text = text.take(cut)
        }

        // Real line breaks first, then the literal two-character forms. These
        // models emit backslash-n *as text* often enough that it reached the
        // screen, in answers reading "...at night.\nIf it continues...". The
        // order matters: converting the literals first would leave real CRLFs
        // behind for the paragraph splitter to trip over.
        text = text.replace("\r\n", "\n")
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .replace("\\t", " ")

        return text
            // Emphasis before bullets, and not the other way round. Stripping
            // leading markers first ate the opening asterisk of "**Bold**",
            // which then no longer matched the bold rule and reached the card
            // as "*Bold**".
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("(?m)^#{1,6}\\s*"), "")
            // Bullets are normalised, not removed. The card can draw a real
            // bulleted list, and flattening a list into a paragraph loses the
            // one piece of structure worth keeping. A marker only counts when
            // whitespace follows it, so emphasis is never mistaken for a list.
            .replace(Regex("(?m)^\\s*[*\u2022]\\s+"), "- ")
            .replace(Regex("(?m)^\\s*-\\s+"), "- ")
            .replace(Regex("[ \t]{2,}"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .lines().joinToString("\n") { it.trimEnd() }
            .trim()
    }
}
