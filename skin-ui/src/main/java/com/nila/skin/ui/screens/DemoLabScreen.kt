package com.nila.skin.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nila.skin.core.food.AllergyRules
import com.nila.skin.core.food.Exposure
import com.nila.skin.core.food.ReactionEvent
import com.nila.skin.core.food.ReactionPatterns
import com.nila.skin.core.food.Sign
import com.nila.skin.core.food.oneIn
import com.nila.skin.core.image.NCircle
import com.nila.skin.core.image.NPoint
import com.nila.skin.core.measure.EyeReflex
import com.nila.skin.core.measure.Mask
import com.nila.skin.core.measure.SkinMeasure
import com.nila.skin.core.measure.StoolCard
import com.nila.skin.core.measure.Trend
import com.nila.skin.core.measure.TrendPoint
import com.nila.skin.core.rules.Advice
import com.nila.skin.core.rules.Answer
import com.nila.skin.core.rules.BodyArea
import com.nila.skin.core.rules.ConcernKind
import com.nila.skin.core.rules.EyeRules
import com.nila.skin.core.rules.JaundiceRules
import com.nila.skin.core.rules.Reason
import com.nila.skin.core.rules.SkinRules
import com.nila.skin.core.rules.Source
import com.nila.skin.core.rules.Step
import com.nila.skin.core.rules.StoolRules
import com.nila.skin.core.synth.Synth
import com.nila.skin.core.vlm.Agreement
import com.nila.skin.core.vlm.VisionForms
import com.nila.skin.data.PhotoStore
import com.nila.skin.ui.SkinServices
import com.nila.skin.ui.components.AdviceCard
import com.nila.skin.ui.components.InfoNote
import com.nila.skin.ui.components.MarkablePhoto
import com.nila.skin.ui.components.Marker
import com.nila.skin.ui.components.MutedText
import com.nila.skin.ui.components.NilaCard
import com.nila.skin.ui.components.OutlinePillButton
import com.nila.skin.ui.components.PillButton
import com.nila.skin.ui.components.ScreenHero
import com.nila.skin.ui.components.SkinScaffold
import com.nila.skin.ui.components.StepPill
import com.nila.skin.ui.safely
import com.nila.skin.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.random.Random

/** One picture in a demo result, with what the maths drew on it. */
data class DemoImage(
    val bitmap: Bitmap, val label: String,
    val circles: List<NCircle> = emptyList(), val mask: Mask? = null, val dots: List<NPoint> = emptyList(),
)

data class DemoResult(val images: List<DemoImage>, val facts: List<String>, val advice: Advice? = null, val takeaway: String)

/** A real moment a parent has, and the code that answers it. */
data class DemoCase(
    val id: String, val title: String, val story: String, val shows: String,
    val route: String, val usesAi: Boolean = false, val run: suspend () -> DemoResult,
)

private const val DAY = 86_400_000L
private const val H = 3_600_000L

/**
 * The demo lab: every claim in the pitch, runnable in one tap.
 *
 * Each case is a moment from real life, run through exactly the same code as
 * the real screens (the maths, the rules and, where marked, the on-device AI),
 * on synthetic pictures whose true answer is known or on openly licensed
 * photos. Nothing on this screen is typed-in output.
 */
object DemoCases {

    private fun bmp(img: com.nila.skin.core.image.PixelImage) = PhotoStore.toBitmap(img)

