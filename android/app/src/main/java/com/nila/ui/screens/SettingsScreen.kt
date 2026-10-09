package com.nila.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MedicalInformation
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.nila.vision.DayNight
import com.nila.vision.VisionSettings
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nila.actions.Reminders
import com.nila.actions.Soother
import com.nila.data.BabyProfile
import com.nila.data.CareKind
import com.nila.ui.AppState
import com.nila.ui.components.BabyAvatar
import com.nila.ui.components.SettingsArt
import com.nila.ui.components.IconBadge
import com.nila.ui.components.InfoNote
import com.nila.ui.components.ListRow
import com.nila.ui.components.NilaCard
import com.nila.ui.components.NilaIcons
import com.nila.ui.components.OutlinePillButton
import com.nila.ui.components.PillButton
import com.nila.ui.components.RowDivider
import com.nila.ui.components.ScreenHero
import com.nila.ui.components.SegmentedPills
import com.nila.ui.components.TonePill
import com.nila.ui.theme.ThemeMode
import com.nila.ui.theme.Tone
import com.nila.ui.theme.toneColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The pages behind Settings, one per thing a person comes here to do.
 *
 * Settings used to be one long scroll with fourteen sections, which is a page
 * people scroll past the thing they wanted on. The hub names each of them in
 * one line, with what it is set to now, and each opens a page of its own.
 */
enum class SettingsPage(val key: String, val title: String) {
    APPEARANCE("appearance", "Appearance"),
    BABY("baby", "Baby profile"),
    REMINDERS("reminders", "Reminders"),
    RECORDINGS("recordings", "Audio recordings"),
    CAMERA("camera", "Camera watch"),
    VOICE("voice", "Your voice"),
    DEVICES("devices", "Devices & access"),
    REPORTS("reports", "Reports"),
    APP_DATA("app", "App & data"),
    PRIVACY("privacy", "Privacy");

    companion object {
        fun fromKey(key: String) = entries.firstOrNull { it.key == key }
    }
}

// ------------------------------------------------------------------- hub

@Composable
fun SettingsScreen(state: AppState, onOpen: (SettingsPage) -> Unit = {}) {
    val baby by state.baby.collectAsState()
    val pendingTag by com.nila.ui.NfcInbox.pendingTag.collectAsState()
    val mode by com.nila.ui.theme.Appearance.mode.collectAsState()
    val reminders by state.reminderSettings.collectAsState()
    val held by state.clipsHeld.collectAsState()
    val keepClips by com.nila.data.ClipPolicy.enabled.collectAsState()
    val recordings by state.recordings.collectAsState()
    val linkPaired by state.linkPaired.collectAsState()
    val context = LocalContext.current
    val vision by remember { VisionSettings(context) }.values
        .collectAsState(initial = VisionSettings.Values())

    PageColumn {
        ScreenHero(
            title = "Make Nila yours",
            body = "And see exactly what stays on this phone.",
            art = { SettingsArt() },
        )

        // Shown the moment an unknown sticker is tapped, wherever the user is.
        if (pendingTag != null) {
            NilaCard(modifier = Modifier.fillMaxWidth(), tone = Tone.TEAL) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconBadge(Icons.Outlined.Nfc, Tone.TEAL, size = 40.dp)
                    Text("New sticker detected. What should tapping it log?",
                         style = MaterialTheme.typography.titleSmall,
                         fontWeight = FontWeight.SemiBold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        CareKind.FEED to "Feed",
                        CareKind.DIAPER to "Nappy",
                        CareKind.SLEEP_START to "Sleep",
                    ).forEach { (kind, label) ->
                        OutlinePillButton(label, { state.assignPendingTag(kind) },
                                          Modifier.weight(1f))
                    }
                }
            }
        }

        val babyLine = listOfNotNull(
            baby?.name?.takeIf { it.isNotBlank() },
            baby?.let { babyAge(it) },
        ).joinToString(", ").ifBlank { "Name, birthday and health notes" }
        val reminderLine = reminders.filter { it.enabled }.let { on ->
            if (on.isEmpty()) "Off" else on.joinToString(" and ") {
                if (it.kind == CareKind.FEED) "feeding" else "nappy changes"
            }.replaceFirstChar { it.uppercase() } + " on"
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HubRow(SettingsPage.APPEARANCE, Icons.Outlined.LightMode, Tone.PEACH,
                   "Light, dark, or follow the phone", value = mode.label, onOpen = onOpen)
            HubRow(SettingsPage.BABY, Icons.Outlined.Person, Tone.PLUM, babyLine, onOpen = onOpen)
            HubRow(SettingsPage.REMINDERS, Icons.Outlined.NotificationsNone, Tone.PEACH,
                   reminderLine, onOpen = onOpen)
            HubRow(SettingsPage.RECORDINGS, Icons.Outlined.GraphicEq, Tone.BLUE,
                   if (!keepClips) "Off – nothing is recorded"
                   else if (held.count == 0) "On – none kept yet"
                   else "${held.count} kept, ${held.readableSize}",
                   onOpen = onOpen)
            HubRow(SettingsPage.CAMERA, Icons.Outlined.Videocam, Tone.PLUM,
                   listOfNotNull(
                       if (vision.modelEnabled) "Describes the cot" else "Posture and movement only",
                       when (vision.nightPreference) {
                           DayNight.Preference.AUTO -> "night vision automatic"
                           DayNight.Preference.ALWAYS_NIGHT -> "night vision on"
                           DayNight.Preference.ALWAYS_DAY -> "night vision off"
                       },
                   ).joinToString(", "),
                   onOpen = onOpen)
            HubRow(SettingsPage.VOICE, Icons.Outlined.Mic, Tone.TEAL,
                   if (recordings.isEmpty()) "Record a sound to help settle her"
                   else "${recordings.size} " +
                       (if (recordings.size == 1) "recording" else "recordings"),
                   onOpen = onOpen)
            HubRow(SettingsPage.DEVICES, Icons.Outlined.Devices, Tone.BLUE,
                   if (linkPaired) "Second phone paired, laptop view"
                   else "Second phone, laptop view", onOpen = onOpen)
            HubRow(SettingsPage.REPORTS, Icons.Outlined.Description, Tone.TEAL,
                   "A PDF for your paediatrician", onOpen = onOpen)
            HubRow(SettingsPage.APP_DATA, Icons.Outlined.Settings, Tone.SLATE,
                   "Models, this device, demo data", onOpen = onOpen)
            HubRow(SettingsPage.PRIVACY, Icons.Outlined.Shield, Tone.BLUE,
                   "Everything stays on this device", onOpen = onOpen)
        }
    }
}

