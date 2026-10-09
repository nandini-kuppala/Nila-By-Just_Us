package com.nila.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PriorityHigh
import androidx.compose.material.icons.outlined.QuestionMark
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SentimentDissatisfied
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.nila.data.Severity
import com.nila.monitor.EpisodePipeline
import com.nila.monitor.EpisodePipeline.Attempt
import com.nila.monitor.EpisodePipeline.Mark
import com.nila.monitor.EpisodePipeline.NextUp
import com.nila.monitor.EpisodePipeline.Upcoming
import com.nila.monitor.Escalation
import com.nila.ui.theme.Tone
import com.nila.ui.theme.calmColors
import com.nila.ui.theme.chartPalette
import com.nila.ui.theme.severityColors
import com.nila.ui.theme.toneColors

/**
 * A cry in progress, drawn as a plan rather than a log.
 *
 * Three questions decide whether a parent trusts the ladder while it runs, and
 * the old seven-row stepper answered none of them at a glance: *what is Nila
 * doing now*, *what will it do next, and when*, and *did the thing it tried
 * work*. These components answer them in that order -- a four-step journey
 * with the second each step is due, a countdown to the next one, and each
 * sound drawn on the cry's own loudness so "it didn't help" is something the
 * reader can see rather than something the app asserts.
 *
 * The seven technical rungs are still one tap away, under the journey. Nothing
 * here replaces the evidence; it puts the evidence in an order a tired person
 * can read.
 */

