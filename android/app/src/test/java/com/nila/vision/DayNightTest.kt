package com.nila.vision

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The day/night switch, which has to be slow in both directions.
 *
 * The failure this guards against is flapping: a nappy change at 3am turns the
 * big light on for ninety seconds, and a lane that re-tuned itself twice in that
 * window would spend the night re-tuning instead of watching.
 */
class DayNightTest {

    private val dwell = DayNight.Config().dwellMs

    @Test
    fun `starts in day`() {
        assertEquals(LightMode.DAY, DayNight().mode)
    }

    @Test
    fun `a dark room switches to night, but only after the dwell`() {
        val switch = DayNight()
        assertEquals(LightMode.DAY, switch.update(lux = 2f, meanLuma = 10f, now = 0))
        assertEquals(LightMode.DAY, switch.update(lux = 2f, meanLuma = 10f, now = dwell - 1))
        assertEquals(LightMode.NIGHT, switch.update(lux = 2f, meanLuma = 10f, now = dwell))
    }

    @Test
    fun `a brief light does not drag it back to day`() {
        val switch = DayNight()
        switch.update(2f, 10f, 0)
        switch.update(2f, 10f, dwell)
        assertEquals(LightMode.NIGHT, switch.mode)

        // Someone switches a lamp on for ten seconds.
        switch.update(200f, 150f, dwell + 1_000)
        assertEquals(LightMode.NIGHT, switch.update(200f, 150f, dwell + 10_000))
        // And off again: the pending change is forgotten.
        assertEquals(LightMode.NIGHT, switch.update(2f, 10f, dwell + 11_000))
    }

    @Test
    fun `a light left on does return it to day`() {
        val switch = DayNight()
        switch.update(2f, 10f, 0)
        switch.update(2f, 10f, dwell)
        assertEquals(LightMode.NIGHT, switch.mode)

        switch.update(200f, 150f, 100_000)
        assertEquals(LightMode.DAY, switch.update(200f, 150f, 100_000 + dwell))
    }

    @Test
    fun `either witness can call for night`() {
        // A bright sensor reading but a black frame: the camera is what matters,
        // because the camera is what the model will be shown.
        val switch = DayNight()
        switch.update(lux = 300f, meanLuma = 8f, now = 0)
        assertEquals(LightMode.NIGHT, switch.update(lux = 300f, meanLuma = 8f, now = dwell))
    }

    @Test
    fun `both have to agree before it goes back to day`() {
        val switch = DayNight()
        switch.update(2f, 10f, 0)
        switch.update(2f, 10f, dwell)

        // Sensor says daylight, frame is still dark: stay in night.
        switch.update(lux = 300f, meanLuma = 20f, now = 60_000)
        assertEquals(LightMode.NIGHT, switch.update(lux = 300f, meanLuma = 20f, now = 60_000 + dwell))
    }

    @Test
    fun `a phone with no light sensor runs on the frame alone`() {
        val switch = DayNight()
        switch.update(lux = null, meanLuma = 12f, now = 0)
        assertEquals(LightMode.NIGHT, switch.update(lux = null, meanLuma = 12f, now = dwell))
        switch.update(lux = null, meanLuma = 180f, now = 90_000)
        assertEquals(LightMode.DAY, switch.update(lux = null, meanLuma = 180f, now = 90_000 + dwell))
    }

    @Test
    fun `the parent's override wins immediately`() {
        val switch = DayNight()
        switch.preference = DayNight.Preference.ALWAYS_NIGHT
        assertEquals(LightMode.NIGHT, switch.update(lux = 500f, meanLuma = 200f, now = 0))

        switch.preference = DayNight.Preference.ALWAYS_DAY
        assertEquals(LightMode.DAY, switch.update(lux = 0f, meanLuma = 0f, now = 1))
    }
}
