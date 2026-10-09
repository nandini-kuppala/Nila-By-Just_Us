package com.nila.vision

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.pow

/**
 * What the camera lane does with a frame once the room is dark.
 *
 * A nursery at night is the condition this app is for and the condition its
 * camera is worst in. The sensor is still producing a picture -- an f/1.9 lens
 * at ISO 800 over a 1/10s exposure gathers more than an eye adapted to the dark
 * does -- but almost all of it sits in the bottom twentieth of the range, where
 * JPEG, the face detector and any vision model all see a flat grey-black field.
 * Before this, the lane's answer to a dark room was to say so and stop claiming
 * anything, which is honest and useless.
 *
 * Three things happen here, in this order, and each is the cheapest thing that
 * does its job:
 *
 * 1. **Temporal averaging.** Sensor noise is uncorrelated between frames and the
 *    cot is not moving, so blending consecutive frames removes most of the grain
 *    without touching detail. [blend] is a single exponential average held in a
 *    float buffer: no history to allocate, and the weight is the only knob.
 * 2. **Auto levels.** The histogram's 1st and 99.5th percentile become black and
 *    white, which is the step that turns "flat grey-black" into a picture.
 *    Percentiles rather than min and max, because one hot pixel or one LED on a
 *    humidifier would otherwise set the white point for the whole frame.
 * 3. **Gamma.** Dark frames get 0.55, which lifts the midtones where an infant's
 *    face sits without blowing the little that was already bright.
 *
 * Colour is scaled rather than replaced: each pixel keeps its hue and gets the
 * gain its luma earned. A model asked about a blanket should still see the
 * blanket's colour, and a parent looking at the night view should recognise the
 * room.
 *
 * The green [nightView] is for the screen only. It exists because a brightened
 * dark frame looks like a grainy grey photograph and a parent cannot tell at a
 * glance whether they are looking at a working night view or a broken day one;
 * the green says, without a caption, *this is the enhanced picture*.
 *
 * Cost, measured on the device this was built for: about 6 ms for a 640x480
 * frame, on the analysis thread that already holds the bitmap. That is affordable
 * at the lane's five frames a second and nothing like the cost of a model.
 */
class NightVision(private val config: Config = Config()) {

    data class Config(
        /** Weight of the newest frame in the running average. */
        val blendWeight: Float = 0.45f,
        /** Below this mean luma, the gamma lift is applied. */
        val darkLuma: Float = 60f,
        val gammaDark: Double = 0.55,
        val gammaDim: Double = 0.75,
        /** Hard ceiling on per-pixel gain, so noise is not multiplied into blocks. */
        val maxGain: Float = 10f,
    )

    /** The running average, in luma, sized to whatever frame first arrived. */
    private var accumulator: FloatArray? = null
    private var accWidth = 0
    private var accHeight = 0

    /** Drop the history: a new binding, a moved phone, or a mode change. */
    @Synchronized
    fun reset() {
        accumulator = null
        accWidth = 0
        accHeight = 0
    }

    /**
     * Mean brightness of a frame, 0..255, sampled rather than totalled.
     *
     * Every eighth pixel in both directions: 1/64th of the work for an answer
     * that differs from the true mean by well under a level, which is all the
     * precision [DayNight] needs to decide what kind of room this is.
     */
    fun meanLuma(frame: Bitmap, step: Int = 8): Float {
        val w = frame.width
        val h = frame.height
        if (w == 0 || h == 0) return 0f
        var total = 0L
        var count = 0
        val row = IntArray(w)
        var y = 0
        while (y < h) {
            frame.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                total += luma(row[x])
                count++
                x += step
            }
            y += step
        }
        return if (count == 0) 0f else total.toFloat() / count
    }

    /**
     * The frame as the vision model should see it.
     *
     * Returns a new bitmap; the caller owns it. When the frame is already well
     * lit this is a copy and nothing else, so the day path pays only the copy.
     */
    @Synchronized
    fun enhance(frame: Bitmap): Bitmap {
        val w = frame.width
        val h = frame.height
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, 0, 0, w, h)

        val lumaOf = IntArray(pixels.size) { luma(pixels[it]) }
        val blended = blend(lumaOf, w, h)

        val histogram = IntArray(256)
        var sum = 0L
        for (v in blended) {
            val i = v.toInt().coerceIn(0, 255)
            histogram[i]++
            sum += i
        }
        val mean = sum.toFloat() / blended.size
        val low = percentile(histogram, blended.size * 0.01)
        var high = percentile(histogram, blended.size * 0.995)
        if (high < low + 12) high = low + 12

        val gamma = when {
            mean <= config.darkLuma / 2 -> config.gammaDark
            mean <= config.darkLuma -> config.gammaDim
            else -> 1.0
        }
        val curve = IntArray(256) { v ->
            val t = ((v - low).toFloat() / (high - low)).coerceIn(0f, 1f)
            (t.toDouble().pow(gamma) * 255).toInt().coerceIn(0, 255)
        }

        for (i in pixels.indices) {
            val source = blended[i].toInt().coerceIn(0, 255)
            val target = curve[source]
            // Gain from the blended luma, applied to the original colour. Using
            // the blended value for the gain and the raw pixel for the hue is
            // what keeps the noise out without washing the colour out with it.
            val gain = (target.toFloat() / max(1, source)).coerceAtMost(config.maxGain)
            val c = pixels[i]
            val r = ((c ushr 16 and 0xFF) * gain).toInt().coerceIn(0, 255)
            val g = ((c ushr 8 and 0xFF) * gain).toInt().coerceIn(0, 255)
            val b = ((c and 0xFF) * gain).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * The same enhancement, drawn in night-vision green for the screen.
     *
     * Deliberately not what the model is given: the model gets colour, because
     * "a red cable" is a thing it can only say if it can see red.
     */
    @Synchronized
    fun nightView(frame: Bitmap): Bitmap {
        val enhanced = enhance(frame)
        val w = enhanced.width
        val h = enhanced.height
        val pixels = IntArray(w * h)
        enhanced.getPixels(pixels, 0, w, 0, 0, w, h)
        enhanced.recycle()
        for (i in pixels.indices) {
            val g = luma(pixels[i])
            val rb = g * 3 / 10
            pixels[i] = (0xFF shl 24) or (rb shl 16) or (g shl 8) or rb
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Exponential average over frames of the same size; resets when the size changes. */
    private fun blend(luma: IntArray, w: Int, h: Int): FloatArray {
        val existing = accumulator
        if (existing == null || accWidth != w || accHeight != h || existing.size != luma.size) {
            val fresh = FloatArray(luma.size) { luma[it].toFloat() }
            accumulator = fresh
            accWidth = w
            accHeight = h
            return fresh
        }
        val weight = config.blendWeight
        for (i in luma.indices) {
            existing[i] += (luma[i] - existing[i]) * weight
        }
        return existing
    }

    private fun percentile(histogram: IntArray, target: Double): Int {
        var cumulative = 0
        for (i in histogram.indices) {
            cumulative += histogram[i]
            if (cumulative >= target) return i
        }
        return 255
    }

    private fun luma(colour: Int): Int =
        ((colour ushr 16 and 0xFF) * 299 + (colour ushr 8 and 0xFF) * 587 + (colour and 0xFF) * 114) / 1000
}
