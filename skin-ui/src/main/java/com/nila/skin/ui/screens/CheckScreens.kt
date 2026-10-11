package com.nila.skin.ui.screens

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.WineBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.nila.skin.core.image.NCircle
import com.nila.skin.core.measure.EyePairReading
import com.nila.skin.core.measure.EyeReflex
import com.nila.skin.core.measure.Reflex
import com.nila.skin.core.measure.StoolCard
import com.nila.skin.core.measure.StoolGroup
import com.nila.skin.core.measure.StoolSwatch
import com.nila.skin.core.measure.YellowReading
import com.nila.skin.core.measure.Yellowness
import com.nila.skin.core.rules.Advice
import com.nila.skin.core.rules.Answer
import com.nila.skin.core.rules.EyeRules
import com.nila.skin.core.rules.JaundiceRules
import com.nila.skin.core.rules.Question
import com.nila.skin.core.rules.Reason
import com.nila.skin.core.rules.SkinRules
import com.nila.skin.core.rules.Source
import com.nila.skin.core.rules.Step
import com.nila.skin.core.rules.StoolRules
import com.nila.skin.data.BabyEntity
import com.nila.skin.data.Codec
import com.nila.skin.data.EyeCheckEntity
import com.nila.skin.data.PhotoStore
import com.nila.skin.data.StoolCheckEntity
import com.nila.skin.data.YellowCheckEntity
import com.nila.skin.ui.SkinServices
import com.nila.skin.ui.safely
import com.nila.skin.ui.ageDays
import com.nila.skin.ui.ageHours
import com.nila.skin.ui.components.AdviceCard
import com.nila.skin.ui.components.FactCard
import com.nila.skin.ui.components.Heading
import com.nila.skin.ui.components.InfoNote
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
// Nappy colour (stool card)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StoolScreen(baby: BabyEntity?, back: () -> Unit) {
    val dao = SkinServices.dao
    val history by dao.stools().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val ageDays = baby?.ageDays() ?: 20
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    val markers = remember { mutableStateListOf(Marker(NCircle(0.5, 0.5, 0.12), Color(0xFF3DDBB0), "Poo")) }
    var active by remember { mutableStateOf(0) }
    var useWhite by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<StoolCard.Reading?>(null) }
    var chosen by remember { mutableStateOf<StoolSwatch?>(null) }
    var advice by remember { mutableStateOf<Advice?>(null) }
    var saved by remember { mutableStateOf(false) }
    var stoolError by remember { mutableStateOf(false) }

    SkinScaffold("Nappy colour check", onBack = back, scrollKey = advice == null) {
        ScreenHero("The poo colour card", "Pale poo is the first visible sign of biliary atresia, a liver condition where every week counts. A daily look in the first month catches it.")
        FactCard("Why this matters", Icons.Outlined.Science, Tone.BLUE, listOf(
            "Taiwan put a stool colour card in every baby's health booklet in 2004. Five-year survival for biliary atresia rose from 55.7% to 89.3%.",
            "Surgery before 60 days of age works best; the card moved more babies into that window.",
            "Colours 1-6 below are sampled from Newborn Screening Ontario's card: any of them means call your doctor.",
        ), footer = Source.STOOL_CARD.full)

        if (advice == null) {
            if (photo == null) PhotoInput("stool", onPhoto = { b, _ ->
                photo = b; reading = null
                scope.launch { safely("stool spot") { withContext(Dispatchers.Default) { StoolCard.suggestSpot(PhotoStore.toPixels(b, 600)) } }?.let { markers[0] = markers[0].copy(circle = it) } }
            }, hint = "Photo the open nappy in daylight. The white of the nappy is used to correct the colour.")
            photo?.let { bmp ->
                Heading("Circle the poo")
                MarkerTabs(markers, active) { active = it }
                MarkablePhoto(bmp, markers, active, onMove = { i, c -> markers[i] = markers[i].copy(circle = c) }, dots = reading?.whitePoints ?: emptyList())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("I'll mark the white nappy myself", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(useWhite, {
                        useWhite = it
                        if (it && markers.size == 1) markers.add(Marker(NCircle(0.15, 0.15, 0.08), Color.White, "White nappy"))
                        if (!it && markers.size > 1) { markers.removeAt(1); active = 0 }
                    })
                }
                PillButton("Read the colour", icon = Icons.Outlined.Analytics, modifier = Modifier.fillMaxWidth(), onClick = {
                    scope.launch {
                        reading = safely("stool read") { withContext(Dispatchers.Default) {
                            StoolCard.read(PhotoStore.toPixels(bmp), markers[0].circle, markers.getOrNull(1)?.circle)
                        } }
                        stoolError = reading == null
                        chosen = reading?.nearest
                    }
                })
            }
            if (stoolError) InfoNote("Couldn't read the colour. Make sure the circle is on the poo and inside the photo.", tone = Tone.PEACH)
            reading?.let { r ->
                NilaCard(modifier = Modifier.fillMaxWidth()) {
                    Text("Closest match from the photo: ${r.nearest.id} · ${r.nearest.label}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    MutedText(when {
                        r.whiteCorrected && r.whitePoints.isNotEmpty() -> "Colour and brightness corrected using the white of the nappy (the small white dots show which pixels were used)."
                        r.whiteCorrected -> "Colour and brightness corrected using the white you marked."
                        else -> "No white found to correct the colour; compare by eye in daylight."
                    })
                    if (r.paleByChroma) MutedText("Reads light and weakly coloured: the signature of the pale colours.")
                }
            }
            Heading(if (photo == null) "Or pick the closest colour" else "Now pick the closest colour yourself")
            MutedText("You're the final judge: compare the nappy with these swatches in daylight.")
            listOf(StoolGroup.PALE to "Pale: call your doctor", StoolGroup.NORMAL to "Normal colours", StoolGroup.RED to "Needs checking today", StoolGroup.BLACK to "").forEach { (g, title) ->
                if (title.isNotEmpty()) Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StoolCard.swatches.filter { it.group == g }.forEach { s ->
                        Column(Modifier.width(72.dp).clickable { chosen = s }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(60.dp).clip(RoundedCornerShape(14.dp)).background(Color(s.argb))
                                .border(if (chosen == s) 4.dp else 1.dp, if (chosen == s) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)))
                            Text(s.id + (if (reading?.nearest == s) " ★" else ""), style = MaterialTheme.typography.labelLarge)
                            Text(s.label, style = MaterialTheme.typography.labelSmall, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            PillButton("Done", enabled = chosen != null, icon = Icons.Outlined.ArrowForward, modifier = Modifier.fillMaxWidth(), onClick = {
                advice = StoolRules.advise(StoolRules.Input(chosen!!, reading, ageDays))
            })
        } else {
            AdviceCard(advice!!)
            if (advice!!.askDoctor.isNotEmpty()) FactCard("Say to the doctor", Icons.Outlined.Check, Tone.TEAL, advice!!.askDoctor)
            if (!saved) PillButton("Save", icon = Icons.Outlined.Check, modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch {
                    val path = photo?.let { withContext(Dispatchers.IO) { SkinServices.photos.save(it, "stool") } }
                    val r = reading
                    dao.addStool(StoolCheckEntity(timeMs = System.currentTimeMillis(), photoPath = path, chosen = chosen!!.id, measured = r?.nearest?.id,
                        l = r?.stool?.l, a = r?.stool?.a, b = r?.stool?.b, whiteCorrected = r?.whiteCorrected ?: false, step = advice!!.step.name))
                    saved = true
                }
            }) else OutlinePillButton("Check another nappy", onClick = { advice = null; photo = null; reading = null; chosen = null; saved = false }, modifier = Modifier.fillMaxWidth())
        }

        if (history.isNotEmpty()) {
            Heading("History")
            NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 0.dp) {
                history.take(14).forEachIndexed { i, h ->
                    if (i > 0) RowDivider()
                    val s = StoolCard.byId(h.chosen)
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(s.argb)))
                        Column(Modifier.weight(1f)) { Text("${s.id} · ${s.label}", style = MaterialTheme.typography.titleSmall); MutedText(fmtDateTime(h.timeMs)) }
                        StepPill(Step.valueOf(h.step))
                    }
                }
            }
        }
        NotADiagnosis()
    }
}

