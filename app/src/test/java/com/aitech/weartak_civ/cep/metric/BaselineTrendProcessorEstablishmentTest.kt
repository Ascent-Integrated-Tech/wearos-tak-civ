package com.aitech.weartak_civ.cep.metric

import org.junit.Assert.assertEquals
import org.junit.Test

class BaselineTrendProcessorEstablishmentTest {

    private val processor = BaselineTrendProcessor()

    @Test
    fun noSamples_reportsNoData() {
        val result = compute(samples = emptyList())

        assertEquals("NO_DATA", result.valuesBySlot["baseline_state"])
    }

    @Test
    fun belowMinimumSamples_reportsWarming() {
        val result = compute(
            samples = samples(count = 2, stepMs = HOUR_MS),
            minSamples = 3,
            minCoverageSec = HOUR_MS / 1000L
        )

        assertEquals("WARMING", result.valuesBySlot["baseline_state"])
    }

    @Test
    fun insufficientCoverage_reportsWarming() {
        val result = compute(
            samples = samples(count = 3, stepMs = HOUR_MS),
            minSamples = 3,
            minCoverageSec = 24L * HOUR_MS / 1000L
        )

        assertEquals("WARMING", result.valuesBySlot["baseline_state"])
    }

    @Test
    fun lowConfidence_reportsProvisional() {
        val result = compute(
            samples = samples(count = 20, stepMs = HOUR_MS),
            minSamples = 20,
            minCoverageSec = 10L * HOUR_MS / 1000L,
            minConfidence = 0.99
        )

        assertEquals("PROVISIONAL", result.valuesBySlot["baseline_state"])
    }

    @Test
    fun establishedBaselineRemainsEstablishedOnPeriodicTickWithinMaxStaleness() {
        val history = samples(count = 20, stepMs = HOUR_MS)
        val result = processor.compute(
            spec = spec(
                minSamples = 20,
                minCoverageSec = 10L * HOUR_MS / 1000L,
                minConfidence = 0.0,
                maxStalenessSec = 2 * 3600
            ),
            sample = null,
            gatingContext = context(
                nowMs = history.last().tsMs + 3600_000L,
                lastSampleAgeMs = 3600_000L,
                isFresh = false
            ),
            state = MetricRuntimeState(),
            historyView = MetricHistoryView(
                hotSamples = history,
                warmBuckets = emptyList(),
                coldBuckets = emptyList(),
                hotWindowSec = 72 * 3600L,
                warmWindowSec = 7 * 24 * 3600L,
                coldWindowSec = 30 * 24 * 3600L
            )
        )

        assertEquals("ESTABLISHED", result.valuesBySlot["baseline_state"])
    }

    @Test
    fun establishedBaselineRemainsEstablishedWhileSamplingIsPausedForActivity() {
        val history = samples(count = 20, stepMs = HOUR_MS)
        val result = processor.compute(
            spec = spec(20, 10L * HOUR_MS / 1000L, 0.0),
            sample = null,
            gatingContext = context(history.last().tsMs, 0L, true).copy(isStill = false),
            state = MetricRuntimeState(),
            historyView = historyView(history)
        )

        assertEquals("ESTABLISHED", result.valuesBySlot["baseline_state"])
        assertEquals("INELIGIBLE_ACTIVE", result.valuesBySlot["sampling_state"])
    }

    @Test
    fun establishedBaselineRemainsEstablishedWhileWatchIsOffWrist() {
        val history = samples(count = 20, stepMs = HOUR_MS)
        val result = processor.compute(
            spec = spec(20, 10L * HOUR_MS / 1000L, 0.0),
            sample = null,
            gatingContext = context(history.last().tsMs, 0L, true).copy(onWrist = false),
            state = MetricRuntimeState(),
            historyView = historyView(history)
        )

        assertEquals("ESTABLISHED", result.valuesBySlot["baseline_state"])
        assertEquals("INELIGIBLE_OFF_WRIST", result.valuesBySlot["sampling_state"])
    }

    @Test
    fun staleBaseline_reportsStaleAfterMaxStaleness() {
        val history = samples(count = 20, stepMs = HOUR_MS)
        val result = processor.compute(
            spec = spec(
                minSamples = 20,
                minCoverageSec = 10L * HOUR_MS / 1000L,
                minConfidence = 0.0,
                maxStalenessSec = 2 * 3600
            ),
            sample = null,
            gatingContext = context(
                nowMs = history.last().tsMs + 3 * 3600_000L,
                lastSampleAgeMs = 3 * 3600_000L,
                isFresh = false
            ),
            state = MetricRuntimeState(),
            historyView = MetricHistoryView(
                hotSamples = history,
                warmBuckets = emptyList(),
                coldBuckets = emptyList(),
                hotWindowSec = 72 * 3600L,
                warmWindowSec = 7 * 24 * 3600L,
                coldWindowSec = 30 * 24 * 3600L
            )
        )

        assertEquals("STALE", result.valuesBySlot["baseline_state"])
    }

