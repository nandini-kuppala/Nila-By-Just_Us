package com.nila.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.SentimentDissatisfied
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nila.data.CareKind
import com.nila.data.Severity
import com.nila.monitor.EpisodePipeline
import com.nila.monitor.MonitorState
import com.nila.ui.AppState
import com.nila.ui.Insights
import com.nila.ui.components.Bar
import com.nila.ui.components.ColumnChart
import com.nila.ui.components.AttemptsCard
import com.nila.ui.components.BestGuessRow
import com.nila.ui.components.CryClipPlayer
import com.nila.ui.components.CryJourney
import com.nila.ui.components.EpisodeCurve
import com.nila.ui.components.NextUpRow
import com.nila.ui.components.StepsToggle
import com.nila.ui.components.clockOf
import com.nila.ui.components.DonutChart
import com.nila.ui.components.HedgeNote
import com.nila.ui.components.IconBadge
import com.nila.ui.components.InfoNote
import com.nila.ui.components.InputLevelMeter
import com.nila.ui.components.IntensityCurve
import com.nila.ui.components.LineChart
import com.nila.ui.components.MiniBars
import com.nila.ui.components.NilaCard
import com.nila.ui.components.NilaIcons
import com.nila.ui.components.OutlinePillButton
import com.nila.ui.components.PillButton
import com.nila.ui.components.PipelineChart
import com.nila.ui.components.Point
import com.nila.ui.components.PulseDot
import com.nila.ui.components.ReasonVerdict
import com.nila.ui.components.SectionTitle
import com.nila.ui.components.SeverityChip
import com.nila.ui.components.SleepingBabyArt
import com.nila.ui.components.TakeawayStrip
import com.nila.ui.components.TonePill
import com.nila.ui.components.VoiceNoteBar
import com.nila.ui.components.WarningBox
import com.nila.ui.components.Wedge
import com.nila.ui.components.humanMinutes
import com.nila.ui.components.percent
import com.nila.ui.theme.ChartPalette
import com.nila.ui.theme.Tone
import com.nila.ui.theme.chartPalette
import com.nila.ui.theme.severityColors
import com.nila.ui.theme.toneColors
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The night screen, and the morning-after screen.
 *
 * It does two jobs and it does them in that order, which is the whole design.
 *
 * **Above the fold it is a monitor**, written to be read at 3am by someone
 * holding a baby: numbers and controls, no prose. During a cry it runs answer,
 * then instruction, then mechanism -- how long and how loud, then the cause,
 * then what to try, and only then the ladder showing how the app got there. A
 * parent who reads nothing but the first card still has the useful half.
 *
 * **Below it is a dashboard**, and that is a different reader. Nobody studies a
 * pie chart while a baby is screaming; they look at it at ten the next morning
 * with a coffee, asking a question the monitor cannot answer -- *is this
 * getting worse, and is there a pattern in it.* Seven days of crying against
 * the three-hour line, a week of sleep, and the share of cries the model would
 * not even name: those are answers, and the last of them is an answer about the
 * app's own limits.
 *
 * Nothing below the fold interprets. Every chart has its own figures written
 * out beside it in words, because a wedge is not a number and this reader is
 * short of sleep.
 */
@Composable
fun HomeScreen(
    state: AppState,
    onOpenInsights: () -> Unit = {},
    onOpenTimeline: () -> Unit = {},
) {
    val monitor by state.monitor.collectAsState()
    val care by state.care.collectAsState()
    val insights by state.insights.collectAsState()
    val asleepSince by state.asleepSinceMs.collectAsState()
    val baby by state.baby.collectAsState()
    val name = baby?.name?.takeIf { it.isNotBlank() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        StatusCard(
            monitor = monitor,
            babyName = name,
            onStart = state::startMonitoring,
            onStop = state::stopMonitoring,
            onCloseDemo = state::closeDemo,
        )

        monitor.fault?.let {
            WarningBox("Monitoring stopped", it, Modifier.fillMaxWidth())
        }

        if (monitor.running && !monitor.simulated) {
            FullScreenAlertCheck(state)
        }

        val evidence = monitor.currentEvidence
        if (evidence != null) {
            LiveEpisode(evidence, monitor, onStop = state::stopMonitoring)
        } else {
            monitor.advice?.let { advice ->
                ReasonVerdict(
                    headline = advice.headline,
                    steps = advice.steps,
                    caution = advice.caution,
                    source = advice.source,
                )
            }
        }

        monitor.lastEpisode?.let { episode ->
            EpisodeSummaryCard(episode, onDismiss = state::dismissEpisode)
        }

        QuickLog(
            care = care,
            asleepSinceMs = asleepSince,
            onLog = state::logCare,
            onToggleSleep = state::toggleSleep,
            onUndo = state::deleteCare,
        )

        TodayTiles(insights, care, name, onSeeAll = onOpenInsights)

        CryTrendCard(insights, onOpenInsights)

        TryItRow(state, monitor)

        WeekBanner(insights, onOpenTimeline)

        SleepCard(insights)

        CauseCard(insights)
    }
}

// --------------------------------------------------------------- status

/**
 * The one card that has to be readable from across a dark room.
 *
 * It changes shape with the phase rather than only changing a word: idle is an
 * invitation with one button, listening shows uptime and the input meter, and
 * an episode shows the elapsed time as the largest thing on the screen.
 */
