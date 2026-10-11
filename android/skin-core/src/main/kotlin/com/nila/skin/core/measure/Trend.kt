package com.nila.skin.core.measure

import kotlin.math.abs

/** One dated reading of a tracked spot. */
data class TrendPoint(
    val timeMs: Long,
    /** Size measure: mm2 when a coin was marked, otherwise share of the photo. */
    val size: Double,
    val sizeIsMm2: Boolean,
    val redness: Double,
    val spots: Int,
)

/**
 * Is it getting bigger, redder, or settling down?
 *
 * Change over time is the thing doctors ask about and parents can't answer from
 * memory ("is it spreading?"). Size is compared only between photos measured
 * the same way -- both with a coin, or both framed with the ghost overlay --
 * and a change has to clear a margin bigger than the wobble of re-photographing
 * the same spot before it is called a change. With three or more photos the
 * change comes from a robust slope through all of them, not just the ends.
 */
data class TrendSummary(
    val size: Direction,
    val redness: Direction,
    val spots: Direction,
    val days: Double,
    /** Size change, last vs first, as a ratio (1.5 = half as big again). */
    val sizeRatio: Double?,
    val sentence: String,
    /** Size change per day from the robust slope, as a ratio (1.2 = 20% a day); null with under 3 photos. */
    val sizePerDay: Double? = null,
    /** True when the robust slope over all photos was used rather than first vs last. */
    val robust: Boolean = false,
) {
    enum class Direction(val word: String) { BIGGER("bigger"), SMALLER("smaller"), SAME("about the same"), UNKNOWN("not enough photos yet") }

    /** Clear growth in a short time: the one trend that raises the advice level. */
    val spreadingFast: Boolean get() = size == Direction.BIGGER && (sizeRatio ?: 1.0) >= 1.5 && days <= 4.0
}

object Trend {

    /** Re-photographing the same unchanged spot moves size by up to ~20% in our tests. */
    const val SIZE_MARGIN = 0.25
    /** a* points: below this a redness change is within camera noise. */
    const val REDNESS_MARGIN = 3.0

    fun summarise(points: List<TrendPoint>): TrendSummary {
        val sorted = points.sortedBy { it.timeMs }
        if (sorted.size < 2) {
            return TrendSummary(
                TrendSummary.Direction.UNKNOWN, TrendSummary.Direction.UNKNOWN, TrendSummary.Direction.UNKNOWN,
                0.0, null, "Take another photo of the same spot tomorrow to see how it changes.",
            )
        }
        val last = sorted.last()
        val first = sorted.first()
        val days = (last.timeMs - first.timeMs) / 86_400_000.0

        // Size is only compared between photos measured the same way as the latest.
        val same = sorted.filter { it.sizeIsMm2 == last.sizeIsMm2 && it.size > 0 }
        val sizeDays = if (same.size >= 2) (same.last().timeMs - same.first().timeMs) / 86_400_000.0 else 0.0
        // With 3+ photos, the Theil-Sen slope (median of all pairwise slopes) of
        // log size against time: one badly framed photo cannot flip the answer,
        // as it can when only the first and last photos are compared.
        val robust = same.size >= 3 && sizeDays > 0
        val perDay = if (robust) kotlin.math.exp(theilSen(same.map { it.timeMs / 86_400_000.0 }, same.map { kotlin.math.ln(it.size) })) else null
        val ratio = when {
            robust -> Math.pow(perDay!!, sizeDays)
            same.size >= 2 -> same.last().size / same.first().size
            else -> null
        }
        val size = when {
            ratio == null -> TrendSummary.Direction.UNKNOWN
            ratio >= 1 + SIZE_MARGIN -> TrendSummary.Direction.BIGGER
            ratio <= 1 - SIZE_MARGIN -> TrendSummary.Direction.SMALLER
            else -> TrendSummary.Direction.SAME
        }
        val dr = if (sorted.size >= 3 && days > 0) theilSen(sorted.map { it.timeMs / 86_400_000.0 }, sorted.map { it.redness }) * days
                 else last.redness - first.redness
        val red = when {
            dr >= REDNESS_MARGIN -> TrendSummary.Direction.BIGGER
            dr <= -REDNESS_MARGIN -> TrendSummary.Direction.SMALLER
            else -> TrendSummary.Direction.SAME
        }
        val ds = last.spots - first.spots
        val spots = when {
            ds >= maxOf(2, (first.spots * 0.3).toInt()) -> TrendSummary.Direction.BIGGER
            ds <= -maxOf(2, (first.spots * 0.3).toInt()) -> TrendSummary.Direction.SMALLER
            else -> TrendSummary.Direction.SAME
        }
        val span = when {
            days < 1 -> "since earlier today"
            days < 1.5 -> "since yesterday"
            else -> "over ${days.toInt()} days"
        }
        val parts = buildList {
            if (size != TrendSummary.Direction.UNKNOWN) add(
                when (size) {
                    TrendSummary.Direction.BIGGER -> if (ratio!! >= 2.0) "bigger (about ${pct(ratio)})" else "bigger (about ${pct(ratio)} larger)"
                    TrendSummary.Direction.SMALLER -> "smaller (about ${pct(ratio!!)} smaller)"
                    else -> "about the same size"
                }
            )
            add(
                when (red) {
                    TrendSummary.Direction.BIGGER -> "redder"
                    TrendSummary.Direction.SMALLER -> "less red"
                    else -> "similar redness"
                }
            )
            if (spots == TrendSummary.Direction.BIGGER) add("more spots") else if (spots == TrendSummary.Direction.SMALLER) add("fewer spots")
        }
        val sentence = "${parts.joinToString(", ").replaceFirstChar { it.uppercase() }} $span."
        return TrendSummary(size, red, spots, days, ratio, sentence, perDay, robust)
    }

    /**
     * Theil-Sen estimator: the median of the slopes between every pair of
     * points. Robust to up to ~29% bad points, and simple enough to explain.
     */
    fun theilSen(t: List<Double>, v: List<Double>): Double {
        val slopes = ArrayList<Double>()
        for (i in t.indices) for (j in i + 1 until t.size) {
            val dt = t[j] - t[i]
            if (kotlin.math.abs(dt) > 1e-6) slopes += (v[j] - v[i]) / dt
        }
        return com.nila.skin.core.image.Regions.med(slopes.sorted())
    }

    /** "about 40% larger", or "about 3 times the size" once it has more than doubled. */
    private fun pct(r: Double) = if (r >= 2.0) "%.1f times the size".format(r).replace(".0 ", " ") else "${(abs(r - 1) * 100).toInt()}%"
}
