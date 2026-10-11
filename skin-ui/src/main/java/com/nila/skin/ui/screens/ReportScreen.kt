package com.nila.skin.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.FileProvider
import com.nila.skin.core.food.Exposure
import com.nila.skin.core.food.ReactionEvent
import com.nila.skin.core.food.ReactionPatterns
import com.nila.skin.core.food.Sign
import com.nila.skin.core.measure.Reflex
import com.nila.skin.core.measure.StoolCard
import com.nila.skin.core.measure.Trend
import com.nila.skin.core.measure.TrendPoint
import com.nila.skin.core.rules.BodyArea
import com.nila.skin.core.rules.ConcernKind
import com.nila.skin.core.rules.Step
import com.nila.skin.data.BabyEntity
import com.nila.skin.data.Codec
import com.nila.skin.ui.SkinServices
import com.nila.skin.ui.ageDays
import com.nila.skin.ui.ageText
import com.nila.skin.ui.components.ChoicePills
import com.nila.skin.ui.components.FactCard
import com.nila.skin.ui.components.InfoNote
import com.nila.skin.ui.components.MutedText
import com.nila.skin.ui.components.NilaCard
import com.nila.skin.ui.components.PillButton
import com.nila.skin.ui.components.ScreenHero
import com.nila.skin.ui.components.SkinScaffold
import com.nila.skin.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@Composable
fun ReportScreen(baby: BabyEntity?, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var range by remember { mutableStateOf(1) }
    var busy by remember { mutableStateOf(false) }
    var made by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val days = listOf(7, 14, 30)[range]
    SkinScaffold("Doctor report", onBack = back) {
        ScreenHero("One page for the doctor", "Photos side by side, how things changed, your checklist answers, foods before each reaction. Doctors get the history; you don't have to remember it.")
        NilaCard(modifier = Modifier.fillMaxWidth()) {
            Text("Covering the last", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            ChoicePills(listOf("7 days", "14 days", "30 days"), range, { range = it; made = null })
        }
        FactCard("What's in it", Icons.Outlined.Description, Tone.TEAL, listOf(
            "Each tracked spot: first and latest photo, measurements, trend and next step.",
            "Reactions, with every food given in the 48 hours before each one, and any food patterns.",
            "Nappy colour, eye flash and yellow checks with dates.",
            "The guideline behind every piece of advice.",
        ))
        InfoNote("The PDF is made on this phone and only leaves it if you share it.", icon = Icons.Outlined.Lock, tone = Tone.SLATE)
        PillButton(if (busy) "Making the PDF..." else "Make the PDF", enabled = baby != null && !busy, icon = Icons.Outlined.Description, modifier = Modifier.fillMaxWidth(), onClick = {
            busy = true
            scope.launch {
                error = null
                made = runCatching { withContext(Dispatchers.IO) { buildReport(context, baby!!, days) } }
                    .onFailure { error = "The report couldn't be made: ${it.message}" }.getOrNull()
                busy = false
            }
        })
        error?.let { InfoNote(it, tone = Tone.PEACH) }
        made?.let { f ->
            MutedText("Saved: ${f.name}")
            PillButton("Share with the doctor", icon = Icons.Outlined.Share, modifier = Modifier.fillMaxWidth(), onClick = {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".skinfiles", f)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share report"))
            })
        }
    }
}

/** A tiny page-flowing writer over Android's PdfDocument. */
private class PdfWriter(private val doc: PdfDocument) {
    val w = 595; val h = 842; val margin = 40f
    var page: PdfDocument.Page? = null
    lateinit var c: Canvas
    var y = 0f
    var n = 0
    val body = Paint().apply { textSize = 10.5f; isAntiAlias = true; color = 0xFF1A1C1B.toInt() }
    val muted = Paint(body).apply { color = 0xFF5B6764.toInt(); textSize = 9.5f }
    val h1 = Paint(body).apply { textSize = 18f; typeface = Typeface.DEFAULT_BOLD; color = 0xFF0F6B60.toInt() }
    val h2 = Paint(body).apply { textSize = 13f; typeface = Typeface.DEFAULT_BOLD }
    val bold = Paint(body).apply { typeface = Typeface.DEFAULT_BOLD }

