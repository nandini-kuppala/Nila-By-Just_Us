package com.nila.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The dashboard's drawing kit.
 *
 * Four marks, and a rule that binds all of them: **no chart here is allowed to
 * be the only place a number appears.** Every one of them sits under a line of
 * text that says the same thing in words, and every bar can be tapped for its
 * own figure. That is not a nicety -- this screen is read at three in the
 * morning by somebody who is not going to squint at a wedge and estimate a
 * percentage, and it is the difference between a dashboard that informs and
 * one that decorates.
 *
 * They are Canvas rather than a charting library for the ordinary reasons: the
 * app ships no dependency it does not need, and the shapes are four rectangles
 * and an arc.
 */

// ------------------------------------------------------------------ bars

data class Bar(
    val label: String,
    val value: Float,
    /** What the value reads as in words, for the tap-out and the talkback. */
    val readout: String,
    val emphasised: Boolean = false,
)

/**
 * A week as columns, with an optional threshold line across it.
 *
 * The threshold is the whole reason this is a bar chart rather than a line: the
 * question a parent has about the colic criterion is not "is the trend up", it
 * is "how many days crossed the line", and columns against a rule answer that
 * by counting rather than by reading a slope.
 */
@Composable
fun WeekBars(
    bars: List<Bar>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 132.dp,
    threshold: Float? = null,
    thresholdLabel: String? = null,
    selected: Int? = null,
    onSelect: (Int) -> Unit = {},
) {
    if (bars.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val track = scheme.surfaceContainerHighest
    val rule = scheme.outline
    val top = max(bars.maxOf { it.value }, threshold ?: 0f).coerceAtLeast(1f) * 1.15f
    val description = bars.joinToString(", ") { "${it.label} ${it.readout}" }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = description }
                .pointerInput(bars.size) {
                    detectTapGestures { offset ->
                        val slot = size.width / bars.size.toFloat()
                        onSelect((offset.x / slot).toInt().coerceIn(0, bars.lastIndex))
                    }
                }
        ) {
            val slot = size.width / bars.size
            val barW = slot * 0.46f
            val h = size.height

            drawLine(rule.copy(alpha = 0.35f), Offset(0f, h), Offset(size.width, h),
                     strokeWidth = 1.5f)

            threshold?.let { t ->
                val y = h - (t / top) * h
                drawLine(
                    color = rule,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 9f)),
                )
            }

            bars.forEachIndexed { i, bar ->
                val x = i * slot + (slot - barW) / 2f
                val barH = ((bar.value / top) * h).coerceAtLeast(if (bar.value > 0f) 4f else 2f)
                // The empty day still gets a stub. A gap in a row of columns
                // reads as missing data; a flat stub reads as a quiet day, and
                // those are different things to tell a parent.
                drawRoundRect(
                    color = if (bar.value > 0f) track else track.copy(alpha = 0.6f),
                    topLeft = Offset(x, 0f),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(barW / 2.4f),
                )
                drawRoundRect(
                    color = if (i == selected || bar.emphasised) color
                            else color.copy(alpha = 0.62f),
                    topLeft = Offset(x, h - barH),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(barW / 2.4f),
                )
                if (i == selected) {
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(x - 3f, -3f),
                        size = Size(barW + 6f, h + 6f),
                        cornerRadius = CornerRadius(barW / 2f),
                        style = Stroke(width = 2f),
                    )
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            bars.forEachIndexed { i, bar ->
                Text(
                    text = bar.label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (i == selected) scheme.onSurface else scheme.onSurfaceVariant,
                )
            }
        }

        thresholdLabel?.let {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DashSwatch(rule)
                Text(it, style = MaterialTheme.typography.labelMedium,
                     color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DashSwatch(color: Color) {
    Canvas(modifier = Modifier.width(16.dp).height(2.dp)) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2),
            end = Offset(size.width, size.height / 2),
            strokeWidth = size.height,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)),
        )
    }
}

// ----------------------------------------------------------------- donut

data class Wedge(val label: String, val value: Float, val color: Color, val readout: String)