    @Test
    fun establishedState_latchesUntilStaleEvenWhenConfidenceFalls() {
        val history = samples(count = 20, stepMs = HOUR_MS)
        val state = MetricRuntimeState()
        val established = processor.compute(
            spec = spec(20, 10L * HOUR_MS / 1000L, 0.0),
            sample = null,
            gatingContext = context(history.last().tsMs, 0L, true),
            state = state,
            historyView = historyView(history)
        )
        val later = processor.compute(
            spec = spec(20, 10L * HOUR_MS / 1000L, 0.99),
            sample = null,
            gatingContext = context(history.last().tsMs + HOUR_MS, HOUR_MS, false),
            state = state,
            historyView = historyView(history)
        )

        assertEquals("ESTABLISHED", established.valuesBySlot["baseline_state"])
        assertEquals("ESTABLISHED", later.valuesBySlot["baseline_state"])
    }

    @Test
    fun gappySamples_reportCoveredDurationCappedByPolicy() {
        val history = listOf(
            NormalizedMetricSample(60.0, 0L),
            NormalizedMetricSample(60.0, 0L),
            NormalizedMetricSample(60.0, 24 * HOUR_MS)
        )
        val result = processor.compute(
            spec = spec(3, 0L, 0.0, maxGapSec = 3600L),
            sample = null,
            gatingContext = context(24 * HOUR_MS, 0L, true),
            state = MetricRuntimeState(),
            historyView = historyView(history)
        )

        assertEquals(3600L, result.valuesBySlot["baseline_covered_sec"])
    }

    private fun compute(
        samples: List<NormalizedMetricSample>,
        minSamples: Int = 20,
        minCoverageSec: Long = 0L,
        minConfidence: Double = 0.0,
        maxStalenessSec: Int = 86_400
    ): MetricComputationResult {
        val newest = samples.lastOrNull()?.tsMs ?: 0L
        return processor.compute(
            spec = spec(minSamples, minCoverageSec, minConfidence, maxStalenessSec),
            sample = null,
            gatingContext = context(newest, 0L, true),
            state = MetricRuntimeState(),
            historyView = historyView(samples)
        )
    }

    private fun spec(
        minSamples: Int,
        minCoverageSec: Long,
        minConfidence: Double,
        maxStalenessSec: Int = 86_400,
        maxGapSec: Long = 0L
    ) = MetricSpec(
        id = "metric.establishment.test",
        source = "heart_rate",
        processor = MetricProcessorType.BASELINE_TREND,
        gating = MetricGatingPolicy(onWrist = true, stillOrSleeping = true, freshWithinSec = 5),
        baseline = MetricBaselinePolicy(
            windowSec = 30 * 86_400,
            minSamples = minSamples,
            maxStalenessSec = maxStalenessSec,
            confidenceThresholds = MetricConfidenceThresholds(5, 10, 20, 40),
            establishment = MetricEstablishmentPolicy(
                minCoverageSec = minCoverageSec,
                minActiveDays = 0,
                minConfidence = minConfidence,
                maxGapSec = maxGapSec
            )
        ),
        trend = MetricTrendPolicy(3600, 86_400, 5.0, 3.0),
        selectedOutputs = MetricSelectedOutputMapping(emptyMap())
    )

    private fun context(nowMs: Long, lastSampleAgeMs: Long, isFresh: Boolean) = MetricGatingContext(
        onWrist = true,
        isStill = true,
        isSleeping = false,
        isAwake = true,
        lastSampleAgeMs = lastSampleAgeMs,
        nowMs = nowMs,
        isFresh = isFresh,
        rawSourceKey = "heart_rate"
    )

    private fun samples(count: Int, stepMs: Long): List<NormalizedMetricSample> =
        (0 until count).map { index ->
            NormalizedMetricSample(value = 60.0, tsMs = index * stepMs)
        }

    private fun historyView(samples: List<NormalizedMetricSample>) = MetricHistoryView(
        hotSamples = samples,
        warmBuckets = emptyList(),
        coldBuckets = emptyList(),
        hotWindowSec = 72 * 3600L,
        warmWindowSec = 7 * 24 * 3600L,
        coldWindowSec = 30 * 24 * 3600L
    )

    companion object {
        private const val HOUR_MS = 3_600_000L
    }
}
