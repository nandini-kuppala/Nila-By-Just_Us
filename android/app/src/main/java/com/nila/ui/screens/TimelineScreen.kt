package com.nila.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.SentimentDissatisfied
import androidx.compose.material.icons.outlined.TrendingDown
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nila.data.CareKind
import com.nila.data.CareRecord
import com.nila.data.EventRecord
import com.nila.data.Severity
import com.nila.ui.AppState
import com.nila.ui.Insights
import com.nila.ui.components.CardShape
import com.nila.ui.components.ChoicePills
import com.nila.ui.components.CryClipPlayer
import com.nila.ui.components.HedgeNote
import com.nila.ui.components.IconBadge
import com.nila.ui.components.InfoNote
import com.nila.ui.components.IntensityCurve
import com.nila.ui.components.NilaCard
import com.nila.ui.components.NilaIcons
import com.nila.ui.components.OutlinePillButton
import com.nila.ui.components.SeverityChip
import com.nila.ui.components.humanMinutes
import com.nila.ui.theme.Tone
import com.nila.ui.theme.chartPalette
import com.nila.ui.theme.severityColors
import com.nila.ui.theme.toneColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * What happened, one line per thing that happened.
 *
 * ### Why it is not one row per database row
 *
 * A single cry writes five or six of them: heard it, played a sound, judged the
 * sound, woke you, ended. Listed flat, that is a screen on which one difficult
 * evening pushes the previous two days off the bottom, and in which the cry
 * itself -- the thing a parent is scrolling to find -- is indistinguishable
 * from the app's own footnotes about it. The intermediate rows are still
 * written, still exported to the clinic report, and still shown here: they are
 * on the cry's own page, one tap away, where they read as the account of that
 * cry rather than as five separate events.
 *
 * ### Care, alongside the alerts
 *
 * Feeds, nappies and sleeps are on the same line as the cries, because the
 * question a parent brings to this screen is usually "what happened before she
 * started crying", and a timeline of alerts alone cannot answer it.
 *
 * ### The recording
 *
 * A cry that woke somebody, outlasted the ladder, or came back as pain keeps
 * its audio, and its page plays it. That is the only item on this screen a
 * parent can check for themselves, and it is the one thing a paediatrician can
 * be given that is evidence rather than a recollection.
 */