/**
 * Shares as a ring, with the total in the hole.
 *
 * A ring rather than a pie because the middle is the most valuable space in the
 * figure and a pie wastes it: the number in the hole is what most readers came
 * for, and the wedges are the breakdown of it.
 *
 * Every wedge is also a labelled row underneath with its own figure, so nobody
 * has to judge an angle. Wedges below a couple of percent are still drawn --
 * they are a real thing that happened a small number of times -- but they get
 * their reading from the row, not from the arc.
 */
@Composable
fun DonutChart(
    wedges: List<Wedge>,
    centreValue: String,
    centreLabel: String,
    modifier: Modifier = Modifier,
    diameter: Dp = 140.dp,
) {
    val total = wedges.sumOf { it.value.toDouble() }.toFloat()
    if (total <= 0f) return
    val scheme = MaterialTheme.colorScheme
    val sweep by animateFloatAsState(
        targetValue = 1f, animationSpec = tween(600), label = "donut",
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(diameter)) {
                val stroke = size.minDimension * 0.2f
                val inset = stroke / 2f
                var start = -90f
                wedges.forEach { wedge ->
                    val angle = wedge.value / total * 360f * sweep
                    drawArc(
                        color = wedge.color,
                        startAngle = start,
                        sweepAngle = (angle - 2f).coerceAtLeast(0.6f),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Butt),
                    )
                    start += angle
                }
            }
            Column(
                // Constrained to the hole. Unbounded, a two-word label sets its
                // own width and prints straight over the ring it is sitting in.
                modifier = Modifier.width(diameter * 0.58f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    centreValue,
                    style = MaterialTheme.typography.headlineMedium
                        .copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface,
                )
                Text(
                    centreLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 13.sp,
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            wedges.forEach { wedge ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier.size(10.dp).clip(CircleShape)
                            .background(wedge.color)
                    )
                    Text(
                        wedge.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                        // One line, shrunk if it has to be. The alternative is
                        // what this did at first: "Discomfort" broken across
                        // two lines mid-word, in a legend whose whole job is to
                        // be read at a glance.
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        wedge.readout,
                        style = MaterialTheme.typography.labelLarge
                            .copy(fontFeatureSettings = "tnum"),
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------- line chart

data class Point(
    val label: String,
    /** Null where nothing was logged: the line breaks rather than dropping to zero. */
    val value: Float?,
    val readout: String,
    /** What the tooltip calls this point. "Today", "Wednesday". */
    val title: String = label,
    val emphasised: Boolean = false,
)

/**
 * A series as a curve through its points, with the figure on the point you touch.
 *
 * Bars and a curve answer different questions, and sleep is the second kind. A
 * bar chart asks "how much", one column at a time, and that is right for crying
 * measured against a three-hour rule. Sleep has no threshold worth drawing for a
 * four-month-old, and the only question a parent actually has about it is
 * whether it is getting better or worse. That is a slope.
 *
 * ### Why it is touchable rather than labelled
 *
 * Seven values printed along a chart is seven numbers competing with the shape,
 * and the shape is the whole reason the chart exists. Putting the figure under
 * the finger instead gives both: the curve reads as a curve, and any point
 * gives up its exact hours on contact. Dragging moves the readout, so comparing
 * Tuesday with Saturday is one gesture rather than two taps and a memory.
 *
 * ### Why the line breaks
 *
 * A day with nothing logged is a hole, not a zero. "We forgot to tap" and "she
 * did not sleep" are wildly different facts, and a curve drawn straight through
 * the gap asserts the second one.
 */
@Composable
fun LineChart(
    points: List<Point>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 168.dp,
    valueSuffix: String = "",
    /** A reference line across the plot -- a rule, not a target. */
    threshold: Float? = null,
    thresholdLabel: String? = null,
    selected: Int? = null,
    onSelect: (Int?) -> Unit = {},
) {
    if (points.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val known = points.mapNotNull { it.value }
    if (known.isEmpty()) return

    val top = niceTop(maxOf(known.max(), threshold ?: 0f))
    val grid = scheme.outlineVariant
    // Sized for what this chart actually measures: three axis labels, seven
    // day labels and two lines of tooltip. The default cache holds eight, so a
    // drag across the plot thrashed it and re-shaped text on every frame.
    val measurer = rememberTextMeasurer(cacheSize = 24)
    val description = points.joinToString(", ") { "${it.label} ${it.readout}" }

    val reveal by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "sleep-line",
    )

    val tipStyle = MaterialTheme.typography.titleSmall
        .copy(color = scheme.surfaceContainerLowest, fontWeight = FontWeight.Bold)
    val tipSubStyle = MaterialTheme.typography.labelSmall
        .copy(color = scheme.surfaceContainerLowest.copy(alpha = 0.85f))
    val axisStyle = MaterialTheme.typography.labelSmall
        .copy(color = scheme.onSurfaceVariant, fontFeatureSettings = "tnum")
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = scheme.onSurfaceVariant)
    val labelStyleOn = labelStyle.copy(color = scheme.onSurface, fontWeight = FontWeight.Bold)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .pointerInput(points.size) {
                // Tap and drag share one handler: a scrubber that only answers
                // to discrete taps is a scrubber nobody discovers is draggable.
                detectHorizontalDragGestures(
                    onDragEnd = { },
                    onDragStart = { offset -> onSelect(indexAt(offset.x, size.width, points.size)) },
                ) { change, _ ->
                    onSelect(indexAt(change.position.x, size.width, points.size))
                }
            }
            .pointerInput(points.size) {
                detectTapGestures { offset ->
                    val hit = indexAt(offset.x, size.width, points.size)
                    onSelect(if (hit == selected) null else hit)
                }
            }
    ) {
        val axisW = 34.dp.toPx()
        val labelH = 20.dp.toPx()
        val tipH = 52.dp.toPx()
        val plotLeft = axisW
        val plotRight = size.width
        val plotTop = tipH
        val plotBottom = size.height - labelH
        val plotW = plotRight - plotLeft
        val plotH = plotBottom - plotTop
        val slot = plotW / points.size

        fun x(i: Int) = plotLeft + slot * i + slot / 2f
        fun y(v: Float) = plotBottom - (v / top) * plotH

        // ---- grid and axis
        listOf(1f, 0.5f, 0f).forEach { f ->
            val gy = plotBottom - f * plotH
            drawLine(grid, Offset(plotLeft, gy), Offset(plotRight, gy), strokeWidth = 1.dp.toPx(),
                     pathEffect = if (f == 0f) null
                                  else PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
            val text = measurer.measure(AnnotatedString("${fmt(top * f)}$valueSuffix"), axisStyle)
            drawText(
                text,
                topLeft = Offset(
                    plotLeft - 8.dp.toPx() - text.size.width,
                    gy - text.size.height / 2f,
                ),
            )
        }

        threshold?.let { t ->
            val ty = y(t)
            drawLine(
                color = scheme.outline,
                start = Offset(plotLeft, ty),
                end = Offset(plotRight, ty),
                strokeWidth = 1.5.dp.toPx() / 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 9f)),
            )
            thresholdLabel?.let { label ->
                val text = measurer.measure(AnnotatedString(label), axisStyle)
                drawText(
                    text,
                    topLeft = Offset(
                        plotRight - text.size.width,
                        ty - text.size.height - 3.dp.toPx(),
                    ),
                )
            }
        }

        // ---- the curve, in unbroken runs
        val runs = mutableListOf<MutableList<Offset>>()
        points.forEachIndexed { i, point ->
            val v = point.value
            if (v == null) {
                if (runs.lastOrNull()?.isNotEmpty() == true) runs.add(mutableListOf())
            } else {
                if (runs.isEmpty()) runs.add(mutableListOf())
                runs.last().add(Offset(x(i), y(v)))
            }
        }

        runs.filter { it.size >= 2 }.forEach { run ->
            val line = smoothPath(run)
            // The wash under the curve. It is what makes a line chart read as a
            // quantity rather than as a wire, and it fades out downward so it
            // never competes with the gridlines it crosses.
            val area = Path().apply {
                addPath(line)
                lineTo(run.last().x, plotBottom)
                lineTo(run.first().x, plotBottom)
                close()
            }
            clipRect(right = plotLeft + plotW * reveal) {
                drawPath(
                    area,
                    brush = Brush.verticalGradient(
                        listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0.02f)),
                        startY = plotTop,
                        endY = plotBottom,
                    ),
                )
                drawPath(
                    line, color,
                    style = Stroke(width = 3.5.dp.toPx() / 2f, cap = StrokeCap.Round,
                                   join = StrokeJoin.Round),
                )
            }
        }
        runs.filter { it.size == 1 }.forEach { run ->
            // A single logged day between two gaps still deserves to exist.
            drawCircle(color, radius = 5.dp.toPx() / 2f, center = run.first())
        }

        // ---- the points
        points.forEachIndexed { i, point ->
            val v = point.value ?: return@forEachIndexed
            if (x(i) > plotLeft + plotW * reveal) return@forEachIndexed
            val centre = Offset(x(i), y(v))
            val on = i == selected
            if (on) {
                drawLine(color.copy(alpha = 0.35f), Offset(centre.x, plotTop),
                         Offset(centre.x, plotBottom), strokeWidth = 1.5.dp.toPx() / 2f)
                drawCircle(color.copy(alpha = 0.18f), radius = 11.dp.toPx() / 2f, center = centre)
            }
            val r = if (on || point.emphasised) 5.dp.toPx() / 2f else 4.dp.toPx() / 2f
            // A ring rather than a disc: the surface-coloured middle keeps the
            // point legible where the curve passes behind it.
            drawCircle(color, radius = r, center = centre)
            drawCircle(scheme.surfaceContainerLowest, radius = r - 1.5.dp.toPx() / 2f,
                       center = centre)
            if (on || point.emphasised) {
                drawCircle(color, radius = r - 3.dp.toPx() / 2f, center = centre)
            }
        }

        // ---- day labels
        points.forEachIndexed { i, point ->
            val text = measurer.measure(
                AnnotatedString(point.label),
                if (i == selected) labelStyleOn else labelStyle,
            )
            drawText(
                text,
                topLeft = Offset(
                    x(i) - text.size.width / 2f,
                    plotBottom + (labelH - text.size.height) / 2f + 2.dp.toPx(),
                ),
            )
        }

        // ---- the readout, over the point it belongs to
        val chosen = selected?.let(points::getOrNull)
        if (chosen?.value != null) {
            val title = measurer.measure(AnnotatedString(chosen.readout), tipStyle)
            val sub = measurer.measure(AnnotatedString(chosen.title), tipSubStyle)
            val padX = 10.dp.toPx()
            val padY = 6.dp.toPx()
            val w = maxOf(title.size.width, sub.size.width) + padX * 2
            val h = title.size.height + sub.size.height + padY * 2
            // Clamped to the plot, so the reading for Sunday is not half off
            // the right-hand edge of the card.
            val left = (x(selected) - w / 2f).coerceIn(plotLeft, plotRight - w)
            val topY = (y(chosen.value) - h - 12.dp.toPx()).coerceAtLeast(0f)
            drawRoundRect(
                color = color,
                topLeft = Offset(left, topY),
                size = Size(w, h),
                cornerRadius = CornerRadius(10.dp.toPx()),
            )
            val pointer = Path().apply {
                val px = x(selected)
                moveTo(px - 5.dp.toPx(), topY + h - 1f)
                lineTo(px + 5.dp.toPx(), topY + h - 1f)
                lineTo(px, topY + h + 5.dp.toPx())
                close()
            }
            drawPath(pointer, color)
            drawText(title, topLeft = Offset(left + (w - title.size.width) / 2f, topY + padY))
            drawText(
                sub,
                topLeft = Offset(
                    left + (w - sub.size.width) / 2f,
                    topY + padY + title.size.height,
                ),
            )
        }
    }
}

