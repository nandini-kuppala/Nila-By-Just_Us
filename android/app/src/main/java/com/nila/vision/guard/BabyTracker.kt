package com.nila.vision.guard

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * Keeps one baby box over time from detector hits plus motion. Ported unchanged from Baby Guard CCTV.
 *  - A detector hit that overlaps the current box (or the best hit, if there is no box yet) becomes the box,
 *    smoothed so it does not jitter.
 *  - No hit but motion overlapping the box: the box follows the motion (keeps its size). At night the
 *    detector misses more often and motion carries the track.
 *  - Neither: the box coasts unchanged. After [lostAfterMs] without any evidence the baby is "not visible".
 */
class BabyTracker(
    private val lostAfterMs: Long = 3_000,
) {
    enum class Source { DETECTOR, MOTION, COAST, NONE }

    @Volatile var box: RectF? = null
        private set

    @Volatile var source = Source.NONE
        private set

    @Volatile var score = 0f
        private set

    @Volatile var lastEvidenceAt = 0L
        private set

    /** The box at the moment the track was lost (to tell "left the view at the edge" from "covered"). */
    @Volatile var lastBoxBeforeLost: RectF? = null
        private set

    fun reset() {
        box = null
        source = Source.NONE
        lastEvidenceAt = 0
        lastBoxBeforeLost = null
    }

    fun visible(now: Long) = box != null && now - lastEvidenceAt <= lostAfterMs

    fun onDetections(hits: List<BabyDetector.Hit>, now: Long) {
        val cur = box
        val pick =
            if (cur != null && now - lastEvidenceAt <= lostAfterMs) {
                hits.filter { iou(it.box, cur) > 0.05f }.maxByOrNull { iou(it.box, cur) + it.score * 0.2f }
            } else {
                hits.maxByOrNull { it.score }
            }
        if (pick != null) {
            box = if (cur == null || now - lastEvidenceAt > lostAfterMs) RectF(pick.box) else blend(cur, pick.box, 0.6f)
            score = pick.score
            source = Source.DETECTOR
            lastBoxBeforeLost = null
            lastEvidenceAt = now
        } else {
            age(now)
        }
    }

    /** Motion blob from [FrameAnalyzer]; used only when it overlaps the current box (the baby moving). */
    fun onMotion(motion: RectF?, now: Long) {
        val cur = box ?: return
        if (motion == null || source == Source.DETECTOR && now - lastEvidenceAt < 400) return
        if (now - lastEvidenceAt > lostAfterMs) return
        val grown = RectF(cur.left - 0.08f, cur.top - 0.08f, cur.right + 0.08f, cur.bottom + 0.08f)
        if (!RectF.intersects(grown, motion)) return
        val dx = motion.centerX() - cur.centerX()
        val dy = motion.centerY() - cur.centerY()
        box = RectF(cur.left + dx * 0.5f, cur.top + dy * 0.5f, cur.right + dx * 0.5f, cur.bottom + dy * 0.5f)
        source = Source.MOTION
        lastEvidenceAt = now
    }

    private fun age(now: Long) {
        if (box == null) return
        if (now - lastEvidenceAt > lostAfterMs) {
            if (source != Source.NONE) lastBoxBeforeLost = box
            source = Source.NONE
        } else {
            source = Source.COAST
        }
    }

    companion object {
        fun iou(a: RectF, b: RectF): Float {
            val ix = max(0f, min(a.right, b.right) - max(a.left, b.left))
            val iy = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
            val inter = ix * iy
            val union = a.width() * a.height() + b.width() * b.height() - inter
            return if (union <= 0f) 0f else inter / union
        }

        private fun blend(a: RectF, b: RectF, t: Float) =
            RectF(a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t,
                  a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t)
    }
}
