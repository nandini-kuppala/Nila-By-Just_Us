package com.nila.skin.core.food

import com.nila.skin.core.rules.Source

/** One food on a plate, with how much. */
data class PlateItem(val food: Food, val grams: Double)

/** Daily needs for one age band, from ICMR-NIN 2020 (RDA) and WHO. */
data class Targets(
    val band: String,
    /** Energy needed from food on top of breast milk, WHO 2003; null for under 6 months. */
    val kcalFromFood: Double?,
    val proteinG: Double, val calciumMg: Double, val ironMg: Double, val zincMg: Double,
    val vitAUg: Double, val vitCMg: Double,
)

object Nutrition {

    /**
     * ICMR-NIN 2020 Summary of RDA for Indians: infants 6-12 months and
     * children 1-3 years (protein 10.5 / 12.5 g, calcium 300 / 500 mg, iron 3 /
     * 8 mg, zinc 2.5 / 3.3 mg, vitamin A 350 / 390 ug, vitamin C 30 / 30 mg).
     * Energy is the amount WHO says must come from foods for a breastfed child:
     * about 200 kcal at 6-8 months, 300 at 9-11 and 550 at 12-23.
     */
    fun targets(ageDays: Int): Targets {
        val months = ageDays / 30.44
        return when {
            months < 6 -> Targets("0-6 months", null, 8.1, 300.0, 0.0, 0.0, 350.0, 20.0)
            months < 9 -> Targets("6-8 months", 200.0, 10.5, 300.0, 3.0, 2.5, 350.0, 30.0)
            months < 12 -> Targets("9-11 months", 300.0, 10.5, 300.0, 3.0, 2.5, 350.0, 30.0)
            months < 24 -> Targets("12-23 months", 550.0, 12.5, 500.0, 8.0, 3.3, 390.0, 30.0)
            else -> Targets("2-3 years", null, 12.5, 500.0, 8.0, 3.3, 390.0, 30.0)
        }
    }

    val sources = listOf(Source.ICMR_NIN_2020, Source.WHO_CF, Source.USDA_FDC)

    fun total(items: List<PlateItem>): Nutrients = items.fold(Nutrients()) { acc, i -> acc + i.food.nutrientsFor(i.grams) }

    /** One line per nutrient: what food gave today against the daily need. */
    data class Line(val name: String, val unit: String, val got: Double, val need: Double) {
        val share: Double get() = if (need <= 0) 1.0 else got / need
    }

    fun lines(total: Nutrients, t: Targets): List<Line> = buildList {
        t.kcalFromFood?.let { add(Line("Energy from food", "kcal", total.kcal, it)) }
        add(Line("Protein", "g", total.proteinG, t.proteinG))
        if (t.ironMg > 0) add(Line("Iron", "mg", total.ironMg, t.ironMg))
        add(Line("Calcium", "mg", total.calciumMg, t.calciumMg))
        if (t.zincMg > 0) add(Line("Zinc", "mg", total.zincMg, t.zincMg))
        add(Line("Vitamin A", "µg", total.vitAUg, t.vitAUg))
        add(Line("Vitamin C", "mg", total.vitCMg, t.vitCMg))
    }

    /**
     * Foods from the table richest in a nutrient per typical portion, for the
     * "ideas" card when iron is low. Only foods safe for the age are offered.
     */
    fun ideas(db: FoodDb, nutrient: (Nutrients) -> Double, ageDays: Int, n: Int = 4): List<Food> =
        db.foods.filter { FoodSafety.blockedReason(it, ageDays) == null && it.groups.isNotEmpty() && FoodGroup.BREASTMILK !in it.groups }
            .sortedByDescending { nutrient(it.nutrientsFor(it.typicalGrams)) }
            .take(n)
}

/**
 * WHO/UNICEF minimum dietary diversity: at least 5 of the 8 food groups in a
 * day, for children 6-23 months. It is the indicator governments use to track
 * child diets (India's NFHS reports it), and it needs no portion sizes at all.
 */
object DietDiversity {
    const val MINIMUM = 5

    data class Result(val groups: Set<FoodGroup>, val missing: List<FoodGroup>) {
        val count: Int get() = groups.size
        val meets: Boolean get() = count >= MINIMUM
    }

    fun of(foods: Collection<Food>, breastfedToday: Boolean): Result {
        val g = foods.flatMap { it.groups }.toMutableSet()
        if (breastfedToday) g += FoodGroup.BREASTMILK
        return Result(g, FoodGroup.entries.filter { it !in g })
    }
}

/**
 * Foods that should not be given at this age, from NHS Start for Life
 * weaning advice: honey under 12 months (infant botulism), whole nuts under 5
 * years (choking; ground nuts and nut butters are fine), whole grapes (cut
 * them lengthwise), and cow's milk as a main drink under 12 months (fine in
 * cooking).
 */
object FoodSafety {
    fun blockedReason(food: Food, ageDays: Int): String? = when {
        food.id == "honey" && ageDays < 365 -> "No honey before 12 months: it can cause infant botulism."
        else -> null
    }

    fun cautions(food: Food, ageDays: Int): List<String> = buildList {
        blockedReason(food, ageDays)?.let { add(it) }
        if (food.id in setOf("almond", "cashew")) add("Give nuts ground or as smooth butter only; whole nuts are a choking risk under 5.")
        if (food.id == "grapes") add("Cut grapes lengthwise into quarters; whole grapes are a choking risk.")
        if (food.id == "milk" && ageDays < 365) add("Cow's milk is fine in cooking, but not as the main drink before 12 months.")
        if (food.id in setOf("jaggery") && ageDays < 365) add("Avoid adding sugar or salt to food for babies under 12 months.")
    }
}
