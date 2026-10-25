package com.aitech.weartak_civ.service

import com.aitech.weartak_civ.service.ActivityRecognitionService.Companion.ActivityRecognized

internal enum class DiagnosticSelectionReason(val code: Long) {
    CONSTANT(0), ALERT(1), SPEED_STATIONARY(2), SPEED_INTERPOLATED(3),
    SPEED_VEHICLE(4), ACTIVITY_PROFILE(5), UNKNOWN_OR_UNSUPPORTED_ACTIVITY(6)
}

internal class ReportingPolicyDiagnostics {
    private data class Selection(
        val dynamic: Boolean,
        val activity: ActivityRecognized,
        val report: DiagnosticSelectionReason,
        val observation: DiagnosticSelectionReason,
        val reportSeconds: Int,
        val observationSeconds: Int,
    )
    private var previous: Selection? = null

    fun update(
        dynamic: Boolean,
        alerting: Boolean,
        speedMps: Double?,
        activity: ActivityRecognized,
        reportSeconds: Int,
        observationSeconds: Int,
        recording: Boolean = true,
    ): Map<DiagnosticMetric, Long>? {
        if (!recording) {
            previous = null
            return null
        }
        val observation = when {
            !dynamic -> DiagnosticSelectionReason.CONSTANT
            alerting -> DiagnosticSelectionReason.ALERT
            else -> when (activity) {
                ActivityRecognized.STILL, ActivityRecognized.ON_FOOT, ActivityRecognized.WALKING,
                ActivityRecognized.RUNNING, ActivityRecognized.IN_VEHICLE ->
                    DiagnosticSelectionReason.ACTIVITY_PROFILE
                else -> DiagnosticSelectionReason.UNKNOWN_OR_UNSUPPORTED_ACTIVITY
            }
        }
        val report = when {
            !dynamic || alerting -> observation
            speedMps == null || !speedMps.isFinite() || speedMps < 0 -> observation
            speedMps < SpeedReportingPolicy.STATIONARY_SPEED_MPS -> DiagnosticSelectionReason.SPEED_STATIONARY
            speedMps >= SpeedReportingPolicy.VEHICLE_SPEED_MPS -> DiagnosticSelectionReason.SPEED_VEHICLE
            else -> DiagnosticSelectionReason.SPEED_INTERPOLATED
        }
        if (previous?.let { it.dynamic == dynamic && it.activity == activity && it.report == report &&
                it.observation == observation && it.reportSeconds == reportSeconds &&
                it.observationSeconds == observationSeconds } == true) return null
        previous = Selection(dynamic, activity, report, observation, reportSeconds, observationSeconds)
        return mapOf(
            DiagnosticMetric.KIND to if (dynamic) 1L else 0L,
            DiagnosticMetric.ACTIVITY to activity.ordinal.toLong(),
            DiagnosticMetric.REPORT_REASON to report.code,
            DiagnosticMetric.OBSERVATION_REASON to observation.code,
            DiagnosticMetric.REPORT_SECONDS to reportSeconds.toLong(),
            DiagnosticMetric.OBSERVATION_SECONDS to observationSeconds.toLong(),
        )
    }
}
