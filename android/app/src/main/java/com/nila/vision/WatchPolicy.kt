package com.nila.vision

import com.nila.data.Severity

/**
 * When the vision model is worth waking up, and when the cheap watch is enough.
 *
 * ### The thing being defended
 *
 * The always-on lane is a face detector and a frame difference: a few megabytes,
 * five frames a second, all night. The vision model is three orders of magnitude
 * more expensive -- on the NPU it is about a second of compute and a shared
 * accelerator the chat model also wants; on the fallback path it is over a
 * gigabyte resident. Running it on a schedule all night would flatten the phone
 * by morning, and a monitor that is dead at 5am is not a monitor.
 *
 * So the model is not a sensor. It is a second opinion, asked when the cheap
 * lane has something it cannot resolve, and otherwise asked rarely.
 *
 * ### What the cheap lane can already answer
 *
 * A sleeping infant produces almost no frame difference, and that is the
 * majority of the night. [asleep] is that observation and nothing more: motion
 * under the stillness threshold for [Config.asleepAfterMs] with nobody crying.
 * While it holds, the model is asked once every [Config.nightIdleMs] -- a
 * quarter-hourly confirmation that the picture still shows what it did, not a
 * stream of inferences about a baby who has not moved.
 *
 * ### What wakes it
 *
 * The baby waking is the event this is built around, and it has three tells the
 * cheap lane can see before the model is needed: motion rising past a multiple
 * of its own baseline, a cry from the audio lane, and the pose rules reporting
 * a posture or zone event. Any of them cuts straight to a look, subject only to
 * [Config.minGapMs] so a baby who squirms for a minute is not twenty looks.
 *
 * ### What it refuses
 *
 * A look costs the same whether or not the frame is worth anything, so the
 * policy declines when the frame is known bad: the phone was knocked, the model
 * is not loaded, or a look is already running. The budget is the last defence --
 * [Config.maxPerHour] caps a pathological night at a number the battery can pay.
 *
 * Pure, so every branch is a JVM test with a clock the test controls.
 */
class WatchPolicy(private val config: Config = Config()) {

    data class Config(
        /** Never two looks closer together than this, whatever happened. */
        val minGapMs: Long = 12_000L,
        /** Daylight, baby awake and moving: a look at this cadence. */
        val dayActiveMs: Long = 90_000L,
        /** Daylight, baby settled: slower. */
        val daySettledMs: Long = 5 * 60_000L,
        /** Dark, baby awake: the interesting case, so closer together. */
        val nightActiveMs: Long = 60_000L,
        /** Dark, baby asleep: a confirmation, not a watch. */
        val nightIdleMs: Long = 15 * 60_000L,
        /** Motion this far above baseline counts as the baby waking. */
        val wakeMotionRatio: Float = 2.5f,
        /** Consecutive frames of that before it is believed. */
        val wakeFrames: Int = 2,
        /** Motion below this (relative to baseline) is a still baby. */
        val stillRatio: Float = 0.6f,
        /** How long still before the baby is treated as asleep. */
        val asleepAfterMs: Long = 2 * 60_000L,
        /** Battery guard: the most looks allowed in any rolling hour. */
        val maxPerHour: Int = 30,
    )

    companion object {
        /** "This has not happened yet", distinct from the timestamp zero. */
        private const val NEVER = Long.MIN_VALUE
    }

    /** Why a look was asked for. Shown to the parent and written to the log. */
    enum class Reason {
        FIRST_LOOK,
        CRY,
        POSTURE,
        MOTION_WAKE,
        SCHEDULED,
        MANUAL,
    }

    /** One frame's worth of evidence, as the policy needs it. */
    data class Inputs(
        val now: Long,
        val mode: LightMode,
        /** Frame difference relative to the lane's own baseline. */
        val motionRelative: Float,
        val crying: Boolean,
        /** The pose rules' verdict for this frame, if there is one yet. */
        val event: ActivityRules.Event? = null,
        val severity: Severity = Severity.NOTE,
        /** The accelerometer says the view cannot be trusted. */
        val cameraMoved: Boolean = false,
        val modelReady: Boolean = true,
        val lookInProgress: Boolean = false,
    )

