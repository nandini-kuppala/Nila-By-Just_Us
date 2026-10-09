package com.nila

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nila.vision.DayNight
import com.nila.vision.LightMode
import com.nila.vision.NightVision
import com.nila.vision.VisionSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * The two pieces of the vision lane that cannot be tested on the JVM.
 *
 * [NightVision] is arithmetic on `android.graphics.Bitmap`, whose JVM stub
 * returns zero for every pixel, and [VisionSettings] is DataStore on a real
 * filesystem. Everything else in the lane is pure and covered by the unit
 * tests; these are the two that need a device under them, so they are here
 * rather than left as the only untested code in the feature.
 */
@RunWith(AndroidJUnit4::class)
class NightVisionDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** A dim, noisy frame: what a nursery actually gives the camera at 3am. */
    private fun darkFrame(seed: Int, base: Int = 18, size: Int = 64): Bitmap {
        val random = Random(seed)
        val pixels = IntArray(size * size) {
            val n = base + random.nextInt(-6, 7)
            val v = n.coerceIn(0, 255)
            Color.rgb(v, v, v)
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    @Test
    fun enhance_lifts_a_dark_frame() {
        val night = NightVision()
        val frame = darkFrame(seed = 1)
        val before = night.meanLuma(frame)
        val lifted = night.enhance(frame)
        val after = night.meanLuma(lifted)

        assertTrue("a frame at $before should be dark to begin with", before < 40f)
        assertTrue("$before should have been lifted, got $after", after > before * 1.5f)
        assertEquals(frame.width, lifted.width)
        assertEquals(frame.height, lifted.height)
    }

    /**
     * The point of the temporal average: the same scene, frame after frame,
     * should settle rather than flicker, because sensor noise is what differs
     * between them and the room is not.
     */
    @Test
    fun repeated_frames_of_one_scene_settle() {
        val night = NightVision()
        val lumas = (1..6).map { night.meanLuma(night.enhance(darkFrame(seed = it))) }
        val early = kotlin.math.abs(lumas[1] - lumas[0])
        val late = kotlin.math.abs(lumas[5] - lumas[4])
        assertTrue("frame-to-frame swing should shrink: $lumas", late <= early + 2f)
    }

    /** Green for the screen, and only green: red and blue stay well under it. */
    @Test
    fun night_view_is_green() {
        val view = NightVision().nightView(darkFrame(seed = 7))
        val pixel = view.getPixel(view.width / 2, view.height / 2)
        val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
        assertTrue("expected green-dominant, got ($r,$g,$b)", g > r && g > b)
        assertEquals("red and blue should match each other", r, b)
    }

    /** A size change must not blend the new frame against the old accumulator. */
    @Test
    fun a_frame_of_a_new_size_does_not_crash() {
        val night = NightVision()
        night.enhance(darkFrame(seed = 2, size = 64))
        val other = night.enhance(darkFrame(seed = 3, size = 32))
        assertEquals(32, other.width)
    }

    /** What the parent chose has to survive the process, which is the whole point. */
    @Test
    fun settings_round_trip_through_datastore() = runBlocking {
        val settings = VisionSettings(context)
        try {
            settings.setNightPreference(DayNight.Preference.ALWAYS_NIGHT)
            settings.setDistractionEnabled(true)
            val read = VisionSettings(context).current
            assertEquals(DayNight.Preference.ALWAYS_NIGHT, read.nightPreference)
            assertTrue(read.distractionEnabled)
        } finally {
            settings.setNightPreference(DayNight.Preference.AUTO)
            settings.setDistractionEnabled(false)
        }
    }

    /** The override is read on the device the same way it is in the unit tests. */
    @Test
    fun an_override_beats_a_bright_room() {
        val dayNight = DayNight()
        dayNight.preference = DayNight.Preference.ALWAYS_NIGHT
        assertEquals(LightMode.NIGHT, dayNight.update(lux = 400f, meanLuma = 200f, now = 0L))
    }
}
