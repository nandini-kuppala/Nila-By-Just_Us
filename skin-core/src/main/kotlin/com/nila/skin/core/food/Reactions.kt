package com.nila.skin.core.food

import com.nila.skin.core.rules.Advice
import com.nila.skin.core.rules.Answer
import com.nila.skin.core.rules.Emergency
import com.nila.skin.core.rules.Question
import com.nila.skin.core.rules.Reason
import com.nila.skin.core.rules.Source
import com.nila.skin.core.rules.Step

/** A food given at a time. */
data class Exposure(val foodId: String, val timeMs: Long, val firstTime: Boolean)

/** What the parent saw, and when. */
data class ReactionEvent(val timeMs: Long, val signs: Set<Sign>)

enum class Sign(val label: String, val delayed: Boolean = false, val severe: Boolean = false) {
    HIVES("Raised itchy bumps (hives) or a rash around the mouth"),
    REDNESS("Red skin or flushing"),
    VOMITING("Vomiting"),
    ECZEMA_FLARE("Eczema flare (dry, red, itchy patches)", delayed = true),
    LOOSE_STOOL("Diarrhoea or mucus in poo", delayed = true),
    SWELLING("Swelling of the lips, face, tongue or eyes", severe = true),
    BREATHING("Coughing, wheeze, noisy or difficult breathing", severe = true),
    FLOPPY("Pale, floppy or suddenly very sleepy", severe = true),
}

/**
 * Which foods keep turning up before reactions?
 *
 * Exposure-window association, kept simple enough to explain out loud:
 * a reaction "follows" a food if it starts within 2 hours (immediate signs) or
 * 48 hours (eczema flares, loose stools). For each food we count exposures and
 * how many were followed by a reaction, smooth the rate so one event cannot
 * look like certainty, and compare it with the rate after every other food.
 * Two or more followed exposures, at least half of all the times it was given,
 * and at least twice the background rate is a pattern worth showing a doctor.
 * The "half" rule stops an everyday food (khichdi at every lunch) from looking
 * guilty just because it was on the table when the real culprit was given.
 * A one-sided Fisher exact test on the 2x2 table (this food vs every other
 * food, followed vs not) then asks how often chance alone would put this many
 * reactions after it; a pattern also needs that to be 5% or less, and the same
 * shape with a bigger chance is shown as "possible: keep logging". It is never
 * a diagnosis of allergy, which needs a doctor and usually a test.
 */
object ReactionPatterns {

    const val IMMEDIATE_MS = 2 * 3_600_000L
    const val DELAYED_MS = 48 * 3_600_000L

    data class Pattern(
        val foodId: String,
        val exposures: Int,
        val followed: Int,
        val rate: Double,
        val background: Double,
        val lift: Double,
        val strength: Strength,
        /** Exposures where another food was also new in the same window. */
        val confounded: Int,
        /**
         * One-sided Fisher exact p: if this food made no difference, the chance
         * that at least this many of the reactions would land after it anyway.
         */
        val chance: Double = 1.0,
    ) {
        /** PATTERN: unlikely to be chance. POSSIBLE: the same shape, but too few logs to rule chance out. */
        enum class Strength { PATTERN, POSSIBLE, ONE_OFF, NONE }
    }

    fun followedBy(e: Exposure, reactions: List<ReactionEvent>): Boolean = reactions.any { r ->
        val dt = r.timeMs - e.timeMs
        val window = if (r.signs.any { !it.delayed }) IMMEDIATE_MS else DELAYED_MS
        dt in 0..window
    }

