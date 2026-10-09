package com.nila.vision

import com.nila.data.Severity

/** Where the pose landmarker has got to, as the screen needs to say it. */
enum class PoseStatus { LOADING, RUNNING, UNAVAILABLE }

/** What the camera lane is doing right now, as the UI and the watch need it. */
data class WatchState(
    val running: Boolean = false,
    val face: FaceWatcher.State = FaceWatcher.State.Starting,
    val assessment: ActivityRules.Assessment? = null,
    val motion: MotionEnergy.Reading = MotionEnergy.Reading(0f, 0f, 1f),
    val framesAnalysed: Long = 0,
    /**
     * Whether the pose landmarker is loaded and answering.
     *
     * Re-read from the camera on every analysed frame rather than sampled once
     * when the watch starts. It used to be sampled once, immediately after
     * `start()`, at a point where the detectors had not been built yet and the
     * field was necessarily false -- so the screen said posture was unavailable
     * on a phone that was, at that moment, reporting posture in the panel above
     * the warning.
     */
    val poseAvailable: Boolean = false,
    val poseDelegate: String = "-",
    val zone: SafeZone = SafeZone.DEFAULT,
    /** The phone was knocked, so every frame since is of who knows what. */
    val cameraMoved: Boolean = false,
    val lightChanged: Boolean = false,
    val roomDark: Boolean = false,
    val lux: Float? = null,
    /** True while the audio lane has a cry in progress. */
    val cryingNow: Boolean = false,
    val fault: String? = null,
    /** True while bundled footage is being replayed instead of the camera. */
    val simulated: Boolean = false,
    /** The last thing that was worth a notification, for the screen. */
    val lastAlert: String? = null,

    // ---- the scene model lane
    //
    // Everything below is about the model that is asked, occasionally, what the
    // cot actually contains. The fields above are the cheap always-on lane and
    // are unchanged: a phone with no model installed shows exactly what it did
    // before, because every one of these has a default that means "nothing yet".

    /** Daylight or dark, as [DayNight] decided from the sensor and the frame. */
    val lightMode: LightMode = LightMode.DAY,
    /** Mean brightness of the last analysed frame, 0..255. */
    val frameLuma: Float = 0f,
    /** The baby has been still long enough that the model can idle. */
    val asleep: Boolean = false,
    /** The last thing the scene model said, or null if it has not been asked. */
    val observation: SceneDescriber.Observation? = null,
    /** What the guard rails made of it, once two looks agreed. */
    val hazard: HazardRules.Verdict? = null,
    /** True while a look is running, so the screen can say so and the policy can wait. */
    val looking: Boolean = false,
    /** Which engine answered, for the settings screen and the technical record. */
    val visionBackend: String = "none",
    /**
     * Whether a scene model is installed. False means the camera lane above is
     * running normally and only the model's second opinion is missing.
     */
    val visionModelInstalled: Boolean = false,
    /** When the last look happened, and when the next is due. */
    val lastLookAt: Long = 0,
    val nextLookAt: Long = 0,
    /** What the phone last played or lit to hold the baby's attention. */
    val distraction: String? = null,
) {

    /**
     * Everything the watch currently believes, most severe first.
     *
     * A list rather than a single state, which is the substantive change from
     * the old screen: a baby who has rolled onto their front *and* crossed the
     * cot line is two facts, and the old single-state watch had to pick one and
     * throw the other away. A parent walking in on the strength of one of them
     * is prepared for the wrong thing.
     */
    val descriptions: List<String>
        get() = buildList {
            if (cameraMoved) add("The phone was moved - check what the camera can see")
            assessment?.events?.forEach { add(ActivityRules.describe(it)) }
            if (cryingNow) add("Crying")
            if (roomDark) add("The room is dark - the camera has little to work with")
            else if (lightChanged) add("The room light changed")
        }

    /** The one line for a notification, a watch face, or the top of the card. */
    val headline: String get() = when {
        !running -> "Not watching"
        fault != null -> "Camera unavailable"
        cameraMoved -> "The phone was moved"
        assessment == null -> "Getting a baseline"
        else -> {
            val primary = ActivityRules.describe(assessment.primary)
            if (cryingNow && assessment.urgent) "$primary, and crying" else primary
        }
    }

    /**
     * How loud this should be, after the audio lane is folded in.
     *
     * A posture change and a cry at the same time is the case the two lanes
     * exist to catch together: a baby who rolls over in their sleep is a
     * notification, and a baby who rolls over and starts crying has probably
     * fallen or got stuck, which is a different phone call.
     */
    val severity: Severity get() {
        val base = assessment?.severity ?: Severity.NOTE
        // A view we cannot trust cannot raise an alarm about the baby. The
        // phone being knocked is the alert, and it is not an urgent one.
        if (cameraMoved) return Severity.ATTENTION
        return when {
            cryingNow && base == Severity.URGENT -> Severity.CRITICAL
            cryingNow && base == Severity.ATTENTION -> Severity.URGENT
            else -> base
        }
    }

    /** The caveat for whatever is being claimed, or null when there is none. */
    val caveat: String?
        get() = assessment?.primary?.let { ActivityRules.caveat(it) }

    /**
     * Loading, running, or genuinely not available on this phone.
     *
     * The distinction the flag alone cannot make. No frame is analysed until
     * both detectors exist -- [CameraWatch.analyse] drops frames while they are
     * null -- so a zero frame count means the models are still loading, and
     * only a frame that has been through them can say posture is unavailable.
     * Without this the warning appeared for the second or two of warm-up on
     * every phone, including the ones where pose works.
     */
    val poseStatus: PoseStatus get() = when {
        poseAvailable -> PoseStatus.RUNNING
        framesAnalysed == 0L -> PoseStatus.LOADING
        else -> PoseStatus.UNAVAILABLE
    }

    val calibrating: Boolean
        get() = assessment == null ||
            assessment.primary == ActivityRules.Event.CALIBRATING
}
