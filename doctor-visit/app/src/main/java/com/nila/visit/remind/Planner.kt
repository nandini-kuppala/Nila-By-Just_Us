package com.nila.visit.remind

import com.nila.visit.extract.VisitNotes
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * What to remind the family of, and when, from one visit's notes.
 *
 * Pure: no Android, so the schedule a visit produces is a JVM test. Rules:
 *  - a medicine rings at each of its times, every day of its course, starting
 *    with the first time still ahead on the day of the visit;
 *  - a course with no length ("do not stop") is planned 30 days ahead and the
 *    screen says so -- an alarm that silently runs forever is worse than one
 *    the parent renews;
 *  - an only-if medicine never rings; it is written down, not scheduled;
 *  - a visit, vaccine or test rings at 7 pm the evening before and at 8 am on
 *    the day (or an hour before, when the doctor gave a time).
 */
object Planner {

    const val OPEN_ENDED_DAYS = 30

    enum class Kind { MEDICINE, VISIT, VACCINE, TEST }

    data class Reminder(val at: LocalDateTime, val kind: Kind, val title: String, val body: String) {
        fun toJson(): JSONObject = JSONObject().put("at", at.toString()).put("kind", kind.name)
            .put("title", title).put("body", body)

        companion object {
            fun fromJson(o: JSONObject) = Reminder(
                LocalDateTime.parse(o.getString("at")), Kind.valueOf(o.getString("kind")),
                o.getString("title"), o.getString("body"),
            )
        }
    }

    fun plan(notes: VisitNotes, visit: LocalDate, now: LocalDateTime = LocalDateTime.now()): List<Reminder> {
        val out = ArrayList<Reminder>()
        for (m in notes.medicines) {
            val times = m.reminderTimes()
            if (times.isEmpty()) continue
            val days = m.durationDays ?: OPEN_ENDED_DAYS
            val body = listOf(m.amount, m.instructions).filter { it.isNotBlank() }.joinToString(" · ")
            var given = 0
            var day = visit
            // A course of N days is N days of doses, starting today if a time is still ahead.
            while (given < days) {
                val upcoming = times.map { LocalDateTime.of(day, it) }.filter { it.isAfter(now) }
                if (upcoming.isNotEmpty() || day.isAfter(now.toLocalDate())) {
                    upcoming.forEach { out += Reminder(it, Kind.MEDICINE, m.name, body) }
                    given++
                } else if (day == visit) {
                    // Every time today has passed: the course starts tomorrow.
                }
                day = day.plusDays(1)
                if (day.isAfter(visit.plusDays(days + 1L))) break
            }
        }
        fun event(date: LocalDate?, time: LocalTime?, kind: Kind, title: String, body: String) {
            date ?: return
            val eve = LocalDateTime.of(date.minusDays(1), LocalTime.of(19, 0))
            val day = if (time != null) LocalDateTime.of(date, time).minusHours(1) else LocalDateTime.of(date, LocalTime.of(8, 0))
            listOf(eve, day).filter { it.isAfter(now) }.forEach { out += Reminder(it, kind, title, body) }
        }
        notes.appointments.forEach { event(it.date, it.time, Kind.VISIT, it.what, it.whenSaid) }
        notes.vaccines.filter { !it.givenToday }.forEach { event(it.date, null, Kind.VACCINE, it.name, it.whenSaid) }
        notes.tests.forEach { event(it.date, it.time, Kind.TEST, it.what, it.whenSaid) }
        return out.sortedBy { it.at }
    }

    fun toJson(list: List<Reminder>) = JSONArray(list.map { it.toJson() })
}