// ---------------------------------------------------------------------------
// Eye flash check
// ---------------------------------------------------------------------------

@Composable
fun EyeScreen(baby: BabyEntity?, back: () -> Unit) {
    val dao = SkinServices.dao
    val history by dao.eyes().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var finding by remember { mutableStateOf(false) }
    var faceFound by remember { mutableStateOf<Boolean?>(null) }
    var angle by remember { mutableStateOf<com.nila.skin.core.measure.EyeAngle?>(null) }
    val markers = remember { mutableStateListOf(Marker(NCircle(0.4, 0.45, 0.03), Color(0xFF3DDBB0), "Left in photo"), Marker(NCircle(0.6, 0.45, 0.03), Color(0xFFF5C066), "Right in photo")) }
    var active by remember { mutableStateOf(0) }
    var pair by remember { mutableStateOf<EyePairReading?>(null) }
    val answers = remember { mutableStateMapOf<String, Answer>() }
    var advice by remember { mutableStateOf<Advice?>(null) }
    var saved by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf<GalleryScan?>(null) }
    var scanning by remember { mutableStateOf(false) }

    val askMedia = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { scanning = true; scope.launch { scan = scanGallery(context); scanning = false } }
    }

    fun locate(b: Bitmap) {
        finding = true
        scope.launch {
            val eyes = safely("face landmarks") { withContext(Dispatchers.Default) { SkinServices.eyes.find(b) } }
            if (eyes != null) { markers[0] = markers[0].copy(circle = eyes.left); markers[1] = markers[1].copy(circle = eyes.right) }
            faceFound = eyes != null
            angle = eyes?.angle
            finding = false
        }
    }

    SkinScaffold("Eye flash check", onBack = back, scrollKey = advice == null) {
        ScreenHero("Red is the usual glow", "In a flash photo, healthy eyes usually glow red. A white or yellow glow in the pupil is worth a quick red reflex test by a doctor.")
        FactCard("Why this matters", Icons.Outlined.RemoveRedEye, Tone.PLUM, listOf(
            "A white pupil glow (leukocoria) can be the first sign of retinoblastoma, cataract or Coats' disease.",
            "In Baylor's CRADLE study, white-eye showed in family photos for 80% of affected children, about 1.3 years before diagnosis.",
            "It also appears now and then in healthy eyes with an off-angle flash, so one photo is never enough to worry.",
        ), footer = Source.RED_REFLEX.full)

        if (advice == null) {
            if (photo == null) {
                FactCard("How to take it", Icons.Outlined.FlashOn, Tone.SLATE, listOf(
                    "Dim room, flash ON.", "Phone about 1 metre away, at the baby's eye level.", "Baby looking straight at the phone. Take 2-3 photos."))
                PhotoInput("eyes", onPhoto = { b, _ -> photo = b; pair = null; locate(b) }, hint = "Take a flash photo or choose one.", flash = true)
                NilaCard(modifier = Modifier.fillMaxWidth(), tone = Tone.PLUM) {
                    Text("Scan recent photos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    MutedText("Nila looks through your last 300 gallery photos taken with flash, finds faces and reads both pupils. Everything stays on this phone.")
                    OutlinePillButton(if (scanning) "Scanning..." else "Scan my gallery", enabled = !scanning, icon = Icons.Outlined.Collections, modifier = Modifier.fillMaxWidth(), onClick = {
                        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
                        if (ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED) {
                            scanning = true; scope.launch { scan = scanGallery(context); scanning = false }
                        } else askMedia.launch(perm)
                    })
                    if (scanning) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp); MutedText("Face landmarks on the GPU, one photo at a time...")
                    }
                    scan?.let { GalleryScanResult(it) }
                }
            }
            photo?.let { bmp ->
                if (finding) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp); MutedText("Finding the eyes with MediaPipe face landmarks...")
                }
                faceFound?.let {
                    InfoNote(if (it) "Eyes found from face landmarks (${SkinServices.eyes.delegate}). Adjust the circles if they're off." else "No face found. Tap each pupil to place the circles.",
                        tone = if (it) Tone.TEAL else Tone.PEACH)
                }
                angle?.let { g ->
                    InfoNote("${g.words()} (gaze ${"%+.2f".format(g.gazeLeft)} / ${"%+.2f".format(g.gazeRight)} of eye width, head turn ${"%+.2f".format(g.headTurn)})." +
                        if (g.straight) " Good: a straight-on photo is the one to trust." else " Off-angle flash photos can glow white in healthy eyes, so a straight-on retake matters.",
                        tone = if (g.straight) Tone.TEAL else Tone.PEACH)
                }
                MarkerTabs(markers, active) { active = it }
                MarkablePhoto(bmp, markers, active, onMove = { i, c -> markers[i] = markers[i].copy(circle = c) })
                PillButton("Read the pupils", icon = Icons.Outlined.Analytics, modifier = Modifier.fillMaxWidth(), onClick = {
                    scope.launch { pair = safely("eye read") { withContext(Dispatchers.Default) { EyeReflex.readPair(PhotoStore.toPixels(bmp, 1600), markers[0].circle, markers[1].circle) } } }
                })
                pair?.let { p ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("Left in photo" to (markers[0].circle to p.left), "Right in photo" to (markers[1].circle to p.right)).forEach { (label, cr) ->
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val crop = remember(cr.first, bmp) { cropAround(bmp, cr.first, 3.0) }
                                Image(crop.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)))
                                Text(label, style = MaterialTheme.typography.labelLarge)
                                Text(cr.second.reflex.word, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    QuestionCard(EyeRules.TURN, answers[EyeRules.TURN.id], { answers[EyeRules.TURN.id] = it })
                    PillButton("See what to do", icon = Icons.Outlined.ArrowForward, modifier = Modifier.fillMaxWidth(), onClick = {
                        val wl = history.count { it.left == Reflex.WHITE.name } + if (p.left.reflex == Reflex.WHITE) 1 else 0
                        val wr = history.count { it.right == Reflex.WHITE.name } + if (p.right.reflex == Reflex.WHITE) 1 else 0
                        advice = EyeRules.advise(EyeRules.Input(p, wl, wr, answers.toMap(), angle))
                    })
                }
                OutlinePillButton("Use another photo", onClick = { photo = null; pair = null; faceFound = null; angle = null }, modifier = Modifier.fillMaxWidth())
            }
        } else {
            AdviceCard(advice!!)
            if (!saved) PillButton("Save", icon = Icons.Outlined.Check, modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch {
                    val path = withContext(Dispatchers.IO) { SkinServices.photos.save(photo!!, "eye") }
                    dao.addEye(EyeCheckEntity(timeMs = System.currentTimeMillis(), photoPath = path, left = pair!!.left.reflex.name, right = pair!!.right.reflex.name, step = advice!!.step.name))
                    saved = true
                }
            }) else OutlinePillButton("Another photo", onClick = { advice = null; photo = null; pair = null; saved = false; faceFound = null }, modifier = Modifier.fillMaxWidth())
        }

        if (history.isNotEmpty()) {
            Heading("History")
            NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 0.dp) {
                history.take(10).forEachIndexed { i, h ->
                    if (i > 0) RowDivider()
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Thumb(h.photoPath, 52)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Left: ${Reflex.valueOf(h.left).word.substringBefore(" (")} · Right: ${Reflex.valueOf(h.right).word.substringBefore(" (")}", style = MaterialTheme.typography.titleSmall)
                            MutedText(fmtDateTime(h.timeMs) + if (h.fromGallery) " · from gallery" else "")
                            StepPill(Step.valueOf(h.step))
                        }
                    }
                }
            }
        }
        NotADiagnosis()
    }
}

