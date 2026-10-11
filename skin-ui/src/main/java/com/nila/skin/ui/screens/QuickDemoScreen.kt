package com.nila.skin.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nila.skin.core.color.ColorMath
import com.nila.skin.core.food.AllergyRules
import com.nila.skin.core.food.DietDiversity
import com.nila.skin.core.food.Exposure
import com.nila.skin.core.food.Nutrition
import com.nila.skin.core.food.PlateItem
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
import com.nila.skin.core.measure.TextureMap
import com.nila.skin.core.measure.Trend
import com.nila.skin.core.measure.TrendPoint
import com.nila.skin.core.measure.YellowReading
import com.nila.skin.core.measure.Yellowness
import com.nila.skin.core.synth.Synth
import com.nila.skin.data.PhotoStore
import com.nila.skin.data.SettingEntity
import com.nila.skin.ui.PlateAI
import com.nila.skin.ui.SkinServices
import com.nila.skin.ui.components.MarkablePhoto
import com.nila.skin.ui.components.Marker
import com.nila.skin.ui.components.MutedText
import com.nila.skin.ui.components.NilaCard
import com.nila.skin.ui.components.OutlinePillButton
import com.nila.skin.ui.components.SkinScaffold
import com.nila.skin.ui.safely
import com.nila.skin.ui.theme.Tone
import com.nila.skin.ui.theme.toneColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI

/** One picture panel: a photo with what the maths drew on it, or a set of colour chips. */
data class QPanel(
    val label: String,
    val bitmap: Bitmap? = null,
    val circles: List<NCircle> = emptyList(),
    val mask: Mask? = null,
    val dots: List<NPoint> = emptyList(),
    val chips: List<DemoChip> = emptyList(),
    val pixelated: Boolean = false,
)

/** A food the AI suggested, and how the three checks came out. */
data class QFood(val name: String, val kept: Boolean, val fromColour: Boolean, val checks: String)

data class QSection(
    val title: String,
    val panels: List<QPanel> = emptyList(),
    val result: List<String> = emptyList(),
    val foods: List<Pair<QPanel, List<QFood>>> = emptyList(),
    val bars: List<Triple<String, Double, Double>> = emptyList(),
)

/**
 * The quick demo: every feature as input → output, computed in the
 * background as soon as the app starts, so the page just shows it.
 * The food AI runs once on the NPU and its result is kept on the phone.
 */
object QuickDemo {
    val sections = MutableStateFlow<List<QSection>?>(null)
    val foods = MutableStateFlow<QSection?>(null)
    val foodRunning = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private const val FOOD_KEY = "quick_food_v1"
    val MEALS = listOf("demo/meal/mashed_banana.jpg" to "Mashed banana", "demo/meal/pureed_veggies.jpg" to "Jars of purée", "demo/meal/ragi_porridge.jpg" to "Ragi porridge")

    fun warm() {
        if (sections.value != null) return
        scope.launch {
            sections.value = safely("quick demo") { build() } ?: emptyList()
            loadFoods()
        }
    }

    private fun asset(p: String) = SkinServices.photos.loadAsset(p)
    private fun bmp(img: com.nila.skin.core.image.PixelImage) = PhotoStore.toBitmap(img)

    private fun skin(title: String, path: String, c: NCircle, short: (com.nila.skin.core.measure.SkinReading) -> List<String>): QSection? {
        val photo = asset(path) ?: return null
        val r = SkinMeasure.measure(PhotoStore.toPixels(photo), c)
        return QSection(title, listOf(QPanel("Photo", photo), QPanel("What the maths found", photo, listOf(c), r.overlay)), short(r))
    }

    private fun stoolChips(img: com.nila.skin.core.image.PixelImage, spot: NCircle): Pair<StoolCard.Reading, List<DemoChip>> {
        val raw = StoolCard.read(img, spot, correct = false)
        val fixed = StoolCard.read(img, spot)
        return fixed to listOf(DemoChip(ColorMath.toArgb(raw.stool), "Photo"), DemoChip(ColorMath.toArgb(fixed.stool), "Corrected"), DemoChip(fixed.nearest.argb, "Card ${fixed.nearest.id}"))
    }

