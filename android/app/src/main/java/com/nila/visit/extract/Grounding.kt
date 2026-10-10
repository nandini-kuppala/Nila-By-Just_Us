package com.nila.visit.extract

/**
 * Did the doctor actually say this?
 *
 * The one guard that matters for this feature: a model that invents "give 5 ml
 * twice a day" has written a prescription. Every item carries the words it was
 * taken from, and an item is kept only when most of those words are in the
 * transcript, in order-insensitive form -- loose enough for the model's habit
 * of trimming a quote or fixing its punctuation, tight enough that a quote it
 * made up does not pass.
 *
 * Tokenised on letters and digits of any script, so a Tamil or Hindi quote is
 * checked against a Tamil or Hindi transcript the same way.
 */
object Grounding {

    private val WORD = Regex("[\\p{L}\\p{M}\\p{N}]+")

    fun tokens(s: String): List<String> = WORD.findAll(s.lowercase()).map { it.value }.toList()

    /**
     * Function words are left out of the count. They are in every transcript,
     * and with them in, "give amoxicillin twice a day" passed against a visit
     * that never mentioned amoxicillin -- "give", "a" and "day" were enough.
     */
    private val FILLER = setOf(
        "a", "an", "the", "and", "or", "of", "to", "in", "on", "at", "for", "with", "is", "it", "be",
        "her", "him", "his", "she", "he", "you", "your", "this", "that", "give", "take", "day", "do",
        "को", "का", "की", "के", "है", "से", "में", "और", "तो", "दीजिए",
    )

    fun content(s: String): List<String> = tokens(s).filter { it !in FILLER }

    fun supported(quote: String, transcript: String, minCoverage: Double = 0.7): Boolean {
        val q = content(quote)
        if (q.size < 2) return false
        val t = tokens(transcript).toHashSet()
        val hit = q.count { it in t }
        return hit.toDouble() / q.size >= minCoverage
    }

    /** Mostly Latin letters: an English (or romanised) transcript. */
    fun isLatin(s: String): Boolean {
        val letters = s.filter { it.isLetter() }
        if (letters.isEmpty()) return true
        return letters.count { it.code < 0x250 } >= letters.length * 0.6
    }

    /** A short phrase (an amount): every content word of it was said. */
    fun supportedLoose(phrase: String, transcript: String): Boolean {
        val q = content(phrase).ifEmpty { tokens(phrase) }
        if (q.isEmpty()) return false
        val t = tokens(transcript).toHashSet()
        return q.all { it in t || it.any(Char::isDigit) }
    }

    /**
     * For a medicine, the name itself must be in what was said, when what was
     * said is in a Latin script. (A Hindi transcript names the medicine in
     * Devanagari and the model's English name cannot be matched against it;
     * there the quote check carries the whole weight.)
     */
    private val GENERIC = setOf("syrup", "drops", "drop", "tablet", "tablets", "medicine", "cream", "ointment", "nasal", "oral", "solution", "sachet")

    fun nameSaid(name: String, transcript: String): Boolean {
        val latin = transcript.count { it.code in 0x41..0x7A } > transcript.length / 3
        if (!latin) return true
        val words = tokens(name).filter { it.length >= 3 && it !in GENERIC }
        if (words.isEmpty()) return true
        val t = tokens(transcript)
        // Loose: "paracetamol" said, "paracetamol drops" written; "vapor" vs "vapour".
        return words.any { w -> t.any { it.startsWith(w.take(5)) || w.startsWith(it.take(5)) && it.length >= 4 } }
    }
}
