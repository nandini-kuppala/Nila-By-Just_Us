package com.nila.actions

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * The ring of light around the rear cameras on an iQOO, driven directly.
 *
 * ### Why this is here at all
 *
 * The phone is on a shelf facing the cot, screen away from the room. When the
 * watch sees something, the three things it can do that a baby or a passing
 * parent will actually notice are the torch, the screen, and this ring -- and
 * the ring is the one that is already pointing the right way and is not a
 * floodlight in a sleeping baby's face.
 *
 * ### Why it is reflection
 *
 * vivo exposes the ring to apps, but only through a notification: post one with
 * a light colour and the system lights the ring. That path has a rule nobody
 * documents and which makes it useless here -- the service ignores notification
 * lights while the screen is **on and unlocked**, which is exactly the state a
 * phone propped up watching a cot is in. Reading the phone's own light service
 * showed why, and showed the way round it: the same service accepts a light
 * *record* built from colour segments, and a record marked as a preview skips
 * every one of those suppression rules.
 *
 * Those classes are hidden API, so every call here goes through reflection and
 * every failure is swallowed. On a phone that is not a vivo, on a vivo whose
 * firmware has moved the classes, or on an Android build that blocks the
 * reflection, [available] is false and the alert falls back to the torch and the
 * notification. Nothing in the watch depends on this working.
 *
 * ### The colours
 *
 * Deliberately not red-only. The ring is used for two different jobs: a slow
 * amber pulse while a hazard is being shown to the parent, and a fast cycle
 * through saturated colours when the baby is being distracted away from
 * something -- and a baby looks at a changing colour far longer than a steady
 * one. Colours are `0xRRGGBB` with no alpha, because the service treats any
 * value that is negative as a signed int as "no colour at all".
 */
class VivoRingLight(private val context: Context) {

    companion object {
        private const val TAG = "VivoRingLight"

        /** `LIGHT_TYPE_MOOD_SHOW`: the one type that accepts an arbitrary record. */
        private const val LIGHT_TYPE = 502

        /** Bright, saturated, far apart on the wheel: a baby tracks the change. */
        private val PLAYFUL = intArrayOf(
            0xFF00FF.toInt(), 0x00FF00, 0x00FFFF, 0xFF0000.toInt(),
            0xFFFF00.toInt(), 0x0000FF, 0xFFFFFF.toInt(), 0xFF6600.toInt(),
        )

        /** Amber, slow: "come and look", not "emergency". */
        private val CALM = intArrayOf(0xFFA000.toInt(), 0x301000)
    }

    private var manager: Any? = null
    private var lightId = -1
    private var failure: String? = null

    /** True when the phone has this light and the hidden API is reachable. */
    val available: Boolean get() = manager != null

    init {
        manager = runCatching {
            Class.forName("com.vivo.framework.vivolight.VivoLightManager")
                .getMethod("getInstance")
                .invoke(null)
        }.onFailure {
            failure = it.message ?: it::class.java.simpleName
            Log.i(TAG, "no vivo ring light on this phone: $failure")
        }.getOrNull()
    }

    fun describe(): String = when {
        available -> "Available"
        Build.MANUFACTURER.equals("vivo", ignoreCase = true) -> "Not reachable: $failure"
        else -> "This phone has no ring light"
    }

    /**
     * Start a repeating colour sequence.
     *
     * @param playful fast saturated colours to hold a baby's attention, or a slow
     *   amber pulse to catch a parent's eye.
     * @param durationMs total run time; the service stops it on its own at the end,
     *   so a crash here cannot leave the ring on all night.
     */
    fun start(playful: Boolean, durationMs: Int): Boolean {
        val service = manager ?: return false
        stop()
        return runCatching {
            val recordClass = Class.forName("vivo.app.vivolight.VivoLightRecord")
            val segmentBase = Class.forName("vivo.app.vivolight.SegmentBase")
            val constant = Class.forName("vivo.app.vivolight.ConstantDurSegment")

            // (unused int, package, light type, isPreview, cycle, duration).
            // isPreview is the flag that makes the service play this with the
            // screen on; cycle makes it repeat for the duration.
            val record = recordClass.getConstructor(
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).newInstance(0, context.packageName, LIGHT_TYPE, true, true, durationMs)

            val addSegment = recordClass.getMethod("addSegment", segmentBase)
            val segment = constant.getConstructor(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            val colours = if (playful) PLAYFUL else CALM
            val step = if (playful) 180 else 900
            for (colour in colours) addSegment.invoke(record, segment.newInstance(colour, step))

            lightId = service.javaClass
                .getMethod("startLight", recordClass)
                .invoke(service, record) as Int
            Log.i(TAG, "ring light started, id=$lightId")
            true
        }.onFailure {
            failure = it.cause?.message ?: it.message
            Log.i(TAG, "ring light refused: $failure")
        }.getOrDefault(false)
    }

    fun stop() {
        val service = manager ?: return
        runCatching {
            if (lightId > 0) {
                service.javaClass
                    .getMethod("stopLightById", Int::class.javaPrimitiveType)
                    .invoke(service, lightId)
            }
            service.javaClass
                .getMethod("stopLightByLightType", Int::class.javaPrimitiveType, String::class.java)
                .invoke(service, LIGHT_TYPE, context.packageName)
        }
        lightId = -1
    }
}
