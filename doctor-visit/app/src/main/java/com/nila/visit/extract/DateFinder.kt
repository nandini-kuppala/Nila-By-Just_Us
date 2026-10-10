package com.nila.visit.extract

import java.time.LocalDate

/**
 * Appointments found in the transcript itself, without the model.
 *
 * A date is the one kind of note with an exact form, and the 2B model was the
 * weak link for it: measured on the synthetic visits, it found 3 of 10 dates
 * once it was told not to list advice as dates, and 6 of 10 when it listed
 * everything. A sentence that says when to come back and gives a day is easy
 * to find in plain code, and its quote is the sentence -- traceable by
 * construction.
 *
 * English only (the resolver reads English); for other languages the model's
 * dates, which it writes in English, are what there is.
 */
object DateFinder {

    private val VISIT = Regex(
        "\\b(come back|come again|come for|come on|come at|show (?:her|him|me|the baby)|review|follow ?up|" +
            "next (?:visit|vaccine|dose|appointment|check)|vaccin\\w*|test\\b|sample|check-?up|bring)",
        RegexOption.IGNORE_CASE,
    )

    fun find(transcript: String, visit: LocalDate, birth: LocalDate?): List<Appointment> {
        if (!Grounding.isLatin(transcript)) return emptyList()
        val out = ArrayList<Appointment>()
        val sentences = transcript.split(Regex("(?<=[.!?])\\s+|\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        for (s in sentences) {
            // Whisper sometimes leaves out the full stops, and a paragraph read
            // as one sentence finds a "date" in one clause and a "come back" in
            // another. A real appointment sentence is short.
            if (s.split(Regex("\\s+")).size > 30) continue
            val kw = VISIT.find(s) ?: continue
            val r = WhenResolver.resolve(s, visit, birth)
            val date = r.date ?: continue
            if (!date.isAfter(visit)) continue            // "today we have given" is not an appointment
            val l = s.lowercase()
            val what = when {
                "vaccin" in l || "dose" in l -> "Vaccination"
                Regex("\\btest\\b|sample|blood|bilirubin|scan").containsMatchIn(l) -> "Test"
                else -> "Next visit"
            }
            if (out.none { it.date == date && it.what == what }) out += Appointment(what, kw.value, date, r.time, s)
        }
        return out
    }
}
