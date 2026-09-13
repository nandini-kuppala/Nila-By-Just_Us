package com.nila.phonelink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How often one situation may wake the phone in the parent's room.
 *
 * The judgement here is the same one [LinkProtocolTest] protects for severity:
 * it fails silently. A budget that never runs out trains somebody to ignore the
 * alarm; a budget that runs out too early, or never refills, means a phone that
 * has quietly stopped alerting and looks exactly like a quiet night.
 */
class AlertBudgetTest {

    private var now = 1_000L
    private val budget = AlertBudget { now }

    @Test
    fun `an urgent situation sounds four times and then stops`() {
        repeat(4) { assertTrue("sounding ${it + 1}", budget.allow(3)) }
        assertFalse(budget.allow(3))
        assertFalse(budget.allow(3))
    }

    @Test
    fun `a critical one gets more than an urgent one`() {
        assertEquals(5, AlertBudget.repeatsFor(4))
        assertEquals(4, AlertBudget.repeatsFor(3))
        assertEquals(3, AlertBudget.repeatsFor(2))
    }

    @Test
    fun `a note is never sounded for`() {
        assertEquals(0, AlertBudget.repeatsFor(1))
        assertFalse(budget.allow(1))
    }

    @Test
    fun `getting worse is news, and news starts again`() {
        repeat(3) { assertTrue(budget.allow(2)) }
        assertFalse(budget.allow(2))
        // The same episode, now urgent. This is exactly what the second phone
        // is for, and the spent budget of the quieter level must not swallow it.
        assertTrue(budget.allow(3))
    }

    @Test
    fun `calming down does not refill the budget`() {
        repeat(4) { assertTrue(budget.allow(3)) }
        // Level 2 after level 3 is the same episode easing off, not a new one.
        assertFalse(budget.allow(2))
    }

    @Test
    fun `a settled situation is followed by a fresh one`() {
        repeat(4) { budget.allow(3) }
        assertFalse(budget.allow(3))
        budget.settled()
        assertTrue(budget.allow(3))
    }

    @Test
    fun `a long enough quiet counts as settled`() {
        repeat(4) { budget.allow(3) }
        assertFalse(budget.allow(3))
        now += AlertBudget.QUIET_MS + 1
        assertTrue(budget.allow(3))
    }

    @Test
    fun `a short quiet does not`() {
        repeat(4) { budget.allow(3) }
        now += AlertBudget.QUIET_MS / 2
        assertFalse(budget.allow(3))
    }

    @Test
    fun `what is left is reported for the notification`() {
        assertTrue(budget.allow(4))
        assertEquals(4, budget.remaining)
        repeat(4) { budget.allow(4) }
        assertEquals(0, budget.remaining)
    }
}
