package com.nila.visit.extract

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The prompts, the parse and the guards -- everything between a transcript and
 * [VisitNotes] except the model call itself, so all of it runs on the JVM.
 *
 * ### Three narrow passes, not one form
 *
 * The first design asked Qwen3.5-2B to fill one JSON form covering everything.
 * Measured on the eight synthetic visits it found 5 of 11 medicines, 6 of 10
 * dates and 13 of 44 pieces of advice: a small model filling a big form fills
 * the first sections and skims the rest, and copies the form's own examples
 * ("times": every time of day, for a once-a-day tablet). So each pass now asks
 * one kind of question -- medicines, then dates, then advice -- over the same
 * transcript, with the engine reset between them. Each answer is shorter, so
 * the three cost about what the one did.
 *
 * ### Amounts are copied, never translated
 *
 * On the Hindi visit the single pass turned "ढाई एमएल" (two and a half ml)
 * into "half a ml". An app that writes down a dose must not change it, so the
 * model copies the amount in the doctor's own words ([Medicine.amount]), and a
 * number it states in English is shown only when [Numbers] finds the same
 * value in the doctor's words; otherwise the screen shows the quote instead.
 */
object Extractor {

    enum class Pass { MEDICINES, DATES, ADVICE }

    private const val RULES = """You write down what a doctor said during a visit, for the family.
Rules:
1. Use only what the doctor said. Never add advice, medicines, amounts or dates of your own.
2. "quote" is always the doctor's exact words, copied from the transcript in the language they were spoken.
3. Other fields are in English unless a field says otherwise.
4. Reply with one JSON object and nothing else. If there is nothing to list, give an empty list."""

    /**
     * The doctor saying "if" in the quote itself: a medicine given on a
     * condition. The model left only_if empty for the Hindi "बुखार सौ से ऊपर
     * जाए तो ... दीजिए" (if the fever goes above 100, give ...), and then
     * scheduled four alarms a day for a fever medicine.
     */
    private val CONDITIONAL = Regex(
        "\\bonly if\\b|(?<!even )\\bif (?:there|the|she|he|it|you|her|his)\\b|\\bonly when\\b|\\bafter every\\b|" +
            "अगर|जाए तो|हो तो|होने पर|இருந்தால்|மட்டும்|ఉంటే|అయితే",
        RegexOption.IGNORE_CASE,
    )

    fun prompt(pass: Pass, transcript: String, visit: LocalDate): String {
        val task = when (pass) {
            Pass.MEDICINES -> """List every medicine the doctor told the family to give or take: drops, syrups, tablets, ORS, supplements.
For each one:
- "name": the medicine, in English
- "amount_as_said": the amount copied exactly from the transcript, in the doctor's words and language, or ""
- "how_often": how often and when, in English, as the doctor said it
- "times": the times of day the doctor named, chosen from morning, afternoon, evening, night; [] if none were named
- "times_per_day": a number if the doctor gave one, else null
- "days": for how many days, as a number, if the doctor said, else null
- "only_if": the condition, if the doctor said to give it only when something happens (for example "if there is fever"); "" if it is given regularly
- "quote"
Reply as {"medicines": [...]}"""
            Pass.DATES -> """List only things that happen on a particular day or at a particular age: the next visit, vaccinations, tests. Also list any vaccine given at this visit.
Do not list medicines or advice here.
For each one:
- "kind": "visit", "vaccine" or "test"
- "what": what it is, in English
- "given_today": true only for a vaccine given at this visit
- "when": when it is, in English words, for example "after three days", "on 8 December", "next Monday at 9 am", "at six months"
- "quote"
Reply as {"items": [...]}"""
            Pass.ADVICE -> """Write down the doctor's advice in three lists, and a one-sentence summary.
- "watch_for": signs to look out for or check; set "come_immediately" to true if the doctor said to come at once
- "do": things the family should do
- "dont": things the family should not do
Each item is {"text": short, in English, "quote"} ("watch_for" items also have "come_immediately").
- "summary": one sentence in English: what the doctor found.
Reply as {"watch_for": [...], "do": [...], "dont": [...], "summary": ""}"""
        }
        // No visit date in the prompt: dates are worked out in code, and with
        // the date in front of it the model listed "Visit date: Saturday" as an
        // appointment and quoted the prompt as the doctor.
        val user = "TRANSCRIPT:\n\"${transcript.trim()}\"\n\n$task"
        return chatMl(RULES, user)
    }

    /** Qwen's own template with reasoning off, as the main app renders it. */
    fun chatMl(system: String, user: String): String =
        "<|im_start|>system\n${system.trim()}<|im_end|>\n" +
            "<|im_start|>user\n${user.trim()}<|im_end|>\n" +
            "<|im_start|>assistant\n<think>\n\n</think>\n\n"

