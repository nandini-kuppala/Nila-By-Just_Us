package com.nila.vision.guard

import org.junit.Assert.assertEquals
import org.junit.Test

/** The guard rails ported from Baby Guard CCTV, on answers the model gave in its tests on the phone. */
class SafetyGuardTest {

    private fun verdict(answer: String) = SafetyGuard.assess(answer).verdict

    @Test fun `stairs is danger`() {
        val a = SafetyGuard.assess("BABY: Yes\nACTIVITY: Climbing stairs\nHAZARD: Stairs\nSAFETY: DANGER")
        assertEquals(Verdict.DANGER, a.verdict)
        assertEquals("stairs", a.category)
    }

    @Test fun `a cable named under a safe verdict is overruled to danger`() {
        val a = SafetyGuard.assess(
            "BABY: Yes\nACTIVITY: The baby is crawling on the floor.\nHAZARD: The baby is near an electrical cable.\nSAFETY: SAFE"
        )
        assertEquals(Verdict.DANGER, a.verdict)
        assertEquals("electrical", a.category)
    }

    @Test fun `playing with a toy is safe`() {
        assertEquals(Verdict.SAFE, verdict("BABY: Yes\nACTIVITY: The baby is playing with a toy.\nHAZARD: None\nSAFETY: Safe"))
    }

    @Test fun `no baby is never an alarm`() {
        assertEquals(Verdict.NO_BABY, verdict("BABY: No\nACTIVITY: Climbing stairs\nHAZARD: Stairs\nSAFETY: DANGER"))
    }

    @Test fun `danger with no hazard is unsure`() {
        assertEquals(Verdict.UNSURE, verdict("BABY: Yes\nACTIVITY: Sitting\nHAZARD: No immediate danger\nSAFETY: DANGER"))
    }

    @Test fun `bath with an adult holding is safe`() {
        assertEquals(Verdict.SAFE,
            verdict("BABY: yes\nACTIVITY: An adult is holding the baby in the bath tub\nHAZARD: water\nSAFETY: DANGER"))
    }

    @Test fun `a teether near the mouth is unsure, not danger`() {
        assertEquals(Verdict.UNSURE,
            verdict("BABY: yes\nACTIVITY: chewing a teether\nHAZARD: small toy near the mouth\nSAFETY: DANGER"))
    }

    @Test fun `no verdict line never alarms`() {
        assertEquals(Verdict.UNKNOWN, verdict("The baby is engaging with a white charger."))
    }

    @Test fun `markdown bold is tolerated`() {
        assertEquals(Verdict.DANGER, verdict("**BABY:** yes\n**ACTIVITY:** near a socket\n**HAZARD:** socket\n**SAFETY:** DANGER"))
    }
}
