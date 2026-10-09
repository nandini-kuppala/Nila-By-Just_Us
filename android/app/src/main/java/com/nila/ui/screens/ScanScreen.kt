package com.nila.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PriorityHigh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nila.assistant.agents.AgentStep
import com.nila.assistant.agents.MedicineReviewResult
import com.nila.assistant.agents.Verdict
import com.nila.data.MedicineScanRecord
import com.nila.data.Severity
import com.nila.ui.AppState
import com.nila.ui.components.CardShape
import com.nila.ui.components.IconBadge
import com.nila.ui.components.InfoNote
import com.nila.ui.components.MedicineArt
import com.nila.ui.components.NilaCard
import com.nila.ui.components.OrDivider
import com.nila.ui.components.OutlinePillButton
import com.nila.ui.components.PillButton
import com.nila.ui.components.ScreenHero
import com.nila.ui.components.SectionTitle
import com.nila.ui.theme.SeverityColors
import com.nila.ui.theme.Tone
import com.nila.ui.theme.calmColors
import com.nila.ui.theme.severityColors
import com.nila.ui.theme.toneColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Photograph, upload or type a medicine, and watch four specialists check it.
 *
 * The pipeline is shown rather than hidden behind a spinner. Each stage names
 * what it looked at, what it concluded, and what it could not determine -- so a
 * "likely safe" that was reached without knowing her allergies looks visibly
 * different from one that cross-checked three conditions and found nothing.
 */
@Composable
fun ScanScreen(state: AppState) {
    // Build the OCR sessions while the user is still deciding which button to
    // press, rather than on the shutter.
    LaunchedEffect(Unit) { state.prepareScanner() }

    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var typed by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    val review by state.review.collectAsState()
    val stage by state.scanStage.collectAsState()
    val scannedText by state.scannedText.collectAsState()
    val thinking by state.thinking.collectAsState()
    val mother by state.mother.collectAsState()
    val history by state.scanHistory.collectAsState()

    /**
     * Leaving the screen puts it back to a blank form.
     *
     * The verdict used to persist in the view model, so coming back to Scan
     * showed the last strip somebody had checked, still sitting under the
     * buttons. That is actively misleading on this screen in particular: the
     * next person to open it is usually about to check a *different* medicine,
     * and a stale AVOID card above a fresh photograph is the worst possible
     * arrangement of those two facts. The scan is in the history below either
     * way, which is where a previous answer belongs.
     */
    DisposableEffect(Unit) {
        onDispose { state.clearReview() }
    }

    fun reset() { photo = null; typed = ""; state.clearReview() }
    // Back from a verdict returns to the form, which is where "check another"
    // goes; it does not leave the tab.
    BackHandler(enabled = review != null) { reset() }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            photo = bitmap
            state.reviewMedicine(bitmap = bitmap)
        }
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            photo = state.recordStore.decodeUri(uri)
            state.reviewMedicine(uri = uri)
        }
    }

    fun checkTyped() {
        if (typed.isBlank() || thinking) return
        photo = null
        state.reviewMedicine(typed = typed)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        val result = review
        if (result == null) {
            ScreenHero(
                title = "Check a medicine",
                body = "Is it likely safe while breastfeeding? Checked against sources " +
                    "and your own health record.",
                art = { MedicineArt() },
            )

            if (mother?.isComplete != true) {
                InfoNote(
                    "Add your conditions and allergies under Health to make this a " +
                        "check about you.",
                    tone = Tone.PEACH,
                )
            }

            // ---- the form, hidden once there is an answer
            //
            // Two buttons and a text field above a verdict is an invitation to
            // start a second check while reading the first one. Once an answer
            // exists the only control is "check another", which puts the form
            // back.
            if (stage == null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.height(IntrinsicSize.Max),
                ) {
                    SourceTile(
                        title = "Take a photo",
                        subtitle = "Of the pack or strip",
                        icon = Icons.Outlined.PhotoCamera,
                        tone = Tone.TEAL,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) { takePicture.launch(null) }
                    SourceTile(
                        title = "Upload a picture",
                        subtitle = "From your gallery",
                        icon = Icons.Outlined.Image,
                        tone = Tone.BLUE,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        pickImage.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    }
                }

                OrDivider()

                SearchField(
                    value = typed,
                    onValueChange = { typed = it },
                    onSubmit = ::checkTyped,
                    enabled = !thinking,
                )

                InfoNote(
                    "This checks safety for you while breastfeeding. Never about " +
                        "giving medicine to a baby, and never a dose."
                )
            }
        }

        if (stage != null) {
            ProgressCard(photo, stage!!)
        }

        result?.let { r ->
            ResultHeader(r, photo, typed.takeIf { photo == null && it.isNotBlank() })
            VerdictCard(r)
            MeaningCard(r)
            if (r.sources.isNotEmpty()) SourceChips(r.sources)
            InfoNote(
                "General information, not medical advice. Follow the instructions on " +
                    "the packet or your doctor's advice.",
                icon = Icons.Outlined.VerifiedUser,
                tone = Tone.PLUM,
                title = "Important",
            )
            AgentTrace(r.steps)

            scannedText?.takeIf { it.isNotBlank() }?.let { text ->
                NilaCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.DocumentScanner, contentDescription = null,
                             tint = MaterialTheme.colorScheme.primary)
                        Text("What the camera read", style = MaterialTheme.typography.titleSmall,
                             fontWeight = FontWeight.Bold)
                    }
                    Text(text.take(240), style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.Shield, contentDescription = null,
                     tint = calmColors().accent, modifier = Modifier.size(18.dp))
                Text("Saved to your recent checks, on this phone only.",
                     style = MaterialTheme.typography.labelLarge,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PillButton("Check another medicine", ::reset, Modifier.fillMaxWidth(),
                       icon = Icons.Outlined.Search)
        }

        if (result == null && stage == null && history.isNotEmpty()) {
            ScanHistory(
                scans = if (showAll) history else history.take(3),
                total = history.size,
                showAll = showAll,
                onToggleAll = { showAll = !showAll },
                onDelete = state::deleteScan,
            )
        }
    }
}