@Composable
private fun StatusCard(
    monitor: MonitorState,
    babyName: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCloseDemo: () -> Unit,
) {
    val idle = !monitor.running && !monitor.demoComplete && monitor.currentEvidence == null
    if (idle) {
        IdleHero(babyName, onStart)
        return
    }

    val phase = monitor.phase
    val urgent = phase is MonitorState.Phase.Escalated || phase is MonitorState.Phase.Safety
    val evidence = monitor.currentEvidence
    val urgentColors = severityColors(Severity.URGENT)
    val scheme = MaterialTheme.colorScheme

    if (evidence != null) {
        EpisodeHero(monitor, evidence, urgent)
        return
    }

    NilaCard(
        modifier = Modifier.fillMaxWidth(),
        tone = if (evidence == null && !urgent) Tone.TEAL else null,
        borderColor = if (urgent) urgentColors.accent.copy(alpha = 0.6f) else null,
        contentPadding = PaddingValues(18.dp),
        verticalSpacing = 16.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PulseDot(active = monitor.running, size = 12.dp,
                     color = if (urgent) urgentColors.accent else null)
            Text(
                text = headline(monitor),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (urgent) urgentColors.accent else scheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            when {
                urgent -> SeverityChip(Severity.URGENT)
                monitor.simulated && monitor.running -> TonePill("Demo", Tone.PLUM, dot = true)
                monitor.running -> TonePill("Live", Tone.TEAL, dot = true)
            }
        }

        if (evidence != null) {
            // Elapsed time as the hero number. During an episode this is the
            // single fact that decides what a parent does next.
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = elapsed(evidence.durationSeconds),
                    style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Bold,
                    color = if (urgent) urgentColors.accent else scheme.onSurface,
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.padding(bottom = 4.dp)) {
                    Text(
                        "crying, " + evidence.trend.name.lowercase(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${(evidence.detectorConfidence * 100).toInt()}% sure it is a cry",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            IntensityCurve(points = evidence.envelope, color = chartPalette().cry)

            // Once a person is needed, nothing on this card may compete with
            // that: the clip bar is bookkeeping and waits for the summary.
            if (monitor.clipSeconds > 0.5f && !urgent) {
                ClipIndicator(monitor.clipSeconds)
            }
        } else if (monitor.running || monitor.demoComplete) {
            StatStrip(
                listOf(
                    (if (monitor.demoComplete) "Demo ran for" else "Listening for") to
                        uptime(monitor.armedSinceMs),
                    "Cries tonight" to monitor.eventsTonight.toString(),
                    "Cry score" to "${(monitor.lastCryProbability * 100).toInt()}%",
                )
            )
        }

        monitor.nowPlaying?.let { sound ->
            VoiceNoteBar(name = sound, isVoice = monitor.nowPlayingIsVoice)
        }

        if (monitor.running || monitor.demoComplete) {
            // The live signal. A bar that moves when you clap is the only thing
            // that distinguishes "listening" from a dead microphone.
            if (!monitor.simulated && !monitor.demoComplete && evidence == null) {
                InputLevelMeter(monitor.inputLevel, monitor.inputDbfs)
            }

            // One reassurance, not a spec sheet. The accelerator and the
            // latency are measured and shown in Insights, where somebody who
            // wants them goes looking; on the monitor they read as a debug
            // build, and during a cry they push the instructions down.
            if (!urgent && evidence == null) {
                TonePill("Analysed on this phone", Tone.TEAL, icon = Icons.Outlined.Shield)
            }
        }

        // The demo says what it is in one line. It used to be a six-line amber
        // box, which during the escalation sat between "Your baby needs you"
        // and the instructions -- the one place nothing may sit. Amber is for
        // a real problem, and a demo is not one.
        if (monitor.demoComplete) {
            InfoNote(
                "Demo finished. Everything here ran through the real pipeline – read " +
                    "what Nila did, then close it.",
                tone = Tone.PLUM,
            )
        } else if (monitor.simulated) {
            InfoNote(
                "Demo: a real recording through the real detector, in real time. " +
                    "Nothing is wrong. You are woken at 1:30.",
                tone = Tone.PLUM,
            )
        } else if (monitor.microphoneLooksDead) {
            WarningBox(
                "No sound reaching the microphone",
                "A minute of silence. Check nothing else is using the mic.",
            )
        }

        if (monitor.demoComplete) {
            // Deliberately the only button here. The summary is the point of
            // having run the demo, and a second control beside it invites
            // dismissing it before reading it.
            PillButton("Close the demo", onCloseDemo, Modifier.fillMaxWidth(),
                       icon = Icons.Outlined.Check)
        } else if (monitor.running) {
            OutlinePillButton(
                if (monitor.simulated) "Stop the demo" else "Stop monitoring",
                onStop,
                Modifier.fillMaxWidth(),
                icon = Icons.Filled.Stop,
            )
        }
    }
}

/**
 * Monitoring is off: one sentence, one button, and the sleeping baby.
 *
 * The line under the button says what pressing it does in the terms that matter
 * to a parent deciding whether to: it listens, and the listening stays here.
 */
@Composable
private fun IdleHero(babyName: String?, onStart: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    NilaCard(
        modifier = Modifier.fillMaxWidth(),
        tone = Tone.TEAL,
        contentPadding = PaddingValues(start = 18.dp, top = 18.dp, end = 10.dp, bottom = 16.dp),
        verticalSpacing = 14.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PulseDot(active = false, size = 12.dp)
                    Text(
                        "Monitoring is off",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    if (babyName != null) "$babyName is not being monitored."
                    else "Your baby is not being monitored.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 22.dp),
                )
            }
            Box(Modifier.size(width = 120.dp, height = 104.dp)) { SleepingBabyArt(dreaming = true) }
        }
        PillButton(
            "Start monitoring",
            onStart,
            Modifier.fillMaxWidth().padding(end = 8.dp),
            icon = Icons.Filled.PlayArrow,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.Mic, contentDescription = null,
                 tint = scheme.primary, modifier = Modifier.size(18.dp))
            Text(
                "Listens for crying, on this phone only",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The one permission that decides whether a 3am alert lights the screen.
 *
 * Android 14 and later do not grant it at install, and it used to be asked for
 * only on a Settings page about a second phone -- discovered, if at all, on the
 * night it mattered. It is checked whenever monitoring is on, and again each
 * time the app comes back from the system page that grants it.
 */
@Composable
private fun FullScreenAlertCheck(state: AppState) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(state.canUseFullScreenAlerts) }
    androidx.compose.runtime.DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                granted = state.canUseFullScreenAlerts
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    if (granted) return
    val colors = severityColors(Severity.ATTENTION)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.container)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Urgent alerts cannot wake the screen yet",
             style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
             color = colors.accent)
        Text("Android asks for this separately. Without it, an alert arrives as an " +
                 "ordinary notification on a dark phone.",
             style = MaterialTheme.typography.bodySmall, color = colors.onContainer)
        OutlinePillButton("Allow full-screen alerts", state::requestFullScreenAlerts,
                          Modifier.fillMaxWidth())
    }
}

