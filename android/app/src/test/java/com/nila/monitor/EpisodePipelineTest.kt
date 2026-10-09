package com.nila.monitor

import com.nila.data.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ladder as the parent sees it.
 *
 * These pin the two properties that make the chart worth drawing: rungs that
 * have not happened yet are shown as *coming* rather than as missing, and the
 * one currently running is distinguishable from the ones that are done. Get
 * either wrong and the chart reads as a broken app at the exact moment somebody
 * is deciding whether to trust it.
 */
class EpisodePipelineTest {

    private fun fired(vararg pairs: Pair<EpisodePipeline.Stage, Int>) =
        pairs.associate { (stage, at) ->
            stage to EpisodePipeline.Fired("did the thing", at)
        }

    @Test
    fun `every rung is present from the first second`() {
        val steps = EpisodePipeline.steps(seconds = 1, fired = emptyMap())
        assertEquals(EpisodePipeline.Stage.entries.size, steps.size)
    }

    @Test
    fun `rungs stay in ladder order`() {
        val steps = EpisodePipeline.steps(seconds = 95, fired = emptyMap())
        assertEquals(EpisodePipeline.Stage.entries.toList(), steps.map { it.stage })
    }

    @Test
    fun `a rung that has not fired and is not due yet is pending`() {
        val steps = EpisodePipeline.steps(seconds = 5, fired = emptyMap())
        val verdict = steps.first { it.stage == EpisodePipeline.Stage.VERDICT }
        assertEquals(EpisodePipeline.Status.PENDING, verdict.status)
        // And it says what it *will* do, so the row is not blank.
        assertTrue(verdict.body.isNotBlank())
        assertNull(verdict.detail)
    }

    @Test
    fun `the newest fired rung is the active one`() {
        val steps = EpisodePipeline.steps(
            seconds = 22,
            fired = fired(
                EpisodePipeline.Stage.HEARD to 0,
                EpisodePipeline.Stage.LOGGED to 12,
                EpisodePipeline.Stage.CLASSIFIED to 20,
            ),
        )
        assertEquals(
            EpisodePipeline.Status.ACTIVE,
            steps.first { it.stage == EpisodePipeline.Stage.CLASSIFIED }.status,
        )
        assertEquals(
            EpisodePipeline.Status.DONE,
            steps.first { it.stage == EpisodePipeline.Stage.LOGGED }.status,
        )
    }

    /**
     * A cry that settles after one sound never reaches the verdict rung. That
     * rung has to stop looking imminent, or the summary of a cry that resolved
     * on its own shows a pending alert.
     */
    @Test
    fun `a rung overtaken by the episode is skipped, not left pending`() {
        val steps = EpisodePipeline.steps(
            seconds = 120,
            fired = fired(EpisodePipeline.Stage.HEARD to 0),
        )
        val soothed = steps.first { it.stage == EpisodePipeline.Stage.SOOTHED }
        assertEquals(EpisodePipeline.Status.SKIPPED, soothed.status)
    }

    @Test
    fun `closing marks nothing as still active`() {
        val steps = EpisodePipeline.steps(
            seconds = 180,
            fired = fired(
                EpisodePipeline.Stage.HEARD to 0,
                EpisodePipeline.Stage.CLOSED to 180,
            ),
            closed = true,
        )
        assertTrue(
            "a closed episode has no running rung",
            steps.none { it.status == EpisodePipeline.Status.ACTIVE },
        )
    }

    @Test
    fun `a fired rung reports the second it actually fired`() {
        val steps = EpisodePipeline.steps(
            seconds = 30,
            fired = mapOf(
                EpisodePipeline.Stage.SOOTHED to
                    EpisodePipeline.Fired("Attempt 1: White noise", 21, Severity.NOTE)
            ),
        )
        val soothed = steps.first { it.stage == EpisodePipeline.Stage.SOOTHED }
        assertEquals(21, soothed.firedAtSeconds)
        assertNotNull(soothed.detail)
        assertEquals("Attempt 1: White noise", soothed.body)
    }

    @Test
    fun `severity rides along so the chart can colour a rung`() {
        val steps = EpisodePipeline.steps(
            seconds = 95,
            fired = mapOf(
                EpisodePipeline.Stage.VERDICT to
                    EpisodePipeline.Fired("woke you", 90, Severity.URGENT)
            ),
        )
        assertEquals(
            Severity.URGENT,
            steps.first { it.stage == EpisodePipeline.Stage.VERDICT }.severity,
        )
    }

