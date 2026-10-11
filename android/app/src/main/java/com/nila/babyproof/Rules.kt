package com.nila.babyproof

/**
 * Plain code that turns detector hits and the model's answers into the list a
 * parent sees. Everything here is deterministic and unit-tested; the models
 * only see, the rules decide.
 *
 *  - The detector proposes; the vision model confirms or rejects each proposal.
 *  - "Already protected" (a socket with a cover, stairs with a gate) moves an
 *    item to the done list instead of the to-fix list.
 *  - A **critical** hazard (sockets, stairs, windows, medicines, water...) is
 *    never silently dropped because the model shrugged: a strong detector hit
 *    the model rejects becomes "Check this".
 *  - Out-of-reach only removes items whose danger is touching them (choking,
 *    poison, sharp); a tall shelf is a danger because it can be pulled over.
 *  - Things the room-wide look names that the word list missed are added
 *    without a box, as "Check this".
 */
object Rules {

    /** A rejected critical hit stays (as "Check this") only if the detector was this sure. */
    const val CRITICAL_KEEP = 2.0f

    /** Without the vision model, only hits this far over their threshold are shown. */
    const val DETECTOR_ONLY_KEEP = 1.5f

    private val REACH_MATTERS = setOf("choking", "poison", "sharp")

    fun decide(candidate: Candidate, answer: CropAnswer?, catalogue: Map<String, Hazard> = emptyMap()): Finding? {
        if (answer == null) {
            // No model answer (not installed, or failed): the detector alone, cautiously.
            if (candidate.strength < DETECTOR_ONLY_KEEP) return null
            return Finding(candidate.hazard, Status.CHECK, candidate.box, candidate.score, "Detector only (${"%.2f".format(candidate.score)})")
        }
        val relabelled = relabel(candidate.hazard, answer, catalogue)
        // The model agrees it is the thing (or names a sibling in the same family).
        // A 2B model says "yes" too easily: a yes that names a bed, a sofa or a
        // frying pan -- and nothing in the catalogue -- is not believed.
        val agreed = when (answer.match) {
            true -> !namesSomethingHarmless(answer.obj, catalogue)
            false -> relabelled != candidate.hazard
            null -> answer.hazard != "none" // the older free-hazard form
        }
        val h = relabelled
        if (!agreed) {
            return if (h.critical && candidate.strength >= CRITICAL_KEEP) {
                Finding(h, Status.CHECK, candidate.box, candidate.score,
                    "The detector is sure, the model is not (it saw: ${answer.obj.ifBlank { "?" }})", answer)
            } else null
        }
        if (answer.protected == true) {
            return Finding(h, Status.PROTECTED, candidate.box, candidate.score, "Looks already protected: ${answer.obj}", answer)
        }
        if (answer.reachable == false && h.category in REACH_MATTERS) return null
        return Finding(h, Status.FIX, candidate.box, candidate.score, answer.obj.ifBlank { h.label }, answer)
    }

    /**
     * The detector's word can be the wrong one of a family -- an extension board
     * scored as "socket", a charger as "cable". The vision model names the object
     * in its OBJECT line; if that name maps to another catalogue entry *of the same
     * category*, the finding takes that entry (and its fix). A different category
     * is never adopted: the detector's category is what the model then confirms.
     */
    /** The OBJECT line names an everyday thing that is not a hazard, and no catalogue entry. */
    fun namesSomethingHarmless(obj: String, catalogue: Map<String, Hazard> = emptyMap()): Boolean {
        val o = obj.lowercase()
        if (o.isBlank() || SCENE_WORDS.any { (word, _) -> word in o }) return false
        return Regex("[a-z]+").findAll(o).any { it.value in HARMLESS }
    }

    /**
     * Everyday things the vision model names when the detector was wrong. Whole
     * words only ("fan" is not in "infant"). tools/replay.py reads this list too.
     */
    private val HARMLESS = setOf(
        "bed", "sofa", "couch", "chair", "fan", "desk", "pan", "bowl", "plate", "pot", "door", "pillow", "pillows",
        "cushion", "mirror", "painting", "picture", "rug", "carpet", "speaker", "clock", "dispenser", "container",
        "monitor", "fridge", "refrigerator", "table", "stool",
    )