    private var lastLookAt = 0L

    /**
     * When the baby was last seen to move, or [NEVER].
     *
     * A sentinel rather than zero, because zero is a perfectly good timestamp
     * and conflating the two is how "the watch started at t=0" became "the baby
     * has been still since the beginning of time".
     */
    private var stillSince = NEVER
    private var wakeRun = 0
    private var everLooked = false
    private var lastCrying = false
    private var lastEvent: ActivityRules.Event? = null

    /** Timestamps of recent looks, for the hourly budget. */
    private val looks = ArrayDeque<Long>()

    /** True while the baby has been still long enough to treat as asleep. */
    var asleep: Boolean = false
        private set

    /** When the next scheduled look is due, for the screen to show a countdown. */
    var nextDueAt: Long = 0L
        private set

    /**
     * @return the reason to look now, or null to stay with the cheap lane.
     *
     * Synchronised because two threads reach this object: frames arrive on the
     * camera executor, and a parent tapping "Look now" arrives on the main
     * thread. Both mutate the same counters.
     */
    @Synchronized
    fun decide(input: Inputs): Reason? {
        updateSleep(input)
        trimBudget(input.now)

        val cadence = cadence(input)
        nextDueAt = if (!everLooked) input.now else lastLookAt + cadence

        if (input.lookInProgress || !input.modelReady) return null
        // A frame from a phone that has just been knocked is a frame of a wall.
        if (input.cameraMoved) return null
        // The gap is between looks, so it cannot apply before the first one --
        // which would otherwise leave a watch blind for its first twelve seconds.
        if (everLooked && input.now - lastLookAt < config.minGapMs) return null
        if (looks.size >= config.maxPerHour) return null

        val reason = when {
            !everLooked -> Reason.FIRST_LOOK
            // The audio lane has just started a cry: the camera's opinion of why
            // is worth more now than at any other moment.
            input.crying && !lastCrying -> Reason.CRY
            // A posture or zone event the cheap rules raised, newly.
            input.event != null && input.event != lastEvent && worthLooking(input.event, input.severity) ->
                Reason.POSTURE
            wakeRun >= config.wakeFrames -> Reason.MOTION_WAKE
            input.now - lastLookAt >= cadence -> Reason.SCHEDULED
            else -> null
        }

        lastCrying = input.crying
        if (input.event != null) lastEvent = input.event
        if (reason != null) wakeRun = 0
        return reason
    }

    /** Record that a look happened, whatever its result. */
    @Synchronized
    fun looked(now: Long) {
        lastLookAt = now
        everLooked = true
        looks.addLast(now)
        trimBudget(now)
    }

    private fun cadence(input: Inputs): Long = when {
        input.mode == LightMode.NIGHT && asleep && !input.crying -> config.nightIdleMs
        input.mode == LightMode.NIGHT -> config.nightActiveMs
        asleep && !input.crying -> config.daySettledMs
        else -> config.dayActiveMs
    }

    private fun worthLooking(event: ActivityRules.Event, severity: Severity): Boolean =
        severity.level >= Severity.ATTENTION.level &&
            event != ActivityRules.Event.CALIBRATING &&
            event != ActivityRules.Event.CAMERA_MOVED

    private fun updateSleep(input: Inputs) {
        val moving = input.motionRelative >= config.wakeMotionRatio
        wakeRun = if (moving) wakeRun + 1 else 0

        val still = input.motionRelative in 0f..config.stillRatio && !input.crying
        if (still) {
            if (stillSince == NEVER) stillSince = input.now
            if (!asleep && input.now - stillSince >= config.asleepAfterMs) asleep = true
        } else {
            stillSince = NEVER
            // Waking is immediate: a baby who moved is awake until they are still again.
            if (moving || input.crying) asleep = false
        }
    }

    private fun trimBudget(now: Long) {
        while (looks.isNotEmpty() && now - looks.first() > 60 * 60_000L) looks.removeFirst()
    }

    @Synchronized
    fun reset() {
        lastLookAt = 0
        stillSince = NEVER
        wakeRun = 0
        everLooked = false
        lastCrying = false
        lastEvent = null
        asleep = false
        nextDueAt = 0
        looks.clear()
    }
}
