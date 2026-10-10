package com.nila.visit

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Vaccines
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nila.visit.extract.Medicine
import com.nila.visit.extract.Note
import com.nila.visit.remind.Planner
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// Nila's own palette, so the lab already looks like the place it will live.
private val Teal = Color(0xFF0F6B60)
private val TealWash = Color(0xFFE3F2EE)
private val Peach = Color(0xFFFCEBDD)
private val PeachInk = Color(0xFF9A4A16)
private val Plum = Color(0xFFF1E6F4)
private val PlumInk = Color(0xFF6B3C78)
private val Blue = Color(0xFFE6EEF9)
private val BlueInk = Color(0xFF2D5B9A)
private val Red = Color(0xFFFBE4E2)
private val RedInk = Color(0xFFB3261E)
private val Ground = Color(0xFFF7FAF9)
private val Ink = Color(0xFF17201E)
private val Muted = Color(0xFF5B6865)

class MainActivity : ComponentActivity() {

    private val vm: VisitViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app has to create its own storage folders: on Android 16 a folder
        // made by adb under Android/data is not readable by the app it is for.
        listOf("models/whisper-small", "models/whisper-turbo", "testdata/audio", "reports", "visits")
            .forEach { java.io.File(getExternalFilesDir(null), it).mkdirs() }
        // Processing keeps the screen on: vivo's power manager freezes an app
        // in the background within seconds, mid-generation.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) vm.startRecording()
        }
        val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        if (android.os.Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Teal, background = Ground, surface = Color.White)) {
                Surface(color = Ground, modifier = Modifier.fillMaxSize()) {
                    App(vm) { askMic.launch(Manifest.permission.RECORD_AUDIO) }
                }
            }
        }
    }
}

@Composable
private fun App(vm: VisitViewModel, record: () -> Unit) {
    val phase by vm.phase.collectAsState()
    val opened by vm.opened.collectAsState()
    val visits by vm.visits.collectAsState()
    var calendar by remember { mutableStateOf(false) }
    val current = visits.firstOrNull { it.id == opened }
    when {
        phase is VisitViewModel.Phase.Recording -> RecordingScreen(phase as VisitViewModel.Phase.Recording, vm::stopRecording)
        phase is VisitViewModel.Phase.Working -> {
            val muted by vm.replayMuted.collectAsState()
            WorkingScreen(phase as VisitViewModel.Phase.Working, muted, vm::setReplayMuted)
        }
        current != null -> { BackHandler { vm.opened.value = null }; VisitScreen(vm, current) { vm.opened.value = null } }
        calendar -> { BackHandler { calendar = false }; CalendarScreen(visits) { calendar = false } }
        else -> HomeScreen(vm, record, { calendar = true }, phase as? VisitViewModel.Phase.Failed)
    }
}

// ------------------------------------------------------------------ home