data class GalleryScan(val looked: Int, val withFlash: Int, val withFace: Int, val flagged: List<Pair<Bitmap, EyePairReading>>, val allRed: Int)

private suspend fun scanGallery(context: android.content.Context): GalleryScan = withContext(Dispatchers.Default) {
    val cr = context.contentResolver
    val uris = ArrayList<android.net.Uri>()
    cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), null, null,
        "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        while (c.moveToNext() && uris.size < 300) uris += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol))
    }
    var flash = 0; var faces = 0; var allRed = 0
    val flagged = ArrayList<Pair<Bitmap, EyePairReading>>()
    for (u in uris) {
        val fired = runCatching { cr.openInputStream(u)?.use { (ExifInterface(it).getAttributeInt(ExifInterface.TAG_FLASH, 0) and 1) == 1 } ?: false }.getOrDefault(false)
        if (!fired) continue
        flash++
        val bmp = SkinServices.photos.loadUri(u, 1600) ?: continue
        val eyes = runCatching { SkinServices.eyes.find(bmp) }.getOrNull() ?: continue
        faces++
        val p = runCatching { EyeReflex.readPair(PhotoStore.toPixels(bmp, 1600), eyes.left, eyes.right) }.getOrNull() ?: continue
        if (p.anyWhite || p.unequal) flagged += cropAround(bmp, NCircle((eyes.left.cx + eyes.right.cx) / 2, (eyes.left.cy + eyes.right.cy) / 2, kotlin.math.abs(eyes.right.cx - eyes.left.cx) * 0.9), 1.0) to p
        else if (p.left.reflex == Reflex.RED && p.right.reflex == Reflex.RED) allRed++
    }
    GalleryScan(uris.size, flash, faces, flagged, allRed)
}

