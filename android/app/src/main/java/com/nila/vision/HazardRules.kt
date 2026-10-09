package com.nila.vision

/**
 * What the vision model is allowed to call dangerous, and what it is not.
 *
 * The scene model answers a form, and one of its lines is the hazard it thinks
 * it can see. That line cannot be shown to a parent as it stands. A 2B model
 * asked whether a baby is in danger will say yes for a baby on a beach ("the
 * sand is soft and could cause him to fall") and yes for a baby holding a
 * teething ring ("a small object near the mouth, a choking hazard"), and a
 * monitor that cries wolf twice gets switched off before the night it is right.
 *
 * So the verdict is not the model's. The model supplies a *named hazard*, and
 * the rules here decide what that name is worth:
 *
 *  - a hazard the model itself negates ("none", "no immediate danger") is not a hazard
 *  - water with an adult holding the baby is a bath, not a drowning
 *  - a "small object" in a sentence that also says toy, biscuit or spoon is a toy or a meal
 *  - a hazard outside the list, where the model still says DANGER, is kept as OTHER:
 *    a missed danger costs more than a false one, and the parent is told it was
 *    unrecognised
 *  - SAFE is overridden only for the five that are never benign to touch
 *
 * Measured on an eighteen-photo set during the experiments this came from:
 * the model's own verdict was right 14 times, and these rules took that to 17.
 * The remaining miss was a baby climbing onto a sink that the model described as
 * looking in a mirror -- a rule cannot rescue a hazard that was never named.
 *
 * Pure Kotlin, no Android, so every rule above is a JVM test.
 */
object HazardRules {

    /** The families of hazard the rules recognise, in the order they are matched. */
    enum class Kind(val label: String) {
        STAIRS("stairs or steps"),
        ELECTRICAL("something electrical"),
        HEAT("something hot"),
        SHARP("something sharp"),
        POISON("medicine or chemicals"),
        FALL("a high edge"),
        WATER("water"),
        CHOKING("something small near the mouth"),
        OTHER("something the model could not name"),
    }

    enum class Level {
        /** Nothing to raise. */
        SAFE,

        /** Something was named but the rules do not trust it enough to wake anybody. */
        UNSURE,

        /** Worth telling the parent about now. */
        DANGER,
    }

    /**
     * @param level what to do about it.
     * @param kind the family, when there is one.
     * @param hazard the model's own words, kept so the parent reads what it saw.
     * @param note why the rules landed here, for the log and the technical record.
     */
    data class Verdict(
        val level: Level,
        val kind: Kind?,
        val hazard: String,
        val note: String = "",
    ) {
        val isDanger: Boolean get() = level == Level.DANGER

        /** One line for a notification. Never the model's raw sentence alone. */
        val headline: String get() = when (level) {
            Level.DANGER -> "Your baby is near ${kind?.label ?: "something unsafe"}"
            Level.UNSURE -> "Something worth a look"
            Level.SAFE -> "Nothing unsafe in view"
        }
    }