// ------------------------------------------------------------------ form

@Composable
private fun SourceTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tone: Tone,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    NilaCard(modifier = modifier, onClick = onClick, contentPadding = PaddingValues(16.dp),
             verticalSpacing = 12.dp) {
        IconBadge(icon, tone, size = 52.dp, iconSize = 26.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null,
                 tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Type a name and press search, or the arrow. */
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    enabled: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text("Type the name of a medicine...") },
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Clear",
                             tint = scheme.onSurfaceVariant)
                    }
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (enabled) scheme.primary else scheme.surfaceContainer)
                            .clickable(enabled = enabled, onClick = onSubmit),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "Check",
                             tint = if (enabled) scheme.onPrimary else scheme.onSurfaceVariant,
                             modifier = Modifier.size(20.dp))
                    }
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = scheme.outlineVariant,
            unfocusedContainerColor = scheme.surfaceContainerLowest,
            focusedContainerColor = scheme.surfaceContainerLowest,
        ),
    )
}

@Composable
private fun ProgressCard(photo: Bitmap?, stage: String) {
    NilaCard(modifier = Modifier.fillMaxWidth(), tone = Tone.TEAL, verticalSpacing = 14.dp) {
        photo?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "The medicine you photographed",
                modifier = Modifier.fillMaxWidth().height(170.dp)
                    .clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Crop,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
            Text(stage, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---------------------------------------------------------------- verdict

/** What was checked: the photo if there is one, and the name it was read as. */
@Composable
private fun ResultHeader(result: MedicineReviewResult, photo: Bitmap?, typed: String?) {
    val scheme = MaterialTheme.colorScheme
    val name = result.displayName.ifBlank { typed.orEmpty() }
        .replaceFirstChar { it.uppercase() }
    NilaCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (photo != null) {
                Image(
                    bitmap = photo.asImageBitmap(),
                    contentDescription = "The medicine you photographed",
                    modifier = Modifier.size(width = 104.dp, height = 84.dp)
                        .clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                IconBadge(Icons.Outlined.Medication, Tone.PINK, size = 64.dp, iconSize = 30.dp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name.ifBlank { "Unidentified medicine" },
                     style = MaterialTheme.typography.headlineSmall)
                Text(if (photo != null) "From a photo" else "Typed by you",
                     style = MaterialTheme.typography.bodyMedium,
                     color = scheme.onSurfaceVariant)
            }
        }
    }
}

private val Verdict.icon: ImageVector
    get() = when (this) {
        Verdict.LIKELY_SAFE -> Icons.Outlined.Check
        Verdict.CAUTION -> Icons.Outlined.PriorityHigh
        Verdict.AVOID -> Icons.Outlined.Close
        Verdict.UNKNOWN -> Icons.Outlined.HelpOutline
    }

@Composable
private fun verdictColors(verdict: Verdict): SeverityColors = when (verdict) {
    Verdict.LIKELY_SAFE -> calmColors()
    Verdict.CAUTION -> severityColors(Severity.ATTENTION)
    Verdict.AVOID -> severityColors(Severity.URGENT)
    Verdict.UNKNOWN -> severityColors(Severity.NOTE)
}

/**
 * The answer, on a ground that carries its own text colour.
 *
 * This is the card that was unreadable. Its background was a hardcoded pale
 * green, amber or pink, and its text was `onSurface` -- which the dark scheme
 * resolves to a near-white. [severityColors] hands back the ground and the text
 * colour that belongs on it as one object, so a caller cannot take one without
 * the other.
 */
@Composable
private fun VerdictCard(result: MedicineReviewResult) {
    val colors = verdictColors(result.verdict)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(colors.container)
            .border(1.dp, colors.accent.copy(alpha = 0.25f), CardShape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).background(colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(result.verdict.icon, contentDescription = null,
                     tint = colors.container, modifier = Modifier.size(28.dp))
            }
            Text(result.verdict.label, style = MaterialTheme.typography.titleLarge,
                 fontWeight = FontWeight.Bold, color = colors.accent)
        }
        // The headline leads, because it is the sentence somebody reads at a
        // pharmacy counter. The summary explains it underneath.
        Text(result.headline, style = MaterialTheme.typography.titleMedium,
             fontWeight = FontWeight.SemiBold, color = colors.onContainer)
        summaryTail(result)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onContainerMuted)
        }
    }
}

