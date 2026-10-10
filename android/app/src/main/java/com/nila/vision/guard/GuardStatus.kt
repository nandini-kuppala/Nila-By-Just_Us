package com.nila.vision.guard

import com.nila.vision.VisionSettings

/** A box in normalised (0..1) frame coordinates, as the screen draws it. */
data class NormBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Who raised the alert that is on now. */
enum class AlertSource(val label: String) {
    BASE("Base watch"),
    VISION("Danger AI"),
    ZONE("Safe zone"),
}

/** The vision model, as the screen needs to say it. */
sealed interface VlmStatus {
    data object Off : VlmStatus
    data object NotInstalled : VlmStatus
    data object DisabledInSettings : VlmStatus
    data class Loading(val attempt: Int, val of: Int) : VlmStatus
    data object Ready : VlmStatus
    data class Failed(val reason: String) : VlmStatus
}

/** The last thing the vision model said, after the guard rails. */
data class LookResult(
    val verdict: Verdict,
    val activity: String,
    val hazard: String,
    val category: String,
    val note: String,
    val modelMs: Long,
    val at: Long,
    val nightEnhanced: Boolean,
)

/**
 * Everything the guard modes (from Baby Guard CCTV) add to the watch, in one object hung off
 * [com.nila.vision.WatchState] so that nothing that already reads the watch state has to change.
 */
data class GuardStatus(
    val baseOn: Boolean = true,
    val vlmOn: Boolean = false,
    val zoneOn: Boolean = false,
    val alertsOn: Boolean = true,
    val musicOn: Boolean = true,
    val flashOn: Boolean = true,
    val source: VisionSettings.Source = VisionSettings.Source.PHONE,
    val lens: VisionSettings.Lens = VisionSettings.Lens.MAIN,
    val cctvUrl: String = VisionSettings.DEMO_CCTV_URL,

    /** Night vision in force right now (Baby Guard's rule: light sensor + picture, held during an alert). */
    val night: Boolean = false,
    val lux: Float? = null,
    val luma: Float = 0f,
    val fps: Float = 0f,
    val thermal: Int = 0,

    val vlm: VlmStatus = VlmStatus.Off,
    val looking: Boolean = false,
    val lastLook: LookResult? = null,
    val looks: Int = 0,

    val zoneStatus: ZoneRule.Status = ZoneRule.Status.NONE,
    val outFraction: Float = 0f,
    val babyBox: NormBox? = null,
    val detector: String = "-",

    /** The alert on now, or null. */
    val alert: AlertSource? = null,
    val alertReason: String = "",
    val alertStartedAt: Long = 0,
    /** "Distract" is playing (lullaby + ring light) without an alert. */
    val distracting: Boolean = false,

    val cctvConnected: Boolean = false,
    val cctvFeedLost: Boolean = false,
    val cctvWidth: Int = 0,
    val cctvHeight: Int = 0,
    /** Ultra-wide lens could not be opened, so the main lens is used. */
    val lensNote: String? = null,
) {
    /** Width / height of the picture the zone and boxes are drawn on. */
    val aspect: Float
        get() = when {
            !source.remote -> 3f / 4f
            cctvWidth > 0 && cctvHeight > 0 -> cctvWidth.toFloat() / cctvHeight
            else -> 16f / 9f // most CCTV cameras, until the stream says otherwise
        }
}
