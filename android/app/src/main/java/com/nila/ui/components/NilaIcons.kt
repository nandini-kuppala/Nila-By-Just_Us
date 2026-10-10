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

    /**
     * A stethoscope, for the doctor visit: two earpieces, the U of the ear
     * tubes, and the tubing curving round to the chest piece.
     */
    val Stethoscope: ImageVector by lazy {
        ImageVector.Builder(
            name = "Stethoscope", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(androidx.compose.ui.graphics.Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                // Earpieces.
                moveTo(5.5f, 3f)
                lineTo(7.5f, 3f)
                moveTo(12.5f, 3f)
                lineTo(14.5f, 3f)
                // Ear tubes, meeting at the bottom of the U.
                moveTo(6.5f, 3f)
                lineTo(6.5f, 8f)
                curveTo(6.5f, 11f, 8f, 12.8f, 10f, 12.8f)
                curveTo(12f, 12.8f, 13.5f, 11f, 13.5f, 8f)
                lineTo(13.5f, 3f)
                // Tubing down and round to the chest piece.
                moveTo(10f, 12.8f)
                lineTo(10f, 16f)
                curveTo(10f, 19f, 12f, 20.8f, 14.5f, 20.8f)
                curveTo(17f, 20.8f, 18.5f, 19f, 18.5f, 16.8f)
                lineTo(18.5f, 15.6f)
                // Chest piece.
                moveTo(20.6f, 13.5f)
                arcTo(2.1f, 2.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 16.4f, 13.5f)
                arcTo(2.1f, 2.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 20.6f, 13.5f)
                close()
            }
        }.build()
    }
}
