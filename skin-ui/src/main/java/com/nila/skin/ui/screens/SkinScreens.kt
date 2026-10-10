package com.nila.skin.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nila.skin.core.image.NCircle
import com.nila.skin.core.image.PhotoQuality
import com.nila.skin.core.image.Quality
import com.nila.skin.core.measure.SizeReference
import com.nila.skin.core.measure.SkinMeasure
import com.nila.skin.core.measure.SkinReading
import com.nila.skin.core.measure.SpotFinder
import com.nila.skin.core.measure.Trend
import com.nila.skin.core.measure.TrendPoint
import com.nila.skin.core.rules.Advice
import com.nila.skin.core.rules.Answer
import com.nila.skin.core.rules.BodyArea
import com.nila.skin.core.rules.ConcernKind
import com.nila.skin.core.rules.Reason
import com.nila.skin.core.rules.SkinRules
import com.nila.skin.core.rules.Step
import com.nila.skin.core.vlm.VisionForms
import com.nila.skin.data.BabyEntity
import com.nila.skin.data.Codec
import com.nila.skin.data.ConcernEntity
import com.nila.skin.data.PhotoStore
import com.nila.skin.data.SkinEntryEntity
import com.nila.skin.ui.SkinServices
import com.nila.skin.ui.ageDays
import com.nila.skin.ui.components.AdviceCard
import com.nila.skin.ui.components.ChipGroup
import com.nila.skin.ui.components.FactCard
import com.nila.skin.ui.components.Heading
import com.nila.skin.ui.components.InfoNote
import com.nila.skin.ui.components.ListRow
import com.nila.skin.ui.components.MarkablePhoto
import com.nila.skin.ui.components.Marker
import com.nila.skin.ui.components.MarkerTabs
import com.nila.skin.ui.components.MutedText
import com.nila.skin.ui.components.NilaCard
import com.nila.skin.ui.components.NotADiagnosis
import com.nila.skin.ui.components.OutlinePillButton
import com.nila.skin.ui.components.PhotoInput
import com.nila.skin.ui.components.PillButton
import com.nila.skin.ui.components.QuestionCard
import com.nila.skin.ui.components.RowDivider
import com.nila.skin.ui.components.SafetyNetCard
import com.nila.skin.ui.components.ScreenHero
import com.nila.skin.ui.components.SkinScaffold
import com.nila.skin.ui.components.StepPill
import com.nila.skin.ui.components.Thumb
import com.nila.skin.ui.components.TrendChart
import com.nila.skin.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// List of tracked spots
// ---------------------------------------------------------------------------

@Composable
fun SkinListScreen(go: (String) -> Unit, back: () -> Unit) {
    val concerns by SkinServices.dao.concerns().collectAsState(initial = emptyList())
    val entries by SkinServices.dao.allEntries().collectAsState(initial = emptyList())
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    SkinScaffold("Skin diary", onBack = back) {
        ScreenHero("Track it, don't guess it",
            "One photo a day of the same spot shows whether it's spreading or settling: the question every doctor asks.")
        if (!adding) PillButton("Track a new spot", onClick = { adding = true }, icon = Icons.Outlined.Add, modifier = Modifier.fillMaxWidth())
        if (adding) NewConcernCard(onCancel = { adding = false }) { c ->
            scope.launch {
                val id = SkinServices.dao.addConcern(c)
                adding = false
                go("check/$id")
            }
        }
        if (concerns.isEmpty() && !adding) InfoNote("Nothing tracked yet. Most baby skin changes are harmless and pass; tracking just makes the change visible.")
        OutlinePillButton("Common and harmless skin changes", onClick = { go("learn") }, modifier = Modifier.fillMaxWidth())
        if (concerns.isNotEmpty()) NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 0.dp) {
            concerns.forEachIndexed { i, c ->
                if (i > 0) RowDivider()
                val mine = entries.filter { it.concernId == c.id }
                val last = mine.maxByOrNull { it.timeMs }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Thumb(last?.photoPath, 56)
                    ListRow(c.title, subtitle = "${ConcernKind.valueOf(c.kind).label} · ${BodyArea.valueOf(c.area).label} · ${mine.size} photo${if (mine.size == 1) "" else "s"}",
                        onClick = { go("concern/${c.id}") }, modifier = Modifier.weight(1f),
                        trailing = { last?.let { StepPill(Step.valueOf(it.step)) } })
                }
            }
        }
        NotADiagnosis()
    }
}

