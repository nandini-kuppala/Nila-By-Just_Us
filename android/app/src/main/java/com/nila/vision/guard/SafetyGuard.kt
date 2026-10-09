package com.nila.vision.guard

enum class Verdict { SAFE, DANGER, UNSURE, NO_BABY, UNKNOWN }

/**
 * Prompt + guard rails that turn one vision-model answer into a decision. Ported unchanged from Baby
 * Guard CCTV (17 of 18 real photos right, 0 false alarms).
 *
 * The model fills four lines (BABY / ACTIVITY / HAZARD / SAFETY). Naming the hazard before the
 * verdict makes it commit to what it saw, and the guard rails below check the verdict against it:
 *  - no SAFETY line                            -> UNKNOWN (never alarms)
 *  - BABY: no                                  -> NO_BABY
 *  - DANGER but HAZARD says none / no danger   -> UNSURE  ("no immediate danger" contradictions)
 *  - DANGER for water with an adult holding    -> SAFE
 *  - DANGER for choking on food or a toy       -> UNSURE
 *  - DANGER with a hazard not on the list      -> DANGER "other" (a missed danger costs more)
 *  - SAFE but HAZARD/ACTIVITY names stairs, electrical, heat, sharp or poison -> DANGER
 */
object SafetyGuard {
    const val SYSTEM_PROMPT =
        "You are a baby safety monitor looking at one photo from a home camera. " +
            "Report only what is clearly visible in the photo. Do not guess."

    const val USER_PROMPT =
        "Fill in exactly these four lines and nothing else:\n" +
            "BABY: yes or no\n" +
            "ACTIVITY: what the baby is doing, in one short sentence\n" +
            "HAZARD: the dangerous thing the baby is touching, holding, climbing or right next to, or none\n" +
            "SAFETY: SAFE or DANGER\n" +
            "\n" +
            "Say DANGER only if the baby is touching, climbing or right next to one of these:\n" +
            "- stairs or steps\n" +
            "- a high edge: bed, sofa, table, counter, sink, shelf or window\n" +
            "- an electrical cable, cord, plug or socket, a TV, computer or other appliance\n" +
            "- a hot stove, oven, iron, fire or hot drink\n" +
            "- a knife, scissors, glass or other sharp object\n" +
            "- medicine, pills, cleaning products, batteries or a plastic bag\n" +
            "- open water: pool, bucket, toilet, or a bathtub with no adult holding the baby\n" +
            "- a small object that is not food, in or near the mouth\n" +
            "Say SAFE for everything else, for example playing with baby toys, eating food, sleeping, " +
            "crawling or sitting on the floor, grass or sand, or being held or bathed by an adult."

    data class Assessment(
        val verdict: Verdict,
        val rawSafety: String,
        val baby: String,
        val activity: String,
        val hazard: String,
        val category: String,
        val note: String,
    ) {
        fun reason(): String = listOf(category, hazard).filter { it.isNotBlank() }.joinToString(": ")
    }

    /** The alarm trigger: last "SAFETY: SAFE|DANGER" in the answer (tolerates markdown bold). */
    private val SAFETY = Regex("""SAFETY\s*\**\s*:\s*\**\s*(SAFE|DANGER)\b""", RegexOption.IGNORE_CASE)

    private val NONE =
        Regex(
            """^\W*(none|no|nothing|n/?a|not visible)\b|no (immediate |visible |obvious |real )?(danger|hazard|risk)|not (dangerous|a hazard|in danger)|nothing dangerous""",
            RegexOption.IGNORE_CASE,
        )

    // Order matters: the first matching category names the hazard.
    private val CATEGORIES =
        listOf(
            "stairs" to """stairs?|staircase|stairway|steps?|ladder""",
            "electrical" to """cables?|cords?|wires?|plugs?|sockets?|outlets?|chargers?|power ?strips?|extension|electric\w*|tv|television|computer|laptop|appliances?|microwave|heater""",
            "heat" to """stove|oven|hob|fire|flames?|candles?|iron|hot|kettle|boiling""",
            "sharp" to """knife|knives|scissors|blades?|razor|sharp|glass|needles?""",
            "poison" to """medicine|medication|pills?|tablets?|chemicals?|detergent|bleach|cleaning|cleaner|batter(y|ies)|plastic bags?""",
            "fall" to """edge|climb\w*|window|balcony|railing|counter|countertop|shelf|shelves|table|bed|sofa|couch|sink""",
            "water" to """pool|bucket|toilet|bath ?tub|tub|water|pond""",
            "choking" to """small (object|item|toy|thing)s?|coins?|buttons?|marbles?|beads?|chok\w*|magnets?""",
        ).map { (name, words) -> name to Regex("""\b($words)\b""", RegexOption.IGNORE_CASE) }

    /** A SAFE verdict is overridden only for hazards that are never fine to touch. */
    private val OVERRIDE_SAFE = setOf("stairs", "electrical", "heat", "sharp", "poison")

    private val ADULT = Regex("""\b(adult|parent|mother|mom|father|dad|caregiver|person|someone|woman|man)\b""", RegexOption.IGNORE_CASE)
    private val HOLDING = Regex("""\b(hold\w*|support\w*|bath\w*|wash\w*|help\w*|supervis\w*)\b""", RegexOption.IGNORE_CASE)
    private val FOOD_OR_TOY =
        Regex("""\b(toys?|food|eat\w*|snack|biscuit|cookie|cracker|fruit|spoon|bottle|teether|pacifier|dummy)\b""", RegexOption.IGNORE_CASE)

    private fun field(text: String, name: String): String =
        Regex("""^[\s*#-]*$name[\s*]*:[\s*]*(.*)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
            .findAll(text)
            .lastOrNull()
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.trim('*')
            ?.trim()
            .orEmpty()

    fun assess(answer: String): Assessment {
        val baby = field(answer, "BABY")
        val activity = field(answer, "ACTIVITY")
        val hazard = field(answer, "HAZARD")
        val match = SAFETY.findAll(answer).lastOrNull()
        val raw = match?.groupValues?.get(1)?.uppercase() ?: "NONE"

        fun result(v: Verdict, category: String = "", note: String = "") =
            Assessment(v, raw, baby, activity, hazard, category, note)

        if (match == null) return result(Verdict.UNKNOWN, note = "no SAFETY line in the answer")
        if (baby.lowercase().startsWith("no")) return result(Verdict.NO_BABY, note = "model reports no baby")

        val negated = NONE.containsMatchIn(hazard)
        val context = "$activity $hazard"
        // The model sometimes puts the hazard only in ACTIVITY (or writes "HAZARD: Yes"), so look at both.
        val category = if (negated) null else CATEGORIES.firstOrNull { it.second.containsMatchIn(context) }?.first
        val supervised = ADULT.containsMatchIn(context) && HOLDING.containsMatchIn(context)

        return if (raw == "DANGER") {
            when {
                negated -> result(Verdict.UNSURE, note = "DANGER but the hazard line says there is none")
                category == "water" && supervised -> result(Verdict.SAFE, category, "water, but an adult is holding the baby")
                category == "choking" && FOOD_OR_TOY.containsMatchIn(context) ->
                    result(Verdict.UNSURE, category, "small object looks like food or a toy")
                category == null -> result(Verdict.DANGER, "other", "hazard not on the list, trusting the model")
                else -> result(Verdict.DANGER, category)
            }
        } else {
            if (category != null && category in OVERRIDE_SAFE) {
                result(Verdict.DANGER, category, "SAFE overridden: hazard named")
            } else {
                result(Verdict.SAFE, category.orEmpty())
            }
        }
    }
}