    fun analyse(exposures: List<Exposure>, reactions: List<ReactionEvent>): List<Pattern> {
        if (exposures.isEmpty()) return emptyList()
        val byFood = exposures.groupBy { it.foodId }
        val followed = exposures.associateWith { followedBy(it, reactions) }
        return byFood.map { (food, list) ->
            val k = list.count { followed[it] == true }
            val n = list.size
            val others = exposures.filter { it.foodId != food }
            val bk = others.count { followed[it] == true }
            val rate = (k + 0.5) / (n + 1.0)
            val background = (bk + 0.5) / (others.size + 1.0)
            val lift = rate / background
            val confounded = list.count { e ->
                followed[e] == true && exposures.any { o -> o.foodId != food && o.firstTime && kotlin.math.abs(o.timeMs - e.timeMs) <= IMMEDIATE_MS }
            }
            val chance = fisherGreater(k, n, bk, others.size)
            val strength = when {
                k >= 2 && k * 2 >= n && lift >= 2.0 && chance <= 0.05 -> Pattern.Strength.PATTERN
                k >= 2 && k * 2 >= n && lift >= 2.0 -> Pattern.Strength.POSSIBLE
                k >= 1 -> Pattern.Strength.ONE_OFF
                else -> Pattern.Strength.NONE
            }
            Pattern(food, n, k, rate, background, lift, strength, confounded, chance)
        }.sortedWith(compareBy<Pattern> { it.strength.ordinal }.thenByDescending { it.followed }.thenByDescending { it.lift })
    }
}

/**
 * One-sided Fisher exact test, P(X >= k) for X ~ Hypergeometric: [k] of [n]
 * exposures to this food were followed by a reaction, and [bk] of [m] for every
 * other food.
 */
fun fisherGreater(k: Int, n: Int, bk: Int, m: Int): Double {
    val total = n + m
    val followed = k + bk
    if (n == 0 || followed == 0) return 1.0
    val lf = DoubleArray(total + 1)
    for (i in 1..total) lf[i] = lf[i - 1] + kotlin.math.ln(i.toDouble())
    fun lc(a: Int, b: Int) = if (b < 0 || b > a) Double.NEGATIVE_INFINITY else lf[a] - lf[b] - lf[a - b]
    val denom = lc(total, n)
    var p = 0.0
    for (x in k..minOf(n, followed)) p += kotlin.math.exp(lc(followed, x) + lc(total - followed, n - x) - denom)
    return p.coerceIn(0.0, 1.0)
}

/** "about 1 in 140" for a small chance, for parents and judges alike. */
fun oneIn(p: Double): String = when {
    p <= 0.0 -> "almost never"
    p >= 0.5 -> "about half the time"
    else -> "about 1 in ${roundNice(1.0 / p)}"
}

private fun roundNice(v: Double): Int = when {
    v < 20 -> kotlin.math.round(v).toInt()
    v < 200 -> (kotlin.math.round(v / 5) * 5).toInt()
    v < 2000 -> (kotlin.math.round(v / 50) * 50).toInt()
    else -> (kotlin.math.round(v / 500) * 500).toInt()
}

/**
 * Keeping allergens in the diet once they are in.
 *
 * Introducing an allergen early only protects if it keeps being eaten: ASCIA
 * advises giving it regularly, about twice a week, once introduced, and the
 * NIAID 2017 addendum suggests about 6-7 g of peanut protein a week over three
 * or more feeds. Any allergen in a food a reaction has followed is paused until
 * a doctor says otherwise.
 */
object AllergenUpkeep {

    data class Status(
        val allergen: Allergen,
        val firstMs: Long?,
        val lastMs: Long?,
        /** Times given in the last 7 days. */
        val lastWeek: Int,
        val paused: Boolean,
    ) {
        val tried: Boolean get() = firstMs != null
        fun daysSince(now: Long): Int? = lastMs?.let { ((now - it) / 86_400_000L).toInt() }

        fun note(now: Long): String? = when {
            !tried -> null
            paused -> "Paused after a reaction. Ask your doctor before giving it again."
            lastWeek >= 2 -> "Given $lastWeek times this week. Keep it going."
            (daysSince(now) ?: 0) >= 7 -> "Last given ${daysSince(now)} days ago. Try to keep it in the diet about twice a week."
            else -> "Given $lastWeek time${if (lastWeek == 1) "" else "s"} this week. About twice a week keeps it in the diet."
        }
    }

    data class Given(val allergens: Set<Allergen>, val timeMs: Long)

    fun of(given: List<Given>, pausedAllergens: Set<Allergen>, now: Long): List<Status> =
        Allergen.entries.map { a ->
            val times = given.filter { a in it.allergens }.map { it.timeMs }
            Status(a, times.minOrNull(), times.maxOrNull(), times.count { now - it in 0..7 * 86_400_000L }, a in pausedAllergens && times.isNotEmpty())
        }

    /** Grams of peanut protein in the last 7 days, against NIAID's 6-7 g a week. */
    const val PEANUT_PROTEIN_WEEK_G = 6.0
}

