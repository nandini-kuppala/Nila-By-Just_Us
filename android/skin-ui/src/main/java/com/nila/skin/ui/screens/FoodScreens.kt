package com.nila.skin.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nila.skin.core.food.Allergen
import com.nila.skin.core.food.AllergenUpkeep
import com.nila.skin.core.food.oneIn
import com.nila.skin.ui.RemindButton
import com.nila.skin.ui.PlateAI
import com.nila.skin.core.food.FoodCheck
import com.nila.skin.ui.safely
import com.nila.skin.core.food.AllergyRules
import com.nila.skin.core.food.DietDiversity
import com.nila.skin.core.food.Exposure
import com.nila.skin.core.food.Food
import com.nila.skin.core.food.FoodGroup
import com.nila.skin.core.food.FoodSafety
import com.nila.skin.core.food.Nutrients
import com.nila.skin.core.food.Nutrition
import com.nila.skin.core.food.PlateItem
import com.nila.skin.core.food.ReactionEvent
import com.nila.skin.core.food.ReactionPatterns
import com.nila.skin.core.food.Sign
import com.nila.skin.core.rules.Advice
import com.nila.skin.core.rules.Answer
import com.nila.skin.core.rules.Source
import com.nila.skin.core.rules.Step
import com.nila.skin.core.vlm.VisionForms
import com.nila.skin.data.BabyEntity
import com.nila.skin.data.Codec
import com.nila.skin.data.FoodLogEntity
import com.nila.skin.data.MealEntity
import com.nila.skin.data.ReactionEntity
import com.nila.skin.data.SettingEntity
import com.nila.skin.ui.SkinServices
import com.nila.skin.ui.ageDays
import com.nila.skin.ui.components.AdviceCard
import com.nila.skin.ui.components.ChipGroup
import com.nila.skin.ui.components.ChoiceChip
import com.nila.skin.ui.components.FactCard
import com.nila.skin.ui.components.Heading
import com.nila.skin.ui.components.InfoNote
import com.nila.skin.ui.components.MutedText
import com.nila.skin.ui.components.NilaCard
import com.nila.skin.ui.components.NotADiagnosis
import com.nila.skin.ui.components.OutlinePillButton
import com.nila.skin.ui.components.PhotoInput
import com.nila.skin.ui.components.PillButton
import com.nila.skin.ui.components.QuestionCard
import com.nila.skin.ui.components.RowDivider
import com.nila.skin.ui.components.ScreenHero
import com.nila.skin.ui.components.SkinScaffold
import com.nila.skin.ui.theme.Tone
import com.nila.skin.ui.theme.toneColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val H = 3_600_000L

// ---------------------------------------------------------------------------
// Food & reactions
// ---------------------------------------------------------------------------