@Composable
private fun GalleryScanResult(s: GalleryScan) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Looked at ${s.looked} photos: ${s.withFlash} with flash, ${s.withFace} with a face found.", style = MaterialTheme.typography.bodyMedium)
        Text("${s.allRed} showed a red glow in both eyes.", style = MaterialTheme.typography.bodyMedium)
        if (s.flagged.isEmpty()) MutedText("None showed a white glow.")
        else {
            Text("${s.flagged.size} photo(s) worth a closer look:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            s.flagged.take(6).forEach { (b, p) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(width = 120.dp, height = 60.dp).clip(RoundedCornerShape(10.dp)))
                    MutedText("Left: ${p.left.reflex.word.substringBefore(" (")}\nRight: ${p.right.reflex.word.substringBefore(" (")}")
                }
            }
            MutedText("One photo can mislead (angle, reflections). If the same eye shows white in more than one photo, ask for a red reflex check.")
        }
    }
}

// ---------------------------------------------------------------------------
// Yellow check (newborn jaundice)
// ---------------------------------------------------------------------------

private val SEEN = Question("seen", "Have you noticed any yellow colour in the skin or the whites of the eyes?")

@Composable
fun YellowScreen(baby: BabyEntity?, back: () -> Unit) {
    val dao = SkinServices.dao
    val history by dao.yellows().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var demoNewborn by remember { mutableStateOf(baby?.demo == true && (baby.ageDays() > 28)) }
    val ageHours = if (demoNewborn) 3 * 24 + 5 else baby?.ageHours() ?: 72
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    val markers = remember { mutableStateListOf(Marker(NCircle(0.5, 0.5, 0.08), Color(0xFF3DDBB0), "Eye white")) }
    var active by remember { mutableStateOf(0) }
    var useWhite by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<YellowReading?>(null) }
    var eyePlaced by remember { mutableStateOf(false) }
    val answers = remember { mutableStateMapOf<String, Answer>() }
    var advice by remember { mutableStateOf<Advice?>(null) }
    var saved by remember { mutableStateOf(false) }

    SkinScaffold("Yellow check", onBack = back, scrollKey = advice == null) {
        ScreenHero("Check in daylight", "Newborn jaundice is common and usually harmless, but a few signs mean go now. This walks you through WHO's check for health workers.")
        if (baby?.demo == true) NilaCard(modifier = Modifier.fillMaxWidth(), tone = Tone.PLUM) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Demo: check as a 3-day-old", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(demoNewborn, { demoNewborn = it })
            }
        }
        Text("Age used for the rules: ${if (ageHours < 72) "$ageHours hours" else "${ageHours / 24} days"}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ageHours > 28 * 24) InfoNote("This check is for the first weeks. Yellow skin or eyes in an older baby should always be checked by a doctor.", tone = Tone.PEACH)
        FactCard("How to look", Icons.Outlined.WbSunny, Tone.PEACH, listOf(
            "Use daylight near a window; indoor bulbs hide yellow.",
            "Look at the whites of the eyes, the gums, the palms and the soles.",
            "Press gently on the nose or forehead and watch the colour as you let go.",
            "On brown or black skin, the eyes, gums, palms and soles show it best.",
        ), footer = Source.NHS_JAUNDICE.full)

        if (advice == null) {
            Heading("Optional: photo of the eye white")
            MutedText("Kept as a colour-corrected record to compare day to day. It is not a bilirubin test, and the questions below decide what to do.")
            if (photo == null) PhotoInput("yellow", onPhoto = { b, _ ->
                photo = b; reading = null
                // Place the circle on the white of the eye: face landmarks for a face, colour for a close-up.
                scope.launch {
                    val c = safely("sclera landmarks") { withContext(Dispatchers.Default) { SkinServices.eyes.sclera(b) } }
                        ?: safely("sclera colour") { withContext(Dispatchers.Default) { Yellowness.suggestSpot(PhotoStore.toPixels(b, 600)) } }
                    c?.let { markers[0] = markers[0].copy(circle = it) }
                    eyePlaced = c != null
                }
            }, hint = "Put a white tissue or paper in the photo for colour correction.")
            if (photo != null && eyePlaced) InfoNote("Nila placed the circle on the white of the eye. Move it if it's off; it should sit only on the white.", tone = Tone.BLUE)
            photo?.let { bmp ->
                MarkerTabs(markers, active) { active = it }
                MarkablePhoto(bmp, markers, active, onMove = { i, c -> markers[i] = markers[i].copy(circle = c) }, dots = reading?.whitePoints ?: emptyList())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("I'll mark the white paper myself", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(useWhite, {
                        useWhite = it
                        if (it && markers.size == 1) markers.add(Marker(NCircle(0.15, 0.15, 0.08), Color.White, "White paper"))
                        if (!it && markers.size > 1) { markers.removeAt(1); active = 0 }
                    })
                }
                OutlinePillButton("Read the colour", icon = Icons.Outlined.Analytics, modifier = Modifier.fillMaxWidth(), onClick = {
                    scope.launch { reading = safely("yellow read") { withContext(Dispatchers.Default) { Yellowness.read(PhotoStore.toPixels(bmp), YellowReading.Site.EYE_WHITE, markers[0].circle, markers.getOrNull(1)?.circle) } } }
                })
                reading?.let { r ->
                    FactCard("Photo reading", Icons.Outlined.Analytics, Tone.TEAL, listOfNotNull(r.looks, "Yellowness (b*, colour-corrected): ${"%.1f".format(r.yellowness)}",
                        if (r.whiteTinted) "The white used for correction (dots) looks tinted itself. If the dots are on the eye rather than a tissue, mark the tissue yourself." else null),
                        footer = "Describes the photo only. Not a bilirubin level.")
                }
            }
            Heading("The checklist")
            (listOf(SEEN) + JaundiceRules.questions).forEach { q -> QuestionCard(q, answers[q.id], { answers[q.id] = it }) }
            PillButton("See what to do", icon = Icons.Outlined.ArrowForward, modifier = Modifier.fillMaxWidth(), onClick = {
                val prev = history.firstOrNull { it.eyeYellowness != null && it.eyeWhiteCorrected }?.eyeYellowness
                advice = JaundiceRules.advise(JaundiceRules.Input(ageHours, answers.toMap(), answers[SEEN.id] == Answer.YES,
                    listOfNotNull(reading), prev, baby?.preterm ?: false))
            })
        } else {
            AdviceCard(advice!!)
            FactCard("Worth asking", Icons.Outlined.Check, Tone.TEAL, advice!!.askDoctor)
            if (!saved) PillButton("Save", icon = Icons.Outlined.Check, modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch {
                    val path = photo?.let { withContext(Dispatchers.IO) { SkinServices.photos.save(it, "yellow") } }
                    dao.addYellow(YellowCheckEntity(timeMs = System.currentTimeMillis(), ageHours = ageHours, eyeYellowness = reading?.yellowness,
                        eyeWhiteCorrected = reading?.whiteCorrected ?: false, photoPath = path, answers = Codec.answers(answers.toMap()),
                        step = advice!!.step.name, reasons = Codec.list(advice!!.reasons.map { reasonText(it) })))
                    saved = true
                }
            }) else OutlinePillButton("Check again", onClick = { advice = null; saved = false; photo = null; reading = null; answers.clear() }, modifier = Modifier.fillMaxWidth())
        }
        val values = history.asReversed().mapNotNull { it.eyeYellowness }
        if (values.size >= 2) NilaCard(modifier = Modifier.fillMaxWidth()) { TrendChart(values, "Eye-white yellowness over checks (b*)") }
        if (history.isNotEmpty()) {
            Heading("History")
            NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 0.dp) {
                history.take(10).forEachIndexed { i, h ->
                    if (i > 0) RowDivider()
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(fmtDateTime(h.timeMs), style = MaterialTheme.typography.titleSmall)
                            MutedText("Age ${h.ageHours / 24} d ${h.ageHours % 24} h" + (h.eyeYellowness?.let { " · eye b* %.1f".format(it) } ?: ""))
                        }
                        StepPill(Step.valueOf(h.step))
                    }
                }
            }
        }
        NotADiagnosis()
    }
}

