package com.nila.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.drawText
import com.nila.ui.theme.IllBlanket
import com.nila.ui.theme.IllBlanketShade
import com.nila.ui.theme.IllBottle
import com.nila.ui.theme.IllBottleCap
import com.nila.ui.theme.IllBubble
import com.nila.ui.theme.IllBubbleInk
import com.nila.ui.theme.IllCheek
import com.nila.ui.theme.IllFeature
import com.nila.ui.theme.IllHair
import com.nila.ui.theme.IllHeart
import com.nila.ui.theme.IllPhone
import com.nila.ui.theme.IllPill
import com.nila.ui.theme.IllPillAccent
import com.nila.ui.theme.IllPillow
import com.nila.ui.theme.IllSkin
import com.nila.ui.theme.IllSkinShade
import com.nila.ui.theme.IllSpark
import com.nila.ui.theme.IllWood
import com.nila.ui.theme.IllWoodShade
import com.nila.ui.theme.illustrationGround

/**
 * The spot illustrations.
 *
 * Drawn in code rather than shipped as bitmaps: they scale to any density, they
 * cost nothing in the APK, and the ground under them follows the theme. Every
 * one is laid out on a 100 x 100 grid and scaled to the box it is given, so the
 * coordinates below read as percentages.
 *
 * The style is deliberately plain -- flat fills, round shapes, one feature
 * colour -- because these sit beside medical information. They are there to
 * make a screen feel cared for, not to be looked at.
 */

/** Scale a 0..100 coordinate to this canvas. */
private class Grid(val scope: DrawScope) {
    val u = minOf(scope.size.width, scope.size.height) / 100f
    val ox = (scope.size.width - 100f * u) / 2f
    val oy = (scope.size.height - 100f * u) / 2f
    fun p(x: Float, y: Float) = Offset(ox + x * u, oy + y * u)
    fun d(v: Float) = v * u
}

private fun DrawScope.ground(g: Grid, color: Color) {
    // A soft blob rather than a circle: two overlapping rounds read as organic,
    // a single disc reads as an avatar placeholder.
    drawCircle(color, radius = g.d(44f), center = g.p(52f, 54f))
    drawCircle(color, radius = g.d(30f), center = g.p(30f, 40f))
}