@Composable
private fun NewConcernCard(onCancel: () -> Unit, onCreate: (ConcernEntity) -> Unit) {
    var title by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ConcernKind.RASH) }
    var area by remember { mutableStateOf(BodyArea.FACE) }
    NilaCard(modifier = Modifier.fillMaxWidth()) {
        Text("What are you tracking?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(title, { title = it }, label = { Text("A name, e.g. \"Cheek patch\"") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        MutedText("In your words")
        ChipGroup(ConcernKind.entries, { it.label }, { it == kind }, { kind = it })
        MutedText("Where")
        ChipGroup(BodyArea.entries, { it.label }, { it == area }, { area = it })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinePillButton("Cancel", onClick = onCancel, modifier = Modifier.weight(1f))
            PillButton("Next: photo", enabled = title.isNotBlank(), onClick = {
                onCreate(ConcernEntity(title = title.trim(), kind = kind.name, area = area.name, createdMs = System.currentTimeMillis()))
            }, modifier = Modifier.weight(1f))
        }
    }
}

// ---------------------------------------------------------------------------
// One check: photo -> mark -> measure + describe -> questions -> result
// ---------------------------------------------------------------------------

private enum class Stage { PHOTO, MARK, QUESTIONS, RESULT }

@Composable
fun SkinCheckScreen(concernId: Long, baby: BabyEntity?, done: (Long) -> Unit, back: () -> Unit) {
    val concern by SkinServices.dao.concern(concernId).collectAsState(initial = null)
    val history by SkinServices.dao.entries(concernId).collectAsState(initial = emptyList())
    val c = concern ?: return
    val kind = ConcernKind.valueOf(c.kind)
    val area = BodyArea.valueOf(c.area)
    val scope = rememberCoroutineScope()

    var stage by remember { mutableStateOf(Stage.PHOTO) }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var quality by remember { mutableStateOf<PhotoQuality?>(null) }
    val markers = remember { mutableStateListOf(Marker(NCircle(0.5, 0.5, 0.2), Color(0xFF3DDBB0), "The spot")) }
    var active by remember { mutableStateOf(0) }
    var useCoin by remember { mutableStateOf(false) }
    var coinMm by remember { mutableStateOf("20") }
    var reading by remember { mutableStateOf<SkinReading?>(null) }
    var ai by remember { mutableStateOf<VisionForms.SkinDescription?>(null) }
    var aiState by remember { mutableStateOf("idle") }
    var aiMs by remember { mutableStateOf(0L) }
    var autoPlaced by remember { mutableStateOf<NCircle?>(null) }
    val answers = remember { mutableStateMapOf<String, Answer>() }
    var advice by remember { mutableStateOf<Advice?>(null) }
    var saving by remember { mutableStateOf(false) }

    val ghost = remember(history) { history.lastOrNull()?.let { SkinServices.photos.load(it.photoPath, 1200) } }

    fun analyse() {
        val bmp = photo ?: return
        val spot = markers[0].circle
        val coin = if (useCoin && markers.size > 1) SizeReference(markers[1].circle, coinMm.toDoubleOrNull() ?: 20.0) else null
        scope.launch {
            reading = withContext(Dispatchers.Default) { SkinMeasure.measure(PhotoStore.toPixels(bmp), spot, coin) }
            stage = Stage.QUESTIONS
            aiState = "running"
            val crop = withContext(Dispatchers.Default) { cropAround(bmp, spot, 1.8) }
            val t0 = System.currentTimeMillis()
            val raw = SkinServices.vision.look(crop, VisionForms.SYSTEM, VisionForms.SKIN_PROMPT)
            aiMs = System.currentTimeMillis() - t0
            ai = raw?.let { VisionForms.parseSkin(it) }
            aiState = if (raw == null) "unavailable" else "done"
        }
    }

    fun decide() {
        val r = reading
        val points = history.map { TrendPoint(it.timeMs, it.areaMm2 ?: it.areaOfPhoto, it.areaMm2 != null, it.redness, it.spots) } +
            listOfNotNull(r?.let { TrendPoint(System.currentTimeMillis(), it.areaMm2 ?: it.areaOfPhoto, it.areaMm2 != null, it.rednessScore, it.spots) })
        val trend = if (points.size >= 2) Trend.summarise(points) else null
        scope.launch {
            val count = SkinServices.dao.birthmarkCount()
            advice = SkinRules.advise(SkinRules.Input(kind, area, baby?.ageDays() ?: 180, answers.toMap(), r, trend, count, history.isEmpty()))
            stage = Stage.RESULT
        }
    }

    SkinScaffold(c.title, onBack = back, scrollKey = stage) {
        when (stage) {
            Stage.PHOTO -> {
                FactCard("For a photo the maths can trust", Icons.Outlined.Spa, Tone.TEAL, listOf(
                    "Daylight from a window, no flash, no filters.",
                    "About a hand-span away, the spot in the middle, some normal skin around it.",
                    if (history.isNotEmpty()) "The camera shows the last photo faintly, so you can frame it the same way." else "Optional: lay a coin beside it to measure size in millimetres.",
                ))
                PhotoInput("skin", onPhoto = { b, _ ->
                    photo = b
                    scope.launch {
                        val px = withContext(Dispatchers.Default) { PhotoStore.toPixels(b, 600) }
                        quality = withContext(Dispatchers.Default) { Quality.check(px) }
                        autoPlaced = withContext(Dispatchers.Default) { SpotFinder.suggest(px) }
                        autoPlaced?.let { markers[0] = markers[0].copy(circle = it) }
                        stage = Stage.MARK
                    }
                }, hint = "Take a photo of the spot, or choose one.", ghost = ghost)
            }
            Stage.MARK -> {
                val bmp = photo!!
                Heading("Circle the spot")
                MutedText("Tap the middle of the spot and size the circle so it covers the spot with a little normal skin inside. The ring just outside is used as your baby's own normal skin.")
                quality?.problems?.forEach { InfoNote(it.advice, icon = Icons.Outlined.Warning, tone = Tone.PEACH) }
                if (autoPlaced != null) InfoNote("Nila placed the circle on the area that differs most from the skin around it. Adjust it if it's off.", icon = Icons.Outlined.AutoAwesome, tone = Tone.BLUE)
                MarkerTabs(markers, active) { active = it }
                MarkablePhoto(bmp, markers, active, onMove = { i, nc -> markers[i] = markers[i].copy(circle = nc) })
                NilaCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("There's a coin in the photo", style = MaterialTheme.typography.titleSmall)
                            MutedText("Gives the size in mm², comparable between any two photos.")
                        }
                        Switch(useCoin, {
                            useCoin = it
                            if (it && markers.size == 1) markers.add(Marker(NCircle(0.2, 0.8, 0.06), Color(0xFFF5C066), "Coin"))
                            if (!it && markers.size > 1) { markers.removeAt(1); active = 0 }
                        })
                    }
                    if (useCoin) OutlinedTextField(coinMm, { coinMm = it.filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text("Coin diameter in mm") },
                        leadingIcon = { androidx.compose.material3.Icon(Icons.Outlined.Straighten, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinePillButton("Retake", onClick = { stage = Stage.PHOTO }, modifier = Modifier.weight(1f))
                    PillButton("Measure", onClick = { analyse() }, icon = Icons.Outlined.Analytics, modifier = Modifier.weight(1f))
                }
            }
            Stage.QUESTIONS, Stage.RESULT -> {
                val r = reading!!
                MarkablePhoto(photo!!, markers.take(1), -1, onMove = { _, _ -> }, mask = r.overlay, showSlider = false)
                MeasureCard(r)
                AiCard(aiState, ai, aiMs)
                if (stage == Stage.QUESTIONS) {
                    Heading("A few questions")
                    MutedText("These come from NICE, NHS and WHO checklists. Your answers, not the AI, decide the next step.")
                    SkinRules.questions(kind).forEach { q ->
                        val hint = if (q.id == SkinRules.BLISTERS.id && ai?.suggestsBlistersOrCrusts == true)
                            "The on-device AI described possible ${ai!!.surfaces.filter { it == VisionForms.Surface.BLISTERS || it == VisionForms.Surface.CRUSTS || it == VisionForms.Surface.WEEPING }.joinToString { it.label.lowercase() }}. Please look closely and answer." else null
                        QuestionCard(q, answers[q.id], { answers[q.id] = it }, hint)
                    }
                    PillButton("See what to do", onClick = { decide() }, icon = Icons.Outlined.ArrowForward, modifier = Modifier.fillMaxWidth())
                } else {
                    val a = advice!!
                    AdviceCard(a)
                    if (history.isNotEmpty()) {
                        val points = history.map { TrendPoint(it.timeMs, it.areaMm2 ?: it.areaOfPhoto, it.areaMm2 != null, it.redness, it.spots) } +
                            TrendPoint(System.currentTimeMillis(), r.areaMm2 ?: r.areaOfPhoto, r.areaMm2 != null, r.rednessScore, r.spots)
                        FactCard("What changed", Icons.Outlined.Timeline, Tone.BLUE, listOf(Trend.summarise(points).sentence))
                    }
                    if (a.askDoctor.isNotEmpty()) FactCard("Worth asking your doctor", Icons.Outlined.Check, Tone.TEAL, a.askDoctor)
                    SafetyNetCard(SkinRules.safetyNet)
                    PillButton(if (saving) "Saving..." else "Save to diary", enabled = !saving, icon = Icons.Outlined.AddAPhoto, modifier = Modifier.fillMaxWidth(), onClick = {
                        saving = true
                        scope.launch {
                            val path = withContext(Dispatchers.IO) { SkinServices.photos.save(photo!!, "skin") }
                            val spot = markers[0].circle
                            val coin = markers.getOrNull(1)?.takeIf { useCoin }?.circle
                            SkinServices.dao.addEntry(SkinEntryEntity(
                                concernId = concernId, timeMs = System.currentTimeMillis(), photoPath = path,
                                cx = spot.cx, cy = spot.cy, r = spot.r,
                                coinCx = coin?.cx, coinCy = coin?.cy, coinR = coin?.r, coinMm = if (coin != null) coinMm.toDoubleOrNull() else null,
                                coverage = r.coverage, areaOfPhoto = r.areaOfPhoto, areaMm2 = r.areaMm2, spots = r.spots,
                                deltaA = r.deltaA, deltaL = r.deltaL, deltaB = r.deltaB, redness = r.rednessScore,
                                words = Codec.list(r.words),
                                aiColour = ai?.colour, aiSurfaces = ai?.surfaces?.let { s -> Codec.list(s.map { it.label }) },
                                aiPattern = ai?.pattern, aiRaw = ai?.raw,
                                answers = Codec.answers(answers.toMap()), step = a.step.name,
                                reasons = Codec.list(a.reasons.map { reasonText(it) }),
                            ))
                            done(concernId)
                        }
                    })
                }
                NotADiagnosis()
            }
        }
    }
}

