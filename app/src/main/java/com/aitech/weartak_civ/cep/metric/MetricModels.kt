package com.aitech.weartak_civ.cep.metric

data class MetricSpec(
    val id: String,
    val source: String,
    val processor: MetricProcessorType,
    val sourceHookId: String? = null,
    val gating: MetricGatingPolicy,
    val baseline: MetricBaselinePolicy,
    val trend: MetricTrendPolicy,
    val selectedOutputs: MetricSelectedOutputMapping
)

data class MetricBaselinePolicy(
    val estimator: String = "trimmed_mean",
    val windowSec: Int,
    val minSamples: Int,
    val maxStalenessSec: Int,
    val confidenceThresholds: MetricConfidenceThresholds
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
    val freshWithinSec: Int? = null
)

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
    val rawSourceKey: String
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
    val lastTs: Long
) {
    fun mean(): Double = if (count <= 0L) 0.0 else sum / count.toDouble()
    fun variance(): Double {
        if (count <= 0L) return 0.0
        val mean = mean()
        return (sumSquares / count.toDouble()) - (mean * mean)
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
    var lastDirection: String = "unknown"
)