private fun DrawScope.heart(g: Grid, cx: Float, cy: Float, s: Float, color: Color = IllHeart) {
    val path = Path().apply {
        moveTo(g.p(cx, cy + s * 0.9f).x, g.p(cx, cy + s * 0.9f).y)
        cubicTo(
            g.p(cx - s * 1.3f, cy + s * 0.1f).x, g.p(cx - s * 1.3f, cy + s * 0.1f).y,
            g.p(cx - s * 0.8f, cy - s * 0.9f).x, g.p(cx - s * 0.8f, cy - s * 0.9f).y,
            g.p(cx, cy - s * 0.3f).x, g.p(cx, cy - s * 0.3f).y,
        )
        cubicTo(
            g.p(cx + s * 0.8f, cy - s * 0.9f).x, g.p(cx + s * 0.8f, cy - s * 0.9f).y,
            g.p(cx + s * 1.3f, cy + s * 0.1f).x, g.p(cx + s * 1.3f, cy + s * 0.1f).y,
            g.p(cx, cy + s * 0.9f).x, g.p(cx, cy + s * 0.9f).y,
        )
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.sparks(g: Grid, cx: Float, cy: Float, color: Color = IllSpark) {
    val stroke = g.d(2.2f)
    listOf(-50f, -5f, 40f).forEach { angle ->
        rotate(angle, pivot = g.p(cx, cy + 10f)) {
            drawLine(color, g.p(cx, cy), g.p(cx, cy + 4.5f), stroke, StrokeCap.Round)
        }
    }
}

/** A baby's face. [awake] opens the eyes; otherwise they are closed, smiling arcs. */
private fun DrawScope.face(
    g: Grid,
    cx: Float,
    cy: Float,
    r: Float,
    awake: Boolean,
    ears: Boolean = true,
) {
    if (ears) {
        drawCircle(IllSkinShade, g.d(r * 0.24f), g.p(cx - r * 0.98f, cy + r * 0.08f))
        drawCircle(IllSkinShade, g.d(r * 0.24f), g.p(cx + r * 0.98f, cy + r * 0.08f))
    }
    drawCircle(IllSkin, g.d(r), g.p(cx, cy))

    // Hair: one round tuft and a curl, which is all a baby has.
    drawCircle(IllHair, g.d(r * 0.34f), g.p(cx - r * 0.12f, cy - r * 0.86f))
    drawArc(
        IllHair, startAngle = 160f, sweepAngle = 250f, useCenter = false,
        topLeft = g.p(cx + r * 0.08f, cy - r * 1.02f),
        size = Size(g.d(r * 0.42f), g.d(r * 0.42f)),
        style = Stroke(g.d(r * 0.11f), cap = StrokeCap.Round),
    )

    val eyeY = cy + r * 0.02f
    val eyeDx = r * 0.36f
    val featureStroke = Stroke(g.d(r * 0.085f), cap = StrokeCap.Round)
    if (awake) {
        drawCircle(IllFeature, g.d(r * 0.1f), g.p(cx - eyeDx, eyeY))
        drawCircle(IllFeature, g.d(r * 0.1f), g.p(cx + eyeDx, eyeY))
        drawCircle(Color.White, g.d(r * 0.035f), g.p(cx - eyeDx + r * 0.035f, eyeY - r * 0.04f))
        drawCircle(Color.White, g.d(r * 0.035f), g.p(cx + eyeDx + r * 0.035f, eyeY - r * 0.04f))
    } else {
        listOf(cx - eyeDx, cx + eyeDx).forEach { ex ->
            drawArc(
                IllFeature, startAngle = 15f, sweepAngle = 150f, useCenter = false,
                topLeft = g.p(ex - r * 0.17f, eyeY - r * 0.14f),
                size = Size(g.d(r * 0.34f), g.d(r * 0.24f)),
                style = featureStroke,
            )
        }
    }
    drawCircle(IllCheek.copy(alpha = 0.55f), g.d(r * 0.17f), g.p(cx - r * 0.58f, cy + r * 0.32f))
    drawCircle(IllCheek.copy(alpha = 0.55f), g.d(r * 0.17f), g.p(cx + r * 0.58f, cy + r * 0.32f))
    drawArc(
        IllFeature, startAngle = 20f, sweepAngle = 140f, useCenter = false,
        topLeft = g.p(cx - r * (if (awake) 0.2f else 0.13f), cy + r * 0.26f),
        size = Size(g.d(r * (if (awake) 0.4f else 0.26f)), g.d(r * 0.24f)),
        style = featureStroke,
    )
}

/** A swaddle: a rounded wrap across the lower body with one fold. */
private fun DrawScope.swaddle(g: Grid, top: Float, left: Float = 12f, right: Float = 92f) {
    val path = Path().apply {
        moveTo(g.p(left + 8f, 94f).x, g.p(left + 8f, 94f).y)
        quadraticTo(g.p(left, 94f).x, g.p(left, 94f).y, g.p(left, 86f).x, g.p(left, 86f).y)
        lineTo(g.p(left, top + 12f).x, g.p(left, top + 12f).y)
        cubicTo(
            g.p(left + 4f, top - 2f).x, g.p(left + 4f, top - 2f).y,
            g.p(right - 30f, top - 8f).x, g.p(right - 30f, top - 8f).y,
            g.p(right, top + 4f).x, g.p(right, top + 4f).y,
        )
        lineTo(g.p(right, 86f).x, g.p(right, 86f).y)
        quadraticTo(g.p(right, 94f).x, g.p(right, 94f).y, g.p(right - 8f, 94f).x, g.p(right - 8f, 94f).y)
        close()
    }
    drawPath(path, IllBlanket)
    // A second tone across the lower half, so the wrap reads as cloth over a
    // body rather than a flat shape laid across the picture.
    clipPath(path) {
        drawRect(IllBlanketShade.copy(alpha = 0.35f), topLeft = g.p(left, top + 20f),
                 size = Size(g.d(right - left), g.d(94f - top - 20f)))
    }
    val fold = Path().apply {
        moveTo(g.p(left + 18f, top + 6f).x, g.p(left + 18f, top + 6f).y)
        quadraticTo(
            g.p(left + 34f, top + 22f).x, g.p(left + 34f, top + 22f).y,
            g.p(left + 30f, 94f).x, g.p(left + 30f, 94f).y,
        )
    }
    drawPath(fold, IllBlanketShade, style = Stroke(g.d(2.4f), cap = StrokeCap.Round))
}

/** A sleeping, swaddled baby on a pillow. Monitor and Insights. */
@Composable
fun SleepingBabyArt(
    modifier: Modifier = Modifier,
    night: Boolean = false,
    dreaming: Boolean = false,
) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val groundColor = illustrationGround()
    Canvas(modifier.fillMaxSize()) {
        val g = Grid(this)
        ground(g, groundColor)
        drawOval(IllPillow, topLeft = g.p(16f, 34f), size = Size(g.d(58f), g.d(36f)))
        face(g, 42f, 52f, 20f, awake = false)
        swaddle(g, top = 64f)
        if (night) {
            // A crescent: a disc with the ground drawn over its corner.
            drawCircle(com.nila.ui.theme.ToneBlueInk.copy(alpha = 0.55f), g.d(9f), g.p(78f, 22f))
            drawCircle(groundColor, g.d(8f), g.p(82f, 18f))
            drawCircle(com.nila.ui.theme.ToneBlueInk.copy(alpha = 0.4f), g.d(1.8f), g.p(64f, 12f))
            drawCircle(com.nila.ui.theme.ToneBlueInk.copy(alpha = 0.4f), g.d(1.3f), g.p(90f, 36f))
        } else if (dreaming) {
            // Two small z's drifting up: asleep, not a sticker sheet.
            listOf(Triple(70f, 26f, 11f), Triple(82f, 12f, 8f)).forEach { (x, y, size) ->
                val z = measurer.measure(
                    "z",
                    androidx.compose.ui.text.TextStyle(
                        color = com.nila.ui.theme.ToneTealInk.copy(alpha = 0.55f),
                        fontSize = androidx.compose.ui.unit.TextUnit(
                            g.d(size) / density / fontScale,
                            androidx.compose.ui.unit.TextUnitType.Sp),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    ),
                )
                drawText(z, topLeft = g.p(x, y))
            }
        } else {
            heart(g, 78f, 20f, 6f)
            sparks(g, 63f, 10f)
        }
    }
}

/** An awake, happy baby wrapped in a blanket. Health and Settings. */
@Composable
fun HappyBabyArt(modifier: Modifier = Modifier) {
    val groundColor = illustrationGround()
    Canvas(modifier.fillMaxSize()) {
        val g = Grid(this)
        ground(g, groundColor)
        face(g, 50f, 46f, 22f, awake = true)
        swaddle(g, top = 70f, left = 20f, right = 84f)
        drawCircle(IllSkin, g.d(5.5f), g.p(40f, 74f))
        drawCircle(IllSkin, g.d(5.5f), g.p(60f, 75f))
        heart(g, 84f, 20f, 6.5f)
        sparks(g, 16f, 18f)
        drawCircle(IllSpark, g.d(1.8f), g.p(88f, 46f))
    }
}

/** A baby with a question bubble. Ask. */
@Composable
fun AskBabyArt(modifier: Modifier = Modifier) {
    val groundColor = illustrationGround()
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    Canvas(modifier.fillMaxSize()) {
        val g = Grid(this)
        ground(g, groundColor)
        face(g, 58f, 56f, 20f, awake = true)
        swaddle(g, top = 78f, left = 30f, right = 88f)
        drawCircle(IllSkin, g.d(5f), g.p(50f, 82f))
        // The bubble, with a tail pointing at the baby.
        drawCircle(IllBubble, g.d(15f), g.p(26f, 24f))
        val tail = Path().apply {
            moveTo(g.p(30f, 34f).x, g.p(30f, 34f).y)
            lineTo(g.p(40f, 44f).x, g.p(40f, 44f).y)
            lineTo(g.p(36f, 31f).x, g.p(36f, 31f).y)
            close()
        }
        drawPath(tail, IllBubble)
        val text = measurer.measure(
            "?",
            androidx.compose.ui.text.TextStyle(
                color = IllBubbleInk,
                fontSize = androidx.compose.ui.unit.TextUnit(g.d(20f) / density / fontScale,
                    androidx.compose.ui.unit.TextUnitType.Sp),
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            ),
        )
        drawText(
            text,
            topLeft = Offset(
                g.p(26f, 24f).x - text.size.width / 2f,
                g.p(26f, 24f).y - text.size.height / 2f,
            ),
        )
        sparks(g, 74f, 12f)
        drawCircle(IllHeart, g.d(2f), g.p(88f, 34f))
    }
}

/** A baby asleep in a cot, seen through the rails. Watch. */
@Composable
fun CotBabyArt(modifier: Modifier = Modifier) {
    val groundColor = illustrationGround()
    Canvas(modifier.fillMaxSize()) {
        val g = Grid(this)
        ground(g, groundColor)
        // Mattress and sheet.
        drawRoundRect(IllPillow, topLeft = g.p(14f, 50f), size = Size(g.d(76f), g.d(32f)),
                      cornerRadius = CornerRadius(g.d(6f)))
        face(g, 36f, 56f, 13f, awake = false, ears = false)
        val blanket = Path().apply {
            moveTo(g.p(44f, 82f).x, g.p(44f, 82f).y)
            lineTo(g.p(44f, 64f).x, g.p(44f, 64f).y)
            cubicTo(g.p(52f, 54f).x, g.p(52f, 54f).y, g.p(78f, 56f).x, g.p(78f, 56f).y,
                    g.p(88f, 62f).x, g.p(88f, 62f).y)
            lineTo(g.p(88f, 82f).x, g.p(88f, 82f).y)
            close()
        }
        drawPath(blanket, IllBlanket)
        // A small bear in the corner.
        drawCircle(IllWoodShade, g.d(5.5f), g.p(80f, 50f))
        drawCircle(IllWoodShade, g.d(2.4f), g.p(76f, 45f))
        drawCircle(IllWoodShade, g.d(2.4f), g.p(84f, 45f))
        // The rails over everything, which is what makes it a cot.
        val rail = g.d(3.2f)
        drawRoundRect(IllWood, topLeft = g.p(8f, 30f), size = Size(g.d(88f), g.d(5f)),
                      cornerRadius = CornerRadius(g.d(2.5f)))
        drawRoundRect(IllWood, topLeft = g.p(8f, 82f), size = Size(g.d(88f), g.d(6f)),
                      cornerRadius = CornerRadius(g.d(3f)))
        var x = 14f
        while (x <= 90f) {
            drawLine(IllWoodShade.copy(alpha = 0.75f), g.p(x, 35f), g.p(x, 82f), rail,
                     StrokeCap.Round)
            x += 9.5f
        }
        drawRoundRect(IllWoodShade, topLeft = g.p(6f, 24f), size = Size(g.d(6f), g.d(70f)),
                      cornerRadius = CornerRadius(g.d(3f)))
        drawRoundRect(IllWoodShade, topLeft = g.p(92f, 24f), size = Size(g.d(6f), g.d(70f)),
                      cornerRadius = CornerRadius(g.d(3f)))
        heart(g, 70f, 14f, 5f)
    }
}

/** A medicine bottle, a capsule and a tablet. Scan. */
@Composable
fun MedicineArt(modifier: Modifier = Modifier) {
    val groundColor = illustrationGround()
    Canvas(modifier.fillMaxSize()) {
        val g = Grid(this)
        ground(g, groundColor)
        // Bottle.
        drawRoundRect(IllBottle, topLeft = g.p(26f, 34f), size = Size(g.d(36f), g.d(54f)),
                      cornerRadius = CornerRadius(g.d(8f)))
        drawRoundRect(IllBottleCap, topLeft = g.p(28f, 22f), size = Size(g.d(32f), g.d(15f)),
                      cornerRadius = CornerRadius(g.d(4f)))
        drawRoundRect(IllPillAccent.copy(alpha = 0.18f), topLeft = g.p(28f, 22f),
                      size = Size(g.d(32f), g.d(15f)), cornerRadius = CornerRadius(g.d(4f)),
                      style = Stroke(g.d(1.2f)))
        drawRoundRect(Color.White.copy(alpha = 0.92f), topLeft = g.p(31f, 50f),
                      size = Size(g.d(26f), g.d(22f)), cornerRadius = CornerRadius(g.d(3f)))
        drawLine(IllBottle, g.p(35f, 57f), g.p(53f, 57f), g.d(2.2f), StrokeCap.Round)
        drawLine(IllBottle.copy(alpha = 0.6f), g.p(35f, 64f), g.p(48f, 64f), g.d(2.2f),
                 StrokeCap.Round)
        // Capsule, tilted.
        rotate(-35f, pivot = g.p(76f, 72f)) {
            drawRoundRect(IllPill, topLeft = g.p(66f, 66f), size = Size(g.d(20f), g.d(11f)),
                          cornerRadius = CornerRadius(g.d(5.5f)))
            drawRoundRect(IllPillAccent, topLeft = g.p(66f, 66f), size = Size(g.d(10f), g.d(11f)),
                          cornerRadius = CornerRadius(g.d(5.5f)))
            drawRect(IllPillAccent, topLeft = g.p(70f, 66f), size = Size(g.d(6f), g.d(11f)))
        }
        // Tablet.
        drawCircle(IllPill, g.d(8f), g.p(76f, 40f))
        drawCircle(IllWoodShade, g.d(8f), g.p(76f, 40f), style = Stroke(g.d(1.2f)))
        drawLine(IllWoodShade, g.p(71f, 40f), g.p(81f, 40f), g.d(1.4f), StrokeCap.Round)
        heart(g, 16f, 26f, 5f)
        sparks(g, 84f, 14f)
    }
}

/**
 * Where the phone goes: on a stand at the side of the cot, looking across it.
 * Watch's placement guide.
 */
@Composable
fun PhonePlacementArt(modifier: Modifier = Modifier) {
    val groundColor = illustrationGround()
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        fun p(x: Float, y: Float) = Offset(x / 100f * w, y / 100f * h)
        fun dx(v: Float) = v / 100f * w
        fun dy(v: Float) = v / 100f * h
        drawRoundRect(groundColor, size = size, cornerRadius = CornerRadius(dx(10f)))

        // Cot, three-quarter view.
        drawRoundRect(IllPillow, topLeft = p(34f, 58f), size = Size(dx(58f), dy(18f)),
                      cornerRadius = CornerRadius(dx(3f)))
        drawCircle(IllSkin, dx(6.5f), p(50f, 60f))
        drawCircle(IllHair, dx(2.6f), p(48f, 54f))
        drawRoundRect(IllBlanket, topLeft = p(56f, 56f), size = Size(dx(30f), dy(12f)),
                      cornerRadius = CornerRadius(dx(4f)))
        drawRoundRect(IllWood, topLeft = p(30f, 36f), size = Size(dx(66f), dy(4f)),
                      cornerRadius = CornerRadius(dx(2f)))
        drawRoundRect(IllWood, topLeft = p(30f, 76f), size = Size(dx(66f), dy(5f)),
                      cornerRadius = CornerRadius(dx(2f)))
        var x = 34f
        while (x <= 92f) {
            drawLine(IllWoodShade.copy(alpha = 0.7f), p(x, 40f), p(x, 76f), dx(1.6f),
                     StrokeCap.Round)
            x += 6f
        }
        drawRoundRect(IllWoodShade, topLeft = p(29f, 30f), size = Size(dx(3.5f), dy(62f)),
                      cornerRadius = CornerRadius(dx(1.5f)))
        drawRoundRect(IllWoodShade, topLeft = p(94f, 30f), size = Size(dx(3.5f), dy(62f)),
                      cornerRadius = CornerRadius(dx(1.5f)))

        // The view, as a dashed cone from the phone to the far rail.
        val dash = PathEffect.dashPathEffect(floatArrayOf(dx(2f), dx(2f)))
        val cone = Path().apply {
            moveTo(p(17f, 22f).x, p(17f, 22f).y)
            lineTo(p(92f, 44f).x, p(92f, 44f).y)
            lineTo(p(92f, 74f).x, p(92f, 74f).y)
            close()
        }
        drawPath(cone, IllSpark.copy(alpha = 0.16f))
        drawLine(IllSpark, p(17f, 22f), p(92f, 44f), dx(0.9f), pathEffect = dash)
        drawLine(IllSpark, p(17f, 22f), p(92f, 74f), dx(0.9f), pathEffect = dash)

        // Stand and phone.
        drawLine(IllPhone, p(16f, 30f), p(16f, 86f), dx(1.8f), StrokeCap.Round)
        drawLine(IllPhone, p(16f, 86f), p(8f, 92f), dx(1.8f), StrokeCap.Round)
        drawLine(IllPhone, p(16f, 86f), p(24f, 92f), dx(1.8f), StrokeCap.Round)
        rotate(-20f, pivot = p(16f, 22f)) {
            drawRoundRect(IllPhone, topLeft = p(12f, 10f), size = Size(dx(8f), dy(22f)),
                          cornerRadius = CornerRadius(dx(1.6f)))
            drawCircle(IllSpark, dx(1f), p(16f, 13f))
        }
    }
}

