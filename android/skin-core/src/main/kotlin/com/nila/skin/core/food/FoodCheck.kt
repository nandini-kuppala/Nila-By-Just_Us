package com.nila.skin.core.food

import com.nila.skin.core.color.ColorMath
import com.nila.skin.core.color.Lab
import com.nila.skin.core.image.PixelImage

/**
 * The main colours in a photo, by k-means clustering in CIELAB.
 *
 * Deterministic: centres start from the most distant colours (farthest-point
 * seeding from the median), so the same photo always gives the same palette.
 */
object Palette {

    data class Cluster(val lab: Lab, val share: Double)

    fun of(photo: PixelImage, k: Int = 7, iterations: Int = 12): List<Cluster> {
        val img = photo.downscaled(96)
        val px = Array(img.width * img.height) { ColorMath.lab(img.argb[it]) }
        if (px.isEmpty()) return emptyList()
        val centres = ArrayList<Lab>()
        centres += com.nila.skin.core.image.Regions.stats(px.toList()).median
        while (centres.size < k) centres += px.maxBy { p -> centres.minOf { ColorMath.deltaE76(p, it) } }
        val assign = IntArray(px.size)
        repeat(iterations) {
            for (i in px.indices) assign[i] = centres.indices.minBy { ColorMath.deltaE76(px[i], centres[it]) }
            for (c in centres.indices) {
                var l = 0.0; var a = 0.0; var b = 0.0; var n = 0
                for (i in px.indices) if (assign[i] == c) { l += px[i].l; a += px[i].a; b += px[i].b; n++ }
                if (n > 0) centres[c] = Lab(l / n, a / n, b / n)
            }
        }
        return centres.indices.map { c -> Cluster(centres[c], assign.count { it == c }.toDouble() / px.size) }
            .filter { it.share > 0 }.sortedByDescending { it.share }
    }
}

/**
 * Typical colours of common baby foods, in CIELAB, for the colour cross-check.
 *
 * Deliberately broad, set from what these foods look like cooked and served
 * (not tuned on the demo photos). The check can only rule a food *out*: no
 * green anywhere means "spinach" is doubtful; an orange region does not prove
 * "carrot", because a bowl can be orange too.
 */
object FoodColours {
    private fun lab(l: Double, a: Double, b: Double) = Lab(l, a, b)
    val ref: Map<String, List<Lab>> = mapOf(
        "ragi_porridge" to listOf(lab(45.0, 8.0, 10.0), lab(55.0, 7.0, 12.0), lab(38.0, 10.0, 12.0)),
        "khichdi" to listOf(lab(68.0, 6.0, 45.0), lab(60.0, 8.0, 38.0)),
        "dal" to listOf(lab(66.0, 8.0, 50.0)), "moong_dal" to listOf(lab(70.0, 4.0, 48.0)), "palak_dal" to listOf(lab(50.0, -10.0, 35.0)),
        "rice" to listOf(lab(88.0, 0.0, 8.0)), "curd" to listOf(lab(90.0, -1.0, 8.0)), "curd_rice" to listOf(lab(88.0, -1.0, 10.0)),
        "idli" to listOf(lab(87.0, 0.0, 10.0)), "suji_kheer" to listOf(lab(84.0, 2.0, 18.0), lab(78.0, 4.0, 22.0)),
        "oats" to listOf(lab(75.0, 4.0, 20.0), lab(68.0, 5.0, 22.0)),
        "banana" to listOf(lab(82.0, -1.0, 30.0), lab(75.0, 3.0, 35.0)),
        "apple" to listOf(lab(80.0, 1.0, 25.0), lab(85.0, -2.0, 22.0)), "pear" to listOf(lab(82.0, -2.0, 22.0)),
        "carrot" to listOf(lab(62.0, 30.0, 58.0)), "sweet_potato" to listOf(lab(66.0, 22.0, 50.0), lab(75.0, 10.0, 40.0)),
        "pumpkin" to listOf(lab(68.0, 22.0, 62.0), lab(62.0, 26.0, 58.0)), "papaya" to listOf(lab(65.0, 30.0, 52.0)),
        "mango" to listOf(lab(72.0, 15.0, 65.0)), "potato" to listOf(lab(82.0, 0.0, 25.0), lab(78.0, 2.0, 30.0)),
        "veg_mash" to listOf(lab(66.0, 15.0, 45.0)), "spinach" to listOf(lab(42.0, -18.0, 28.0), lab(50.0, -15.0, 30.0)),
        "peas" to listOf(lab(60.0, -25.0, 42.0), lab(55.0, -20.0, 38.0)), "broccoli" to listOf(lab(50.0, -18.0, 30.0)),
        "beetroot" to listOf(lab(35.0, 45.0, 5.0), lab(30.0, 40.0, 0.0)), "pomegranate" to listOf(lab(40.0, 50.0, 20.0)),
        "egg_yolk" to listOf(lab(75.0, 8.0, 60.0)), "chikoo" to listOf(lab(55.0, 12.0, 30.0)), "avocado" to listOf(lab(70.0, -15.0, 40.0)),
        "sambar" to listOf(lab(50.0, 20.0, 45.0)), "dates" to listOf(lab(30.0, 15.0, 20.0)),
    )
}

