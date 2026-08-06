package com.aitech.weartak_civ.cep.metric

import com.aitech.weartak_civ.cep.engine.FeatureStore
import kotlin.math.sqrt
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.ln
import kotlin.math.exp

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
    fun findById(metricId: String): MetricSourceHookBinding? = byMetricId[metricId]
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
        val samplingState = samplingState(spec, gatingContext)
        val latestAgeMs = gatingContext.lastSampleAgeMs
        if (latestAgeMs != null && latestAgeMs > spec.baseline.maxStalenessSec * 1000L) {
            state.samples.clear()
            state.establishedLatch = false
            state.lastDirection = "unknown"
            return unknownResult(MetricEstablishmentState.STALE, samplingState)
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
        if (tieredSeries.allSamples.isEmpty()) {
            return unknownResult(MetricEstablishmentState.NO_DATA, samplingState)
        }

        val newestTs = tieredSeries.allSamples.maxOf { it.tsMs }
        val windowCutoff = newestTs - (spec.baseline.windowSec * 1000L)
        val boundedSamples = tieredSeries.allSamples.filter { it.tsMs >= windowCutoff }
        if (boundedSamples.isEmpty()) {
            return unknownResult(MetricEstablishmentState.NO_DATA, samplingState)
        }

        val transformedSeries = transformSeries(tieredSeries, spec.baseline.transform)
        val transformedBoundedSamples = transformedSeries.allSamples.filter { it.tsMs >= windowCutoff }
        val baselineEstimate = if (historyView != null) {
            computeTierAwareBaseline(
                estimator = spec.baseline.estimator,
                hotSamples = transformedSeries.hotSamples.filter { it.tsMs >= windowCutoff },
                warmBuckets = transformedSeries.warmBuckets.filter { it.lastTs >= windowCutoff },
                coldBuckets = transformedSeries.coldBuckets.filter { it.lastTs >= windowCutoff },
                maxGapSec = spec.baseline.establishment.maxGapSec,
                transform = spec.baseline.transform
            )
        } else {
            estimateSamples(transformedBoundedSamples, spec.baseline.estimator, spec.baseline.establishment.maxGapSec)
        }
        val baseline = inverseTransform(baselineEstimate, spec.baseline.transform)
        val shortTerm = inverseTransform(
            averageSince(transformedBoundedSamples, newestTs - spec.trend.shortWindowSec * 1000L) ?: baselineEstimate,
            spec.baseline.transform
        )
        val longTerm = inverseTransform(
            averageSince(transformedBoundedSamples, newestTs - spec.trend.longWindowSec * 1000L) ?: baselineEstimate,
            spec.baseline.transform
        )
        val deviation = shortTerm - baseline
        val percentDeviation = if (baseline == 0.0) 0.0 else (deviation / baseline) * 100.0
        val confidence = computeConfidence(
            spec = spec,
            samples = transformedBoundedSamples,
            tieredSeries = tieredSeries,
            windowCutoff = windowCutoff,
            coveredSeconds = coveredSeconds(boundedSamples, spec.baseline.establishment.maxGapSec),
            gatingContext = gatingContext
        )
        val establishmentState = determineEstablishmentState(
            spec = spec,
            samples = boundedSamples,
            hotSampleCount = tieredSeries.hotSamples.count { it.tsMs >= windowCutoff },
            bucketSampleCount = tieredSeries.warmBuckets.sumOf { it.count } +
                tieredSeries.coldBuckets.sumOf { it.count },
            confidence = confidence,
            coveredSeconds = coveredSeconds(boundedSamples, spec.baseline.establishment.maxGapSec),
            state = state
        )

        val nextDirection = classifyDirection(
            percentDeviation = percentDeviation,
            enterPercent = spec.trend.enterPercent,
            returnToStable = spec.trend.returnToStableInnerPercent,
            lastDirection = state.lastDirection
        )
        state.lastDirection = nextDirection

        val thresholds = thresholdValues(baseline, spec.baseline.thresholdPolicy)

        return MetricComputationResult(
            valuesBySlot = mapOf(
                "trend_direction" to nextDirection,
                "current_value" to tieredSeries.allSamples.maxByOrNull { it.tsMs }?.value,
                "trend_short_term" to shortTerm,
                "trend_long_term" to longTerm,
                "trend_deviation" to deviation,
                "trend_percent_deviation" to percentDeviation,
                "low_threshold" to thresholds.lowAlert,
                "low_threshold_warn" to thresholds.lowWarning,
                "high_threshold" to thresholds.highAlert,
                "high_threshold_warn" to thresholds.highWarning,
                // Internal slot retained in computation result for debug/inspection.
                "baseline_confidence" to confidence,
                "baseline_state" to establishmentState.name,
                "baseline_sample_count" to sampleCount(tieredSeries, windowCutoff),
                "baseline_coverage_sec" to coverageSeconds(boundedSamples),
                "baseline_covered_sec" to coveredSeconds(boundedSamples, spec.baseline.establishment.maxGapSec),
                "sampling_state" to samplingState.name
            ),
            confidence = confidence
        )
    }

    fun computeCore(spec: MetricSpec, historyView: MetricHistoryView): MetricBaselineCore {
        val tieredSeries = assembleTieredSeries(historyView)
        if (tieredSeries.allSamples.isEmpty()) return MetricBaselineCore()
        val newestTs = tieredSeries.allSamples.maxOf { it.tsMs }
        val windowCutoff = newestTs - (spec.baseline.windowSec * 1000L)
        val boundedSamples = tieredSeries.allSamples.filter { it.tsMs >= windowCutoff }
        if (boundedSamples.isEmpty()) return MetricBaselineCore()

        val transformedSeries = transformSeries(tieredSeries, spec.baseline.transform)
        val transformedBoundedSamples = transformedSeries.allSamples.filter { it.tsMs >= windowCutoff }
        val baselineEstimate = computeTierAwareBaseline(
            estimator = spec.baseline.estimator,
            hotSamples = transformedSeries.hotSamples.filter { it.tsMs >= windowCutoff },
            warmBuckets = transformedSeries.warmBuckets.filter { it.lastTs >= windowCutoff },
            coldBuckets = transformedSeries.coldBuckets.filter { it.lastTs >= windowCutoff },
            maxGapSec = spec.baseline.establishment.maxGapSec,
            transform = spec.baseline.transform
        )
        val baseline = inverseTransform(baselineEstimate, spec.baseline.transform)
        val shortTerm = inverseTransform(
            averageSince(transformedBoundedSamples, newestTs - spec.trend.shortWindowSec * 1000L) ?: baselineEstimate,
            spec.baseline.transform
        )
        val longTerm = inverseTransform(
            averageSince(transformedBoundedSamples, newestTs - spec.trend.longWindowSec * 1000L) ?: baselineEstimate,
            spec.baseline.transform
        )
        val deviation = shortTerm - baseline
        val percentDeviation = if (baseline == 0.0) 0.0 else (deviation / baseline) * 100.0
        val covered = coveredSeconds(boundedSamples, spec.baseline.establishment.maxGapSec)
        val sampleCount = sampleCount(tieredSeries, windowCutoff)
        val confidenceBase = computeConfidenceBase(tieredSeries, spec, windowCutoff, covered)
        val latest = tieredSeries.allSamples.maxByOrNull { it.tsMs }
        return MetricBaselineCore(
            baseline = baseline,
            shortTerm = shortTerm,
            longTerm = longTerm,
            deviation = deviation,
            percentDeviation = percentDeviation,
            lowThreshold = thresholdValues(baseline, spec.baseline.thresholdPolicy).lowAlert,
            lowThresholdWarn = thresholdValues(baseline, spec.baseline.thresholdPolicy).lowWarning,
            highThreshold = thresholdValues(baseline, spec.baseline.thresholdPolicy).highAlert,
            highThresholdWarn = thresholdValues(baseline, spec.baseline.thresholdPolicy).highWarning,
            confidenceBase = confidenceBase,
            sampleCount = sampleCount,
            coverageSeconds = coverageSeconds(boundedSamples),
            coveredSeconds = covered,
            activeDays = activeDays(boundedSamples),
            latestValue = latest?.value,
            latestTsMs = latest?.tsMs
        )
    }

    fun overlay(
        spec: MetricSpec,
        core: MetricBaselineCore,
        gatingContext: MetricGatingContext,
        state: MetricRuntimeState
    ): MetricComputationResult {
        val samplingState = samplingState(spec, gatingContext)
        val latest = state.latestSample
        val latestTs = latest?.tsMs ?: core.latestTsMs
        val latestValue = latest?.value ?: core.latestValue
        if (core.baseline == null || latestTs == null) {
            return unknownResult(MetricEstablishmentState.NO_DATA, samplingState)
        }
        val latestAgeMs = gatingContext.lastSampleAgeMs ?: max(gatingContext.nowMs - latestTs, 0L)
        if (latestAgeMs > spec.baseline.maxStalenessSec * 1000L) {
            state.establishedLatch = false
            state.lastDirection = "unknown"
            return unknownResult(MetricEstablishmentState.STALE, samplingState)
        }
        val recencyRaw = 1.0 - (latestAgeMs.toDouble() / max(spec.baseline.maxStalenessSec * 1000L, 1))
        val confidence = (core.confidenceBase * recencyRaw.coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
        val establishmentState = determineEstablishmentState(core, spec, confidence, state)
        val direction = classifyDirection(
            percentDeviation = core.percentDeviation ?: 0.0,
            enterPercent = spec.trend.enterPercent,
            returnToStable = spec.trend.returnToStableInnerPercent,
            lastDirection = state.lastDirection
        )
        state.lastDirection = direction
        return MetricComputationResult(
            valuesBySlot = mapOf(
                "trend_direction" to direction,
                "current_value" to latestValue,
                "trend_short_term" to core.shortTerm,
                "trend_long_term" to core.longTerm,
                "trend_deviation" to core.deviation,
                "trend_percent_deviation" to core.percentDeviation,
                "low_threshold" to core.lowThreshold,
                "low_threshold_warn" to core.lowThresholdWarn,
                "high_threshold" to core.highThreshold,
                "high_threshold_warn" to core.highThresholdWarn,
                "baseline_confidence" to confidence,
                "baseline_state" to establishmentState.name,
                "baseline_sample_count" to core.sampleCount,
                "baseline_coverage_sec" to core.coverageSeconds,
                "baseline_covered_sec" to core.coveredSeconds,
                "sampling_state" to samplingState.name
            ),
            confidence = confidence
        )
    }

    private fun determineEstablishmentState(
        core: MetricBaselineCore,
        spec: MetricSpec,
        confidence: Double,
        state: MetricRuntimeState
    ): MetricEstablishmentState {
        if (state.establishedLatch) return MetricEstablishmentState.ESTABLISHED
        val policy = spec.baseline.establishment
        if (core.sampleCount < spec.baseline.minSamples ||
            core.coveredSeconds < policy.minCoverageSec ||
            core.activeDays < policy.minActiveDays
        ) return MetricEstablishmentState.WARMING
        if (confidence < policy.minConfidence) return MetricEstablishmentState.PROVISIONAL
        state.establishedLatch = true
        return MetricEstablishmentState.ESTABLISHED
    }

    private fun computeConfidenceBase(
        tieredSeries: TieredSeries,
        spec: MetricSpec,
        windowCutoff: Long,
        coveredSeconds: Long
    ): Double {
        val sampleCount = sampleCount(tieredSeries, windowCutoff)
        val sampleMaturity = min(
            sampleCount.toDouble() / max(spec.baseline.minSamples, 1).toDouble(),
            1.0
        )
        val coverageTargetSec = spec.baseline.coverageTargetSec
            ?: (max(spec.baseline.windowSec.toLong() * 1000L, 1L) / 1000L)
        val windowCoverage = min(coveredSeconds.toDouble() / max(coverageTargetSec, 1L), 1.0)
        val cv = coefficientOfVariation(tieredSeries, windowCutoff, spec.baseline.transform)
        val stability = (1.0 / (1.0 + (cv * spec.baseline.stabilityWeight))).coerceIn(0.0, 1.0)
        val horizonCeiling = horizonCeilingForWindow(spec.baseline.windowSec)
        return (sampleMaturity * windowCoverage * stability * horizonCeiling).coerceIn(0.0, 1.0)
    }

    private fun unknownResult(
        establishmentState: MetricEstablishmentState,
        samplingState: MetricSamplingState
    ): MetricComputationResult {
        return MetricComputationResult(
            valuesBySlot = mapOf(
                "trend_direction" to "unknown",
                "current_value" to null,
                "trend_short_term" to null,
                "trend_long_term" to null,
                "trend_deviation" to null,
                "trend_percent_deviation" to null,
                "low_threshold" to null,
                "low_threshold_warn" to null,
                "high_threshold" to null,
                "high_threshold_warn" to null,
                "baseline_confidence" to null,
                "baseline_state" to establishmentState.name,
                "baseline_sample_count" to 0L,
                "baseline_coverage_sec" to 0L,
                "baseline_covered_sec" to 0L,
                "sampling_state" to samplingState.name
            ),
            confidence = 0.0
        )
    }

    private data class ThresholdValues(
        val lowAlert: Double,
        val lowWarning: Double,
        val highWarning: Double,
        val highAlert: Double
    )

    private fun thresholdValues(baseline: Double, policy: MetricThresholdPolicy): ThresholdValues {
        return ThresholdValues(
            lowAlert = baseline * (1.0 - policy.lowAlertPercent),
            lowWarning = baseline * (1.0 - policy.lowWarningPercent),
            highWarning = baseline * (1.0 + policy.highWarningPercent),
            highAlert = baseline * (1.0 + policy.highAlertPercent)
        )
    }

    private fun samplingState(
        spec: MetricSpec,
        context: MetricGatingContext
    ): MetricSamplingState {
        if (spec.gating.onWrist && !context.onWrist) {
            return MetricSamplingState.INELIGIBLE_OFF_WRIST
        }
        if (spec.gating.stillOrSleeping && !(context.isStill || context.isSleeping)) {
            return MetricSamplingState.INELIGIBLE_ACTIVE
        }
        return MetricSamplingState.ELIGIBLE
    }

    private fun determineEstablishmentState(
        spec: MetricSpec,
        samples: List<NormalizedMetricSample>,
        hotSampleCount: Int,
        bucketSampleCount: Long,
        confidence: Double,
        coveredSeconds: Long,
        state: MetricRuntimeState
    ): MetricEstablishmentState {
        if (state.establishedLatch) return MetricEstablishmentState.ESTABLISHED
        val sampleCount = hotSampleCount.toLong() + bucketSampleCount
        val policy = spec.baseline.establishment
        if (sampleCount < spec.baseline.minSamples) return MetricEstablishmentState.WARMING
        if (coveredSeconds < policy.minCoverageSec) return MetricEstablishmentState.WARMING
        if (activeDays(samples) < policy.minActiveDays) return MetricEstablishmentState.WARMING
        if (confidence < policy.minConfidence) return MetricEstablishmentState.PROVISIONAL
        state.establishedLatch = true
        return MetricEstablishmentState.ESTABLISHED
    }

    private fun sampleCount(series: TieredSeries, cutoffMs: Long): Long =
        series.hotSamples.count { it.tsMs >= cutoffMs }.toLong() +
            series.warmBuckets.filter { it.lastTs >= cutoffMs }.sumOf { it.count } +
            series.coldBuckets.filter { it.lastTs >= cutoffMs }.sumOf { it.count }

    private fun coverageSeconds(samples: List<NormalizedMetricSample>): Long {
        if (samples.size < 2) return 0L
        return (samples.maxOf { it.tsMs } - samples.minOf { it.tsMs }) / 1000L
    }

    private fun coveredSeconds(samples: List<NormalizedMetricSample>, maxGapSec: Long): Long {
        if (samples.size < 2) return 0L
        val capMs = if (maxGapSec > 0) maxGapSec * 1000L else Long.MAX_VALUE
        return samples.sortedBy { it.tsMs }.zipWithNext().sumOf { (left, right) ->
            min(max(right.tsMs - left.tsMs, 0L), capMs)
        } / 1000L
    }

    private fun activeDays(samples: List<NormalizedMetricSample>): Int =
        samples.map { (it.tsMs / DAY_MS) * DAY_MS }.toSet().size

    private fun computeConfidence(
        spec: MetricSpec,
        samples: List<NormalizedMetricSample>,
        tieredSeries: TieredSeries,
        windowCutoff: Long,
        coveredSeconds: Long,
        gatingContext: MetricGatingContext
    ): Double {
        val sampleCount = sampleCount(tieredSeries, windowCutoff)
        val minSamples = max(spec.baseline.minSamples, 1)
        val sampleMaturity = min(sampleCount.toDouble() / minSamples.toDouble(), 1.0)

        val newestTs = samples.maxOf { it.tsMs }
        val baselineWindowMs = max(spec.baseline.windowSec.toLong() * 1000L, 1L)
        val coverageTargetSec = spec.baseline.coverageTargetSec ?: (baselineWindowMs / 1000L)
        val windowCoverage = min(coveredSeconds.toDouble() / max(coverageTargetSec, 1L), 1.0)

        // fresh_within_sec controls whether a new sample may be ingested. For a
        // periodic tick, confidence must decay against the baseline staleness
        // horizon rather than the sensor sampling cadence.
        val freshnessWindowSec = spec.baseline.maxStalenessSec
        val latestAgeMs = gatingContext.lastSampleAgeMs
            ?: max(gatingContext.nowMs - newestTs, 0L)
        val recencyRaw = 1.0 - (latestAgeMs.toDouble() / max(freshnessWindowSec * 1000, 1))
        val recency = recencyRaw.coerceIn(0.0, 1.0)

        val cv = coefficientOfVariation(tieredSeries, windowCutoff, spec.baseline.transform)
        val stability = (1.0 / (1.0 + (cv * spec.baseline.stabilityWeight))).coerceIn(0.0, 1.0)

        val horizonCeiling = horizonCeilingForWindow(spec.baseline.windowSec)

        return (sampleMaturity * windowCoverage * recency * stability * horizonCeiling).coerceIn(0.0, 1.0)
    }

    private fun coefficientOfVariation(
        series: TieredSeries,
        cutoffMs: Long,
        transform: String
    ): Double {
        var count = 0.0
        var sum = 0.0
        var sumSquares = 0.0

        fun add(value: Double, valueSquares: Double, weight: Long) {
            if (weight <= 0L || !value.isFinite() || !valueSquares.isFinite()) return
            val weightedCount = weight.toDouble()
            count += weightedCount
            sum += value * weightedCount
            sumSquares += valueSquares
        }

        series.hotSamples.filter { it.tsMs >= cutoffMs }.forEach { sample ->
            val value = transformValue(sample.value, transform)
            add(value, value * value, 1L)
        }
        series.warmBuckets.filter { it.lastTs >= cutoffMs }.forEach { bucket ->
            addBucketMoments(bucket, transform, ::add)
        }
        series.coldBuckets.filter { it.lastTs >= cutoffMs }.forEach { bucket ->
            addBucketMoments(bucket, transform, ::add)
        }

        if (count <= 0.0) return 0.0
        val mean = sum / count
        val variance = (sumSquares / count - mean * mean).coerceAtLeast(0.0)
        return sqrt(variance) / max(abs(mean), 1e-6)
    }

    private fun addBucketMoments(
        bucket: MetricBucket,
        transform: String,
        add: (Double, Double, Long) -> Unit
    ) {
        if (transform == "ln" && bucket.meanLog() != null && bucket.sumLogSquares != null) {
            add(bucket.meanLog()!!, bucket.sumLogSquares, bucket.count)
            return
        }
        val mean = transformValue(bucket.mean(), transform)
        val sumSquares = if (transform == "none") {
            bucket.sumSquares
        } else {
            mean * mean * bucket.count
        }
        add(mean, sumSquares, bucket.count)
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
        estimator: String,
        hotSamples: List<NormalizedMetricSample>,
        warmBuckets: List<MetricBucket>,
        coldBuckets: List<MetricBucket>,
        maxGapSec: Long,
        transform: String
    ): Double {
        val hotMean = if (hotSamples.isEmpty()) null else estimateSamples(hotSamples, estimator, maxGapSec)
        val hotWeight = if (estimator == "time_weighted_mean") hotDurationWeight(hotSamples, maxGapSec) else hotSamples.size.toDouble()

        val warmWeighted = weightedMeanFromBuckets(warmBuckets, estimator, transform)
        val coldWeighted = weightedMeanFromBuckets(coldBuckets, estimator, transform)

        var totalWeight = 0.0
        var weightedSum = 0.0

        if (hotMean != null && hotWeight > 0) {
            totalWeight += hotWeight
            weightedSum += hotMean * hotWeight
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

    private fun weightedMeanFromBuckets(
        buckets: List<MetricBucket>,
        estimator: String,
        transform: String
    ): Pair<Double, Long>? {
        if (buckets.isEmpty()) return null
        val weights = buckets.map { bucket ->
            val weight = if (estimator == "time_weighted_mean") {
                max(bucket.lastTs - bucket.firstTs, bucket.bucketSizeMs).toDouble()
            } else {
                bucket.count.toDouble()
            }
            bucket to weight
        }.filter { it.second > 0.0 }
        if (weights.isEmpty()) return null
        val totalWeight = weights.sumOf { it.second }
        val weightedSum = weights.sumOf { (bucket, weight) ->
            bucketValueInTransform(bucket, transform) * weight
        }
        return (weightedSum / totalWeight) to totalWeight.toLong()
    }

    private fun estimateSamples(
        samples: List<NormalizedMetricSample>,
        estimator: String,
        maxGapSec: Long
    ): Double {
        if (samples.isEmpty()) return 0.0
        return when (estimator) {
            "time_weighted_mean" -> {
                val weights = hotWeights(samples, maxGapSec)
                val total = weights.sumOf { it.second }
                if (total <= 0.0) samples.map { it.value }.average()
                else weights.sumOf { (sample, weight) -> sample.value * weight } / total
            }
            else -> trimmedMean(samples.map { it.value })
        }
    }

    private fun hotDurationWeight(samples: List<NormalizedMetricSample>, maxGapSec: Long): Double =
        hotWeights(samples, maxGapSec).sumOf { it.second }

    private fun hotWeights(
        samples: List<NormalizedMetricSample>,
        maxGapSec: Long
    ): List<Pair<NormalizedMetricSample, Double>> {
        val sorted = samples.sortedBy { it.tsMs }
        val capMs = if (maxGapSec > 0) maxGapSec * 1000L else Long.MAX_VALUE
        return sorted.mapIndexed { index, sample ->
            val nextTs = sorted.getOrNull(index + 1)?.tsMs ?: sample.tsMs
            sample to max(min(nextTs - sample.tsMs, capMs), 1L).toDouble()
        }
    }

    private fun bucketValueInTransform(bucket: MetricBucket, transform: String): Double = when (transform) {
        "none" -> bucket.mean()
        "ln" -> bucket.meanLog()?.takeIf { it.isFinite() } ?: transformValue(bucket.mean(), transform)
        else -> error("Unsupported baseline transform=$transform")
    }

    private fun transformSeries(series: TieredSeries, transform: String): TieredSeries {
        fun transformSample(sample: NormalizedMetricSample) =
            sample.copy(value = transformValue(sample.value, transform))
        return TieredSeries(
            allSamples = series.allSamples.map(::transformSample),
            hotSamples = series.hotSamples.map(::transformSample),
            warmBuckets = series.warmBuckets,
            coldBuckets = series.coldBuckets
        )
    }

    private fun transformValue(value: Double, transform: String): Double = when (transform) {
        "none" -> value
        "ln" -> ln(value.coerceAtLeast(Double.MIN_VALUE))
        else -> error("Unsupported baseline transform=$transform")
    }

    private fun inverseTransform(value: Double, transform: String): Double = when (transform) {
        "none" -> value
        "ln" -> exp(value)
        else -> error("Unsupported baseline transform=$transform")
    }

    private fun standardDeviation(values: List<Double>, mean: Double): Double {
        if (values.isEmpty()) return 0.0
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance.coerceAtLeast(0.0))
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

    companion object {
        private const val DAY_MS: Long = 24L * 60L * 60L * 1000L
    }
}

fun computeRmssdFromIbiMs(ibiValuesMs: List<Double>): Double? {
    if (ibiValuesMs.size < 2) return null
    val diffs = ibiValuesMs.zipWithNext { a, b -> b - a }
    val meanSq = diffs.map { it * it }.average()
    return sqrt(meanSq)
}