/** A round baby face for a profile row. */
@Composable
fun BabyAvatar(modifier: Modifier = Modifier) {
    val groundColor = illustrationGround()
    Canvas(modifier.fillMaxSize()) {
        val g = Grid(this)
        drawCircle(groundColor, g.d(50f), g.p(50f, 50f))
        face(g, 50f, 56f, 28f, awake = true)
    }
}

/** A small settings card with two switches and a heart. Settings. */
@Composable
fun SettingsArt(modifier: Modifier = Modifier) {
    val groundColor = illustrationGround()
    Canvas(modifier.fillMaxSize()) {
        val g = Grid(this)
        ground(g, groundColor)
        drawRoundRect(androidx.compose.ui.graphics.Color.White, topLeft = g.p(22f, 22f),
                      size = Size(g.d(56f), g.d(62f)), cornerRadius = CornerRadius(g.d(9f)))
        drawRoundRect(IllWoodShade.copy(alpha = 0.6f), topLeft = g.p(22f, 22f),
                      size = Size(g.d(56f), g.d(62f)), cornerRadius = CornerRadius(g.d(9f)),
                      style = Stroke(g.d(1.2f)))
        listOf(38f to true, 56f to false).forEach { (y, on) ->
            drawLine(IllWoodShade, g.p(30f, y), g.p(48f, y), g.d(3f), StrokeCap.Round)
            drawRoundRect(if (on) IllSpark else IllWood, topLeft = g.p(54f, y - 5f),
                          size = Size(g.d(17f), g.d(10f)), cornerRadius = CornerRadius(g.d(5f)))
            drawCircle(androidx.compose.ui.graphics.Color.White, g.d(3.6f),
                       g.p(if (on) 66f else 59f, y))
        }
        drawLine(IllWoodShade, g.p(30f, 72f), g.p(58f, 72f), g.d(3f), StrokeCap.Round)
        heart(g, 80f, 22f, 6f)
        face(g, 80f, 76f, 11f, awake = true, ears = false)
    }
}