fun reasonText(r: Reason) = r.text + (r.source?.let { " [${it.short}]" } ?: " [measured]")

@Composable
fun MeasureCard(r: SkinReading) {
    FactCard("What the maths measured", Icons.Outlined.Analytics, Tone.TEAL, r.words + buildList {
        r.areaMm2?.let { add("Area about ${it.toInt()} mm²") }
        add("Redness compared with own skin: Δa* ${"%+.1f".format(r.deltaA)}  ·  lightness ΔL* ${"%+.1f".format(r.deltaL)}")
    }, footer = "Compared with your baby's own skin in the ring around the circle, so it reads the same on every skin tone.")
}

@Composable
fun AiCard(state: String, ai: VisionForms.SkinDescription?, ms: Long = 0) {
    NilaCard(modifier = Modifier.fillMaxWidth(), tone = Tone.BLUE) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.material3.Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.tertiary)
            Text("What the on-device AI describes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        when (state) {
            "running" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                MutedText("Intern3.5-VL is looking at the photo on the NPU...")
            }
            "unavailable" -> MutedText("The AI model isn't available on this phone right now. The measurement above and the checklist don't need it.")
            "done" -> {
                val d = ai!!
                if (d.notSkin) MutedText("The AI doesn't think this is a close-up of skin. Check the photo.")
                listOfNotNull(
                    d.colour?.let { "Colour: $it" },
                    d.surfaces.takeIf { it.isNotEmpty() }?.let { s -> "Surface: " + s.joinToString { it.label.lowercase() } },
                    d.pattern?.let { "Pattern: $it" },
                ).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                MutedText("A description only. The AI is never asked what condition this is." +
                    if (ms > 0) " Answered in ${"%.1f".format(ms / 1000.0)} s on the NPU${if (ms > 8000) " (includes loading the model)" else ""}." else "")
            }
            else -> Unit
        }
    }
}

