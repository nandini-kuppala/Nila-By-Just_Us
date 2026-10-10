package com.nila.skin.core.food

import kotlin.math.roundToInt

/** WHO/UNICEF (2021) minimum dietary diversity food groups, 6-23 months. */
enum class FoodGroup(val label: String) {
    BREASTMILK("Breast milk"),
    GRAINS("Grains, roots and tubers"),
    PULSES("Pulses, nuts and seeds"),
    DAIRY("Dairy"),
    FLESH("Meat, fish and poultry"),
    EGGS("Eggs"),
    VITA_FV("Vitamin A-rich fruits and vegetables"),
    OTHER_FV("Other fruits and vegetables"),
}

/** The nine allergens behind most food allergy in children. */
enum class Allergen(val label: String) {
    MILK("Cow's milk"), EGG("Egg"), PEANUT("Peanut"), TREE_NUT("Tree nuts"), WHEAT("Wheat"),
    SOY("Soy"), SESAME("Sesame"), FISH("Fish"), SHELLFISH("Shellfish");

    companion object {
        fun parse(s: String): Allergen? = entries.firstOrNull { it.name.equals(s.trim(), ignoreCase = true) }
    }
}

data class Nutrients(
    val kcal: Double = 0.0, val proteinG: Double = 0.0, val fatG: Double = 0.0, val carbG: Double = 0.0,
    val fibreG: Double = 0.0, val calciumMg: Double = 0.0, val ironMg: Double = 0.0, val zincMg: Double = 0.0,
    val vitAUg: Double = 0.0, val vitCMg: Double = 0.0,
) {
    operator fun plus(o: Nutrients) = Nutrients(kcal + o.kcal, proteinG + o.proteinG, fatG + o.fatG, carbG + o.carbG,
        fibreG + o.fibreG, calciumMg + o.calciumMg, ironMg + o.ironMg, zincMg + o.zincMg, vitAUg + o.vitAUg, vitCMg + o.vitCMg)

    operator fun times(f: Double) = Nutrients(kcal * f, proteinG * f, fatG * f, carbG * f, fibreG * f,
        calciumMg * f, ironMg * f, zincMg * f, vitAUg * f, vitCMg * f)
}

data class Food(
    val id: String,
    val name: String,
    val aliases: List<String>,
    val groups: Set<FoodGroup>,
    val allergens: Set<Allergen>,
    val unit: String,
    val unitGrams: Double,
    val per100g: Nutrients,
    val source: String,
) {
    fun nutrientsFor(grams: Double) = per100g * (grams / 100.0)
    fun portionText(grams: Double): String {
        val units = grams / unitGrams
        val u = if (units == units.roundToInt().toDouble()) units.roundToInt().toString() else "%.1f".format(units)
        return "$u $unit (${grams.roundToInt()} g)"
    }
}

/**
 * The food table, read from foods.tsv (built by tools/build_foods.py from
 * USDA FoodData Central). Plain TSV so it parses with no library on the JVM
 * and on the phone alike.
 */
class FoodDb(val foods: List<Food>) {

    private val byId = foods.associateBy { it.id }

    operator fun get(id: String): Food? = byId[id]

    /**
     * Best food for a free-text name, from the vision model or the search box.
     * Exact alias first, then an alias contained in the text, then a word
     * overlap -- and null rather than a wild guess.
     */
    fun match(text: String): Food? {
        val t = normalise(text)
        if (t.isBlank()) return null
        foods.firstOrNull { f -> f.aliases.any { normalise(it) == t } || normalise(f.name) == t }?.let { return it }
        // Longest alias contained in the text wins: "moong dal khichdi" -> khichdi beats moong.
        var best: Food? = null; var bestLen = 0
        for (f in foods) for (a in f.aliases) {
            val na = normalise(a)
            if (na.length >= 3 && Regex("\\b${Regex.escape(na)}\\b").containsMatchIn(t) && na.length > bestLen) {
                best = f; bestLen = na.length
            }
        }
        if (best != null) return best
        val words = t.split(' ').filter { it.length >= 4 }.toSet()
        return foods.maxByOrNull { f -> (f.aliases + f.name).sumOf { a -> normalise(a).split(' ').count { it in words } } }
            ?.takeIf { f -> (f.aliases + f.name).any { a -> normalise(a).split(' ').any { it in words } } }
    }

    fun search(query: String, limit: Int = 12): List<Food> {
        val q = normalise(query)
        if (q.isBlank()) return foods.take(limit)
        return foods.filter { f -> normalise(f.name).contains(q) || f.aliases.any { normalise(it).contains(q) } }.take(limit)
    }

    companion object {
        fun normalise(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

        fun parse(tsv: String): FoodDb {
            val lines = tsv.lineSequence().filter { it.isNotBlank() }.toList()
            val head = lines.first().split('\t')
            fun idx(n: String) = head.indexOf(n).also { require(it >= 0) { "column $n missing" } }
            val foods = lines.drop(1).map { line ->
                val c = line.split('\t')
                fun d(n: String) = c[idx(n)].toDoubleOrNull() ?: 0.0
                Food(
                    id = c[idx("id")],
                    name = c[idx("name")],
                    aliases = c[idx("aliases")].split('|').map { it.trim() }.filter { it.isNotEmpty() },
                    groups = c[idx("groups")].split(',').mapNotNull { g -> FoodGroup.entries.firstOrNull { it.name == g.trim() } }.toSet(),
                    allergens = c[idx("allergens")].split(',').mapNotNull { Allergen.parse(it) }.toSet(),
                    unit = c[idx("unit")],
                    unitGrams = d("unit_g"),
                    per100g = Nutrients(d("kcal"), d("protein_g"), d("fat_g"), d("carb_g"), d("fibre_g"),
                        d("calcium_mg"), d("iron_mg"), d("zinc_mg"), d("vita_ug"), d("vitc_mg")),
                    source = c[idx("source")],
                )
            }
            return FoodDb(foods)
        }

        /** The table shipped inside skin-core. */
        fun bundled(): FoodDb {
            val text = FoodDb::class.java.getResourceAsStream("/foods.tsv")?.bufferedReader()?.use { it.readText() }
                ?: error("foods.tsv missing from skin-core resources")
            return parse(text)
        }
    }
}
