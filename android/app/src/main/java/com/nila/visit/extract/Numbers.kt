package com.nila.visit.extract

/**
 * The numbers in a piece of English, however they were said.
 *
 * "zero point six ml", "0.6 ml" and "point six" are the same amount, and
 * "two and a half" is 2.5. Used to check that an amount the model wrote is an
 * amount the doctor said -- the check that would have caught "half a ml" for
 * two and a half.
 */
object Numbers {
    private val UNITS = mapOf(
        "zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
        "seventy" to 70, "eighty" to 80, "ninety" to 90)

    fun values(text: String): Set<Double> {
        val out = HashSet<Double>()
        Regex("\\d+(?:\\.\\d+)?").findAll(text).forEach { out += it.value.toDouble() }
        if (Regex("\\bhalf\\b").containsMatchIn(text.lowercase()) && !Regex("and a half").containsMatchIn(text.lowercase())) out += 0.5
        val words = text.lowercase().replace(Regex("[^a-z ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        var i = 0
        while (i < words.size) {
            val (n, used) = readNumber(words, i)
            if (used == 0) { i++; continue }
            var value = n.toDouble(); var j = i + used
            // "zero point six", "two point five"
            if (j < words.size && words[j] == "point") {
                val digits = StringBuilder(); var k = j + 1
                while (k < words.size && UNITS[words[k]]?.let { it < 10 } == true) { digits.append(UNITS[words[k]]); k++ }
                if (digits.isNotEmpty()) { value = "$n.$digits".toDouble(); j = k }
            }
            // "two and a half"
            if (j + 2 < words.size && words[j] == "and" && words[j + 1] == "a" && words[j + 2] == "half") { value += 0.5; j += 3 }
            out += value; i = j
        }
        // "point six" with no whole part
        Regex("(?<!\\w)point ((?:zero|one|two|three|four|five|six|seven|eight|nine)(?: (?:zero|one|two|three|four|five|six|seven|eight|nine))*)")
            .findAll(text.lowercase()).forEach { m ->
                out += ("0." + m.groupValues[1].split(" ").joinToString("") { UNITS[it].toString() }).toDouble()
            }
        return out
    }

    private fun readNumber(w: List<String>, i: Int): Pair<Int, Int> {
        var total = 0; var used = 0; var j = i
        TENS[w.getOrNull(j)]?.let { total += it; j++; used++ ; UNITS[w.getOrNull(j)]?.takeIf { it in 1..9 }?.let { u -> total += u; j++; used++ } ; return total to used }
        UNITS[w.getOrNull(j)]?.let { total += it; j++; used++ }
        if (used > 0 && w.getOrNull(j) == "hundred") { total *= 100; j++; used++
            if (w.getOrNull(j) == "and") { val (n, u) = readNumber(w, j + 1); if (u > 0) { total += n; used += u + 1 } }
            else { val (n, u) = readNumber(w, j); if (u > 0) { total += n; used += u } }
        }
        return total to used
    }

    /** Every number in [amount] appears, by value, in [quote]. Vacuously true with none. */
    fun consistent(amount: String, quote: String): Boolean {
        val a = values(amount)
        if (a.isEmpty()) return true
        val q = values(quote)
        return a.all { x -> q.any { kotlin.math.abs(it - x) < 1e-6 } }
    }
}