/** Three figures in a white inset, dividers between them. */
@Composable
private fun StatStrip(stats: List<Pair<String, String>>) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainerLowest)
            .height(IntrinsicSize.Min)
            .padding(vertical = 12.dp),
    ) {
        stats.forEachIndexed { i, (label, value) ->
            if (i > 0) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(scheme.outlineVariant))
            }
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium,
                     color = scheme.onSurfaceVariant, maxLines = 1,
                     overflow = TextOverflow.Ellipsis)
                Text(value, style = MaterialTheme.typography.titleLarge
                         .copy(fontFeatureSettings = "tnum"),
                     fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Says a clip is being kept, and how much of the ninety seconds is used. */
@Composable
private fun ClipIndicator(seconds: Float) {
    val fraction by animateFloatAsState(
        targetValue = (seconds / com.nila.audio.EpisodeRecorder.CAP_SECONDS)
            .coerceIn(0f, 1f),
        animationSpec = tween(300),
        label = "clip-progress",
    )
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Keeping a clip of this cry",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            Text(
                "${seconds.toInt()}s / ${com.nila.audio.EpisodeRecorder.CAP_SECONDS}s",
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = scheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(scheme.surfaceContainerHighest)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(scheme.primary)
            )
        }
    }
}

/**
 * The two things a new user should try first, as two quiet tiles.
 *
 * Both run the real pipeline, which is why they sit on the monitor rather than
 * in Settings: they are how somebody without a crying baby finds out the
 * monitor works.
 */
@Composable
private fun TryItRow(state: AppState, monitor: MonitorState) {
    val result by state.selfTest.collectAsState()
    val running by state.selfTestRunning.collectAsState()
    val demoEnabled = !monitor.running && !monitor.demoComplete

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Test & troubleshoot")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TryTile(
                title = "Play a demo cry",
                subtitle = if (demoEnabled) "Sound up – it plays aloud" else "Running now",
                icon = Icons.Filled.PlayArrow,
                tone = Tone.TEAL,
                enabled = demoEnabled,
                onClick = state::runDemoCry,
                modifier = Modifier.weight(1f),
            )
            TryTile(
                title = "Test the detector",
                subtitle = if (running) "Testing..." else "Check it's working",
                icon = Icons.Outlined.GraphicEq,
                tone = Tone.BLUE,
                enabled = !running,
                onClick = state::runSelfTest,
                modifier = Modifier.weight(1f),
            )
        }
        result?.let {
            InfoNote(it.shortLine, icon = Icons.Outlined.Info, tone = Tone.BLUE)
        }
    }
}

@Composable
private fun TryTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tone: Tone,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NilaCard(
        modifier = modifier.alpha(if (enabled) 1f else 0.55f),
        tone = tone,
        contentPadding = PaddingValues(14.dp),
        onClick = if (enabled) onClick else null,
    ) {
        IconBadge(icon, tone, size = 42.dp, iconSize = 24.dp)
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.Bold, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                 overflow = TextOverflow.Ellipsis)
        }
    }
}

// ------------------------------------------------------------ this cry

/**
 * The top of a cry in progress: how long, what Nila is doing, the plan, and
 * what is next.
 *
 * The plan is the part that changed. The ladder used to be a progress rail and
 * seven technical rows further down the screen; a parent watching at forty
 * seconds could not tell whether the app was mid-plan or stuck. The journey
 * shows the four steps they would name, with the second each is due, and the
 * line under it says what is next and how long until it happens.
 */
