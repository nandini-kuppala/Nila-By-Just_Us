package com.nila.npu

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

    // ---- Residency: one model on the NPU at a time --------------------------
    //
    // Measured on the phone, 2026-10-09: with Qwen built on the NPU, creating the
    // vision model segfaulted in libQnnHtpV81Stub (QnnApi::createDevice). These
    // pin the rule that prevents it.

    @Test
    fun `the vision load moves a resting chat engine off the npu first`() = runBlocking {
        var evictions = 0
        NpuArbiter.registerChatEvictor { evictions++; true }

        assertTrue(NpuArbiter.claimForVisionLoad())
        assertEquals(1, evictions)
    }

    @Test
    fun `the vision load waits for a chat engine that is busy`() = runBlocking {
        NpuArbiter.registerChatEvictor { false } // generating, or mid-build

        assertFalse(NpuArbiter.claimForVisionLoad())
    }

    @Test
    fun `every chat engine is asked, not only the last one registered`() = runBlocking {
        val asked = mutableListOf<String>()
        NpuArbiter.registerChatEvictor { asked += "old"; true }
        NpuArbiter.registerChatEvictor { asked += "new"; true }

        assertTrue(NpuArbiter.claimForVisionLoad())
        assertEquals(setOf("old", "new"), asked.toSet())
    }

    @Test
    fun `chat cannot build on the npu while the vision model is resident`() = runBlocking {
        NpuArbiter.claimForVisionLoad()
        // Long after any single look: residency is not a per-look hold.
        assertFalse(NpuArbiter.chatMayUseNpu(now = 10 * NpuArbiter.GRACE_MS))
    }

    @Test
    fun `a deferred claim still holds chat off, so the next look can evict it`() = runBlocking {
        NpuArbiter.registerChatEvictor { false }
        assertFalse(NpuArbiter.claimForVisionLoad())
        assertFalse(NpuArbiter.chatMayUseNpu(now = 10 * NpuArbiter.GRACE_MS))
    }

    @Test
    fun `chat may return once the vision model is closed and the grace has passed`() = runBlocking {
        NpuArbiter.claimForVisionLoad()
        NpuArbiter.visionUnloaded(now = 100_000)

        assertFalse(NpuArbiter.chatMayUseNpu(now = 100_000 + NpuArbiter.GRACE_MS - 1))
        assertTrue(NpuArbiter.chatMayUseNpu(now = 100_000 + NpuArbiter.GRACE_MS))
    }
}