    /**
     * Three passes' replies -> notes. Lenient about the wrapper (code fences,
     * chatter, trailing commas, a reply cut off by the token limit), strict
     * about the content: anything without a quote the transcript contains is
     * dropped and listed in [VisitNotes.dropped].
     */
    fun parse(replies: Map<Pass, String>, transcript: String, visit: LocalDate, birth: LocalDate?): VisitNotes {
        val dropped = ArrayList<String>()
        fun grounded(kind: String, label: String, quote: String): Boolean {
            val ok = Grounding.supported(quote, transcript) && !quote.contains("TRANSCRIPT", ignoreCase = false)
            if (!ok) dropped += "$kind '$label' (quote not in transcript: '${quote.take(80)}')"
            return ok
        }
        fun obj(p: Pass): JSONObject? = replies[p]?.let { r ->
            jsonObjectIn(r).also { if (it == null && r.isNotBlank()) dropped += "${p.name.lowercase()}: unreadable reply" }
        }
        val latin = Grounding.isLatin(transcript)

        val medicines = obj(Pass.MEDICINES)?.arr("medicines").orEmpty().mapNotNull { m ->
            val name = m.str("name"); val quote = m.str("quote")
            if (name.isBlank() || !grounded("medicine", name, quote)) return@mapNotNull null
            if (!Grounding.nameSaid(name, transcript)) {
                dropped += "medicine '$name' (name not in transcript)"; return@mapNotNull null
            }
            val asSaid = m.str("amount_as_said").ifBlank { m.str("amount") }
            // An amount is shown only in words the doctor used, and for an
            // English visit only when its numbers are the quote's numbers.
            val amount = when {
                asSaid.isBlank() -> ""
                !Grounding.supportedLoose(asSaid, transcript) -> { dropped += "amount '$asSaid' for $name (not in transcript)"; "" }
                latin && !Numbers.consistent(asSaid, quote) -> { dropped += "amount '$asSaid' for $name (numbers differ from the quote)"; "" }
                else -> asSaid
            }
            val how = m.str("how_often").ifBlank { m.str("when") }
            val onlyIf = m.str("only_if")
            // "After each meal" is three times a day at meal times.
            val withMeals = Regex("(each|every|all|with|after|before) meals?|after food|khana", RegexOption.IGNORE_CASE)
                .containsMatchIn("$how $quote")
            val times = (if (withMeals) listOf("morning", "afternoon", "night") else m.strings("times")).mapNotNull { TimeOfDay.parse(it) }.distinct()
                // All four named = the list of options copied, not an answer.
                .takeIf { it.size < TimeOfDay.entries.size }.orEmpty()
                .ifEmpty { listOfNotNull(TimeOfDay.parse(how)) }
            Medicine(
                name = name,
                amount = amount,
                instructions = listOf(how, onlyIf.takeIf { it.isNotBlank() && !how.contains(it, ignoreCase = true) }
                    ?.let { "only $it".replace("only only", "only") })
                    .filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                // "Three times a day" with one time named: the count is the instruction.
                times = (m.intOrNull("times_per_day") ?: timesPerDayIn(how))?.let { n -> if (n > times.size) emptyList() else times } ?: times,
                timesPerDay = m.intOrNull("times_per_day") ?: timesPerDayIn(how),
                durationDays = m.intOrNull("days") ?: daysIn(how) ?: (if (latin) daysIn(quote) ?: daysIn(clauseAbout(name, transcript)) else null),
                asNeeded = onlyIf.isNotBlank() ||
                    Regex("only if|only when|as needed|when needed|after every", RegexOption.IGNORE_CASE).containsMatchIn(how) ||
                    CONDITIONAL.containsMatchIn(quote),
                quote = quote,
            )
        }

        val vaccines = ArrayList<Vaccine>(); val appointments = ArrayList<Appointment>(); val tests = ArrayList<Appointment>()
        // Dates the transcript states outright, found in code (English only),
        // come first: their labels are clean ("Next visit") and their quote is
        // the doctor's sentence. The model's dates fill in days these missed.
        for (a in DateFinder.find(transcript, visit, birth)) {
            when (a.what) {
                "Vaccination" -> vaccines += Vaccine("Next vaccination", false, a.whenSaid, a.date, a.quote)
                "Test" -> tests += a
                else -> appointments += a
            }
        }
        fun known(date: java.time.LocalDate?) = date != null &&
            ((appointments + tests).any { it.date == date } || vaccines.any { it.date == date })
        obj(Pass.DATES)?.arr("items").orEmpty().forEach { d ->
            val what = d.str("what").ifBlank { "Next visit" }; val quote = d.str("quote")
            if (!grounded("date", what, quote)) return@forEach
            val r = WhenResolver.resolve(d.str("when") + " " + (if (latin) quote else ""), visit, birth)
            val given = d.str("kind").equals("vaccine", true) && d.optBoolean("given_today", false)
            // A "date" with no day the code can find is advice in disguise --
            // the model listed "tummy time, three or four times a day" here.
            if (!given && r.date == null) { dropped += "date '$what' (no day in '${d.str("when")}')"; return@forEach }
            // Today's visit is not a future appointment.
            if (!given && r.date != null && !r.date.isAfter(visit)) { dropped += "date '$what' (is the visit itself)"; return@forEach }
            if (!given && known(r.date)) return@forEach
            when (d.str("kind").lowercase()) {
                "vaccine" -> {
                    vaccines += Vaccine(what, given, d.str("when"), if (given) visit else r.date, quote)
                }
                "test" -> tests += Appointment(what, d.str("when"), r.date, r.time, quote)
                else -> appointments += Appointment(what, d.str("when"), r.date, r.time, quote)
            }
        }

        val advice = obj(Pass.ADVICE)
        fun notes(key: String, urgentKey: String? = null) = advice?.arr(key).orEmpty().mapNotNull { n ->
            val text = n.str("text"); val quote = n.str("quote")
            if (text.isBlank() || !grounded(key, text, quote)) return@mapNotNull null
            Note(text, urgentKey != null && n.optBoolean(urgentKey, false), quote)
        }
        return VisitNotes(
            medicines = medicines, vaccines = vaccines, appointments = appointments,
            watchFor = notes("watch_for", "come_immediately"), dos = notes("do"), donts = notes("dont"),
            tests = tests, summary = advice?.optString("summary", "").orEmpty(), dropped = dropped,
        )
    }

