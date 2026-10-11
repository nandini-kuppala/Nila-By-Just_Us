package com.nila.babyproof.detect

import com.nila.babyproof.pipeline.Box
import com.nila.babyproof.pipeline.Candidate

/**
 * Where the detector looks.
 *
 * OWL-ViT sees 768x768. A whole room squeezed into that leaves a wall socket a
 * dozen pixels wide, so besides the whole photo the detector also looks at four
 * overlapping quarters (each 60% of the width and height), which roughly
 * doubles every object's size on its input.
 */
object Tiles {

    data class Tile(val name: String, val area: Box)

    fun plan(width: Int, height: Int, quarters: Boolean = true): List<Tile> {
        val whole = Tile("whole", Box(0f, 0f, 1f, 1f))
        if (!quarters) return listOf(whole)
        val s = 0.6f
        val o = 1f - s
        return listOf(
            whole,
            Tile("top-left", Box(0f, 0f, s, s)),
            Tile("top-right", Box(o, 0f, 1f, s)),
            Tile("bottom-left", Box(0f, o, s, 1f)),
            Tile("bottom-right", Box(o, o, 1f, 1f)),
        )
    }

    /** A box in a tile's own 0..1 coordinates, moved into whole-photo coordinates. */
    fun toPhoto(tile: Box, inTile: Box) = Box(
        tile.l + inTile.l * tile.w, tile.t + inTile.t * tile.h,
        tile.l + inTile.r * tile.w, tile.t + inTile.b * tile.h,
    ).clamp()

    /**
     * Non-maximum suppression per hazard: of boxes that overlap by more than
     * [iou], the highest score stays. Boxes that cover most of the photo are
     * dropped -- "the whole room is a socket" is the detector's commonest
     * nonsense answer.
     */
    fun nms(all: List<Candidate>, iou: Float = 0.45f, maxPerHazard: Int = 4, maxArea: Float = 0.6f): List<Candidate> {
        val out = mutableListOf<Candidate>()
        for ((_, group) in all.filter { it.box.area in 0.0002f..maxArea }.groupBy { it.hazard.id }) {
            val kept = mutableListOf<Candidate>()
            for (c in group.sortedByDescending { it.score }) {
                if (kept.none { it.box.iou(c.box) > iou || contains(it.box, c.box) }) kept += c
                if (kept.size >= maxPerHazard) break
            }
            out += kept
        }
        return out.sortedByDescending { it.strength }
    }

    /** One box mostly inside a bigger one of the same kind: the same object seen at two scales. */
    private fun contains(big: Box, small: Box): Boolean {
        val iw = (minOf(big.r, small.r) - maxOf(big.l, small.l)).coerceAtLeast(0f)
        val ih = (minOf(big.b, small.b) - maxOf(big.t, small.t)).coerceAtLeast(0f)
        return small.area > 0f && iw * ih / small.area > 0.8f
    }
}
