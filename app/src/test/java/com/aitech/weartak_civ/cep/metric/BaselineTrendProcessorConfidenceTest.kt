package com.aitech.weartak_civ.cep.metric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineTrendProcessorConfidenceTest {

    private val processor = BaselineTrendProcessor()

    @Test
    fun confidenceLowWhenBelowMinSamples() {
        val spec = testSpec(minSamples = 20, windowSec = 86_400, maxStalenessSec = 3_600)
        val state = MetricRuntimeState()
        val result = runWithSamples(
            spec = spec,
            state = state,
            sampleCount = 3,
            startTs = 0L,
            stepMs = 60_000L,
            value = 50.0
        )

        assertTrue(result.confidence < 0.30)
    }

    @Test
    fun confidenceIncreasesAsCoverageGrows() {
        val spec = testSpec(minSamples = 10, windowSec = 86_400, maxStalenessSec = 3_600)
        val shortState = MetricRuntimeState()
        val shortCoverage = runWithSamples(spec, shortState, sampleCount = 12, startTs = 0L, stepMs = 600L, value = 60.0)

        val wideState = MetricRuntimeState()
        val wideCoverage = runWithSamples(spec, wideState, sampleCount = 12, startTs = 0L, stepMs = 7_200_000L, value = 60.0)

        assertTrue(wideCoverage.confidence > shortCoverage.confidence)
    }

    @Test
    fun staleDataLowersConfidence() {
        val spec = testSpec(minSamples = 10, windowSec = 86_400, maxStalenessSec = 7_200, freshWithinSec = 7_200)
        val state = MetricRuntimeState()
        runWithSamples(spec, state, sampleCount = 20, startTs = 0L, stepMs = 3_600_000L, value = 55.0)

        val fresh = processor.compute(
            spec = spec,
            sample = null,
            gatingContext = testContext(nowMs = 20 * 3_600_000L, lastSampleAgeMs = 1_000L, isFresh = true),
            state = state
        )
        val stale = processor.compute(
            spec = spec,
            sample = null,
            gatingContext = testContext(nowMs = 20 * 3_600_000L, lastSampleAgeMs = 7_100_000L, isFresh = true),
            state = state
        )

        assertTrue(stale.confidence < fresh.confidence)
    }

    @Test
    fun noisyDataLowersConfidence() {
        val spec = testSpec(minSamples = 10, windowSec = 86_400, maxStalenessSec = 3_600)
        val stableState = MetricRuntimeState()
        val stable = runWithSamples(spec, stableState, sampleCount = 20, startTs = 0L, stepMs = 3_600_000L, value = 60.0)

        val noisyState = MetricRuntimeState()
        var ts = 0L
        var noisyResult = MetricComputationResult(emptyMap())
        repeat(20) { idx ->
            val value = if (idx % 2 == 0) 40.0 else 90.0
            noisyResult = processor.compute(
                spec = spec,
                sample = NormalizedMetricSample(value = value, tsMs = ts),
                gatingContext = testContext(nowMs = ts, lastSampleAgeMs = 0L, isFresh = true),
                state = noisyState
            )
            ts += 3_600_000L
        }

        assertTrue(noisyResult.confidence < stable.confidence)
    }

    @Test
    fun fullThirtyDayWindowScoresHigherThanFullOneDayWindow() {
        val daySpec = testSpec(minSamples = 20, windowSec = 86_400, maxStalenessSec = 3_600)
        val dayState = MetricRuntimeState()
        val dayResult = runWithSamples(daySpec, dayState, sampleCount = 24, startTs = 0L, stepMs = 3_600_000L, value = 50.0)

        val monthSpec = testSpec(minSamples = 20, windowSec = 30 * 86_400, maxStalenessSec = 3_600)
        val monthState = MetricRuntimeState()
        val monthResult = runWithSamples(
            spec = monthSpec,
            state = monthState,
            sampleCount = 30,
            startTs = 0L,
            stepMs = 86_400_000L,
            value = 50.0
        )

        assertTrue(monthResult.confidence > dayResult.confidence)
    }

    @Test
    fun maxStalenessIsEnforced() {
        val spec = testSpec(minSamples = 10, windowSec = 86_400, maxStalenessSec = 60)
        val state = MetricRuntimeState()
        runWithSamples(spec, state, sampleCount = 20, startTs = 0L, stepMs = 3_000L, value = 50.0)

        val staleResult = processor.compute(
            spec = spec,
            sample = null,
            gatingContext = testContext(nowMs = 1_000_000L, lastSampleAgeMs = 120_000L, isFresh = false),
            state = state
        )

        assertEquals(0.0, staleResult.confidence, 0.0)
        assertEquals(null, staleResult.valuesBySlot["baseline_confidence"])
        assertEquals("unknown", staleResult.valuesBySlot["trend_direction"])
    }

    @Test
    fun lowConfidenceStillPublishedInComputationResult() {
        val spec = testSpec(minSamples = 100, windowSec = 86_400, maxStalenessSec = 3_600)
        val state = MetricRuntimeState()
        val result = runWithSamples(spec, state, sampleCount = 3, startTs = 0L, stepMs = 1_000L, value = 55.0)

        val slotValue = result.valuesBySlot["baseline_confidence"] as? Double
        requireNotNull(slotValue)
        assertTrue(slotValue in 0.0..1.0)
        assertTrue(slotValue < 0.30)
        assertEquals(result.confidence, slotValue, 0.000001)
    }

    @Test
    fun sampleMaturityCountsOnlyHotSamples_notWarmOrColdRepresentatives() {
        val spec = testSpec(minSamples = 10, windowSec = 30 * 86_400, maxStalenessSec = 86_400)
        val result = processor.compute(
            spec = spec,
            sample = null,
            gatingContext = testContext(nowMs = 30 * 86_400_000L, lastSampleAgeMs = 1_000L, isFresh = true),
            state = MetricRuntimeState(),
            historyView = MetricHistoryView(
                hotSamples = listOf(
                    NormalizedMetricSample(value = 60.0, tsMs = 30 * 86_400_000L - 1_000L),
                    NormalizedMetricSample(value = 61.0, tsMs = 30 * 86_400_000L - 500L)
                ),
                warmBuckets = (0 until 24).map { idx ->
                    MetricBucket(
                        bucketStartTs = idx * 3_600_000L,
                        bucketSizeMs = 3_600_000L,
                        count = 100L,
                        sum = 6_000.0,
                        sumSquares = 360_000.0,
                        min = 55.0,
                        max = 65.0,
                        firstTs = idx * 3_600_000L,
                        lastTs = idx * 3_600_000L + 3_599_000L
                    )
                },
                coldBuckets = (0 until 30).map { day ->
                    MetricBucket(
                        bucketStartTs = day * 86_400_000L,
                        bucketSizeMs = 86_400_000L,
                        count = 500L,
                        sum = 30_000.0,
                        sumSquares = 1_800_000.0,
                        min = 50.0,
                        max = 70.0,
                        firstTs = day * 86_400_000L,
                        lastTs = day * 86_400_000L + 86_399_000L
                    )
                },
                hotWindowSec = 72 * 3600L,
                warmWindowSec = 7 * 24 * 3600L,
                coldWindowSec = 30 * 24 * 3600L
            )
        )
        val expectedMaturity = 2.0 / 10.0
        val upperBoundWithoutMaturity = 1.0 * 1.0 * 1.0 * 1.0 // coverage*recency*stability ceilinged by horizon below
        val horizonCeiling = 1.0 // 30-day window
        val practicalUpperBound = expectedMaturity * upperBoundWithoutMaturity * horizonCeiling
        assertTrue(result.confidence <= practicalUpperBound + 1e-6)
    }

    @Test
    fun tierAwareBaselineWithHistoryView_stillPublishesCoreSlots() {
        val spec = testSpec(minSamples = 2, windowSec = 30 * 86_400, maxStalenessSec = 86_400)
        val result = processor.compute(
            spec = spec,
            sample = null,
            gatingContext = testContext(nowMs = 30 * 86_400_000L, lastSampleAgeMs = 500L, isFresh = true),
            state = MetricRuntimeState(),
            historyView = MetricHistoryView(
                hotSamples = listOf(
                    NormalizedMetricSample(value = 58.0, tsMs = 30 * 86_400_000L - 2_000L),
                    NormalizedMetricSample(value = 60.0, tsMs = 30 * 86_400_000L - 1_000L)
                ),
                warmBuckets = listOf(
                    MetricBucket(29 * 86_400_000L, 3_600_000L, 20, 1180.0, 69620.0, 57.0, 61.0, 29 * 86_400_000L, 29 * 86_400_000L + 3_599_000L)
                ),
                coldBuckets = listOf(
                    MetricBucket(20 * 86_400_000L, 86_400_000L, 100, 5900.0, 348100.0, 56.0, 62.0, 20 * 86_400_000L, 20 * 86_400_000L + 86_399_000L)
                ),
                hotWindowSec = 72 * 3600L,
                warmWindowSec = 7 * 24 * 3600L,
                coldWindowSec = 30 * 24 * 3600L
            )
        )

        assertNotNull(result.valuesBySlot["trend_direction"])
        assertNotNull(result.valuesBySlot["trend_short_term"])
        assertNotNull(result.valuesBySlot["trend_long_term"])
        assertNotNull(result.valuesBySlot["baseline_confidence"])
    }

    private fun runWithSamples(
        spec: MetricSpec,
        state: MetricRuntimeState,
        sampleCount: Int,
        startTs: Long,
        stepMs: Long,
        value: Double
    ): MetricComputationResult {
        var result = MetricComputationResult(emptyMap())
        var ts = startTs
        repeat(sampleCount) {
            result = processor.compute(
                spec = spec,
                sample = NormalizedMetricSample(value = value, tsMs = ts),
                gatingContext = testContext(nowMs = ts, lastSampleAgeMs = 0L, isFresh = true),
                state = state
            )
            ts += stepMs
        }
        return result
    }

    private fun testSpec(
        minSamples: Int,
        windowSec: Int,
        maxStalenessSec: Int,
        freshWithinSec: Int? = null
    ): MetricSpec {
        return MetricSpec(
            id = "metric.test",
            source = "heart_rate",
            processor = MetricProcessorType.BASELINE_TREND,
            sourceHookId = null,
            gating = MetricGatingPolicy(onWrist = false, stillOrSleeping = false, freshWithinSec = freshWithinSec),
            baseline = MetricBaselinePolicy(
                estimator = "trimmed_mean",
                windowSec = windowSec,
                minSamples = minSamples,
                maxStalenessSec = maxStalenessSec,
                confidenceThresholds = MetricConfidenceThresholds(
                    warming = 5,
                    readyLow = 10,
                    medium = 20,
                    high = 40
                )
            ),
            trend = MetricTrendPolicy(
                shortWindowSec = 3_600,
                longWindowSec = 86_400,
                enterPercent = 10.0,
                returnToStableInnerPercent = 7.0
            ),
            selectedOutputs = MetricSelectedOutputMapping(
                slotsToSelectedKeys = mapOf(
                    "trend_direction" to "selected.trends.test.direction",
                    "trend_short_term" to "selected.trends.test.short_term",
                    "trend_long_term" to "selected.trends.test.long_term",
                    "low_threshold" to "selected.thresholds.test.low",
                    "low_threshold_warn" to "selected.thresholds.test.low_warn",
                    "baseline_confidence" to "selected.baseline.test.confidence"
                )
            )
        )
    }

    private fun testContext(
        nowMs: Long,
        lastSampleAgeMs: Long?,
        isFresh: Boolean
    ): MetricGatingContext {
        return MetricGatingContext(
            onWrist = true,
            isStill = true,
            isSleeping = false,
            isAwake = true,
            lastSampleAgeMs = lastSampleAgeMs,
            nowMs = nowMs,
            isFresh = isFresh,
            rawSourceKey = "heart_rate"
        )
    }
}