    /** The outermost {...} in the text, parsed leniently. */
    fun jsonObjectIn(text: String): JSONObject? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0; var inStr = false; var esc = false
        for (i in start until text.length) {
            val c = text[i]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return lenient(text.substring(start, i + 1)) }
            }
        }
        return repairTruncated(text.substring(start))
    }

    /**
     * A reply cut off by the token limit: keep every element that finished.
     * Walk back to the last '}' or ']' that closed something, and close the
     * brackets still open at that point.
     */
    private fun repairTruncated(s: String): JSONObject? {
        val stack = ArrayDeque<Char>()
        val closes = ArrayList<Pair<Int, String>>()
        var inStr = false; var esc = false
        for (i in s.indices) {
            val c = s[i]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) {
                '"' -> inStr = true
                '{', '[' -> stack.addLast(c)
                '}', ']' -> {
                    if (stack.isNotEmpty()) stack.removeLast()
                    closes += i to stack.reversed().joinToString("") { if (it == '{') "}" else "]" }
                }
            }
        }
        for ((i, closing) in closes.asReversed()) {
            lenient(s.substring(0, i + 1) + closing)?.let { return it }
        }
        return null
    }

    private fun lenient(s: String): JSONObject? =
        runCatching { JSONObject(s.replace(Regex(",\\s*([}\\]])"), "$1")) }.getOrNull()

    private fun timesPerDayIn(s: String): Int? {
        val l = s.lowercase()
        return when {
            Regex("\\bonce\\b|one time|1 time").containsMatchIn(l) -> 1
            Regex("twice|two times|2 times").containsMatchIn(l) -> 2
            Regex("three times|thrice|3 times").containsMatchIn(l) -> 3
            Regex("four times|4 times").containsMatchIn(l) -> 4
            else -> null
        }
    }

    /**
     * The doctor's own clause about one medicine: the sentence that names it,
     * cut at the next "and the" / ";" so a second medicine's course is not
     * borrowed. "take the iron tablet once a day after lunch for three months,
     * and the calcium tablet at night" gives iron its three months and calcium
     * none. Used only when neither the model nor its quote gave a length.
     */
    fun clauseAbout(name: String, transcript: String): String {
        val key = Grounding.tokens(name).filter { it.length >= 3 }.firstOrNull { w ->
            w !in setOf("syrup", "drops", "drop", "tablet", "tablets", "nasal", "medicine")
        } ?: return ""
        val sentence = transcript.split(Regex("(?<=[.!?])\\s+")).firstOrNull { key in it.lowercase() } ?: return ""
        val from = sentence.lowercase().indexOf(key)
        val rest = sentence.substring(from)
        val end = Regex(",? and the |;|, and |\\. ").find(rest, 1)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    /** "for fourteen days", "for three months" -> days; null when not said. */
    fun daysIn(s: String): Int? {
        val m = Regex("for (?:the next |another )?([a-z0-9 ]+?) (day|week|month)s?\\b").find(s.lowercase()) ?: return null
        val base = LocalDate.of(2000, 1, 1)
        val r = WhenResolver.resolve("in ${m.groupValues[1]} ${m.groupValues[2]}", base).date ?: return null
        return java.time.temporal.ChronoUnit.DAYS.between(base, r).toInt().takeIf { it > 0 }
    }

    // ---- small JSON helpers, forgiving about what a 2B model emits
    private fun JSONObject.arr(k: String): List<JSONObject> {
        val a = optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }
    private fun JSONObject.str(k: String): String = optString(k, "").let { if (it == "null") "" else it.trim() }
    private fun JSONObject.strings(k: String): List<String> {
        val a: JSONArray = optJSONArray(k) ?: return optString(k, "").split(',', '|').filter { it.isNotBlank() }
        return (0 until a.length()).map { a.optString(it) }.flatMap { it.split('|', ',') }.filter { it.isNotBlank() }
    }
    private fun JSONObject.intOrNull(k: String): Int? =
        if (isNull(k)) null else optInt(k, -1).takeIf { it > 0 }
}