@Composable
private fun HubRow(
    page: SettingsPage,
    icon: ImageVector,
    tone: Tone,
    subtitle: String,
    value: String? = null,
    onOpen: (SettingsPage) -> Unit,
) {
    NilaCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = { onOpen(page) },
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
    ) {
        ListRow(
            title = page.title,
            subtitle = subtitle,
            icon = icon,
            tone = tone,
            badgeSize = 40.dp,
            chevron = true,
            subtitleLines = 1,
            trailing = value?.let {
                {
                    Text(it, style = MaterialTheme.typography.labelLarge,
                         color = MaterialTheme.colorScheme.primary)
                }
            },
        )
    }
}

/** "6 months 2 weeks", from a birth date. Null when none is set. */
private fun babyAge(baby: BabyProfile): String? {
    if (baby.birthDateMs <= 0) return null
    val days = ((System.currentTimeMillis() - baby.birthDateMs) / 86_400_000L).toInt()
    if (days < 0) return null
    val months = (days / 30.44).toInt()
    val weeks = ((days - months * 30.44) / 7).toInt()
    return when {
        months == 0 -> "$weeks " + if (weeks == 1) "week" else "weeks"
        weeks == 0 -> "$months " + if (months == 1) "month" else "months"
        else -> "$months " + (if (months == 1) "month" else "months") +
            " $weeks " + if (weeks == 1) "week" else "weeks"
    }
}

// ------------------------------------------------------------------ pages

@Composable
fun SettingsPageScreen(state: AppState, page: SettingsPage) {
    PageColumn {
        when (page) {
            SettingsPage.APPEARANCE -> AppearancePage()
            SettingsPage.BABY -> BabyPage(state)
            SettingsPage.REMINDERS -> RemindersPage(state)
            SettingsPage.RECORDINGS -> RecordingsPage(state)
            SettingsPage.CAMERA -> CameraPage(state)
            SettingsPage.VOICE -> VoicePage(state)
            SettingsPage.DEVICES -> DevicesPage(state)
            SettingsPage.REPORTS -> ReportsPage(state)
            SettingsPage.APP_DATA -> AppDataPage(state)
            SettingsPage.PRIVACY -> PrivacyPage()
        }
    }
}

@Composable
private fun PageColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) { content() }
}

@Composable
private fun PageTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

// ----------------------------------------------------------- appearance

/**
 * Light, dark, or whatever the phone says.
 *
 * The top bar has a one-tap switch between light and dark, which is the control
 * people actually use. This is the other half of it: the way back to following
 * the system, which a two-state button cannot express, and the only place all
 * three states are named so somebody can tell which one they are in.
 */
@Composable
private fun AppearancePage() {
    val context = LocalContext.current
    val mode by com.nila.ui.theme.Appearance.mode.collectAsState()
    PageTitle("Theme")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.height(IntrinsicSize.Max)) {
        ThemeMode.entries.forEach { option ->
            ThemeCard(
                option = option,
                selected = option == mode,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) { com.nila.ui.theme.Appearance.set(context, option) }
        }
    }
    InfoNote("The moon at the top of every screen switches light and dark in one tap.")
}

@Composable
private fun ThemeCard(
    option: ThemeMode,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val (icon, tone, caption) = when (option) {
        ThemeMode.SYSTEM -> Triple(Icons.Outlined.Contrast, Tone.PEACH, "Follows your phone")
        ThemeMode.LIGHT -> Triple(Icons.Outlined.LightMode, Tone.PEACH, "Clean and bright")
        ThemeMode.DARK -> Triple(Icons.Outlined.DarkMode, Tone.BLUE, "Easy on the eyes")
    }
    Box(modifier) {
        NilaCard(
            modifier = Modifier.fillMaxSize(),
            borderColor = if (selected) scheme.primary else null,
            tone = if (selected) Tone.TEAL else null,
            onClick = onClick,
            contentPadding = PaddingValues(vertical = 16.dp, horizontal = 8.dp),
            verticalSpacing = 8.dp,
        ) {
            IconBadge(icon, tone, size = 48.dp, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(option.label, style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                 modifier = Modifier.fillMaxWidth())
            Text(caption, style = MaterialTheme.typography.labelMedium,
                 color = scheme.onSurfaceVariant, textAlign = TextAlign.Center,
                 modifier = Modifier.fillMaxWidth())
        }
        if (selected) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp).clip(CircleShape)
                    .background(scheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Check, contentDescription = "Selected",
                     tint = scheme.onPrimary, modifier = Modifier.size(15.dp))
            }
        }
    }
}

