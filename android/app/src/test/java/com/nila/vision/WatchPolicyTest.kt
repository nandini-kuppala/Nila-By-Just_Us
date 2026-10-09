package com.nila.vision

import com.nila.data.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the model runs, and -- mostly -- when it does not.
 *
 * The number this protects is the battery over an eight-hour night. A model
 * asked every minute all night is about five hundred inferences; the policy
 * below turns a quiet night into roughly thirty, and the tests are the statement
 * of that. The second thing it protects is the opposite case: a baby who wakes
 * must get a look within seconds, not at the next quarter-hour.
 */
class WatchPolicyTest {

    private val config = WatchPolicy.Config()

    private fun settled(now: Long, mode: LightMode = LightMode.NIGHT) = WatchPolicy.Inputs(
        now = now,
        mode = mode,
        motionRelative = 0.2f,
        crying = false,
        event = ActivityRules.Event.SETTLED,
        severity = Severity.NOTE,
    )

    @Test
    fun `the first frame is always worth a look`() {
        val policy = WatchPolicy()
        assertEquals(WatchPolicy.Reason.FIRST_LOOK, policy.decide(settled(1_000)))
    }

    @Test
    fun `a still baby in the dark costs one look a quarter of an hour`() {
        val policy = WatchPolicy()
        policy.decide(settled(0))
        policy.looked(0)

        // Stillness has to be observed before it is believed: until then the
        // lane is on the night-awake cadence, which is a look a minute.
        var now = 0L
        while (now <= config.asleepAfterMs) {
            policy.decide(settled(now))
            now += 10_000
        }
        assertTrue("two minutes of stillness should read as asleep", policy.asleep)

        // From here the model is left alone until the quarter-hourly confirmation.
        val asleepAt = now
        policy.looked(asleepAt)
        while (now < asleepAt + config.nightIdleMs - 10_000) {
            assertNull("no look expected while asleep at $now", policy.decide(settled(now)))
            now += 30_000
        }
        assertEquals(
            WatchPolicy.Reason.SCHEDULED,
            policy.decide(settled(asleepAt + config.nightIdleMs)),
        )
    }

    @Test
    fun `a baby waking in the night gets a look in seconds`() {
        val policy = WatchPolicy()
        policy.decide(settled(0))
        policy.looked(0)

        // Settle the baby, then take the scheduled look out of the way so what
        // follows can only be the wake path.
        var now = 0L
        while (now <= config.asleepAfterMs) {
            policy.decide(settled(now))
            now += 10_000
        }
        assertTrue(policy.asleep)
        policy.looked(now)

        // Movement well above the scene's own baseline, twice.
        val waking = settled(now).copy(motionRelative = 4f)
        now += config.minGapMs + 1_000
        assertNull(policy.decide(waking.copy(now = now)))
        now += 1_000
        assertEquals(WatchPolicy.Reason.MOTION_WAKE, policy.decide(waking.copy(now = now)))
    }

    @Test
    fun `a cry starting is looked at immediately`() {
        val policy = WatchPolicy()
        policy.decide(settled(0))
        policy.looked(0)
        val now = config.minGapMs + 1_000
        val crying = settled(now).copy(crying = true, motionRelative = 1f)
        assertEquals(WatchPolicy.Reason.CRY, policy.decide(crying))
    }

    @Test
    fun `a posture event worth attention is looked at`() {
        val policy = WatchPolicy()
        policy.decide(settled(0))
        policy.looked(0)
        val now = config.minGapMs + 1_000
        val rolled = settled(now).copy(
            event = ActivityRules.Event.ROLLED_TO_FRONT,
            severity = Severity.URGENT,
            motionRelative = 1f,
        )
        assertEquals(WatchPolicy.Reason.POSTURE, policy.decide(rolled))
    }

    @Test
    fun `calibrating is not an event worth a model`() {
        val policy = WatchPolicy()
        policy.decide(settled(0))
        policy.looked(0)
        val now = config.minGapMs + 1_000
        val calibrating = settled(now).copy(
            event = ActivityRules.Event.CALIBRATING,
            severity = Severity.NOTE,
            motionRelative = 1f,
        )
        assertNull(policy.decide(calibrating))
    }

    @Test
    fun `nothing is looked at twice inside the minimum gap`() {
        val policy = WatchPolicy()
        policy.decide(settled(0))
        policy.looked(0)
        val crying = settled(config.minGapMs - 1).copy(crying = true)
        assertNull(policy.decide(crying))
    }

    @Test
    fun `a knocked phone is not worth a model`() {
        val policy = WatchPolicy()
        val moved = settled(5_000).copy(cameraMoved = true)
        assertNull(policy.decide(moved))
    }

    @Test
    fun `a look already running blocks another`() {
        val policy = WatchPolicy()
        assertNull(policy.decide(settled(5_000).copy(lookInProgress = true)))
    }

    @Test
    fun `an unloaded model is never asked`() {
        val policy = WatchPolicy()
        assertNull(policy.decide(settled(5_000).copy(modelReady = false)))
    }

    @Test
    fun `daylight and an awake baby runs at the day cadence`() {
        val policy = WatchPolicy()
        val awake = settled(0, LightMode.DAY).copy(motionRelative = 1.2f)
        policy.decide(awake)
        policy.looked(0)

        assertNull(policy.decide(awake.copy(now = config.dayActiveMs - 1_000)))
        assertEquals(
            WatchPolicy.Reason.SCHEDULED,
            policy.decide(awake.copy(now = config.dayActiveMs)),
        )
    }

    @Test
    fun `the hourly budget is a hard stop`() {
        val policy = WatchPolicy()
        var now = 0L
        repeat(config.maxPerHour) {
            policy.looked(now)
            now += config.minGapMs + 1
        }
        val crying = settled(now).copy(crying = true)
        assertNull("the budget should refuse even a cry", policy.decide(crying))

        // An hour later the window has slid and looks are possible again.
        val later = settled(now + 60 * 60_000L).copy(crying = true)
        assertNotNull(policy.decide(later))
    }
}