/** Which point a horizontal position belongs to. */
private fun indexAt(x: Float, width: Int, count: Int): Int {
    val axis = width * 0.09f
    val slot = (width - axis) / count
    return (((x - axis) / slot).toInt()).coerceIn(0, count - 1)
}

/**
 * A Catmull-Rom spline, converted to the cubic Béziers a Path can draw.
 *
 * Straight segments between seven daily points make a zig-zag that reads as
 * noise; a curve reads as a trend, which is what a week of sleep is. The
 * tension is fixed at the standard 1/6 so the curve passes exactly through
 * every measured point -- a smoothing that moved the points would be drawing
 * numbers nobody recorded.
 */
private fun smoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    if (points.size == 2) {
        path.lineTo(points[1].x, points[1].y)
        return path
    }
    for (i in 0 until points.size - 1) {
        val p0 = points[(i - 1).coerceAtLeast(0)]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points[(i + 2).coerceAtMost(points.size - 1)]
        path.cubicTo(
            p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
            p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
            p2.x, p2.y,
        )
    }
    return path
}

/**
 * A ceiling that produces axis labels somebody would write by hand.
 *
 * The obvious `max * 1.2` gave "16h" at the top and "7.8h" halfway, which is
 * not a number anyone has ever put on an axis. Rounding up to an even multiple
 * of a sensible step keeps the midpoint whole too, which is the only reason the
 * midpoint is worth printing at all.
 */
