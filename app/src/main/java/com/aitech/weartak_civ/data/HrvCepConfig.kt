/*
 * Typed HRV baseline/trend policy loaded from DataStore for CEP metric adapters.
 * Preferences keys mirror the design doc (`hrv.baseline.window_days` → `hrv_baseline_window_days`, etc.).
 */
package com.aitech.weartak_civ.data

data class HrvCepConfig(
    val baselineWindowDays: Int = Defaults.BASELINE_WINDOW_DAYS,
    val baselineMinSamples: Int = Defaults.BASELINE_MIN_SAMPLES,
    val trendShortWindowMinutes: Int = Defaults.TREND_SHORT_WINDOW_MINUTES,
    /** Percent of personal baseline RMSSD for warn-tier selected threshold (e.g. 80 → baseline × 0.80). */
    val lowDeviationWarnPercent: Int = Defaults.LOW_DEVIATION_WARN_PERCENT,
    /** Percent of personal baseline RMSSD for alert-tier / primary rule threshold. */
    val lowDeviationAlertPercent: Int = Defaults.LOW_DEVIATION_ALERT_PERCENT,
) {
    init {
        require(baselineWindowDays > 0) { "baselineWindowDays must be positive" }
        require(baselineMinSamples > 0) { "baselineMinSamples must be positive" }
        require(trendShortWindowMinutes > 0) { "trendShortWindowMinutes must be positive" }
        require(lowDeviationWarnPercent in 1..100) { "lowDeviationWarnPercent must be in 1..100" }
        require(lowDeviationAlertPercent in 1..100) { "lowDeviationAlertPercent must be in 1..100" }
    }

    object Defaults {
        const val BASELINE_WINDOW_DAYS: Int = 7
        const val BASELINE_MIN_SAMPLES: Int = 20
        const val TREND_SHORT_WINDOW_MINUTES: Int = 60
        /** Matches prior single-threshold behavior until settings supply distinct warn/alert percents. */
        const val LOW_DEVIATION_WARN_PERCENT: Int = 80
        const val LOW_DEVIATION_ALERT_PERCENT: Int = 80
        val DEFAULT = HrvCepConfig()
    }
}
