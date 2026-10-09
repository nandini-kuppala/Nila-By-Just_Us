package com.nila.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.LocalFlorist
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PregnantWoman
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Vaccines
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nila.data.HealthRecord
import com.nila.data.MotherProfile
import com.nila.data.RecordCategory
import com.nila.data.RecordSubject
import com.nila.ui.AppState
import com.nila.ui.components.HappyBabyArt
import com.nila.ui.components.IconBadge
import com.nila.ui.components.InfoNote
import com.nila.ui.components.ListRow
import com.nila.ui.components.NilaCard
import com.nila.ui.components.NilaIcons
import com.nila.ui.components.OutlinePillButton
import com.nila.ui.components.PillButton
import com.nila.ui.components.RowDivider
import com.nila.ui.components.SegmentedPills
import com.nila.ui.components.TonePill
import com.nila.ui.theme.Tone
import com.nila.ui.theme.toneColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The health shelf, for both people being cared for.
 *
 * Two things make this more than a photo album. Documents are OCR'd at import,
 * so the assistant can retrieve over them; and the mother's profile feeds the
 * medicine review directly, which is what lets "is this safe" become "is this
 * safe *for you*".
 *
 * Records are grouped by category rather than listed by date, because people
 * look for "the vaccination card", not "the thing from March". Each group
 * folds, and each record opens in place to show the text that was read from it
 * -- which is the evidence that the document is searchable at all.
 */
@Composable
fun HealthScreen(state: AppState) {
    var subjectIndex by rememberSaveable { mutableStateOf(0) }
    val subject = RecordSubject.entries[subjectIndex]
    val records by state.records.collectAsState()
    val mother by state.mother.collectAsState()
    val baby by state.baby.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    val folded = remember { mutableStateMapOf<RecordCategory, Boolean>() }

    val visible = records.filter { it.recordSubject == subject }
    val babyName = baby?.name?.takeIf { it.isNotBlank() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SegmentedPills(
            options = listOf(
                "Baby" to Icons.Outlined.ChildCare,
                "Mother" to Icons.Outlined.Person,
            ),
            selected = subjectIndex,
            onSelect = { subjectIndex = it },
        )

        if (subject == RecordSubject.BABY) {
            BabyHero(babyName, onAdd = { showAdd = true })
        } else {
            MotherCard(mother, state::saveMother, onAdd = { showAdd = true })
        }

        if (visible.isEmpty()) {
            NilaCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    IconBadge(Icons.Outlined.Description, Tone.SLATE)
                    Text(
                        "Photograph a prescription, report or vaccination card. " +
                            "It stays on this phone and stays searchable.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            RecordCategory.forSubject(subject)
                .mapNotNull { cat ->
                    visible.filter { it.recordCategory == cat }
                        .takeIf { it.isNotEmpty() }?.let { cat to it }
                }
                .forEach { (category, items) ->
                    val open = folded[category] != true
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { folded[category] = open }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${category.label}  (${items.size})",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                contentDescription = if (open) "Fold ${category.label}"
                                                     else "Open ${category.label}",
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        AnimatedVisibility(visible = open) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                items.forEach { record ->
                                    RecordCard(record) { state.deleteRecord(record) }
                                }
                            }
                        }
                    }
                }
        }

        InfoNote(
            "Everything here stays in this app's private storage and is left " +
                "out of cloud backup. Nothing is uploaded.",
            icon = Icons.Outlined.Shield,
            tone = Tone.TEAL,
        )
    }

    if (showAdd) {
        AddRecordDialog(
            subject = subject,
            onDismiss = { showAdd = false },
            onSave = { category, title, notes, uri ->
                state.addRecord(subject, category, title, notes, uri = uri)
                showAdd = false
            },
        )
    }
}

/** The invitation at the top of the baby's shelf. */
@Composable
private fun BabyHero(babyName: String?, onAdd: () -> Unit) {
    NilaCard(
        modifier = Modifier.fillMaxWidth(),
        tone = Tone.TEAL,
        contentPadding = PaddingValues(start = 18.dp, top = 16.dp, end = 12.dp, bottom = 18.dp),
        verticalSpacing = 14.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    if (babyName != null) "Keep $babyName's health records in one place"
                    else "Keep your baby's health records in one place",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Vaccinations, check-ups, growth, prescriptions and reports.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(Modifier.size(100.dp)) { HappyBabyArt() }
        }
        PillButton("Add a baby record", onAdd, Modifier.fillMaxWidth().padding(end = 6.dp),
                   icon = Icons.Outlined.Add)
    }
}

