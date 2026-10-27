package com.aitech.weartak_civ.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class PeriodicReportingGateTest {
    @Test fun motionCanAllowAnImmediatePliInsteadOfWaitingForTheStationaryDeadline() {
        val gate = PeriodicReportingGate()
        gate.reported(1_000_000_000)
        assertFalse(gate.isDue(2_000_000_000, 60))
        gate.reset()
        assertTrue(gate.isDue(2_000_000_000, 5))
    }

    @Test fun observationsDoNotResetDeadlineOrEmitPerFix() {
        val gate = PeriodicReportingGate()
        assertTrue(gate.isDue(1_000_000_000, 60))
        gate.reported(1_000_000_000)
        (2L..60L).forEach { assertFalse(gate.isDue(it * 1_000_000_000, 60)) }
        assertTrue(gate.isDue(61_000_000_000, 60))
    }

    @Test fun changedSpeedReevaluatesAgainstLastSendWithoutNewAsapTrigger() {
        val gate = PeriodicReportingGate()
        gate.reported(1_000_000_000)
        assertFalse(gate.isDue(9_000_000_000, 10))
        assertTrue(gate.isDue(11_000_000_000, 10))
        assertFalse(gate.isDue(11_000_000_000, 3600))
        assertFalse(gate.isDue(0, 1))
    }

    @Test fun schedulerSleepsToTheDeadlineInsteadOfPollingEverySecond() {
        val gate = PeriodicReportingGate()
        gate.reported(1_000_000_000)
        assertEquals(300_000L, gate.delayMillis(1_000_000_000, 300))
        assertEquals(299_000L, gate.delayMillis(2_000_000_000, 300))
        assertEquals(4_000L, gate.delayMillis(2_000_000_000, 5))
        assertEquals(1_000L, gate.delayMillis(301_000_000_000, 300))
        gate.reset()
        assertEquals(1_000L, gate.delayMillis(2_000_000_000, 300))
    }
}