    /**
     * The rung times are read off the escalation config rather than repeated,
     * so retuning the ladder cannot leave the chart describing the old one.
     */
    @Test
    fun `rung times match the escalation ladder`() {
        val config = Escalation.Config()
        assertEquals(config.noteSeconds, EpisodePipeline.Stage.LOGGED.atSeconds)
        assertEquals(config.reasonSeconds, EpisodePipeline.Stage.CLASSIFIED.atSeconds)
        assertEquals(config.sootheAfterSeconds, EpisodePipeline.Stage.SOOTHED.atSeconds)
        assertEquals(config.verdictSeconds, EpisodePipeline.Stage.VERDICT.atSeconds)
        assertEquals(config.closeSeconds, EpisodePipeline.Stage.CLOSED.atSeconds)
    }

    @Test
    fun `progress runs zero to one across the episode`() {
        assertEquals(0f, EpisodePipeline.progress(0), 1e-4f)
        assertEquals(0.5f, EpisodePipeline.progress(90), 1e-4f)
        assertEquals(1f, EpisodePipeline.progress(180), 1e-4f)
        assertEquals("clamped past the close", 1f, EpisodePipeline.progress(400), 1e-4f)
    }
}

/**
 * The episode as a story: which sounds were tried, what each one did, and what
 * the ladder will do next.
 */
class EpisodeHistoryTest {

    private fun mark(stage: EpisodePipeline.Stage, at: Int, detail: String = "x",
                     severity: Severity = Severity.NOTE) =
        EpisodePipeline.Mark(stage, detail, at, severity)

    @Test
    fun `a second sound does not erase the first`() {
        val history = listOf(
            mark(EpisodePipeline.Stage.SOOTHED, 20, "Attempt 1: White noise"),
            mark(EpisodePipeline.Stage.VERIFIED, 55, "White noise did not help",
                 Severity.ATTENTION),
            mark(EpisodePipeline.Stage.SOOTHED, 55, "Attempt 2: Amma humming"),
        )
        val tried = EpisodePipeline.attempts(history)
        assertEquals(listOf("White noise", "Amma humming"), tried.map { it.name })
        assertEquals(false, tried[0].helped)
        assertEquals(55, tried[0].checkedAtSeconds)
        assertNull(tried[1].helped)
    }

    @Test
    fun `a calm check marks the sound as having helped`() {
        val tried = EpisodePipeline.attempts(listOf(
            mark(EpisodePipeline.Stage.SOOTHED, 20, "Attempt 1: Heartbeat"),
            mark(EpisodePipeline.Stage.VERIFIED, 55, "Heartbeat is working"),
        ))
        assertEquals(true, tried.single().helped)
    }

    @Test
    fun `before anything is tried the next rung is the first sound`() {
        val next = EpisodePipeline.nextUp(5, emptyList())
        assertEquals(EpisodePipeline.Upcoming.SOOTHE, next?.what)
        assertEquals(15, next?.inSeconds(5))
    }

    @Test
    fun `while a sound plays the next rung is its check`() {
        val next = EpisodePipeline.nextUp(
            30, listOf(mark(EpisodePipeline.Stage.SOOTHED, 20, "Attempt 1: White noise")),
        )
        assertEquals(EpisodePipeline.Upcoming.CHECK, next?.what)
        assertEquals(55, next?.atSeconds)
    }

    @Test
    fun `a check that would land after the alert is not next - the alert is`() {
        val next = EpisodePipeline.nextUp(
            70, listOf(
                mark(EpisodePipeline.Stage.SOOTHED, 20, "Attempt 1: White noise"),
                mark(EpisodePipeline.Stage.VERIFIED, 55, "no", Severity.ATTENTION),
                mark(EpisodePipeline.Stage.SOOTHED, 60, "Attempt 2: Your voice"),
            ),
        )
        assertEquals(EpisodePipeline.Upcoming.WAKE, next?.what)
        assertEquals(90, next?.atSeconds)
    }

    @Test
    fun `after the alert the only thing left is closing`() {
        val next = EpisodePipeline.nextUp(
            95, listOf(mark(EpisodePipeline.Stage.VERDICT, 90, "woke you", Severity.URGENT)),
        )
        assertEquals(EpisodePipeline.Upcoming.CLOSE, next?.what)
        assertNull(EpisodePipeline.nextUp(
            181, listOf(mark(EpisodePipeline.Stage.CLOSED, 180)),
        ))
    }
}