/**
 * The mother's own profile: what the medicine checker reads.
 *
 * Three rows rather than a form, because a form is something you fill in once
 * and a summary is something you glance at before asking about a medicine.
 * Tapping any of them opens the editor.
 */
@Composable
private fun MotherCard(
    profile: MotherProfile?,
    onSave: (MotherProfile) -> Unit,
    onAdd: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var conditions by remember(profile) { mutableStateOf(profile?.conditions ?: "") }
    var allergies by remember(profile) { mutableStateOf(profile?.allergies ?: "") }
    var medicines by remember(profile) {
        mutableStateOf(profile?.currentMedicines ?: "")
    }
    val scheme = MaterialTheme.colorScheme

    NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 14.dp,
             contentPadding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your health summary", style = MaterialTheme.typography.titleLarge,
                 fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (!editing) {
                Box(Modifier.clip(RoundedCornerShape(50)).clickable { editing = true }) {
                    TonePill("Edit", Tone.TEAL, icon = Icons.Outlined.Edit)
                }
            }
        }

        if (editing) {
            OutlinedTextField(
                value = conditions, onValueChange = { conditions = it },
                label = { Text("Conditions (asthma, diabetes...)") },
                modifier = Modifier.fillMaxWidth(), minLines = 2,
                shape = RoundedCornerShape(14.dp),
            )
            OutlinedTextField(
                value = allergies, onValueChange = { allergies = it },
                label = { Text("Allergies (penicillin...)") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )
            OutlinedTextField(
                value = medicines, onValueChange = { medicines = it },
                label = { Text("Medicines you take now") },
                modifier = Modifier.fillMaxWidth(), minLines = 2,
                shape = RoundedCornerShape(14.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Save", onClick = {
                    onSave(
                        (profile ?: MotherProfile()).copy(
                            conditions = conditions,
                            allergies = allergies,
                            currentMedicines = medicines,
                        )
                    )
                    editing = false
                }, modifier = Modifier.weight(1f))
                OutlinePillButton("Cancel", { editing = false }, Modifier.weight(1f))
            }
        } else {
            if (profile?.isComplete != true) {
                // Said plainly, because the medicine checker is meaningfully
                // weaker without this and the user should know why it asks.
                Text(
                    "The medicine checker uses this. Without it, it can only give " +
                        "you the generic answer rather than one about you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .padding(horizontal = 2.dp),
            ) {
                ListRow(
                    title = "Conditions",
                    subtitle = profile?.conditions?.takeIf { it.isNotBlank() } ?: "None added",
                    icon = Icons.Outlined.MonitorHeart,
                    tone = Tone.PINK,
                    onClick = { editing = true },
                )
                RowDivider(inset = 58.dp)
                ListRow(
                    title = "Allergies",
                    subtitle = profile?.allergies?.takeIf { it.isNotBlank() } ?: "None added",
                    icon = Icons.Outlined.LocalFlorist,
                    tone = Tone.PLUM,
                    onClick = { editing = true },
                )
                RowDivider(inset = 58.dp)
                ListRow(
                    title = "Taking now",
                    subtitle = profile?.currentMedicines?.takeIf { it.isNotBlank() }
                        ?: "None added",
                    icon = Icons.Outlined.Medication,
                    tone = Tone.BLUE,
                    onClick = { editing = true },
                )
            }
            PillButton("Add a mother record", onAdd, Modifier.fillMaxWidth(),
                       icon = Icons.Outlined.Add)
        }
    }
}

/**
 * One document. Closed, it shows what it is and when; open, it shows the text
 * that was read from it and the way to remove it.
 *
 * Remove lives inside the opened card rather than on every row. A destructive
 * button on every line of a list is a list people are nervous to scroll.
 */
