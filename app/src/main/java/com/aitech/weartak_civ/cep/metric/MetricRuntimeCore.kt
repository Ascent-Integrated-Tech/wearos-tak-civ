package com.aitech.weartak_civ.cep.metric

import com.aitech.weartak_civ.cep.engine.FeatureStore
import kotlin.math.sqrt
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

interface MetricSourceHook {
    val hookId: String
    fun normalize(payload: Any, context: MetricGatingContext): NormalizedMetricSample?
}

interface MetricProcessor {
    fun compute(
        spec: MetricSpec,
        sample: NormalizedMetricSample?,
        gatingContext: MetricGatingContext,
        state: MetricRuntimeState,
        historyView: MetricHistoryView? = null
    ): MetricComputationResult
}

class MetricHookRegistry(hooks: List<MetricSourceHook>) {
    private val byId: Map<String, MetricSourceHook> = hooks.associateBy { it.hookId }
    fun resolveOrNull(id: String): MetricSourceHook? = byId[id]
}

class ProcessorRegistry(processors: Map<MetricProcessorType, MetricProcessor>) {
    private val byType = processors
    fun resolveOrThrow(type: MetricProcessorType): MetricProcessor =
        byType[type] ?: throw IllegalArgumentException("No processor bound for type=${type.name}")
}

class MetricRegistry(bindings: List<MetricSourceHookBinding>) {
    private val byMetricId = bindings.associateBy { it.metricSpec.id }
    private val bySource = bindings.groupBy { it.metricSpec.source }
    private val states = bindings.associate { it.metricSpec.id to MetricRuntimeState() }.toMutableMap()

    fun allBindings(): Collection<MetricSourceHookBinding> = byMetricId.values
    fun findBySource(source: String): List<MetricSourceHookBinding> = bySource[source].orEmpty()
    fun stateFor(metricId: String): MetricRuntimeState =
        states[metricId] ?: error("Missing runtime state for metric=$metricId")
}

object MetricOutputMapper {
    fun publish(
        featureStore: FeatureStore,
        mapping: MetricSelectedOutputMapping,
        result: MetricComputationResult,
        nowMs: Long
    ) {
        mapping.slotsToSelectedKeys.forEach { (slot, selectedKey) ->
            val value = result.valuesBySlot[slot]
            if (value == null) {
                featureStore.clear(selectedKey)
            } else {
                featureStore.put(selectedKey, value, nowMs)
            }
        }
        result.clearSelectedKeys.forEach { featureStore.clear(it) }
    }
}

class BaselineTrendProcessor : MetricProcessor {
    private data class TieredSeries(
        val allSamples: List<NormalizedMetricSample>,
        val hotSamples: List<NormalizedMetricSample>,
        val warmBuckets: List<MetricBucket>,
        val coldBuckets: List<MetricBucket>
    )

