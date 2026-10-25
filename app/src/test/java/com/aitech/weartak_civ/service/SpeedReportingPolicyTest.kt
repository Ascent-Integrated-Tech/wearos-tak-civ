package com.aitech.weartak_civ.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedReportingPolicyTest {
    private fun interval(speed: Double?, foot: Int = 60, vehicle: Int = 10, alert: Boolean = false) =
        SpeedReportingPolicy.intervalSeconds(speed, alert, 7, foot, vehicle, 3600)

    @Test fun stationaryAndMovingEndpointsUseSavedRates() {
        assertEquals(3600, interval(0.0))
        assertEquals(3600, interval(SpeedReportingPolicy.STATIONARY_SPEED_MPS - 0.000001))
        assertEquals(60, interval(SpeedReportingPolicy.STATIONARY_SPEED_MPS))
        assertEquals(10, interval(SpeedReportingPolicy.VEHICLE_SPEED_MPS))
        assertEquals(10, interval(100.0))
    }

    @Test fun interpolationRoundsHalfUpWithoutAtakNormalizationOffset() {
        val midpoint = (SpeedReportingPolicy.STATIONARY_SPEED_MPS +
            SpeedReportingPolicy.VEHICLE_SPEED_MPS) / 2
        assertEquals(35, interval(midpoint))
        assertEquals(11, interval(midpoint, foot = 11, vehicle = 10))
        assertEquals(11, interval(midpoint, foot = 10, vehicle = 11))
    }

    @Test fun interpolationIsBoundedMonotonicAndSupportsReversedRates() {
        listOf(60 to 10, 10 to 60, 60 to 60, 1_000_000 to 1).forEach { (foot, vehicle) ->
            val rates = (0..1000).map { step ->
                val speed = SpeedReportingPolicy.STATIONARY_SPEED_MPS +
                    (SpeedReportingPolicy.VEHICLE_SPEED_MPS - SpeedReportingPolicy.STATIONARY_SPEED_MPS) * step / 1000
                interval(speed, foot, vehicle).also {
                    assertTrue(it in minOf(foot, vehicle)..maxOf(foot, vehicle))
                }
            }
            rates.zipWithNext().forEach { (before, after) ->
                assertTrue(if (foot >= vehicle) after <= before else after >= before)
            }
        }
    }

    @Test fun unknownIsNotStationaryAndAlertingOverridesDynamicSpeed() {
        listOf(null, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -1.0).forEach {
            assertEquals(60, interval(it))
            assertEquals(80, interval(it, foot = 10, vehicle = 80))
            assertEquals(7, interval(it, alert = true))
        }
        assertEquals(7, interval(0.0, alert = true))
        assertEquals(7, interval(100.0, alert = true))
    }

    @Test fun validityUsesActualObservationDeadlineAndTimeoutNotInventedStationarySpeed() {
        val observed = 10_000_000_000L
        val nextObservation = observed + 60_000_000_000L
        val expires = nextObservation + LocationService.ACQUISITION_TIMEOUT_MILLIS * 1_000_000L
        assertEquals(0.0, SpeedReportingPolicy.validSpeed(0.0, observed, expires, true, expires))
        assertEquals(null, SpeedReportingPolicy.validSpeed(0.0, observed, expires + 1, true, expires))
        assertEquals(null, SpeedReportingPolicy.validSpeed(5.0, observed, observed - 1, true, expires))
        assertEquals(null, SpeedReportingPolicy.validSpeed(5.0, observed, observed, false, expires))
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0).forEach {
            assertEquals(null, SpeedReportingPolicy.validSpeed(it, observed, observed, true, expires))
        }
    }

    @Test fun observationCapUsesSelectedRateAndIndependentExistingPowerPolicy() {
        assertEquals(30, SpeedReportingPolicy.desiredObservationIntervalSeconds(3600, 30, 60))
        assertEquals(30, SpeedReportingPolicy.desiredObservationIntervalSeconds(3600, 60, 30))
        assertEquals(60, SpeedReportingPolicy.desiredObservationIntervalSeconds(3600, 120, 90))
        assertEquals(10, SpeedReportingPolicy.desiredObservationIntervalSeconds(10, 30, 60))
        fun observation(dynamic: Boolean, desired: Int, actual: Int = desired,
                        power: Boolean = false, dock: Boolean = false) =
            SpeedReportingPolicy.observationIntervalSeconds(dynamic, desired, actual, power, dock)
        assertEquals(60, observation(true, 3600))
        assertEquals(30, observation(true, 30))
        assertEquals(1, observation(true, 1))
        assertEquals(3600, observation(false, 3600))
        assertEquals(260, observation(true, 3600, power = true))
        assertEquals(140, observation(true, 30, power = true))
        assertEquals(900, observation(true, 1, power = true, dock = true))
        assertEquals(17, observation(false, 1, actual = 17, power = true, dock = true))
    }
}
