package com.aitech.weartak_civ.service

import com.aitech.weartak_civ.service.ActivityRecognitionService.Companion.ActivityRecognized
import org.junit.Assert.*
import org.junit.Test

class ReportingPolicyDiagnosticsTest {
    private fun selection(
        speed: Double? = null,
        activity: ActivityRecognized = ActivityRecognized.UNKNOWN,
        dynamic: Boolean = true,
        alerting: Boolean = false,
    ) = ReportingPolicyDiagnostics().update(dynamic, alerting, speed, activity, 60, 60)!!

    @Test fun validSpeedReportsIndependentlyOfActivityOwnedAcquisition() {
        val metrics = selection(30.0, ActivityRecognized.STILL)
        assertEquals(DiagnosticSelectionReason.SPEED_VEHICLE.code, metrics[DiagnosticMetric.REPORT_REASON])
        assertEquals(DiagnosticSelectionReason.ACTIVITY_PROFILE.code, metrics[DiagnosticMetric.OBSERVATION_REASON])
        assertEquals(ActivityRecognized.STILL.ordinal.toLong(), metrics[DiagnosticMetric.ACTIVITY])
        assertEquals(DiagnosticSelectionReason.SPEED_STATIONARY.code,
            selection(0.0)[DiagnosticMetric.REPORT_REASON])
        assertEquals(DiagnosticSelectionReason.SPEED_INTERPOLATED.code,
            selection(SpeedReportingPolicy.STATIONARY_SPEED_MPS)[DiagnosticMetric.REPORT_REASON])
    }

    @Test fun absentInvalidOrExpiredSpeedUsesRecognizedActivityOrExplicitUnknownFallback() {
        listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { speed ->
            listOf(ActivityRecognized.STILL, ActivityRecognized.WALKING,
                ActivityRecognized.RUNNING, ActivityRecognized.ON_FOOT,
                ActivityRecognized.IN_VEHICLE).forEach { activity ->
                val metrics = selection(speed, activity)
                assertEquals(DiagnosticSelectionReason.ACTIVITY_PROFILE.code, metrics[DiagnosticMetric.REPORT_REASON])
                assertEquals(metrics[DiagnosticMetric.REPORT_REASON], metrics[DiagnosticMetric.OBSERVATION_REASON])
            }
            listOf(ActivityRecognized.UNKNOWN, ActivityRecognized.TILTING,
                ActivityRecognized.BICYCLING).forEach { activity ->
                assertEquals(DiagnosticSelectionReason.UNKNOWN_OR_UNSUPPORTED_ACTIVITY.code,
                    selection(speed, activity)[DiagnosticMetric.REPORT_REASON])
            }
        }
    }

    @Test fun constantAndAlertPrecedenceMatchActualSelectionAndPowerTransformsRemainIndependent() {
        val constant = selection(30.0, ActivityRecognized.STILL, dynamic = false, alerting = true)
        assertEquals(DiagnosticSelectionReason.CONSTANT.code, constant[DiagnosticMetric.REPORT_REASON])
        assertEquals(DiagnosticSelectionReason.CONSTANT.code, constant[DiagnosticMetric.OBSERVATION_REASON])
        val alert = selection(30.0, ActivityRecognized.STILL, alerting = true)
        assertEquals(DiagnosticSelectionReason.ALERT.code, alert[DiagnosticMetric.REPORT_REASON])
        assertEquals(DiagnosticSelectionReason.ALERT.code, alert[DiagnosticMetric.OBSERVATION_REASON])
        assertEquals(60, SpeedReportingPolicy.observationIntervalSeconds(false, 3600, 60, true, true))
        assertEquals(com.aitech.weartak_civ.DEFAULT_DOCKED_REPORTING_INTERVAL_SECS,
            SpeedReportingPolicy.observationIntervalSeconds(true, 3600, 60, true, true))
    }

    @Test fun unchangedInputsDoNotCreateHotPolicyEventsButEqualRateReasonChangesAreRecorded() {
        val recorder = ReportingPolicyDiagnostics()
        assertNotNull(recorder.update(true, false, 10.0, ActivityRecognized.WALKING, 60, 60))
        repeat(10_000) {
            assertNull(recorder.update(true, false, 10.1, ActivityRecognized.WALKING, 60, 60))
        }
        val fallback = recorder.update(true, false, null, ActivityRecognized.WALKING, 60, 60)!!
        assertEquals(DiagnosticSelectionReason.ACTIVITY_PROFILE.code, fallback[DiagnosticMetric.REPORT_REASON])
        assertNotNull(recorder.update(true, false, null, ActivityRecognized.IN_VEHICLE, 60, 60))
        val unknown = recorder.update(true, false, null, ActivityRecognized.UNKNOWN, 60, 60)!!
        assertEquals(DiagnosticSelectionReason.UNKNOWN_OR_UNSUPPORTED_ACTIVITY.code,
            unknown[DiagnosticMetric.REPORT_REASON])
        assertNotNull(recorder.update(true, false, null, ActivityRecognized.UNKNOWN, 120, 60))
    }

    @Test fun recordingOffSkipsSelectionAndReenableRestoresAnUnchangedSnapshot() {
        val recorder = ReportingPolicyDiagnostics()
        fun update(recording: Boolean) = recorder.update(true, false, 10.0,
            ActivityRecognized.WALKING, 60, 60, recording)
        assertNotNull(update(true))
        assertNull(update(true))
        repeat(10_000) { assertNull(update(false)) }
        assertNotNull(update(true))
        assertNull(update(true))
    }
}