@Composable
private fun EpisodeHero(
    monitor: MonitorState,
    evidence: com.nila.audio.CryEvidence,
    urgent: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val urgentColors = severityColors(Severity.URGENT)
    val seconds = evidence.durationSeconds
    val tried = EpisodePipeline.attempts(monitor.history)
    val latest = tried.lastOrNull()
    val phase = monitor.phase

    NilaCard(
        modifier = Modifier.fillMaxWidth(),
        tone = if (urgent) null else Tone.TEAL,
        borderColor = if (urgent) urgentColors.accent.copy(alpha = 0.5f) else null,
        contentPadding = PaddingValues(18.dp),
        verticalSpacing = 14.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PulseDot(active = true, size = 12.dp,
                     color = if (urgent) urgentColors.accent else null)
            Text(
                text = when {
                    urgent -> "Your baby needs you"
                    phase is MonitorState.Phase.Settling ->
                        if (monitor.nowPlayingIsVoice) "Trying your voice"
                        else "Trying ${(monitor.nowPlaying ?: phase.soother).lowercase()}"
                    phase is MonitorState.Phase.Verifying -> "Checking if it helped"
                    else -> "Crying"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (urgent) urgentColors.accent else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when {
                urgent -> SeverityChip(Severity.URGENT)
                monitor.simulated -> TonePill("Demo", Tone.PLUM, dot = true)
                else -> TonePill("Live", Tone.TEAL, dot = true)
            }
        }

        // Elapsed time as the hero number: during an episode it is the single
        // fact that decides what a parent does next.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = clockOf(seconds),
                style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.Bold,
                color = if (urgent) urgentColors.accent else scheme.onSurface,
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text("crying, " + evidence.trend.name.lowercase(),
                     style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        urgent && tried.isNotEmpty() && tried.none { it.helped == true } ->
                            if (tried.size == 1) "1 sound tried, it did not settle her"
                            else "${tried.size} sounds tried, none settled her"
                        latest?.helped == false -> "${latest.name} did not help"
                        latest?.helped == true -> "${latest.name} seemed to help"
                        else -> "${(evidence.detectorConfidence * 100).toInt()}% sure it is a cry"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }

        CryJourney(
            seconds = seconds,
            history = monitor.history,
            settling = phase is MonitorState.Phase.Settling,
            verifying = phase is MonitorState.Phase.Verifying,
        )

        if (!urgent) {
            EpisodePipeline.nextUp(seconds, monitor.history)?.let { next ->
                NextUpRow(next, seconds, tried, keepingClip = monitor.clipSeconds > 0.5f)
            }
        }
    }
}

/**
 * Everything under the hero while a cry runs, in the order a parent needs it.
 *
 * Before the alert: the sound that is playing, the sounds already judged, the
 * cry's loudness with each sound drawn on it, and the guess about the cause --
 * small, because it is a guess. At the alert: what to do, what was tried, and
 * how long until the episode closes. The full seven-rung ladder is folded at
 * the bottom for anybody who wants every step.
 */
@Composable
private fun LiveEpisode(
    evidence: com.nila.audio.CryEvidence,
    monitor: MonitorState,
    onStop: () -> Unit,
) {
    val phase = monitor.phase
    val urgent = phase is MonitorState.Phase.Escalated || phase is MonitorState.Phase.Safety
    val seconds = evidence.durationSeconds
    val tried = EpisodePipeline.attempts(monitor.history)
    var showSteps by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!urgent) {
            monitor.nowPlaying?.let { sound ->
                val current = tried.lastOrNull()
                VoiceNoteBar(
                    name = sound,
                    isVoice = monitor.nowPlayingIsVoice,
                    detail = current?.let {
                        "Try ${it.number} · playing for ${(seconds - it.startedAtSeconds)
                            .coerceAtLeast(0)}s"
                    },
                )
            }
        }

        // The verdict outranks everything else the moment it exists: it is
        // the only card that tells the reader what to do.
        if (urgent) {
            monitor.advice?.let { advice ->
                ReasonVerdict(
                    headline = advice.headline,
                    steps = advice.steps,
                    caution = advice.caution,
                    source = advice.source,
                    lead = "Go to her now.",
                )
            }
        }

        // One sound playing is already on screen in the bar above. The list
        // earns its place once there is a result in it, or a second sound.
        if (urgent || tried.size > 1 || tried.any { it.helped != null }) {
            AttemptsCard(
                attempts = tried,
                seconds = seconds,
                playingNow = monitor.nowPlaying != null,
                subtitle = if (urgent) "before waking you" else "measured, not assumed",
            )
        }

        if (urgent) {
            EpisodePipeline.nextUp(seconds, monitor.history)?.let { next ->
                NextUpRow(next, seconds, tried, keepingClip = monitor.clipSeconds > 0.5f)
            }
        }

        EpisodeCurve(
            envelope = evidence.envelope,
            seconds = seconds,
            attempts = tried,
            clipSeconds = if (urgent) 0f else monitor.clipSeconds,
        )

        if (!urgent) {
            evidence.hypothesis?.let { h ->
                BestGuessRow(h.label, h.confidence, h.runnerUp.takeIf { h.ambiguous })
            }
        }

        if (monitor.pipeline.isNotEmpty()) {
            StepsToggle(
                done = monitor.pipeline.count {
                    it.status == EpisodePipeline.Status.DONE ||
                        it.status == EpisodePipeline.Status.ACTIVE
                },
                total = monitor.pipeline.size,
                open = showSteps,
                onToggle = { showSteps = !showSteps },
            )
            AnimatedVisibility(visible = showSteps) {
                NilaCard(modifier = Modifier.fillMaxWidth()) {
                    PipelineChart(steps = monitor.pipeline, seconds = seconds, showRail = false)
                }
            }
        }

        if (monitor.simulated) {
            InfoNote(
                "Demo: a real recording through the real detector, in real time. " +
                    if (urgent) "Nothing is wrong – this is what an alert looks like."
                    else "Nothing is wrong. You are woken at 1:30.",
                tone = Tone.PLUM,
            )
        }
        OutlinePillButton(
            if (monitor.simulated) "Stop the demo" else "Stop monitoring",
            onStop,
            Modifier.fillMaxWidth(),
            icon = Icons.Filled.Stop,
        )
    }
}


// -------------------------------------------------------- closed episode

/**
 * The record of an episode that has finished.
 *
 * Everything the app claimed, with the audio it claimed it about. The clip is
 * first because it is the only item on the card a reader can check for
 * themselves; the ladder is collapsed because by this point it is evidence
 * rather than news.
 */
