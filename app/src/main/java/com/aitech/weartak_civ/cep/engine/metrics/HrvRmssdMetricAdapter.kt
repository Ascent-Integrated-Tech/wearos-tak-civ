package com.aitech.weartak_civ.cep.engine.metrics

import com.aitech.weartak_civ.data.HrvCepConfig
import com.aitech.weartak_civ.cep.engine.BaselineEstimator
import com.aitech.weartak_civ.cep.engine.BaselineMetricAdapter
import com.aitech.weartak_civ.cep.engine.ComputedBaseline
import com.aitech.weartak_civ.cep.engine.MetricBaselinePolicy
import com.aitech.weartak_civ.cep.engine.MetricRuntimeContext
import com.aitech.weartak_civ.cep.engine.MetricTrendResult
import com.aitech.weartak_civ.cep.engine.NormalizedMetricSample
import com.aitech.weartak_civ.cep.engine.SampleQuality
import com.aitech.weartak_civ.cep.engine.TrendDirection
import kotlin.math.max

class HrvRmssdMetricAdapter(
    private val config: HrvCepConfig = HrvCepConfig.Defaults.DEFAULT,
) : BaselineMetricAdapter {

    private val baselineMinSamplesEff: Int = config.baselineMinSamples.coerceAtLeast(4)

    override val metricId: String = "hrv.rmssd_ms"
    override val sourceKey: String = "hrv.rmssd_ms"
    override val unit: String = "ms"
    override val shortWindowSec: Long = config.trendShortWindowMinutes * 60L
    override val longWindowSec: Long = config.baselineWindowDays.toLong() * 24L * 60L * 60L
    override val minBaselineSamples: Int = baselineMinSamplesEff
    override val sampleCadenceMs: Long = 30_000
    override val qualifiedWindowMs: Long = 60_000
    override val baselinePolicy: MetricBaselinePolicy = run {
        val m = baselineMinSamplesEff
        val warming = max(1, m / 4)
        val readyLow = max(warming + 1, (m + 1) / 2).coerceAtMost(m - 1)
        MetricBaselinePolicy(
            estimator = BaselineEstimator.TrimmedMean(0.1),
            warmingMinSamples = warming,
            readyLowConfidenceMinSamples = readyLow,
            mediumConfidenceMinSamples = m,
            highConfidenceMinSamples = m * 2,
            baselineMaxStalenessMs = 12L * 60L * 60L * 1000L,
            trendEnterPercent = 10.0,
            trendReturnToStableInnerPercent = 7.0,
        )
    }

    override fun normalize(sample: Double, nowMs: Long, context: MetricRuntimeContext): NormalizedMetricSample? {
        if (!context.onWrist || (!context.isStill && !context.isSleeping) || !sample.isFinite() || sample <= 0.0) return null
        return NormalizedMetricSample(
            metricId = metricId,
            ts = nowMs,
            value = sample,
            unit = unit,
            quality = SampleQuality.GOOD,
            baselineEligible = true,
            context = mapOf(
                "isStill" to context.isStill,
                "isSleeping" to context.isSleeping,
                "isAwake" to context.isAwake,
                "onWrist" to context.onWrist,
                "qualified_window" to true
            )
        )
    }

    override fun selectedOutputs(baseline: ComputedBaseline, trend: MetricTrendResult): Map<String, Any?> {
        val outputs = mutableMapOf<String, Any?>(
            "selected.trends.hrv.direction" to trend.direction.name
        )
        trend.shortTermAverage?.let { outputs["selected.trends.hrv.short_term"] = it }
        trend.longTermAverage?.let { outputs["selected.trends.hrv.long_term"] = it }
        baseline.value?.let { b ->
            val alertFrac = config.lowDeviationAlertPercent / 100.0
            val warnFrac = config.lowDeviationWarnPercent / 100.0
            outputs["selected.thresholds.hrv.low_rmssd_ms"] = (b * alertFrac).coerceAtLeast(1.0)
            outputs["selected.thresholds.hrv.low_rmssd_ms_warn"] = (b * warnFrac).coerceAtLeast(1.0)
        }
        return outputs
    }

    override fun selectedOutputsForUnknownTrend(): Map<String, Any?> = mapOf(
        "selected.trends.hrv.direction" to TrendDirection.UNKNOWN.name
    )

    override fun transientSelectedOutputKeysForUnknownTrend(): List<String> = listOf(
        "selected.trends.hrv.short_term",
        "selected.trends.hrv.long_term",
        "selected.thresholds.hrv.low_rmssd_ms_warn",
    )
}
