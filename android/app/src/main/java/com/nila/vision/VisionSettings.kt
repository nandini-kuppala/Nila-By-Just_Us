package com.nila.vision

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.visionStore by preferencesDataStore("vision")

/**
 * The switches for everything the camera lane learned to do.
 *
 * Each one is here rather than compiled in because each one is a judgement a
 * parent is entitled to make differently from the default:
 *
 *  - **The scene model** costs the NPU and some battery. On by default, because
 *    a monitor that cannot say what it is looking at is the thing this release
 *    set out to fix -- but a phone with no bundle installed never loads it
 *    anyway, so the switch mostly matters to somebody who has one and wants it off.
 *  - **Night mode** is automatic by default. The overrides exist for the two
 *    rooms that fool the sensor: a nursery lit by a bright hall light through an
 *    open door, and one with a lamp behind the phone.
 *  - **Distraction** is off by default, and deliberately. It makes the phone
 *    play a sound and light up at a baby, which is an intervention; a parent
 *    should choose it rather than discover it at 2am.
 *  - **The torch** is off even when distraction is on, for the same reason
 *    again: it is a bright light into a cot, right for a crawler reaching for a
 *    socket and wrong for a drowsy baby at 3am.
 *
 * DataStore rather than Room, beside [SafeZoneStore]: these are four small
 * preferences read by a service, with nothing to do with the care log.
 */
class VisionSettings(private val context: Context) {

    companion object {
        private val MODEL = booleanPreferencesKey("scene_model_enabled")
        private val NIGHT = stringPreferencesKey("night_preference")
        private val DISTRACTION = booleanPreferencesKey("distraction_enabled")
        private val TORCH = booleanPreferencesKey("distraction_torch")

        // The guard modes, ported from Baby Guard CCTV. Each runs on its own or with the others.
        private val BASE_ON = booleanPreferencesKey("guard_base_on")
        private val VLM_ON = booleanPreferencesKey("guard_vlm_on")
        private val ZONE_ON = booleanPreferencesKey("guard_zone_on")
        private val ALERTS_ON = booleanPreferencesKey("guard_alerts_on")
        private val SOURCE = stringPreferencesKey("guard_source")
        private val LENS = stringPreferencesKey("guard_lens")
        private val CCTV_URL = stringPreferencesKey("guard_cctv_address") // default = the built-in demo video

        const val DEMO_CCTV_URL = com.nila.vision.guard.CctvSource.DEMO_URL
    }

    /** Where the frames come from: this phone's camera, or a CCTV / IP camera over RTSP. */
    enum class Source { PHONE, CCTV }

    /** The phone's main lens, or its ultra-wide lens (which vivo hides from CameraX). */
    enum class Lens { MAIN, WIDE }

    data class Values(
        /**
         * The master switch for the vision model ("Describe what it sees" in Settings). Off: the Danger AI
         * mode and "Look now" do not run.
         */
        val modelEnabled: Boolean = true,
        val nightPreference: DayNight.Preference = DayNight.Preference.AUTO,
        /** The lullaby with alerts and on "Distract" (Baby Guard's music switch). Plays at media volume. */
        val distractionEnabled: Boolean = true,
        /** The torch strobe during alerts and on "Distract". */
        val torchEnabled: Boolean = true,
        /** Base: face, pose and movement rules, no vision model. */
        val baseOn: Boolean = true,
        /** Danger AI: the vision model looks back to back and alerts on the first danger. */
        val vlmOn: Boolean = false,
        /** Safe zone: person detector + tracker, alert when the baby leaves the drawn zone. */
        val zoneOn: Boolean = false,
        /** Lights, flashing screen and music when a mode raises an alert. Notifications always go out. */
        val alertsOn: Boolean = true,
        val source: Source = Source.PHONE,
        val lens: Lens = Lens.MAIN,
        val cctvUrl: String = DEMO_CCTV_URL,
    ) {
        /** Nothing is switched on: the watch has nothing to do. */
        val noMode: Boolean get() = !baseOn && !vlmOn && !zoneOn
    }

    val values: Flow<Values> = context.visionStore.data.map { prefs ->
        Values(
            modelEnabled = prefs[MODEL] ?: true,
            nightPreference = prefs[NIGHT]?.let { name ->
                runCatching { DayNight.Preference.valueOf(name) }.getOrDefault(DayNight.Preference.AUTO)
            } ?: DayNight.Preference.AUTO,
            distractionEnabled = prefs[DISTRACTION] ?: true,
            torchEnabled = prefs[TORCH] ?: true,
            baseOn = prefs[BASE_ON] ?: true,
            vlmOn = prefs[VLM_ON] ?: false,
            zoneOn = prefs[ZONE_ON] ?: false,
            alertsOn = prefs[ALERTS_ON] ?: true,
            source = prefs[SOURCE]?.let { runCatching { Source.valueOf(it) }.getOrNull() } ?: Source.PHONE,
            lens = prefs[LENS]?.let { runCatching { Lens.valueOf(it) }.getOrNull() } ?: Lens.MAIN,
            cctvUrl = prefs[CCTV_URL]?.takeIf { it.isNotBlank() } ?: DEMO_CCTV_URL,
        )
    }

    suspend fun setModes(base: Boolean? = null, vlm: Boolean? = null, zone: Boolean? = null) {
        context.visionStore.edit { p ->
            base?.let { p[BASE_ON] = it }
            vlm?.let { p[VLM_ON] = it }
            zone?.let { p[ZONE_ON] = it }
        }
    }

    suspend fun setAlertsOn(on: Boolean) {
        context.visionStore.edit { it[ALERTS_ON] = on }
    }

    suspend fun setSource(source: Source) {
        context.visionStore.edit { it[SOURCE] = source.name }
    }

    suspend fun setLens(lens: Lens) {
        context.visionStore.edit { it[LENS] = lens.name }
    }

    suspend fun setCctvUrl(url: String) {
        context.visionStore.edit { it[CCTV_URL] = url.trim() }
    }

    /**
     * The values right now, for the service binding its camera.
     *
     * Same reasoning as [SafeZoneStore.current]: the watch starts from a service
     * callback that cannot suspend, and a watch that started with the defaults
     * because a read had not finished would run the model a parent had turned
     * off. One small synchronous read per start is the cheaper mistake.
     */
    val current: Values get() = runBlocking { values.first() }

    suspend fun setModelEnabled(enabled: Boolean) {
        context.visionStore.edit { it[MODEL] = enabled }
    }

    suspend fun setNightPreference(preference: DayNight.Preference) {
        context.visionStore.edit { it[NIGHT] = preference.name }
    }

    suspend fun setDistractionEnabled(enabled: Boolean) {
        context.visionStore.edit { it[DISTRACTION] = enabled }
    }

    suspend fun setTorchEnabled(enabled: Boolean) {
        context.visionStore.edit { it[TORCH] = enabled }
    }
}