    fun relabel(h: Hazard, answer: CropAnswer?, catalogue: Map<String, Hazard>): Hazard {
        val obj = answer?.obj?.lowercase()?.takeIf { it.isNotBlank() } ?: return h
        val named = SCENE_WORDS.firstOrNull { (word, _) -> word in obj }?.let { catalogue[it.second] } ?: return h
        return if (named.category == h.category) named else h
    }

    /** One finding per object: same hazard, overlapping boxes -> keep the stronger. */
    fun merge(findings: List<Finding>): List<Finding> {
        val out = mutableListOf<Finding>()
        for (f in findings.sortedWith(compareBy({ it.status.ordinal }, { -it.score }))) {
            val dup = out.any { o ->
                o.hazard.id == f.hazard.id && o.box != null && f.box != null && o.box.iou(f.box) > 0.3f
            }
            if (!dup) out += f
        }
        return out
    }

    /**
     * Words the models use, mapped to a catalogue entry. Order matters: most specific first.
     * tools/replay.py reads this list from here, so keep it one pair per `"word" to "id"`.
     */
    private val SCENE_WORDS = listOf(
        "extension" to "power_strip", "power strip" to "power_strip", "multi plug" to "power_strip", "plug board" to "power_strip",
        "socket" to "socket", "outlet" to "socket", "plug point" to "socket", "switch" to "switchboard",
        "charger" to "charger", "adapter" to "charger", "cable" to "cable", "wire" to "cable", "cord" to "cable",
        "stair" to "stairs", "step" to "stairs", "window" to "window", "balcon" to "balcony", "railing" to "balcony",
        "shelf" to "bookshelf", "bookcase" to "bookshelf", "cupboard" to "dresser", "drawer" to "dresser",
        "wardrobe" to "dresser", "tv" to "tv", "television" to "tv", "lamp" to "lamp",
        // Not a bare "table": the model names every table, and only its corners are the hazard.
        "corner" to "table_corner", "edge" to "table_corner",
        "knife" to "knife", "scissor" to "scissors", "medicine" to "medicine", "pills" to "medicine", "tablet" to "medicine",
        "clean" to "cleaner", "detergent" to "cleaner", "chemical" to "cleaner", "coin" to "coins", "batter" to "battery",
        "plastic bag" to "plastic_bag", "bag" to "plastic_bag", "toy" to "small_toys", "stove" to "stove", "burner" to "stove",
        "cylinder" to "cylinder", "gas" to "cylinder", "iron" to "iron", "kettle" to "kettle", "heater" to "heater",
        "radiator" to "heater", "candle" to "candle", "diya" to "candle", "flame" to "candle", "bucket" to "bucket",
        "toilet" to "toilet", "tub" to "tub", "blind" to "blind_cord", "plant" to "plant",
    )

    fun sceneHazard(item: SceneItem, catalogue: Map<String, Hazard>): Hazard? {
        val t = item.thing.lowercase()
        return SCENE_WORDS.firstOrNull { (word, _) -> word in t }?.let { catalogue[it.second] }
    }

    /** Add what only the room-wide look named, as "Check this", for hazards not already on the list. */
    fun addScene(findings: List<Finding>, scene: List<SceneItem>, catalogue: Map<String, Hazard>): List<Finding> {
        val have = findings.map { it.hazard.id }.toMutableSet()
        val extra = scene.mapNotNull { item ->
            val h = sceneHazard(item, catalogue) ?: return@mapNotNull null
            if (h.id in have) return@mapNotNull null
            have += h.id
            Finding(h, Status.CHECK, null, 0f, "Named by the room-wide look: ${item.thing}" +
                if (item.where.isNotBlank()) " (${item.where})" else "")
        }
        return findings + extra
    }
}
