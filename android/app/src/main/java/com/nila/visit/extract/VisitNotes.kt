package com.nila.visit.extract

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/**
 * What a visit produced, sorted into the places a parent will look for it.
 *
 * Every item keeps `quote`, the doctor's own words it came from. It is shown
 * under the item, it is what [Grounding] checks against the transcript, and it
 * is the honest answer to "where did the app get that" -- this feature records
 * what was said, it never advises.
 */
data class VisitNotes(
    val medicines: List<Medicine> = emptyList(),
    val vaccines: List<Vaccine> = emptyList(),
    val appointments: List<Appointment> = emptyList(),
    val watchFor: List<Note> = emptyList(),
    val dos: List<Note> = emptyList(),
    val donts: List<Note> = emptyList(),
    val tests: List<Appointment> = emptyList(),
    val summary: String = "",
    /** Items the model produced that did not survive [Grounding], kept for the lab. */
    val dropped: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = medicines.isEmpty() && vaccines.isEmpty() && appointments.isEmpty() &&
            watchFor.isEmpty() && dos.isEmpty() && donts.isEmpty() && tests.isEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("medicines", JSONArray(medicines.map { it.toJson() }))
        .put("vaccines", JSONArray(vaccines.map { it.toJson() }))
        .put("appointments", JSONArray(appointments.map { it.toJson() }))
        .put("watch_for", JSONArray(watchFor.map { it.toJson() }))
        .put("dos", JSONArray(dos.map { it.toJson() }))
        .put("donts", JSONArray(donts.map { it.toJson() }))
        .put("tests", JSONArray(tests.map { it.toJson() }))
        .put("summary", summary)
        .put("dropped", JSONArray(dropped))
}

enum class TimeOfDay(val label: String, val clock: LocalTime) {
    MORNING("morning", LocalTime.of(8, 0)),
    AFTERNOON("afternoon", LocalTime.of(14, 0)),
    EVENING("evening", LocalTime.of(18, 0)),
    NIGHT("night", LocalTime.of(21, 0));

    companion object {
        fun parse(s: String): TimeOfDay? = when {
            Regex("morn|breakfast|subah|wake").containsMatchIn(s.lowercase()) -> MORNING
            Regex("after ?noon|lunch|dopahar|midday").containsMatchIn(s.lowercase()) -> AFTERNOON
            Regex("evening|shaam").containsMatchIn(s.lowercase()) -> EVENING
            Regex("night|bed|dinner|raat|sleep").containsMatchIn(s.lowercase()) -> NIGHT
            else -> null
        }
    }
}

data class Medicine(
    val name: String,
    /** As said: "0.6 ml", "two drops in each nostril". Never computed. */
    val amount: String = "",
    /** As said: "every morning", "only if fever above 100". */
    val instructions: String = "",
    val times: List<TimeOfDay> = emptyList(),
    val timesPerDay: Int? = null,
    val durationDays: Int? = null,
    /** Only when needed ("if there is fever"): noted, never put on an alarm. */
    val asNeeded: Boolean = false,
    val quote: String = "",
) {
    /** The clock times reminders ring at; empty for as-needed medicines. */
    fun reminderTimes(): List<LocalTime> {
        if (asNeeded) return emptyList()
        if (times.isNotEmpty()) return times.map { it.clock }.distinct().sorted()
        return when (timesPerDay) {
            null, 1 -> listOf(LocalTime.of(9, 0))
            2 -> listOf(LocalTime.of(9, 0), LocalTime.of(21, 0))
            3 -> listOf(LocalTime.of(8, 0), LocalTime.of(14, 0), LocalTime.of(20, 0))
            4 -> listOf(LocalTime.of(8, 0), LocalTime.of(12, 0), LocalTime.of(16, 0), LocalTime.of(20, 0))
            else -> (0 until timesPerDay).map { LocalTime.of(8, 0).plusMinutes((12 * 60L / (timesPerDay - 1)) * it) }
        }
    }

    fun toJson(): JSONObject = JSONObject().put("name", name).put("amount", amount)
        .put("instructions", instructions).put("times", JSONArray(times.map { it.label }))
        .put("times_per_day", timesPerDay ?: JSONObject.NULL)
        .put("duration_days", durationDays ?: JSONObject.NULL)
        .put("as_needed", asNeeded).put("quote", quote)
        .put("reminders", JSONArray(reminderTimes().map { it.toString() }))
}

data class Vaccine(
    val name: String,
    val givenToday: Boolean = false,
    val whenSaid: String = "",
    val date: LocalDate? = null,
    val quote: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("given_today", givenToday)
        .put("when", whenSaid).put("date", date?.toString() ?: JSONObject.NULL).put("quote", quote)
}

data class Appointment(
    val what: String,
    val whenSaid: String = "",
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val quote: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("what", what).put("when", whenSaid)
        .put("date", date?.toString() ?: JSONObject.NULL)
        .put("time", time?.toString() ?: JSONObject.NULL).put("quote", quote)
}

data class Note(val text: String, val urgent: Boolean = false, val quote: String = "") {
    fun toJson(): JSONObject = JSONObject().put("text", text).put("urgent", urgent).put("quote", quote)
}