// ------------------------------------------------------------- baby

private val LANGUAGES = listOf(
    "en" to "English", "hi" to "Hindi", "ta" to "Tamil", "te" to "Telugu",
    "kn" to "Kannada", "bn" to "Bengali", "mr" to "Marathi",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BabyPage(state: AppState) {
    val baby by state.baby.collectAsState()
    var name by remember(baby) { mutableStateOf(baby?.name ?: "") }
    var notes by remember(baby) { mutableStateOf(baby?.healthNotes ?: "") }
    var language by remember(baby) { mutableStateOf(baby?.language ?: "en") }
    var birth by remember(baby) { mutableStateOf(baby?.birthDateMs ?: 0L) }
    var picking by remember { mutableStateOf(false) }
    var languageOpen by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    NilaCard(modifier = Modifier.fillMaxWidth(), tone = Tone.TEAL) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(64.dp)) { BabyAvatar() }
            Column {
                Text(name.ifBlank { "Your baby" }, style = MaterialTheme.typography.headlineSmall)
                Text(
                    babyAge((baby ?: BabyProfile()).copy(birthDateMs = birth))
                        ?: "Add a date of birth",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }

    NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 14.dp) {
        OutlinedTextField(
            value = name, onValueChange = { name = it; saved = false },
            label = { Text("Name") }, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp), singleLine = true,
        )
        OutlinedTextField(
            value = notes, onValueChange = { notes = it; saved = false },
            label = { Text("Health notes (allergies, conditions)") },
            modifier = Modifier.fillMaxWidth(), minLines = 2,
            shape = RoundedCornerShape(14.dp),
        )
        SettingRow(
            icon = Icons.Outlined.CalendarToday,
            title = "Date of birth",
            value = if (birth > 0) SimpleDateFormat("d MMM yyyy", Locale.getDefault())
                .format(Date(birth)) else "Not set",
            onClick = { picking = true },
        )
        RowDivider()
        Box {
            SettingRow(
                icon = Icons.Outlined.Language,
                title = "Answer language",
                value = LANGUAGES.firstOrNull { it.first == language }?.second ?: language,
                onClick = { languageOpen = true },
            )
            DropdownMenu(expanded = languageOpen, onDismissRequest = { languageOpen = false }) {
                LANGUAGES.forEach { (code, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = { language = code; languageOpen = false; saved = false },
                        trailingIcon = if (code == language) {
                            { Icon(Icons.Outlined.Check, contentDescription = null) }
                        } else null,
                    )
                }
            }
        }
        PillButton(
            if (saved) "Saved" else "Save",
            onClick = {
                state.saveBaby(
                    (baby ?: BabyProfile()).copy(
                        name = name, healthNotes = notes, language = language,
                        birthDateMs = birth,
                    )
                )
                saved = true
            },
            icon = if (saved) Icons.Outlined.Check else null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    InfoNote("The assistant reads these notes to answer about your baby rather than " +
                 "about babies in general. They never leave the phone.")

    if (picking) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = birth.takeIf { it > 0 } ?: System.currentTimeMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { birth = it; saved = false }
                    picking = false
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state = picker) }
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = scheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(value, style = MaterialTheme.typography.bodyMedium,
                 color = scheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = scheme.onSurfaceVariant)
    }
}

// --------------------------------------------------------- reminders

@Composable
private fun RemindersPage(state: AppState) {
    val reminderSettings by state.reminderSettings.collectAsState()
    InfoNote("Reminders are counted from your last log, not the clock.",
             icon = Icons.Outlined.NotificationsNone, tone = Tone.TEAL)
    reminderSettings.forEach { setting ->
        ReminderCard(
            setting = setting,
            onEnabled = { state.setReminderEnabled(setting.kind, it) },
            onInterval = { state.setReminderInterval(setting.kind, it) },
        )
    }
}

/**
 * One reminder: a switch and an interval.
 *
 * The interval is a list of fifteen-minute steps rather than a text field,
 * because it is a preference, not a measurement -- and because nobody wants to
 * type "165" at three in the morning.
 */
@Composable
private fun ReminderCard(
    setting: Reminders.Setting,
    onEnabled: (Boolean) -> Unit,
    onInterval: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val feed = setting.kind == CareKind.FEED
    val scheme = MaterialTheme.colorScheme
    NilaCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(if (feed) NilaIcons.Bottle else NilaIcons.Nappy,
                      if (feed) Tone.TEAL else Tone.PEACH)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (feed) "Feeding" else "Nappy change",
                     style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Remind every", style = MaterialTheme.typography.bodySmall,
                         color = scheme.onSurfaceVariant)
                    Box {
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(scheme.surfaceContainerLow)
                                .border(1.dp, scheme.outlineVariant, CircleShape)
                                .clickable(enabled = setting.enabled) { open = true }
                                .padding(start = 12.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(format(setting.intervalMinutes),
                                 style = MaterialTheme.typography.labelLarge,
                                 color = if (setting.enabled) scheme.onSurface
                                         else scheme.onSurfaceVariant)
                            Icon(Icons.Outlined.ExpandMore, contentDescription = null,
                                 modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false },
                                     modifier = Modifier.heightIn(max = 320.dp)) {
                            (30..360 step 15).forEach { minutes ->
                                DropdownMenuItem(
                                    text = { Text(format(minutes)) },
                                    onClick = { onInterval(minutes); open = false },
                                    trailingIcon = if (minutes == setting.intervalMinutes) {
                                        { Icon(Icons.Outlined.Check, contentDescription = null) }
                                    } else null,
                                )
                            }
                        }
                    }
                }
            }
            Switch(checked = setting.enabled, onCheckedChange = onEnabled)
        }
    }
}

