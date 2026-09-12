package com.nila.vision

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Loading is not the same as broken.
 *
 * The watch screen showed "Posture is unavailable on this phone" on a phone
 * whose posture readout said "Sitting" at the same moment. The flag behind the
 * warning was read once, immediately after the camera was asked to start, at a
 * point where the pose landmarker had not been built yet -- so it was false,
 * and nothing ever asked again.
 *
 * The fix is two parts and this covers the second: the flag is refreshed from
 * every analysed frame, and the warning waits for a frame before believing it.
 * No frame is analysed until both detectors exist, so a zero frame count means
 * warming up and cannot mean failed.
 */
class PoseStatusTest {

    @Test
    fun `no frames yet means loading, not unavailable`() {
        val state = WatchState(running = true, framesAnalysed = 0, poseAvailable = false)
        assertEquals(PoseStatus.LOADING, state.poseStatus)
    }

    @Test
    fun `a frame that went through the model reports it running`() {
        val state = WatchState(running = true, framesAnalysed = 1,
                               poseAvailable = true, poseDelegate = "GPU")
        assertEquals(PoseStatus.RUNNING, state.poseStatus)
    }

    @Test
    fun `frames analysed without pose is a real failure`() {
        val state = WatchState(running = true, framesAnalysed = 40, poseAvailable = false)
        assertEquals(PoseStatus.UNAVAILABLE, state.poseStatus)
    }

    @Test
    fun `pose arriving late is picked up rather than latched`() {
        var state = WatchState(running = true)
        assertEquals(PoseStatus.LOADING, state.poseStatus)
        // What the frame callback now does, and what nothing did before.
        state = state.copy(framesAnalysed = 1, poseAvailable = true, poseDelegate = "CPU")
        assertEquals(PoseStatus.RUNNING, state.poseStatus)
    }
}