    override fun compute(
        spec: MetricSpec,
        sample: NormalizedMetricSample?,
        gatingContext: MetricGatingContext,
        state: MetricRuntimeState,
        historyView: MetricHistoryView?
    ): MetricComputationResult {
        if (!gatingContext.onWrist && spec.gating.onWrist) {
            state.samples.clear()
            state.lastDirection = "unknown"
            return unknownResult()
        }
        if (spec.gating.stillOrSleeping && !(gatingContext.isStill || gatingContext.isSleeping)) {
            return unknownResult()
        }
        if (sample == null && spec.gating.freshWithinSec != null && !gatingContext.isFresh) {
            return unknownResult()
        }
        val latestAgeMs = gatingContext.lastSampleAgeMs
        if (latestAgeMs != null && latestAgeMs > spec.baseline.maxStalenessSec * 1000L) {
            state.samples.clear()
            state.lastDirection = "unknown"
            return unknownResult()
        }
        val tieredSeries = when {
            historyView != null -> assembleTieredSeries(historyView)
            else -> {
                if (sample != null) state.samples.add(sample)
                val raw = if (state.samples.isEmpty()) emptyList() else state.samples.toList()
                TieredSeries(
                    allSamples = raw,
                    hotSamples = raw,
                    warmBuckets = emptyList(),
                    coldBuckets = emptyList()
                )
            }
        }
        if (tieredSeries.allSamples.isEmpty()) return unknownResult()

        val newestTs = tieredSeries.allSamples.maxOf { it.tsMs }
        val windowCutoff = newestTs - (spec.baseline.windowSec * 1000L)
        val boundedSamples = tieredSeries.allSamples.filter { it.tsMs >= windowCutoff }
        if (boundedSamples.isEmpty()) return unknownResult()

        val baseline = if (historyView != null) {
            computeTierAwareBaseline(
                hotSamples = tieredSeries.hotSamples.filter { it.tsMs >= windowCutoff },
                warmBuckets = tieredSeries.warmBuckets.filter { it.lastTs >= windowCutoff },
                coldBuckets = tieredSeries.coldBuckets.filter { it.lastTs >= windowCutoff }
            )
        } else {
            trimmedMean(boundedSamples.map { it.value })
        }
        val shortTerm = averageSince(boundedSamples, newestTs - spec.trend.shortWindowSec * 1000L) ?: baseline
        val longTerm = averageSince(boundedSamples, newestTs - spec.trend.longWindowSec * 1000L) ?: baseline
        val deviation = shortTerm - baseline
        val percentDeviation = if (baseline == 0.0) 0.0 else (deviation / baseline) * 100.0
        val confidence = computeConfidence(
            spec = spec,
            samples = boundedSamples,
            hotSampleCount = tieredSeries.hotSamples.filter { it.tsMs >= windowCutoff }.size,
            gatingContext = gatingContext
        )

        val nextDirection = classifyDirection(
            percentDeviation = percentDeviation,
            enterPercent = spec.trend.enterPercent,
            returnToStable = spec.trend.returnToStableInnerPercent,
            lastDirection = state.lastDirection
        )
        state.lastDirection = nextDirection

        val lowThreshold = baseline * 0.8
        val lowThresholdWarn = baseline * 0.9

        return MetricComputationResult(
            valuesBySlot = mapOf(
                "trend_direction" to nextDirection,
                "trend_short_term" to shortTerm,
                "trend_long_term" to longTerm,
                "trend_deviation" to deviation,
                "trend_percent_deviation" to percentDeviation,
                "low_threshold" to lowThreshold,
                "low_threshold_warn" to lowThresholdWarn,
                // Internal slot retained in computation result for debug/inspection.
                "baseline_confidence" to confidence
            ),
            confidence = confidence
        )
    }

    private fun unknownResult(): MetricComputationResult {
        return MetricComputationResult(
            valuesBySlot = mapOf(
                "trend_direction" to "unknown",
                "trend_short_term" to null,
                "trend_long_term" to null,
                "trend_deviation" to null,
                "trend_percent_deviation" to null,
                "low_threshold" to null,
                "low_threshold_warn" to null,
                "baseline_confidence" to null
            ),
            confidence = 0.0
        )
    }

    private fun computeConfidence(
        spec: MetricSpec,
        samples: List<NormalizedMetricSample>,
        hotSampleCount: Int,
        gatingContext: MetricGatingContext
    ): Double {
        val minSamples = max(spec.baseline.minSamples, 1)
        val sampleMaturity = min(hotSampleCount.toDouble() / minSamples.toDouble(), 1.0)

        val oldestTs = samples.minOf { it.tsMs }
        val newestTs = samples.maxOf { it.tsMs }
        val spanMs = max(newestTs - oldestTs, 0L)
        val baselineWindowMs = max(spec.baseline.windowSec.toLong() * 1000L, 1L)
        val windowCoverage = min(spanMs.toDouble() / baselineWindowMs.toDouble(), 1.0)

        val freshnessWindowSec = spec.gating.freshWithinSec ?: spec.baseline.maxStalenessSec
        val latestAgeMs = gatingContext.lastSampleAgeMs
            ?: max(gatingContext.nowMs - newestTs, 0L)
        val recencyRaw = 1.0 - (latestAgeMs.toDouble() / max(freshnessWindowSec * 1000, 1))
        val recency = recencyRaw.coerceIn(0.0, 1.0)

        val values = samples.map { it.value }
        val mean = values.average()
        val stdDev = standardDeviation(values, mean)
        val cv = stdDev / max(abs(mean), 1e-6)
        val stability = (1.0 / (1.0 + cv)).coerceIn(0.0, 1.0)

        val horizonCeiling = horizonCeilingForWindow(spec.baseline.windowSec)

        return (sampleMaturity * windowCoverage * recency * stability * horizonCeiling).coerceIn(0.0, 1.0)
    }