/**
 * Cross-checking one food the AI named, so the answer never rests on the AI
 * alone.
 *
 * Three independent checks:
 *  1. self-consistency: the model looks twice with differently worded
 *     questions, and a food named both times counts as agreeing;
 *  2. a yes/no question about that one food (small models are better at
 *     verifying one object than at listing many);
 *  3. maths: does the photo contain a region of that food's colour
 *     (k-means palette, CIEDE2000 under [MATCH_DE], at least [MIN_SHARE] of
 *     the picture)? Only for foods with a known colour.
 *
 * The parent sees each check and confirms; foods the checks disagree on are
 * shown unticked.
 */
object FoodCheck {

    const val MATCH_DE = 14.0
    /** A food's colour must cover at least this share of the photo to count as present. */
    const val MIN_SHARE = 0.01
    /** A colour covering this much of the centre is worth proposing when the AI named nothing like it. */
    const val PROPOSE_SHARE = 0.15

    /** The photo's pixels in CIELAB, downscaled: what the colour checks count over. */
    class Pixels(val labs: List<Lab>, val centre: List<Lab> = labs) {
        companion object {
            /**
             * [centre] is the middle half of the frame, where the bowl usually
             * is: proposals come only from there, so a wooden table or a steel
             * tray at the edges is not offered as a food.
             */
            fun of(photo: PixelImage): Pixels {
                val img = photo.downscaled(96)
                val all = img.argb.map { ColorMath.lab(it) }
                val mid = ArrayList<Lab>()
                for (y in img.height / 4 until img.height * 3 / 4) for (x in img.width / 4 until img.width * 3 / 4) mid += all[y * img.width + x]
                return Pixels(all, mid.ifEmpty { all })
            }
        }
        fun centreShare(refs: List<Lab>): Double = Pixels(centre).share(refs)
        /** Share of pixels within [MATCH_DE] (CIEDE2000) of any reference colour. */
        fun share(refs: List<Lab>): Double {
            if (labs.isEmpty()) return 0.0
            // A cheap CIE76 pre-filter keeps CIEDE2000 to the plausible pixels.
            return labs.count { p -> refs.any { r -> ColorMath.deltaE76(p, r) < 40 && ColorMath.deltaE2000(p, r) < MATCH_DE } }.toDouble() / labs.size
        }
        fun closest(refs: List<Lab>): Double = labs.minOfOrNull { p -> refs.minOf { ColorMath.deltaE2000(p, it) } } ?: Double.MAX_VALUE
    }

    data class ColourMatch(val found: Boolean, val share: Double, val deltaE: Double)

    enum class Verdict(val label: String) {
        CONFIRMED("All checks agree"), LIKELY("Most checks agree"), UNSURE("Checks disagree: please confirm"),
    }