/**
 * Reaction checklist. Severe signs are the NHS / Resuscitation Council signs of
 * anaphylaxis in babies; the rest come from NHS weaning and allergy advice.
 */
object AllergyRules {

    fun advise(reaction: ReactionEvent, patterns: List<ReactionPatterns.Pattern>, foodName: (String) -> String): Advice {
        val r = ArrayList<Reason>()
        if (reaction.signs.any { it.severe })
            r += Reason("Swelling, breathing trouble or a pale, floppy baby after food can be a strong allergic reaction and needs a doctor straight away.", Source.ANAPHYLAXIS, Step.URGENT)
        if (Sign.HIVES in reaction.signs || Sign.VOMITING in reaction.signs)
            r += Reason("Hives or vomiting soon after a food can be an allergic reaction. Don't give that food again until a doctor has advised.", Source.NHS_WEANING, Step.DOCTOR_SOON)
        if (Sign.ECZEMA_FLARE in reaction.signs || Sign.LOOSE_STOOL in reaction.signs)
            r += Reason("Delayed signs like eczema flares or loose stools are worth tracking and mentioning to your doctor.", Source.NHS_WEANING, Step.RECHECK)
        patterns.filter { it.strength == ReactionPatterns.Pattern.Strength.PATTERN }.forEach {
            r += Reason("${foodName(it.foodId)}: ${it.followed} of ${it.exposures} times it was followed by a reaction. By chance alone, that would happen ${oneIn(it.chance)}.", null, Step.DOCTOR_SOON)
        }
        patterns.filter { it.strength == ReactionPatterns.Pattern.Strength.POSSIBLE }.forEach {
            r += Reason("${foodName(it.foodId)}: ${it.followed} of ${it.exposures} times followed by a reaction, but with so few logs that could still be chance (${oneIn(it.chance)}). Keep logging.", null, Step.RECHECK)
        }
        return Advice.of(r, Step.WATCH, ::doNow, listOf(
            "Whether this could be a food allergy, and whether a test is needed.",
            "How and when to try the food again, if at all.",
        ))
    }

    fun doNow(step: Step): List<String> = when (step) {
        Step.URGENT -> listOf(Emergency.LINE, "If your baby has a prescribed adrenaline pen, use it now.", "Lie your baby flat (or sitting up if breathing is hard); don't let them stand or walk.")
        Step.DOCTOR_TODAY, Step.DOCTOR_SOON -> listOf("Stop giving the food you suspect and note it in Nila.", "Book a visit and bring the report: it lists foods, times and photos.")
        Step.RECHECK -> listOf("Keep logging foods and photos; patterns need a few days to show.")
        Step.WATCH -> listOf("Nothing worrying logged. Keep introducing one new food at a time.")
    }

    /** NIAID 2017: severe eczema puts a baby in the highest peanut-allergy risk group. */
    val ECZEMA_SEVERE = Question("eczema", "Has a doctor said your baby's eczema is severe, or does it keep coming back despite moisturising?")

    /**
     * Allergen introduction card. Timing guidance only, from the NIAID 2017
     * addendum (LEAP: early peanut cut peanut allergy by 81% in high-risk
     * infants): severe eczema or egg allergy -> talk to the doctor before peanut
     * at 4-6 months; mild-moderate eczema -> peanut around 6 months; none ->
     * introduce freely with other foods.
     */
    fun introductionAdvice(ageDays: Int, eczema: Answer?, tried: Set<Allergen>): List<Reason> = buildList {
        val months = ageDays / 30.44
        if (months >= 4 && Allergen.PEANUT !in tried) when (eczema) {
            Answer.YES -> add(Reason("With severe eczema, talk to your doctor about introducing peanut at 4-6 months; they may suggest a test first.", Source.NIAID_2017, Step.DOCTOR_SOON))
            Answer.UNSURE -> add(Reason("If your baby has eczema, ask your doctor when to start peanut. Early introduction lowers peanut allergy risk.", Source.NIAID_2017, Step.WATCH))
            else -> if (months >= 6) add(Reason("Peanut (as smooth butter thinned with water) can be introduced around 6 months, after other solids.", Source.NIAID_2017, Step.WATCH))
        }
    }
}