/** The cautions, under a heading that says what they are for. */
@Composable
private fun MeaningCard(result: MedicineReviewResult) {
    val cautions = result.cautions.filter { it.isNotBlank() }
    if (cautions.isEmpty()) return
    NilaCard(modifier = Modifier.fillMaxWidth(), tone = Tone.SLATE) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(Icons.Outlined.Description, Tone.SLATE, size = 40.dp, iconSize = 21.dp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("What this means", style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.Bold)
                cautions.forEach {
                    Text(it, style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SourceChips(sources: List<String>) {
    NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 10.dp) {
        Text("Sources (${sources.size})", style = MaterialTheme.typography.titleSmall,
             fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sources.forEach {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null,
                         tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Text(it, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
}

/**
 * The summary minus its duplicated first sentence.
 *
 * `summary` is built to stand alone in a notification, so it opens by restating
 * the headline. On the card the headline is already the largest thing above it,
 * and printing it twice in two sizes is what made this card look padded.
 */
private fun summaryTail(result: MedicineReviewResult): String? {
    val summary = result.summary.trim()
    if (summary.isEmpty()) return null
    val headline = result.headline.trim().trimEnd('.')
    val tail = summary
        .removePrefix(headline)
        .removePrefix(".")
        .trim()
    // A summary that was *only* the headline leaves nothing, and an empty
    // paragraph under a title is worse than no paragraph.
    return tail.takeIf { it.isNotBlank() }
}

// ---------------------------------------------------------------- history

/**
 * Previous checks.
 *
 * One row per medicine, showing the two things that identify it from across the
 * room -- the name and the verdict -- and nothing else until it is tapped.
 * Expanded, it shows the strip that was photographed and what the app decided,
 * which is what somebody comes back to a history for.
 */
@Composable
private fun ScanHistory(
    scans: List<MedicineScanRecord>,
    total: Int,
    showAll: Boolean,
    onToggleAll: () -> Unit,
    onDelete: (MedicineScanRecord) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(
            "Recent checks",
            action = if (total > 3) (if (showAll) "Show fewer" else "See all $total") else null,
            onAction = if (total > 3) onToggleAll else null,
        )
        scans.forEach { scan ->
            ScanHistoryRow(scan, onDelete = { onDelete(scan) })
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(calmColors().container.copy(alpha = 0.6f))
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.Shield, contentDescription = null, tint = calmColors().accent,
                 modifier = Modifier.size(18.dp))
            Text("All checks are saved on this phone only.",
                 style = MaterialTheme.typography.labelLarge, color = calmColors().accent)
        }
    }
}

@Composable
private fun ScanHistoryRow(
    scan: MedicineScanRecord,
    onDelete: () -> Unit,
) {
    var expanded by remember(scan.id) { mutableStateOf(false) }
    val verdict = runCatching { Verdict.valueOf(scan.verdict) }
        .getOrDefault(Verdict.UNKNOWN)
    val colors = verdictColors(verdict)
    val scheme = MaterialTheme.colorScheme

    NilaCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp),
             verticalSpacing = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ScanThumb(scan.imagePath, colors, size = 58.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    scan.name.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(colors.accent))
                    Text(verdict.label, style = MaterialTheme.typography.labelLarge,
                         color = colors.accent, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "Checked ${relativeDay(scan.atMs)} · " +
                        if (scan.imagePath != null) "from a photo" else "typed",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = scheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                scan.imagePath?.let { path -> StripThumbnail(path) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.container)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(scan.headline, style = MaterialTheme.typography.titleSmall,
                         fontWeight = FontWeight.SemiBold, color = colors.onContainer)
                    Text(scan.summary, style = MaterialTheme.typography.bodyMedium,
                         color = colors.onContainer)
                    scan.cautionList().forEach {
                        Text(it, style = MaterialTheme.typography.bodyMedium,
                             color = colors.onContainerMuted)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(fullTimestamp(scan.atMs), style = MaterialTheme.typography.labelMedium,
                         color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    OutlinePillButton("Delete", onDelete, icon = Icons.Outlined.DeleteOutline,
                                      danger = true)
                }
            }
        }
    }
}

/**
 * A small square of the photographed strip, or a pill badge if there was none.
 *
 * Decoded at a fraction of its size, off the main thread: the full photo is
 * only ever decoded when a row is opened, so a long history costs a few
 * kilobytes of thumbnails rather than a bitmap per row.
 */
@Composable
private fun ScanThumb(path: String?, colors: SeverityColors, size: Dp) {
    val thumb by produceState<Bitmap?>(null, path) {
        value = path?.let {
            withContext(Dispatchers.IO) {
                runCatching {
                    android.graphics.BitmapFactory.decodeFile(
                        it, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 },
                    )
                }.getOrNull()
            }
        }
    }
    val bitmap = thumb
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(size).clip(RoundedCornerShape(14.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(colors.container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Medication, contentDescription = null, tint = colors.accent,
                 modifier = Modifier.size(size * 0.5f))
        }
    }
}

/**
 * The photographed strip, decoded only while the row is open.
 *
 * Twenty rows each holding a decoded bitmap is tens of megabytes for a list
 * where nineteen of them are not being looked at, so the file is read when the
 * row expands and dropped when it closes.
 */
@Composable
private fun StripThumbnail(path: String) {
    val bitmap = remember(path) {
        runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull()
    }
    if (bitmap == null) {
        Text(
            "The photo for this scan is no longer on the phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "The medicine strip that was checked",
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(14.dp)),
        contentScale = ContentScale.Fit,
    )
}

/** "today", "yesterday", then a date. What a person actually needs here. */
private fun relativeDay(atMs: Long): String {
    val days = ((startOfDay(System.currentTimeMillis()) - startOfDay(atMs)) /
        86_400_000L).toInt()
    return when {
        days <= 0 -> "today"
        days == 1 -> "yesterday"
        days < 7 -> "$days days ago"
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(atMs))
    }
}

private fun fullTimestamp(atMs: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(atMs))

private fun startOfDay(ms: Long): Long {
    val cal = java.util.Calendar.getInstance().apply {
        timeInMillis = ms
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

// ------------------------------------------------------------ agent trace

/** The chain of specialists, each with its own finding and its own blind spot. */
@Composable
private fun AgentTrace(steps: List<AgentStep>) {
    var expanded by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    NilaCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp),
             verticalSpacing = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconBadge(Icons.Outlined.VerifiedUser, Tone.TEAL, size = 40.dp, iconSize = 21.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text("How this was checked", style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.Bold)
                Text("${steps.size} steps, each with what it could not check",
                     style = MaterialTheme.typography.labelMedium,
                     color = scheme.onSurfaceVariant)
            }
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                steps.forEachIndexed { i, step ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier.size(26.dp).clip(CircleShape)
                                .background(toneColors(Tone.TEAL).container),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${i + 1}", style = MaterialTheme.typography.labelLarge,
                                 fontWeight = FontWeight.Bold, color = toneColors(Tone.TEAL).ink)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(step.agent, style = MaterialTheme.typography.titleSmall,
                                     fontWeight = FontWeight.SemiBold)
                                ConfidenceDot(step.confidence)
                            }
                            Text("Looked at: ${step.looked_at}",
                                 style = MaterialTheme.typography.bodySmall,
                                 color = scheme.onSurfaceVariant)
                            Text(step.finding, style = MaterialTheme.typography.bodyMedium)
                            if (step.flags.isNotEmpty()) {
                                val flagColors = severityColors(Severity.ATTENTION)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    step.flags.take(3).forEach { flag ->
                                        Text(
                                            flag, color = flagColors.accent,
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier
                                                .clip(CircleShape)
                                                .background(flagColors.container)
                                                .padding(horizontal = 9.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }
                            step.couldNotCheck?.let {
                                Text("Could not check: $it",
                                     style = MaterialTheme.typography.bodySmall,
                                     color = scheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfidenceDot(confidence: AgentStep.Confidence) {
    val attention = severityColors(Severity.ATTENTION).accent
    val (label, color) = when (confidence) {
        AgentStep.Confidence.HIGH -> "confident" to calmColors().accent
        AgentStep.Confidence.MEDIUM -> "partial" to attention
        AgentStep.Confidence.LOW -> "weak" to attention
        AgentStep.Confidence.NONE -> "no data" to MaterialTheme.colorScheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}