private fun format(minutes: Int): String = com.nila.ui.components.humanMinutes(minutes)

// -------------------------------------------------------- recordings

/**
 * Whether Nila keeps the sound of a difficult cry, and what that means.
 *
 * Written out rather than reduced to a switch label, because this is the one
 * setting in the app that decides whether a microphone in a nursery produces a
 * file. A parent turning it off deserves to know exactly what stops, and a
 * parent leaving it on deserves to know exactly what is kept, for how long, and
 * where it can and cannot go.
 */
@Composable
private fun RecordingsPage(state: AppState) {
    val enabled by com.nila.data.ClipPolicy.enabled.collectAsState()
    val held by state.clipsHeld.collectAsState()
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var confirm by remember { mutableStateOf(false) }

    NilaCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(Icons.Outlined.GraphicEq, Tone.PLUM)
            Column(modifier = Modifier.weight(1f)) {
                Text("Keep difficult cries", style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.Bold)
                Text(
                    if (enabled) "On – cries that woke you, ran to three minutes, or came " +
                        "back as pain are kept"
                    else "Off – nothing is recorded",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = { com.nila.data.ClipPolicy.set(context, it) })
        }
    }

    // The why of it -- that a clip is the one thing a doctor can be given that is
    // evidence rather than a recollection -- is on the timeline, beside the
    // clips. Here, only what happens to them.
    InfoNote(
        "Stored only on this phone, never uploaded or backed up. Difficult cries are " +
            "kept ${com.nila.audio.EpisodeRecorder.KEEP_DAYS} days, others " +
            "${com.nila.audio.EpisodeRecorder.RETAIN_DAYS}.",
        icon = Icons.Outlined.Shield,
        tone = Tone.TEAL,
    )

    NilaCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (held.count == 0) "No recordings kept"
                    else "${held.count} " + (if (held.count == 1) "recording" else "recordings"),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                )
                if (held.count > 0) {
                    Text(held.readableSize, style = MaterialTheme.typography.bodySmall,
                         color = scheme.onSurfaceVariant)
                }
            }
            if (held.count > 0) {
                val red = com.nila.ui.theme.severityColors(com.nila.data.Severity.URGENT)
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(red.container)
                        .clickable { confirm = true }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null,
                         tint = red.accent, modifier = Modifier.size(18.dp))
                    Text("Delete all", style = MaterialTheme.typography.labelLarge,
                         color = red.accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    if (!enabled) {
        InfoNote("Turning this off changes nothing about detection, the soothing sounds " +
                     "or the alerts. Only the audio.")
    }

    if (confirm) {
        ConfirmDialog(
            title = "Delete every recording?",
            body = "All ${held.count} kept cries are removed from this phone. This cannot " +
                "be undone.",
            action = "Delete all",
            onConfirm = { state.deleteAllClips(); confirm = false },
            onDismiss = { confirm = false },
        )
    }
}

// --------------------------------------------------------------- voice

@Composable
private fun VoicePage(state: AppState) {
    val recordings by state.recordings.collectAsState()
    val recording by state.recording.collectAsState()
    var label by remember { mutableStateOf("") }
    val scheme = MaterialTheme.colorScheme

    NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(Icons.Outlined.Mic, Tone.TEAL)
            Text("Played to settle your baby before you are woken.",
                 style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        if (!recording) {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("What is it? e.g. Amma humming") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        PillButton(
            if (recording) "Stop and save" else "Record",
            onClick = {
                if (recording) state.stopRecording()
                else { state.startRecording(label); label = "" }
            },
            icon = if (recording) Icons.Outlined.Stop else Icons.Outlined.Mic,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (recordings.isNotEmpty()) {
        PageTitle("Your recordings")
        NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 0.dp,
                 contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
            recordings.forEachIndexed { i, file ->
                if (i > 0) RowDivider(inset = 58.dp)
                ListRow(
                    title = file.nameWithoutExtension.replace('-', ' ')
                        .replaceFirstChar { it.uppercase() },
                    icon = Icons.AutoMirrored.Outlined.VolumeUp,
                    tone = Tone.TEAL,
                    badgeSize = 40.dp,
                    trailing = {
                        IconButton(onClick = {
                            state.previewSoother(
                                Soother.Recorded(file.nameWithoutExtension, "Your voice", file)
                            )
                        }) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = "Play",
                                 tint = scheme.primary)
                        }
                        IconButton(onClick = { state.deleteRecording(file) }) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete",
                                 tint = scheme.onSurfaceVariant)
                        }
                    },
                )
            }
        }
    }
}

// ------------------------------------------------------------- devices

