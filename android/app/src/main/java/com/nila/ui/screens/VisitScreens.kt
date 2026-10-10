package com.nila.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Vaccines
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nila.data.Severity
import com.nila.ui.components.ChoicePills
import com.nila.ui.components.DoctorVisitArt
import com.nila.ui.components.IconBadge
import com.nila.ui.components.InfoNote
import com.nila.ui.components.ListRow
import com.nila.ui.components.NilaCard
import com.nila.ui.components.OutlinePillButton
import com.nila.ui.components.PillButton
import com.nila.ui.components.RowDivider
import com.nila.ui.components.ScreenHero
import com.nila.ui.components.SectionTitle
import com.nila.ui.theme.Tone
import com.nila.ui.theme.severityColors
import com.nila.ui.theme.toneColors
import com.nila.visit.VisitModels
import com.nila.visit.VisitStore
import com.nila.visit.VisitViewModel
import com.nila.visit.extract.Medicine
import com.nila.visit.extract.Note
import com.nila.visit.extract.VisitNotes
import com.nila.visit.remind.Planner
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The doctor visit tab.
 *
 * One tab, three states: the visit list with a record button; the recording,
 * while the doctor talks; and the notes being written, with the visit played
 * back and its words appearing in step. A finished visit opens as its own page
 * ([VisitNotesScreen]) so back returns here.
 */
@Composable
fun VisitScreen(
    vm: VisitViewModel,
    onOpenVisit: (String) -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val phase by vm.phase.collectAsState()
    val opened by vm.opened.collectAsState()

    // A visit that has just been written opens by itself -- when this tab is
    // on screen. Finished while the parent was on another tab, it waits here.
    LaunchedEffect(opened) {
        opened?.let { vm.opened.value = null; onOpenVisit(it) }
    }

    when (val p = phase) {
        is VisitViewModel.Phase.Recording -> RecordingPane(p, vm::stopRecording)
        is VisitViewModel.Phase.Working -> {
            val muted by vm.replayMuted.collectAsState()
            WorkingPane(p, muted, vm::setReplayMuted)
        }
        else -> VisitHome(vm, p as? VisitViewModel.Phase.Failed, onOpenVisit, onOpenCalendar, onOpenSettings)
    }
}

private val LANGUAGES = listOf(
    "auto" to "Any language", "en" to "English", "hi" to "हिन्दी", "ta" to "தமிழ்", "te" to "తెలుగు",
)