@Composable
private fun EpisodeSummaryCard(
    episode: MonitorState.EpisodeSummary,
    onDismiss: () -> Unit,
) {
    var showSteps by remember(episode.startedAtMs) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(
            if (episode.closedByTimeout) "Last cry – closed at three minutes"
            else "Last cry"
        )
        NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 14.dp) {
            StatStrip(
                listOf(
                    "Ran for" to elapsed(episode.durationSeconds),
                    "Sounds tried" to episode.soothersTried.toString(),
                    "Woke you" to if (episode.wokeSomebody) "Yes" else "No",
                )
            )

            episode.clipPath?.let { path ->
                CryClipPlayer(
                    path = path,
                    envelope = episode.evidence?.envelope.orEmpty(),
                )
            }

            episode.advice?.let { advice ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "REASON DETERMINED",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        advice.headline,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 24.sp,
                    )
                }
            } ?: episode.evidence?.hypothesis?.let { h ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "REASON DETERMINED",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Probably ${h.label.replace('_', ' ')} – " +
                            "${(h.confidence * 100).toInt()}%, a guess from the sound.",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            TextButton(
                onClick = { showSteps = !showSteps },
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                Text(
                    if (showSteps) "Hide the steps Nila took"
                    else "Show the ${episode.pipeline.count {
                        it.status == EpisodePipeline.Status.DONE
                    }} steps Nila took",
                    fontWeight = FontWeight.SemiBold,
                )
            }
            AnimatedVisibility(visible = showSteps) {
                PipelineChart(
                    steps = episode.pipeline,
                    seconds = episode.durationSeconds,
                    showRail = false,
                )
            }

            OutlinePillButton("Dismiss", onDismiss, Modifier.fillMaxWidth())
        }
        AttemptsCard(
            attempts = EpisodePipeline.attempts(episode.history),
            seconds = episode.durationSeconds,
            playingNow = false,
            subtitle = "measured, not assumed",
        )
        // Only while the clip is actually there. The player already says when
        // it has gone, and a note promising seven days of storage under that
        // sentence contradicted it.
        val clipKept = remember(episode.clipPath) {
            episode.clipPath?.let { java.io.File(it).exists() } == true
        }
        if (clipKept) HedgeNote(
            "The clip is ${episode.clipSeconds.toInt()}s of audio in this app's " +
                "private storage. It is deleted after " +
                "${com.nila.audio.EpisodeRecorder.RETAIN_DAYS} days and is never uploaded."
        )
    }
}

// ------------------------------------------------------------ quick log

/**
 * Three targets at thumb height, and a sleep clock.
 *
 * Every fact the assistant knows about this baby comes from here, and a log
 * that costs more than one tap at 3am is a log that stops being kept.
 *
 * Sleep is the one of the three that is not an instant. A feed and a nappy are
 * things that happened; a sleep is a thing that is *still happening*, and
 * logging it as a timestamp produced a log from which no duration could be
 * recovered. So the sleep tile is a stopwatch with two states. Tap to start,
 * tap to stop, and while it runs it shows the time on its face. Still one tap
 * either way.
 *
 * Each tile confirms itself with a tick for a moment after it is tapped. A log
 * that changes nothing visible except "1h 8m ago" becoming "just now" is a log
 * people tap twice.
 */
@Composable
private fun QuickLog(
    care: List<com.nila.data.CareRecord>,
    asleepSinceMs: Long?,
    onLog: (CareKind) -> Unit,
    onToggleSleep: () -> Unit,
    onUndo: (Long) -> Unit,
) {
    var confirmed by remember { mutableStateOf<String?>(null) }
    var undoable by remember { mutableStateOf<Pair<CareKind, Long>?>(null) }
    LaunchedEffect(confirmed) {
        if (confirmed != null) {
            kotlinx.coroutines.delay(1_800)
            confirmed = null
        }
    }
    // Long enough to notice a mis-tap and reach back for it one-handed.
    LaunchedEffect(undoable) {
        if (undoable != null) {
            kotlinx.coroutines.delay(6_000)
            undoable = null
        }
    }
    val sleepElapsed = rememberElapsed(asleepSinceMs)
    fun logged(kind: CareKind) { undoable = kind to System.currentTimeMillis() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Quick log")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LogTile(
                label = "Feed",
                subtitle = lastOf(care, CareKind.FEED),
                icon = NilaIcons.Bottle,
                tone = Tone.TEAL,
                confirmed = confirmed == "feed",
                modifier = Modifier.weight(1f),
            ) { onLog(CareKind.FEED); confirmed = "feed"; logged(CareKind.FEED) }
            LogTile(
                label = "Nappy",
                subtitle = lastOf(care, CareKind.DIAPER),
                icon = NilaIcons.Nappy,
                tone = Tone.PEACH,
                confirmed = confirmed == "nappy",
                modifier = Modifier.weight(1f),
            ) { onLog(CareKind.DIAPER); confirmed = "nappy"; logged(CareKind.DIAPER) }
            // One word on the face: "Sleep" when awake, "Awake" when asleep --
            // each is the *action*, matching the two tiles beside it.
            LogTile(
                label = if (asleepSinceMs != null) "Awake" else "Sleep",
                subtitle = sleepElapsed?.let { "asleep $it" } ?: lastOf(care, CareKind.SLEEP_START),
                icon = if (asleepSinceMs != null) Icons.Outlined.LightMode
                       else Icons.Outlined.Bedtime,
                tone = Tone.BLUE,
                emphasised = asleepSinceMs != null,
                confirmed = confirmed == "sleep",
                action = if (asleepSinceMs != null) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                modifier = Modifier.weight(1f),
            ) {
                val kind = if (asleepSinceMs != null) CareKind.SLEEP_END else CareKind.SLEEP_START
                onToggleSleep(); confirmed = "sleep"; logged(kind)
            }
        }
        undoable?.let { (kind, at) ->
            UndoStrip(
                text = when (kind) {
                    CareKind.FEED -> "Feed logged"
                    CareKind.DIAPER -> "Nappy change logged"
                    CareKind.SLEEP_START -> "Sleep started"
                    else -> "Sleep ended"
                } + " at " + java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
                    .format(java.util.Date(at)),
                onUndo = {
                    care.firstOrNull { it.kind == kind.name && it.atMs >= at - 5_000 }
                        ?.let { onUndo(it.id) }
                    undoable = null
                },
            )
        }
        if (asleepSinceMs != null && undoable == null) {
            SleepTrackerNote(asleepSinceMs)
        }
    }
}

