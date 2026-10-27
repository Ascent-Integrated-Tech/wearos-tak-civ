package com.aitech.weartak_civ.service

import org.junit.Assert.*
import org.junit.Test

class DittoPowerPolicyTest {
    private val policy = DittoPowerPolicy()
    private val healthy = DittoPowerState(worn = true, battery = 80)

    @Test fun unknownWristAndBatteryAreNotEvidenceToSuppressBluetooth() {
        assertFalse(policy.suppressed(DittoPowerState(), 0))
        assertFalse(policy.suppressed(healthy, 0))
    }

    @Test fun eachPowerGatePausesAndItsRecoveryResumes() {
        listOf(healthy.copy(worn = false), healthy.copy(sleeping = true),
            healthy.copy(powerSave = true), healthy.copy(battery = 20)).forEach {
            assertTrue(policy.suppressed(it, 0))
            assertFalse(policy.suppressed(it.copy(charging = true), 0))
            assertFalse(policy.suppressed(it.copy(alerting = true), 0))
            assertFalse(policy.suppressed(healthy, 0))
        }
        assertFalse(policy.suppressed(healthy.copy(battery = 21), 0))
        assertTrue(policy.suppressed(healthy.copy(battery = 35, lowBatteryThreshold = 35), 0))
    }

    @Test fun alertOverrideIsImmediateBoundedAndExtendedByAnotherAlert() {
        val offWrist = healthy.copy(worn = false, battery = 1, sleeping = true, powerSave = true)
        assertTrue(policy.suppressed(offWrist, 1_000))
        policy.resumeForAlert(1_000)
        assertFalse(policy.suppressed(offWrist, 1_000))
        assertFalse(policy.suppressed(offWrist, 60_999))
        policy.resumeForAlert(60_999)
        assertFalse(policy.suppressed(offWrist, 61_000))
        assertTrue(policy.suppressed(offWrist, 120_999))
    }
}
