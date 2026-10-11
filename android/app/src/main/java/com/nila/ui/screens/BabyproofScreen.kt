package com.nila.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Chair
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ElectricalServices
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Stairs
import androidx.compose.material.icons.outlined.Toys
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nila.babyproof.BabyproofViewModel
import com.nila.babyproof.BabyproofViewModel.Phase
import com.nila.babyproof.Finding
import com.nila.babyproof.Scan
import com.nila.babyproof.Status
import com.nila.data.Severity
import com.nila.ui.components.IconBadge
import com.nila.ui.components.InfoNote
import com.nila.ui.components.NilaCard
import com.nila.ui.components.OutlinePillButton
import com.nila.ui.components.PillButton
import com.nila.ui.components.RoomArt
import com.nila.ui.components.ScreenHero
import com.nila.ui.components.SectionTitle
import com.nila.ui.components.TonePill
import com.nila.ui.theme.Tone
import com.nila.ui.theme.calmColors
import com.nila.ui.theme.severityColors
import com.nila.ui.theme.toneColors
import com.nila.babyproof.Severity as HazardLevel

/**
 * Babyproof a room: photograph it, and get back what to cover, lock, anchor or
 * move -- each with the fix written in advance from published childproofing
 * guidance, never by the model.
 *
 * The models only point; the list is built by plain rules (see
 * [com.nila.babyproof.Rules]). So every item carries what Nila actually saw, and
 * an item it was unsure about says so ("Worth a check") instead of being dressed
 * up as a finding.
 */
@Composable
fun BabyproofScreen(vm: BabyproofViewModel = viewModel()) {
    val phase by vm.phase.collectAsState()
    val fixed by vm.fixed.collectAsState()

    // vivo freezes a backgrounded app within seconds; a scan keeps the screen on.
    val view = LocalView.current
    val working = phase is Phase.Working
    DisposableEffect(working) {
        view.keepScreenOn = working
        onDispose { view.keepScreenOn = false }
    }
    // Back from a result returns to the start of the page, which is where "check another room" goes.
    BackHandler(enabled = phase is Phase.Done || phase is Phase.Failed) { vm.reset() }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) vm.scanCameraPhoto()
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.scanUri(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        when (val p = phase) {
            Phase.Idle -> StartPage(
                vm,
                onCamera = { takePicture.launch(vm.cameraTarget()) },
                onGallery = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            )
            is Phase.Working -> WorkingCard(p)
            is Phase.Done -> Results(p, fixed, vm::toggleFixed, onAgain = vm::reset)
            is Phase.Failed -> {
                NilaCard(Modifier.fillMaxWidth(), borderColor = severityColors(Severity.ATTENTION).accent) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconBadge(Icons.Outlined.WarningAmber, Tone.PEACH)
                        Text(p.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
                PillButton("Try again", vm::reset, Modifier.fillMaxWidth(), icon = Icons.Outlined.PhotoCamera)
            }
        }
    }
}

// ------------------------------------------------------------------ start

@Composable
private fun StartPage(vm: BabyproofViewModel, onCamera: () -> Unit, onGallery: () -> Unit) {
    val blocked = vm.watchRunning
    ScreenHero(
        title = "Is this room baby-safe?",
        body = "Take a photo of a room your baby will crawl in. Nila points out what to " +
            "cover, lock, anchor or move. The photo stays on this phone.",
        art = { RoomArt() },
    )

    if (blocked) {
        InfoNote(
            "The safety watch is using the phone's AI chip. Stop the watch on the Watch tab, " +
                "then come back to check a room.",
            tone = Tone.PEACH,
            title = "Watch is running",
        )
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.height(IntrinsicSize.Max).alpha(if (blocked) 0.45f else 1f),
    ) {
        SourceTile("Take a photo", "Of the whole room", Icons.Outlined.PhotoCamera, Tone.TEAL,
                   Modifier.weight(1f).fillMaxHeight(), enabled = !blocked, onClick = onCamera)
        SourceTile("Upload a picture", "From your gallery", Icons.Outlined.Image, Tone.BLUE,
                   Modifier.weight(1f).fillMaxHeight(), enabled = !blocked, onClick = onGallery)
    }

    PhotoTips()

    if (vm.samples.isNotEmpty()) {
        SectionTitle("Or try a sample room")
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .alpha(if (blocked) 0.45f else 1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            vm.samples.forEach { s -> SampleTile(vm, s, enabled = !blocked) }
        }
    }

    if (!vm.detectorInstalled || !vm.visionModelInstalled) {
        InfoNote(
            when {
                !vm.detectorInstalled && !vm.visionModelInstalled ->
                    "The babyproofing models are not on this phone yet, so a room cannot be checked."
                !vm.detectorInstalled ->
                    "The object finder is not on this phone, so Nila can only list what it notices, without pointing to it."
                else ->
                    "The vision model is not on this phone, so every item is the object finder's guess. Expect a few wrong ones."
            },
            tone = Tone.SLATE,
            title = "Models",
        )
    }

    InfoNote(
        "Nila looks for about 30 common dangers. It can miss things and it can be wrong. " +
            "Also get down to your baby's height and look around the room yourself.",
        icon = Icons.Outlined.VerifiedUser,
        tone = Tone.PLUM,
        title = "A helper, not an inspection",
    )
}

