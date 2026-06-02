package com.aitech.weartak_civ.cep.engine

import com.aitech.weartak_civ.cep.engine.metrics.HrvRmssdMetricAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MetricBaselineCalculatorTest {
    private val adapter = HrvRmssdMetricAdapter()

    @Test
    fun `baseline status transitions include ready low confidence`() {
        val now = 1_000_000L
        val three = List(3) { idx -> (now + idx * 60_000L) to (30.0 + idx) }
        val seven = List(7) { idx -> (now + idx * 60_000L) to (30.0 + idx) }
        val ten = List(10) { idx -> (now + idx * 60_000L) to (30.0 + idx) }
        val twenty = List(20) { idx -> (now + idx * 60_000L) to (30.0 + idx) }

        assertEquals(BaselineStatus.INSUFFICIENT_DATA, MetricBaselineCalculator.computeBaseline(adapter, three, now).status)
        assertEquals(BaselineStatus.WARMING, MetricBaselineCalculator.computeBaseline(adapter, seven, now).status)
        assertEquals(BaselineStatus.READY_LOW_CONFIDENCE, MetricBaselineCalculator.computeBaseline(adapter, ten, now).status)
        assertEquals(BaselineStatus.READY_MEDIUM_CONFIDENCE, MetricBaselineCalculator.computeBaseline(adapter, twenty, now).status)
    }

    @Test
    fun `baseline confidence transitions to high after doubled minimum`() {
        val now = 2_000_000L
        val forty = List(40) { idx -> (now + idx * 60_000L) to (40.0 + (idx % 3)) }
        val baseline = MetricBaselineCalculator.computeBaseline(adapter, forty, now)

        assertEquals(BaselineConfidence.HIGH, baseline.confidence)
        assertEquals(BaselineStatus.READY_HIGH_CONFIDENCE, baseline.status)
    }

    @Test
    fun `trend classification reflects hrv decline and recovery`() {
        val now = 3_000_000L
        val windows = listOf(
            (now - 180_000L) to 42.0,
            (now - 120_000L) to 39.0,
            (now - 60_000L) to 35.0,
            now to 30.0
        )
        val baseline = ComputedBaseline(
            metricId = adapter.metricId,
            value = 40.0,
            windowStartTs = now - 180_000L,
            windowEndTs = now,
            sampleCount = 25,
            confidence = BaselineConfidence.MEDIUM,
            lastUpdatedTs = now,
            status = BaselineStatus.READY_MEDIUM_CONFIDENCE
        )

        val declining = MetricBaselineCalculator.computeTrend(adapter, baseline, windows, now, null)
        assertNotNull(declining)
        assertEquals(TrendDirection.DECLINING, declining?.direction)

        val recoveringWindows = windows.dropLast(1) + (now to 45.0)
        val improving = MetricBaselineCalculator.computeTrend(adapter, baseline, recoveringWindows, now, null)
        assertEquals(TrendDirection.IMPROVING, improving?.direction)
    }

    @Test
    fun `trend hysteresis keeps declining until deviation recovers past inner band`() {
        val now = 3_100_000L
        val baseline = ComputedBaseline(
            metricId = adapter.metricId,
            value = 40.0,
            windowStartTs = now - 60_000L,
            windowEndTs = now,
            sampleCount = 25,
            confidence = BaselineConfidence.MEDIUM,
            lastUpdatedTs = now,
            status = BaselineStatus.READY_MEDIUM_CONFIDENCE
        )
        val strongDecline = listOf((now - 60_000L) to 40.0, now to 28.0)
        val first = MetricBaselineCalculator.computeTrend(adapter, baseline, strongDecline, now, null)
        assertEquals(TrendDirection.DECLINING, first?.direction)

        val mildRebound = listOf((now - 60_000L) to 40.0, now to 36.8)
        val stillDeclining = MetricBaselineCalculator.computeTrend(adapter, baseline, mildRebound, now, first?.direction)
        assertEquals(TrendDirection.DECLINING, stillDeclining?.direction)

        val recovered = listOf((now - 60_000L) to 40.0, now to 38.0)
        val stable = MetricBaselineCalculator.computeTrend(adapter, baseline, recovered, now, stillDeclining?.direction)
        assertEquals(TrendDirection.STABLE, stable?.direction)
    }

    @Test
    fun `calculator honors adapter configured trimmed mean estimator`() {
        val now = 4_000_000L
        val outlierSamples = listOf(
            now to 10.0,
            now + 1 to 11.0,
            now + 2 to 12.0,
            now + 3 to 13.0,
            now + 4 to 400.0
        )
        val meanAdapter = object : BaselineMetricAdapter {
            override val metricId: String = "test.mean"
            override val sourceKey: String = "test.mean"
            override val unit: String = "u"
            override val shortWindowSec: Long = 60
            override val longWindowSec: Long = 600
            override val minBaselineSamples: Int = 1
            override val sampleCadenceMs: Long = 1000
            override val qualifiedWindowMs: Long = 1000
            override val baselinePolicy: MetricBaselinePolicy = MetricBaselinePolicy(estimator = BaselineEstimator.Mean)
            override fun normalize(sample: Double, nowMs: Long, context: MetricRuntimeContext): NormalizedMetricSample? = null
        }
        val medianAdapter = object : BaselineMetricAdapter {
            override val metricId: String = "test.median"
            override val sourceKey: String = "test.median"
            override val unit: String = "u"
            override val shortWindowSec: Long = 60
            override val longWindowSec: Long = 600
            override val minBaselineSamples: Int = 1
            override val sampleCadenceMs: Long = 1000
            override val qualifiedWindowMs: Long = 1000
            override val baselinePolicy: MetricBaselinePolicy = MetricBaselinePolicy(estimator = BaselineEstimator.Median)
            override fun normalize(sample: Double, nowMs: Long, context: MetricRuntimeContext): NormalizedMetricSample? = null
        }

        val meanBaseline = MetricBaselineCalculator.computeBaseline(meanAdapter, outlierSamples, now)
        val medianBaseline = MetricBaselineCalculator.computeBaseline(medianAdapter, outlierSamples, now)
        assertEquals(89.2, meanBaseline.value ?: 0.0, 0.1)
        assertEquals(12.0, medianBaseline.value ?: 0.0, 0.1)
    }

    @Test
    fun `ema follows time order`() {
        val now = 5_000_000L
        val samples = listOf(now to 10.0, now + 1000 to 20.0, now + 2000 to 30.0)
        val emaAdapter = object : BaselineMetricAdapter {
            override val metricId = "e"
            override val sourceKey = "e"
            override val unit = "u"
            override val shortWindowSec = 60L
            override val longWindowSec = 600L
            override val minBaselineSamples = 1
            override val sampleCadenceMs = 1L
            override val qualifiedWindowMs = 1L
            override val baselinePolicy = MetricBaselinePolicy(estimator = BaselineEstimator.Ema(0.5))
            override fun normalize(sample: Double, nowMs: Long, context: MetricRuntimeContext) = null
        }
        val b = MetricBaselineCalculator.computeBaseline(emaAdapter, samples, now + 2000)
        assertEquals(22.5, b.value ?: 0.0, 1e-9)
    }

    @Test
    fun `percentile inclusive interpolation`() {
        val now = 6_000_000L
        val samples = listOf(now to 10.0, now + 1 to 20.0, now + 2 to 30.0)
        val p50Adapter = object : BaselineMetricAdapter {
            override val metricId = "p"
            override val sourceKey = "p"
            override val unit = "u"
            override val shortWindowSec = 60L
            override val longWindowSec = 600L
            override val minBaselineSamples = 1
            override val sampleCadenceMs = 1L
            override val qualifiedWindowMs = 1L
            override val baselinePolicy = MetricBaselinePolicy(estimator = BaselineEstimator.Percentile(0.5))
            override fun normalize(sample: Double, nowMs: Long, context: MetricRuntimeContext) = null
        }
        val b = MetricBaselineCalculator.computeBaseline(p50Adapter, samples, now)
        assertEquals(20.0, b.value ?: 0.0, 1e-9)
    }

    @Test
    fun `anchored session mean fails fast`() {
        val now = 7_000_000L
        val samples = listOf(now to 1.0)
        val badAdapter = object : BaselineMetricAdapter {
            override val metricId = "a"
            override val sourceKey = "a"
            override val unit = "u"
            override val shortWindowSec = 60L
            override val longWindowSec = 600L
            override val minBaselineSamples = 1
            override val sampleCadenceMs = 1L
            override val qualifiedWindowMs = 1L
            override val baselinePolicy = MetricBaselinePolicy(estimator = BaselineEstimator.AnchoredSessionMean)
            override fun normalize(sample: Double, nowMs: Long, context: MetricRuntimeContext) = null
        }
        try {
            MetricBaselineCalculator.computeBaseline(badAdapter, samples, now)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `restored status becomes stale when snapshot too old`() {
        val now = 8_000_000L
        val staleSnapshot = ProfileStore.MetricBaselineSnapshot(
            metricId = adapter.metricId,
            value = 35.0,
            sampleCount = 22,
            confidence = BaselineConfidence.MEDIUM,
            status = BaselineStatus.READY_MEDIUM_CONFIDENCE,
            windowStartTs = now - 2_000_000L,
            windowEndTs = now - 1_000_000L,
            lastUpdatedMs = now - adapter.baselinePolicy.baselineMaxStalenessMs - 1L
        )

        val status = MetricBaselineCalculator.restoredStatus(adapter, staleSnapshot, now)
        assertEquals(BaselineStatus.STALE, status)
    }
}