    fun newPage() {
        page?.let { doc.finishPage(it) }
        n++
        page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, n).create())
        c = page!!.canvas
        y = margin
        c.drawText("Little Signs · parent's record · page $n", margin, h - 20f, muted)
    }

    fun need(px: Float) { if (page == null || y + px > h - 40f) newPage() }

    fun text(s: String, p: Paint = body, gap: Float = 4f) {
        val maxW = w - 2 * margin
        val words = s.split(' ')
        var line = ""
        for (word in words) {
            val t = if (line.isEmpty()) word else "$line $word"
            if (p.measureText(t) > maxW && line.isNotEmpty()) { need(p.textSize + gap); y += p.textSize; c.drawText(line, margin, y, p); y += gap; line = word }
            else line = t
        }
        if (line.isNotEmpty()) { need(p.textSize + gap); y += p.textSize; c.drawText(line, margin, y, p); y += gap }
    }

    fun space(px: Float = 8f) { y += px }

    fun images(bitmaps: List<Pair<Bitmap, String>>, size: Float = 150f) {
        need(size + 20f)
        var x = margin
        bitmaps.forEach { (b, label) ->
            val s = minOf(size / b.width, size / b.height)
            val rw = b.width * s; val rh = b.height * s
            c.drawBitmap(b, null, RectF(x, y, x + rw, y + rh), null)
            c.drawText(label, x, y + rh + 11f, muted)
            x += rw + 12f
        }
        y += size + 18f
    }

    fun finish() { page?.let { doc.finishPage(it) } }
}

