package com.nila.vision

/**
 * Whether the cot is in daylight or in the dark, and when to admit it changed.
 *
 * The camera lane behaves differently in the two: in daylight the frame is worth
 * sending to a vision model as it stands, and in a dark nursery it is mostly
 * sensor noise until [NightVision] has lifted it. Something has to decide which
 * of those is true, and the honest answer comes from two sources that fail in
 * different ways.
 *
 * The phone's light sensor is the better signal and the one a parent would
 * expect to matter -- but it faces the ceiling while the camera faces the cot,
 * so a lamp behind the phone can read 40 lux at a sensor looking at a cot in
 * shadow. The frame's own mean brightness is the second opinion, and it is the
 * one that matches what the model will actually be shown. Either can call for
 * night; both have to agree before the lane goes back to day, because being
 * wrongly in night mode costs a little processing and being wrongly in day mode
 * costs the picture.
 *
 * Nothing switches on a single reading. A nappy change at 3am turns the big
 * light on for ninety seconds, and a lane that re-tuned itself twice in that
 * window would spend the night re-tuning; [dwellMs] is what makes a change have
 * to persist before it is believed.
 *
 * Pure, so the whole table of cases can be tested on the JVM without a phone,
 * a light sensor or a dark room.
 */
class DayNight(
    private val config: Config = Config(),
) {

    data class Config(
        /** Below this many lux, the room is dark enough for night mode. */
        val nightLux: Float = 10f,
        /** Above this many lux, it is daylight again. */
        val dayLux: Float = 30f,
        /** Mean frame brightness (0..255) that reads as a dark picture. */
        val nightLuma: Float = 45f,
        /** Mean frame brightness that reads as a well-lit picture. */
        val dayLuma: Float = 80f,
        /** How long a change has to persist before it is acted on. */
        val dwellMs: Long = 20_000L,
    )

    /** What the parent asked for, which overrides the sensors when it is not AUTO. */
    enum class Preference { AUTO, ALWAYS_DAY, ALWAYS_NIGHT }

    @Volatile
    var preference: Preference = Preference.AUTO

    /** The mode in force. Starts in DAY so a watch started at noon does not flicker. */
    var mode: LightMode = LightMode.DAY
        private set

    /** The candidate waiting out its dwell, and since when. */
    private var pending: LightMode? = null
    private var pendingSince = 0L

    /**
     * Fold one frame's evidence in.
     *
     * @param lux the light sensor, or null on a phone that has none.
     * @param meanLuma mean brightness of the analysed frame, 0..255.
     * @return the mode now in force.
     */
    fun update(lux: Float?, meanLuma: Float, now: Long): LightMode {
        when (preference) {
            Preference.ALWAYS_DAY -> return settle(LightMode.DAY)
            Preference.ALWAYS_NIGHT -> return settle(LightMode.NIGHT)
            Preference.AUTO -> Unit
        }

        val candidate = when {
            // Either witness may call for night.
            (lux != null && lux <= config.nightLux) || meanLuma <= config.nightLuma -> LightMode.NIGHT
            // Both have to agree before the picture is treated as daylight again.
            (lux == null || lux >= config.dayLux) && meanLuma >= config.dayLuma -> LightMode.DAY
            // In between: whatever is already in force.
            else -> mode
        }

        if (candidate == mode) {
            pending = null
            return mode
        }
        if (pending != candidate) {
            pending = candidate
            pendingSince = now
            return mode
        }
        if (now - pendingSince >= config.dwellMs) {
            mode = candidate
            pending = null
        }
        return mode
    }

    /** Force a mode, used by the preference branches and when the watch restarts. */
    private fun settle(target: LightMode): LightMode {
        mode = target
        pending = null
        return mode
    }

    fun reset() {
        mode = LightMode.DAY
        pending = null
        pendingSince = 0L
    }
}

/** Daylight or dark, as the rest of the camera lane needs to ask it. */
enum class LightMode { DAY, NIGHT }
