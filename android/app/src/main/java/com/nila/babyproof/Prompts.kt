package com.nila.babyproof

/**
 * What the vision model is asked, and how its answers are read.
 *
 * The same lesson as Nila's danger watch: a small model is reliable when it
 * fills a short form with closed answers, and unreliable when it writes free
 * text. Naming the object before the hazard makes it commit to what it saw.
 */
object Prompts {

    val HAZARDS = listOf("electric", "fall", "tip-over", "sharp", "poison", "choking", "burn", "water", "strangle")

    const val SYSTEM = "You check homes for baby safety. Reply with exactly the lines asked for, nothing else."

    /**
     * About one crop around a detector hit. The model is not asked whether the
     * object is dangerous -- a 2B model calls most windows and stairs "safe" --
     * only whether the detector is right about what it is, whether it is already
     * babyproofed, and whether a baby can get to it. That a socket is a hazard is
     * the catalogue's knowledge, not the model's.
     */
    fun crop(h: Hazard) = """This is part of a photo of a room where a baby who crawls and pulls up to stand will live.
Look at the object in the middle of the picture and answer in four lines:
OBJECT: what it is, in a few words
MATCH: yes or no (is it ${h.query}?)
PROTECTED: yes or no (is a cover, gate, grill, lock or wall anchor already fitted on it?)
REACHABLE: yes or no (could a crawling or standing baby reach it or climb to it?)"""

    /** About the whole photo: catches what the word list has no word for. */
    const val SCENE = """This is a photo of a room where a baby who crawls and pulls up to stand will live.
Name up to six objects in this room that the baby could get hurt by, separated by commas.
If there are none, write NONE."""

    private val field = Regex("""^\s*\**\s*(OBJECT|MATCH|HAZARD|REACHABLE|PROTECTED)\s*\**\s*[:\-]\s*(.*)$""", RegexOption.IGNORE_CASE)

    fun parseCrop(raw: String): CropAnswer? {
        val f = mutableMapOf<String, String>()
        raw.lines().forEach { line ->
            field.find(line)?.let { m -> f.putIfAbsent(m.groupValues[1].uppercase(), m.groupValues[2].trim().trim('*', '.', ' ')) }
        }
        val match = yesNo(f["MATCH"])
        if (match == null && f["HAZARD"] == null) return null
        return CropAnswer(
            obj = f["OBJECT"].orEmpty(),
            hazard = f["HAZARD"]?.let { normaliseHazard(it) } ?: if (match == false) "none" else "match",
            reachable = yesNo(f["REACHABLE"]),
            protected = yesNo(f["PROTECTED"]),
            raw = raw.trim(),
            match = match,
        )
    }

    fun parseScene(raw: String): List<SceneItem> {
        val text = raw.trim()
        if (text.uppercase().startsWith("NONE")) return emptyList()
        return text.split(',', '\n', ';').map { it.trim().trimStart('-', '*', '•', ' ').replace(Regex("""^\d+[.)]\s*"""), "").trim('.', ' ') }
            .filter { it.length in 3..60 && !it.equals("none", true) }
            .map { SceneItem(it, "", "") }
            .distinctBy { it.thing.lowercase() }.take(6)
    }

    /** Map the model's word onto the closed list; "tip over", "electrical", "fire" all land somewhere. */
    fun normaliseHazard(s: String): String? {
        val w = s.lowercase().trim()
        return when {
            w.startsWith("none") || w == "no" || w.startsWith("no hazard") -> "none"
            "electr" in w || "shock" in w -> "electric"
            "tip" in w || "topple" in w -> "tip-over"
            "fall" in w -> "fall"
            "sharp" in w || "cut" in w -> "sharp"
            "poison" in w || "chemical" in w || "toxic" in w -> "poison"
            "chok" in w || "swallow" in w || "suffoc" in w -> "choking"
            "burn" in w || "fire" in w || "hot" in w || "heat" in w -> "burn"
            "water" in w || "drown" in w -> "water"
            "strang" in w || "cord" in w -> "strangle"
            else -> null
        }
    }

    private fun yesNo(s: String?): Boolean? {
        val w = s?.lowercase()?.trim() ?: return null
        return when {
            w.startsWith("yes") -> true
            w.startsWith("no") -> false
            else -> null
        }
    }
}