// ---------------------------------------------------------------------------
// Glass test
// ---------------------------------------------------------------------------

@Composable
fun GlassTestScreen(go: (String) -> Unit, back: () -> Unit) {
    var answer by remember { mutableStateOf<Answer?>(null) }
    SkinScaffold("Glass test", onBack = back) {
        ScreenHero("Does the rash fade?", "For a rash with a fever or an unwell baby. It takes ten seconds and needs a clear glass, not an app.")
        FactCard("How to do it", Icons.Outlined.WineBar, Tone.SLATE, listOf(
            "Press the side of a clear drinking glass firmly against the spots.",
            "Look through the glass. Do the spots fade, or can you still see them?",
            "On brown or black skin, check paler areas: palms, soles, inside the eyelids, the roof of the mouth.",
        ), footer = Source.NHS_RASH.full)
        InfoNote("Nila does not judge this from a photo. Where a missed sign could be dangerous, a person decides, not the camera.", icon = Icons.Outlined.Info, tone = Tone.BLUE)
        QuestionCard(SkinRules.GLASS, answer, { answer = it })
        answer?.let { a ->
            val adv = when (a) {
                Answer.YES -> Advice(Step.URGENT, listOf(Reason("Spots that don't fade under a glass need a doctor straight away.", Source.NHS_RASH, Step.URGENT)), SkinRules.doNow(Step.URGENT))
                Answer.UNSURE -> Advice(Step.DOCTOR_TODAY, listOf(Reason("If you can't tell, or your baby seems unwell, treat it as not fading and see a doctor now.", Source.NHS_RASH, Step.DOCTOR_TODAY)),
                    listOf("If your baby is unwell, has a fever, or you're worried, go to a doctor or hospital now.", "Otherwise call your doctor now."))
                Answer.NO -> Advice(Step.WATCH, listOf(Reason("Spots that fade under pressure are reassuring, but a baby who seems very unwell still needs help.", Source.NHS_RASH, Step.WATCH)),
                    listOf("Keep checking the rash and how your baby is.", "Track it in the skin diary to see if it spreads."))
            }
            AdviceCard(adv)
            if (a == Answer.NO) OutlinePillButton("Open the skin diary", onClick = { go("skin") }, icon = Icons.Outlined.Spa, modifier = Modifier.fillMaxWidth())
            SafetyNetCard(SkinRules.safetyNet)
        }
        NotADiagnosis()
    }
}
