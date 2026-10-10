package com.nila.skin.core.vlm

import com.nila.skin.core.food.Food
import com.nila.skin.core.food.FoodDb

/**
 * What the on-device vision model is asked, and how its answers are read.
 *
 * The model (Intern3.5-VL-2B on the Hexagon NPU) is only ever asked to
 * **describe** -- colours, textures, which foods are on a plate, what kind of
 * photo this is. It is never asked what condition a baby has, and nothing it
 * says is shown without the parent confirming it. Closed vocabularies keep a
 * 2B model's answers checkable, and temperature zero keeps them repeatable.
 */
object VisionForms {

    const val SYSTEM = "You describe photos precisely and briefly. You never guess medical conditions."

    // ---- skin ---------------------------------------------------------------

    val SKIN_PROMPT = """
        This is a close-up photo of a baby's skin. Describe only what you can see.
        Answer with exactly these four lines:
        COLOUR: one of pink, red, dark red, purple, brown, yellow, white, skin-coloured
        SURFACE: any of flat, raised bumps, dry or scaly, blisters, crusts, weeping, smooth
        PATTERN: one of single spot, few spots, many small spots, patch, ring, none
        PHOTO: one of clear, blurry, not skin
    """.trimIndent()

    enum class Surface(val label: String, val keys: List<String>) {
        FLAT("Flat", listOf("flat")),
        RAISED("Raised bumps", listOf("raised", "bump", "papule")),
        DRY("Dry or scaly", listOf("dry", "scaly", "flaky", "rough")),
        BLISTERS("Blisters", listOf("blister", "vesicle", "fluid")),
        CRUSTS("Crusts", listOf("crust")),
        WEEPING("Weeping or oozing", listOf("weep", "ooz", "wet")),
        SMOOTH("Smooth", listOf("smooth")),
    }

    data class SkinDescription(
        val colour: String?,
        val surfaces: Set<Surface>,
        val pattern: String?,
        val photoOk: Boolean,
        val notSkin: Boolean,
        val raw: String,
    ) {
        /** Surfaces that map to a checklist question, so the parent is asked to confirm. */
        val suggestsBlistersOrCrusts: Boolean get() = surfaces.any { it == Surface.BLISTERS || it == Surface.CRUSTS || it == Surface.WEEPING }
    }

    private val COLOURS = listOf("dark red", "pink", "red", "purple", "brown", "yellow", "white", "skin-coloured", "skin coloured")
    private val PATTERNS = listOf("single spot", "few spots", "many small spots", "patch", "ring", "none")

    fun parseSkin(raw: String): SkinDescription {
        val lines = raw.lines().map { it.trim() }
        fun field(key: String) = lines.firstOrNull { it.uppercase().startsWith(key) }?.substringAfter(':', "")?.trim()?.lowercase()
        val colour = field("COLOUR") ?: field("COLOR")
        val surface = field("SURFACE") ?: ""
        val pattern = field("PATTERN")
        val photo = field("PHOTO") ?: ""
        return SkinDescription(
            colour = colour?.let { c -> COLOURS.firstOrNull { c.contains(it) }?.replace("skin coloured", "skin-coloured") },
            surfaces = Surface.entries.filter { s -> s.keys.any { surface.contains(it) } }.toSet(),
            pattern = pattern?.let { p -> PATTERNS.firstOrNull { p.contains(it) } },
            photoOk = !photo.contains("blur") && !photo.contains("not skin"),
            notSkin = photo.contains("not skin"),
            raw = raw,
        )
    }

    // ---- plate --------------------------------------------------------------

    val PLATE_PROMPT = """
        This is a photo of a baby's or toddler's meal. List every food you can see,
        one per line, using simple everyday names such as rice, dal, roti, idli,
        khichdi, curd, banana, apple, carrot, egg, chicken, fish, spinach, ragi.
        Write only the food names, nothing else.
    """.trimIndent()

    data class PlateGuess(val text: String, val food: Food?)

    fun parsePlate(raw: String, db: FoodDb): List<PlateGuess> {
        val names = raw.lines()
            .flatMap { it.split(',', ';') }
            .map { it.trim().trimStart('-', '*', '•', ' ').replace(Regex("^\\d+[.)]\\s*"), "").trim() }
            .filter { it.isNotBlank() && it.length < 40 }
            .distinctBy { it.lowercase() }
        val seen = HashSet<String>()
        return names.map { PlateGuess(it, db.match(it)) }
            .filter { g -> g.food == null || seen.add(g.food.id) }
            .take(10)
    }

    // ---- what kind of photo -------------------------------------------------

    val KIND_PROMPT = "What is the main subject of this photo? Answer with one word: SKIN, NAPPY, EYES, FOOD or OTHER."

    enum class Kind { SKIN, NAPPY, EYES, FOOD, OTHER }

    fun parseKind(raw: String): Kind {
        val t = raw.uppercase()
        return when {
            "NAPPY" in t || "DIAPER" in t || "POO" in t || "STOOL" in t -> Kind.NAPPY
            "EYE" in t || "FACE" in t -> Kind.EYES
            "FOOD" in t || "PLATE" in t || "MEAL" in t -> Kind.FOOD
            "SKIN" in t -> Kind.SKIN
            else -> Kind.OTHER
        }
    }
}
