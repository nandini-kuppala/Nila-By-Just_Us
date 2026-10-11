package com.nila.babyproof.pipeline

import org.json.JSONArray
import org.json.JSONObject

/** A box in normalised (0..1) coordinates of the whole photo. */
data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w get() = r - l
    val h get() = b - t
    val area get() = (w.coerceAtLeast(0f)) * (h.coerceAtLeast(0f))
    val cx get() = (l + r) / 2
    val cy get() = (t + b) / 2

    fun iou(o: Box): Float {
        val iw = (minOf(r, o.r) - maxOf(l, o.l)).coerceAtLeast(0f)
        val ih = (minOf(b, o.b) - maxOf(t, o.t)).coerceAtLeast(0f)
        val inter = iw * ih
        val union = area + o.area - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun clamp() = Box(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))

    fun json() = JSONArray(listOf(l.r3(), t.r3(), r.r3(), b.r3()))

    companion object {
        fun of(a: JSONArray) = Box(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())
    }
}

internal fun Float.r3() = Math.round(this * 1000) / 1000.0

enum class Severity(val rank: Int, val label: String) { URGENT(0, "Urgent"), HIGH(1, "High"), MEDIUM(2, "Medium") }

/** What a finding turned out to be after the model and the rules looked at it. */
enum class Status(val label: String) {
    /** The detector found it and the vision model agreed it is a hazard a baby can reach. */
    FIX("Fix this"),
    /** A critical thing the model was unsure about, or one only the room-wide look named. */
    CHECK("Check this"),
    /** Already covered, gated, locked or anchored. */
    PROTECTED("Already done"),
}

data class Source(val name: String, val url: String)

/** One entry of hazards.json: what the detector looks for and what the parent is told to do. */
data class Hazard(
    val id: String,
    val query: String,
    val category: String,
    val critical: Boolean,
    val threshold: Float,
    val label: String,
    val fix: String,
    val source: Source,
    val inputIds: IntArray,
    val attentionMask: IntArray,
    /** The query's embedding from OWL-ViT's text half, unit length; scored against each image patch. */
    val embed: FloatArray = FloatArray(0),
) {
    /**
     * How soon to fix it. A critical hazard that can kill in seconds (a socket, a
     * fall, poison, water, a swallowed battery, a cord) is urgent; a critical burn
     * or blade is high. Of the rest, things that tip over or are swallowed are
     * high -- furniture tip-overs kill children every year -- and the others
     * (a plant, a loose cable, a corner) are medium.
     */
    val severity: Severity
        get() = if (critical) when (category) {
            "electric", "fall", "poison", "water", "choking", "strangle" -> Severity.URGENT
            else -> Severity.HIGH
        } else when (category) {
            "tip-over", "choking" -> Severity.HIGH
            else -> Severity.MEDIUM
        }

    companion object {
        fun parseAll(json: String): List<Hazard> {
            val a = JSONObject(json).getJSONArray("hazards")
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val src = o.getJSONObject("source")
                Hazard(
                    id = o.getString("id"), query = o.getString("query"), category = o.getString("category"),
                    critical = o.getBoolean("critical"), threshold = o.getDouble("threshold").toFloat(),
                    label = o.getString("label"), fix = o.getString("fix"),
                    source = Source(src.getString("name"), src.getString("url")),
                    inputIds = o.getJSONArray("input_ids").let { ids -> IntArray(ids.length()) { ids.getInt(it) } },
                    attentionMask = o.getJSONArray("attention_mask").let { m -> IntArray(m.length()) { m.getInt(it) } },
                    embed = o.optJSONArray("embed")?.let { e -> FloatArray(e.length()) { e.getDouble(it).toFloat() } } ?: FloatArray(0),
                )
            }
        }
    }
}

/** A detector hit, in whole-photo coordinates. */
data class Candidate(val hazard: Hazard, val box: Box, val score: Float, val tile: String) {
    /** Score relative to this query's own threshold, so queries can be ranked against each other. */
    val strength get() = score / hazard.threshold
}

/** The vision model's four-line answer about one crop. */
data class CropAnswer(
    val obj: String,
    val hazard: String,
    val reachable: Boolean?,
    val protected: Boolean?,
    val raw: String,
    /** Whether the model agrees the object is what the detector said; null for the older free-hazard form. */
    val match: Boolean? = null,
)

/** One thing the room-wide look named. */
data class SceneItem(val thing: String, val hazard: String, val where: String)

data class Finding(
    val hazard: Hazard,
    val status: Status,
    val box: Box?,
    val score: Float,
    val why: String,
    val answer: CropAnswer? = null,
) {
    val severity get() = hazard.severity

    fun json() = JSONObject()
        .put("id", hazard.id).put("label", hazard.label).put("category", hazard.category)
        .put("status", status.name).put("severity", severity.name).put("score", score.r3())
        .put("box", box?.json() ?: JSONObject.NULL).put("why", why)
        .put("vlm", answer?.raw ?: JSONObject.NULL)
}

/** One vision-model look at a detector hit, kept raw so the rules can be replayed offline. */
data class Look(val candidate: Candidate, val raw: String?)

data class Scan(
    val findings: List<Finding>,
    val candidates: List<Candidate>,
    val scene: List<SceneItem>,
    val timings: Map<String, Long>,
    val notes: List<String>,
    val looks: List<Look> = emptyList(),
    val sceneRaw: String? = null,
) {
    val toFix get() = findings.filter { it.status != Status.PROTECTED }
        .sortedWith(compareBy({ it.status.ordinal }, { it.severity.rank }, { !it.hazard.critical }, { -it.score / it.hazard.threshold }))
    val done get() = findings.filter { it.status == Status.PROTECTED }

    fun json() = JSONObject()
        .put("findings", JSONArray(findings.map { it.json() }))
        .put("candidates", JSONArray(candidates.map {
            JSONObject().put("id", it.hazard.id).put("score", it.score.r3()).put("box", it.box.json()).put("tile", it.tile)
        }))
        .put("scene", JSONArray(scene.map { JSONObject().put("thing", it.thing).put("hazard", it.hazard).put("where", it.where) }))
        .put("looks", JSONArray(looks.map {
            JSONObject().put("id", it.candidate.hazard.id).put("score", it.candidate.score.r3())
                .put("box", it.candidate.box.json()).put("raw", it.raw ?: JSONObject.NULL)
        }))
        .put("scene_raw", sceneRaw ?: JSONObject.NULL)
        .put("ms", JSONObject(timings))
        .put("notes", JSONArray(notes))
}