@Composable
fun FoodScreen(baby: BabyEntity?, go: (String) -> Unit, back: () -> Unit) {
    val dao = SkinServices.dao
    val db = SkinServices.foods
    val logs by dao.foods().collectAsState(initial = emptyList())
    val reactions by dao.reactions().collectAsState(initial = emptyList())
    val eczemaText by dao.settingFlow("eczema_severe").collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val now = System.currentTimeMillis()
    val ageDays = baby?.ageDays() ?: 240

    var reacting by remember { mutableStateOf(false) }
    var reactionAdvice by remember { mutableStateOf<Advice?>(null) }

    val patterns = remember(logs, reactions) {
        ReactionPatterns.analyse(
            logs.map { Exposure(it.foodId, it.timeMs, it.firstTime) },
            reactions.map { r -> ReactionEvent(r.timeMs, Codec.list(r.signs).mapNotNull { s -> Sign.entries.firstOrNull { it.name == s } }.toSet()) },
        )
    }

    SkinScaffold("Food & reactions", onBack = back) {
        ScreenHero("One new food at a time", "Log foods and Nila spots any that keep coming before a reaction.")

        // Check-ins due
        val due = logs.filter { Today.checkInDue(it, now) }
        if (due.isNotEmpty()) {
            Heading("Check-ins")
            due.forEach { f ->
                NilaCard(modifier = Modifier.fillMaxWidth(), tone = Tone.PINK) {
                    Text("First time: ${db[f.foodId]?.name ?: f.foodId}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    MutedText("Given ${fmtDateTime(f.timeMs)}. Most reactions show within 2 hours; eczema flares can take up to 2 days.")
                    if (now - f.timeMs < 2 * H) RemindButton("checkin-${f.id}", "Remind me at ${fmtDateTime(f.timeMs + 2 * H).substringAfter(", ")}",
                        "Check-in: first ${db[f.foodId]?.name ?: f.foodId}", "Two hours since the first taste. Any rash, swelling, vomiting or breathing change? Tap to answer.",
                        f.timeMs + 2 * H, Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinePillButton("No reaction", onClick = { scope.launch { dao.updateFood(f.copy(checkedInMs = System.currentTimeMillis())) } }, modifier = Modifier.weight(1f))
                        PillButton("Something's off", onClick = {
                            scope.launch { dao.updateFood(f.copy(checkedInMs = System.currentTimeMillis())) }
                            reacting = true
                        }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // Reaction
        reactionAdvice?.let { AdviceCard(it); OutlinePillButton("Done", onClick = { reactionAdvice = null }, modifier = Modifier.fillMaxWidth()) }
        if (reacting) ReactionCard(onCancel = { reacting = false }) { signs, photo, note ->
            scope.launch {
                val path = photo?.let { withContext(Dispatchers.IO) { SkinServices.photos.save(it, "reaction") } }
                val ev = ReactionEvent(System.currentTimeMillis(), signs)
                val newPatterns = ReactionPatterns.analyse(logs.map { Exposure(it.foodId, it.timeMs, it.firstTime) },
                    reactions.map { r -> ReactionEvent(r.timeMs, Codec.list(r.signs).mapNotNull { s -> Sign.entries.firstOrNull { it.name == s } }.toSet()) } + ev)
                val adv = AllergyRules.advise(ev, newPatterns) { db[it]?.name ?: it }
                dao.addReaction(ReactionEntity(timeMs = ev.timeMs, signs = Codec.list(signs.map { it.name }), note = note, photoPath = path, step = adv.step.name))
                reacting = false
                reactionAdvice = adv
            }
        } else if (reactionAdvice == null) {
            OutlinePillButton("Log a reaction", onClick = { reacting = true }, icon = Icons.Outlined.Info, modifier = Modifier.fillMaxWidth())
        }

        // Log food
        Heading("Log a food")
        FoodPicker(ageDays) { food, grams ->
            scope.launch {
                val first = logs.none { it.foodId == food.id }
                dao.addFood(FoodLogEntity(foodId = food.id, grams = grams, timeMs = System.currentTimeMillis(), firstTime = first))
            }
        }
        OutlinePillButton("Photo a whole plate instead", onClick = { go("meal") }, icon = Icons.Outlined.Restaurant, modifier = Modifier.fillMaxWidth())

        // Patterns
        Heading("Patterns")
        val strong = patterns.filter { it.strength == ReactionPatterns.Pattern.Strength.PATTERN }
        val possible = patterns.filter { it.strength == ReactionPatterns.Pattern.Strength.POSSIBLE }
        val once = patterns.filter { it.strength == ReactionPatterns.Pattern.Strength.ONE_OFF }
        NilaCard(modifier = Modifier.fillMaxWidth()) {
            if (strong.isEmpty() && possible.isEmpty() && once.isEmpty()) MutedText("No reactions logged after any food. Patterns appear here once there's something to compare.")
            strong.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Insights, null, tint = toneColors(Tone.PINK).ink)
                    Column(Modifier.weight(1f)) {
                        Text("${db[p.foodId]?.name ?: p.foodId}: ${p.followed} of ${p.exposures} times followed by a reaction",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        MutedText("${"%.0f".format(p.lift)}× the rate after other foods. By chance alone this would happen ${oneIn(p.chance)} (Fisher's exact test). Worth showing your doctor; only a doctor (and often a test) can say if it's an allergy.")
                        if (p.confounded > 0) MutedText("${p.confounded} of those times another new food was given too, which muddies the picture.")
                    }
                }
            }
            possible.forEach { p ->
                Text("${db[p.foodId]?.name ?: p.foodId}: possible pattern (${p.followed} of ${p.exposures} times)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                MutedText("With this few logs it could still be chance (${oneIn(p.chance)}). Keep logging; it becomes clearer either way.")
            }
            once.take(4).forEach { p ->
                MutedText("${db[p.foodId]?.name ?: p.foodId}: a reaction followed once (${p.exposures} times given). One event isn't a pattern.")
            }
        }

        // Allergen tracker
        Heading("Common allergens")
        val tried = logs.flatMap { db[it.foodId]?.allergens ?: emptySet() }.toSet()
        // Paused on a pattern or possible pattern, or after a reaction to a food given only once or twice:
        // an everyday food with one coincidence (khichdi, 1 of 11) does not pause its allergens.
        val paused = patterns.filter { p ->
            p.strength == ReactionPatterns.Pattern.Strength.PATTERN || p.strength == ReactionPatterns.Pattern.Strength.POSSIBLE ||
                (p.followed > 0 && p.exposures <= 2)
        }.flatMap { db[it.foodId]?.allergens ?: emptySet() }.toSet()
        val upkeep = AllergenUpkeep.of(logs.map { AllergenUpkeep.Given(db[it.foodId]?.allergens ?: emptySet(), it.timeMs) }, paused, now)
        val peanutProtein = logs.filter { now - it.timeMs in 0..7 * 24 * H && db[it.foodId]?.allergens?.contains(Allergen.PEANUT) == true }
            .sumOf { (db[it.foodId]?.per100g?.proteinG ?: 0.0) * it.grams / 100.0 }
        NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 6.dp) {
            MutedText("Early introduction of allergens, one at a time, lowers allergy risk for many babies (LEAP trial; NIAID 2017). Once in, keeping them in the diet about twice a week matters too (ASCIA).")
            upkeep.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(if (s.tried) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
                        tint = if (s.paused) toneColors(Tone.PEACH).ink else if (s.tried) toneColors(Tone.TEAL).ink else MaterialTheme.colorScheme.outline)
                    Column(Modifier.weight(1f)) {
                        Text(s.allergen.label, style = MaterialTheme.typography.bodyLarge)
                        s.note(now)?.let { MutedText(it) }
                    }
                    MutedText(s.firstMs?.let { "since ${fmtDate(it)}" } ?: "not yet")
                }
            }
            if (Allergen.PEANUT in tried && Allergen.PEANUT !in paused)
                MutedText("Peanut protein this week: ${"%.1f".format(peanutProtein)} g. NIAID suggests about 6-7 g a week over 3 or more feeds once peanut is started (about 2 tsp of peanut butter, three times a week).")
        }
        WeekCard(logs, ageDays, now)
        val eczema = eczemaText?.let { t -> Answer.entries.firstOrNull { it.name == t } }
        QuestionCard(AllergyRules.ECZEMA_SEVERE, eczema, { a -> scope.launch { dao.putSetting(SettingEntity("eczema_severe", a.name)) } })
        AllergyRules.introductionAdvice(ageDays, eczema, tried).forEach { r ->
            InfoNote(r.text + "  [${r.source?.short}]", icon = Icons.Outlined.Lightbulb, tone = if (r.step == Step.DOCTOR_SOON) Tone.PEACH else Tone.TEAL)
        }

        // Recent
        Heading("Recent")
        NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 0.dp) {
            if (logs.isEmpty()) MutedText("Nothing logged yet.")
            logs.take(15).forEachIndexed { i, f ->
                if (i > 0) RowDivider()
                val food = db[f.foodId]
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((food?.name ?: f.foodId) + if (f.firstTime) "  · first time" else "", style = MaterialTheme.typography.titleSmall)
                        MutedText("${fmtDateTime(f.timeMs)} · ${food?.portionText(f.grams) ?: "${f.grams.toInt()} g"}")
                    }
                    IconButton(onClick = { scope.launch { dao.deleteFood(f.id) } }) { Icon(Icons.Outlined.Close, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            reactions.take(5).forEach { r ->
                RowDivider()
                Text("Reaction · ${fmtDateTime(r.timeMs)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                MutedText(Codec.list(r.signs).mapNotNull { s -> Sign.entries.firstOrNull { it.name == s }?.label }.joinToString(", "))
            }
        }
        NotADiagnosis()
    }
}

/** The last 7 days at a glance: food-group days and average iron, the two numbers worth watching. */
@Composable
private fun WeekCard(logs: List<FoodLogEntity>, ageDays: Int, now: Long) {
    if (ageDays < 182) return
    val db = SkinServices.foods
    val week = logs.filter { now - it.timeMs in 0..7 * 24 * H }
    if (week.isEmpty()) return
    val byDay = week.groupBy { (now - it.timeMs) / (24 * H) }
    val diverseDays = byDay.values.count { day -> DietDiversity.of(day.mapNotNull { db[it.foodId] }, breastfedToday = true).meets }
    val t = Nutrition.targets(ageDays)
    val ironPerDay = byDay.values.map { day -> day.sumOf { (db[it.foodId]?.per100g?.ironMg ?: 0.0) * it.grams / 100.0 } }.average()
    Heading("This week")
    NilaCard(modifier = Modifier.fillMaxWidth()) {
        Text("$diverseDays of ${byDay.size} logged days reached 5 of 8 food groups", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        MutedText("WHO's minimum dietary diversity, counting breast milk as one group.")
        if (t.ironMg > 0) {
            Text("Iron from food: about ${"%.1f".format(ironPerDay)} of ${"%.0f".format(t.ironMg)} mg a day", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            MutedText(if (ironPerDay >= t.ironMg * 0.8) "Close to the ICMR-NIN 2020 need." else "Iron is the nutrient most often short at this age; the meal plate screen suggests iron-rich foods from the table.")
        }
    }
}

@Composable
private fun ReactionCard(onCancel: () -> Unit, onSave: (Set<Sign>, Bitmap?, String) -> Unit) {
    val signs = remember { mutableStateListOf<Sign>() }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var note by remember { mutableStateOf("") }
    NilaCard(modifier = Modifier.fillMaxWidth()) {
        Text("What did you notice?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        ChipGroup(Sign.entries, { it.label }, { it in signs }, { s -> if (s in signs) signs.remove(s) else signs.add(s) })
        if (signs.any { it.severe }) InfoNote("These signs need a doctor straight away. Go now; you can save the log after.", icon = Icons.Outlined.LocalHospital, tone = Tone.PLUM)
        OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
        if (photo == null) PhotoInput("skin", onPhoto = { b, _ -> photo = b }, hint = "A photo of any rash helps the doctor (optional).")
        else MutedText("Photo added.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinePillButton("Cancel", onClick = onCancel, modifier = Modifier.weight(1f))
            PillButton("Save", enabled = signs.isNotEmpty(), onClick = { onSave(signs.toSet(), photo, note) }, modifier = Modifier.weight(1f))
        }
    }
}

/** Search the food table and pick an amount. */
@Composable
fun FoodPicker(ageDays: Int, onPick: (Food, Double) -> Unit) {
    val db = SkinServices.foods
    var q by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<Food?>(null) }
    var units by remember { mutableStateOf(2.0) }
    NilaCard(modifier = Modifier.fillMaxWidth()) {
        val f = chosen
        if (f == null) {
            OutlinedTextField(q, { q = it }, label = { Text("Search: dal, ragi, egg, banana...") }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth())
            val results = if (q.isBlank()) listOf("khichdi", "ragi_porridge", "curd", "banana", "egg", "dal", "idli", "carrot", "peanut_butter", "roti").mapNotNull { db[it] } else db.search(q, 8)
            ChipGroup(results, { it.name }, { false }, { chosen = it; units = if (it.unit == "tbsp") 2.0 else 1.0 })
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = { chosen = null }) { Icon(Icons.Outlined.Close, "Change") }
            }
            AmountStepper(f, units) { units = it }
            FoodSafety.cautions(f, ageDays).forEach { InfoNote(it, icon = Icons.Outlined.Info, tone = Tone.PEACH) }
            PillButton("Log it", enabled = FoodSafety.blockedReason(f, ageDays) == null, icon = Icons.Outlined.Check, modifier = Modifier.fillMaxWidth(), onClick = {
                onPick(f, units * f.unitGrams); chosen = null; q = ""
            })
        }
    }
}

@Composable
fun AmountStepper(food: Food, units: Double, onChange: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onClick = { onChange((units - 0.5).coerceAtLeast(0.5)) }) { Icon(Icons.Outlined.Remove, "Less") }
        Text(food.portionText(units * food.unitGrams), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange(units + 0.5) }) { Icon(Icons.Outlined.Add, "More") }
    }
}

// ---------------------------------------------------------------------------
// Meal plate
// ---------------------------------------------------------------------------

private data class PlateRow(val food: Food?, val text: String, val units: Double, val evidence: FoodCheck.Evidence? = null, val included: Boolean = true)

@Composable
fun MealScreen(baby: BabyEntity?, back: () -> Unit) {
    val dao = SkinServices.dao
    val db = SkinServices.foods
    val scope = rememberCoroutineScope()
    val ageDays = baby?.ageDays() ?: 240
    val startOfDay = remember { val n = System.currentTimeMillis(); n - (n % 86_400_000L) }
    val todayLogs by dao.foodsSince(startOfDay).collectAsState(initial = emptyList())
    val allLogs by dao.foods().collectAsState(initial = emptyList())
    val breastfedText by dao.settingFlow("breastfed").collectAsState(initial = "true")
    val breastfed = breastfedText != "false"

    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var stage by remember { mutableStateOf<String?>(null) }
    var aiNote by remember { mutableStateOf<String?>(null) }
    var aiRaw by remember { mutableStateOf<String?>(null) }
    var crossCheck by remember { mutableStateOf(true) }
    val rows = remember { mutableStateListOf<PlateRow>() }
    var saved by remember { mutableStateOf(false) }

    SkinScaffold("Meal plate", onBack = back) {
        ScreenHero("What's in the bowl?", "The AI suggests foods, three checks test each one, you confirm.")
        if (ageDays < 182) InfoNote("WHO recommends only breast milk or formula until about 6 months. You can still log foods your doctor has advised.", tone = Tone.PEACH)

        if (!saved) {
            if (photo == null) PhotoInput("meal", onPhoto = { b, _ ->
                photo = b
                scope.launch {
                    val r = PlateAI.look(b, crossCheck) { stage = it }
                    if (r == null) { aiNote = "The AI model isn't available, so add the foods yourself below."; return@launch }
                    aiRaw = r.firstRaw + (r.secondRaw?.let { "\n[second look] $it" } ?: "")
                    rows.clear()
                    r.evidence.forEach { e -> rows.add(PlateRow(e.food, e.text, if (e.food?.unit == "tbsp") 3.0 else 1.0, e.takeIf { crossCheck }, !crossCheck || e.keep)) }
                    val kept = r.evidence.count { it.keep }
                    aiNote = if (r.evidence.isEmpty()) "The AI couldn't name the foods; add them below."
                    else "The AI suggested ${r.evidence.size} food${if (r.evidence.size == 1) "" else "s"} in ${"%.1f".format(r.listMs / 1000.0)} s on the NPU." +
                        (if (crossCheck) " Cross-checks took ${"%.1f".format(r.checkMs / 1000.0)} s: $kept pre-ticked where most checks agree; the rest are left for you to decide." else "") +
                        " Untick or remove anything that isn't there."
                }
            }, hint = "Photo the bowl or plate from above, or skip and add foods by hand.")
            if (photo == null) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Cross-check each food", style = MaterialTheme.typography.bodyLarge)
                    MutedText("Small vision models sometimes name foods that aren't there. Each suggestion is tested three ways: does a second, differently worded look name it too; does the AI say yes when asked about that one food; and does the photo actually contain that food's colour (colour maths, no AI). About 1 s per food.")
                }
                Switch(crossCheck, { crossCheck = it })
            }
            stage?.let { st -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp); MutedText(st)
            } }
            aiNote?.let { InfoNote(it, icon = Icons.Outlined.AutoAwesome, tone = Tone.BLUE) }

            if (rows.isNotEmpty()) NilaCard(modifier = Modifier.fillMaxWidth()) {
                Text("In this meal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                rows.forEachIndexed { i, r ->
                    if (i > 0) RowDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(r.included, { rows[i] = r.copy(included = it) })
                        Column(Modifier.weight(1f)) {
                            Text(r.food?.name ?: "\"${r.text}\" (not in the food table)", style = MaterialTheme.typography.titleSmall)
                            r.evidence?.let { e ->
                                Text(if (e.fromColour) "Suggested by the colour maths" else e.verdict.label, style = MaterialTheme.typography.labelMedium,
                                    color = toneColors(when (e.verdict) { FoodCheck.Verdict.CONFIRMED -> Tone.TEAL; FoodCheck.Verdict.LIKELY -> Tone.BLUE; else -> Tone.PEACH }).ink)
                                MutedText(e.summary())
                            }
                            r.food?.let { f -> FoodSafety.cautions(f, ageDays).forEach { MutedText("Note: $it") } }
                        }
                        IconButton(onClick = { rows.removeAt(i) }) { Icon(Icons.Outlined.Close, "Remove") }
                    }
                    if (r.included) r.food?.let { f -> AmountStepper(f, r.units) { u -> rows[i] = r.copy(units = u) } }
                }
            }
            Heading("Add a food")
            FoodPicker(ageDays) { f, g -> rows.add(PlateRow(f, f.name, g / f.unitGrams)) }
            NilaCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Breastfed today", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(breastfed, { v -> scope.launch { dao.putSetting(SettingEntity("breastfed", v.toString())) } })
                }
                MutedText("Breast milk is one of WHO's eight food groups.")
            }
            PillButton("Save meal", enabled = rows.any { it.food != null && it.included }, icon = Icons.Outlined.Check, modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch {
                    val path = photo?.let { withContext(Dispatchers.IO) { SkinServices.photos.save(it, "meal") } }
                    val now = System.currentTimeMillis()
                    val mealId = dao.addMeal(MealEntity(timeMs = now, photoPath = path, aiRaw = aiRaw))
                    rows.filter { it.included }.mapNotNull { r -> r.food?.let { it to r.units * it.unitGrams } }.forEach { (f, g) ->
                        dao.addFood(FoodLogEntity(foodId = f.id, grams = g, timeMs = now, firstTime = allLogs.none { it.foodId == f.id }, mealId = mealId))
                    }
                    saved = true
                }
            })
        } else {
            InfoNote("Meal saved. New foods get a check-in on the Food screen.", icon = Icons.Outlined.CheckCircle, tone = Tone.TEAL)
            OutlinePillButton("Log another meal", onClick = { saved = false; photo = null; rows.clear(); aiNote = null; stage = null }, modifier = Modifier.fillMaxWidth())
        }

        // Today so far
        val items = todayLogs.mapNotNull { l -> db[l.foodId]?.let { PlateItem(it, l.grams) } } +
            if (!saved) rows.filter { it.included }.mapNotNull { r -> r.food?.let { PlateItem(it, r.units * it.unitGrams) } } else emptyList()
        DayNutrition(items, ageDays, breastfed, if (saved) "Today" else "Today, including this plate")
        NotADiagnosis()
    }
}