    data class Evidence(
        val text: String,
        val food: Food?,
        /** Named by both of the model's differently worded looks. */
        val secondLook: Boolean?,
        val yesNo: Boolean?,
        val colour: ColourMatch?,
        /** True when the maths proposed it from colour; never pre-ticked. */
        val fromColour: Boolean = false,
    ) {
        val checks: List<Boolean> get() = listOfNotNull(secondLook, yesNo, colour?.found)
        val agree: Int get() = checks.count { it }
        val against: Int get() = checks.count { !it }
        val verdict: Verdict get() = when {
            against == 0 && agree >= 2 -> Verdict.CONFIRMED
            agree > against -> Verdict.LIKELY
            else -> Verdict.UNSURE
        }
        /** Pre-ticked for the parent when most checks agree. */
        val keep: Boolean get() = !fromColour && verdict != Verdict.UNSURE
        fun summary(): String = if (fromColour) "${((colour?.share ?: 0.0) * 100).toInt()}% of the middle of the photo is this colour" +
            (yesNo?.let { " · AI yes/no ${if (it) "✓" else "✗"}" } ?: "") + " · tick if it is there" else buildList {
            secondLook?.let { add(if (it) "named in both looks ✓" else "named in one look only ✗") }
            yesNo?.let { add("yes/no ${if (it) "✓" else "✗"}") }
            colour?.let { add(if (it.found) "colour ✓ (${(it.share * 100).toInt()}% of photo)" else "colour ✗ (none of its colour)") }
        }.joinToString(" · ")
    }

    /** Counted pixel by pixel, so a small bowl or jar still counts. */
    fun colour(foodId: String?, px: Pixels): ColourMatch? {
        val refs = foodId?.let { FoodColours.ref[it] } ?: return null
        val share = px.share(refs)
        return ColourMatch(share >= MIN_SHARE, share, if (share > 0) 0.0 else px.closest(refs))
    }

    /**
     * When the AI misses: foods whose colour dominates the photo and that
     * nothing named so far resembles. Shown to the parent unticked, as
     * "the colour suggests...". At most one food per colour family, so an
     * orange bowl proposes pumpkin, not pumpkin, carrot and papaya.
     */
    /**
     * Only colours that point to a food with little doubt in an Indian baby's
     * diet are ever proposed: ragi's grey-brown porridge, beetroot's magenta,
     * green purée, orange purée. Whites and yellows (rice, idli, curd, dal,
     * khichdi, banana, suji) look alike, so they are left to the AI and the parent.
     */
    val PROPOSABLE = listOf("ragi_porridge", "beetroot", "spinach", "peas", "carrot", "pumpkin", "sweet_potato")

    fun proposals(px: Pixels, already: Set<String>, max: Int = 2): List<Pair<String, Double>> {
        // Only the main (most typical) colour of each food, so pale shades of beige foods are not taken for it.
        val shares = PROPOSABLE.filter { it !in already }.map { it to px.centreShare(FoodColours.ref.getValue(it).take(1)) }
            .filter { it.second >= PROPOSE_SHARE }.sortedByDescending { it.second }
        val taken = already.mapNotNull { FoodColours.ref[it] }.flatten().toMutableList()
        val out = ArrayList<Pair<String, Double>>()
        for ((id, share) in shares) {
            val refs = FoodColours.ref.getValue(id)
            if (taken.any { t -> refs.any { ColorMath.deltaE2000(it, t) < MATCH_DE } }) continue
            out += id to share; taken += refs
            if (out.size >= max) break
        }
        return out
    }

    /** As above, from a k-means palette (cluster shares). */
    fun colour(foodId: String?, palette: List<Palette.Cluster>): ColourMatch? {
        val refs = foodId?.let { FoodColours.ref[it] } ?: return null
        var best = Double.MAX_VALUE; var share = 0.0
        for (c in palette) {
            val d = refs.minOf { ColorMath.deltaE2000(it, c.lab) }
            if (d < MATCH_DE) share += c.share
            if (d < best) best = d
        }
        return ColourMatch(share >= MIN_SHARE, share, best)
    }

    /** Does a second list name the same food (same table food, or the same words)? */
    fun named(guess: String, food: Food?, second: List<com.nila.skin.core.vlm.VisionForms.PlateGuess>): Boolean =
        second.any { s -> (food != null && s.food?.id == food.id) || s.text.equals(guess, ignoreCase = true) }
}
