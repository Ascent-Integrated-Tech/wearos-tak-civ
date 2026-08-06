package com.aitech.weartak_civ.cep.metric

data class MetricSpec(
    val id: String,
    val source: String,
    val processor: MetricProcessorType,
    val sourceHookId: String? = null,
    val gating: MetricGatingPolicy,
    val validity: MetricValidityPolicy = MetricValidityPolicy(),
    val baseline: MetricBaselinePolicy,
    val trend: MetricTrendPolicy,
    val selectedOutputs: MetricSelectedOutputMapping,
    val topic: String? = null,
    val scalarField: String? = null
)

data class MetricBaselinePolicy(
    val estimator: String = "trimmed_mean",
    val transform: String = "none",
    val windowSec: Int,
    val minSamples: Int,
    val maxStalenessSec: Int,
    val confidenceThresholds: MetricConfidenceThresholds,
    val establishment: MetricEstablishmentPolicy = MetricEstablishmentPolicy(),
    val coverageTargetSec: Long? = null,
    val stabilityWeight: Double = 1.0,
    val thresholdPolicy: MetricThresholdPolicy = MetricThresholdPolicy(),
    val calculation: MetricCalculationPolicy = MetricCalculationPolicy()
)

data class MetricThresholdPolicy(
    val lowAlertPercent: Double = 0.20,
    val lowWarningPercent: Double = 0.10,
    val highWarningPercent: Double = 0.10,
    val highAlertPercent: Double = 0.20
)

data class MetricCalculationPolicy(
    val warmingMaxAgeSec: Long = 900L,
    val warmingSampleTrigger: Long = 10L,
    val establishedMaxAgeSec: Long = 21_600L
)

data class MetricEstablishmentPolicy(
    val minCoverageSec: Long = 0L,
    val minActiveDays: Int = 0,
    val minConfidence: Double = 0.0,
    val maxGapSec: Long = 0L
)

data class MetricValidityPolicy(
    val minValue: Double? = null,
    val maxValue: Double? = null
)

data class MetricConfidenceThresholds(
    val warming: Int,
    val readyLow: Int,
    val medium: Int,
    val high: Int
)

data class MetricTrendPolicy(
    val shortWindowSec: Int,
    val longWindowSec: Int,
    val enterPercent: Double,
    val returnToStableInnerPercent: Double
)

data class MetricGatingPolicy(
    val onWrist: Boolean = false,
    val stillOrSleeping: Boolean = false,
    val freshWithinSec: Int? = null,
    val activityContext: MetricActivityContext = MetricActivityContext.ANY,
    val minContextDwellSec: Long = 0L,
    val restingCooldownSec: Long = 0L
)

enum class MetricActivityContext {
    ANY,
    RESTING,
    ACTIVE,
    UNKNOWN
}

data class MetricSelectedOutputMapping(
    val slotsToSelectedKeys: Map<String, String>
)

enum class MetricProcessorType(val jsonValue: String) {
    BASELINE_TREND("baseline_trend");

    companion object {
        fun fromJson(value: String): MetricProcessorType =
            entries.firstOrNull { it.jsonValue == value }
                ?: throw IllegalArgumentException("Unknown processor type: $value")
    }
}

data class MetricGatingContext(
    val onWrist: Boolean,
    val isStill: Boolean,
    val isSleeping: Boolean,
    val isAwake: Boolean,
    val lastSampleAgeMs: Long?,
    val nowMs: Long,
    val isFresh: Boolean,
    val rawSourceKey: String,
    val hrRestBpm: Double? = null,
    val hrMaxBpm: Double? = null,
    val activityContext: MetricActivityContext = MetricActivityContext.ANY,
    val contextStableForSec: Long = 0L,
    val lastActiveAgeSec: Long? = null
)

data class NormalizedMetricSample(
    val value: Double,
    val tsMs: Long
)

data class MetricBucket(
    val bucketStartTs: Long,
    val bucketSizeMs: Long,
    val count: Long,
    val sum: Double,
    val sumSquares: Double,
    val min: Double,
    val max: Double,
    val firstTs: Long,
    val lastTs: Long,
    // Optional transformed aggregates. They preserve exact log-space statistics
    // after raw samples have been compacted into warm/cold history.
    val sumLog: Double? = null,
    val sumLogSquares: Double? = null
) {
    fun mean(): Double = if (count <= 0L) 0.0 else sum / count.toDouble()
    fun meanLog(): Double? = if (count <= 0L || sumLog == null) null else sumLog / count.toDouble()
    fun variance(): Double {
        if (count <= 0L) return 0.0
        val mean = mean()
        return ((sumSquares / count.toDouble()) - (mean * mean)).coerceAtLeast(0.0)
    }
}

data class MetricHistoryView(
    val hotSamples: List<NormalizedMetricSample>,
    val warmBuckets: List<MetricBucket>,
    val coldBuckets: List<MetricBucket>,
    val hotWindowSec: Long,
    val warmWindowSec: Long,
    val coldWindowSec: Long
)

data class MetricComputationResult(
    val valuesBySlot: Map<String, Any?>,
    val confidence: Double = 0.0,
    val clearSelectedKeys: Set<String> = emptySet()
)

/** Read-only, UI-neutral view of a metric's current baseline state. */
data class MetricInspectionSnapshot(
    val metricId: String,
    val displayName: String,
    val unit: String,
    val currentValue: Double?,
    val baseline: Double?,
    val state: MetricEstablishmentState,
    val confidence: Double?,
    val calculatedAtMs: Long?,
    val dataThroughTsMs: Long?,
    val coveredSeconds: Long,
    val activeDays: Int,
    val trendDirection: String?
)

data class MetricBaselineCore(
    val baseline: Double? = null,
    val shortTerm: Double? = null,
    val longTerm: Double? = null,
    val deviation: Double? = null,
    val percentDeviation: Double? = null,
    val lowThreshold: Double? = null,
    val lowThresholdWarn: Double? = null,
    val highThreshold: Double? = null,
    val highThresholdWarn: Double? = null,
    val confidenceBase: Double = 0.0,
    val sampleCount: Long = 0L,
    val coverageSeconds: Long = 0L,
    val coveredSeconds: Long = 0L,
    val activeDays: Int = 0,
    val latestValue: Double? = null,
    val latestTsMs: Long? = null
)

data class MetricOutputContract(
    val processorType: MetricProcessorType,
    val canonicalSlots: Set<String>
)

data class MetricSourceHookBinding(
    val metricSpec: MetricSpec,
    val sourceHook: MetricSourceHook?,
    val processor: MetricProcessor,
    val outputContract: MetricOutputContract
)

data class MetricRuntimeState(
    val samples: MutableList<NormalizedMetricSample> = mutableListOf(),
    var lastDirection: String = "unknown",
    var acceptedSamples: Long = 0L,
    var gatedSamples: Long = 0L,
    var invalidSamples: Long = 0L,
    var unknownContextSamples: Long = 0L,
    var establishedLatch: Boolean = false,
    var latestSample: NormalizedMetricSample? = null,
    var cachedCore: MetricBaselineCore? = null,
    var coreCalculatedAtMs: Long = 0L,
    var samplesAtLastCoreCalculation: Long = 0L
)

enum class MetricEstablishmentState {
    NO_DATA,
    WARMING,
    PROVISIONAL,
    ESTABLISHED,
    STALE
}

enum class MetricSamplingState {
    ELIGIBLE,
    INELIGIBLE_ACTIVE,
    INELIGIBLE_OFF_WRIST
}
