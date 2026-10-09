package com.nila.vision.guard

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Per-frame image processing on the camera's brightness (Y) plane at ~320x240, ported unchanged from
 * Baby Guard CCTV:
 *  - night vision: temporal averaging (less noise), auto levels + gamma (brighter), green tint
 *  - motion tracking: running background model, per-cell change detection and the largest connected
 *    blob, which gives the baby's position to the tracker between detector hits
 * Output coordinates are normalised (0..1) in the upright (portrait) frame.
 */
class FrameAnalyzer {
    data class Result(
        val bitmap: Bitmap?,
        val meanLuma: Double,
        val centroid: PointF?,
        val box: RectF?,
        val activeCells: Int,
        val globalChange: Boolean,
    )

    private var w = 0
    private var h = 0
    private var lum = FloatArray(0)
    private var acc: FloatArray? = null
    private var bg: FloatArray? = null
    private var noise = 3f

    fun reset() {
        acc = null
        bg = null
    }

    fun process(frame: GuardFrame, night: Boolean): Result =
        process(frame.yBuffer, frame.yRowStride, frame.yPixelStride, frame.width, frame.height, frame.rotation, night)

    private fun process(
        buf: ByteBuffer,
        rowStride: Int,
        pixStride: Int,
        width: Int,
        height: Int,
        rot: Int,
        night: Boolean,
    ): Result {
        val step = max(1, max(width, height) / TARGET_LONG_SIDE)
        val sw = width / step
        val sh = height / step
        val ow = if (rot == 90 || rot == 270) sh else sw
        val oh = if (rot == 90 || rot == 270) sw else sh
        if (ow != w || oh != h) {
            w = ow
            h = oh
            lum = FloatArray(w * h)
            reset()
        }

        // Downsample and rotate upright.
        var sum = 0L
        for (y in 0 until sh) {
            val row = y * step * rowStride
            for (x in 0 until sw) {
                val v = buf.get(row + x * step * pixStride).toInt() and 0xFF
                sum += v
                val idx =
                    when (rot) {
                        90 -> x * w + (sh - 1 - y)
                        270 -> (sw - 1 - x) * w + y
                        180 -> (sh - 1 - y) * w + (sw - 1 - x)
                        else -> y * w + x
                    }
                lum[idx] = v.toFloat()
            }
        }
        val meanLuma = sum.toDouble() / (sw * sh)

        // Night: blend frames over time to average out sensor noise.
        val src = acc ?: lum.copyOf().also { acc = it }
        if (night) {
            for (i in lum.indices) src[i] += (lum[i] - src[i]) * NIGHT_BLEND
        } else {
            System.arraycopy(lum, 0, src, 0, lum.size)
        }

        // Motion: difference to a slowly updated background, counted per cell.
        val back = bg ?: src.copyOf().also { bg = it }
        val gx = w / CELL
        val gy = h / CELL
        val counts = IntArray(gx * gy)
        var diffSum = 0.0
        val threshold = max(MIN_THRESHOLD, noise * 4f)
        for (y in 0 until gy * CELL) {
            val cy = y / CELL
            for (x in 0 until gx * CELL) {
                val i = y * w + x
                val d = abs(src[i] - back[i])
                diffSum += d
                if (d > threshold) counts[cy * gx + x / CELL]++
            }
        }
        val meanDiff = (diffSum / (gx * CELL * gy * CELL)).toFloat()
        noise = noise * 0.97f + meanDiff.coerceAtMost(noise * 2f + 2f) * 0.03f
        val active = BooleanArray(gx * gy) { counts[it] > CELL * CELL / 4 }
        val activeCount = active.count { it }
        // Most of the frame changing at once = exposure / light change (e.g. the torch), not a baby.
        val global = activeCount > gx * gy / 2

        for (y in 0 until h) {
            val cy = y / CELL
            for (x in 0 until w) {
                val i = y * w + x
                val cx = x / CELL
                val alpha =
                    when {
                        global -> 1f
                        cx < gx && cy < gy && active[cy * gx + cx] -> 0.01f
                        else -> 0.06f
                    }
                back[i] += (src[i] - back[i]) * alpha
            }
        }

        var centroid: PointF? = null
        var box: RectF? = null
        if (!global && activeCount >= MIN_CELLS) {
            val seen = BooleanArray(gx * gy)
            val queue = IntArray(gx * gy)
            var best = IntArray(0)
            for (start in 0 until gx * gy) {
                if (!active[start] || seen[start]) continue
                var head = 0
                var tail = 0
                queue[tail++] = start
                seen[start] = true
                while (head < tail) {
                    val c = queue[head++]
                    val cx = c % gx
                    val cy = c / gx
                    val neighbours = intArrayOf(
                        if (cx > 0) c - 1 else -1, if (cx < gx - 1) c + 1 else -1,
                        if (cy > 0) c - gx else -1, if (cy < gy - 1) c + gx else -1,
                    )
                    for (n in neighbours) {
                        if (n >= 0 && active[n] && !seen[n]) {
                            seen[n] = true
                            queue[tail++] = n
                        }
                    }
                }
                if (tail > best.size) best = queue.copyOf(tail)
            }
            if (best.size >= MIN_CELLS) {
                var minX = gx
                var minY = gy
                var maxX = 0
                var maxY = 0
                var sx = 0f
                var sy = 0f
                for (c in best) {
                    val cx = c % gx
                    val cy = c / gx
                    minX = minOf(minX, cx)
                    maxX = maxOf(maxX, cx)
                    minY = minOf(minY, cy)
                    maxY = maxOf(maxY, cy)
                    sx += cx + 0.5f
                    sy += cy + 0.5f
                }
                centroid = PointF(sx / best.size / gx, sy / best.size / gy)
                box = RectF(minX.toFloat() / gx, minY.toFloat() / gy, (maxX + 1f) / gx, (maxY + 1f) / gy)
            }
        }

        return Result(if (night) render(src) else null, meanLuma, centroid, box, activeCount, global)
    }

    /** Auto levels (1%..99.5%) + gamma, drawn in night-vision green. */
    private fun render(src: FloatArray): Bitmap {
        val hist = IntArray(256)
        for (v in src) hist[v.toInt().coerceIn(0, 255)]++
        val n = src.size
        val lo = percentile(hist, n * 0.01)
        var hi = percentile(hist, n * 0.995)
        if (hi < lo + 12) hi = lo + 12
        val lut =
            IntArray(256) { v ->
                val t = ((v - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)
                (t.toDouble().pow(GAMMA) * 255).toInt()
            }
        val px =
            IntArray(n) { i ->
                val g = lut[src[i].toInt().coerceIn(0, 255)]
                val rb = g * 3 / 10
                (0xFF shl 24) or (rb shl 16) or (g shl 8) or rb
            }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun percentile(hist: IntArray, target: Double): Int {
        var cumulative = 0
        for (i in hist.indices) {
            cumulative += hist[i]
            if (cumulative >= target) return i
        }
        return 255
    }

    companion object {
        private const val TARGET_LONG_SIDE = 320
        private const val CELL = 10
        private const val NIGHT_BLEND = 0.3f
        private const val MIN_THRESHOLD = 14f
        private const val MIN_CELLS = 2
        private const val GAMMA = 0.55
    }
}