fun cropAround(bmp: Bitmap, c: NCircle, scale: Double): Bitmap {
    val short = minOf(bmp.width, bmp.height)
    val r = (c.r * short * scale).toInt().coerceAtLeast(40)
    val cx = (c.cx * bmp.width).toInt(); val cy = (c.cy * bmp.height).toInt()
    val x0 = (cx - r).coerceIn(0, bmp.width - 1); val y0 = (cy - r).coerceIn(0, bmp.height - 1)
    val x1 = (cx + r).coerceIn(x0 + 1, bmp.width); val y1 = (cy + r).coerceIn(y0 + 1, bmp.height)
    return Bitmap.createBitmap(bmp, x0, y0, x1 - x0, y1 - y0)
}

// ---------------------------------------------------------------------------
// One tracked spot over time
// ---------------------------------------------------------------------------

@Composable
fun ConcernScreen(id: Long, baby: BabyEntity?, go: (String) -> Unit, back: () -> Unit) {
    val concern by SkinServices.dao.concern(id).collectAsState(initial = null)
    val entries by SkinServices.dao.entries(id).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val c = concern ?: return
    SkinScaffold(c.title, onBack = back) {
        Text("${ConcernKind.valueOf(c.kind).label} · ${BodyArea.valueOf(c.area).label} · tracked since ${fmtDate(c.createdMs)}",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val last = entries.lastOrNull()
        if (last != null) {
            val step = Step.valueOf(last.step)
            NilaCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Latest check · ${fmtDateTime(last.timeMs)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    StepPill(step)
                }
                Codec.list(last.reasons).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (Codec.list(last.reasons).isEmpty()) MutedText(step.title)
            }
        }
        PillButton("Add today's photo", onClick = { go("check/$id") }, icon = Icons.Outlined.AddAPhoto, modifier = Modifier.fillMaxWidth())
        if (entries.size >= 2) {
            val points = entries.map { TrendPoint(it.timeMs, it.areaMm2 ?: it.areaOfPhoto, it.areaMm2 != null, it.redness, it.spots) }
            val t = Trend.summarise(points)
            NilaCard(modifier = Modifier.fillMaxWidth()) {
                Text("How it's changing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(t.sentence, style = MaterialTheme.typography.bodyLarge)
                TrendChart(entries.map { (it.areaMm2 ?: it.areaOfPhoto * 100) }, if (entries.all { it.areaMm2 != null }) "Size (mm²)" else "Size (% of photo)")
                TrendChart(entries.map { it.redness }, "Redness vs own skin (0-100)")
                TrendChart(entries.map { it.spots.toDouble() }, "Number of spots")
            }
            Heading("First and latest")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(entries.first(), entries.last()).forEach { e ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val bmp = remember(e.photoPath) { SkinServices.photos.load(e.photoPath, 700) }
                        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp))) }
                        MutedText(fmtDate(e.timeMs))
                    }
                }
            }
        }
        Heading("Timeline")
        NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 0.dp) {
            if (entries.isEmpty()) MutedText("No photos yet.")
            entries.asReversed().forEachIndexed { i, e ->
                if (i > 0) RowDivider()
                Row(Modifier.fillMaxWidth().padding6(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Thumb(e.photoPath, 60)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(fmtDateTime(e.timeMs), style = MaterialTheme.typography.titleSmall)
                        MutedText(Codec.list(e.words).take(3).joinToString(" · "))
                        e.aiColour?.let { MutedText("AI: $it${e.aiSurfaces?.let { s -> ", " + Codec.list(s).joinToString().lowercase() } ?: ""}") }
                        StepPill(Step.valueOf(e.step))
                    }
                }
            }
        }
        OutlinePillButton("Stop tracking this", onClick = { scope.launch { SkinServices.dao.updateConcern(c.copy(archived = true)); back() } }, modifier = Modifier.fillMaxWidth())
        NotADiagnosis()
    }
}

private fun Modifier.padding6() = this.padding(vertical = 8.dp)