private fun niceTop(max: Float): Float {
    val step = when {
        max < 5f -> 1f
        max < 20f -> 2f
        max < 50f -> 5f
        else -> 10f
    }
    val pair = step * 2f
    return (kotlin.math.ceil(max * 1.1f / pair) * pair).coerceAtLeast(pair)
}

private fun fmt(v: Float): String =
    if (v == v.toInt().toFloat()) v.roundToInt().toString() else "%.1f".format(v)

// -------------------------------------------------------------- hour arc// -------------------------------------------------------------- hour arc

/**
 * Crying by hour of the day, averaged over the window.
 *
 * The rhythm track shows one day; this shows whether that day was typical. A
 * cluster that appears in both is a routine. A cluster in one and not the other
 * is a bad night, and telling those apart is most of what a parent wants from
 * a week of data.
 */
@Composable
fun HourProfile(
    minutesPerHour: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 84.dp,
) {
    if (minutesPerHour.size != 24) return
    val top = (minutesPerHour.maxOrNull() ?: 0f).coerceAtLeast(0.5f)
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            val slot = size.width / 24f
            val barW = slot * 0.62f
            minutesPerHour.forEachIndexed { hour, minutes ->
                val barH = (minutes / top * size.height).coerceAtLeast(2f)
                drawRoundRect(
                    color = color.copy(alpha = 0.3f + 0.7f * (minutes / top).coerceIn(0f, 1f)),
                    topLeft = Offset(hour * slot + (slot - barW) / 2f, size.height - barH),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(barW / 2.5f),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("00", "06", "12", "18", "23").forEachIndexed { i, label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    textAlign = when (i) {
                        0 -> TextAlign.Start
                        4 -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ------------------------------------------------------------- stat tile

/**
 * A number with its unit, its meaning and a colour that ties it to a chart.
 *
 * The colour is the tile's only ornament and it is load-bearing: the teal on
 * the feeds tile is the same teal as the feed ticks on the track below it, so
 * the eye can get from "seven feeds" to where those seven were without a
 * legend.
 */
@Composable
fun MetricTile(
    label: String,
    value: String,
    caption: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(accent.copy(alpha = 0.07f))
            .border(1.dp, accent.copy(alpha = 0.16f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(accent))
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall
                .copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Bold,
            color = scheme.onSurface,
        )
        Text(
            caption,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
    }
}

/** `3h 20m`, or `45m` below the hour. The unit is always attached. */
fun humanMinutes(minutes: Int): String = when {
    minutes <= 0 -> "0m"
    minutes < 60 -> "${minutes}m"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes / 60}h ${minutes % 60}m"
}

/** A share, rounded once so the rows always add to something sensible. */
fun percent(part: Float, whole: Float): Int =
    if (whole <= 0f) 0 else (part / whole * 100f).roundToInt()

// ---------------------------------------------------------- column chart

/**
 * A week of columns on a labelled axis, with a threshold and a tap-out reading.
 *
 * The successor to [WeekBars] for the dashboard: it carries its own y-axis, so
 * "3h" is printed where three hours is rather than left to a legend, and the
 * columns that cross the threshold are drawn at full strength while the rest
 * stay pale. That contrast is the chart's argument -- *how many days crossed
 * the line* -- made without a word.
 *
 * @param ticks axis values with the label to print for each, bottom to top.
 * @param strongAbove columns at or over this are drawn at full strength. Null
 *   draws every column pale except the selected one.
 * @param valueLabels print each column's [Bar.readout] above it, for a chart
 *   short enough to label every value (the model card).
 */
@Composable
fun ColumnChart(
    bars: List<Bar>,
    color: Color,
    ticks: List<Pair<Float, String>>,
    modifier: Modifier = Modifier,
    height: Dp = 176.dp,
    threshold: Float? = null,
    thresholdLabel: String? = null,
    strongAbove: Float? = threshold,
    valueLabels: Boolean = false,
    selected: Int? = null,
    onSelect: ((Int?) -> Unit)? = null,
    labelLines: Int = 1,
) {
    if (bars.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val top = maxOf(
        ticks.maxOfOrNull { it.first } ?: 0f,
        bars.maxOf { it.value },
        threshold ?: 0f,
    ).coerceAtLeast(0.0001f)
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val description = bars.joinToString(", ") { "${it.label} ${it.readout}" }
    val grow by animateFloatAsState(1f, tween(650, easing = FastOutSlowInEasing),
                                    label = "columns")

    val axisStyle = MaterialTheme.typography.labelSmall
        .copy(color = scheme.onSurfaceVariant, fontFeatureSettings = "tnum")
    val dayStyle = MaterialTheme.typography.labelMedium.copy(color = scheme.onSurfaceVariant,
                                                              textAlign = TextAlign.Center)
    val dayStyleOn = dayStyle.copy(color = scheme.onSurface, fontWeight = FontWeight.Bold)
    val valueStyle = MaterialTheme.typography.labelLarge
        .copy(color = scheme.onSurface, fontWeight = FontWeight.SemiBold,
              fontFeatureSettings = "tnum")
    val tipStyle = MaterialTheme.typography.labelLarge
        .copy(color = scheme.surfaceContainerLowest, fontWeight = FontWeight.Bold,
              fontFeatureSettings = "tnum")
    val grid = scheme.outlineVariant

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .then(
                if (onSelect == null) Modifier else Modifier.pointerInput(bars.size, selected) {
                    detectTapGestures { offset ->
                        val axisW = 36.dp.toPx()
                        val slot = (size.width - axisW) / bars.size
                        val hit = ((offset.x - axisW) / slot).toInt()
                            .coerceIn(0, bars.lastIndex)
                        onSelect(if (hit == selected) null else hit)
                    }
                }
            )
    ) {
        val axisW = 36.dp.toPx()
        val labelH = (if (labelLines > 1) 34.dp else 22.dp).toPx()
        val headroom = (if (valueLabels) 22.dp else 28.dp).toPx()
        val plotLeft = axisW
        val plotTop = headroom
        val plotBottom = size.height - labelH
        val plotH = plotBottom - plotTop
        val slot = (size.width - plotLeft) / bars.size
        val barW = (slot * 0.5f).coerceAtMost(30.dp.toPx())
        fun y(v: Float) = plotBottom - (v / top) * plotH

        // Grid and axis labels.
        ticks.forEach { (value, label) ->
            val gy = y(value)
            drawLine(
                grid, Offset(plotLeft, gy), Offset(size.width, gy), strokeWidth = 1.dp.toPx(),
                pathEffect = if (value == 0f) null
                             else PathEffect.dashPathEffect(floatArrayOf(6f, 8f)),
            )
            val t = measurer.measure(AnnotatedString(label), axisStyle)
            drawText(t, topLeft = Offset(plotLeft - 8.dp.toPx() - t.size.width,
                                         gy - t.size.height / 2f))
        }

        threshold?.let { t ->
            val ty = y(t)
            drawLine(
                color.copy(alpha = 0.7f), Offset(plotLeft, ty), Offset(size.width, ty),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
            )
            thresholdLabel?.let { label ->
                val text = measurer.measure(AnnotatedString(label), axisStyle)
                drawText(text, topLeft = Offset(size.width - text.size.width,
                                                ty - text.size.height - 3.dp.toPx()))
            }
        }

        bars.forEachIndexed { i, bar ->
            val cx = plotLeft + slot * i + slot / 2f
            val h = ((bar.value / top) * plotH * grow)
                .coerceAtLeast(if (bar.value > 0f) 3.dp.toPx() else 0f)
            val strong = i == selected ||
                (selected == null && strongAbove != null && bar.value >= strongAbove) ||
                (selected == null && strongAbove == null && bar.emphasised)
            val fill = if (strong) color else color.copy(alpha = 0.38f)
            if (h > 0f) {
                // Rounded at the top only, and never more than the bar is tall:
                // a near-zero day is a short stub, not a blob.
                val r = minOf(6.dp.toPx(), h)
                val path = Path().apply {
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            left = cx - barW / 2f, top = plotBottom - h,
                            right = cx + barW / 2f, bottom = plotBottom,
                            topLeftCornerRadius = CornerRadius(r),
                            topRightCornerRadius = CornerRadius(r),
                            bottomLeftCornerRadius = CornerRadius(0f),
                            bottomRightCornerRadius = CornerRadius(0f),
                        )
                    )
                }
                drawPath(path, fill)
            }
            if (valueLabels) {
                val t = measurer.measure(AnnotatedString(bar.readout), valueStyle)
                var labelTop = plotBottom - h - t.size.height - 4.dp.toPx()
                // A label that would sit on the threshold line is lifted clear
                // above it, so "0.44" never reads as struck through.
                threshold?.let { th ->
                    val ty = y(th)
                    if (labelTop + t.size.height > ty - 2.dp.toPx() && labelTop < ty + 2.dp.toPx()) {
                        labelTop = ty - t.size.height - 3.dp.toPx()
                    }
                }
                drawText(t, topLeft = Offset(cx - t.size.width / 2f, labelTop))
            }
            val day = measurer.measure(
                AnnotatedString(bar.label),
                if (i == selected) dayStyleOn else dayStyle,
                constraints = androidx.compose.ui.unit.Constraints(
                    maxWidth = slot.toInt().coerceAtLeast(1)),
            )
            drawText(day, topLeft = Offset(cx - day.size.width / 2f, plotBottom + 6.dp.toPx()))
        }

        // The reading over the chosen column, pointing at it.
        val chosen = selected?.let(bars::getOrNull)
        if (chosen != null && !valueLabels) {
            val i = selected
            val cx = plotLeft + slot * i + slot / 2f
            val h = (chosen.value / top) * plotH
            val t = measurer.measure(AnnotatedString(chosen.readout), tipStyle)
            val padX = 10.dp.toPx()
            val padY = 5.dp.toPx()
            val w = t.size.width + padX * 2
            val hh = t.size.height + padY * 2
            val left = (cx - w / 2f).coerceIn(plotLeft, size.width - w)
            val tipTop = (plotBottom - h - hh - 9.dp.toPx()).coerceAtLeast(0f)
            drawRoundRect(color, topLeft = Offset(left, tipTop), size = Size(w, hh),
                          cornerRadius = CornerRadius(hh / 2f))
            val pointer = Path().apply {
                moveTo(cx - 5.dp.toPx(), tipTop + hh - 1f)
                lineTo(cx + 5.dp.toPx(), tipTop + hh - 1f)
                lineTo(cx, tipTop + hh + 5.dp.toPx())
                close()
            }
            drawPath(pointer, color)
            drawText(t, topLeft = Offset(left + padX, tipTop + padY))
        }
    }
}

/**
 * Four or five tiny columns, for the corner of a stat tile: the shape of the
 * last few days, with no axis. The last one, today, is drawn darkest.
 */
@Composable
fun MiniBars(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (values.isEmpty() || values.all { it <= 0f }) return
    val top = values.max().coerceAtLeast(0.0001f)
    Canvas(modifier = modifier.size(width = 44.dp, height = 28.dp)) {
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (values.size - 1)) / values.size
        values.forEachIndexed { i, v ->
            val h = (v / top * size.height).coerceAtLeast(4.dp.toPx())
            drawRoundRect(
                color = if (i == values.lastIndex) color else color.copy(alpha = 0.35f),
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2f),
            )
        }
    }
}