    private fun assembleTieredSeries(historyView: MetricHistoryView): TieredSeries {
        val warm = historyView.warmBuckets.map {
            NormalizedMetricSample(value = it.mean(), tsMs = it.lastTs)
        }
        val cold = historyView.coldBuckets.map {
            NormalizedMetricSample(value = it.mean(), tsMs = it.lastTs)
        }
        return TieredSeries(
            allSamples = (historyView.hotSamples + warm + cold).sortedBy { it.tsMs },
            hotSamples = historyView.hotSamples.sortedBy { it.tsMs },
            warmBuckets = historyView.warmBuckets.sortedBy { it.lastTs },
            coldBuckets = historyView.coldBuckets.sortedBy { it.lastTs }
        )
    }

    private fun computeTierAwareBaseline(
        hotSamples: List<NormalizedMetricSample>,
        warmBuckets: List<MetricBucket>,
        coldBuckets: List<MetricBucket>
    ): Double {
        val hotMean = if (hotSamples.isEmpty()) null else trimmedMean(hotSamples.map { it.value })
        val hotCount = hotSamples.size.toLong()

        val warmWeighted = weightedMeanFromBuckets(warmBuckets)
        val coldWeighted = weightedMeanFromBuckets(coldBuckets)

        var totalWeight = 0.0
        var weightedSum = 0.0

        if (hotMean != null && hotCount > 0) {
            totalWeight += hotCount.toDouble()
            weightedSum += hotMean * hotCount.toDouble()
        }
        if (warmWeighted != null) {
            totalWeight += warmWeighted.second.toDouble()
            weightedSum += warmWeighted.first * warmWeighted.second.toDouble()
        }
        if (coldWeighted != null) {
            totalWeight += coldWeighted.second.toDouble()
            weightedSum += coldWeighted.first * coldWeighted.second.toDouble()
        }

        if (totalWeight <= 0.0) return 0.0
        return weightedSum / totalWeight
    }

    private fun weightedMeanFromBuckets(buckets: List<MetricBucket>): Pair<Double, Long>? {
        if (buckets.isEmpty()) return null
        val totalCount = buckets.sumOf { it.count }
        if (totalCount <= 0L) return null
        val weightedSum = buckets.sumOf { it.mean() * it.count.toDouble() }
        return (weightedSum / totalCount.toDouble()) to totalCount
    }

    private fun standardDeviation(values: List<Double>, mean: Double): Double {
        if (values.isEmpty()) return 0.0
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance)
    }

    private fun horizonCeilingForWindow(windowSec: Int): Double {
        return when {
            windowSec < 3600 -> 0.40
            windowSec < 86_400 -> 0.60
            windowSec < 7 * 86_400 -> 0.80
            windowSec < 30 * 86_400 -> 0.92
            else -> 1.00
        }
    }

    private fun averageSince(samples: List<NormalizedMetricSample>, cutoffMs: Long): Double? {
        val values = samples.filter { it.tsMs >= cutoffMs }.map { it.value }
        return if (values.isEmpty()) null else values.average()
    }

    private fun trimmedMean(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        if (values.size < 5) return values.average()
        val sorted = values.sorted()
        val trim = (sorted.size * 0.1).toInt().coerceAtLeast(1)
        return sorted.subList(trim, sorted.size - trim).average()
    }

    private fun classifyDirection(
        percentDeviation: Double,
        enterPercent: Double,
        returnToStable: Double,
        lastDirection: String
    ): String {
        val absDev = kotlin.math.abs(percentDeviation)
        if (absDev <= returnToStable) return "stable"
        if (lastDirection == "stable" && absDev < enterPercent) return "stable"
        return if (percentDeviation < 0) "declining" else "rising"
    }
}

fun computeRmssdFromIbiMs(ibiValuesMs: List<Double>): Double? {
    if (ibiValuesMs.size < 2) return null
    val diffs = ibiValuesMs.zipWithNext { a, b -> b - a }
    val meanSq = diffs.map { it * it }.average()
    return sqrt(meanSq)
}