@Composable
private fun DevicesPage(state: AppState) {
    val linkRole by state.linkRole.collectAsState()
    val linkPaired by state.linkPaired.collectAsState()
    val linkStatus by state.linkStatus.collectAsState()
    val pairingCode by state.pairingCode.collectAsState()
    val bridgeAddress by state.bridgeAddress.collectAsState()
    val scheme = MaterialTheme.colorScheme

    // Next to the laptop view because they are the same kind of thing: both
    // open a socket on the local network, and both are off until asked for.
    NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(Icons.Outlined.PhoneAndroid, Tone.BLUE)
            Column(modifier = Modifier.weight(1f)) {
                Text("A second phone", style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.Bold)
                Text("One phone by the cot, one with you. Alerts go to whichever phone " +
                         "you are carrying.",
                     style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
        TonePill(if (linkPaired) "Paired" else "Not paired",
                 if (linkPaired) Tone.TEAL else Tone.SLATE, dot = true)

        Text("What does this phone do?", style = MaterialTheme.typography.titleSmall)
        SegmentedPills(
            options = listOf("Watches the baby" to null, "Receives alerts" to null),
            selected = if (linkRole == com.nila.phonelink.LinkStore.Role.GUARDIAN) 0 else 1,
            onSelect = {
                state.setLinkRole(
                    if (it == 0) com.nila.phonelink.LinkStore.Role.GUARDIAN
                    else com.nila.phonelink.LinkStore.Role.PARENT
                )
            },
        )

        if (linkRole == com.nila.phonelink.LinkStore.Role.GUARDIAN) {
            GuardianPairing(state, linkPaired, pairingCode)
        } else {
            ParentPairing(state, linkPaired, linkStatus)
        }

        if (linkPaired) {
            TextButton(onClick = state::unpairPhones) { Text("Forget the other phone") }
        }
    }

    // The one thing in this app designed to leave the phone, so it gets its own
    // card rather than being buried as an action on another screen, and it is
    // framed honestly: this opens a socket.
    NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(Icons.Outlined.Laptop, Tone.BLUE)
            Column(modifier = Modifier.weight(1f)) {
                Text("Watch from a laptop", style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.Bold)
                Text("A read-only view in a browser on the same Wi-Fi. Counts and " +
                         "timings only.",
                     style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
        bridgeAddress?.let {
            InfoNote("Open $it on your laptop", icon = Icons.Outlined.Laptop, tone = Tone.BLUE)
        }
        OutlinePillButton(
            if (bridgeAddress != null) "Stop serving" else "Start the laptop view",
            state::toggleDeskBridge,
            Modifier.fillMaxWidth(),
            icon = if (bridgeAddress != null) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
        )
    }
}

/**
 * The sending phone's half of pairing.
 *
 * It generates the code, because the code is only as good as its
 * unpredictability and the phone has a better source of randomness than a
 * person choosing six digits.
 */
@Composable
private fun GuardianPairing(
    state: AppState,
    paired: Boolean,
    code: String?,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (code != null) {
            Text(code, style = MaterialTheme.typography.displaySmall,
                 fontWeight = FontWeight.Bold, color = scheme.primary)
            Text(
                "Type this into the other phone. It stays valid until you pair, and is " +
                    "not stored anywhere.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            state.linkAddress?.let {
                Text("If the other phone cannot find this one, point it at $it",
                     style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            TextButton(onClick = state::hidePairingCode) { Text("Done") }
        } else {
            if (paired) {
                Text(state.linkSummary, style = MaterialTheme.typography.bodyMedium)
            }
            // The address stays on screen after the code has gone, because the
            // other phone is usually typed at second and this is the thing it
            // needs when the network will not carry discovery.
            if (paired) {
                state.linkAddress?.let {
                    Text("This phone is at $it on the Wi-Fi",
                         style = MaterialTheme.typography.bodySmall,
                         color = scheme.onSurfaceVariant)
                }
            }
            OutlinePillButton(
                if (paired) "Show a new code" else "Pair a second phone",
                state::generatePairingCode,
                Modifier.fillMaxWidth(),
                icon = Icons.Outlined.Add,
            )
        }
    }
}

/**
 * The receiving phone's half.
 *
 * The link status is spelled out rather than shown as a dot, because the whole
 * value of this phone is knowing whether it would still hear about a problem --
 * and "connected" and "lost contact" have to be distinguishable at a glance,
 * half asleep.
 */
@Composable
private fun ParentPairing(
    state: AppState,
    paired: Boolean,
    status: com.nila.phonelink.ParentLink.Status,
) {
    var typed by remember { mutableStateOf("") }
    var rejected by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!paired) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it.filter(Char::isDigit).take(6); rejected = false },
                label = { Text("Six digits from the other phone") },
                singleLine = true,
                isError = rejected,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            if (rejected) {
                Text("That is not a six-digit code.",
                     style = MaterialTheme.typography.bodySmall, color = scheme.error)
            }
            PillButton("Connect", { rejected = !state.pairWithCode(typed) },
                       Modifier.fillMaxWidth(), enabled = typed.length == 6)
        } else {
            Text(
                when (status) {
                    is com.nila.phonelink.ParentLink.Status.Connected ->
                        "Connected to the monitoring phone"
                    is com.nila.phonelink.ParentLink.Status.Searching -> status.detail
                    is com.nila.phonelink.ParentLink.Status.Lost ->
                        "Lost contact with the monitoring phone"
                    // The reason rather than a fixed sentence: the other phone
                    // says whether monitoring was stopped or its app was closed,
                    // and those want different things done about them.
                    is com.nila.phonelink.ParentLink.Status.Stopped ->
                        status.reason.ifBlank { "The other phone stopped monitoring" }
                    // Paired, so this is the link coming up rather than a phone
                    // with nothing to connect to.
                    com.nila.phonelink.ParentLink.Status.Unpaired -> "Starting up"
                },
                style = MaterialTheme.typography.titleSmall,
            )

            ManualAddress(state)
            Text(
                "Urgent alerts sound on the alarm volume, so this phone can stay on silent.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )

            // Said plainly rather than discovered on the night it matters:
            // Android 14 and later do not grant this at install.
            if (!state.canUseFullScreenAlerts) {
                Text(
                    "The most serious alerts cannot turn this screen on yet. Android asks " +
                        "for that separately.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                OutlinePillButton("Allow full-screen alerts", state::requestFullScreenAlerts,
                                  Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * The address of the other phone, typed in when discovery cannot find it.
 *
 * Folded away by default, because on a network that carries mDNS -- most homes
 * -- nobody should ever need it. It is here because plenty of routers filter
 * multicast between clients and several OEM builds drop it to an app whose
 * screen is off, and on those networks discovery searches forever with no way
 * out. The address to type is on the other phone's Settings screen.
 */
@Composable
private fun ManualAddress(state: AppState) {
    var open by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var rejected by remember { mutableStateOf(false) }

    if (!open) {
        TextButton(onClick = { open = true }) { Text("It cannot find the other phone") }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Type the address shown under the pairing code on the other phone. " +
                "Discovery is still tried first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = typed,
            onValueChange = {
                typed = it.filter { c -> c.isDigit() || c == '.' }.take(15)
                rejected = false
            },
            label = { Text("e.g. 192.168.1.42") },
            singleLine = true,
            isError = rejected,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        if (rejected) {
            Text("That is not an address on the Wi-Fi.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Use it", {
                if (state.useLinkAddress(typed)) { open = false; typed = "" }
                else rejected = true
            })
            TextButton(onClick = { open = false; rejected = false }) { Text("Cancel") }
        }
    }
}

// ------------------------------------------------------------- reports

@Composable
private fun ReportsPage(state: AppState) {
    val exported by state.exportedReport.collectAsState()
    val thinking by state.thinking.collectAsState()
    NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(Icons.Outlined.Description, Tone.TEAL)
            Column(modifier = Modifier.weight(1f)) {
                Text("Export for your paediatrician", style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.Bold)
                Text("A one-page PDF of crying, day by day, to take to an appointment.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        exported?.let {
            InfoNote("Saved to ${it.absolutePath}", icon = Icons.Outlined.Check, tone = Tone.TEAL)
        }
        PillButton(
            if (thinking) "Building..." else "Export the last 14 days",
            state::exportClinicReport,
            Modifier.fillMaxWidth(),
            icon = Icons.Outlined.Download,
            enabled = !thinking,
        )
    }
}

/**
 * The model Ask answers with: Qwen3.5-2B on the Hexagon NPU.
 *
 * Its state is the NPU engine's own, not the download's, because "the file is
 * here" and "the NPU accepted it" are different facts and only the second one
 * means Ask is using it.
 */
@Composable
private fun AssistantModelCard(state: AppState) {
    val download by state.assistantModelState.collectAsState()
    val npu by state.npuState.collectAsState()
    val scheme = MaterialTheme.colorScheme
    NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(Icons.Outlined.Memory, Tone.PLUM)
            Column(modifier = Modifier.weight(1f)) {
                Text("Answers in your own words", style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.Bold)
                Text("Qwen3.5-2B on this phone's NPU. Ask uses it to answer questions " +
                         "about your own records; without it, Ask shows the sourced text.",
                     style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
        when {
            npu is com.nila.assistant.NpuLlm.State.Ready -> {
                val ready = npu as com.nila.assistant.NpuLlm.State.Ready
                InfoNote("Answering on the ${ready.computeUnit.uppercase()}, loaded in " +
                             "${"%.1f".format(ready.loadMs / 1000f)} s.",
                         icon = Icons.Outlined.Check, tone = Tone.TEAL)
            }
            download is com.nila.assistant.ModelInstaller.State.Downloading -> {
                val pct = (download as com.nila.assistant.ModelInstaller.State.Downloading).percent
                Text("Downloading $pct% of 1.2 GB", style = MaterialTheme.typography.bodyMedium)
                OutlinePillButton("Cancel", state::cancelAssistantDownload, Modifier.fillMaxWidth())
            }
            npu is com.nila.assistant.NpuLlm.State.Initialising ||
                npu is com.nila.assistant.NpuLlm.State.Registering ||
                npu is com.nila.assistant.NpuLlm.State.Loading ->
                InfoNote("Loading onto the NPU…", icon = Icons.Outlined.Memory)
            download is com.nila.assistant.ModelInstaller.State.Installed -> {
                val reason = (npu as? com.nila.assistant.NpuLlm.State.Failed)?.reason
                InfoNote(
                    if (reason != null) "Downloaded, but the NPU did not load it: $reason"
                    else "Downloaded. It loads the next time Nila starts.",
                    icon = Icons.Outlined.Memory,
                    tone = if (reason != null) Tone.PEACH else Tone.SLATE,
                )
            }
            download is com.nila.assistant.ModelInstaller.State.Failed -> {
                Text("Failed: ${(download as com.nila.assistant.ModelInstaller.State.Failed).reason}",
                     style = MaterialTheme.typography.bodyMedium, color = scheme.error)
                OutlinePillButton("Try again", state::downloadAssistantModel, Modifier.fillMaxWidth(),
                                  icon = Icons.Outlined.Refresh)
            }
            else -> {
                Text("1.2 GB, once, over Wi-Fi. Everything else works without it.",
                     style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                OutlinePillButton("Download", state::downloadAssistantModel, Modifier.fillMaxWidth(),
                                  icon = Icons.Outlined.Download)
            }
        }
    }
}

// -------------------------------------------------------------- camera

/**
 * The switches for the camera lane's scene model, night vision and distraction.
 *
 * Each default is the cautious one, and each is here because it is a judgement
 * rather than a setting: whether a model may run at all, whether the phone may
 * act at a baby rather than only report, and whether it may point a torch into a
 * cot. The last of those is off even when distraction is on.
 *
 * Reads its own store rather than going through [AppState], the way the clip
 * policy does: four preferences belonging to a service, with nothing in the view
 * model to hold.
 */
@Composable
private fun CameraPage(state: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { VisionSettings(context) }
    val values by settings.values.collectAsState(initial = VisionSettings.Values())

    NilaCard(modifier = Modifier.fillMaxWidth()) {
        SwitchRow(
            icon = Icons.Outlined.Visibility,
            tone = Tone.PLUM,
            title = "Describe what it sees",
            body = if (values.modelEnabled) "On – a vision model looks when something " +
                "changes, and rests while the baby is settled"
            else "Off – face, posture and movement only",
            checked = values.modelEnabled,
        ) { scope.launch { settings.setModelEnabled(it) } }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PageTitle("Night vision")
        val options = listOf(
            DayNight.Preference.AUTO to "Automatic",
            DayNight.Preference.ALWAYS_NIGHT to "Always on",
            DayNight.Preference.ALWAYS_DAY to "Off",
        )
        SegmentedPills(
            options = options.map { it.second to null },
            selected = options.indexOfFirst { it.first == values.nightPreference }.coerceAtLeast(0),
            onSelect = { i -> scope.launch { settings.setNightPreference(options[i].first) } },
        )
        Text(
            "In a dark nursery the camera sees mostly noise until the picture is lifted. " +
                "Automatic decides from the light sensor and the picture itself. Pick " +
                "one yourself if your room fools it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PageTitle("When it sees a hazard")
        NilaCard(modifier = Modifier.fillMaxWidth()) {
            SwitchRow(
                icon = Icons.Outlined.MusicNote,
                tone = Tone.TEAL,
                title = "Try to distract the baby",
                body = if (values.distractionEnabled) "On – plays the sound that settles " +
                    "your baby and lights the ring, to buy you the seconds it takes to " +
                    "cross the room"
                else "Off – you get a notification only",
                checked = values.distractionEnabled,
            ) { scope.launch { settings.setDistractionEnabled(it) } }
            if (values.distractionEnabled) {
                RowDivider()
                SwitchRow(
                    icon = Icons.Outlined.FlashlightOn,
                    tone = Tone.PEACH,
                    title = "Use the flash as well",
                    body = "A bright light into the cot. Right for a crawler reaching for a " +
                        "socket in daylight, wrong for a drowsy baby at 3am.",
                    checked = values.torchEnabled,
                ) { scope.launch { settings.setTorchEnabled(it) } }
            }
        }
    }

    // Which model would answer, so "Describe what it sees" is never a switch
    // for something that is not on the phone.
    NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 0.dp,
             contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
        Capability(Icons.Outlined.Memory, "Scene model", state.sceneDescriber.describe())
    }

    InfoNote(
        "Nothing here reaches the network. The model runs on this phone's NPU when it " +
            "is installed, and the watch keeps working without it.",
        icon = Icons.Outlined.Shield,
        tone = Tone.TEAL,
    )
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    tone: Tone,
    title: String,
    body: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        IconBadge(icon, tone)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ------------------------------------------------------------ app & data

@Composable
private fun AppDataPage(state: AppState) {
    val modelState by state.modelState.collectAsState()
    val visionModelState by state.visionModelState.collectAsState()
    val irRemote by state.irRemote.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    PageTitle("Models")
    NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 0.dp,
             contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
        Capability(Icons.Outlined.GraphicEq, "Cry detection",
                   "Trained on unseen infants, runs offline")
        RowDivider(inset = 58.dp)
        Capability(Icons.Outlined.Memory, "Cause estimate", "A best guess, shown as one")
        RowDivider(inset = 58.dp)
        Capability(Icons.Outlined.CloudOff, "Everything", "On this phone. No account, no network.")
    }

    AssistantModelCard(state)

    // ---- language model
    NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(Icons.AutoMirrored.Outlined.ShortText, Tone.TEAL)
            Column(modifier = Modifier.weight(1f)) {
                Text("Shorter answers", style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.Bold)
                Text("A small model that shortens answers into two or three sentences. " +
                         "Facts always come from the sourced entries, never from the model.",
                     style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
        when (val m = modelState) {
            is com.nila.assistant.ModelInstaller.State.Installed ->
                InfoNote("Installed. ${state.llmStatus}", icon = Icons.Outlined.Check,
                         tone = Tone.TEAL)
            is com.nila.assistant.ModelInstaller.State.Downloading -> {
                Text("Downloading ${m.percent}% of 547 MB", style = MaterialTheme.typography.bodyMedium)
                OutlinePillButton("Cancel", state::cancelModelDownload, Modifier.fillMaxWidth())
            }
            is com.nila.assistant.ModelInstaller.State.Failed -> {
                Text("Failed: ${m.reason}", style = MaterialTheme.typography.bodyMedium,
                     color = scheme.error)
                OutlinePillButton("Try again", state::downloadModel, Modifier.fillMaxWidth(),
                                  icon = Icons.Outlined.Refresh)
            }
            else -> {
                Text("547 MB, once, over Wi-Fi. This is the only outbound request Nila " +
                         "ever makes, and everything works without it.",
                     style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                OutlinePillButton("Download", state::downloadModel, Modifier.fillMaxWidth(),
                                  icon = Icons.Outlined.Download)
            }
        }
    }

    // Hardware capability reporting. The IR blaster is genuinely uncertain on
    // this platform, so the app states which situation it is in rather than
    // hiding a silent failure.
    PageTitle("This device")
    NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 0.dp,
             contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
        Capability(Icons.Outlined.Memory, "Infrared blaster", state.irActuator.describe())
        RowDivider(inset = 58.dp)
        Capability(Icons.Outlined.Nfc, "NFC stickers", state.nfc.describe())
        RowDivider(inset = 58.dp)
        Capability(Icons.Outlined.MedicalInformation, "Knowledge base",
                   "${state.knowledgeSize} entries, offline")
        RowDivider(inset = 58.dp)
        Capability(Icons.Outlined.NotificationsNone, "Smartwatch alerts",
                   "Any paired watch or band, no setup")
        RowDivider(inset = 58.dp)
        Capability(Icons.Outlined.Description, "Text recognition", state.ocrStatus)
        RowDivider(inset = 58.dp)
        // The optional vision model lives here rather than on the Watch screen:
        // an optional download belongs beside the other optional download, not
        // in the middle of the thing you are trying to watch.
        Capability(Icons.Outlined.Visibility, "Describing the cot",
                   state.sceneDescriber.describe())
    }
    if (state.sceneDescriber.isSupported && !state.sceneDescriber.isInstalled) {
        when (val v = visionModelState) {
            is com.nila.assistant.ModelInstaller.State.Downloading ->
                Text("Downloading ${v.percent}% of 1.1 GB", style = MaterialTheme.typography.bodyMedium)
            else -> OutlinePillButton("Download vision model (1.1 GB)", state::downloadVisionModel,
                                      Modifier.fillMaxWidth(), icon = Icons.Outlined.Download)
        }
    }

    // Only offered where it can actually work. On the far more common phone
    // with no emitter, an input box that silently does nothing is worse than no
    // input box.
    if (state.irActuator.isReady) {
        var address by remember(irRemote) { mutableStateOf(irRemote?.address?.toString() ?: "") }
        var command by remember(irRemote) { mutableStateOf(irRemote?.command?.toString() ?: "") }
        var sent by remember { mutableStateOf<Boolean?>(null) }
        NilaCard(modifier = Modifier.fillMaxWidth()) {
            Text("Enter your remote's NEC code to control a fan or AC.",
                 style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = address, onValueChange = { address = it },
                    label = { Text("Address 0-255") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = command, onValueChange = { command = it },
                    label = { Text("Command 0-255") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinePillButton("Save", {
                    state.saveIrRemote(address.toIntOrNull() ?: -1, command.toIntOrNull() ?: -1)
                }, Modifier.weight(1f))
                OutlinePillButton("Send it now", { state.testIrRemote { sent = it } },
                                  Modifier.weight(1f))
            }
            sent?.let {
                Text(
                    if (it) "Transmitted. If nothing moved, the code is wrong."
                    else "Nothing was sent – no code saved, or the system refused the emitter.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }

    PageTitle("Demo data")
    NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(Icons.Outlined.Storage, Tone.SLATE)
            Text(
                "A fresh install is seeded with one example family so every screen has " +
                    "something to show. Resetting wipes the log and lays it down again.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinePillButton("Reset to demo data", { confirmReset = true }, Modifier.fillMaxWidth(),
                          icon = Icons.Outlined.Refresh)
    }

    if (confirmReset) {
        ConfirmDialog(
            title = "Reset to the demo family?",
            body = "Your log is wiped and the example family is laid down again.",
            action = "Reset",
            onConfirm = { state.resetDemoData(); confirmReset = false },
            onDismiss = { confirmReset = false },
        )
    }
}

@Composable
private fun Capability(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconBadge(icon, Tone.SLATE, size = 40.dp, iconSize = 21.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(value, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------- privacy

@Composable
private fun PrivacyPage() {
    NilaCard(modifier = Modifier.fillMaxWidth(), tone = Tone.TEAL) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(Icons.Outlined.Lock, Tone.TEAL, size = 52.dp)
            Column {
                Text("Your data stays on this device", style = MaterialTheme.typography.titleMedium,
                     fontWeight = FontWeight.Bold)
                Text("No network requests. No account. No analytics.",
                     style = MaterialTheme.typography.bodySmall,
                     color = toneColors(Tone.TEAL).ink)
            }
        }
    }
    NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 0.dp,
             contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
        listOf(
            Triple(Icons.Outlined.GraphicEq, "Only a cry is recorded",
                   "90 seconds per episode, kept for 7 days in this app's private storage."),
            Triple(Icons.Outlined.CloudOff, "Left out of cloud backup",
                   "Your log and documents are excluded from every backup."),
            Triple(Icons.Outlined.MedicalInformation, "An awareness aid",
                   "Not a medical device. It does not monitor breathing or vital signs."),
        ).forEachIndexed { i, (icon, title, body) ->
            if (i > 0) RowDivider(inset = 58.dp)
            ListRow(title = title, subtitle = body, icon = icon, tone = Tone.TEAL,
                    badgeSize = 40.dp, subtitleLines = 3)
        }
    }
}

// ------------------------------------------------------------- shared

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    action: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        title = { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
