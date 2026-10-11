package com.nila.skin.ui

import android.graphics.Bitmap
import android.util.Log
import com.nila.skin.core.food.FoodCheck
import com.nila.skin.core.vlm.VisionForms
import com.nila.skin.data.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Naming the foods in a photo, cross-checked so the answer never rests on the
 * AI alone.
 *
 * 1. The model looks twice with differently worded questions; the candidates
 *    are everything either look named.
 * 2. Each candidate is checked three ways: named by both looks
 *    (self-consistency), a yes/no question about that one food, and the
 *    colour maths (k-means palette against the food's colour, CIEDE2000).
 * 3. Foods most checks agree on are pre-ticked; the rest are shown unticked
 *    for the parent to decide.
 */
object PlateAI {

    data class Result(val firstRaw: String, val secondRaw: String?, val evidence: List<FoodCheck.Evidence>, val listMs: Long, val checkMs: Long)

    suspend fun look(photo: Bitmap, crossCheck: Boolean = true, stage: (String?) -> Unit = {}): Result? {
        val db = SkinServices.foods
        stage("Intern3.5-VL is naming the foods on the NPU...")
        val t0 = System.currentTimeMillis()
        val raw = safely("plate look") { SkinServices.vision.look(photo, VisionForms.SYSTEM, VisionForms.PLATE_PROMPT) } ?: run { stage(null); return null }
        val first = VisionForms.parsePlate(raw, db)
        val listMs = System.currentTimeMillis() - t0
        if (!crossCheck) { stage(null); return Result(raw, null, first.map { FoodCheck.Evidence(it.text, it.food, null, null, null) }, listMs, 0) }

        val t1 = System.currentTimeMillis()
        stage("Second look, worded differently...")
        val secondRaw = safely("second look") { SkinServices.vision.look(photo, VisionForms.SYSTEM, VisionForms.SECOND_PROMPT) }
        val second = secondRaw?.let { VisionForms.parsePlate(it, db) } ?: emptyList()
        stage("Colour maths: counting each food's colour in CIELAB...")
        val px = withContext(Dispatchers.Default) { FoodCheck.Pixels.of(PhotoStore.toPixels(photo, 400)) }
        val candidates = (first + second.filter { s -> !FoodCheck.named(s.text, s.food, first) }).take(10)
        val evidence = candidates.map { g ->
            stage("Is there ${g.text}? (yes/no check)")
            val yn = safely("verify") { SkinServices.vision.look(photo, VisionForms.SYSTEM, VisionForms.verifyPrompt(g.text)) }?.let { VisionForms.parseYesNo(it) }
            val both = FoodCheck.named(g.text, g.food, first) && FoodCheck.named(g.text, g.food, second)
            FoodCheck.Evidence(g.text, g.food, if (secondRaw == null) null else both, yn, withContext(Dispatchers.Default) { FoodCheck.colour(g.food?.id, px) })
        }
        // When the AI misses, the colour maths proposes (unticked), and the AI is asked yes/no about it.
        val named = evidence.mapNotNull { it.food?.id }.toSet()
        val proposed = withContext(Dispatchers.Default) { FoodCheck.proposals(px, named) }.mapNotNull { (id, share) ->
            val f = db[id] ?: return@mapNotNull null
            val word = f.aliases.firstOrNull() ?: f.name
            stage("Colour suggests ${f.name.lowercase()}: asking the AI yes/no...")
            val yn = safely("verify colour") { SkinServices.vision.look(photo, VisionForms.SYSTEM, VisionForms.verifyPrompt(word)) }?.let { VisionForms.parseYesNo(it) }
            FoodCheck.Evidence(f.name, f, null, yn, FoodCheck.ColourMatch(true, share, 0.0), fromColour = true)
        }
        stage(null)
        val all = evidence + proposed
        return Result(raw, secondRaw, all, listMs, System.currentTimeMillis() - t1)
    }
}

/**
 * How good is the on-device AI at naming baby foods? Measured, not claimed.
 *
 * Ten openly licensed baby-food photos with hand-written lists of what is
 * really there. Precision = share of the foods shown (pre-ticked) that are
 * really there; recall = share of the main foods that were found. Compared:
 * the AI's list alone, and the list after the cross-checks. Runs on the phone
 * so a judge can watch it.
 */
object VisionEval {

    data class Truth(
        val asset: String, val label: String,
        /** Food-table ids and words that count as correct. */
        val ok: Set<String>,
        /** The main foods; each is found by any of its ids or words. */
        val core: List<Set<String>>,
    )

    val truths = listOf(
        Truth("demo/meal/ragi_porridge.jpg", "Ragi porridge", setOf("ragi_porridge", "ragi", "porridge", "millet", "chikoo"), listOf(setOf("ragi_porridge", "ragi", "porridge", "millet"))),
        Truth("demo/meal/vegetable_khichdi_1.jpg", "Vegetable khichdi",
            setOf("khichdi", "rice", "moong", "moong_dal", "dal", "carrot", "peas", "coconut", "onion", "cucumber", "salad", "chutney", "pickle", "lemon"),
            listOf(setOf("khichdi", "rice", "dal", "moong_dal"), setOf("onion", "cucumber", "salad"))),
        Truth("demo/meal/suji_kheer.jpg", "Suji kheer with raisins", setOf("suji_kheer", "semolina", "kheer", "pudding", "porridge", "raisin", "dates", "milk", "oats", "rice"),
            listOf(setOf("suji_kheer", "semolina", "kheer", "pudding", "porridge", "oats", "rice"), setOf("raisin", "dates"))),
        Truth("demo/meal/mashed_banana.jpg", "Mashed banana", setOf("banana", "oats", "granola", "muesli", "cereal", "nuts"),
            listOf(setOf("banana"), setOf("oats", "granola", "muesli", "cereal"))),
        Truth("demo/meal/pumpkin_puree.jpg", "Pumpkin puree", setOf("pumpkin", "sweet_potato", "carrot", "squash", "puree", "mango"), listOf(setOf("pumpkin", "squash", "sweet_potato"))),
        Truth("demo/meal/mashed_pumpkin_potato.jpg", "Mashed pumpkin and potato", setOf("pumpkin", "potato", "sweet_potato", "squash", "veg_mash", "mash", "curry", "spinach", "coriander", "herb"),
            listOf(setOf("pumpkin", "squash", "sweet_potato"), setOf("potato", "veg_mash", "mash"))),
        Truth("demo/meal/starting_solids_homemade_baby_food_in_a_.jpg", "Baby mash with fruit slices",
            setOf("potato", "veg_mash", "apple", "pear", "sweet_potato", "pumpkin", "dal", "moong_dal", "khichdi", "banana", "puree", "mash", "porridge", "fruit"),
            listOf(setOf("potato", "veg_mash", "sweet_potato", "pumpkin", "dal", "moong_dal", "khichdi", "puree", "mash", "porridge"), setOf("apple", "pear", "banana", "fruit"))),
        Truth("demo/meal/pureed_veggies.jpg", "Jars of vegetable puree",
            setOf("peas", "spinach", "broccoli", "carrot", "beetroot", "pumpkin", "sweet_potato", "avocado", "potato", "veg_mash", "puree", "zucchini", "squash", "apple", "pear"),
            listOf(setOf("peas", "spinach", "broccoli", "avocado", "zucchini"), setOf("carrot", "pumpkin", "sweet_potato", "squash"), setOf("beetroot"))),
        Truth("demo/meal/parsnip_sweet_potato.jpg", "Parsnip and sweet potato", setOf("sweet_potato", "parsnip", "potato", "veg_mash", "puree", "mash", "pumpkin", "apple", "banana"),
            listOf(setOf("sweet_potato", "parsnip", "potato", "veg_mash", "mash", "puree"))),
        Truth("demo/meal/berry_oatmeal_baby_food.jpg", "Oatmeal with berries", setOf("oats", "berries", "berry", "blueberr", "raspberr", "strawberr", "blackberr", "jam", "porridge", "oatmeal", "beetroot"),
            listOf(setOf("oats", "porridge", "oatmeal"), setOf("berries", "berry", "blueberr", "raspberr", "strawberr", "blackberr", "jam"))),
    )

    data class Row(
        val label: String, val listed: List<String>, val kept: List<String>,
        val listedRight: Int, val keptRight: Int, val coreTotal: Int, val coreListed: Int, val coreKept: Int, val ms: Long,
        val suggested: List<String> = emptyList(), val coreShown: Int = 0,
    )

    data class Totals(val listedRight: Int, val listed: Int, val keptRight: Int, val kept: Int, val coreTotal: Int, val coreListed: Int, val coreKept: Int, val coreShown: Int = 0) {
        fun pct(a: Int, b: Int) = if (b == 0) 0 else Math.round(100.0 * a / b).toInt()
    }

    fun totals(rows: List<Row>) = Totals(rows.sumOf { it.listedRight }, rows.sumOf { it.listed.size }, rows.sumOf { it.keptRight }, rows.sumOf { it.kept.size },
        rows.sumOf { it.coreTotal }, rows.sumOf { it.coreListed }, rows.sumOf { it.coreKept }, rows.sumOf { it.coreShown })

    private fun matches(text: String, foodId: String?, keys: Set<String>) =
        (foodId != null && foodId in keys) || keys.any { k -> text.lowercase().contains(k) }

    private fun core(es: List<FoodCheck.Evidence>, t: Truth) = t.core.count { item -> es.any { matches(it.text, it.food?.id, item) } }

    suspend fun run(onRow: (Row) -> Unit): List<Row>? {
        if (!SkinServices.vision.ensureLoaded()) return null
        val out = ArrayList<Row>()
        for (t in truths) {
            val photo = SkinServices.photos.loadAsset(t.asset) ?: continue
            val t0 = System.currentTimeMillis()
            val r = PlateAI.look(photo) ?: continue
            val firstOnly = r.evidence.filter { e -> !e.fromColour && FoodCheck.named(e.text, e.food, VisionForms.parsePlate(r.firstRaw, SkinServices.foods)) }
            val kept = r.evidence.filter { it.keep }
            val row = Row(t.label, firstOnly.map { it.text }, kept.map { it.text },
                firstOnly.count { matches(it.text, it.food?.id, t.ok) }, kept.count { matches(it.text, it.food?.id, t.ok) },
                t.core.size, core(firstOnly, t), core(kept, t), System.currentTimeMillis() - t0,
                r.evidence.filter { it.fromColour }.map { it.text }, core(r.evidence, t))
            Log.i("SkinEval", "${t.label} | first=${r.firstRaw.replace('\n', ' ')} | second=${r.secondRaw?.replace('\n', ' ')} | " +
                r.evidence.joinToString(" ; ") { "${it.text}:${it.summary()}:${it.verdict}" } +
                " | list ${row.listedRight}/${row.listed.size} core ${row.coreListed}/${row.coreTotal} -> kept ${row.keptRight}/${row.kept.size} core ${row.coreKept}/${row.coreTotal}; shown core ${row.coreShown}")
            out += row
            onRow(row)
        }
        return out
    }
}