    private fun build(): List<QSection> = buildList {
        // ---- Skin -----------------------------------------------------------
        skin("Birthmark", "demo/skin/infantile_hemangioma.jpg", NCircle(0.56, 0.47, 0.2)) { r ->
            listOf("Redder Δa* ${"%+.0f".format(r.deltaA)} · darker ΔL* ${"%+.0f".format(r.deltaL)} · one patch, ${(r.coverage * 100).toInt()}% of circle")
        }?.let(::add)
        skin("Rash spots", "demo/skin/irritant_diaper_dermatitis.jpg", NCircle(0.7, 0.5, 0.12)) { r ->
            listOf("${r.spots} spots · redder Δa* ${"%+.0f".format(r.deltaA)}")
        }?.let(::add)
        skin("Blue-grey patch", "demo/skin/real_mongolian_spot.jpg", NCircle(0.66, 0.43, 0.1)) { r ->
            listOf("Blue-grey tone (Δa* ${"%+.0f".format(r.deltaA)}) · darker ΔL* ${"%+.0f".format(r.deltaL)}")
        }?.let(::add)
        asset("demo/skin/real_cradle_cap.jpg")?.let { photo ->
            val px = PhotoStore.toPixels(photo)
            val r = SkinMeasure.measure(px, NCircle(0.62, 0.55, 0.24))
            add(QSection("Flaky scalp", listOf(QPanel("Photo", photo), QPanel("Texture map", bmp(TextureMap.of(px)))),
                listOf("Same colour as the skin around it · texture ${"%.1f".format(r.textureRatio)}× rougher")))
        }

        // ---- Nappy ------------------------------------------------------------
        asset("demo/stool/real_yellow_seedy.jpg")?.let { photo ->
            val img = PhotoStore.toPixels(photo)
            val (r, chips) = stoolChips(img, StoolCard.suggestSpot(img) ?: NCircle(0.5, 0.5, 0.12))
            val night = Synth.onNappy(320, StoolCard.byId("5").lab, doubleArrayOf(0.4, 0.32, 0.22), seed = 21)
            val (n, nightChips) = stoolChips(night, NCircle(0.5, 0.5, 0.15))
            add(QSection("Poo colour", listOf(QPanel("Day nappy", photo, pixelated = true), QPanel("", chips = chips),
                QPanel("Night, under a lamp (test pattern)", bmp(night), pixelated = true), QPanel("", chips = nightChips)),
                listOf("Day: card ${r.nearest.id}, normal", "Night: looks normal, corrected card ${n.nearest.id}: pale → doctor today")))
        }

        // ---- Eyes ---------------------------------------------------------------
        val eyePanels = ArrayList<QPanel>()
        for ((path, label) in listOf("demo/eyes/red_eye_effect_2.jpg" to "Usual", "demo/eyes/coats_disease_camera_with_flash.jpg" to "Rare")) {
            val photo = asset(path) ?: continue
            val e = runCatching { SkinServices.eyes.find(photo) }.getOrNull()
            val l = e?.left ?: NCircle(0.4, 0.45, 0.03); val rr = e?.right ?: NCircle(0.6, 0.45, 0.03)
            val p = EyeReflex.readPair(PhotoStore.toPixels(photo, 1600), l, rr)
            val (crop, cs) = zoom(photo, listOf(l, rr))
            val angle = e?.angle?.let { if (it.straight) "straight" else "turned" } ?: "no face"
            eyePanels += QPanel("$label: ${short(p.left.reflex.name)} · ${short(p.right.reflex.name)} · $angle", crop, cs)
        }
        if (eyePanels.isNotEmpty()) add(QSection("Eye flash", eyePanels, listOf("One white photo → take 2-3 more")))

        // ---- Yellow ---------------------------------------------------------------
        asset("demo/yellow/real_yellow_eye_whites.jpg")?.let { photo ->
            val c = runCatching { SkinServices.eyes.sclera(photo) }.getOrNull() ?: Yellowness.suggestSpot(PhotoStore.toPixels(photo, 600)) ?: NCircle(0.5, 0.5, 0.05)
            val r = Yellowness.read(PhotoStore.toPixels(photo), YellowReading.Site.EYE_WHITE, c)
            add(QSection("Yellow eyes", listOf(QPanel("Photo", photo), QPanel("Eye white + white reference", photo, listOf(c), dots = r.whitePoints.filterIndexed { i, _ -> i % 3 == 0 })),
                listOf("${r.looks} · b* ${"%.0f".format(r.yellowness)} · not a bilirubin level")))
        }

        // ---- Fairness and light ------------------------------------------------------
        val spot = Synth.Spot(0.5, 0.5, 0.12); val circle = NCircle(0.5, 0.5, 0.22)
        val trueCov = PI * 0.12 * 0.12 / (PI * 0.22 * 0.22)
        val tones = Synth.TONES.mapIndexed { i, (name, tone) ->
            val img = Synth.skin(200, tone, listOf(spot), deltaA = Synth.rednessFor(tone, 14.0), seed = 300 + i)
            val r = SkinMeasure.measure(img, circle)
            QPanel("$name · ${"%.0f".format(r.coverage * 100)}%", bmp(img), listOf(circle), r.overlay) to r.coverage
        }
        add(QSection("Every skin tone", tones.map { it.first },
            listOf("Found ${"%.1f".format(tones.minOf { it.second } * 100)}–${"%.1f".format(tones.maxOf { it.second } * 100)}% · true ${"%.1f".format(trueCov * 100)}%")))
        val tone = Synth.TONES[3].second
        val side = Synth.skin(260, tone, listOf(spot), deltaA = 1.0, deltaL = -12.0, seed = 31, gradient = 0.4)
        val plane = SkinMeasure.measure(side, circle); val flat = SkinMeasure.measure(side, circle, lightingModel = false)
        add(QSection("Window light", listOf(QPanel("With lighting model", bmp(side), listOf(circle), plane.overlay), QPanel("Without", bmp(side), listOf(circle), flat.overlay)),
            listOf("Brown mark found ${"%.0f".format(plane.coverage * 100)}% vs ${"%.0f".format(flat.coverage * 100)}% (true 30%)")))

        // ---- Trend --------------------------------------------------------------------
        val tone3 = Synth.TONES[2].second
        val rnd = kotlin.random.Random(42)
        val all = List(16) { Synth.Spot(0.5 + (rnd.nextDouble() - 0.5) * 0.36, 0.5 + (rnd.nextDouble() - 0.5) * 0.36, 0.014 + rnd.nextDouble() * 0.012) }
        val c3 = NCircle(0.5, 0.5, 0.27)
        val days = listOf(5, 10, 16).mapIndexed { i, n ->
            val img = Synth.skin(220, tone3, all.take(n), deltaA = Synth.rednessFor(tone3, 15.0), deltaL = -3.0, seed = 20 + i)
            val r = SkinMeasure.measure(img, c3)
            Triple(img, r, i)
        }
        val t = Trend.summarise(days.map { (_, r, i) -> TrendPoint(i * 86_400_000L, r.areaOfPhoto, false, r.rednessScore, r.spots) })
        add(QSection("Spreading or settling (simulated)", days.map { (img, r, i) -> QPanel("Day ${i + 1}: ${r.spots} spots", bmp(img), listOf(c3), r.overlay) },
            listOf("${"%.0f".format(((t.sizePerDay ?: 1.0) - 1) * 100)}% bigger per day → doctor this week")))

        // ---- Food reactions and nutrition ------------------------------------------------
        val now = 20 * 86_400_000L; val h = 3_600_000L
        val ex = ArrayList<Exposure>()
        for (d in 10 downTo 1) listOf("ragi_porridge", "khichdi", "banana", "curd").forEachIndexed { i, f -> ex += Exposure(f, now - d * 86_400_000L + (8 + i * 3) * h, d == 10) }
        listOf(9, 6, 3).forEachIndexed { i, d -> ex += Exposure("egg_yolk", now - d * 86_400_000L + 10 * h, i == 0) }
        val re = listOf(ReactionEvent(now - 9 * 86_400_000L + 11 * h, setOf(Sign.HIVES)), ReactionEvent(now - 6 * 86_400_000L + 11 * h, setOf(Sign.HIVES)))
        val pats = ReactionPatterns.analyse(ex, re).filter { it.followed > 0 }
        add(QSection("Food & reactions", result = pats.map { p ->
            "${SkinServices.foods[p.foodId]?.name?.substringBefore(" (")}: ${p.followed} of ${p.exposures} → ${p.strength.name.lowercase().replace('_', ' ')}" +
                if (p.strength == ReactionPatterns.Pattern.Strength.PATTERN) " (chance ${oneIn(p.chance)})" else ""
        }))
        val db = SkinServices.foods
        val items = listOf("ragi_porridge" to 45.0, "khichdi" to 60.0, "curd" to 30.0, "banana" to 50.0, "dal" to 30.0, "carrot" to 30.0).mapNotNull { (id, g) -> db[id]?.let { PlateItem(it, g) } }
        val total = Nutrition.total(items); val tg = Nutrition.targets(245)
        val groups = DietDiversity.of(items.map { it.food }, true)
        add(QSection("A day's food (8 months)", bars = listOf(
            Triple("Iron", total.ironMg, tg.ironMg), Triple("Protein", total.proteinG, tg.proteinG), Triple("Vitamin A", total.vitAUg, tg.vitAUg),
            Triple("Food groups", groups.count.toDouble(), 8.0)),
            result = listOf("ICMR-NIN 2020 needs · WHO food groups")))
    }

    /** A crop around both eyes, with the pupil circles moved into the crop's coordinates. */
    private fun zoom(b: Bitmap, cs: List<NCircle>): Pair<Bitmap, List<NCircle>> {
        val w = b.width.toDouble(); val h = b.height.toDouble(); val short = minOf(w, h)
        val xs = cs.map { it.cx * w }; val ys = cs.map { it.cy * h }
        val half = ((xs.max() - xs.min()) * 0.85).coerceAtLeast(short * 0.12)
        val mx = xs.average(); val my = ys.average()
        val x0 = (mx - half).coerceIn(0.0, w - 1); val y0 = (my - half * 0.6).coerceIn(0.0, h - 1)
        val x1 = (mx + half).coerceIn(x0 + 1, w); val y1 = (my + half * 0.6).coerceIn(y0 + 1, h)
        val crop = Bitmap.createBitmap(b, x0.toInt(), y0.toInt(), (x1 - x0).toInt(), (y1 - y0).toInt())
        val cw = crop.width.toDouble(); val ch = crop.height.toDouble()
        return crop to cs.map { c -> NCircle((c.cx * w - x0) / cw, (c.cy * h - y0) / ch, c.r * short / minOf(cw, ch)) }
    }

    private fun short(reflex: String) = when (reflex) { "RED" -> "red"; "WHITE" -> "white"; "DARK" -> "dark"; else -> "unclear" }

    // ---- Food AI: run once on the NPU, then kept --------------------------------------
    private suspend fun loadFoods() {
        val json = SkinServices.dao.setting(FOOD_KEY) ?: return
        foods.value = safely("food cache") { parse(JSONArray(json)) }
    }

    private fun parse(a: JSONArray): QSection {
        val list = (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val photo = asset(o.getString("asset")) ?: return@mapNotNull null
            val rows = o.getJSONArray("rows")
            QPanel(o.getString("label"), photo) to (0 until rows.length()).map { k ->
                val r = rows.getJSONObject(k); QFood(r.getString("name"), r.getBoolean("kept"), r.getBoolean("colour"), r.getString("checks"))
            }
        }
        return QSection("Food photo: AI + three checks", foods = list)
    }

    fun runFoods() {
        if (foodRunning.value) return
        foodRunning.value = true
        scope.launch {
            val arr = JSONArray()
            for ((path, label) in MEALS) {
                val photo = asset(path) ?: continue
                val r = PlateAI.look(photo) ?: break
                val rows = JSONArray()
                r.evidence.forEach { e ->
                    fun m(b: Boolean?) = when (b) { true -> "✓"; false -> "✗"; null -> "–" }
                    rows.put(JSONObject().put("name", (e.food?.name ?: e.text).substringBefore(" (")).put("kept", e.keep).put("colour", e.fromColour)
                        .put("checks", if (e.fromColour) "colour" else "${m(e.secondLook)} ${m(e.yesNo)} ${m(e.colour?.found)}"))
                }
                arr.put(JSONObject().put("asset", path).put("label", label).put("rows", rows))
            }
            if (arr.length() > 0) {
                SkinServices.dao.putSetting(SettingEntity(FOOD_KEY, arr.toString()))
                foods.value = parse(arr)
            }
            foodRunning.value = false
        }
    }
}

@Composable
fun QuickDemoScreen(back: () -> Unit) {
    val sections by QuickDemo.sections.collectAsState()
    val food by QuickDemo.foods.collectAsState()
    val running by QuickDemo.foodRunning.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { QuickDemo.warm() }
    SkinScaffold("Quick demo", onBack = back) {
        val list = sections
        if (list == null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp); MutedText("Preparing...")
        } else {
            list.take(4).forEach { SectionView(it) }
            list.drop(4).take(3).forEach { SectionView(it) }
            // Food AI after the eyes and yellow check.
            val f = food
            NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 10.dp) {
                Title("Food photo: AI + three checks")
                if (f == null) {
                    MutedText("Runs once on the NPU, then stays on the phone.")
                    if (running) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp); MutedText("Running on the NPU...")
                    } else OutlinePillButton("Run the food AI", icon = Icons.Outlined.AutoAwesome, onClick = { QuickDemo.runFoods() }, modifier = Modifier.fillMaxWidth())
                } else {
                    MutedText("✓ ✗ = two looks agree · yes/no · colour match")
                    f.foods.forEach { (panel, rows) -> FoodRow(panel, rows) }
                }
            }
            list.drop(7).forEach { SectionView(it) }
        }
    }
}