@Composable
fun TimelineScreen(state: AppState) {
    val events by state.events.collectAsState()
    val care by state.care.collectAsState()
    val entries = remember(events) { Timeline.collapse(events) }
    var range by rememberSaveable { mutableStateOf(0) }
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }

    val now = System.currentTimeMillis()
    val (from, to) = remember(range, now / 60_000L) { rangeBounds(range, now) }
    val items = remember(entries, care, from, to) { buildItems(entries, care, from, to, now) }
    val cries = items.filterIsInstance<Item.Event>().filter { it.entry.isCry }

    val open = openId?.let { id -> cries.firstOrNull { it.entry.id == id } }
    if (open != null) {
        BackHandler { openId = null }
        val index = cries.indexOf(open)
        CryDetail(
            entry = open.entry,
            onPrevious = cries.getOrNull(index + 1)?.let { { openId = it.entry.id } },
            onNext = cries.getOrNull(index - 1)?.let { { openId = it.entry.id } },
        )
        return
    }

    val byDay = items.groupBy { startOfDayMs(it.atMs) }.toSortedMap(compareByDescending { it })
    val kept = items.count { it is Item.Event && it.entry.clipPath != null }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item {
            ChoicePills(
                options = RANGES,
                selected = range,
                onSelect = { range = it },
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
        item { SummaryCard(events, care, from, to, now) }

        if (items.isEmpty()) {
            item {
                NilaCard(modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
                    Text("Nothing recorded in this range.",
                         style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        byDay.forEach { (dayStart, dayItems) ->
            item(key = "day-$dayStart") { DayHeader(dayStart, dayItems.size, now) }
            items(dayItems, key = { it.key }) { item ->
                TimelineRow(
                    item = item,
                    isLast = item == dayItems.last(),
                    onOpen = (item as? Item.Event)?.takeIf { it.entry.isCry }
                        ?.let { { openId = it.entry.id } },
                )
            }
        }

        if (kept > 0) {
            item {
                Box(Modifier.padding(top = 16.dp)) {
                    HedgeNote(
                        "Recordings are kept only for cries that woke you, ran to " +
                            "three minutes, or came back as pain. They live in this " +
                            "app's private storage, are never uploaded, and are " +
                            "deleted after ${com.nila.audio.EpisodeRecorder.KEEP_DAYS} " +
                            "days. Settings can turn recording off entirely."
                    )
                }
            }
        }
    }
}

private val RANGES = listOf("Today", "Yesterday", "7 days", "30 days")

/** The window a range pill stands for, as [from, to) in epoch millis. */
private fun rangeBounds(range: Int, now: Long): Pair<Long, Long> {
    val today = startOfDayMs(now)
    val day = 86_400_000L
    return when (range) {
        0 -> today to Long.MAX_VALUE
        1 -> (today - day) to today
        2 -> (today - 6 * day) to Long.MAX_VALUE
        else -> (today - 29 * day) to Long.MAX_VALUE
    }
}

// ----------------------------------------------------------------- items

/** One line on the timeline: an event from the log, or a piece of care. */
private sealed interface Item {
    val atMs: Long
    val key: String

    data class Event(val entry: Timeline.Entry) : Item {
        override val atMs get() = entry.atMs
        override val key get() = "e${entry.id}"
    }

    data class Care(
        override val atMs: Long,
        val title: String,
        val summary: String?,
        val icon: ImageVector,
        val tone: Tone,
        override val key: String,
    ) : Item
}

/**
 * Events and care for one window, newest first.
 *
 * Sleep is drawn from the sleep blocks rather than the raw start and stop taps,
 * for the same reason the dashboard does: a nap is one thing that lasted two
 * hours, not two taps.
 */
private fun buildItems(
    entries: List<Timeline.Entry>,
    care: List<CareRecord>,
    from: Long,
    to: Long,
    now: Long,
): List<Item> {
    val events = entries.filter { it.atMs in from until to }.map { Item.Event(it) }
    val careItems = care.filter { it.atMs in from until to }.mapNotNull { record ->
        when (record.kind) {
            CareKind.FEED.name -> Item.Care(
                record.atMs, "Feed",
                listOfNotNull(record.quantity, record.detail).joinToString(" · ").ifBlank { null },
                NilaIcons.Bottle, Tone.TEAL, "c${record.id}",
            )
            CareKind.DIAPER.name -> Item.Care(
                record.atMs, "Nappy change", record.detail,
                NilaIcons.Nappy, Tone.PEACH, "c${record.id}",
            )
            CareKind.MEDICINE.name -> Item.Care(
                record.atMs, "Medicine", record.detail,
                Icons.Outlined.Medication, Tone.PINK,
                "c${record.id}",
            )
            else -> null
        }
    }
    val sleeps = Insights.sleepBlocks(care, now)
        .filter { it.fromMs in from until to }
        .map { block ->
            Item.Care(
                block.fromMs, "Sleep",
                if (block.toMs >= now - 60_000L) "Asleep now - ${humanMinutes(block.minutes)} so far"
                else humanMinutes(block.minutes),
                Icons.Outlined.Bedtime, Tone.BLUE, "s${block.fromMs}",
            )
        }
    return (events + careItems + sleeps).sortedByDescending { it.atMs }
}

// --------------------------------------------------------------- summary

/** The window in four numbers: cries, sleep, feeds, nappies. */
@Composable
private fun SummaryCard(
    events: List<EventRecord>,
    care: List<CareRecord>,
    from: Long,
    to: Long,
    now: Long,
) {
    val stats = remember(events, care, from, to) { rangeStats(events, care, from, to, now) }
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(scheme.surfaceContainerLowest)
            .border(1.dp, scheme.outlineVariant, CardShape)
            .height(IntrinsicSize.Min)
            .padding(vertical = 14.dp),
    ) {
        val cells = listOf(
            Cell(Icons.Outlined.SentimentDissatisfied, Tone.PLUM, "${stats.cries}",
                 if (stats.cries == 1) "cry" else "cries", "${humanMinutes(stats.cryMinutes)} total"),
            Cell(Icons.Outlined.Bedtime, Tone.BLUE, humanMinutes(stats.sleepMinutes), "sleep",
                 "${stats.naps} " + if (stats.naps == 1) "stretch" else "stretches"),
            Cell(NilaIcons.Bottle, Tone.TEAL, "${stats.feeds}",
                 if (stats.feeds == 1) "feed" else "feeds", stats.lastFeed),
            Cell(NilaIcons.Nappy, Tone.PEACH, "${stats.nappies}",
                 if (stats.nappies == 1) "nappy" else "nappies", stats.lastNappy),
        )
        cells.forEachIndexed { i, cell ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(scheme.outlineVariant))
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconBadge(cell.icon, cell.tone, size = 38.dp, iconSize = 20.dp)
                Text(cell.value, style = MaterialTheme.typography.titleMedium
                         .copy(fontFeatureSettings = "tnum"),
                     fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                Text(cell.unit, style = MaterialTheme.typography.labelMedium,
                     color = scheme.onSurfaceVariant)
                Text(cell.caption, style = MaterialTheme.typography.labelSmall,
                     color = scheme.onSurfaceVariant, textAlign = TextAlign.Center,
                     maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private data class Cell(
    val icon: ImageVector, val tone: Tone, val value: String,
    val unit: String, val caption: String,
)

private data class RangeStats(
    val cries: Int, val cryMinutes: Int, val sleepMinutes: Int, val naps: Int,
    val feeds: Int, val nappies: Int, val lastFeed: String, val lastNappy: String,
)

private fun rangeStats(
    events: List<EventRecord>,
    care: List<CareRecord>,
    from: Long,
    to: Long,
    now: Long,
): RangeStats {
    val cryKinds = setOf("CRY_STARTED", "CRY_ONGOING", "CRY_ENDED")
    val cries = events
        .filter { it.kind in cryKinds && it.startedAtMs in from until to }
        .groupBy { it.episodeId ?: -it.id }
        .values
    val cryMinutes = cries.sumOf { rows -> rows.maxOf { it.durationSeconds } } / 60
    // Sleep is counted by overlap with the window, the same way the dashboard
    // counts it: a night that began yesterday and ended this morning is part of
    // this morning's total, or the two screens disagree about the same night.
    val sleeps = Insights.sleepBlocks(care, now)
        .map { maxOf(it.fromMs, from) to minOf(it.toMs, to) }
        .filter { (a, b) -> b > a }
    val feeds = care.filter { it.kind == CareKind.FEED.name && it.atMs in from until to }
    val nappies = care.filter { it.kind == CareKind.DIAPER.name && it.atMs in from until to }
    fun ago(record: CareRecord?): String {
        record ?: return "none"
        val m = (now - record.atMs) / 60_000L
        return when {
            m < 60 -> "last ${m}m ago"
            m < 24 * 60 -> "last ${m / 60}h ago"
            else -> "last ${m / (24 * 60)}d ago"
        }
    }
    return RangeStats(
        cries = cries.size,
        cryMinutes = cryMinutes,
        sleepMinutes = (sleeps.sumOf { (a, b) -> b - a } / 60_000L).toInt(),
        naps = sleeps.size,
        feeds = feeds.size,
        nappies = nappies.size,
        lastFeed = ago(feeds.maxByOrNull { it.atMs }),
        lastNappy = ago(nappies.maxByOrNull { it.atMs }),
    )
}

// ------------------------------------------------------------------ rows

@Composable
private fun DayHeader(dayStart: Long, count: Int, now: Long) {
    val scheme = MaterialTheme.colorScheme
    val today = startOfDayMs(now)
    val title = when (dayStart) {
        today -> "Today"
        today - 86_400_000L -> "Yesterday"
        else -> SimpleDateFormat("EEEE d MMM", Locale.getDefault()).format(Date(dayStart))
    }
    Row(
        modifier = Modifier
            .padding(top = 22.dp, bottom = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainerLow)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (dayStart == today) Icons.Outlined.WbSunny else Icons.Outlined.DateRange,
            contentDescription = null,
            tint = if (dayStart == today) toneColors(Tone.PEACH).ink else scheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
             modifier = Modifier.weight(1f))
        Text("$count " + if (count == 1) "event" else "events",
             style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
    }
}

/**
 * One line: the time, a dot on the spine, and the card.
 *
 * The spine is drawn per row so the last one of the day can end at its dot.
 */
@Composable
private fun TimelineRow(item: Item, isLast: Boolean, onOpen: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    val (icon, tone) = when (item) {
        is Item.Care -> item.icon to item.tone
        is Item.Event -> eventLook(item.entry)
    }
    val flagged = item is Item.Event && item.entry.severity.level >= Severity.URGENT.level
    val dot = if (flagged) severityColors(Severity.URGENT).accent else toneColors(tone).ink

    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Text(
            when (item) {
                is Item.Event -> item.entry.clockRange.substringBefore('-')
                is Item.Care -> clock(item.atMs)
            },
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            color = scheme.onSurfaceVariant,
            modifier = Modifier.width(46.dp).padding(top = 16.dp),
        )
        Column(
            modifier = Modifier.width(20.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.width(2.dp).height(20.dp).background(scheme.outlineVariant))
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            if (!isLast) {
                Box(Modifier.width(2.dp).weight(1f).background(scheme.outlineVariant))
            }
        }
        Box(Modifier.weight(1f).padding(start = 8.dp, bottom = 10.dp)) {
            when (item) {
                is Item.Event -> EventCard(item.entry, icon, tone, flagged, onOpen)
                is Item.Care -> NilaCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconBadge(icon, tone, size = 36.dp, iconSize = 19.dp)
                        Column {
                            Text(item.title, style = MaterialTheme.typography.titleSmall,
                                 fontWeight = FontWeight.Bold)
                            item.summary?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall,
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
private fun EventCard(
    entry: Timeline.Entry,
    icon: ImageVector,
    tone: Tone,
    flagged: Boolean,
    onOpen: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    val urgent = severityColors(entry.severity)
    // A light wash of the urgent colour and a red rule, rather than a solid
    // severity ground: the chip and the red title already say "urgent", and a
    // full maroon slab in the dark theme said it a third time, loudly.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(if (flagged) urgent.accent.copy(alpha = 0.08f)
                        else scheme.surfaceContainerLowest)
            .border(1.dp, if (flagged) urgent.accent.copy(alpha = 0.45f) else scheme.outlineVariant,
                    CardShape)
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (flagged) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(urgent.accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = urgent.accent,
                         modifier = Modifier.size(21.dp))
                }
            } else {
                IconBadge(icon, tone, size = 40.dp, iconSize = 21.dp)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (entry.count > 1) "${entry.title} ×${entry.count}" else entry.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (flagged) urgent.accent else scheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (flagged) SeverityChip(entry.severity)
                }
                if (entry.count > 1) {
                    Text("${entry.clockRange.replace("-", " – ")}, ${entry.count} times",
                         style = MaterialTheme.typography.labelMedium,
                         color = scheme.onSurfaceVariant)
                }
                // The folded summary opens with "N times.", which the title
                // already says; dropped here so the explanation fits. Urgent
                // entries are never cut short -- they have no page of their own.
                entry.summary?.removePrefix("${entry.count} times. ")?.let {
                    Text(it.replace(" - ", " – "), style = MaterialTheme.typography.bodySmall,
                         color = scheme.onSurfaceVariant,
                         maxLines = if (flagged) Int.MAX_VALUE else 3,
                         overflow = TextOverflow.Ellipsis)
                }
                if (entry.clipPath != null) {
                    // A mark on the entry itself, so a parent looking for "the one
                    // I can play to the doctor" can find it by scanning.
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Outlined.GraphicEq, contentDescription = null,
                             tint = scheme.primary, modifier = Modifier.size(16.dp))
                        Text("Recording kept", style = MaterialTheme.typography.labelMedium,
                             color = scheme.primary)
                    }
                }
            }
            if (onOpen != null) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Open",
                     tint = scheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
            }
        }
    }
}

/** The badge an event wears. Cries are plum; the camera's events are blue. */
private fun eventLook(entry: Timeline.Entry): Pair<ImageVector, Tone> = when {
    entry.isCry -> Icons.Outlined.SentimentDissatisfied to Tone.PLUM
    entry.kind == "OUT_OF_VIEW" || entry.kind == "FACE_NOT_VISIBLE" ->
        Icons.Outlined.VideocamOff to Tone.BLUE
    entry.kind == "CAMERA_MOVED" -> Icons.Outlined.PhoneAndroid to Tone.SLATE
    entry.kind == "GUARDIAN_FAULT" -> Icons.Outlined.ErrorOutline to Tone.PEACH
    entry.kind == "FACE_RETURNED" -> Icons.Outlined.Visibility to Tone.TEAL
    else -> Icons.Outlined.ChildCare to Tone.BLUE
}

// ----------------------------------------------------------------- detail

/**
 * One cry, on its own page: what it was, what Nila did, and the audio.
 *
 * The order is the order a parent's questions come in -- how bad was it, can I
 * hear it, what did the app do about it -- and the guess about the cause is
 * last, on its own card, with its uncertainty attached.
 */
@Composable
private fun CryDetail(
    entry: Timeline.Entry,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    val flagged = entry.severity.level >= Severity.URGENT.level
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        NilaCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(entry.clock, style = MaterialTheme.typography.titleMedium
                         .copy(fontFeatureSettings = "tnum"),
                     color = scheme.onSurfaceVariant)
                Text(entry.title, style = MaterialTheme.typography.titleLarge,
                     fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (flagged) SeverityChip(entry.severity)
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconBadge(Icons.Outlined.SentimentDissatisfied, Tone.PLUM, size = 52.dp)
                Column {
                    Text(
                        cryDuration(entry.seconds) + (entry.trend?.let { ", $it" } ?: ""),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    )
                    entry.outcome?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium,
                             color = scheme.onSurfaceVariant)
                    }
                }
            }
        }

        entry.clipPath?.let { path ->
            DetailSection("Audio") { CryClipPlayer(path = path, envelope = entry.envelope) }
        }

        if (entry.envelope.size > 3) {
            DetailSection("Crying level") {
                NilaCard(modifier = Modifier.fillMaxWidth()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(modifier = Modifier.height(110.dp),
                               verticalArrangement = Arrangement.SpaceBetween) {
                            Text("Loud", style = MaterialTheme.typography.labelSmall,
                                 color = scheme.onSurfaceVariant)
                            Text("Calm", style = MaterialTheme.typography.labelSmall,
                                 color = scheme.onSurfaceVariant)
                        }
                        IntensityCurve(points = entry.envelope, height = 110.dp,
                                       color = chartPalette().cry,
                                       modifier = Modifier.weight(1f))
                    }
                    Text("The loudness of the cry from start to finish, as the detector heard it.",
                         style = MaterialTheme.typography.labelMedium,
                         color = scheme.onSurfaceVariant)
                }
            }
        }

        if (entry.steps.isNotEmpty()) {
            DetailSection("What Nila did (${entry.steps.size} steps)") {
                // Times only when they run forwards. Each row stamps the time it
                // was written against, and some rows carry the episode's start
                // rather than their own moment -- printed as-is, "the crying
                // stopped" can sit a minute before "alerted you".
                val timed = entry.steps.all { it.atMs > 0 } &&
                    entry.steps.zipWithNext().all { (a, b) -> b.atMs >= a.atMs }
                NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 14.dp) {
                    entry.steps.forEach { step ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(stepIcon(step.kind), contentDescription = null,
                                 tint = toneColors(Tone.PLUM).ink, modifier = Modifier.size(20.dp))
                            if (timed) {
                                Text(clock(step.atMs),
                                     style = MaterialTheme.typography.labelLarge
                                         .copy(fontFeatureSettings = "tnum"),
                                     color = scheme.onSurfaceVariant,
                                     modifier = Modifier.width(44.dp))
                            }
                            Column {
                                Text(step.label, style = MaterialTheme.typography.bodyMedium)
                                step.detail?.let {
                                    Text(it, style = MaterialTheme.typography.labelMedium,
                                         color = scheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }

        entry.cause?.let { cause ->
            DetailSection("The sound's guess") {
                InfoNote(
                    title = "Probably $cause" +
                        if (entry.confidence > 0f) " - ${(entry.confidence * 100).toInt()}%" else "",
                    text = "A guess from the sound, not a diagnosis. The cause model scores " +
                        "below chance on babies it has never heard. If this pattern is " +
                        "unusual or keeps happening, it is worth mentioning to your doctor.",
                    icon = Icons.Outlined.Lightbulb,
                    tone = Tone.PEACH,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinePillButton("Earlier cry", { onPrevious?.invoke() }, Modifier.weight(1f),
                              icon = Icons.AutoMirrored.Outlined.ArrowBack,
                              enabled = onPrevious != null)
            OutlinePillButton("Later cry", { onNext?.invoke() }, Modifier.weight(1f),
                              icon = Icons.AutoMirrored.Outlined.ArrowForward,
                              enabled = onNext != null)
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        content()
    }
}

private fun stepIcon(kind: String?): ImageVector = when (kind) {
    "CRY_STARTED" -> Icons.Outlined.Hearing
    "CRY_ONGOING" -> Icons.Outlined.GraphicEq
    "SOOTHE_PLAYED" -> Icons.Outlined.VolumeUp
    "SOOTHE_WORKED" -> Icons.Outlined.CheckCircleOutline
    "SOOTHE_FAILED" -> Icons.Outlined.WarningAmber
    "ESCALATED_TO_PARENT" -> Icons.Outlined.NotificationsActive
    "CRY_ENDED" -> Icons.Outlined.TrendingDown
    else -> Icons.Outlined.GraphicEq
}

private fun cryDuration(seconds: Int): String =
    if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"

private fun clock(ms: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

private fun startOfDayMs(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

// ----------------------------------------------------------- the folding

/**
 * Turning event rows into the entries a person reads.
 *
 * Pure, and separate from the composables, so the folding can be tested on the
 * JVM -- a grouping bug here silently merges two cries into one or splits one
 * across two, and neither is visible in a screenshot.
 */
object Timeline {

    data class Step(val label: String, val detail: String?, val atMs: Long = 0L,
                    val kind: String? = null)

    data class Entry(
        val id: Long,
        val atMs: Long,
        val title: String,
        val summary: String?,
        val severity: Severity,
        val isCry: Boolean,
        val envelope: List<Int> = emptyList(),
        val clipPath: String? = null,
        val steps: List<Step> = emptyList(),
        /** How many identical events this row stands for. One, unless folded. */
        val count: Int = 1,
        /** The oldest of them, when [count] is more than one. */
        val fromMs: Long = atMs,
        /** The event kind this row came from, for its icon. Null for a cry. */
        val kind: String? = null,
        /** How long the cry ran, in seconds. Zero for anything else. */
        val seconds: Int = 0,
        /** The cause the sound suggested, and how sure the model was. */
        val cause: String? = null,
        val confidence: Float = 0f,
        /** How it ended, in one sentence: "Nila woke you." */
        val outcome: String? = null,
        val trend: String? = null,
    ) {
        val clock: String
            get() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(atMs))

        /** `19:12` normally, `19:12-20:09` for a folded run. */
        val clockRange: String
            get() = if (count <= 1) clock
            else "${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(fromMs))}-$clock"
    }

    /**
     * How close two of the same event have to be to fold into one row.
     *
     * The camera lane is the reason this exists. A baby under a blanket
     * produces "Out of view of the camera" every time the face detector loses
     * and regains her, which on a restless evening is a dozen identical
     * paragraphs -- and they push the cries, which are the thing somebody
     * opened this screen to find, off the bottom of it. Folded, the same
     * dozen is one row that says it happened twelve times and over what span,
     * which is both shorter and more informative than the twelve were.
     *
     * An hour, because these are a state flapping rather than separate
     * incidents; past an hour apart they are separate incidents.
     */
    private const val FOLD_WINDOW_MS = 60 * 60 * 1000L

    private val CRY_KINDS = setOf(
        "CRY_STARTED", "CRY_ONGOING", "CRY_ENDED",
        "SOOTHE_PLAYED", "SOOTHE_WORKED", "SOOTHE_FAILED", "ESCALATED_TO_PARENT",
    )

    /**
     * Fold cry rows into one entry each, newest first.
     *
     * Rows are grouped on `episodeId`. A cry row with no episode id is its own
     * entry rather than being guessed into a neighbouring group: rows written
     * before this column existed, and rows from a crash mid-episode, are real
     * events and showing them alone is honest, where attaching them to the
     * nearest cry is a fabricated association.
     */
    fun collapse(events: List<EventRecord>): List<Entry> {
        val cryRows = events.filter { it.kind in CRY_KINDS && it.episodeId != null }
        val grouped = cryRows.groupBy { it.episodeId!! }

        val episodes = grouped.map { (id, rows) -> episodeEntry(id, rows) }
        val loose = fold(
            events
                .filter { it.kind !in CRY_KINDS || it.episodeId == null }
                .sortedByDescending { it.startedAtMs }
        )

        return (episodes + loose).sortedByDescending { it.atMs }
    }

    private fun episodeEntry(id: Long, rows: List<EventRecord>): Entry {
        val ordered = rows.sortedBy { it.id }
        val opened = ordered.firstOrNull { it.kind == "CRY_STARTED" } ?: ordered.first()
        val closed = ordered.lastOrNull { it.kind == "CRY_ENDED" }
        val escalated = ordered.firstOrNull { it.kind == "ESCALATED_TO_PARENT" }
        val settled = ordered.lastOrNull { it.kind == "SOOTHE_WORKED" }
        val failed = ordered.lastOrNull { it.kind == "SOOTHE_FAILED" }
        val played = ordered.filter { it.kind == "SOOTHE_PLAYED" }

        val seconds = closed?.durationSeconds
            ?: ordered.maxOf { it.durationSeconds }
        val cause = (closed ?: escalated ?: opened).hypothesisLabel
        val confidence = (closed ?: escalated ?: opened).hypothesisConfidence

        val outcome = when {
            escalated != null -> "Nila woke you."
            settled != null -> "${settled.note ?: "A sound"} settled her."
            failed != null -> "${failed.note ?: "A sound"} did not help."
            played.isNotEmpty() -> "Nila played ${played.size} " +
                (if (played.size == 1) "sound." else "sounds.")
            else -> "Settled on her own."
        }

        val summary = buildString {
            append(duration(seconds))
            closed?.trend?.let { append(", ").append(it.lowercase()) }
            if (cause != null) {
                append(". Probably ").append(cause.replace('_', ' '))
                if (confidence > 0f) append(" - ${(confidence * 100).toInt()}%")
                append(", a guess from the sound")
            }
            append(". ")
            append(outcome)
        }

        return Entry(
            // The opening row's id, so the key survives the episode gaining
            // more rows while the screen is open.
            id = id,
            atMs = opened.startedAtMs,
            title = if (escalated != null) "Cry – you were woken" else "Cry",
            summary = summary,
            severity = ordered.maxByOrNull { it.severityLevel }?.severity ?: Severity.NOTE,
            isCry = true,
            // The fullest envelope any row of this episode carries, not the
            // one on whichever row happens to anchor it.
            //
            // Every row stores the envelope as it stood when that row was
            // written, so the opening row holds a single point -- the instant
            // the detector crossed its threshold. Reading from there gave the
            // player a flat line and the card no curve at all, and it showed up
            // only on cries closed at the three-minute cap, because those write
            // no CRY_ENDED row for the old code to prefer. The longest one is
            // the latest one, and it is the shape of the whole cry.
            envelope = ordered
                .map { it.envelopePoints() }
                .maxByOrNull { it.size }
                .orEmpty(),
            // Written on whichever row the service stamped; searched rather
            // than assumed so a future change of anchor row cannot lose it.
            clipPath = ordered.firstNotNullOfOrNull { it.clipPath },
            steps = ordered.map { Step(readable(it), it.note, it.startedAtMs, it.kind) },
            seconds = seconds,
            cause = cause?.replace('_', ' '),
            confidence = confidence,
            outcome = outcome,
            trend = closed?.trend?.lowercase(),
        )
    }

    /**
     * Fold neighbouring events of the same kind into one row each.
     *
     * Runs only over consecutive rows, so a different event between two of the
     * same kind splits the run -- "out of view, out of view" is one row, but
     * "out of view, rolled over, out of view" is three, because in the second
     * case something happened in between and hiding that would be a lie about
     * the night.
     *
     * Severity is the strongest in the run, never the newest: a run that
     * contains one CRITICAL is a critical row.
     */
    private fun fold(newestFirst: List<EventRecord>): List<Entry> {
        val out = mutableListOf<Entry>()
        var run = mutableListOf<EventRecord>()

        fun flush() {
            if (run.isEmpty()) return
            val newest = run.first()
            val oldest = run.last()
            val worst = run.maxByOrNull { it.severityLevel } ?: newest
            out += plainEntry(newest).copy(
                severity = worst.severity,
                count = run.size,
                fromMs = oldest.startedAtMs,
                summary = if (run.size == 1) plainEntry(newest).summary
                          else buildString {
                              append(run.size).append(" times. ")
                              // The body once, from the strongest of them --
                              // the CRITICAL wording is the one worth keeping.
                              plainEntry(worst).summary?.let(::append)
                          }.trim(),
            )
            run = mutableListOf()
        }

        newestFirst.forEach { event ->
            val last = run.lastOrNull()
            val joins = last != null &&
                last.kind == event.kind &&
                last.startedAtMs - event.startedAtMs <= FOLD_WINDOW_MS
            if (!joins) flush()
            run.add(event)
        }
        flush()
        return out
    }

    private fun plainEntry(event: EventRecord): Entry = Entry(
        id = -event.id,
        atMs = event.startedAtMs,
        title = readable(event),
        summary = buildString {
            if (event.durationSeconds > 0) append(duration(event.durationSeconds))
            event.trend?.let {
                if (isNotEmpty()) append("  ")
                append(it.lowercase())
            }
            event.note?.let {
                if (isNotEmpty()) append("  ")
                append(it)
            }
        }.ifBlank { null },
        severity = event.severity,
        isCry = false,
        kind = event.kind,
        seconds = event.durationSeconds,
    )

    private fun duration(seconds: Int): String =
        if (seconds < 60) "${seconds}s"
        else "${seconds / 60}m ${seconds % 60}s"

    fun readable(event: EventRecord): String = when (event.kind) {
        "CRY_STARTED" -> "Heard crying"
        "CRY_ONGOING" -> "Still crying"
        "CRY_ENDED" -> "The crying stopped"
        "SOOTHE_PLAYED" -> "Played a sound"
        "SOOTHE_WORKED" -> "That settled her"
        "SOOTHE_FAILED" -> "That didn't help"
        "ESCALATED_TO_PARENT" -> "Alerted you"
        "FACE_NOT_VISIBLE" -> "Couldn't see the face"
        "FACE_RETURNED" -> "Face visible again"
        "STILLNESS" -> "Unusually still"
        "ROLLED_TO_FRONT" -> "Rolled onto her front"
        "ROLLED_TO_SIDE" -> "Rolled onto her side"
        "CRAWLING" -> "Crawling"
        "SITTING_UP" -> "Sat up"
        "STANDING_UP" -> "Stood up"
        "LEFT_SAFE_ZONE" -> "Left the safe zone"
        "MOVED_AWAY" -> "Moved away from the cot"
        "OUT_OF_VIEW" -> "Out of view of the camera"
        "CAMERA_MOVED" -> "The phone was moved"
        "GUARDIAN_FAULT" -> "Monitoring problem"
        else -> event.kind.lowercase().replace('_', ' ')
    }
}