@Composable
private fun RecordCard(record: HealthRecord, onDelete: () -> Unit) {
    var open by remember(record.id) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val (icon, tone) = categoryLook(record.recordCategory)
    val date = remember(record.recordedAtMs) {
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(record.recordedAtMs))
    }

    NilaCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = { open = !open },
        contentPadding = PaddingValues(16.dp),
        verticalSpacing = 10.dp,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(icon, tone, size = 46.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(record.title, style = MaterialTheme.typography.titleSmall,
                         fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (record.hasFile) {
                        TonePill(
                            if (record.mimeType?.contains("pdf") == true) "PDF" else "Photo",
                            tone,
                            icon = Icons.Outlined.AttachFile,
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Outlined.CalendarToday, contentDescription = null,
                         tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    Text(date, style = MaterialTheme.typography.bodySmall,
                         color = scheme.onSurfaceVariant)
                }
                if (record.notes.isNotBlank()) {
                    Text(record.notes, style = MaterialTheme.typography.bodyMedium,
                         maxLines = if (open) Int.MAX_VALUE else 2,
                         overflow = TextOverflow.Ellipsis)
                }
                if (record.extractedText.isNotBlank() && !open) {
                    // Shown so it is obvious the text was captured and is
                    // searchable -- otherwise the OCR is invisible work the user
                    // has no reason to believe happened.
                    Text(
                        readable(record.extractedText).replace("\n", " · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (open) "Close" else "Open",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }

        AnimatedVisibility(visible = open) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (record.extractedText.isNotBlank()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .padding(top = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("TEXT READ FROM THE DOCUMENT",
                             style = MaterialTheme.typography.labelMedium,
                             color = toneColors(tone).ink)
                        Text(readable(record.extractedText).trim(),
                             style = MaterialTheme.typography.bodySmall,
                             color = scheme.onSurfaceVariant)
                    }
                }
                OutlinePillButton(
                    "Remove this record",
                    onDelete,
                    Modifier.fillMaxWidth(),
                    icon = Icons.Outlined.DeleteOutline,
                    danger = true,
                )
            }
        }
    }
}

/**
 * OCR text, with shouted lines brought down to sentence case.
 *
 * Printed forms set their headings in capitals, and read back verbatim they
 * take over a two-line preview ("NATIONAL IMMUNISATION SCHEDULE.") and push
 * out the part that says what was given. The stored text is untouched -- this
 * is how it is shown, not what is searched.
 */
private fun readable(text: String): String =
    text.split(Regex("(?<=[.:\n])")).joinToString("") { part ->
        val letters = part.filter { it.isLetter() }
        if (letters.length > 6 && letters.count { it.isUpperCase() } > letters.length * 0.8) {
            val lower = part.lowercase()
            val i = lower.indexOfFirst { it.isLetter() }
            if (i < 0) lower else lower.substring(0, i) + lower[i].uppercase() + lower.substring(i + 1)
        } else part
    }

/** The badge a category wears, so the same kind of document looks the same everywhere. */
private fun categoryLook(category: RecordCategory): Pair<ImageVector, Tone> = when (category) {
    RecordCategory.GROWTH -> Icons.Outlined.BarChart to Tone.BLUE
    RecordCategory.VACCINATION -> Icons.Outlined.Vaccines to Tone.PLUM
    RecordCategory.ILLNESS -> Icons.Outlined.MedicalServices to Tone.PEACH
    RecordCategory.SCREENING -> Icons.Outlined.TaskAlt to Tone.TEAL
    RecordCategory.POSTNATAL -> Icons.Outlined.PregnantWoman to Tone.PINK
    RecordCategory.LACTATION -> NilaIcons.Bottle to Tone.TEAL
    RecordCategory.MENTAL_HEALTH -> Icons.Outlined.Spa to Tone.PINK
    RecordCategory.PRESCRIPTION -> Icons.Outlined.Medication to Tone.PINK
    RecordCategory.LAB_REPORT -> Icons.Outlined.Science to Tone.TEAL
    RecordCategory.OTHER -> Icons.Outlined.Description to Tone.SLATE
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddRecordDialog(
    subject: RecordSubject,
    onDismiss: () -> Unit,
    onSave: (RecordCategory, String, String, Uri?) -> Unit,
) {
    val categories = RecordCategory.forSubject(subject)
    var category by remember { mutableStateOf(categories.first()) }
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Uri?>(null) }

    val pickDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> picked = uri }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        title = {
            Text("Add a ${subject.label.lowercase()} record",
                 style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Category", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    categories.forEach { c ->
                        FilterChip(
                            selected = category == c,
                            onClick = { category = c },
                            label = { Text(c.label) },
                            shape = RoundedCornerShape(50),
                        )
                    }
                }
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("Title") }, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinePillButton(
                    if (picked == null) "Attach a photo or PDF" else "Attached – tap to change",
                    { pickDocument.launch("*/*") },
                    Modifier.fillMaxWidth(),
                    icon = Icons.Outlined.AttachFile,
                )
            }
        },
        confirmButton = {
            PillButton("Save", { onSave(category, title, notes, picked) },
                       enabled = title.isNotBlank())
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