@Composable
private fun SourceTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tone: Tone,
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    NilaCard(modifier = modifier, onClick = if (enabled) onClick else null, contentPadding = PaddingValues(16.dp),
             verticalSpacing = 12.dp) {
        IconBadge(icon, tone, size = 52.dp, iconSize = 26.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null,
                 tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PhotoTips() {
    NilaCard(Modifier.fillMaxWidth(), tone = Tone.TEAL, verticalSpacing = 10.dp) {
        Text("For a good photo", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        listOf(
            "Stand in a corner so the whole room fits",
            "Daylight or the lights on",
            "Then take one low down, at a crawling baby's height",
        ).forEach { tip ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Check, contentDescription = null, tint = toneColors(Tone.TEAL).ink,
                     modifier = Modifier.size(18.dp))
                Text(tip, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SampleTile(vm: BabyproofViewModel, s: BabyproofViewModel.Sample, enabled: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val thumb = remember(s.file) {
        runCatching {
            context.assets.open("babyproof/samples/${s.file}").use {
                android.graphics.BitmapFactory.decodeStream(it, null,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })
            }
        }.getOrNull()
    }
    NilaCard(
        modifier = Modifier.width(150.dp),
        contentPadding = PaddingValues(8.dp),
        verticalSpacing = 8.dp,
        onClick = if (enabled) ({ vm.scanSample(s) }) else null,
    ) {
        thumb?.let {
            Image(it.asImageBitmap(), contentDescription = s.title,
                  modifier = Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(12.dp)),
                  contentScale = ContentScale.Crop)
        }
        Text(s.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
             modifier = Modifier.padding(horizontal = 4.dp))
    }
}

// ---------------------------------------------------------------- working

@Composable
private fun WorkingCard(p: Phase.Working) {
    NilaCard(Modifier.fillMaxWidth(), tone = Tone.TEAL, verticalSpacing = 14.dp) {
        Image(
            bitmap = p.photo.asImageBitmap(),
            contentDescription = "The room you photographed",
            modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(14.dp)),
            contentScale = ContentScale.Crop,
        )
        Text(p.step, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        LinearProgressIndicator(
            progress = { p.progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
        )
        Text("About a minute. Keep this screen open; nothing leaves the phone.",
             style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- results

/**
 * One card per kind of hazard: three windows are one "Window" card with one
 * number, ringed in three places on the photo. A doubtful window joins the card
 * of a window Nila is sure about rather than appearing twice.
 */
private class Group(val hazard: com.nila.babyproof.Hazard, val items: List<Finding>) {
    val status: Status = if (items.any { it.status == Status.FIX }) Status.FIX else Status.CHECK
    /** The finding that speaks for the group: a confirmed one, the strongest. */
    val lead: Finding = items.filter { it.status == status }.maxBy { it.score }
    val places: Int = items.count { it.box != null }
}

private val Finding.strength: Float get() = score / hazard.threshold

private fun groupsOf(scan: Scan): List<Group> =
    scan.toFix.groupBy { it.hazard.id }.values.map { Group(it.first().hazard, it) }
        .sortedWith(compareBy({ it.status.ordinal }, { it.hazard.severity.rank }, { !it.hazard.critical }, { -it.lead.strength }))

/** The list a parent reads: what to fix (numbered on the photo), what to check, and what is already done. */
@Composable
private fun Results(p: Phase.Done, fixed: Set<Int>, onToggle: (Int) -> Unit, onAgain: () -> Unit) {
    val scan = p.scan
    val groups = remember(scan) { groupsOf(scan) }
    val fixFirst = groups.withIndex().filter { it.value.status == Status.FIX }
    val toCheck = groups.withIndex().filter { it.value.status == Status.CHECK }
    var selected by remember(scan) { mutableStateOf<Int?>(null) }
    val done = remember(scan) { scan.done.groupBy { it.hazard.id }.values.toList() }

    RoomPhoto(p.photo, scan, groups, fixed, selected) { selected = it }

    Summary(fixFirst.size, toCheck.size, done.size, fixed.count { it < groups.size })

    if (fixFirst.isNotEmpty()) {
        SectionTitle("Fix these", count = fixFirst.size)
        fixFirst.forEach { (i, g) ->
            GroupCard(i + 1, g, done = i in fixed, selected = selected == i,
                      onSelect = { selected = if (selected == i) null else i }, onToggle = { onToggle(i) })
        }
    }
    if (toCheck.isNotEmpty()) {
        SectionTitle("Worth a check", count = toCheck.size)
        toCheck.forEach { (i, g) ->
            GroupCard(i + 1, g, done = i in fixed, selected = selected == i,
                      onSelect = { selected = if (selected == i) null else i }, onToggle = { onToggle(i) })
        }
    }
    if (done.isNotEmpty()) {
        SectionTitle("Already babyproofed", count = done.size)
        NilaCard(Modifier.fillMaxWidth(), verticalSpacing = 4.dp) {
            done.forEach { same ->
                val f = same.maxBy { it.score }
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = calmColors().accent)
                    Column(Modifier.weight(1f)) {
                        Text(f.hazard.label + if (same.size > 1) " · ${same.size} places" else "",
                             style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        f.answer?.obj?.takeIf { it.isNotBlank() }?.let {
                            Text("Looks covered or secured: ${it.trimEnd('.').lowercase()}", style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (groups.isEmpty() && done.isEmpty()) {
        NilaCard(Modifier.fillMaxWidth(), borderColor = calmColors().accent) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Outlined.Shield, Tone.TEAL)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Nothing obvious in this photo", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("Try another angle, or a photo from lower down. Small things on the floor are easy to miss.",
                         style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    InfoNote(
        "Nila can miss things and can be wrong. The fixes come from the AAP and the US Consumer " +
            "Product Safety Commission, not from the AI.",
        icon = Icons.Outlined.VerifiedUser,
        tone = Tone.PLUM,
        title = "Good to know",
    )
    p.sample?.let { s ->
        val uri = LocalUriHandler.current
        Text("Sample photo: ${s.credit}", style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant,
             modifier = if (s.url.isNotBlank()) Modifier.clickable { runCatching { uri.openUri(s.url) } } else Modifier)
    }
    PillButton("Check another room", onAgain, Modifier.fillMaxWidth(), icon = Icons.Outlined.PhotoCamera)
}

@Composable
private fun Summary(fix: Int, check: Int, done: Int, ticked: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        if (fix > 0) TonePill("$fix to fix", Tone.PEACH, dot = true)
        if (check > 0) TonePill("$check to check", Tone.SLATE, dot = true)
        if (done > 0) TonePill("$done done", Tone.TEAL, icon = Icons.Outlined.Check)
        if (ticked > 0) TonePill("$ticked sorted", Tone.TEAL, icon = Icons.Outlined.CheckCircle)
    }
}

/** The photo with a numbered ring on each place; tap a ring or a card to pick a hazard out. */
@Composable
private fun RoomPhoto(
    photo: Bitmap,
    scan: Scan,
    groups: List<Group>,
    fixed: Set<Int>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val colors = groups.map { groupColor(it) }
    val calm = calmColors().accent
    val image = remember(photo) { photo.asImageBitmap() }
    Box(
        Modifier.fillMaxWidth()
            .aspectRatio(photo.width.toFloat() / photo.height)
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
    ) {
        Image(image, contentDescription = "The room, with what Nila found marked", modifier = Modifier.fillMaxSize(),
              contentScale = ContentScale.FillBounds)
        Canvas(
            Modifier.fillMaxSize().pointerInput(groups) {
                detectTapGestures { pos ->
                    val x = pos.x / size.width; val y = pos.y / size.height
                    val hit = groups.withIndex().flatMap { (gi, g) -> g.items.mapNotNull { f -> f.box?.let { gi to it } } }
                        .filter { (_, b) -> x in b.l..b.r && y in b.t..b.b }
                        .minByOrNull { (_, b) -> b.area }
                    onSelect(hit?.first)
                }
            }
        ) {
            val w = size.width; val h = size.height
            // What is already done first, quietly, under the rest.
            scan.done.forEach { f ->
                val b = f.box ?: return@forEach
                drawRoundRect(calm, Offset(b.l * w, b.t * h), Size(b.w * w, b.h * h), CornerRadius(10f), style = Stroke(3f))
            }
            groups.forEachIndexed { gi, g ->
                val dim = (selected != null && selected != gi) || gi in fixed
                val c = colors[gi].copy(alpha = if (dim) 0.35f else 1f)
                val stroke = if (selected == gi) 8f else 5f
                g.items.forEach { f ->
                    val b = f.box ?: return@forEach
                    drawRoundRect(Color.White.copy(alpha = if (dim) 0.3f else 0.8f), Offset(b.l * w, b.t * h),
                                  Size(b.w * w, b.h * h), CornerRadius(12f), style = Stroke(stroke + 3f))
                    drawRoundRect(c, Offset(b.l * w, b.t * h), Size(b.w * w, b.h * h), CornerRadius(12f), style = Stroke(stroke))
                    // The number, on a disc at the box's top-left corner.
                    val r = 30f
                    val cx = (b.l * w).coerceIn(r, w - r); val cy = (b.t * h).coerceIn(r, h - r)
                    drawCircle(Color.White, r + 4f, Offset(cx, cy), alpha = if (dim) 0.5f else 1f)
                    drawCircle(c, r, Offset(cx, cy))
                    val label = measurer.measure("${gi + 1}", TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                    drawText(label, topLeft = Offset(cx - label.size.width / 2f, cy - label.size.height / 2f))
                }
            }
        }
    }
}

@Composable
private fun groupColor(g: Group): Color =
    if (g.status == Status.FIX) severityColors(levelOf(g.hazard.severity)).accent else toneColors(Tone.SLATE).ink

@Composable
private fun GroupCard(
    number: Int,
    g: Group,
    done: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
) {
    val level = levelOf(g.hazard.severity)
    val sev = severityColors(level)
    val isFix = g.status == Status.FIX
    val uri = LocalUriHandler.current
    val accent = groupColor(g)
    NilaCard(
        modifier = Modifier.fillMaxWidth().animateContentSize().alpha(if (done) 0.6f else 1f),
        borderColor = if (selected) accent else null,
        onClick = onSelect,
        verticalSpacing = 10.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(if (g.places > 0) accent else MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center) {
                if (g.places > 0) Text("$number", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                else Icon(Icons.Outlined.Visibility, contentDescription = null, modifier = Modifier.size(18.dp),
                          tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(g.hazard.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                     maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(seenLine(g), style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(categoryIcon(g.hazard.category), contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isFix) LevelChip(g.hazard.severity, level) else TonePill("Not sure", Tone.SLATE, icon = Icons.Outlined.HelpOutline)
            TonePill(categoryName(g.hazard.category), Tone.SLATE)
        }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(if (isFix) sev.container.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceContainer)
                .padding(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("What to do", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                     color = if (isFix) sev.accent else MaterialTheme.colorScheme.onSurface)
                Text(g.hazard.fix, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .clickable { runCatching { uri.openUri(g.hazard.source.url) } }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp),
                     tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Source: ${g.hazard.source.name}", style = MaterialTheme.typography.labelMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinePillButton(if (done) "Done" else "Mark done", onToggle,
                              icon = if (done) Icons.Outlined.CheckCircle else Icons.Outlined.Check)
        }
    }
}

@Composable
private fun LevelChip(level: HazardLevel, colorsOf: Severity) {
    val c = severityColors(colorsOf)
    Row(Modifier.clip(CircleShape).background(c.container).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(c.accent))
        Text(level.label, color = c.accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

/** What Nila based the card on, in a parent's words. */
private fun seenLine(g: Group): String {
    val f = g.lead
    val obj = f.answer?.obj?.trim()?.trimEnd('.')?.takeIf { it.isNotBlank() }?.lowercase()
    val where = if (g.places > 1) " · ${g.places} places" else ""
    return when {
        g.status == Status.FIX && obj != null -> "Seen: $obj$where"
        g.status == Status.FIX -> "Seen in the photo$where"
        g.places == 0 -> "Nila noticed this in the room but could not point to it"
        f.answer == null -> "Possibly one here; have a look$where"
        else -> "This might be one; have a look$where"
    }
}

private fun levelOf(s: HazardLevel): Severity = when (s) {
    HazardLevel.URGENT -> Severity.URGENT
    HazardLevel.HIGH -> Severity.ATTENTION
    HazardLevel.MEDIUM -> Severity.NOTE
}

private fun categoryIcon(category: String): ImageVector = when (category) {
    "electric" -> Icons.Outlined.ElectricalServices
    "fall" -> Icons.Outlined.Stairs
    "tip-over" -> Icons.Outlined.Chair
    "sharp" -> Icons.Outlined.ContentCut
    "poison" -> Icons.Outlined.Science
    "choking" -> Icons.Outlined.Toys
    "burn" -> Icons.Outlined.LocalFireDepartment
    "water" -> Icons.Outlined.WaterDrop
    "strangle" -> Icons.Outlined.Cable
    else -> Icons.Outlined.WarningAmber
}

private fun categoryName(category: String): String = when (category) {
    "electric" -> "Shock"
    "fall" -> "Fall"
    "tip-over" -> "Tip-over"
    "sharp" -> "Cuts"
    "poison" -> "Poison"
    "choking" -> "Choking"
    "burn" -> "Burns"
    "water" -> "Drowning"
    "strangle" -> "Cords"
    else -> category.replaceFirstChar { it.uppercase() }
}
