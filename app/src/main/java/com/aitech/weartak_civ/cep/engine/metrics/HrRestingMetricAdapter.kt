package com.aitech.weartak_civ.cep.engine.metrics

import com.aitech.weartak_civ.cep.engine.BaselineMetricAdapter
import com.aitech.weartak_civ.cep.engine.BaselineEstimator
import com.aitech.weartak_civ.cep.engine.ComputedBaseline
import com.aitech.weartak_civ.cep.engine.MetricBaselinePolicy
import com.aitech.weartak_civ.cep.engine.MetricRuntimeContext
import com.aitech.weartak_civ.cep.engine.MetricTrendResult
import com.aitech.weartak_civ.cep.engine.NormalizedMetricSample
import com.aitech.weartak_civ.cep.engine.SampleQuality

class HrRestingMetricAdapter : BaselineMetricAdapter {
    override val metricId: String = "hr.resting_bpm"
    override val sourceKey: String = "heart_rate"
    override val unit: String = "bpm"
    override val shortWindowSec: Long = 10 * 60
    override val longWindowSec: Long = 24 * 60 * 60
    override val minBaselineSamples: Int = 20
    override val sampleCadenceMs: Long = 30_000
    override val qualifiedWindowMs: Long = 60_000
    override val baselinePolicy: MetricBaselinePolicy = MetricBaselinePolicy(
        estimator = BaselineEstimator.Median,
        warmingMinSamples = 5,
        readyLowConfidenceMinSamples = 10,
        mediumConfidenceMinSamples = 20,
        highConfidenceMinSamples = 60,
        baselineMaxStalenessMs = 48L * 60L * 60L * 1000L
    )

    override fun normalize(sample: Double, nowMs: Long, context: MetricRuntimeContext): NormalizedMetricSample? {
        if (!context.isStill || !context.isAwake || !context.onWrist) return null
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
                "onWrist" to context.onWrist
            )
        )
    }

    override fun selectedOutputs(baseline: ComputedBaseline, trend: MetricTrendResult): Map<String, Any?> = emptyMap()
}
