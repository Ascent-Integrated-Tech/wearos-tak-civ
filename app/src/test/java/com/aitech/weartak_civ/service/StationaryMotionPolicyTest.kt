package com.aitech.weartak_civ.service

import com.aitech.weartak_civ.service.ActivityRecognitionService.Companion.ActivityRecognized
import org.junit.Assert.*
import org.junit.Test

class StationaryMotionPolicyTest {
    @Test fun speedRequiresNoiseMarginAndReportedUncertainty() {
        assertNull(StationaryMotionPolicy.reliableSpeed(0.6, 0.01))
        assertNull(StationaryMotionPolicy.reliableSpeed(1.2, null))
        assertNull(StationaryMotionPolicy.reliableSpeed(1.2, Double.NaN))
        assertNull(StationaryMotionPolicy.reliableSpeed(1.2, -0.1))
        assertNull(StationaryMotionPolicy.reliableSpeed(Double.POSITIVE_INFINITY, 0.1))
        assertNull(StationaryMotionPolicy.reliableSpeed(0.999999, 0.1))
        assertEquals(1.0, StationaryMotionPolicy.reliableSpeed(1.0, 0.1)!!, 0.0)
        val boundary = SpeedReportingPolicy.STATIONARY_SPEED_MPS + 2 * 0.5
        assertEquals(boundary, StationaryMotionPolicy.reliableSpeed(boundary, 0.5)!!, 0.0)
        assertNull(StationaryMotionPolicy.reliableSpeed(boundary - 0.000001, 0.5))
    }

    @Test fun displacementMustExceedFloorAndBothAccuracyRadii() {
        fun moved(distance: Double, accuracy: Double = 5.0, anchor: Double = 5.0) =
            StationaryMotionPolicy.moved(accuracy, null, null, distance, anchor)
        assertFalse(moved(2.0))
        assertFalse(moved(20.0))
        assertTrue(moved(20.00001))
        assertFalse(moved(15.0, 1.0, 1.0))
        assertTrue(moved(15.00001, 1.0, 1.0))
        assertFalse(moved(40.0, 10.0, 10.0))
        assertTrue(moved(40.00001, 10.0, 10.0))
        assertFalse(moved(Double.NaN))
        assertFalse(moved(Double.POSITIVE_INFINITY))
    }

    @Test fun poorOrMissingAccuracyCannotConfirmEvenHighSpeed() {
        listOf(null, -1.0, 25.00001, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertFalse(StationaryMotionPolicy.moved(it, 10.0, 0.01, 1000.0, 5.0))
        }
        assertTrue(StationaryMotionPolicy.moved(25.0, 1.2, 0.2, null, null))
        assertTrue(StationaryMotionPolicy.moved(5.0, null, null, 100.0, 5.0))
        assertFalse(StationaryMotionPolicy.moved(5.0, null, null, 100.0, null))
        assertFalse(StationaryMotionPolicy.moved(5.0, null, null, 100.0, 26.0))
    }

    @Test fun stationaryGateSurvivesSpeedExpiryAndActivityNoise() {
        val policy = StationaryMotionPolicy()
        policy.hold()
        assertNull(policy.speed(20.0, 10))
        assertEquals(ActivityRecognized.STILL, policy.activity(ActivityRecognized.WALKING, 10))
        assertEquals(ActivityRecognized.STILL, policy.activity(ActivityRecognized.UNKNOWN, 10))
        policy.expire(policy.state.value, Long.MAX_VALUE)
        assertTrue(policy.state.value.stationary)
        policy.clear()
        assertEquals(20.0, policy.speed(20.0, 10)!!, 0.0)
        assertEquals(ActivityRecognized.WALKING, policy.activity(ActivityRecognized.WALKING, 10))
    }

    @Test fun displacementEvidenceDoesNotInventSpeedAndExpiresWithoutClearingNewState() {
        val policy = StationaryMotionPolicy()
        policy.confirm(100, null)
        val previous = policy.state.value
        assertNull(policy.speed(0.0, 99))
        assertEquals(ActivityRecognized.UNKNOWN, policy.activity(ActivityRecognized.STILL, 99))
        assertEquals(ActivityRecognized.WALKING, policy.activity(ActivityRecognized.WALKING, 99))
        policy.expire(previous, 99)
        assertEquals(previous, policy.state.value)
        policy.hold()
        policy.expire(previous, 100)
        assertTrue(policy.state.value.stationary)
        policy.confirm(100, 1.2)
        assertEquals(1.2, policy.speed(0.0, 99)!!, 0.0)
        policy.expire(policy.state.value, 100)
        assertEquals(StationaryMotionPolicy.State(), policy.state.value)
    }
}