@Composable
private fun HomeScreen(vm: VisitViewModel, record: () -> Unit, openCalendar: () -> Unit, failed: VisitViewModel.Phase.Failed?) {
    val visits by vm.visits.collectAsState()
    val lang by vm.language.collectAsState()
    val model by vm.model.collectAsState()
    var allSamples by remember { mutableStateOf(false) }
    var allVisits by remember { mutableStateOf(false) }
    // The language picked is the one the doctor will speak, so the samples
    // shown are the ones in that language; Auto shows them all.
    val samples = vm.samples().filter { lang == "auto" || it.optString("language") == lang }
    val pastVisits = visits.filterNot { it.id.startsWith("sample-") }
    val triedSamples = visits.filter { it.id.startsWith("sample-") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Doctor visit", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("Everything stays on this phone", color = Muted, fontSize = 14.sp)
            }
            IconButton(onClick = openCalendar) { Icon(Icons.Outlined.CalendarMonth, "Calendar", tint = Teal) }
        }

        Card(TealWash) {
            Text("Keep the phone near the doctor and tap record. Afterwards Nila writes down the medicines, " +
                "dates and advice, each with the doctor's own words.", color = Ink, fontSize = 15.sp, lineHeight = 21.sp)
            Button(onClick = record, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(27.dp)) {
                Icon(Icons.Outlined.Mic, null); Spacer(Modifier.width(8.dp)); Text("Record the visit", fontSize = 17.sp)
            }
        }

        failed?.let {
            Card(Red) {
                Text(it.message, color = RedInk)
                TextButton(onClick = vm::dismissError) { Text("OK") }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("The doctor will speak")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                listOf("auto" to "Any language", "en" to "English", "hi" to "हिन्दी", "ta" to "தமிழ்", "te" to "తెలుగు").forEach { (code, label) ->
                    FilterChip(selected = lang == code, onClick = { vm.language.value = code }, label = { Text(label) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Text("Listening model", color = Muted, fontSize = 13.sp)
                listOf("turbo" to "Accurate", "small" to "Fast").forEach { (code, label) ->
                    FilterChip(selected = model == code, onClick = { vm.model.value = code }, label = { Text(label, fontSize = 13.sp) })
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Try a sample visit", samples.size, allSamples) { allSamples = !allSamples }
            Text("A recorded doctor, read from a file. Nothing plays until the notes are written.", color = Muted, fontSize = 13.sp)
            if (samples.isEmpty()) Empty("No samples in this language yet.")
            (if (allSamples) samples else samples.take(3)).forEach { s ->
                ListCard(
                    tint = Plum, icon = Icons.Outlined.Science, iconTint = PlumInk,
                    title = VisitViewModel.sampleTitle(s),
                    subtitle = s.getString("transcript").take(80) + "…",
                    tag = VisitViewModel.languageName(s.optString("language")),
                ) { vm.runSample(s) }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val all = pastVisits + triedSamples
            SectionHeader("Past visits", all.size, allVisits) { allVisits = !allVisits }
            if (all.isEmpty()) Empty("Your recorded visits will appear here.")
            (if (allVisits) all else all.take(3)).forEach { v ->
                val dates = v.notes.appointments.size + v.notes.tests.size + v.notes.vaccines.count { !it.givenToday }
                ListCard(
                    tint = Color.White, icon = if (v.remindersSet) Icons.Outlined.Alarm else Icons.Outlined.Medication,
                    iconTint = Teal,
                    title = v.title,
                    subtitle = listOf(
                        v.date.format(DateTimeFormatter.ofPattern("d MMM")),
                        "${v.notes.medicines.size} medicine" + if (v.notes.medicines.size == 1) "" else "s",
                        "$dates date" + if (dates == 1) "" else "s",
                    ).joinToString(" · "),
                    tag = if (v.remindersSet) "Reminders on" else null,
                    border = true,
                ) { vm.opened.value = v.id }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int, expanded: Boolean, toggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Ink, modifier = Modifier.weight(1f))
        if (count > 3) TextButton(onClick = toggle) { Text(if (expanded) "Show less" else "View all $count") }
    }
}

@Composable
private fun Label(t: String) = Text(t, fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 15.sp)

@Composable
private fun ListCard(
    tint: Color, icon: ImageVector, iconTint: Color, title: String, subtitle: String, tag: String?,
    border: Boolean = false, onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(tint)
            .then(if (border) Modifier.border(1.dp, Color(0xFFE2E8E6), RoundedCornerShape(16.dp)) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(subtitle, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        tag?.let { Spacer(Modifier.width(8.dp)); Text(it, color = iconTint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
    }
}

// ----------------------------------------------------- recording & working

@Composable
private fun RecordingScreen(p: VisitViewModel.Phase.Recording, stop: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(160.dp).clip(CircleShape).background(Red), contentAlignment = Alignment.Center) {
            Box(Modifier.size((70 + 80 * p.level).dp).clip(CircleShape).background(RedInk.copy(alpha = 0.25f)))
            Icon(Icons.Outlined.Mic, null, tint = RedInk, modifier = Modifier.size(56.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("%d:%02d".format(p.seconds / 60, p.seconds % 60), fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("Listening to the doctor. Keep the phone near them.", color = Muted)
        Spacer(Modifier.height(32.dp))
        Button(onClick = stop, colors = ButtonDefaults.buttonColors(containerColor = RedInk),
            modifier = Modifier.height(56.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
            Icon(Icons.Outlined.Stop, null); Spacer(Modifier.width(8.dp)); Text("Visit finished", fontSize = 17.sp)
        }
    }
}

@Composable
private fun WorkingScreen(p: VisitViewModel.Phase.Working, muted: Boolean, setMuted: (Boolean) -> Unit) {
    // While the notes are written: the visit plays back and its words appear
    // in step with it, so the wait is spent hearing the doctor again.
    var position by remember { mutableStateOf(0f) }
    val player = remember(p.audio) {
        p.audio?.takeIf { it.exists() }?.let { f ->
            runCatching { android.media.MediaPlayer().apply { setDataSource(f.absolutePath); prepare() } }.getOrNull()
        }
    }
    androidx.compose.runtime.DisposableEffect(player) {
        player?.start()
        onDispose { runCatching { player?.stop(); player?.release() } }
    }
    androidx.compose.runtime.LaunchedEffect(muted, player) { player?.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f) }
    androidx.compose.runtime.LaunchedEffect(player, p.transcript) {
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

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(p.step, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.detail, color = Muted, modifier = Modifier.weight(1f))
            if (player != null) {
                TextButton(onClick = { setMuted(!muted) }) { Text(if (muted) "Sound off · turn on" else "Sound on · mute") }
            }
        }
        if (p.progress != null) LinearProgressIndicator(progress = { p.progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))

        if (p.transcript.isNotBlank()) {
            Column(
                Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)).background(Color.White)
                    .border(1.dp, Color(0xFFE2E8E6), RoundedCornerShape(18.dp)).padding(16.dp)
                    .verticalScroll(rememberScrollState(), reverseScrolling = true),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("What the doctor said", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                val words = p.transcript.split(" ")
                val shown = (words.size * position).toInt().coerceIn(1, words.size)
                Text(words.take(shown).joinToString(" ") + if (shown < words.size) " ▍" else "", color = Ink, fontSize = 16.sp, lineHeight = 23.sp)
            }
            p.partial?.let { FoundSoFar(it, p.passesDone) }
        } else {
            Spacer(Modifier.weight(1f))
            Text("First the speech is turned into text, then the notes are written — one model on the NPU at a time.",
                color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun FoundSoFar(n: com.nila.visit.extract.VisitNotes, passes: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        val dates = n.appointments.size + n.tests.size + n.vaccines.count { !it.givenToday }
        Pill("${n.medicines.size} medicine" + if (n.medicines.size == 1) "" else "s", Peach, PeachInk, passes >= 1)
        Pill("$dates date" + if (dates == 1) "" else "s", Blue, BlueInk, passes >= 2)
        Pill("${n.watchFor.size + n.dos.size + n.donts.size} advice", TealWash, Teal, passes >= 3)
    }
}

@Composable
private fun Pill(text: String, bg: Color, ink: Color, done: Boolean) {
    Row(Modifier.clip(CircleShape).background(if (done) bg else Color(0xFFEFF2F1)).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (done) { Icon(Icons.Outlined.CheckCircle, null, tint = ink, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
        Text(if (done) text else text.substringAfter(' ').replaceFirstChar { it.uppercase() } + "…",
            color = if (done) ink else Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// ---------------------------------------------------------------- a visit

@Composable
private fun VisitScreen(vm: VisitViewModel, v: VisitStore.Saved, back: () -> Unit) {
    var showTranscript by remember { mutableStateOf(false) }
    var setCount by remember { mutableStateOf<Int?>(null) }
    val n = v.notes
    val day = DateTimeFormatter.ofPattern("EEE d MMM")
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(v.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("${v.date.format(day)}${v.language?.let { " · heard in $it" } ?: ""}", color = Muted, fontSize = 13.sp)
            }
        }
        if (n.summary.isNotBlank()) Card(TealWash) { Text(n.summary, color = Ink, fontSize = 16.sp) }

        Section("Medicines", Icons.Outlined.Medication, PeachInk) {
            if (n.medicines.isEmpty()) Empty("No medicines were mentioned.")
            n.medicines.forEach { MedicineRow(it) }
            val plan = remember(v) { Planner.plan(n, v.date) }
            if (plan.isNotEmpty()) {
                if (v.remindersSet) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CheckCircle, null, tint = Teal); Spacer(Modifier.width(6.dp))
                        Text("Reminders are set (${setCount ?: plan.size}). Silent in this lab build.", color = Teal, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.clearReminders(v) }) { Text("Remove") }
                    }
                } else {
                    Button(onClick = { setCount = vm.setReminders(v) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                        Icon(Icons.Outlined.Alarm, null); Spacer(Modifier.width(8.dp)); Text("Set ${plan.size} reminders")
                    }
                }
                Text("Amounts are written as the doctor said them. Check them against the prescription.",
                    color = Muted, fontSize = 12.sp)
            }
        }

        Section("Dates", Icons.Outlined.CalendarMonth, BlueInk) {
            val dated = n.appointments.map { Triple(it.what, it.date, it.quote) } +
                n.vaccines.filter { !it.givenToday }.map { Triple("Vaccine: ${it.name}", it.date, it.quote) } +
                n.tests.map { Triple("Test: ${it.what}", it.date, it.quote) }
            if (dated.isEmpty()) Empty("No dates were given.")
            dated.forEach { (what, date, quote) ->
                Item(
                    title = what,
                    trailing = date?.format(day) ?: "Set a date",
                    trailingColor = if (date == null) RedInk else BlueInk,
                    quote = quote, tint = Blue,
                )
            }
            n.vaccines.filter { it.givenToday }.forEach {
                Item("Given today: ${it.name}", null, BlueInk, it.quote, Blue, icon = Icons.Outlined.Vaccines)
            }
        }

        NotesSection("Watch for", Icons.Outlined.WarningAmber, RedInk, Red, n.watchFor, "Nothing to watch for was mentioned.")
        NotesSection("Do", Icons.Outlined.CheckCircle, Teal, TealWash, n.dos, "Nothing.")
        NotesSection("Don't", Icons.Outlined.Close, PlumInk, Plum, n.donts, "Nothing.")

        OutlinedButton(onClick = { showTranscript = !showTranscript }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Visibility, null); Spacer(Modifier.width(8.dp))
            Text(if (showTranscript) "Hide what was heard" else "Show what was heard")
        }
        AnimatedVisibility(showTranscript) {
            Card(Color.White) {
                Text(v.transcript, color = Ink, fontSize = 14.sp)
                if (n.dropped.isNotEmpty()) {
                    Text("Left out (not in what the doctor said):", color = RedInk, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    n.dropped.forEach { Text("• $it", color = Muted, fontSize = 12.sp) }
                }
                val stages = (0 until v.stages.length()).map { v.stages.getJSONObject(it) }
                Text(stages.joinToString("  ·  ") { "${it.getString("name")} ${it.getLong("ms")} ms" }, color = Muted, fontSize = 11.sp)
            }
        }
        Row {
            if (vm.hasRecording(v)) TextButton(onClick = { vm.reprocess(v) }) { Text("Process again") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.delete(v) }) { Text("Delete this visit", color = RedInk) }
        }
    }
}

@Composable
private fun MedicineRow(m: Medicine) {
    // Compact: name and amount, one line of when, chips for times and course.
    // The doctor's words fold away under a tap; open, they used to make each
    // medicine a screen tall.
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Peach)
            .clickable { open = !open }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.name.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Bold, color = Ink,
                modifier = Modifier.weight(1f), fontSize = 16.sp, maxLines = 1)
            if (m.amount.isNotBlank()) Text(m.amount, color = PeachInk, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        if (m.instructions.isNotBlank()) Text(m.instructions.replaceFirstChar { it.uppercase() }, color = Ink, fontSize = 14.sp, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            if (m.asNeeded) Chip("Only when needed", PeachInk)
            else m.reminderTimes().forEach { Chip(it.toString(), PeachInk) }
            if (!m.asNeeded) Chip(m.durationDays?.let { "$it days" } ?: "Ongoing", PeachInk)
        }
        if (m.quote.isNotBlank()) {
            if (open) Quote(m.quote)
            else Text("Doctor's words ›", color = PeachInk.copy(alpha = 0.8f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun Chip(t: String, ink: Color) {
    Text(t, color = ink, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.75f)).padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable
private fun NotesSection(title: String, icon: ImageVector, ink: Color, tint: Color, notes: List<Note>, empty: String) {
    Section(title, icon, ink) {
        if (notes.isEmpty()) Empty(empty)
        notes.forEach { Item(it.text, if (it.urgent) "Come at once" else null, RedInk, it.quote, tint) }
    }
}

@Composable
private fun Item(title: String, trailing: String?, trailingColor: Color, quote: String, tint: Color, icon: ImageVector? = null) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tint)
        .clickable(enabled = quote.isNotBlank()) { open = !open }.padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon?.let { Icon(it, null, tint = trailingColor, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
            Text(title.replaceFirstChar { it.uppercase() }, color = Ink, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            trailing?.let { Spacer(Modifier.width(8.dp)); Text(it, color = trailingColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
        }
        if (quote.isNotBlank() && open) Quote(quote)
    }
}

@Composable
private fun Quote(q: String) {
    Text("“${q.trim().trim('"')}”", color = Muted, fontSize = 13.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
}

@Composable
private fun Section(title: String, icon: ImageVector, ink: Color, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = ink); Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
        }
        content()
    }
}

@Composable private fun Empty(t: String) = Text(t, color = Muted, fontSize = 14.sp)

@Composable
private fun Card(color: Color, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(color).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

// -------------------------------------------------------------- calendar

@Composable
private fun CalendarScreen(visits: List<VisitStore.Saved>, back: () -> Unit) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    var picked by remember { mutableStateOf(LocalDate.now()) }
    // Every dated thing from every visit, and each medicine course as a span.
    data class Ev(val date: LocalDate, val label: String, val color: Color)
    val events = remember(visits) {
        visits.flatMap { v ->
            v.notes.appointments.mapNotNull { a -> a.date?.let { Ev(it, a.what, BlueInk) } } +
                v.notes.vaccines.mapNotNull { x -> x.date?.let { Ev(it, "Vaccine: ${x.name}", PlumInk) } } +
                v.notes.tests.mapNotNull { t -> t.date?.let { Ev(it, "Test: ${t.what}", BlueInk) } } +
                v.notes.medicines.filter { !it.asNeeded }.flatMap { m ->
                    (0 until (m.durationDays ?: 7)).map { Ev(v.date.plusDays(it.toLong()), m.name, PeachInk) }
                }
        }.groupBy { it.date }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text("Calendar", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
            TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹") }
            Text(month.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + month.year, color = Ink)
            TextButton(onClick = { month = month.plusMonths(1) }) { Text("›") }
        }
        Row { listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, Modifier.weight(1f), color = Muted, fontSize = 12.sp) } }
        val first = month.atDay(1); val lead = first.dayOfWeek.value - 1
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
                                    .background(if (d == picked) TealWash else Color.White).clickable { picked = d },
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                            ) {
                                Text("$dayN", color = if (d == LocalDate.now()) Teal else Ink,
                                    fontWeight = if (d == LocalDate.now()) FontWeight.Bold else FontWeight.Normal)
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    evs.map { it.color }.distinct().take(3).forEach {
                                        Box(Modifier.size(5.dp).clip(CircleShape).background(it))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(picked.format(DateTimeFormatter.ofPattern("EEEE d MMMM")), fontWeight = FontWeight.SemiBold, color = Ink)
        val todays = events[picked].orEmpty().distinctBy { it.label }
        if (todays.isEmpty()) Empty("Nothing on this day.")
        todays.forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(e.color)); Spacer(Modifier.width(8.dp))
                Text(e.label, color = Ink)
            }
        }
    }
}
