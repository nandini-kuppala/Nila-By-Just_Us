package com.nila.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nila.audio.ModelCard
import com.nila.ui.AppState
import com.nila.ui.Insights
import com.nila.ui.components.Bar
import com.nila.ui.components.ColumnChart
import com.nila.ui.components.DonutChart
import com.nila.ui.components.HedgeNote
import com.nila.ui.components.HourProfile
import com.nila.ui.components.IconBadge
import com.nila.ui.components.InfoNote
import com.nila.ui.components.MetricTile
import com.nila.ui.components.NilaCard
import com.nila.ui.components.OutlinePillButton
import com.nila.ui.components.ScreenHero
import com.nila.ui.components.SleepingBabyArt
import com.nila.ui.components.TakeawayStrip
import com.nila.ui.components.TonePill
import com.nila.ui.components.Wedge
import com.nila.ui.components.humanMinutes
import com.nila.ui.components.percent
import com.nila.ui.theme.Tone
import com.nila.ui.theme.chartPalette
import com.nila.ui.theme.toneColors
import java.time.format.TextStyle
import java.util.Locale

/**
 * The screen that argues with itself.
 *
 * Everywhere else the app makes claims: this is crying, this is probably belly
 * pain, this sound helped. Insights is where each of those is put next to the
 * measurement behind it, including the one measurement that came back saying
 * the feature does not work. Shipping a model card inside the product, with the
 * failure in it, is the whole posture of this project.
 *
 * Four questions, in the order somebody actually asks them:
 *
 * 1. **Is it getting worse?** A week of crying as a trend, and the hours it
 *    falls in.
 * 2. **Does the thing it claims to do actually happen?** The ladder's own
 *    outcomes -- how many cries never reached a person.
 * 3. **What settles this baby?** Measured by re-reading the loudness envelope
 *    after a sound, not by asking a parent to rate it.
 * 4. **How good are the models, and how hard is the phone working?** Both
 *    answered with numbers from this device, not from a datasheet.
 *
 * Each section's caveat sits behind the (i) beside its title where it is an
 * explanation, and stays on the card where it is a warning.
 */
@Composable
fun LearnScreen(state: AppState) {
    val sootheStats by state.sootheStats.collectAsState()
    val insights by state.insights.collectAsState()
    val monitor by state.monitor.collectAsState()
    val selfTest by state.selfTest.collectAsState()
    val testing by state.selfTestRunning.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        ScreenHero(
            title = "Insights",
            body = "The patterns in what Nila heard and what you logged this week, " +
                "and how far each of them can be trusted.",
            art = { SleepingBabyArt(night = true) },
        )

        CryTrend(insights)
        LadderOutcomes(insights)
        WhatSettles(sootheStats)
        ModelQuality()
        OnThisPhone(monitor, selfTest, testing, onRunTest = state::runSelfTest)
    }
}

/**
 * A section: title, an (i) that opens its explanation, and its content.
 */
@Composable
private fun Section(
    title: String,
    info: String? = null,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (info != null) {
                IconButton(onClick = { open = !open }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Info,
                         contentDescription = if (open) "Hide the explanation" else "What this means",
                         tint = if (open) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                         modifier = Modifier.size(20.dp))
                }
            }
        }
        if (info != null) {
            AnimatedVisibility(visible = open) { InfoNote(info) }
        }
        content()
    }
}

/**
 * A section whose title lives inside its card, the way a chart is titled: the
 * card is the figure, and its name belongs on it.
 */
@Composable
private fun CardSection(
    title: String,
    info: String? = null,
    spacing: androidx.compose.ui.unit.Dp = 12.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    NilaCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(18.dp),
             verticalSpacing = spacing) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                 fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
            if (info != null) {
                IconButton(onClick = { open = !open }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Info,
                         contentDescription = if (open) "Hide the explanation" else "What this means",
                         tint = if (open) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                         modifier = Modifier.size(19.dp))
                }
            }
        }
        if (info != null) {
            AnimatedVisibility(visible = open) { InfoNote(info) }
        }
        content()
    }
}

// ------------------------------------------------------------- the week

