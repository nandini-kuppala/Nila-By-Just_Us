package com.nila.skin.core.measure

import com.nila.skin.core.image.NPoint
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Was the baby looking straight at the camera?
 *
 * The main cause of a false white glow in a flash photo is the angle: when an
 * eye looks about 15 degrees towards the nose, the flash lands on the optic
 * nerve head, which is pale, and the pupil glows white in a healthy eye
 * ("pseudo-leukocoria"; the CRADLE authors list off-axis gaze as their main
 * source of false positives). Face landmarks already give the iris centre and
 * both eye corners, so the gaze can be read from where the iris sits between
 * the corners, and head turn from where the nose sits between the eyes.
 *
 * This never lowers the advice -- a white glow is still recorded and counted --
 * it explains a likely cause and asks for a straight-on retake.
 */
data class EyeAngle(
    /** Iris offset from the middle of the eye, as a share of eye width; ~0 when looking straight. */
    val gazeLeft: Double,
    val gazeRight: Double,
    /** Nose offset from the midpoint of the eyes, as a share of the distance between them. */
    val headTurn: Double,
) {
    val leftOffAxis: Boolean get() = abs(gazeLeft) > GAZE_LIMIT || abs(headTurn) > TURN_LIMIT
    val rightOffAxis: Boolean get() = abs(gazeRight) > GAZE_LIMIT || abs(headTurn) > TURN_LIMIT
    val straight: Boolean get() = !leftOffAxis && !rightOffAxis

    fun words(): String = when {
        abs(headTurn) > TURN_LIMIT -> "Head turned to one side"
        abs(gazeLeft) > GAZE_LIMIT || abs(gazeRight) > GAZE_LIMIT -> "Eyes looking to the side, not at the camera"
        else -> "Looking straight at the camera"
    }

    companion object {
        /**
         * The iris moves about a third of the eye width at full sideways gaze,
         * so 0.10 of eye width is roughly 10-15 degrees: the angle at which the
         * optic nerve starts to show.
         */
        const val GAZE_LIMIT = 0.10
        const val TURN_LIMIT = 0.18

        /**
         * All points in normalised photo coordinates; "left" means left in the
         * photo. Corners may be given in either order.
         */
        fun of(
            irisLeft: NPoint, leftCornerA: NPoint, leftCornerB: NPoint,
            irisRight: NPoint, rightCornerA: NPoint, rightCornerB: NPoint,
            noseTip: NPoint, aspect: Double,
        ): EyeAngle {
            fun gaze(iris: NPoint, a: NPoint, b: NPoint): Double {
                val midX = (a.x + b.x) / 2
                val width = hypot((a.x - b.x) * aspect, a.y - b.y).coerceAtLeast(1e-6)
                return (iris.x - midX) * aspect / width
            }
            val midEyes = ((irisLeft.x + irisRight.x) / 2)
            val between = hypot((irisRight.x - irisLeft.x) * aspect, irisRight.y - irisLeft.y).coerceAtLeast(1e-6)
            return EyeAngle(
                gaze(irisLeft, leftCornerA, leftCornerB),
                gaze(irisRight, rightCornerA, rightCornerB),
                (noseTip.x - midEyes) * aspect / between,
            )
        }
    }
}
