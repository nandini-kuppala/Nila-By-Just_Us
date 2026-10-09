package com.nila.ui.components

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The two icons Material does not have and a baby app cannot do without.
 *
 * Drawn as 1.8-unit strokes on the 24-unit grid so they sit beside the outlined
 * Material set without looking borrowed from somewhere else. The colour is a
 * placeholder; [androidx.compose.material3.Icon] tints the whole vector.
 */
object NilaIcons {

    /** A feeding bottle: teat, collar, body with two graduation marks. */
    val Bottle: ImageVector by lazy {
        ImageVector.Builder(
            name = "Bottle", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(androidx.compose.ui.graphics.Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Teat.
                moveTo(10.5f, 5.5f)
                curveTo(10.5f, 3.2f, 13.5f, 3.2f, 13.5f, 5.5f)
                // Collar.
                moveTo(8.5f, 5.5f)
                lineTo(15.5f, 5.5f)
                lineTo(15.5f, 8f)
                lineTo(8.5f, 8f)
                close()
                // Body.
                moveTo(9f, 8f)
                lineTo(9f, 19f)
                curveTo(9f, 20.4f, 10f, 21f, 12f, 21f)
                curveTo(14f, 21f, 15f, 20.4f, 15f, 19f)
                lineTo(15f, 8f)
                // Marks.
                moveTo(12f, 12f)
                lineTo(15f, 12f)
                moveTo(12f, 15.5f)
                lineTo(15f, 15.5f)
            }
        }.build()
    }

    /** A nappy, front view: waistband, tabs, and the leg curve. */
    val Nappy: ImageVector by lazy {
        ImageVector.Builder(
            name = "Nappy", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(androidx.compose.ui.graphics.Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3.5f, 7f)
                lineTo(20.5f, 7f)
                lineTo(20.5f, 10.5f)
                curveTo(17.5f, 11f, 15f, 13.5f, 14.5f, 17.5f)
                lineTo(9.5f, 17.5f)
                curveTo(9f, 13.5f, 6.5f, 11f, 3.5f, 10.5f)
                close()
                // Waistband.
                moveTo(3.5f, 9f)
                lineTo(20.5f, 9f)
                // Tabs.
                moveTo(6.5f, 9f)
                lineTo(6.5f, 11.2f)
                moveTo(17.5f, 9f)
                lineTo(17.5f, 11.2f)
            }
        }.build()
    }
}