    val all: List<DemoCase> = listOf(
        DemoCase("stool", "11 pm, dim bedroom: is this poo pale?",
            "A 3-week-old's nappy under a warm bedside lamp. To the eye it looks yellow-ish, so it feels fine.",
            "White-nappy correction of colour and brightness; Ontario stool card; CIEDE2000.", "stool") {
            val dimWarm = doubleArrayOf(0.4, 0.32, 0.22)
            val img = Synth.onNappy(400, StoolCard.byId("5").lab, dimWarm, seed = 21)
            val spot = NCircle(0.5, 0.5, 0.15)
            val raw = StoolCard.read(img, spot, correct = false)
            val fixed = StoolCard.read(img, spot)
            DemoResult(
                listOf(DemoImage(bmp(img), "The photo (true colour: card 5, pale sand)", listOf(spot), dots = fixed.whitePoints)),
                listOf(
                    "Without correction: nearest card colour ${raw.nearest.id} (${raw.nearest.label}) → ${raw.group.name.lowercase()}. That would have reassured the parent.",
                    "With the white nappy as reference (white dots): ${fixed.nearest.id} (${fixed.nearest.label}) → ${fixed.group.name.lowercase()}.",
                    "Lightness ${"%.0f".format(raw.stool.l)} → ${"%.0f".format(fixed.stool.l)}: a dim room makes pale poo look darker and more coloured.",
                ),
                StoolRules.advise(StoolRules.Input(fixed.nearest, fixed, 21)),
                "Pale poo is the first sign of biliary atresia, where surgery before 60 days matters. In our dim-light test the uncorrected reading got 8 of 13 card colours right; corrected, 13 of 13.",
            )
        },
        DemoCase("realmark", "A real strawberry mark: what the maths sees",
            "A parent photographs a raised red mark on their baby's arm (a real photo, Wikimedia Commons).",
            "The circle's ring is the baby's own skin; every pixel inside is compared with the skin expected at that point; the outline shows what was counted.", "skin") {
            val photo = SkinServices.photos.loadAsset("demo/skin/infantile_hemangioma.jpg")!!
            val circle = NCircle(0.56, 0.47, 0.2)
            val r = SkinMeasure.measure(PhotoStore.toPixels(photo), circle)
            val adv = SkinRules.advise(SkinRules.Input(ConcernKind.BIRTHMARK, BodyArea.ARMS_HANDS, 60,
                SkinRules.questions(ConcernKind.BIRTHMARK).associate { it.id to Answer.NO } + ("growing" to Answer.YES), r, null, firstPhoto = true))
            DemoResult(listOf(DemoImage(photo, "Real photo, measured outline in blue", listOf(circle), r.overlay)),
                r.words + listOf("Redness against the baby's own skin: Δa* ${"%+.1f".format(r.deltaA)}; lightness ΔL* ${"%+.1f".format(r.deltaL)}.",
                    "If the parent says it has grown and the baby is 2 months old, the AAP 2019 rule applies:"),
                adv,
                "A photo every few days makes \"has it grown?\" a measured answer. For marks that change in the first months, the AAP guideline says to be seen early, and accepts photos.")
        },
        DemoCase("tones", "Does it work on my baby's skin?",
            "The same red patch photographed on six skin tones, from very light to very dark.",
            "Measuring against the baby's own skin in a ring around the spot, not a population average.", "how") {
            val spot = Synth.Spot(0.5, 0.5, 0.12); val circle = NCircle(0.5, 0.5, 0.22)
            val trueCov = PI * 0.12 * 0.12 / (PI * 0.22 * 0.22)
            val rows = Synth.TONES.mapIndexed { i, (name, tone) ->
                val dA = Synth.rednessFor(tone, 14.0)
                val img = Synth.skin(320, tone, listOf(spot), deltaA = dA, seed = 300 + i)
                val r = SkinMeasure.measure(img, circle)
                Triple(name, img, "Tone $name: found ${"%.1f".format(r.coverage * 100)}% of the circle (true ${"%.1f".format(trueCov * 100)}%), ${r.spots} spot, redness Δa* ${"%.1f".format(r.deltaA)} (true ${"%.1f".format(dA)})")
            }
            DemoResult(listOf(0, 3, 5).map { DemoImage(bmp(rows[it].second), "Tone ${rows[it].first}", listOf(circle)) }, rows.map { it.third }, null,
                "Skin-disease AI trained on mostly light skin loses 27-36% accuracy on dark skin (DDI, 2022). A difference from the same baby's skin a centimetre away means the same on every tone.")
        },
        DemoCase("light", "A brown mark, photographed by a window",
            "Light from one side makes one half of the arm brighter. The parent wants to track a flat brown mark.",
            "A plane fitted to the surrounding skin (least squares, outliers trimmed) gives the expected skin colour at every pixel.", "skin") {
            val tone = Synth.TONES[3].second
            val circle = NCircle(0.5, 0.5, 0.22)
            val img = Synth.skin(400, tone, listOf(Synth.Spot(0.5, 0.5, 0.12)), deltaA = 1.0, deltaL = -12.0, seed = 31, gradient = 0.4)
            val plane = SkinMeasure.measure(img, circle)
            val flat = SkinMeasure.measure(img, circle, lightingModel = false)
            DemoResult(
                listOf(DemoImage(bmp(img), "Lighting model: ${"%.0f".format(plane.coverage * 100)}% found", listOf(circle), plane.overlay),
                    DemoImage(bmp(img), "One flat skin colour: ${"%.0f".format(flat.coverage * 100)}% found", listOf(circle), flat.overlay)),
                listOf(
                    "True size: 30% of the circle, darker by 12 L*.",
                    "With the lighting model: ${"%.1f".format(plane.coverage * 100)}%, ${plane.spots} spot, \"${plane.words.first()}\".",
                    "With one flat skin colour: ${"%.1f".format(flat.coverage * 100)}% — the light slope across the ring hides the mark.",
                    "Light slope in the surrounding skin: ${"%.1f".format(plane.lightSlope)} L* per circle radius.",
                ), null,
                "Same result on all six tones in the unit tests: the flat reference missed the mark every time, the plane found it every time.")
        },
        DemoCase("eye", "Grandma's flash photo: one eye glows white",
            "A family photo with the flash on. One pupil looks white or yellow instead of red.",
            "MediaPipe face landmarks find the pupils (GPU), the reflex is read by contrast with the iris, and gaze is checked for the off-angle false alarm.", "eyes") {
            val photo = SkinServices.photos.loadAsset("demo/eyes/coats_disease_camera_with_flash.jpg")!!
            val eyes = SkinServices.eyes.find(photo)
            val left = eyes?.left ?: NCircle(0.4, 0.45, 0.03); val right = eyes?.right ?: NCircle(0.6, 0.45, 0.03)
            val pair = EyeReflex.readPair(PhotoStore.toPixels(photo, 1600), left, right)
            val angle = eyes?.angle
            DemoResult(
                listOf(DemoImage(cropAround(photo, NCircle((left.cx + right.cx) / 2, (left.cy + right.cy) / 2, kotlin.math.abs(right.cx - left.cx) * 0.8), 1.0), "Real photo (Wikimedia Commons, Coats disease)")),
                listOfNotNull(
                    if (eyes != null) "Face landmarks found both pupils on the ${SkinServices.eyes.delegate}." else "No face found; default circles used.",
                    "Left in photo: ${pair.left.reflex.word}. Right in photo: ${pair.right.reflex.word}.",
                    angle?.let { "${it.words()} (gaze ${"%+.2f".format(it.gazeLeft)} / ${"%+.2f".format(it.gazeRight)}, head turn ${"%+.2f".format(it.headTurn)})." },
                ),
                EyeRules.advise(EyeRules.Input(pair, if (pair.left.reflex.name == "WHITE") 1 else 0, if (pair.right.reflex.name == "WHITE") 1 else 0, emptyMap(), angle)),
                "Baylor's CRADLE study saw white-eye in family photos for 80% of affected children, about 1.3 years before diagnosis. One photo means retake; the same eye white twice means a red reflex check.")
        },
        DemoCase("trend", "Is the rash spreading? One photo was taken from further away",
            "Four daily photos of tummy spots. On day 1 the parent stood back, so the spots look smaller in that photo.",
            "Theil–Sen: the median slope through every pair of photos, so one bad photo can't swing the answer.", "skin") {
            val tone = Synth.TONES[2].second
            val rnd = Random(5)
            val spots = List(14) { Synth.Spot(0.5 + (rnd.nextDouble() - 0.5) * 0.34, 0.5 + (rnd.nextDouble() - 0.5) * 0.34, 0.014 + rnd.nextDouble() * 0.01) }
            val counts = listOf(5, 7, 10, 14)
            val circle = NCircle(0.5, 0.5, 0.27)
            val readings = counts.mapIndexed { i, n -> SkinMeasure.measure(Synth.skin(320, tone, spots.take(n), deltaA = Synth.rednessFor(tone, 15.0), seed = 40 + i), circle) }
            // Day 1 framed from further away: the same spots cover a smaller share of the photo.
            val sizes = readings.mapIndexed { i, r -> if (i == 0) r.areaOfPhoto * 0.45 else r.areaOfPhoto }
            val pts = readings.mapIndexed { i, r -> TrendPoint(i * DAY, sizes[i], false, r.rednessScore, r.spots) }
            val t = Trend.summarise(pts)
            val firstLast = sizes.last() / sizes.first()
            val adv = SkinRules.advise(SkinRules.Input(ConcernKind.RASH, BodyArea.CHEST_BELLY, 200, SkinRules.questions(ConcernKind.RASH).associate { it.id to Answer.NO }, readings.last(), t))
            DemoResult(
                listOf(0, 3).map { i -> DemoImage(bmp(Synth.skin(240, tone, spots.take(counts[i]), deltaA = Synth.rednessFor(tone, 15.0), seed = 40 + i)), "Day ${i + 1}: ${readings[i].spots} spots", listOf(circle)) },
                listOf(
                    "First vs last alone: ${"%.1f".format(firstLast)}× — exaggerated by the far-away first photo.",
                    "Robust line through all four: ${"%.0f".format(((t.sizePerDay ?: 1.0) - 1) * 100)}% bigger per day, ${"%.1f".format(t.sizeRatio ?: 1.0)}× over 3 days.",
                    t.sentence,
                ), adv,
                "\"Is it spreading?\" is the question doctors ask and parents can't answer from memory. Growth of 1.5× or more within 4 days raises the step to \"show your doctor this week\" — the only way a photo trend can raise advice.")
        },
        DemoCase("food", "Hives twice after egg — but khichdi is on the table every day",
            "Ten days of a normal Indian weaning diary. Egg yolk three times; red bumps round the mouth after two of them.",
            "Exposure windows (2 h for hives, 48 h for eczema), the half rule, and Fisher's exact test.", "food") {
            val now = 20 * DAY
            val ex = ArrayList<Exposure>()
            for (d in 10 downTo 1) listOf("ragi_porridge", "khichdi", "banana", "curd").forEachIndexed { i, f -> ex += Exposure(f, now - d * DAY + (8 + i * 3) * H, d == 10) }
            listOf(9, 6, 3).forEachIndexed { i, d -> ex += Exposure("egg_yolk", now - d * DAY + 10 * H, i == 0) }
            val re = listOf(ReactionEvent(now - 9 * DAY + 11 * H, setOf(Sign.HIVES)), ReactionEvent(now - 6 * DAY + 11 * H, setOf(Sign.HIVES)))
            val p = ReactionPatterns.analyse(ex, re)
            val name: (String) -> String = { SkinServices.foods[it]?.name ?: it }
            val early = ReactionPatterns.analyse(ex.filter { it.timeMs <= now - 6 * DAY + 12 * H && it.timeMs >= now - 9 * DAY }, re)
            DemoResult(emptyList(),
                p.filter { it.followed > 0 }.map { "${name(it.foodId)}: ${it.followed} of ${it.exposures} times followed → ${it.strength.name.lowercase().replace('_', ' ')}" +
                    if (it.strength != ReactionPatterns.Pattern.Strength.ONE_OFF) " (chance alone: ${oneIn(it.chance)})" else "" } +
                    listOf("Khichdi was eaten before both reactions too, but after only 2 of its 10 servings, so the half rule doesn't blame it.",
                        "After just 4 days of logs, the same egg pattern reads \"${early.first { it.foodId == "egg_yolk" }.strength.name.lowercase()}\" (chance ${oneIn(early.first { it.foodId == "egg_yolk" }.chance)}): Nila waits for evidence."),
                AllergyRules.advise(re.last(), p, name),
                "Patterns are evidence to show a doctor, never a diagnosis of allergy. Wrongly cutting out everyday foods narrows a baby's diet, so Nila is careful about blame.")
        },
        DemoCase("yellow", "A 3-day-old looks yellow — tonight or tomorrow?",
            "Day 3 after birth. The eyes look a little yellow. The parent checks the soles in daylight.",
            "The WHO/India IMNCI young-infant jaundice rows, answered by the parent. No bilirubin guess from the camera.", "yellow") {
            val eyesOnly = JaundiceRules.advise(JaundiceRules.Input(77, mapOf("eyes" to Answer.YES, "palms" to Answer.NO), true, emptyList()))
            val soles = JaundiceRules.advise(JaundiceRules.Input(77, mapOf("eyes" to Answer.YES, "palms" to Answer.YES), true, emptyList()))
            DemoResult(emptyList(),
                listOf("Yellow eyes, palms and soles normal → ${eyesOnly.heading}.", "Yellow soles as well → ${soles.heading}. Rule: ${soles.reasons.first().text}"),
                soles,
                "Only a checklist answer can say \"see a doctor now\". Camera jaundice apps missed a third of high bilirubin levels in dark-skinned newborns (Picterus, Botswana), so Nila keeps photos as a record, not a score.")
        },
        DemoCase("ai", "The AI says \"blisters\" — the parent looks and says no",
            "The on-device AI describes possible blisters on a rash. What happens to the advice?",
            "The AI describes; it can prompt a closer look but can't raise the step. The parent's answer decides.", "skin") {
            val tone = Synth.TONES[2].second
            val circle = NCircle(0.5, 0.5, 0.22)
            val r = SkinMeasure.measure(Synth.skin(320, tone, listOf(Synth.Spot(0.5, 0.5, 0.12)), deltaA = 12.0, seed = 9), circle)
            val ai = VisionForms.parseSkin("COLOUR: red\nSURFACE: raised bumps, blisters\nPATTERN: patch\nPHOTO: clear")
            val base = SkinRules.questions(ConcernKind.RASH).associate { it.id to Answer.NO }
            val no = SkinRules.advise(SkinRules.Input(ConcernKind.RASH, BodyArea.ARMS_HANDS, 200, base, r, null, firstPhoto = true))
            val yes = SkinRules.advise(SkinRules.Input(ConcernKind.RASH, BodyArea.ARMS_HANDS, 200, base + ("blisters" to Answer.YES), r, null, firstPhoto = true))
            val agree = Agreement.skin(r, ai)
            DemoResult(emptyList(),
                listOf("AI description: colour ${ai.colour}, surface ${ai.surfaces.joinToString { it.label.lowercase() }}, pattern ${ai.pattern}.",
                    "${agree.level.title}: ${agree.points.joinToString(" ")}",
                    "The blisters question shows the AI's note and asks the parent to look closely.",
                    "Parent answers no → ${no.heading}. Parent answers yes → ${yes.heading} (${yes.reasons.first().source?.short}).",
                ), no,
                "A 2-billion-parameter model on a phone will sometimes be wrong. Here that can only cost a closer look, never a false alarm or a missed one.")
        },
        DemoCase("plate", "What did my baby actually eat this week?",
            "A parent made three purées for the week and photographs the jars.",
            "Intern3.5-VL-2B on the NPU suggests foods; each is cross-checked three ways (two differently worded looks, a yes/no question, and colour maths with no AI); nutrients from USDA against ICMR-NIN 2020.", "meal", usesAi = true) {
            val photo = SkinServices.photos.loadAsset("demo/meal/pureed_veggies.jpg")!!
            val r = com.nila.skin.ui.PlateAI.look(photo)
                ?: return@DemoCase DemoResult(listOf(DemoImage(photo, "Jars of vegetable purée (Wikimedia Commons)")),
                    listOf("The on-device model isn't available on this phone, so the parent would add foods by hand. Everything else still works."), null,
                    "The app runs fully without the model.")
            val kept = r.evidence.filter { it.keep }
            val items = kept.mapNotNull { e -> e.food?.let { com.nila.skin.core.food.PlateItem(it, it.typicalGrams * 2) } }
            val total = com.nila.skin.core.food.Nutrition.total(items)
            val t = com.nila.skin.core.food.Nutrition.targets(240)
            DemoResult(listOf(DemoImage(photo, "Jars of vegetable purée (Wikimedia Commons)")),
                listOf("AI suggested ${r.evidence.size} foods in ${"%.1f".format(r.listMs / 1000.0)} s${if (r.listMs > 8000) " (includes loading the model)" else ""}; cross-checks took ${"%.1f".format(r.checkMs / 1000.0)} s.") +
                    r.evidence.map { "${it.food?.name ?: it.text}: ${it.verdict.label.lowercase()} — ${it.summary()}" } +
                    listOf("Pre-ticked for the parent: ${kept.joinToString { it.food?.name ?: it.text }.ifEmpty { "none" }}.",
                        "For a typical 8-month portion of those: vitamin A ${"%.0f".format(total.vitAUg)} of ${"%.0f".format(t.vitAUg)} µg, iron ${"%.1f".format(total.ironMg)} of ${"%.0f".format(t.ironMg)} mg (ICMR-NIN 2020)."),
                null,
                "The AI only saves typing. Three independent checks decide what is pre-ticked, and the parent confirms. On ten real baby-food photos the cross-check is measured on the phone: How Nila decides → Test the on-device AI.")
        },
        DemoCase("glass", "Spots and a fever at night",
            "An 8-month-old has a fever and a new rash. The parent presses a glass on the spots and can still see them.",
            "The NHS glass test, answered by the parent. Nila deliberately doesn't judge it from a photo.", "glass") {
            val adv = SkinRules.advise(SkinRules.Input(ConcernKind.RASH, BodyArea.CHEST_BELLY, 240,
                mapOf("glass" to Answer.YES, "fever" to Answer.YES), null, null, firstPhoto = true))
            DemoResult(emptyList(), listOf("Glass test: spots still visible. Fever: yes.", "Every reason that fired is shown with its guideline."), adv,
                "The answer is one calm, clear next step with its guideline. Whether spots fade under a glass is decided by a person looking, not by the camera.")
        },
    )
}

