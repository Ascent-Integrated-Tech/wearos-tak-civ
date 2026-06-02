package com.aitech.weartak_civ.cep.engine

data class WindowSpec(
    val windowSec: Int? = null,
    val sampleCount: Int? = null
)

sealed interface BaselineEstimator {
    data object Mean : BaselineEstimator
    data object Median : BaselineEstimator
    data class TrimmedMean(val trimFraction: Double = 0.1) : BaselineEstimator
    /** [p] in 0.0..1.0; linear interpolation between closest ranks (PERCENTILE.INC). */
    data class Percentile(val p: Double) : BaselineEstimator
    /**
     * EMA over samples ordered by increasing timestamp: first sample seeds the accumulator;
     * each subsequent value applies [alpha] * x + (1 - alpha) * ema.
     */
    data class Ema(val alpha: Double) : BaselineEstimator
    data object AnchoredSessionMean : BaselineEstimator
}

sealed interface ThresholdMode {
    data object Absolute : ThresholdMode
    data object OffsetFromBaseline : ThresholdMode
    data object PercentFromBaseline : ThresholdMode
    data object StdDevFromBaseline : ThresholdMode
    data object HybridMax : ThresholdMode
    data object HybridMin : ThresholdMode
}

sealed interface TrendOperator {
    data object Delta : TrendOperator
    data object Slope : TrendOperator
    data object DeviationFromBaseline : TrendOperator
    data object PercentDeviationFromBaseline : TrendOperator
    data object Variance : TrendOperator
    data object StdDev : TrendOperator
}

data class EligibilitySpec(
    val requiredKeys: List<String> = emptyList(),
    val description: String = ""
)

data class PersistenceSpec(
    val persistAcrossRestarts: Boolean = true,
    val maxStalenessSec: Long? = null
)

data class BaselineDefinition(
    val id: String,
    val sourceKey: String,
    val estimator: BaselineEstimator,
    val eligibility: EligibilitySpec,
    val window: WindowSpec,
    val minSamples: Int,
    val maxAgeSec: Long?,
    val updateIntervalMs: Long,
    val persistence: PersistenceSpec
)

data class ThresholdPolicyDefinition(
    val id: String,
    val metric: String,
    val baselineId: String?,
    val mode: ThresholdMode,
    val params: Map<String, Double>,
    val overrideSourceKey: String?,
    val outputKey: String
)

data class TrendDefinition(
    val id: String,
    val sourceKey: String,
    val operator: TrendOperator,
    val window: WindowSpec,
    val baselineId: String? = null,
    val minSamples: Int = 1,
    val maxAgeSec: Long? = null,
    val updateIntervalMs: Long = 1000L
)

enum class SampleQuality {
    POOR,
    FAIR,
    GOOD
}

enum class BaselineStatus {
    UNAVAILABLE,
    INSUFFICIENT_DATA,
    WARMING,
    READY_LOW_CONFIDENCE,
    READY_MEDIUM_CONFIDENCE,
    READY_HIGH_CONFIDENCE,
    STALE
}

enum class BaselineConfidence {
    LOW,
    MEDIUM,
    HIGH
}

enum class TrendDirection {
    IMPROVING,
    DECLINING,
    STABLE,
    UNKNOWN
}

data class NormalizedMetricSample(
    val metricId: String,
    val ts: Long,
    val value: Double,
    val unit: String,
    val quality: SampleQuality,
    val baselineEligible: Boolean,
    val context: Map<String, Any?> = emptyMap()
)

data class ComputedBaseline(
    val metricId: String,
    val value: Double?,
    val windowStartTs: Long?,
    val windowEndTs: Long?,
    val sampleCount: Int,
    val confidence: BaselineConfidence,
    val lastUpdatedTs: Long?,
    val status: BaselineStatus
)

data class MetricTrendResult(
    val metricId: String,
    val direction: TrendDirection,
    val deviationFromBaseline: Double?,
    val percentDeviationFromBaseline: Double?,
    val shortTermAverage: Double?,
    val longTermAverage: Double?,
    val ts: Long
)

data class MetricRuntimeContext(
    val isStill: Boolean,
    val isSleeping: Boolean,
    val onWrist: Boolean
) {
    val isAwake: Boolean get() = !isSleeping
}

data class MetricBaselinePolicy(
    val estimator: BaselineEstimator = BaselineEstimator.Median,
    val warmingMinSamples: Int = 5,
    val readyLowConfidenceMinSamples: Int = 10,
    val mediumConfidenceMinSamples: Int = 20,
    val highConfidenceMinSamples: Int = 40,
    val baselineMaxStalenessMs: Long = 24L * 60L * 60L * 1000L,
    val trendEnterPercent: Double = 8.0,
    val trendReturnToStableInnerPercent: Double = 5.0,
) {
    init {
        require(warmingMinSamples < readyLowConfidenceMinSamples) {
            "warmingMinSamples must be < readyLowConfidenceMinSamples"
        }
        require(readyLowConfidenceMinSamples < mediumConfidenceMinSamples) {
            "readyLowConfidenceMinSamples must be < mediumConfidenceMinSamples"
        }
        require(mediumConfidenceMinSamples < highConfidenceMinSamples) {
            "mediumConfidenceMinSamples must be < highConfidenceMinSamples"
        }
        require(trendReturnToStableInnerPercent in 0.0..<trendEnterPercent) {
            "trendReturnToStableInnerPercent must be in [0, trendEnterPercent)"
        }
    }
}

interface BaselineMetricAdapter {
    val metricId: String
    val sourceKey: String
    val unit: String
    val shortWindowSec: Long
    val longWindowSec: Long
    val minBaselineSamples: Int
    val sampleCadenceMs: Long
    val qualifiedWindowMs: Long
    val baselinePolicy: MetricBaselinePolicy

    fun normalize(sample: Double, nowMs: Long, context: MetricRuntimeContext): NormalizedMetricSample?

    fun selectedOutputs(baseline: ComputedBaseline, trend: MetricTrendResult): Map<String, Any?> = emptyMap()
    fun selectedOutputsForUnknownTrend(): Map<String, Any?> = emptyMap()
    fun transientSelectedOutputKeysForUnknownTrend(): List<String> = emptyList()
}
