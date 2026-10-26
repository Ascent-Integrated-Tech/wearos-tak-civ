package com.aitech.weartak_civ.service

import com.aitech.weartak_civ.service.ActivityRecognitionService.Companion.ActivityRecognized
import org.junit.Assert.*
import org.junit.Test

class Spo2StillnessPolicyTest {
    private val now = 1_000L

    @Test fun stationaryHoldAllowsAttemptWithoutActivityRecognition() {
        assertTrue(spo2StillnessAllowed(StationaryMotionPolicy.State(stationary = true),
            ActivityRecognized.UNKNOWN, null, now))
    }

    @Test fun confirmedMotionOverridesStaleHighConfidenceStillEvenWithoutSpeed() {
        assertFalse(spo2StillnessAllowed(StationaryMotionPolicy.State(movingUntilNanos = now + 1),
            ActivityRecognized.STILL, 100, now))
    }

    @Test fun expiredMotionFallsBackToActivityAtExactDeadline() {
        val state = StationaryMotionPolicy.State(movingUntilNanos = now)
        assertTrue(spo2StillnessAllowed(state, ActivityRecognized.STILL, 50, now))
        assertFalse(spo2StillnessAllowed(state, ActivityRecognized.WALKING, 100, now))
    }

    @Test fun fallbackRequiresStillAndConfidenceAtLeastFifty() {
        val state = StationaryMotionPolicy.State()
        ActivityRecognized.entries.filter { it != ActivityRecognized.STILL }.forEach {
            assertFalse(it.name, spo2StillnessAllowed(state, it, 100, now))
        }
        listOf(null, 0, 49).forEach {
            assertFalse(spo2StillnessAllowed(state, ActivityRecognized.STILL, it, now))
        }
        assertTrue(spo2StillnessAllowed(state, ActivityRecognized.STILL, 50, now))
    }

    @Test fun diagnosticIdsAreAppendOnly() {
        assertEquals(41, DiagnosticEvent.MOTION_CONFIRMATION.ordinal)
        assertEquals(42, DiagnosticEvent.POWER_CONTEXT.ordinal)
        assertEquals(43, DiagnosticEvent.DEVICE_SETTING.ordinal)
        assertEquals(44, DiagnosticEvent.RESOURCE_LIFETIME.ordinal)
        assertEquals(45, DiagnosticEvent.MISSION_LIST.ordinal)
        assertEquals(46, DiagnosticEvent.OFF_BODY_TAK.ordinal)
        assertEquals(47, DiagnosticEvent.BLE_DISCOVERY.ordinal)
        assertEquals(48, DiagnosticEvent.SPO2_SKIPPED_MOTION.ordinal)
    }
}