/** "Feed logged at 03:12 · Undo", for six seconds after a tap. */
@Composable
private fun UndoStrip(text: String, onUndo: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.inverseSurface)
            .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Check, contentDescription = null, tint = scheme.inverseOnSurface,
             modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = scheme.inverseOnSurface,
             modifier = Modifier.weight(1f).padding(start = 10.dp))
        TextButton(onClick = onUndo) {
            Text("Undo", color = scheme.inversePrimary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LogTile(
    label: String,
    subtitle: String,
    icon: ImageVector,
    tone: Tone,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
    confirmed: Boolean = false,
    action: ImageVector = Icons.Outlined.Add,
    onClick: () -> Unit,
) {
    val colors = toneColors(tone)
    val scheme = MaterialTheme.colorScheme
    NilaCard(
        modifier = modifier,
        tone = tone,
        borderColor = if (emphasised) colors.ink.copy(alpha = 0.6f) else null,
        contentPadding = PaddingValues(12.dp),
        verticalSpacing = 6.dp,
        onClick = onClick,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            IconBadge(if (confirmed) Icons.Outlined.Check else icon, tone,
                      size = 38.dp, iconSize = 21.dp)
            Spacer(Modifier.weight(1f))
            Icon(action, contentDescription = null,
                 tint = colors.ink.copy(alpha = 0.75f), modifier = Modifier.size(18.dp))
        }
        Column {
            Text(label, style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.Bold, maxLines = 1)
            Text(
                if (confirmed) "Logged" else subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (confirmed) colors.ink else scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The line under the row while a sleep is running. Says what to tap, and when it began. */
@Composable
private fun SleepTrackerNote(sinceMs: Long) {
    val started = remember(sinceMs) {
        java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
            .format(java.util.Date(sinceMs))
    }
    val elapsed = rememberElapsed(sinceMs) ?: "just now"
    InfoNote(
        "Asleep since $started – $elapsed. Tap Awake when she wakes.",
        icon = Icons.Outlined.Bedtime,
        tone = Tone.BLUE,
    )
}

/**
 * A clock that ticks while a sleep is running, and costs nothing when it is not.
 *
 * Thirty seconds rather than one: the face reads in minutes, so a per-second
 * tick would recompose sixty times to change nothing fifty-nine of them, on a
 * screen that is often left open all night.
 */
@Composable
private fun rememberElapsed(sinceMs: Long?): String? {
    if (sinceMs == null) return null
    val elapsed by androidx.compose.runtime.produceState(
        initialValue = System.currentTimeMillis() - sinceMs,
        key1 = sinceMs,
    ) {
        while (true) {
            value = System.currentTimeMillis() - sinceMs
            kotlinx.coroutines.delay(30_000)
        }
    }
    val minutes = (elapsed / 60_000L).toInt()
    return when {
        minutes < 1 -> "just started"
        minutes < 60 -> "${minutes}m"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}

// ------------------------------------------------------------- dashboard

/**
 * Today in four numbers, each in the colour of the chart it belongs to, with
 * the last five days drawn small in the corner so "today" has something to be
 * compared with.
 */
@Composable
private fun TodayTiles(
    insights: Insights.Summary,
    care: List<com.nila.data.CareRecord>,
    babyName: String?,
    onSeeAll: () -> Unit,
) {
    // Every figure on the dashboard comes from one place. The colic summary is
    // the same crying counted a different way -- rolling twenty-four hour
    // windows rather than calendar days -- and mixing the two on one screen is
    // how a tile ends up disagreeing with the chart directly beneath it.
    val today = insights.today
    val cryMinutes = today?.cryMinutes ?: 0
    val episodes = today?.cryEpisodes ?: 0
    val recent = insights.days.takeLast(5)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(
            if (babyName != null) "$babyName today" else "Today",
            action = "See all",
            onAction = onSeeAll,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard(
                label = "Crying",
                value = humanMinutes(cryMinutes),
                caption = when (episodes) {
                    0 -> "no episodes yet"
                    1 -> "1 episode"
                    else -> "$episodes episodes"
                },
                icon = Icons.Outlined.SentimentDissatisfied,
                tone = Tone.PLUM,
                trend = recent.map { it.cryMinutes.toFloat() },
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                label = "Sleep",
                value = today?.sleepMinutes?.takeIf { it > 0 }
                    ?.let { humanMinutes(it) } ?: "-",
                caption = if ((today?.sleepMinutes ?: 0) > 0) "from your sleep log"
                          else "tap Sleep to start one",
                icon = Icons.Outlined.Bedtime,
                tone = Tone.BLUE,
                trend = recent.map { it.sleepMinutes.toFloat() },
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard(
                label = "Feeds",
                value = (today?.feeds ?: 0).toString(),
                caption = "last ${lastOf(care, CareKind.FEED)}",
                icon = NilaIcons.Bottle,
                tone = Tone.TEAL,
                trend = recent.map { it.feeds.toFloat() },
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                label = "Days over 3h",
                value = "${insights.daysOverThreeHours}/${Insights.DAYS}",
                caption = if (insights.meetsDurationCriterion) "worth a doctor's eye"
                          else "the colic duration rule",
                icon = Icons.Outlined.DateRange,
                tone = Tone.PLUM,
                trend = null,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    caption: String,
    icon: ImageVector,
    tone: Tone,
    trend: List<Float>?,
    modifier: Modifier = Modifier,
) {
    val colors = toneColors(tone)
    NilaCard(
        modifier = modifier,
        tone = tone,
        contentPadding = PaddingValues(14.dp),
        verticalSpacing = 10.dp,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            IconBadge(icon, tone, size = 40.dp, iconSize = 22.dp)
            Spacer(Modifier.weight(1f))
            trend?.let { MiniBars(it, colors.ink) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = colors.ink,
                 fontWeight = FontWeight.SemiBold)
            Text(value, style = MaterialTheme.typography.headlineSmall
                     .copy(fontFeatureSettings = "tnum"))
            Text(caption, style = MaterialTheme.typography.labelMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                 overflow = TextOverflow.Ellipsis)
        }
    }
}

// ----------------------------------------------------------- cry trend

/**
 * Seven days of crying against the three-hour line.
 *
 * The line is the Wessel duration criterion and nothing else. It is a count of
 * hours, which is why the app is allowed to draw it: measuring a duration
 * against a published duration makes no clinical claim. What it means, and why
 * crossing it three times is not a diagnosis, is in Insights.
 */
@Composable
private fun CryTrendCard(insights: Insights.Summary, onOpenInsights: () -> Unit) {
    val palette = chartPalette()
    val scheme = MaterialTheme.colorScheme
    var picked by remember { mutableStateOf<Int?>(null) }
    if (insights.days.isEmpty()) return
    val peak = insights.peakCryMinutes

    NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 14.dp,
             contentPadding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Crying, day by day", style = MaterialTheme.typography.titleLarge,
                 fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("Last 7 days", style = MaterialTheme.typography.labelLarge,
                 color = scheme.onSurfaceVariant)
        }
        ColumnChart(
            bars = insights.days.map {
                Bar(
                    label = dayShort(it),
                    value = it.cryMinutes.toFloat(),
                    readout = humanMinutes(it.cryMinutes),
                    emphasised = it.isToday,
                )
            },
            color = palette.cry,
            ticks = hourTicks(maxOf(peak, 180)),
            threshold = 180f,
            thresholdLabel = "3h colic line",
            selected = picked,
            onSelect = { picked = it },
        )
        val day = picked?.let(insights.days::getOrNull)
        Text(
            text = day?.let {
                "${dayTitle(it)}: ${humanMinutes(it.cryMinutes)} over " +
                    "${it.cryEpisodes} ${if (it.cryEpisodes == 1) "episode" else "episodes"}."
            } ?: ("Averaging ${humanMinutes(insights.averageCryMinutes)} a day, " +
                "with a worst day of ${humanMinutes(peak)}. Tap a bar for that day."),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        TakeawayStrip(
            text = when (val n = insights.daysOverThreeHours) {
                0 -> "No day reached the 3h line this week"
                1 -> "1 day was above the 3h line"
                else -> "$n days were above the 3h line"
            },
            tone = Tone.PLUM,
            icon = Icons.Outlined.BarChart,
            onClick = onOpenInsights,
        )
    }
}

/**
 * The bridge from today to the week: one sentence and a way into the full record.
 */
@Composable
private fun WeekBanner(insights: Insights.Summary, onOpenTimeline: () -> Unit) {
    val cries = insights.days.sumOf { it.cryEpisodes }
    if (insights.days.isEmpty()) return
    val colors = toneColors(Tone.PLUM)
    NilaCard(
        modifier = Modifier.fillMaxWidth(),
        tone = Tone.PLUM,
        onClick = onOpenTimeline,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Outlined.BarChart, contentDescription = null, tint = colors.ink,
                 modifier = Modifier.size(28.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when (cries) {
                        0 -> "No crying this week"
                        1 -> "1 crying episode this week"
                        else -> "$cries crying episodes this week"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text("Each one is in the timeline, with what Nila did.",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null,
                 tint = colors.ink)
        }
    }
}

// --------------------------------------------------------------- sleep

/**
 * Seven days of sleep, as a line through seven points.
 *
 * A line rather than the bars used for crying, because the two charts are
 * asked different questions. Crying is measured against a fixed rule -- three
 * hours -- so its chart has to support *counting* the days that crossed it.
 * Sleep has no threshold worth drawing for a four-month-old, and the only
 * question a parent actually has about it is whether it is getting better or
 * worse. That is a slope.
 *
 * A day with nothing logged is a hole in the line, not a zero. "We forgot to
 * tap" and "she did not sleep" are wildly different facts and this chart will
 * not print one as the other.
 */
@Composable
private fun SleepCard(insights: Insights.Summary) {
    val palette = chartPalette()
    val scheme = MaterialTheme.colorScheme
    var picked by remember { mutableStateOf<Int?>(null) }
    if (insights.days.isEmpty()) return

    NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 14.dp,
             contentPadding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sleep, day by day", style = MaterialTheme.typography.titleLarge,
                 fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            insights.averageSleepMinutes?.let {
                TonePill("Avg ${humanMinutes(it)}/day", Tone.BLUE, icon = Icons.Outlined.Bedtime)
            }
        }
        if (insights.daysWithSleepLogged == 0) {
            Text(
                "No sleep tracked yet. Tap Sleep when she goes down and Awake when " +
                    "she wakes. Two taps a nap is all this chart needs, and Nila will " +
                    "not guess at the ones you miss.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        } else {
            LineChart(
                points = insights.days.map {
                    Point(
                        label = dayShort(it),
                        value = it.sleepMinutes.takeIf { m -> m > 0 }?.let { m -> m / 60f },
                        readout = humanMinutes(it.sleepMinutes),
                        title = dayTitle(it),
                        emphasised = it.isToday,
                    )
                },
                color = palette.sleep,
                valueSuffix = "h",
                selected = picked,
                onSelect = { picked = it },
                height = 190.dp,
            )
            val day = picked?.let(insights.days::getOrNull)
            Text(
                text = day?.let {
                    if (it.sleepMinutes == 0) "${dayTitle(it)}: nothing tracked."
                    else "${dayTitle(it)}: ${humanMinutes(it.sleepMinutes)} over " +
                        "${it.sleepSessions} " +
                        (if (it.sleepSessions == 1) "stretch." else "stretches.")
                } ?: (insights.averageSleepMinutes?.let {
                    "Averaging ${humanMinutes(it)} a day across the " +
                        "${insights.daysWithSleepLogged} days you tracked. " +
                        "Touch the line to read a day."
                } ?: "Touch the line to read a day."),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            if (insights.daysWithSleepLogged < Insights.DAYS) {
                Text(
                    "A gap in the line is a day with nothing tracked, not a day without sleep.",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

// -------------------------------------------------------------- causes

/**
 * What the sound suggested, and how often it suggested nothing.
 *
 * This is the one chart on the screen that is drawn from a model the project
 * says out loud does not work: the reason head scores 0.444 macro AUC across
 * unseen infants, which is below chance. It is here anyway, because a parent
 * who has been shown "probably belly pain" on nine separate nights deserves to
 * see that the guess was "belly pain" on nine separate nights -- a bias that is
 * invisible one cry at a time and obvious in a ring.
 *
 * The wedge for the episodes the model would not name is the point of the
 * figure as much as the named ones are, which is why it is never dropped.
 */
@Composable
private fun CauseCard(insights: Insights.Summary) {
    val palette = chartPalette()
    if (!insights.hasCauses) return
    val total = insights.causes.sumOf { it.episodes }.toFloat()

    NilaCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 16.dp,
             contentPadding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("What the sound suggested", style = MaterialTheme.typography.titleLarge,
                 fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("This week", style = MaterialTheme.typography.labelLarge,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DonutChart(
            wedges = insights.causes.mapIndexed { i, cause ->
                Wedge(
                    label = cause.label,
                    value = cause.episodes.toFloat(),
                    // The unnamed bucket is always the last colour, a
                    // deliberate grey, whatever rank it came out at.
                    color = if (cause.key.isEmpty()) palette.causes.last()
                            else palette.cause(i),
                    readout = "${cause.episodes} · " +
                        "${percent(cause.episodes.toFloat(), total)}%",
                )
            },
            centreValue = total.toInt().toString(),
            centreLabel = "cries\nthis week",
        )
        insights.causes.firstOrNull()?.let {
            TakeawayStrip(
                "Most often ${it.label.lowercase()} – ${it.episodes} of ${total.toInt()} cries.",
                tone = Tone.PLUM,
                icon = Icons.Outlined.SentimentDissatisfied,
            )
        }
        HedgeNote(
            "Guesses from the sound, not findings – on babies it has never heard, " +
                "the cause model does no better than chance."
        )
    }
}

// ---------------------------------------------------------------- helpers

/** Axis ticks in whole hours up to a little above [maxMinutes]. */
internal fun hourTicks(maxMinutes: Int): List<Pair<Float, String>> {
    val topHours = ((maxMinutes + 59) / 60).coerceAtLeast(3)
    val step = if (topHours <= 4) 1 else 2
    return (0..topHours step step).map { it * 60f to "${it}h" }
}

private fun dayShort(day: Insights.Day): String =
    day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())

private fun dayTitle(day: Insights.Day): String =
    if (day.isToday) "Today"
    else day.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())

private fun lastOf(care: List<com.nila.data.CareRecord>, kind: CareKind): String {
    val record = care.firstOrNull { it.kind == kind.name } ?: return "not yet"
    val minutes = (System.currentTimeMillis() - record.atMs) / 60_000L
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        else -> "${minutes / 60}h ${minutes % 60}m ago"
    }
}

/**
 * The status line, shortened for a card header.
 *
 * [MonitorState.statusLine] is written for a watch face and a notification,
 * where it is the only text there is. Here the duration is already the largest
 * thing on the card, so repeating it in the header wastes the one line that
 * could say what the app is *doing* instead.
 */
private fun headline(monitor: MonitorState): String = when (monitor.phase) {
    is MonitorState.Phase.CryDetected -> "Crying"
    is MonitorState.Phase.Settling -> "Trying to settle"
    is MonitorState.Phase.Verifying -> "Checking whether that helped"
    is MonitorState.Phase.Escalated -> "Your baby needs you"
    else -> monitor.statusLine
}

/** `1:42`, or `42s` below a minute. Cries are read in both units. */
private fun elapsed(seconds: Int): String =
    if (seconds < 60) "${seconds}s"
    else "%d:%02d".format(seconds / 60, seconds % 60)

/**
 * Seconds below a minute, then minutes, then hours.
 *
 * It used to start at minutes, so the whole of a twenty-second demo showed
 * "0m" and looked frozen -- the one number on screen that should have been
 * visibly counting.
 */
private fun uptime(sinceMs: Long): String {
    if (sinceMs <= 0) return "-"
    val ms = System.currentTimeMillis() - sinceMs
    val h = TimeUnit.MILLISECONDS.toHours(ms)
    val m = TimeUnit.MILLISECONDS.toMinutes(ms)
    val s = TimeUnit.MILLISECONDS.toSeconds(ms)
    return when {
        h > 0 -> "${h}h ${m % 60}m"
        m > 0 -> "${m}m ${s % 60}s"
        else -> "${s}s"
    }
}
