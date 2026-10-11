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

    // "skin-coloured" first: it contains the letters "red".
    private val COLOURS = listOf("skin-coloured", "skin coloured", "skin colored", "skin-colored", "dark red", "pink", "red", "purple", "brown", "yellow", "white")
    private val PATTERNS = listOf("single spot", "few spots", "many small spots", "patch", "ring", "none")

    fun parseSkin(raw: String): SkinDescription {
        val lines = raw.lines().map { it.trim() }
        fun field(key: String) = lines.firstOrNull { it.uppercase().startsWith(key) }?.substringAfter(':', "")?.trim()?.lowercase()
        val colour = field("COLOUR") ?: field("COLOR")
        val surface = field("SURFACE") ?: ""
        val pattern = field("PATTERN")
        val photo = field("PHOTO") ?: ""
        return SkinDescription(
            colour = colour?.let { c -> COLOURS.firstOrNull { c.contains(it) }?.let { if (it.startsWith("skin")) "skin-coloured" else it } },
            surfaces = Surface.entries.filter { s -> s.keys.any { surface.contains(it) } }.toSet(),
            pattern = pattern?.let { p -> PATTERNS.firstOrNull { p.contains(it) } },
            photoOk = !photo.contains("blur") && !photo.contains("not skin"),
            notSkin = photo.contains("not skin"),
            raw = raw,
        )
    }

    // ---- plate --------------------------------------------------------------

    /**
     * Short and open. A closed list of 30 dishes made the 2B model ramble
     * ("what is the main point, it seems like this is a question..."), and the
     * plain open question named idli as "bread". Short prompts with three
     * examples work; the food table's aliases then map how a small model
     * describes Indian dishes ("rice cakes", "lentil soup") to the dish.
     */
    val PLATE_PROMPT = "List the foods you can see in this photo. Reply with only food names separated by commas, for example: rice, dal, banana."

    /**
     * The second, differently worded look used to cross-check the first list
     * (self-consistency): a food named both times is more likely really there.
     */
    val SECOND_PROMPT = "What baby food or foods are in this photo? Reply with only the food names, separated by commas."

    /** Every name the app shows as a choice when the AI is unavailable. */
    val PLATE_CHOICES = listOf("rice", "dal", "sambar", "roti", "idli", "dosa", "upma", "poha", "khichdi", "curd rice", "curd",
        "ragi porridge", "oats porridge", "banana", "apple", "papaya", "mango", "egg", "chicken", "fish",
        "carrot", "potato", "sweet potato", "spinach", "peas", "pumpkin", "paneer", "bread", "milk")

    data class PlateGuess(val text: String, val food: Food?)

    fun parsePlate(raw: String, db: FoodDb): List<PlateGuess> {
        val names = raw.lines()
            .flatMap { it.split(',', ';') }
            .map { it.trim().trimStart('-', '*', '•', ' ').replace(Regex("^\\d+[.)]\\s*"), "").trim() }
            .filter { it.isNotBlank() && it.length < 40 }
            .distinctBy { it.lowercase() }
        val seen = HashSet<String>()
        val guesses = names.map { PlateGuess(it, db.match(it)) }
        // Drop fragments of other answers ("pane" next to "paneer") and junk.
        return guesses
            .filter { g -> g.food != null || (g.text.length >= 4 && g.text.split(' ').size <= 3 && !looksLikeChatter(g.text) &&
                guesses.none { o -> o !== g && o.text.length > g.text.length && o.text.contains(g.text, ignoreCase = true) }) }
            .filter { g -> g.food == null || seen.add(g.food.id) }
            .take(10)
    }

    /**
     * The double-check. Small vision models name foods that are not there
     * (object hallucination), but are noticeably better at a yes/no question
     * about one named object. Each food from the list is asked about on its
     * own, and dropped when the model says no.
     */
    fun verifyPrompt(food: String) = "Is there $food in this photo? Answer only yes or no."

    private val YES = Regex("\\byes\\b")
    private val NO = Regex("\\b(no|not)\\b")

    /** Yes, no, or null when the reply is neither. */
    fun parseYesNo(raw: String): Boolean? {
        val t = raw.trim().lowercase()
        return when {
            t.startsWith("yes") -> true
            t.startsWith("no") -> false
            YES.containsMatchIn(t) && !NO.containsMatchIn(t) -> true
            NO.containsMatchIn(t) && !YES.containsMatchIn(t) -> false
            else -> null
        }
    }

    private val CHATTER = Regex("\\b(i|you|it|is|this|that|question|point|answer|image|photo|picture|see|also|but|and|the)\\b", RegexOption.IGNORE_CASE)
    private fun looksLikeChatter(t: String) = CHATTER.containsMatchIn(t)

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
