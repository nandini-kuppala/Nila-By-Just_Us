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
    }

    data class Values(
        val modelEnabled: Boolean = true,
        val nightPreference: DayNight.Preference = DayNight.Preference.AUTO,
        val distractionEnabled: Boolean = false,
        val torchEnabled: Boolean = false,
    )

    val values: Flow<Values> = context.visionStore.data.map { prefs ->
        Values(
            modelEnabled = prefs[MODEL] ?: true,
            nightPreference = prefs[NIGHT]?.let { name ->
                runCatching { DayNight.Preference.valueOf(name) }.getOrDefault(DayNight.Preference.AUTO)
            } ?: DayNight.Preference.AUTO,
            distractionEnabled = prefs[DISTRACTION] ?: false,
            torchEnabled = prefs[TORCH] ?: false,
        )
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