    /** "none", and the ways a model says it without saying it. */
    private val NEGATED = Regex(
        """^\W*(none|no|nothing|n/?a|not visible|unclear)\b""" +
            """|no (immediate |visible |obvious |real )?(danger|hazard|risk)""" +
            """|not (dangerous|a hazard|in danger|unsafe)""" +
            """|nothing (dangerous|unsafe|of concern)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Matched in order: the first family that matches the text names the hazard.
     *
     * Order matters where a word belongs to two families. A sink is a fall risk
     * when a baby is climbing it and a water risk when it is full, and FALL is
     * listed first because the climbing is what the camera can actually see.
     */
    private val FAMILIES: List<Pair<Kind, Regex>> = listOf(
        Kind.STAIRS to """stairs?|staircase|stairway|steps?|ladder""",
        Kind.ELECTRICAL to """cables?|cords?|wires?|plugs?|sockets?|outlets?|chargers?|power ?strips?|""" +
            """extension|electric\w*|tv|television|computer|laptop|monitor|appliances?|microwave|heater|fan""",
        Kind.HEAT to """stove|oven|hob|fire|flames?|candles?|iron|kettle|boiling|hot (drink|water|cup|tea|coffee)""",
        Kind.SHARP to """knife|knives|scissors|blades?|razor|sharp|broken glass|needles?|nails?""",
        Kind.POISON to """medicine|medication|pills?|tablets?|chemicals?|detergent|bleach|cleaning|cleaner|""" +
            """batter(y|ies)|plastic bags?""",
        Kind.FALL to """edge|climb\w*|window|balcony|railing|counter|countertop|shelf|shelves|""" +
            """table|bed|sofa|couch|sink|worktop""",
        Kind.WATER to """pool|bucket|toilet|bath ?tub|tub|water|pond|basin""",
        Kind.CHOKING to """small (object|item|toy|thing)s?|coins?|buttons?|marbles?|beads?|chok\w*|magnets?""",
    ).map { (kind, words) -> kind to Regex("""\b($words)\b""", RegexOption.IGNORE_CASE) }

    /** The five that are never fine for a baby to be touching, whatever the model concluded. */
    private val NEVER_BENIGN = setOf(Kind.STAIRS, Kind.ELECTRICAL, Kind.HEAT, Kind.SHARP, Kind.POISON)

    private val ADULT = Regex(
        """\b(adult|parent|mother|mum|mom|father|dad|caregiver|carer|person|someone|woman|man|hand)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SUPERVISING = Regex(
        """\b(hold\w*|support\w*|bath\w*|wash\w*|help\w*|supervis\w*|carry\w*)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val FOOD_OR_TOY = Regex(
        """\b(toys?|rattle|teether|teething|dummy|pacifier|soother|food|eat\w*|snack|biscuit|cookie|""" +
            """cracker|fruit|spoon|bottle|cup)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Turn what the model said into what the app will do.
     *
     * @param hazardLine the model's HAZARD field, verbatim.
     * @param context the rest of what it said -- the activity sentence -- which is
     *   where "being held by an adult" and "playing with a toy" turn up, and is
     *   what separates a bath from a drowning.
     * @param modelSaidDanger the model's own verdict, used as the tie-breaker when
     *   the named hazard is outside the list.
     */
    fun assess(
        hazardLine: String,
        context: String = "",
        modelSaidDanger: Boolean = false,
    ): Verdict {
        val hazard = hazardLine.trim()
        val negated = hazard.isBlank() || NEGATED.containsMatchIn(hazard)
        val whole = "$context $hazard"
        val kind = if (negated) null else FAMILIES.firstOrNull { it.second.containsMatchIn(whole) }?.first
        val supervised = ADULT.containsMatchIn(whole) && SUPERVISING.containsMatchIn(whole)

        if (negated) {
            return if (modelSaidDanger) {
                Verdict(Level.UNSURE, null, hazard, "the model said danger but named nothing")
            } else {
                Verdict(Level.SAFE, null, hazard)
            }
        }

        if (kind == Kind.WATER && supervised) {
            return Verdict(Level.SAFE, Kind.WATER, hazard, "water, but an adult is holding the baby")
        }
        if (kind == Kind.CHOKING && FOOD_OR_TOY.containsMatchIn(whole)) {
            return Verdict(Level.UNSURE, Kind.CHOKING, hazard, "the small object reads as food or a toy")
        }

        return when {
            modelSaidDanger && kind == null ->
                Verdict(Level.DANGER, Kind.OTHER, hazard, "hazard not on the list, trusting the model")
            modelSaidDanger ->
                Verdict(Level.DANGER, kind, hazard)
            kind in NEVER_BENIGN ->
                Verdict(Level.DANGER, kind, hazard, "the model said safe, but named something never safe to touch")
            else ->
                Verdict(Level.SAFE, kind, hazard)
        }
    }

    /**
     * Two looks before anybody is woken.
     *
     * The camera lane already works this way for posture -- a run of frames
     * rather than one -- and the reason is the same here: one look at one frame
     * of a dim nursery is exactly the evidence a 2B model is worst at. The
     * window is short because looks are minutes apart, not frames apart.
     */
    class Confirmer(
        private val needed: Int = 2,
        private val window: Int = 3,
    ) {
        private val recent = ArrayDeque<Boolean>()

        /** @return true when the run is long enough to act on. */
        @Synchronized
        fun add(verdict: Verdict): Boolean {
            recent.addLast(verdict.isDanger)
            while (recent.size > window) recent.removeFirst()
            return recent.count { it } >= needed
        }

        @Synchronized
        fun clear() = recent.clear()
    }
}