/** A week of crying: the two totals, the days against the colic line, and the hours. */
@Composable
private fun CryTrend(insights: Insights.Summary) {
    val palette = chartPalette()
    val scheme = MaterialTheme.colorScheme
    var picked by remember { mutableStateOf<Int?>(null) }
    if (insights.days.isEmpty()) return
    val cries = insights.days.sumOf { it.cryEpisodes }

    Section(
        "Crying this week",
        info = "The colic definition is three or more hours of crying a day on three " +
            "or more days a week. Every part of that is a duration, which is why " +
            "this app can measure it – and why the measurement is not a diagnosis.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.height(IntrinsicSize.Max)) {
            StatTile(
                icon = Icons.Outlined.Schedule, tone = Tone.PLUM,
                label = "Total crying", value = humanMinutes(insights.weekCryMinutes),
                caption = "$cries " + if (cries == 1) "cry" else "cries",
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            StatTile(
                icon = Icons.Outlined.DateRange, tone = Tone.BLUE,
                label = "Days over 3h",
                value = "${insights.daysOverThreeHours} / ${Insights.DAYS}",
                caption = if (insights.meetsDurationCriterion) "Meets the duration rule"
                          else "Below the duration rule",
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }

    CardSection("Daily crying duration", spacing = 14.dp) {
            ColumnChart(
                bars = insights.days.map {
                    Bar(
                        label = it.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                        value = it.cryMinutes.toFloat(),
                        readout = humanMinutes(it.cryMinutes),
                        emphasised = it.isToday,
                    )
                },
                color = palette.cry,
                ticks = hourTicks(maxOf(insights.peakCryMinutes, 180)),
                threshold = 180f,
                thresholdLabel = "3h colic line",
                selected = picked,
                onSelect = { picked = it },
                height = 200.dp,
            )
            val day = picked?.let(insights.days::getOrNull)
            Text(
                day?.let {
                    val name = if (it.isToday) "Today"
                               else it.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
                    "$name: ${humanMinutes(it.cryMinutes)} over ${it.cryEpisodes} " +
                        (if (it.cryEpisodes == 1) "episode." else "episodes.")
                } ?: ("Averaging ${humanMinutes(insights.averageCryMinutes)} a day, with a worst " +
                    "day of ${humanMinutes(insights.peakCryMinutes)}. Touch a bar to read that day."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        
    }

    CardSection("When in the day", spacing = 14.dp) {
            HourProfile(insights.hourlyCryMinutes, palette.cry)
            Text(
                insights.pattern
                    ?: "No time of day stands out yet – the crying is spread fairly " +
                        "evenly, or there is not enough of it to tell.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )
        
    }
}

@Composable
private fun StatTile(
    icon: ImageVector,
    tone: Tone,
    label: String,
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
) {
    NilaCard(modifier = modifier, contentPadding = PaddingValues(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(icon, tone, size = 42.dp, iconSize = 22.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.headlineSmall
                         .copy(fontFeatureSettings = "tnum"))
                Text(caption, style = MaterialTheme.typography.labelMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------------------------------------------------- the argument

/**
 * The ladder's own scorecard.
 *
 * This is the one figure in the app that measures the product's central claim
 * rather than a model's accuracy: every other monitor ends at *notify*, and the
 * argument for this one is the two rungs before that. Either the cries are
 * being handled without a person or they are not, and this says which.
 */
@Composable
private fun LadderOutcomes(insights: Insights.Summary) {
    val palette = chartPalette()
    val o = insights.outcomes
    if (o.total == 0) return
    val total = o.total.toFloat()

    CardSection(
        "How the week's cries ended",
        info = "\"Nila woke you\" is not a failure count. A cry that needed a parent and " +
            "got one is the system working. What this cannot count is the opposite – " +
            "a cry that needed a parent and did not get one.",
        spacing = 16.dp,
    ) {
            DonutChart(
                // Short labels, because the legend gives each one a third of
                // the card's width and the sentence underneath says the whole
                // thing in words anyway.
                wedges = listOf(
                    Wedge("A sound", o.settledBySound.toFloat(), palette.feed,
                          "${o.settledBySound} · ${percent(o.settledBySound.toFloat(), total)}%"),
                    Wedge("Nila woke you", o.wokeYou.toFloat(), palette.cry,
                          "${o.wokeYou} · ${percent(o.wokeYou.toFloat(), total)}%"),
                    Wedge("On her own", o.settledAlone.toFloat(), palette.sleep,
                          "${o.settledAlone} · ${percent(o.settledAlone.toFloat(), total)}%"),
                ).filter { it.value > 0f },
                centreValue = "${o.total}",
                centreLabel = "cries\nthis week",
            )
            TakeawayStrip(
                "${o.settledAlone + o.settledBySound} of ${o.total} cries were over before " +
                    "anybody had to get up.",
                tone = Tone.TEAL,
                icon = Icons.Outlined.SentimentSatisfied,
            )
        
    }
}

// ------------------------------------------------------------- soothers

/**
 * What actually settles this baby.
 *
 * Learned by playing a sound and then re-reading the cry's loudness envelope
 * thirty-five seconds later, not by asking a parent to rate it. The bar is the
 * smoothed score the picker uses; the fraction beside it is the raw count,
 * because a bar at 70% from two attempts and a bar at 70% from twenty are very
 * different facts and the bar alone cannot tell them apart.
 */
@Composable
private fun WhatSettles(stats: List<com.nila.actions.SootheMemory.Stats>) {
    val scheme = MaterialTheme.colorScheme

    CardSection(
        "What settles your baby",
        info = "Nila plays a sound, then re-reads the loudness of the cry thirty-five " +
            "seconds later. The bar is the smoothed score it picks by; the count beside " +
            "it is the raw record, because 70% from two tries and 70% from twenty are " +
            "different facts.",
        spacing = 16.dp,
    ) {
            if (stats.all { it.attempts == 0 }) {
                Text(
                    "Nothing tried yet. When your baby cries, Nila plays a sound and then " +
                        "checks whether the crying actually settled. After a few nights " +
                        "this list means something.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            } else {
                val best = stats.filter { it.attempts > 0 }.maxByOrNull { it.score }
                stats.sortedByDescending { it.score }.forEach { stat ->
                    SootherRow(stat, best = stat.id == best?.id)
                }
                best?.let {
                    TakeawayStrip(
                        "${readableSoother(it.id)} has the best record with this baby, so " +
                            "it is what Nila reaches for first.",
                        tone = Tone.TEAL,
                        icon = Icons.Outlined.CheckCircle,
                    )
                }
            }
        
    }
}

@Composable
private fun SootherRow(stat: com.nila.actions.SootheMemory.Stats, best: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val (icon, tone) = sootherLook(stat.id)
    val teal = toneColors(Tone.TEAL).ink
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        IconBadge(icon, tone, size = 44.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(readableSoother(stat.id), style = MaterialTheme.typography.titleSmall,
                     fontWeight = if (best) FontWeight.Bold else FontWeight.SemiBold,
                     modifier = Modifier.weight(1f))
                Text(
                    if (stat.attempts == 0) "untried" else "${stat.successes} of ${stat.attempts}",
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    color = scheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(scheme.surfaceContainerHigh)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(stat.score.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(if (best) teal else teal.copy(alpha = 0.75f))
                )
            }
        }
    }
}

private fun sootherLook(id: String): Pair<ImageVector, Tone> = when (id) {
    "white_noise" -> Icons.Outlined.GraphicEq to Tone.BLUE
    "shush" -> Icons.Outlined.Air to Tone.BLUE
    "heartbeat" -> Icons.Outlined.Favorite to Tone.PINK
    else -> Icons.Outlined.MusicNote to Tone.TEAL
}

// --------------------------------------------------------- the model card

/**
 * The two models, against the only line that matters: chance.
 *
 * Drawn as bars rather than printed as a table because the point is a
 * comparison. One bar is nearly at the top of the chart. The other is *below*
 * the dashed line. That picture is the argument this project is built around,
 * and it is more honest than any sentence we could write under it.
 */
@Composable
private fun ModelQuality() {
    val palette = chartPalette()
    val scheme = MaterialTheme.colorScheme

    CardSection("How well this works", spacing = 16.dp) {
            ColumnChart(
                bars = listOf(
                    Bar("Detection", ModelCard.DETECT_SUBJECT_WISE_AUC,
                        "%.2f".format(ModelCard.DETECT_SUBJECT_WISE_AUC), true),
                    Bar("Cause", ModelCard.REASON_SUBJECT_WISE_AUC,
                        "%.2f".format(ModelCard.REASON_SUBJECT_WISE_AUC)),
                    Bar("Cause, mixed", ModelCard.REASON_LEAKY_AUC,
                        "%.2f".format(ModelCard.REASON_LEAKY_AUC)),
                    Bar("Second model", ModelCard.RF_HONEST_AUC,
                        "%.2f".format(ModelCard.RF_HONEST_AUC)),
                ),
                color = palette.cry,
                ticks = listOf(0f to "0", 0.5f to "0.5", 1f to "1.0"),
                threshold = 0.5f,
                strongAbove = null,
                valueLabels = true,
                labelLines = 2,
                height = 210.dp,
            )
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(18.dp).height(2.dp).background(palette.cry.copy(alpha = 0.7f)))
                Text("Dashed line: chance, 0.5. A model below it is guessing.",
                     style = MaterialTheme.typography.labelMedium,
                     color = scheme.onSurfaceVariant)
            }
            Text(
                "\"Mixed\" is the same cause model tested the common, flawed way, " +
                    "with the same babies in training and test. Scores are area under " +
                    "the curve, on recordings of infants the models never heard " +
                    "during training – the only kind of test that predicts how they " +
                    "behave on your baby.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(toneColors(Tone.BLUE).wash)
                    .height(IntrinsicSize.Min)
                    .padding(vertical = 12.dp),
            ) {
                listOf(
                    "Detection recall" to "%.0f%%".format(ModelCard.DETECT_RECALL_CRY * 100),
                    "Detect clips" to "%,d".format(ModelCard.DETECT_TEST_CLIPS),
                    "Cause clips" to "${ModelCard.REASON_TEST_CLIPS}",
                ).forEachIndexed { i, (label, value) ->
                    if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight()
                                       .background(scheme.outlineVariant))
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(label, style = MaterialTheme.typography.labelMedium,
                             color = scheme.onSurfaceVariant, maxLines = 1)
                        Text(value, style = MaterialTheme.typography.titleLarge
                                 .copy(fontFeatureSettings = "tnum"),
                             fontWeight = FontWeight.Bold)
                    }
                }
            }

            HedgeNote(
                // Parenthesised deliberately: in Kotlin `.format()` binds to the
                // last literal in a `+` chain, so without these the earlier
                // specifiers render as literal "%.2f".
                (
                    "Tested on babies it had never heard, the cause model scored %.2f – " +
                        "below chance. Tested the common way, with the same babies on " +
                        "both sides, it scored %.2f. The %.2f gap was the test, not the " +
                        "model, so Nila tells you your baby is crying and does not " +
                        "pretend to know why."
                    ).format(
                    ModelCard.REASON_SUBJECT_WISE_AUC,
                    ModelCard.REASON_LEAKY_AUC,
                    ModelCard.LEAKAGE_AUC_INFLATION,
                )
            )
            Text("Model card generated ${ModelCard.GENERATED_ON}.",
                 style = MaterialTheme.typography.labelMedium,
                 color = scheme.onSurfaceVariant)
        
    }
}

// -------------------------------------------------------- the silicon

/**
 * What the phone is actually doing, measured on the phone.
 *
 * Two numbers carry this card and neither is quotable from a spec sheet.
 *
 * **Where the graph runs.** The detector is offered to NNAPI first, then the
 * GPU, then the CPU, and each option is proved with a real inference before it
 * is accepted -- a delegate that constructs successfully can still fail at
 * first invoke, and 3am in a foreground service is the wrong time to find out.
 * The chip below is whichever one survived that, on this device.
 *
 * **How little of the night reaches it.** Below the silence gate a window is a
 * dBFS comparison and nothing else: no log-mel, no inference, no NPU. In a
 * quiet nursery that is almost all of them, and the ratio is why this can run
 * all night on a battery. It is counted rather than estimated.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnThisPhone(
    monitor: com.nila.monitor.MonitorState,
    selfTest: com.nila.audio.SelfTest.Result?,
    testing: Boolean,
    onRunTest: () -> Unit,
) {
    val palette = chartPalette()
    val scheme = MaterialTheme.colorScheme

    val accelerator = monitor.accelerator.takeIf { it != "-" }
        ?: selfTest?.accelerator?.takeIf { it != "-" }
    val seen = monitor.windowsSeen
    val inferred = monitor.windowsInferred
    val dutyCycle = if (seen > 0) (inferred * 100f / seen) else null

    CardSection("Running on this phone", spacing = 12.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TonePill(accelerator?.let(::acceleratorLabel) ?: "Not measured yet",
                             Tone.TEAL, icon = Icons.Outlined.Speed)
                    TonePill("On device", Tone.TEAL)
                    TonePill("Offline", Tone.TEAL)
                }

                if (monitor.latencySamples > 0) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("DETECTOR LATENCY, THIS SESSION",
                             style = MaterialTheme.typography.labelMedium,
                             color = scheme.onSurfaceVariant)
                        ColumnChart(
                            bars = listOf(
                                Bar("mean", monitor.detectorLatencyMs.toFloat(),
                                    "%.1f ms".format(monitor.detectorLatencyMs)),
                                Bar("p50", monitor.detectorP50Ms.toFloat(),
                                    "%.1f ms".format(monitor.detectorP50Ms)),
                                Bar("p95", monitor.detectorP95Ms.toFloat(),
                                    "%.1f ms".format(monitor.detectorP95Ms), true),
                            ),
                            color = palette.feed,
                            ticks = listOf(0f to "0"),
                            strongAbove = null,
                            valueLabels = true,
                            height = 130.dp,
                        )
                        Text(
                            "Over ${monitor.latencySamples} inferences. One analysis " +
                                "window arrives every 480 ms, so a p95 of " +
                                "%.1f ms leaves the detector idle %.1f%% of the time."
                                    .format(
                                        monitor.detectorP95Ms,
                                        100.0 - (monitor.detectorP95Ms / 480.0 * 100.0),
                                    ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurface,
                        )
                    }
                }

                if (dutyCycle != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("HOW MUCH OF THE ROOM REACHES THE DETECTOR",
                             style = MaterialTheme.typography.labelMedium,
                             color = scheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MetricTile(
                                label = "Windows analysed",
                                value = "%.0f%%".format(dutyCycle),
                                caption = "$inferred of $seen",
                                accent = palette.feed,
                                modifier = Modifier.weight(1f),
                            )
                            MetricTile(
                                label = "Skipped at the gate",
                                value = "%.0f%%".format(100f - dutyCycle),
                                caption = "a dBFS compare, no inference",
                                accent = palette.sleep,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                selfTest?.let {
                    Text(
                        it.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                    )
                }

                OutlinePillButton(
                    if (testing) "Measuring..." else "Run the detector on this phone",
                    onRunTest,
                    Modifier.fillMaxWidth(),
                    icon = Icons.Outlined.Speed,
                    enabled = !testing,
                )

                // Gone once anything has actually been measured, including by
                // the button directly above it -- an invitation to measure,
                // printed under a measurement, reads as a card that ignored
                // the thing it just did.
                if (monitor.latencySamples == 0 && dutyCycle == null && selfTest == null) {
                    Text(
                        "Start monitoring, or run the check above, and this card " +
                            "fills in with numbers measured on this device rather " +
                            "than quoted from a datasheet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        
        HedgeNote(
            "Nothing on this screen was measured anywhere but here. The latency is " +
                "this phone's, the accelerator is whichever one accepted the graph " +
                "on this phone, and the model scores are our own evaluation shipped " +
                "inside the app so they cannot drift from what we claim."
        )
    }
}

/** `NNAPI` is a framework; what a reader wants to know is which silicon. */
private fun acceleratorLabel(name: String): String = when (name) {
    "NNAPI" -> "NNAPI – NPU or DSP"
    "GPU" -> "GPU delegate"
    "CPU" -> "CPU, XNNPACK"
    else -> name
}

private fun readableSoother(id: String) = when (id) {
    "white_noise" -> "White noise"
    "shush" -> "Shushing"
    "heartbeat" -> "Heartbeat"
    else -> "Your voice"
}
