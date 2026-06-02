package com.aitech.weartak_civ.cep.engine

import kotlin.math.abs
import kotlin.math.floor

object MetricBaselineCalculator {
    fun computeBaseline(
        adapter: BaselineMetricAdapter,
        samples: List<Pair<Long, Double>>,
        nowMs: Long
    ): ComputedBaseline {
        if (samples.isEmpty()) {
            return ComputedBaseline(
                metricId = adapter.metricId,
                value = null,
                windowStartTs = null,
                windowEndTs = null,
                sampleCount = 0,
                confidence = BaselineConfidence.LOW,
                lastUpdatedTs = null,
                status = BaselineStatus.UNAVAILABLE
            )
        }

        val policy = adapter.baselinePolicy
        val baselineValue = estimateBaseline(samples, policy.estimator)
        val count = samples.size
        val confidence = when {
            count >= policy.highConfidenceMinSamples -> BaselineConfidence.HIGH
            count >= policy.mediumConfidenceMinSamples -> BaselineConfidence.MEDIUM
            else -> BaselineConfidence.LOW
        }
        val status = when {
            count >= policy.highConfidenceMinSamples -> BaselineStatus.READY_HIGH_CONFIDENCE
            count >= policy.mediumConfidenceMinSamples -> BaselineStatus.READY_MEDIUM_CONFIDENCE
            count >= policy.readyLowConfidenceMinSamples -> BaselineStatus.READY_LOW_CONFIDENCE
            count >= policy.warmingMinSamples -> BaselineStatus.WARMING
            else -> BaselineStatus.INSUFFICIENT_DATA
        }
        return ComputedBaseline(
            metricId = adapter.metricId,
            value = baselineValue,
            windowStartTs = samples.first().first,
            windowEndTs = samples.last().first,
            sampleCount = count,
            confidence = confidence,
            lastUpdatedTs = nowMs,
            status = status
        )
    }

    fun computeTrend(
        adapter: BaselineMetricAdapter,
        baseline: ComputedBaseline,
        windows: List<Pair<Long, Double>>,
        nowMs: Long,
        previousDirection: TrendDirection?
    ): MetricTrendResult? {
        val current = windows.lastOrNull()?.second ?: return null
        val baselineValue = baseline.value ?: return null
        val shortCutoff = nowMs - (adapter.shortWindowSec * 1000L)
        val shortValues = windows.filter { it.first >= shortCutoff }.map { it.second }
        val shortAvg = shortValues.takeIf { it.isNotEmpty() }?.average()
        val longAvg = windows.takeIf { it.isNotEmpty() }?.map { it.second }?.average()
        val delta = current - baselineValue
        val pct = if (abs(baselineValue) > 0.001) (delta / baselineValue) * 100.0 else null

        val direction = classifyTrendDirectionWithHysteresis(
            percentDeviation = pct,
            previous = previousDirection,
            policy = adapter.baselinePolicy
        )

        return MetricTrendResult(
            metricId = adapter.metricId,
            direction = direction,
            deviationFromBaseline = delta,
            percentDeviationFromBaseline = pct,
            shortTermAverage = shortAvg,
            longTermAverage = longAvg,
            ts = nowMs
        )
    }

    fun restoredStatus(adapter: BaselineMetricAdapter, snapshot: ProfileStore.MetricBaselineSnapshot, nowMs: Long): BaselineStatus {
        val ageMs = (nowMs - snapshot.lastUpdatedMs).coerceAtLeast(0L)
        return if (ageMs > adapter.baselinePolicy.baselineMaxStalenessMs) BaselineStatus.STALE else snapshot.status
    }

    internal fun classifyTrendDirectionWithHysteresis(
        percentDeviation: Double?,
        previous: TrendDirection?,
        policy: MetricBaselinePolicy
    ): TrendDirection {
        if (percentDeviation == null) return TrendDirection.UNKNOWN
        val enter = policy.trendEnterPercent
        val inner = policy.trendReturnToStableInnerPercent
        val p = percentDeviation
        return when (previous) {
            TrendDirection.DECLINING ->
                if (p > -inner) TrendDirection.STABLE else TrendDirection.DECLINING
            TrendDirection.IMPROVING ->
                if (p < inner) TrendDirection.STABLE else TrendDirection.IMPROVING
            TrendDirection.STABLE, TrendDirection.UNKNOWN, null -> when {
                p <= -enter -> TrendDirection.DECLINING
                p >= enter -> TrendDirection.IMPROVING
                else -> TrendDirection.STABLE
            }
        }
    }

    private fun estimateBaseline(samples: List<Pair<Long, Double>>, estimator: BaselineEstimator): Double? {
        if (samples.isEmpty()) return null
        val ordered = samples.sortedBy { it.first }
        val values = ordered.map { it.second }
        return when (estimator) {
            is BaselineEstimator.Mean -> values.average()
            is BaselineEstimator.Median -> {
                val s = values.sorted()
                s[s.size / 2]
            }
            is BaselineEstimator.TrimmedMean -> trimmedMean(values, estimator.trimFraction)
            is BaselineEstimator.Ema -> exponentialMovingAverage(ordered, estimator.alpha)
            is BaselineEstimator.Percentile -> percentileInclusive(values.sorted(), estimator.p)
            is BaselineEstimator.AnchoredSessionMean ->
                throw IllegalArgumentException(
                    "BaselineEstimator.AnchoredSessionMean is not implemented; configure a supported estimator or add a session model first."
                )
        }
    }

    private fun exponentialMovingAverage(orderedByTime: List<Pair<Long, Double>>, alpha: Double): Double {
        require(alpha in 0.0..1.0) { "EMA alpha must be in 0.0..1.0" }
        val first = orderedByTime.first().second
        var ema = first
        for (i in 1 until orderedByTime.size) {
            val x = orderedByTime[i].second
            ema = alpha * x + (1.0 - alpha) * ema
        }
        return ema
    }

    private fun percentileInclusive(sortedValues: List<Double>, p: Double): Double {
        require(p in 0.0..1.0) { "Percentile p must be in 0.0..1.0, was $p" }
        if (sortedValues.isEmpty()) return Double.NaN
        if (sortedValues.size == 1) return sortedValues[0]
        val n = sortedValues.size
        val rank = p * (n - 1)
        val lo = floor(rank).toInt().coerceIn(0, n - 2)
        val hi = lo + 1
        val gamma = rank - lo
        return sortedValues[lo] * (1.0 - gamma) + sortedValues[hi] * gamma
    }

    private fun trimmedMean(values: List<Double>, trimFraction: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val trimCount = (sorted.size * trimFraction).toInt().coerceAtMost((sorted.size - 1) / 2)
        val kept = sorted.subList(trimCount, sorted.size - trimCount)
        return kept.average()
    }
}
