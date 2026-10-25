package com.aitech.weartak_civ.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodicReportingGateTest {
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
}