suspend fun buildReport(context: Context, baby: BabyEntity, days: Int): File {
    val dao = SkinServices.dao
    val db = SkinServices.foods
    val now = System.currentTimeMillis()
    val from = now - days * 86_400_000L
    val doc = PdfDocument()
    val p = PdfWriter(doc)
    p.newPage()
    p.text("Little Signs: record for the doctor", p.h1, 8f)
    p.text("${baby.name} · born ${fmtDate(baby.birthMs)} (${ageText(baby.ageDays())})${if (baby.preterm) " · born early" else ""}", p.bold)
    p.text("Covers ${fmtDate(from)} to ${fmtDate(now)}. Made on the parent's phone by Nila.", p.muted)
    p.text("Photos, measurements and checklist answers recorded by the parent. Nila describes and measures; it does not diagnose. Measurements compare each spot with the child's own surrounding skin (CIELAB colour).", p.muted)
    p.space(10f)

    // Skin
    val concerns = dao.concerns().first()
    if (concerns.isNotEmpty()) p.text("Skin diary", p.h2, 6f)
    for (c in concerns) {
        val entries = dao.entriesNow(c.id)
        val inRange = entries.filter { it.timeMs >= from }
        if (inRange.isEmpty()) continue
        p.text("${c.title} — ${ConcernKind.valueOf(c.kind).label}, ${BodyArea.valueOf(c.area).label}. Tracked since ${fmtDate(c.createdMs)}, ${entries.size} photo${if (entries.size == 1) "" else "s"}.", p.bold)
        val pics = listOfNotNull(entries.first().let { e -> SkinServices.photos.load(e.photoPath, 500)?.let { it to "First · ${fmtDate(e.timeMs)}" } },
            entries.last().takeIf { entries.size > 1 }?.let { e -> SkinServices.photos.load(e.photoPath, 500)?.let { it to "Latest · ${fmtDate(e.timeMs)}" } })
        if (pics.isNotEmpty()) p.images(pics)
        if (entries.size >= 2) p.text("Change: " + Trend.summarise(entries.map { TrendPoint(it.timeMs, it.areaMm2 ?: it.areaOfPhoto, it.areaMm2 != null, it.redness, it.spots) }).sentence)
        inRange.forEach { e ->
            val size = e.areaMm2?.let { "${it.toInt()} mm²" } ?: "%.1f%% of photo".format(e.areaOfPhoto * 100)
            val deltas = "Δa* %+.1f · ΔL* %+.1f".format(e.deltaA, e.deltaL)
            p.text("${fmtDateTime(e.timeMs)} · ${Step.valueOf(e.step).title} · spots ${e.spots} · size $size · $deltas", p.body, 2f)
            val words = Codec.list(e.words).joinToString("; ")
            p.text("   Measured: $words" + (e.aiColour?.let { ". AI description: " + (listOf(it) + Codec.list(e.aiSurfaces).filter { s -> s.isNotBlank() }.map { s -> s.lowercase() } + listOfNotNull(e.aiPattern)).joinToString(", ") } ?: ""), p.muted, 2f)
            val yes = Codec.answers(e.answers).filterValues { it.name != "NO" }.map { "${it.key}=${it.value.name.lowercase()}" }
            if (yes.isNotEmpty()) p.text("   Parent answered: ${yes.joinToString()}", p.muted, 2f)
            Codec.list(e.reasons).forEach { p.text("   • $it", p.muted, 2f) }
        }
        p.space()
    }

    // Food
    val logs = dao.foodsNow()
    val reactions = dao.reactionsNow().filter { it.timeMs >= from }
    if (reactions.isNotEmpty() || logs.any { it.timeMs >= from }) {
        p.text("Foods and reactions", p.h2, 6f)
        reactions.forEach { r ->
            val signs = Codec.list(r.signs).mapNotNull { s -> Sign.entries.firstOrNull { it.name == s }?.label }
            p.text("Reaction ${fmtDateTime(r.timeMs)}: ${signs.joinToString()}${if (r.note.isNotBlank()) " — ${r.note}" else ""}", p.bold, 2f)
            val before = logs.filter { r.timeMs - it.timeMs in 0..ReactionPatterns.DELAYED_MS }.sortedBy { it.timeMs }
            p.text("   Foods in the 48 h before: " + before.joinToString { "${db[it.foodId]?.name ?: it.foodId} (${((r.timeMs - it.timeMs) / 3_600_000.0).let { h -> "%.1f h".format(h) }}${if (it.firstTime) ", first time" else ""})" }, p.muted, 2f)
        }
        val pats = ReactionPatterns.analyse(logs.map { Exposure(it.foodId, it.timeMs, it.firstTime) },
            dao.reactionsNow().map { r -> ReactionEvent(r.timeMs, Codec.list(r.signs).mapNotNull { s -> Sign.entries.firstOrNull { it.name == s } }.toSet()) })
            .filter { it.strength == ReactionPatterns.Pattern.Strength.PATTERN || it.strength == ReactionPatterns.Pattern.Strength.POSSIBLE }
        pats.forEach { p.text("${if (it.strength == ReactionPatterns.Pattern.Strength.PATTERN) "Pattern" else "Possible pattern"}: ${db[it.foodId]?.name}: ${it.followed} of ${it.exposures} exposures followed by a reaction within the window (${"%.0f".format(it.lift)}× other foods; one-sided Fisher exact p = ${"%.3f".format(it.chance)}).", p.body, 2f) }
        val firsts = logs.filter { it.firstTime && it.timeMs >= from }.sortedBy { it.timeMs }
        if (firsts.isNotEmpty()) p.text("New foods in this period: " + firsts.joinToString { "${db[it.foodId]?.name} (${fmtDate(it.timeMs)})" }, p.muted)
        p.space()
    }

    // Checks
    val stools = dao.stoolsNow().filter { it.timeMs >= from }
    val eyes = dao.eyesNow().filter { it.timeMs >= from }
    val yellows = dao.yellowsNow().filter { it.timeMs >= from }
    if (stools.isNotEmpty() || eyes.isNotEmpty() || yellows.isNotEmpty()) p.text("Other checks", p.h2, 6f)
    stools.forEach { s -> p.text("Nappy ${fmtDateTime(s.timeMs)}: parent chose ${s.chosen} (${StoolCard.byId(s.chosen).label})" + (s.measured?.let { "; photo read nearest $it${if (s.whiteCorrected) ", white-corrected" else ""}" } ?: "") + " · ${Step.valueOf(s.step).title}", p.body, 2f) }
    eyes.forEach { e -> p.text("Eye flash ${fmtDateTime(e.timeMs)}: left ${Reflex.valueOf(e.left).word}; right ${Reflex.valueOf(e.right).word} · ${Step.valueOf(e.step).title}", p.body, 2f) }
    yellows.forEach { y -> p.text("Yellow check ${fmtDateTime(y.timeMs)} at ${y.ageHours / 24} d ${y.ageHours % 24} h · ${Step.valueOf(y.step).title}" + (y.eyeYellowness?.let { " · eye-white b* %.1f (photo only, not bilirubin)".format(it) } ?: ""), p.body, 2f) }

    p.space(12f)
    p.text("Rules used: NICE NG143 (fever traffic light), NHS rash and glass test, NICE CG57 (eczema), WHO/India IMNCI young infant chart, Newborn Screening Ontario stool card, AAP 2019 infantile hemangioma guideline, AAP red reflex, NIAID 2017 peanut addendum. Nutrition: ICMR-NIN 2020, WHO/UNICEF dietary diversity, USDA FoodData Central.", p.muted)
    p.finish()
    val dir = File(context.cacheDir, "reports").apply { mkdirs() }
    val f = File(dir, "little-signs-${baby.name.replace(Regex("[^A-Za-z0-9]"), "")}-${java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date(now))}.pdf")
    FileOutputStream(f).use { doc.writeTo(it) }
    doc.close()
    return f
}