@Composable
fun DemoLabScreen(go: (String) -> Unit, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val results = remember { mutableStateMapOf<String, DemoResult?>() }
    val running = remember { mutableStateMapOf<String, Boolean>() }
    var all by remember { mutableStateOf(false) }

    suspend fun run(c: DemoCase) {
        running[c.id] = true
        results[c.id] = safely("demo ${c.id}") { withContext(Dispatchers.Default) { c.run() } }
            ?: DemoResult(emptyList(), listOf("This demo couldn't run on this phone."), null, "")
        running[c.id] = false
    }

    SkinScaffold("Demo lab", onBack = back) {
        ScreenHero("Real moments, one tap each", "Every case runs the same maths, rules and on-device AI as the real screens, on pictures whose true answer is known or on openly licensed photos.")
        PillButton(if (all) "Running..." else "Run every offline demo", enabled = !all, icon = Icons.Outlined.PlayArrow, modifier = Modifier.fillMaxWidth(), onClick = {
            all = true
            scope.launch { DemoCases.all.filter { !it.usesAi }.forEach { run(it) }; all = false }
        })
        DemoCases.all.forEachIndexed { i, c ->
            NilaCard(modifier = Modifier.fillMaxWidth(), tone = if (c.usesAi) Tone.BLUE else null) {
                Text("${i + 1}. ${c.title}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(c.story, style = MaterialTheme.typography.bodyMedium)
                MutedText("How: ${c.shows}")
                val r = results[c.id]
                if (running[c.id] == true) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    MutedText(if (c.usesAi) "Running on the NPU (the first run loads the model, 10-20 s)..." else "Running...")
                } else if (r == null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Run", icon = if (c.usesAi) Icons.Outlined.AutoAwesome else Icons.Outlined.Science, onClick = { scope.launch { run(c) } }, modifier = Modifier.weight(1f))
                    OutlinePillButton("Open screen", icon = Icons.Outlined.ArrowForward, onClick = { go(c.route) }, modifier = Modifier.weight(1f))
                }
                r?.let { DemoResultView(it) }
                if (r != null) OutlinePillButton("Try it on the real screen", icon = Icons.Outlined.ArrowForward, onClick = { go(c.route) }, modifier = Modifier.fillMaxWidth())
            }
        }
        InfoNote("Synthetic pictures test the maths against a known answer; they are not clinical validation. Real photos are credited in the app's demo folder.", tone = Tone.SLATE)
    }
}

@Composable
private fun DemoResultView(r: DemoResult) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (r.images.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            r.images.forEach { im ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MarkablePhoto(im.bitmap, im.circles.map { Marker(it, Color(0xFF3DDBB0), "") }, -1, onMove = { _, _ -> }, mask = im.mask, showSlider = false, dots = im.dots)
                    Text(im.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        r.facts.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        r.advice?.let { AdviceCard(it) }
        if (r.takeaway.isNotBlank()) InfoNote(r.takeaway, tone = Tone.TEAL, title = "Why it matters")
    }
}