/** `1:05`. Every time on these components is in minutes and seconds. */
fun clockOf(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

// --------------------------------------------------------------- journey

private enum class NodeState { DONE, NOW, PENDING, HELPED, FAILED, ALERT }

private data class Node(
    val label: String,
    val atSeconds: Int,
    val icon: ImageVector,
    val state: NodeState,
)

/**
 * Heard, Soothe, Check, Wake you: the ladder as the four things a parent would
 * name, each at the second it fired or is due.
 *
 * Equal spacing rather than to scale. To scale, the first twenty seconds would
 * be a sliver and the ninety-second alert a far wall; evenly spaced, the next
 * step is always visibly *next*, and the fill between two nodes shows how far
 * the wait between them has run.
 */
@Composable
fun CryJourney(
    seconds: Int,
    history: List<Mark>,
    settling: Boolean,
    verifying: Boolean,
    modifier: Modifier = Modifier,
    config: Escalation.Config = Escalation.Config(),
) {
    val scheme = MaterialTheme.colorScheme
    val tried = EpisodePipeline.attempts(history)
    val verdict = history.firstOrNull { it.stage == EpisodePipeline.Stage.VERDICT }
    val firstTry = tried.firstOrNull()
    val anyHelped = tried.any { it.helped == true }
    val anyFailed = tried.any { it.helped == false }

    val sootheAt = firstTry?.startedAtSeconds ?: config.sootheAfterSeconds
    val checkAt = firstTry?.checkedAtSeconds
        ?: (sootheAt + config.verifyAfterSeconds)
    val wakeAt = verdict?.atSeconds ?: config.verdictSeconds

    val nodes = listOf(
        Node("Heard", 0, Icons.Outlined.Check, NodeState.DONE),
        Node("Soothe", sootheAt, Icons.Outlined.MusicNote, when {
            settling && tried.size <= 1 -> NodeState.NOW
            tried.isNotEmpty() -> NodeState.DONE
            else -> NodeState.PENDING
        }),
        Node("Check", checkAt, when {
            anyHelped -> Icons.Outlined.Check
            anyFailed -> Icons.Outlined.Close
            else -> Icons.Outlined.QuestionMark
        }, when {
            verifying -> NodeState.NOW
            anyHelped -> NodeState.HELPED
            anyFailed -> NodeState.FAILED
            else -> NodeState.PENDING
        }),
        Node(if (verdict != null) "Woke you" else "Wake you", wakeAt,
             Icons.Outlined.PriorityHigh,
             if (verdict != null) NodeState.ALERT else NodeState.PENDING),
    )

    // How far along the journey: whole segments for steps passed, and the
    // share of the current wait that has run.
    val times = nodes.map { it.atSeconds }
    val target = when {
        verdict != null -> 3f
        else -> {
            val seg = (0 until 3).lastOrNull { seconds >= times[it] } ?: 0
            val span = (times[seg + 1] - times[seg]).coerceAtLeast(1)
            (seg + ((seconds - times[seg]).toFloat() / span).coerceIn(0f, 1f))
        }
    }
    val progress by animateFloatAsState(target / 3f, tween(400), label = "journey")
    val urgent = severityColors(Severity.URGENT)
    val fill = if (verdict != null) urgent.accent else scheme.primary

    BoxWithConstraints(modifier = modifier.fillMaxWidth().height(64.dp)) {
        val edge = 30.dp
        val span = maxWidth - edge * 2
        Canvas(Modifier.fillMaxWidth().height(24.dp)) {
            val left = edge.toPx()
            val right = size.width - edge.toPx()
            val y = size.height / 2f
            drawLine(scheme.outlineVariant, Offset(left, y), Offset(right, y),
                     strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            drawLine(fill, Offset(left, y), Offset(left + (right - left) * progress, y),
                     strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        }
        nodes.forEachIndexed { i, node ->
            val x = edge + span * (i / 3f)
            Column(
                modifier = Modifier.offset(x = x - 36.dp).width(72.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                JourneyNode(node)
                Text(node.label, style = MaterialTheme.typography.labelMedium,
                     fontWeight = FontWeight.Bold, maxLines = 1,
                     modifier = Modifier.padding(top = 4.dp))
                Text(clockOf(node.atSeconds),
                     style = MaterialTheme.typography.labelSmall
                         .copy(fontFeatureSettings = "tnum"),
                     color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun JourneyNode(node: Node) {
    val scheme = MaterialTheme.colorScheme
    val urgent = severityColors(Severity.URGENT)
    val attention = severityColors(Severity.ATTENTION)
    val (ground, ring, ink) = when (node.state) {
        NodeState.DONE -> Triple(scheme.primary, scheme.primary, scheme.surfaceContainerLowest)
        NodeState.NOW -> Triple(scheme.surfaceContainerLowest, scheme.primary, scheme.primary)
        NodeState.PENDING -> Triple(scheme.surfaceContainerLowest, scheme.outlineVariant,
                                    scheme.outline)
        NodeState.HELPED -> Triple(calmColors().accent, calmColors().accent,
                                   scheme.surfaceContainerLowest)
        NodeState.FAILED -> Triple(attention.accent, attention.accent,
                                   scheme.surfaceContainerLowest)
        NodeState.ALERT -> Triple(urgent.accent, urgent.accent, scheme.surfaceContainerLowest)
    }
    val halo = node.state == NodeState.NOW || node.state == NodeState.ALERT
    Box(
        modifier = Modifier
            .size(24.dp)
            .then(if (halo) Modifier.border(5.dp, ring.copy(alpha = 0.16f), CircleShape)
                  else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(ground)
                .border(2.dp, ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(node.icon, contentDescription = null, tint = ink, modifier = Modifier.size(14.dp))
        }
    }
}

// --------------------------------------------------------------- next up

/**
 * What the ladder will do next, and in how long.
 *
 * The single line that turns the ladder's long waits into a plan: thirty-five
 * seconds of a sound playing with nothing visibly happening reads as a stalled
 * app, and "check whether it helped -- in 0:17" reads as a deliberate one.
 */
@Composable
fun NextUpRow(
    next: NextUp,
    seconds: Int,
    attempts: List<Attempt>,
    keepingClip: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val closing = next.what == Upcoming.CLOSE
    val ink = if (closing) severityColors(Severity.URGENT).accent else scheme.primary
    val ground = if (closing) severityColors(Severity.URGENT).container
                 else toneColors(Tone.TEAL).container
    val open = attempts.lastOrNull { it.helped == null }
    val (title, sub) = when (next.what) {
        Upcoming.SOOTHE -> "Next: play a sound" to "To settle her before waking anyone"
        Upcoming.CHECK -> "Next: check if it helped" to
            "Listens again to ${open?.name?.lowercase() ?: "the sound"}"
        Upcoming.WAKE -> "Next: wake you" to
            (if (attempts.isEmpty()) "If still crying – no sound is available to try"
             else "If still crying, saying what was tried")
        Upcoming.CLOSE -> (if (keepingClip) "Keeping a clip of this cry"
                           else "Wrapping up at ${clockOf(next.atSeconds)}") to
            (if (keepingClip) "Closes at ${clockOf(next.atSeconds)} – the clip stays on this phone"
             else "The steps and the reason are kept")
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainerLowest)
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(ground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (closing) Icons.Filled.FiberManualRecord else Icons.Outlined.Schedule,
                 contentDescription = null, tint = ink, modifier = Modifier.size(17.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium,
                 fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(sub, style = MaterialTheme.typography.labelMedium,
                 color = scheme.onSurfaceVariant, maxLines = 2)
        }
        val left = next.inSeconds(seconds)
        Text(
            if (closing) "${clockOf(left)} left" else "in ${clockOf(left)}",
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Bold,
            color = ink,
            modifier = Modifier
                .clip(CircleShape)
                .background(ground)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

// --------------------------------------------------------------- attempts

/** The tone each attempt is drawn in, by order: the curve's bands and the list agree. */
private fun attemptTone(number: Int): Tone = if (number % 2 == 1) Tone.BLUE else Tone.TEAL

/**
 * Every sound that was tried, and what re-reading the cry said about each.
 *
 * "Measured, not assumed" is the point of the row: the verdict on each sound
 * is the loudness check the ladder ran, not a rating somebody gave it.
 */
@Composable
fun AttemptsCard(
    attempts: List<Attempt>,
    seconds: Int,
    playingNow: Boolean,
    subtitle: String,
    modifier: Modifier = Modifier,
    config: Escalation.Config = Escalation.Config(),
) {
    if (attempts.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    NilaCard(modifier = modifier.fillMaxWidth(), verticalSpacing = 0.dp) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 6.dp)) {
            Text("What Nila tried", style = MaterialTheme.typography.titleMedium,
                 fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(subtitle, style = MaterialTheme.typography.labelMedium,
                 color = scheme.onSurfaceVariant)
        }
        attempts.forEachIndexed { i, attempt ->
            if (i > 0) RowDivider()
            val last = i == attempts.lastIndex
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconBadge(Icons.Outlined.MusicNote, attemptTone(attempt.number),
                          size = 36.dp, iconSize = 19.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(attempt.name, style = MaterialTheme.typography.bodyMedium,
                         fontWeight = FontWeight.SemiBold, maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            attempt.checkedAtSeconds != null ->
                                "${clockOf(attempt.startedAtSeconds)} – " +
                                    clockOf(attempt.checkedAtSeconds) +
                                    if (attempt.helped == false) " · crying stayed as loud" else ""
                            else -> "from ${clockOf(attempt.startedAtSeconds)} · checking at " +
                                clockOf(attempt.startedAtSeconds + config.verifyAfterSeconds)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
                ResultPill(attempt.helped, playing = last && playingNow)
            }
        }
    }
}

@Composable
private fun ResultPill(helped: Boolean?, playing: Boolean) {
    val (text, ground, ink) = when (helped) {
        true -> Triple("Helped", calmColors().container, calmColors().accent)
        false -> Triple("Didn't help", severityColors(Severity.ATTENTION).container,
                        severityColors(Severity.ATTENTION).accent)
        null -> Triple(if (playing) "Playing" else "Checking",
                       toneColors(Tone.SLATE).container, toneColors(Tone.SLATE).ink)
    }
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
         color = ink,
         modifier = Modifier.clip(CircleShape).background(ground)
             .padding(horizontal = 10.dp, vertical = 5.dp))
}

// ------------------------------------------------------------------ curve

/**
 * The cry's loudness, with each sound drawn as a band over the stretch it
 * played.
 *
 * This is the ladder's evidence. Nila judges a sound by re-reading this exact
 * envelope thirty-five seconds after it starts; drawing the sound onto the
 * envelope lets the reader make the same judgement with their own eyes, and
 * see why the app made the one it did.
 */
@Composable
fun EpisodeCurve(
    envelope: List<Int>,
    seconds: Int,
    attempts: List<Attempt>,
    clipSeconds: Float,
    modifier: Modifier = Modifier,
) {
    if (envelope.size < 2 || seconds <= 0) return
    val scheme = MaterialTheme.colorScheme
    val cry = chartPalette().cry
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val bandColors = attempts.map { toneColors(attemptTone(it.number)) }
    val tagStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant,
                                                             fontFeatureSettings = "tnum")

    NilaCard(modifier = modifier.fillMaxWidth(), verticalSpacing = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Crying level", style = MaterialTheme.typography.titleMedium,
                 fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("loud ↑  calm ↓", style = MaterialTheme.typography.labelMedium,
                 color = scheme.onSurfaceVariant)
        }
        Canvas(Modifier.fillMaxWidth().height(118.dp)) {
            val axisH = 16.dp.toPx()
            val top = 4.dp.toPx()
            val bottom = size.height - axisH
            val plotH = bottom - top
            fun x(sec: Int) = (sec.toFloat() / seconds).coerceIn(0f, 1f) * size.width

            // Bands first, under the line.
            attempts.forEachIndexed { i, a ->
                val l = x(a.startedAtSeconds)
                val r = x(a.checkedAtSeconds ?: seconds)
                if (r - l < 1f) return@forEachIndexed
                val colors = bandColors[i]
                drawRoundRect(colors.container.copy(alpha = 0.75f), topLeft = Offset(l, top),
                              size = Size(r - l, plotH), cornerRadius = CornerRadius(6.dp.toPx()))
                drawLine(colors.ink, Offset(l, top), Offset(l, bottom), 1.dp.toPx(),
                         pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                // The result leads the label, so a narrow band that has to
                // truncate the name still shows what the sound did.
                val mark = when (a.helped) { true -> "✓"; false -> "✕"; null -> "♪" }
                val tag = measurer.measure(
                    AnnotatedString("$mark ${a.name}"),
                    tagStyle.copy(color = colors.ink),
                    constraints = Constraints(maxWidth = (r - l - 8.dp.toPx()).toInt()
                        .coerceAtLeast(1)),
                    overflow = TextOverflow.Ellipsis, maxLines = 1,
                )
                drawText(tag, topLeft = Offset(l + 5.dp.toPx(), top + 3.dp.toPx()))
            }

            // The envelope.
            val step = size.width / (envelope.size - 1)
            val path = Path()
            envelope.forEachIndexed { i, v ->
                val px = i * step
                val py = bottom - (v / 100f).coerceIn(0f, 1f) * (plotH - 14.dp.toPx())
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawLine(scheme.outlineVariant, Offset(0f, bottom), Offset(size.width, bottom), 1f)
            drawPath(path, cry, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round,
                                               join = StrokeJoin.Round))
            val lastY = bottom - (envelope.last() / 100f).coerceIn(0f, 1f) * (plotH - 14.dp.toPx())
            drawCircle(cry, 4.dp.toPx(), Offset(size.width - 1f, lastY))

            // Times: the start, each sound, and now -- skipping any that would
            // collide with the one before.
            var lastRight = -1f
            val ticks = listOf(0 to "0:00") +
                attempts.map { it.startedAtSeconds to clockOf(it.startedAtSeconds) }
            ticks.forEach { (sec, label) ->
                val t = measurer.measure(AnnotatedString(label), axisStyle)
                val tx = (x(sec) - if (sec == 0) 0f else t.size.width / 2f)
                    .coerceIn(0f, size.width - t.size.width)
                if (tx > lastRight + 6.dp.toPx() &&
                    tx + t.size.width < size.width - 34.dp.toPx()) {
                    drawText(t, topLeft = Offset(tx, bottom + 2.dp.toPx()))
                    lastRight = tx + t.size.width
                }
            }
            val now = measurer.measure(AnnotatedString("now"), axisStyle)
            drawText(now, topLeft = Offset(size.width - now.size.width, bottom + 2.dp.toPx()))
        }
        if (clipSeconds > 0.5f) {
            Text(
                "Keeping a clip of this cry · ${clipSeconds.toInt()}s of " +
                    "${com.nila.audio.EpisodeRecorder.CAP_SECONDS}s",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------------ guess

/**
 * The cause, as the guess it is.
 *
 * Small on purpose. It used to be a full card titled "Cry reason" with the
 * label at headline size, which gave a 0.44-AUC model the visual weight of a
 * finding. One line, the word "guess" in it, and the runner-up beside it when
 * the two are close.
 */
@Composable
fun BestGuessRow(
    label: String,
    confidence: Float,
    runnerUp: String?,
    modifier: Modifier = Modifier,
) {
    val plum = toneColors(Tone.PLUM)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(plum.wash)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.SentimentDissatisfied, contentDescription = null,
                 tint = plum.ink, modifier = Modifier.size(19.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Best guess: ${label.replace('_', ' ')} · ${(confidence * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = plum.ink,
            )
            Text(
                buildString {
                    append("A guess from the sound, often wrong.")
                    runnerUp?.let { append(" Close to ${it.replace('_', ' ')}.") }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "All 7 steps · 3 done", opening to the full ladder. */
@Composable
fun StepsToggle(done: Int, total: Int, open: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("All $total steps · $done done", style = MaterialTheme.typography.titleSmall,
             fontWeight = FontWeight.SemiBold, color = scheme.primary,
             modifier = Modifier.weight(1f))
        Icon(
            if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (open) "Hide the steps" else "Show every step",
            tint = scheme.primary,
        )
    }
}
