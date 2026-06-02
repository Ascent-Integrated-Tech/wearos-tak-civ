package com.aitech.weartak_civ.cep.engine

import android.content.Context
import android.content.SharedPreferences

class ProfileStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("cep_profile", Context.MODE_PRIVATE)

    data class NumericBaselineSnapshot(
        val value: Double,
        val sampleCount: Int,
        val lastUpdatedMs: Long
    )

    data class MetricBaselineSnapshot(
        val metricId: String,
        val value: Double?,
        val sampleCount: Int,
        val confidence: BaselineConfidence,
        val status: BaselineStatus,
        val windowStartTs: Long?,
        val windowEndTs: Long?,
        val lastUpdatedMs: Long
    )

    fun hrRestingBaseline(): NumericBaselineSnapshot? {
        val metricSnapshot = metricBaseline("hr.resting_bpm") ?: return null
        val value = metricSnapshot.value ?: return null
        return NumericBaselineSnapshot(value, metricSnapshot.sampleCount, metricSnapshot.lastUpdatedMs)
    }

    fun injectHrRestingBaselineForTest(value: Double, sampleCount: Int = 1, nowMs: Long = System.currentTimeMillis()) {
        putMetricBaseline(
            MetricBaselineSnapshot(
                metricId = "hr.resting_bpm",
                value = value,
                sampleCount = sampleCount.coerceAtLeast(0),
                confidence = BaselineConfidence.HIGH,
                status = BaselineStatus.READY_HIGH_CONFIDENCE,
                windowStartTs = null,
                windowEndTs = nowMs,
                lastUpdatedMs = nowMs
            )
        )
    }

    fun clearHrRestingBaseline() {
        clearMetricBaseline("hr.resting_bpm")
    }

    fun metricBaseline(metricId: String): MetricBaselineSnapshot? {
        val key = metricKey(metricId)
        if (!prefs.contains("${key}.value")) return null
        val hasValue = prefs.getBoolean("${key}.has_value", false)
        return MetricBaselineSnapshot(
            metricId = metricId,
            value = if (hasValue) prefs.getFloat("${key}.value", 0f).toDouble() else null,
            sampleCount = prefs.getInt("${key}.samples", 0),
            confidence = BaselineConfidence.valueOf(
                prefs.getString("${key}.confidence", BaselineConfidence.LOW.name) ?: BaselineConfidence.LOW.name
            ),
            status = BaselineStatus.valueOf(
                prefs.getString("${key}.status", BaselineStatus.UNAVAILABLE.name) ?: BaselineStatus.UNAVAILABLE.name
            ),
            windowStartTs = if (prefs.contains("${key}.window_start")) prefs.getLong("${key}.window_start", 0L) else null,
            windowEndTs = if (prefs.contains("${key}.window_end")) prefs.getLong("${key}.window_end", 0L) else null,
            lastUpdatedMs = prefs.getLong("${key}.updated_ms", 0L)
        )
    }

    fun putMetricBaseline(snapshot: MetricBaselineSnapshot) {
        val key = metricKey(snapshot.metricId)
        val edit = prefs.edit()
            .putBoolean("${key}.has_value", snapshot.value != null)
            .putInt("${key}.samples", snapshot.sampleCount)
            .putString("${key}.confidence", snapshot.confidence.name)
            .putString("${key}.status", snapshot.status.name)
            .putLong("${key}.updated_ms", snapshot.lastUpdatedMs)
        if (snapshot.value != null) edit.putFloat("${key}.value", snapshot.value.toFloat())
        if (snapshot.windowStartTs != null) edit.putLong("${key}.window_start", snapshot.windowStartTs) else edit.remove("${key}.window_start")
        if (snapshot.windowEndTs != null) edit.putLong("${key}.window_end", snapshot.windowEndTs) else edit.remove("${key}.window_end")
        if (snapshot.metricId == HR_RESTING_METRIC_ID) {
            if (snapshot.value != null) {
                edit.putFloat(KEY_HR_RESTING_BASELINE_VALUE, snapshot.value.toFloat())
            } else {
                edit.remove(KEY_HR_RESTING_BASELINE_VALUE)
            }
            edit.putInt(KEY_HR_RESTING_BASELINE_SAMPLES, snapshot.sampleCount)
            edit.putLong(KEY_HR_RESTING_BASELINE_UPDATED_MS, snapshot.lastUpdatedMs)
        }
        edit.apply()
    }

    fun clearMetricBaseline(metricId: String) {
        val key = metricKey(metricId)
        val edit = prefs.edit()
            .remove("${key}.has_value")
            .remove("${key}.value")
            .remove("${key}.samples")
            .remove("${key}.confidence")
            .remove("${key}.status")
            .remove("${key}.window_start")
            .remove("${key}.window_end")
            .remove("${key}.updated_ms")
        if (metricId == HR_RESTING_METRIC_ID) {
            edit.remove(KEY_HR_RESTING_BASELINE_VALUE)
                .remove(KEY_HR_RESTING_BASELINE_SAMPLES)
                .remove(KEY_HR_RESTING_BASELINE_UPDATED_MS)
        }
        edit.apply()
    }

    private fun metricKey(metricId: String): String = "baseline.metric.${metricId.replace('.', '_')}"

    /**
     * Mirrors the generic `hr.resting_bpm` metric snapshot into legacy SharedPreferences keys
     * (`baseline.hr.resting_bpm.*`) that older builds or tooling may still read directly from
     * `cep_profile`. Safe to keep: it is write-only glue and does not affect the generic adapter path.
     */
    fun upsertLegacyHrBaselineKeysForCompatibility() {
        val hr = metricBaseline(HR_RESTING_METRIC_ID) ?: return
        val value = hr.value ?: return
        prefs.edit()
            .putFloat(KEY_HR_RESTING_BASELINE_VALUE, value.toFloat())
            .putInt(KEY_HR_RESTING_BASELINE_SAMPLES, hr.sampleCount)
            .putLong(KEY_HR_RESTING_BASELINE_UPDATED_MS, hr.lastUpdatedMs)
            .apply()
    }

    companion object {
        private const val HR_RESTING_METRIC_ID = "hr.resting_bpm"
        private const val KEY_HR_RESTING_BASELINE_VALUE = "baseline.hr.resting_bpm.value"
        private const val KEY_HR_RESTING_BASELINE_SAMPLES = "baseline.hr.resting_bpm.samples"
        private const val KEY_HR_RESTING_BASELINE_UPDATED_MS = "baseline.hr.resting_bpm.updated_ms"
    }
}