@Composable
private fun Title(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

@Composable
private fun SectionView(s: QSection) {
    NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 10.dp) {
        Title(s.title)
        val many = s.panels.size > 2 && s.panels.all { it.chips.isEmpty() }
        val perRow = if (s.panels.size >= 6) 3 else if (many) 3 else 2
        s.panels.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Colour chips get more room than the small mosaic beside them.
                row.forEach { p -> Box(Modifier.weight(if (p.chips.isNotEmpty()) 2.2f else 1f)) { PanelView(p) } }
                repeat(perRow - row.size) { Box(Modifier.weight(1f)) {} }
            }
        }
        s.bars.forEach { (name, got, need) -> Bar(name, got, need) }
        s.result.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }
    }
}

@Composable
private fun PanelView(p: QPanel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            p.chips.isNotEmpty() -> ColourChips(p.chips)
            p.pixelated && p.bitmap != null -> {
                val small = androidx.compose.runtime.remember(p.bitmap) { Bitmap.createScaledBitmap(p.bitmap, 9, 9, true) }
                Image(small.asImageBitmap(), null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.None,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
            }
            p.bitmap != null -> MarkablePhoto(p.bitmap, p.circles.map { Marker(it, Color(0xFF3DDBB0), "") }, -1, onMove = { _, _ -> }, mask = p.mask, showSlider = false, dots = p.dots)
        }
        if (p.label.isNotBlank()) Text(p.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FoodRow(panel: QPanel, rows: List<QFood>) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Image(panel.bitmap!!.asImageBitmap(), panel.label, contentScale = ContentScale.Crop,
            modifier = Modifier.width(96.dp).aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(panel.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            rows.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (r.kept) toneColors(Tone.TEAL).ink else MaterialTheme.colorScheme.outlineVariant))
                    Text(r.name, style = MaterialTheme.typography.bodySmall, fontWeight = if (r.kept) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (r.kept) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(if (r.fromColour) "colour" else r.checks, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Bar(name: String, got: Double, need: Double) {
    val share = if (need <= 0) 1.0 else got / need
    val tone = toneColors(if (share >= 1) Tone.TEAL else if (share >= 0.5) Tone.BLUE else Tone.PEACH)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row {
            Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text("${if (got >= 100) "%.0f".format(got) else "%.1f".format(got)} / ${if (need >= 100) "%.0f".format(need) else "%.0f".format(need)}",
                style = MaterialTheme.typography.labelMedium, color = tone.ink)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(tone.container)) {
            Box(Modifier.fillMaxWidth(share.coerceIn(0.0, 1.0).toFloat()).fillMaxHeight().clip(CircleShape).background(tone.ink))
        }
    }
}
