package com.nila.npu

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Who gets the accelerator, stated as tests because the rule is a product
 * decision rather than a technical one: the camera wins, and the chat model
 * waits on the CPU rather than failing.
 */
class NpuArbiterTest {

    @Before
    fun reset() = NpuArbiter.reset()

    @After
    fun tidy() = NpuArbiter.reset()

    @Test
    fun `vision takes the npu when nothing is generating`() {
        assertTrue(NpuArbiter.requestForVision(now = 0))
        assertTrue(NpuArbiter.currentHolder == NpuArbiter.Tenant.VISION)
    }

    @Test
    fun `vision does not cut into a reply being written`() {
        NpuArbiter.chatGenerationStarted()
        assertFalse(NpuArbiter.requestForVision(now = 0))
        // And the chat model is told to leave once it is done.
        assertTrue(NpuArbiter.chatOnCpu.value)

        NpuArbiter.chatGenerationFinished()
        assertTrue(NpuArbiter.requestForVision(now = 1_000))
    }

    @Test
    fun `chat stays on the cpu while vision holds it`() {
        NpuArbiter.requestForVision(now = 0)
        assertFalse(NpuArbiter.chatMayUseNpu(now = 1_000))
        assertTrue(NpuArbiter.chatOnCpu.value)
    }

    @Test
    fun `chat does not grab it back the moment a look ends`() {
        NpuArbiter.requestForVision(now = 0)
        NpuArbiter.releaseVision(now = 10_000)

        // Straight after: still the camera's, because looks come in runs and
        // reloading a 2B model between them costs more than the CPU does.
        assertFalse(NpuArbiter.chatMayUseNpu(now = 10_000 + NpuArbiter.GRACE_MS - 1))
        assertTrue(NpuArbiter.chatMayUseNpu(now = 10_000 + NpuArbiter.GRACE_MS))
    }
}