@Composable
private fun VisitHome(
    vm: VisitViewModel,
    failed: VisitViewModel.Phase.Failed?,
    onOpenVisit: (String) -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val visits by vm.visits.collectAsState()
    val lang by vm.language.collectAsState()
    val models by vm.modelState.collectAsState()
    val ready = models == VisitModels.State.Ready
    var allSamples by rememberSaveable { mutableStateOf(false) }
    var allVisits by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshModels() }

    // The language picked is the one the doctor will speak, so the samples
    // shown are the ones in that language; "Any language" shows them all.
    val sampleList by vm.samples.collectAsState()
    val samples = sampleList.filter { lang == "auto" || it.optString("language") == lang }
    val past = visits.filterNot { it.id.startsWith("sample-") } + visits.filter { it.id.startsWith("sample-") }
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        ScreenHero(
            title = "At the doctor's",
            body = "Record the visit. Nila writes down the medicines, dates and advice " +
                "in the doctor's own words. Everything stays on this phone.",
            art = { DoctorVisitArt() },
        )

        if (!ready) ModelsCard(models, vm::downloadModels, vm::cancelModelDownload, onOpenSettings)

        failed?.let {
            val c = severityColors(Severity.URGENT)
            NilaCard(Modifier.fillMaxWidth(), borderColor = c.accent.copy(alpha = 0.4f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.WarningAmber, null, tint = c.accent)
                    Text(it.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
                TextButton(onClick = vm::dismissError, modifier = Modifier.align(Alignment.End)) { Text("OK") }
            }
        }

        NilaCard(Modifier.fillMaxWidth(), tone = Tone.TEAL) {
            Text("The doctor will speak", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            ChoicePills(
                options = LANGUAGES.map { it.second },
                selected = LANGUAGES.indexOfFirst { it.first == lang }.coerceAtLeast(0),
                onSelect = { vm.language.value = LANGUAGES[it].first },
            )
            PillButton(
                "Record the visit",
                onClick = vm::startRecording,
                icon = Icons.Outlined.Mic,
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Keep the phone near the doctor. Stop when the visit is over.",
                 style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }

        NilaCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            ListRow(
                title = "Calendar",
                subtitle = "Next visits, vaccines and medicine courses",
                icon = Icons.Outlined.CalendarMonth,
                tone = Tone.BLUE,
                badgeSize = 40.dp,
                onClick = onOpenCalendar,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle(
                "Try a sample visit",
                action = if (samples.size > 3) (if (allSamples) "Show less" else "View all ${samples.size}") else null,
                onAction = { allSamples = !allSamples },
            )
            Hint("A recorded doctor, read from a file, to see what Nila writes down.")
            if (samples.isEmpty()) {
                Hint("No samples in this language yet.")
            } else {
                RowsCard(if (allSamples) samples else samples.take(3)) { s ->
                    ListRow(
                        title = VisitViewModel.sampleTitle(s),
                        subtitle = VisitViewModel.languageName(s.optString("language")) + " · " +
                            s.getString("transcript").take(70) + "…",
                        icon = Icons.Outlined.Science,
                        tone = Tone.PLUM,
                        badgeSize = 40.dp,
                        subtitleLines = 1,
                        onClick = if (ready) ({ vm.runSample(s) }) else null,
                        chevron = ready,
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle(
                "Past visits",
                action = if (past.size > 3) (if (allVisits) "Show less" else "View all ${past.size}") else null,
                onAction = { allVisits = !allVisits },
            )
            if (past.isEmpty()) {
                Hint("Your recorded visits will appear here.")
            } else {
                RowsCard((if (allVisits) past else past.take(3))) { v ->
                    val dates = v.notes.appointments.size + v.notes.tests.size + v.notes.vaccines.count { !it.givenToday }
                    ListRow(
                        title = v.title,
                        subtitle = if (!v.notesWritten) {
                            v.date.format(DateTimeFormatter.ofPattern("d MMM")) + " · notes not written yet"
                        } else listOf(
                            v.date.format(DateTimeFormatter.ofPattern("d MMM")),
                            plural(v.notes.medicines.size, "medicine"),
                            plural(dates, "date"),
                        ).joinToString(" · ") + if (v.remindersSet) " · reminders on" else "",
                        icon = if (v.id.startsWith("sample-")) Icons.Outlined.Science
                               else if (v.remindersSet) Icons.Outlined.Alarm else Icons.Outlined.Medication,
                        tone = if (v.id.startsWith("sample-")) Tone.PLUM else Tone.PEACH,
                        badgeSize = 40.dp,
                        subtitleLines = 1,
                        onClick = { onOpenVisit(v.id) },
                    )
                }
            }
        }

    }
}

/** What has to be downloaded before the first visit, and getting it. */
@Composable
private fun ModelsCard(
    state: VisitModels.State,
    download: () -> Unit,
    cancel: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    NilaCard(Modifier.fillMaxWidth(), tone = Tone.PEACH) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Outlined.CloudDownload, Tone.PEACH, size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (state == VisitModels.State.NeedsChatModel) "Ask's model is needed too"
                    else "One-time download",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when (state) {
                        VisitModels.State.NeedsChatModel ->
                            "The notes are written by the model Ask uses. Download it in Settings."
                        is VisitModels.State.Downloading -> "Downloading the listening model… ${state.percent}%"
                        VisitModels.State.Unpacking -> "Setting up the listening model…"
                        is VisitModels.State.Failed -> state.reason
                        else -> "The listening model (Whisper, 860 MB) turns the doctor's words into text " +
                            "on this phone's NPU. Over Wi-Fi only."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (state) {
            VisitModels.State.NeedsChatModel ->
                OutlinePillButton("Open Settings", onClick = onOpenSettings, modifier = Modifier.fillMaxWidth())
            is VisitModels.State.Downloading -> {
                LinearProgressIndicator(
                    progress = { state.percent / 100f },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                )
                OutlinePillButton("Cancel", onClick = cancel, modifier = Modifier.fillMaxWidth())
            }
            VisitModels.State.Unpacking ->
                LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
            else -> PillButton("Download (860 MB)", onClick = download, icon = Icons.Outlined.CloudDownload,
                               modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun <T> RowsCard(items: List<T>, row: @Composable (T) -> Unit) {
    NilaCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
             verticalSpacing = 0.dp) {
        items.forEachIndexed { i, item ->
            if (i > 0) RowDivider(inset = 54.dp)
            row(item)
        }
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall,
                                      color = MaterialTheme.colorScheme.onSurfaceVariant)

private fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

// ----------------------------------------------------- recording & working

@Composable
private fun RecordingPane(p: VisitViewModel.Phase.Recording, stop: () -> Unit) {
    val red = severityColors(Severity.URGENT)
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(170.dp).clip(CircleShape).background(red.container), contentAlignment = Alignment.Center) {
            Box(Modifier.size((70 + 90 * p.level.coerceIn(0f, 1f)).dp).clip(CircleShape)
                .background(red.accent.copy(alpha = 0.22f)))
            Icon(Icons.Outlined.Mic, null, tint = red.accent, modifier = Modifier.size(56.dp))
        }
        Text("%d:%02d".format(p.seconds / 60, p.seconds % 60), style = MaterialTheme.typography.displaySmall,
             fontWeight = FontWeight.Bold)
        Text("Listening to the doctor. Keep the phone near them.",
             style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = stop,
            colors = ButtonDefaults.buttonColors(containerColor = red.accent, contentColor = red.container),
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = CircleShape,
        ) {
            Icon(Icons.Outlined.Stop, null); Spacer(Modifier.width(8.dp))
            Text("Visit finished", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun WorkingPane(p: VisitViewModel.Phase.Working, muted: Boolean, setMuted: (Boolean) -> Unit) {
    // While the notes are written the visit plays back and its words appear in
    // step with it, so the wait is spent hearing the doctor again.
    var position by remember { mutableFloatStateOf(0f) }
    val player = remember(p.audio) {
        p.audio?.takeIf { it.exists() }?.let { f ->
            runCatching { android.media.MediaPlayer().apply { setDataSource(f.absolutePath); prepare() } }.getOrNull()
        }
    }
    // Volume before start, so a muted replay never makes a sound.
    LaunchedEffect(muted, player) { player?.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f) }
    DisposableEffect(player) {
        player?.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
        player?.start()
        onDispose { runCatching { player?.stop(); player?.release() } }
    }
    LaunchedEffect(player, p.transcript) {
        if (p.transcript.isBlank()) return@LaunchedEffect
        val words = p.transcript.split(" ").size
        while (true) {
            position = if (player != null && player.duration > 0) {
                runCatching { player.currentPosition.toFloat() / player.duration }.getOrDefault(1f)
            } else (position + 1f / words).coerceAtMost(1f)
            if (player != null && runCatching { !player.isPlaying }.getOrDefault(true) && position > 0.98f) position = 1f
            kotlinx.coroutines.delay(120)
        }
    }
    val scheme = MaterialTheme.colorScheme

    Column(
        Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(p.step, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.detail, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                 modifier = Modifier.weight(1f))
            if (player != null) {
                IconButton(onClick = { setMuted(!muted) }) {
                    Icon(if (muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                         contentDescription = if (muted) "Turn the replay sound on" else "Mute the replay",
                         tint = scheme.primary)
                }
            }
        }
        if (p.progress != null) {
            LinearProgressIndicator(progress = { p.progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
        }

        if (p.transcript.isNotBlank()) {
            NilaCard(Modifier.fillMaxWidth().weight(1f), verticalSpacing = 10.dp) {
                Text("What the doctor said", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                Column(Modifier.verticalScroll(rememberScrollState(), reverseScrolling = true)) {
                    val words = p.transcript.split(" ")
                    val shown = (words.size * position).toInt().coerceIn(1, words.size)
                    Text(words.take(shown).joinToString(" ") + if (shown < words.size) " ▍" else "",
                         style = MaterialTheme.typography.bodyLarge)
                }
            }
            p.partial?.let { FoundSoFar(it, p.passesDone) }
        } else {
            Spacer(Modifier.weight(1f))
            InfoNote("First the speech is turned into text, then the notes are written — one model on the NPU at a time. " +
                     "Keep Nila open; the screen stays on until it is done.")
        }
    }
}

@Composable
private fun FoundSoFar(n: VisitNotes, passes: Int) {
    val dates = n.appointments.size + n.tests.size + n.vaccines.count { !it.givenToday }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Progress(plural(n.medicines.size, "medicine"), "Medicines", Tone.PEACH, passes >= 1)
        Progress(plural(dates, "date"), "Dates", Tone.BLUE, passes >= 2)
        Progress("${n.watchFor.size + n.dos.size + n.donts.size} advice", "Advice", Tone.TEAL, passes >= 3)
    }
}

@Composable
private fun Progress(done: String, pending: String, tone: Tone, isDone: Boolean) {
    val c = toneColors(tone)
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.clip(CircleShape).background(if (isDone) c.container else scheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (isDone) Icon(Icons.Outlined.CheckCircle, null, tint = c.ink, modifier = Modifier.size(16.dp))
        Text(if (isDone) done else "$pending…", style = MaterialTheme.typography.labelLarge,
             color = if (isDone) c.ink else scheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- a visit

/** One visit's notes, as a page of its own off the Doctor tab. */
@Composable
fun VisitNotesScreen(vm: VisitViewModel, id: String, onClose: () -> Unit) {
    val visits by vm.visits.collectAsState()
    val v = visits.firstOrNull { it.id == id }
    if (v == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Hint("This visit was deleted.") }
        return
    }
    var showTranscript by rememberSaveable(v.notesWritten) { mutableStateOf(!v.notesWritten) }
    var confirmDelete by remember { mutableStateOf(false) }
    var setCount by remember { mutableStateOf<Int?>(null) }
    val n = v.notes
    val day = DateTimeFormatter.ofPattern("EEE d MMM")
    val scheme = MaterialTheme.colorScheme

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(v.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("${v.date.format(day)}${v.language?.let { " · heard in ${VisitViewModel.languageName(it)}" } ?: ""}",
                 style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        if (!v.notesWritten) {
            NilaCard(Modifier.fillMaxWidth(), tone = Tone.PEACH) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.WarningAmber, null, tint = toneColors(Tone.PEACH).ink)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("The notes were not written", style = MaterialTheme.typography.titleSmall,
                             fontWeight = FontWeight.SemiBold)
                        Text("What the doctor said is saved below. Try writing the notes again.",
                             style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
                PillButton("Write the notes again", onClick = { vm.writeNotesAgain(v); onClose() },
                           icon = Icons.Outlined.Refresh, modifier = Modifier.fillMaxWidth())
            }
        }
        if (n.summary.isNotBlank()) {
            NilaCard(Modifier.fillMaxWidth(), tone = Tone.TEAL) { Text(n.summary, style = MaterialTheme.typography.bodyLarge) }
        }

        if (v.notesWritten) Section("Medicines", Icons.Outlined.Medication, Tone.PEACH) {
            if (n.medicines.isEmpty()) Hint("No medicines were mentioned.")
            n.medicines.forEach { MedicineRow(it) }
            val plan = remember(v) { Planner.plan(n, v.date) }
            if (plan.isNotEmpty()) {
                if (v.remindersSet) {
                    val teal = toneColors(Tone.TEAL)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CheckCircle, null, tint = teal.ink); Spacer(Modifier.width(8.dp))
                        Text("${setCount ?: plan.size} reminders are set", color = teal.ink,
                             style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.clearReminders(v) }) { Text("Remove") }
                    }
                } else {
                    PillButton("Set ${plan.size} reminders", onClick = { setCount = vm.setReminders(v) },
                               icon = Icons.Outlined.Alarm, modifier = Modifier.fillMaxWidth())
                }
                Hint("Amounts are written as the doctor said them. Check them against the prescription.")
            }
        }

        if (v.notesWritten) Section("Dates", Icons.Outlined.CalendarMonth, Tone.BLUE) {
            val dated = n.appointments.map { Triple(it.what, it.date, it.quote) } +
                n.vaccines.filter { !it.givenToday }.map { Triple("Vaccine: ${it.name}", it.date, it.quote) } +
                n.tests.map { Triple("Test: ${it.what}", it.date, it.quote) }
            if (dated.isEmpty()) Hint("No dates were given.")
            dated.forEach { (what, date, quote) ->
                Item(what, date?.format(day) ?: "Date not clear",
                     if (date == null) severityColors(Severity.URGENT).accent else toneColors(Tone.BLUE).ink,
                     quote, Tone.BLUE)
            }
            n.vaccines.filter { it.givenToday }.forEach {
                Item("Given today: ${it.name}", null, toneColors(Tone.BLUE).ink, it.quote, Tone.BLUE, Icons.Outlined.Vaccines)
            }
        }

        if (v.notesWritten) {
            NotesSection("Watch for", Icons.Outlined.WarningAmber, Tone.PINK, n.watchFor, "Nothing to watch for was mentioned.")
            NotesSection("Do", Icons.Outlined.CheckCircle, Tone.TEAL, n.dos, "Nothing was mentioned.")
            NotesSection("Don't", Icons.Outlined.Close, Tone.PLUM, n.donts, "Nothing was mentioned.")
        }

        OutlinePillButton(if (showTranscript) "Hide what was heard" else "Show what was heard",
                          onClick = { showTranscript = !showTranscript }, icon = Icons.Outlined.Notes,
                          modifier = Modifier.fillMaxWidth())
        AnimatedVisibility(showTranscript) {
            NilaCard(Modifier.fillMaxWidth()) {
                Text(v.transcript, style = MaterialTheme.typography.bodyMedium)
                if (n.dropped.isNotEmpty()) {
                    Text("Left out (not in what the doctor said):", style = MaterialTheme.typography.labelLarge,
                         color = severityColors(Severity.URGENT).accent)
                    n.dropped.forEach { Hint("• $it") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (vm.hasRecording(v)) {
                OutlinePillButton("Write again", onClick = { vm.reprocess(v); onClose() },
                                  icon = Icons.Outlined.Refresh, modifier = Modifier.weight(1f))
            }
            OutlinePillButton("Delete", onClick = { confirmDelete = true }, icon = Icons.Outlined.DeleteOutline,
                              danger = true, modifier = Modifier.weight(1f))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this visit?") },
            text = { Text("Its notes, recording and reminders are removed from this phone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete(v); onClose() }) {
                    Text("Delete", color = severityColors(Severity.URGENT).accent)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun MedicineRow(m: Medicine) {
    // Compact: name and amount, one line of when, chips for times and course.
    // The doctor's words fold away under a tap.
    var open by remember { mutableStateOf(false) }
    val c = toneColors(Tone.PEACH)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.wash)
            .clickable(enabled = m.quote.isNotBlank()) { open = !open }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.name.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium,
                 fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1,
                 overflow = TextOverflow.Ellipsis)
            if (m.amount.isNotBlank()) {
                Text(m.amount, color = c.ink, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            }
        }
        if (m.instructions.isNotBlank()) {
            Text(m.instructions.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium,
                 maxLines = if (open) 4 else 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            if (m.asNeeded) Chip("Only when needed", c.ink)
            else m.reminderTimes().forEach { Chip(it.toString(), c.ink) }
            if (!m.asNeeded) Chip(m.durationDays?.let { "$it days" } ?: "Ongoing", c.ink)
        }
        if (m.quote.isNotBlank()) {
            if (open) Quote(m.quote)
            else Text("Doctor's words ›", color = c.ink, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Chip(t: String, ink: Color) {
    Text(t, color = ink, style = MaterialTheme.typography.labelMedium,
         modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
             .padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable
private fun NotesSection(title: String, icon: ImageVector, tone: Tone, notes: List<Note>, empty: String) {
    Section(title, icon, tone) {
        if (notes.isEmpty()) Hint(empty)
        // The same sign is sometimes said twice; it is written down once.
        notes.distinctBy { it.text.trim().lowercase() }.forEach {
            Item(it.text, if (it.urgent) "Come at once" else null, severityColors(Severity.URGENT).accent, it.quote, tone)
        }
    }
}

@Composable
private fun Item(title: String, trailing: String?, trailingColor: Color, quote: String, tone: Tone, icon: ImageVector? = null) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(toneColors(tone).wash)
            .clickable(enabled = quote.isNotBlank()) { open = !open }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon?.let { Icon(it, null, tint = trailingColor, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
            Text(title.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge,
                 fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            trailing?.let {
                Spacer(Modifier.width(8.dp))
                Text(it, color = trailingColor, style = MaterialTheme.typography.labelLarge)
            }
        }
        if (quote.isNotBlank() && open) Quote(quote)
    }
}

@Composable
private fun Quote(q: String) {
    Text("“${q.trim().trim('"')}”", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
         color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Section(title: String, icon: ImageVector, tone: Tone, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconBadge(icon, tone, size = 32.dp, iconSize = 18.dp)
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

// -------------------------------------------------------------- calendar

/** Every dated thing from every visit, by month. A page off the Doctor tab. */
@Composable
fun VisitCalendarScreen(vm: VisitViewModel) {
    val visits by vm.visits.collectAsState()
    var month by rememberSaveable { mutableStateOf(YearMonth.now()) }
    var picked by rememberSaveable { mutableStateOf(LocalDate.now()) }
    data class Ev(val date: LocalDate, val label: String, val tone: Tone)
    val events = remember(visits) {
        visits.filterNot { it.id.startsWith("sample-") }.flatMap { v ->
            v.notes.appointments.mapNotNull { a -> a.date?.let { Ev(it, a.what.replaceFirstChar { c -> c.uppercase() }, Tone.BLUE) } } +
                v.notes.vaccines.mapNotNull { x -> x.date?.let { Ev(it, "Vaccine: ${x.name}", Tone.PLUM) } } +
                v.notes.tests.mapNotNull { t -> t.date?.let { Ev(it, "Test: ${t.what}", Tone.BLUE) } } +
                v.notes.medicines.filter { !it.asNeeded }.flatMap { m ->
                    (0 until (m.durationDays ?: 7)).map {
                        Ev(v.date.plusDays(it.toLong()), m.name.replaceFirstChar { c -> c.uppercase() }, Tone.PEACH)
                    }
                }
        }.groupBy { it.date }
    }
    val scheme = MaterialTheme.colorScheme
    val today = LocalDate.now()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NilaCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalSpacing = 6.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = month.minusMonths(1) }) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous month")
                }
                Text(month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + month.year,
                     style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                     modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton(onClick = { month = month.plusMonths(1) }) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next month")
                }
            }
            Row {
                listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                    Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                         color = scheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            val lead = month.atDay(1).dayOfWeek.value - 1
            val cells = lead + month.lengthOfMonth()
            for (row in 0 until (cells + 6) / 7) {
                Row {
                    for (col in 0 until 7) {
                        val dayN = row * 7 + col - lead + 1
                        Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp)) {
                            if (dayN in 1..month.lengthOfMonth()) {
                                val d = month.atDay(dayN)
                                val evs = events[d].orEmpty()
                                Column(
                                    Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                                        .background(if (d == picked) scheme.primaryContainer else Color.Transparent)
                                        .clickable { picked = d },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Text("$dayN", style = MaterialTheme.typography.bodyMedium,
                                         color = if (d == today) scheme.primary else scheme.onSurface,
                                         fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal)
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        evs.map { it.tone }.distinct().take(3).forEach {
                                            Box(Modifier.size(5.dp).clip(CircleShape).background(toneColors(it).ink))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(picked.format(DateTimeFormatter.ofPattern("EEEE d MMMM")), style = MaterialTheme.typography.titleMedium,
             fontWeight = FontWeight.SemiBold)
        val todays = events[picked].orEmpty().distinctBy { it.label }
        if (todays.isEmpty()) Hint("Nothing on this day.")
        if (visits.isNotEmpty() && visits.all { it.id.startsWith("sample-") }) {
            InfoNote("Sample visits are not added to the calendar. Your recorded visits are.")
        }
        todays.forEach { e ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(toneColors(e.tone).wash)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(toneColors(e.tone).ink))
                Text(e.label, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend("Visits & tests", Tone.BLUE); Legend("Vaccines", Tone.PLUM); Legend("Medicines", Tone.PEACH)
        }
    }
}

@Composable
private fun Legend(label: String, tone: Tone) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(toneColors(tone).ink))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
