package com.nila.visit

import android.content.Context
import com.nila.visit.extract.Appointment
import com.nila.visit.extract.Medicine
import com.nila.visit.extract.Note
import com.nila.visit.extract.TimeOfDay
import com.nila.visit.extract.Vaccine
import com.nila.visit.extract.VisitNotes
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * Saved visits: one JSON file per visit, beside its recording.
 *
 * Plain files rather than a database because this is a lab -- the integrated
 * feature will move them into the app's Room store as its own tables.
 */
class VisitStore(private val context: Context) {

    data class Saved(
        val id: String,
        val date: LocalDate,
        val title: String,
        val language: String?,
        val transcript: String,
        val notes: VisitNotes,
        val stages: JSONArray,
        val remindersSet: Boolean,
    )

    private val dir get() = File(context.getExternalFilesDir(null), "visits").apply { mkdirs() }

    fun audioFile(id: String) = File(dir, "$id.wav")

    fun save(v: Saved) {
        File(dir, "${v.id}.json").writeText(
            JSONObject().put("id", v.id).put("date", v.date.toString()).put("title", v.title)
                .put("language", v.language ?: JSONObject.NULL).put("transcript", v.transcript)
                .put("notes", v.notes.toJson()).put("stages", v.stages).put("reminders_set", v.remindersSet)
                .toString(1)
        )
    }

    fun all(): List<Saved> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { runCatching { read(JSONObject(it.readText())) }.getOrNull() }
        .sortedByDescending { it.id }

    fun get(id: String): Saved? = File(dir, "$id.json").takeIf { it.exists() }?.let { read(JSONObject(it.readText())) }

    fun delete(id: String) { File(dir, "$id.json").delete(); audioFile(id).delete() }

    private fun read(o: JSONObject) = Saved(
        id = o.getString("id"), date = LocalDate.parse(o.getString("date")), title = o.optString("title"),
        language = o.optString("language").takeIf { it.isNotBlank() && it != "null" },
        transcript = o.optString("transcript"), notes = notesFrom(o.getJSONObject("notes")),
        stages = o.optJSONArray("stages") ?: JSONArray(), remindersSet = o.optBoolean("reminders_set"),
    )

    companion object {
        fun notesFrom(o: JSONObject): VisitNotes {
            fun arr(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
            fun date(s: String?) = s?.takeIf { it.isNotBlank() && it != "null" }?.let(LocalDate::parse)
            fun time(s: String?) = s?.takeIf { it.isNotBlank() && it != "null" }?.let(LocalTime::parse)
            fun appt(j: JSONObject) = Appointment(j.optString("what"), j.optString("when"),
                date(j.optString("date")), time(j.optString("time")), j.optString("quote"))
            fun note(j: JSONObject) = Note(j.optString("text"), j.optBoolean("urgent"), j.optString("quote"))
            return VisitNotes(
                medicines = arr("medicines").map { m ->
                    Medicine(
                        m.optString("name"), m.optString("amount"), m.optString("instructions"),
                        m.optJSONArray("times")?.let { a -> (0 until a.length()).mapNotNull { TimeOfDay.parse(a.getString(it)) } } ?: emptyList(),
                        if (m.isNull("times_per_day")) null else m.optInt("times_per_day"),
                        if (m.isNull("duration_days")) null else m.optInt("duration_days"),
                        m.optBoolean("as_needed"), m.optString("quote"),
                    )
                },
                vaccines = arr("vaccines").map { v -> Vaccine(v.optString("name"), v.optBoolean("given_today"), v.optString("when"), date(v.optString("date")), v.optString("quote")) },
                appointments = arr("appointments").map(::appt),
                watchFor = arr("watch_for").map(::note), dos = arr("dos").map(::note), donts = arr("donts").map(::note),
                tests = arr("tests").map(::appt), summary = o.optString("summary"),
                dropped = o.optJSONArray("dropped")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            )
        }
    }
}