@Composable
fun DayNutrition(items: List<PlateItem>, ageDays: Int, breastfed: Boolean, title: String) {
    val db = SkinServices.foods
    val t = Nutrition.targets(ageDays)
    val total = Nutrition.total(items)
    Heading(title)
    NilaCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.PieChart, null, tint = toneColors(Tone.PEACH).ink)
            Text("Nutrients from food (${t.band})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Nutrition.lines(total, t).forEach { NutrientBar(it) }
        MutedText("Daily needs: ICMR-NIN 2020 RDA. Energy: what WHO says should come from food alongside breast milk. Food values: USDA FoodData Central. Milk feeds also count towards protein, calcium and vitamin A.")
    }
    val d = DietDiversity.of(items.map { it.food }, breastfed)
    NilaCard(modifier = Modifier.fillMaxWidth()) {
        Text("Food groups: ${d.count} of 8", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        MutedText(if (d.meets) "Meets WHO's minimum dietary diversity (5 of 8 groups) for today." else "WHO's minimum is 5 of 8 groups a day for 6-23 months.")
        FoodGroup.entries.forEach { g ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (g in d.groups) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
                    tint = if (g in d.groups) toneColors(Tone.TEAL).ink else MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
                Text(g.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (ageDays >= 182 && items.isNotEmpty()) {
        val lines = Nutrition.lines(total, t)
        val low = lines.filter { it.name != "Energy from food" }.minByOrNull { it.share }
        if (low != null && low.share < 0.6) {
            val pick: (Nutrients) -> Double = when (low.name) {
                "Iron" -> { n -> n.ironMg }; "Calcium" -> { n -> n.calciumMg }; "Zinc" -> { n -> n.zincMg }
                "Vitamin A" -> { n -> n.vitAUg }; "Vitamin C" -> { n -> n.vitCMg }; else -> { n -> n.proteinG }
            }
            val ideas = Nutrition.ideas(db, pick, ageDays)
            val label = if (low.name.startsWith("Vitamin")) low.name else low.name.lowercase()
            FactCard("Ideas for more $label", Icons.Outlined.Lightbulb, Tone.TEAL,
                ideas.map { "${it.name}: ${"%.1f".format(pick(it.nutrientsFor(it.typicalGrams)))} ${low.unit} per ${it.portionText(it.typicalGrams)}" } +
                    if (low.name == "Iron") listOf("Pair iron foods with vitamin C (guava, orange, tomato) to help absorption.") else emptyList(),
                footer = "Suggestions from the food table, filtered for your baby's age. ${Source.ICMR_NIN_2020.short}.")
        }
    }
}

@Composable
private fun NutrientBar(l: Nutrition.Line) {
    val tone = toneColors(if (l.share >= 1) Tone.TEAL else if (l.share >= 0.5) Tone.BLUE else Tone.PEACH)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(l.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("${fmt(l.got)} / ${fmt(l.need)} ${l.unit}", style = MaterialTheme.typography.labelLarge, color = tone.ink)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(tone.container)) {
            Box(Modifier.fillMaxWidth(l.share.coerceIn(0.0, 1.0).toFloat()).fillMaxHeight().clip(CircleShape).background(tone.ink))
        }
    }
}

private fun fmt(v: Double) = if (v >= 100) "%.0f".format(v) else "%.1f".format(v)

