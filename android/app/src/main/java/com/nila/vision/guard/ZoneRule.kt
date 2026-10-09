package com.nila.vision.guard

import android.graphics.RectF
import com.nila.vision.SafeZone

/**
 * The safe-zone rule from Baby Guard CCTV, on the share of the baby's box outside the zone:
 *  - under 10 %: inside; 10-40 %: partly outside (orange, no alert yet)
 *  - 40 % or more for 1 s: alert ("leaving the safe area")
 *  - the baby disappears while partly outside or at the picture edge: alert ("out of view")
 *  - the alert ends when the baby is back (under 25 % outside)
 *
 * The zone itself is Nila's [SafeZone] rectangle, which the Watch screen already lets a parent drag.
 */
class ZoneRule {

    enum class Status(val label: String) {
        NONE("Safe zone off"),
        NOT_SEEN("Looking for the baby"),
        INSIDE("Baby in the safe zone"),
        PARTLY("Baby partly outside"),
        OUT("Baby OUTSIDE the safe zone"),
        LOST("Baby not visible"),
    }

    sealed interface Decision {
        data object Nothing : Decision
        data class Raise(val reason: String) : Decision
        data object BackInside : Decision
    }

    @Volatile var status = Status.NONE
        private set

    @Volatile var outFraction = 0f
        private set

    private var outsideSince = 0L

    fun reset() {
        status = Status.NONE
        outFraction = 0f
        outsideSince = 0L
    }

    /**
     * One evaluation. [active] = the zone mode is on and the watch is running; [zoneAlertOn] = the alert
     * currently on screen was raised by this rule; [alertIdle] = no alert of any kind is on.
     */
    @Synchronized
    fun update(
        now: Long,
        zone: SafeZone,
        tracker: BabyTracker,
        active: Boolean,
        zoneAlertOn: Boolean,
        alertIdle: Boolean,
        log: (String) -> Unit,
    ): Decision {
        if (!active) {
            outsideSince = 0
            if (status != Status.NONE) {
                status = Status.NONE
                outFraction = 0f
            }
            return Decision.Nothing
        }
        val box = tracker.box?.takeIf { tracker.visible(now) }
        outFraction = if (box != null) outsideFraction(zone, box) else 0f
        val lost = tracker.lastBoxBeforeLost
        val next =
            when {
                box == null -> if (lost != null) Status.LOST else Status.NOT_SEEN
                outFraction >= OUT_FRACTION -> Status.OUT
                outFraction >= 0.1f -> Status.PARTLY
                else -> Status.INSIDE
            }
        if (next != status) {
            log("ZONE_STATUS ${status.name} -> ${next.name} out=${pct(outFraction)} baby=${fmt(box)} src=${tracker.source}")
            status = next
        }
        val lostAtEdge =
            next == Status.LOST && lost != null &&
                (outsideFraction(zone, lost) > 0.2f || lost.left < 0.03f || lost.right > 0.97f ||
                    lost.top < 0.03f || lost.bottom > 0.97f)
        // The detector often loses a baby that is half out of the picture (climbing out): if the last box was
        // already partly outside and nothing has confirmed it for 0.8 s, treat it as leaving.
        val fadingAtEdge =
            tracker.source == BabyTracker.Source.COAST && outFraction >= 0.2f && now - tracker.lastEvidenceAt >= 800
        val bad = next == Status.OUT || lostAtEdge || fadingAtEdge
        if (!bad) {
            outsideSince = 0
            val back = next == Status.INSIDE || (next == Status.PARTLY && outFraction < BACK_FRACTION)
            if (zoneAlertOn && back) {
                log("ZONE_BACK_INSIDE out=${pct(outFraction)}")
                return Decision.BackInside
            }
            return Decision.Nothing
        }
        if (outsideSince == 0L) outsideSince = now
        if (alertIdle && now - outsideSince >= CONFIRM_MS) {
            val reason =
                if (lostAtEdge || fadingAtEdge && next != Status.OUT) "Baby out of view (last seen at the edge of the safe zone)"
                else "Baby leaving the safe zone (${pct(outFraction)} outside)"
            return Decision.Raise(reason)
        }
        return Decision.Nothing
    }

    /** Share (0..1) of the baby's box that is outside the zone, sampled on a 12x12 grid over the box. */
    fun outsideFraction(zone: SafeZone, box: RectF): Float {
        var out = 0
        val n = 12
        for (i in 0 until n) {
            for (j in 0 until n) {
                val x = box.left + box.width() * (i + 0.5f) / n
                val y = box.top + box.height() * (j + 0.5f) / n
                if (!zone.contains(x, y)) out++
            }
        }
        return out / (n * n).toFloat()
    }

    companion object {
        const val CONFIRM_MS = 1_000L
        const val OUT_FRACTION = 0.4f
        const val BACK_FRACTION = 0.25f

        fun pct(f: Float) = "${(f * 100).toInt()}%"
        fun fmt(b: RectF?) = b?.let { "%.2f,%.2f,%.2f,%.2f".format(it.left, it.top, it.right, it.bottom) } ?: "none"
    }
}
