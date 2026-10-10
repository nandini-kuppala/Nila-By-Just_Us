package com.nila.visit.extract

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.temporal.TemporalAdjusters

/**
 * Turns "after three days", "on the eighth of December", "next Monday at nine"
 * into a date, relative to the day of the visit.
 *
 * Deliberately not the language model's job. A 2B model asked for a date does
 * the arithmetic wrong often enough to send a family to a clinic on the wrong
 * day; asked to copy the phrase, it copies it, and this does the rest. When a
 * phrase cannot be read the date is null and the screen asks the parent to set
 * it -- never a guess.
 *
 * The model is told to write these phrases in English whatever language the
 * doctor spoke, so English is all this reads.
 */
object WhenResolver {

    data class Resolved(val date: LocalDate?, val time: LocalTime?)

    private val NUMBER_WORDS = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
        "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
        "twenty" to 20, "thirty" to 30, "couple of" to 2, "few" to 3,
    )
    private val ORDINALS = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6,
        "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10, "eleventh" to 11,
        "twelfth" to 12, "thirteenth" to 13, "fourteenth" to 14, "fifteenth" to 15,
        "sixteenth" to 16, "seventeenth" to 17, "eighteenth" to 18, "nineteenth" to 19,
        "twentieth" to 20, "twenty first" to 21, "twenty second" to 22, "twenty third" to 23,
        "twenty fourth" to 24, "twenty fifth" to 25, "twenty sixth" to 26, "twenty seventh" to 27,
        "twenty eighth" to 28, "twenty ninth" to 29, "thirtieth" to 30, "thirty first" to 31,
    )
    private val MONTHS = Month.entries.associateBy { it.name.lowercase() } +
        Month.entries.associateBy { it.name.lowercase().take(3) }

    fun resolve(phrase: String, visit: LocalDate, birth: LocalDate? = null): Resolved {
        val p = normalise(phrase)
        return Resolved(date(p, visit, birth), time(p))
    }

    private fun normalise(s: String): String {
        var p = " " + s.lowercase().replace(Regex("[,;!?()]"), " ").replace(Regex("\\.(?!\\d)"), " ").replace('-', ' ') + " "
        p = p.replace(Regex("\\s+"), " ")
        // Ordinals first ("twenty first" before "first"), then cardinals.
        for ((w, n) in ORDINALS.entries.sortedByDescending { it.key.length }) p = p.replace(" $w ", " $n ")
        for ((w, n) in NUMBER_WORDS.entries.sortedByDescending { it.key.length }) p = p.replace(" $w ", " $n ")
        return p.replace(Regex("(\\d+)(st|nd|rd|th)\\b"), "$1")
    }

    private fun date(p: String, visit: LocalDate, birth: LocalDate?): LocalDate? {
        if (Regex(" today | tonight | now ").containsMatchIn(p)) return visit
        if (" day after tomorrow " in p) return visit.plusDays(2)
        if (" tomorrow " in p) return visit.plusDays(1)

        // "at six months", "when she is 9 months old": an age, so from birth.
        Regex(" (?:at|when (?:she|he|baby) (?:is|turns)) (\\d+) months?(?: old| of age)? ").find(p)?.let { m ->
            if (birth != null) return birth.plusMonths(m.groupValues[1].toLong())
        }
        // "in 3 days", "after two weeks", "3 days later", "after 1 month"
        Regex(" (?:in|after|within) (\\d+) (day|week|month)s? ").find(p)?.let { return plus(visit, it) }
        Regex(" (\\d+) (day|week|month)s? (?:later|from now|after) ").find(p)?.let { return plus(visit, it) }
        if (" next week " in p) return visit.plusWeeks(1)
        if (" next month " in p) return visit.plusMonths(1)

        // "on the 8 of december", "december 8", "8 december", "8/12"
        Regex(" (\\d{1,2}) (?:of )?([a-z]{3,9}) ").find(p)?.let { m ->
            MONTHS[m.groupValues[2]]?.let { mo -> return dated(visit, mo, m.groupValues[1].toInt()) }
        }
        Regex(" ([a-z]{3,9}) (\\d{1,2}) ").find(p)?.let { m ->
            MONTHS[m.groupValues[1]]?.let { mo -> return dated(visit, mo, m.groupValues[2].toInt()) }
        }
        Regex(" (\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))? ").find(p)?.let { m ->
            val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
            if (mo in 1..12 && d in 1..31) return dated(visit, Month.of(mo), d)
        }
        // "monday", "next monday", "on friday": the next one after the visit.
        DayOfWeek.entries.firstOrNull { " ${it.name.lowercase()} " in p }?.let { dow ->
            return visit.with(TemporalAdjusters.next(dow))
        }
        // "the 24th" alone: that day this month, or next month if it has passed.
        Regex(" (?:on )?the (\\d{1,2}) ").find(p)?.let { m ->
            val d = m.groupValues[1].toInt()
            if (d in 1..31) {
                val thisMonth = runCatching { visit.withDayOfMonth(d) }.getOrNull()
                return if (thisMonth != null && thisMonth.isAfter(visit)) thisMonth
                else runCatching { visit.plusMonths(1).withDayOfMonth(d) }.getOrNull()
            }
        }
        return null
    }

    private fun plus(visit: LocalDate, m: MatchResult): LocalDate {
        val n = m.groupValues[1].toLong()
        return when (m.groupValues[2]) {
            "day" -> visit.plusDays(n)
            "week" -> visit.plusWeeks(n)
            else -> visit.plusMonths(n)
        }
    }

    /** That day in the coming twelve months: a visit date is never in the past. */
    private fun dated(visit: LocalDate, month: Month, day: Int): LocalDate? {
        val candidate = runCatching { LocalDate.of(visit.year, month, day) }.getOrNull() ?: return null
        return if (candidate.isBefore(visit)) candidate.plusYears(1) else candidate
    }

    private fun time(p: String): LocalTime? {
        Regex(" (?:at )?(\\d{1,2})(?::(\\d{2}))? ?(am|pm|a m|p m|o clock|in the (morning|evening|afternoon|night))").find(p)?.let { m ->
            var h = m.groupValues[1].toInt(); val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            val suffix = m.groupValues[3]
            if ((suffix.startsWith("p") || suffix.contains("evening") || suffix.contains("afternoon") ||
                    suffix.contains("night")) && h < 12) h += 12
            if (suffix.startsWith("a") && h == 12) h = 0
            if (h in 0..23 && min in 0..59) return LocalTime.of(h, min)
        }
        return null
    }
}
