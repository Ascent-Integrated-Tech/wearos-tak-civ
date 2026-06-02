package com.aitech.weartak_civ.cep.engine.metrics

import com.aitech.weartak_civ.cep.engine.BaselineStatus
import com.aitech.weartak_civ.cep.engine.BaselineConfidence
import com.aitech.weartak_civ.cep.engine.ComputedBaseline
import com.aitech.weartak_civ.cep.engine.MetricRuntimeContext
import com.aitech.weartak_civ.cep.engine.MetricTrendResult
import com.aitech.weartak_civ.cep.engine.TrendDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MetricAdaptersTest {
    @Test
    fun `hr adapter only normalizes still awake on-wrist samples`() {
        val adapter = HrRestingMetricAdapter()
        val ok = adapter.normalize(61.0, 1000L, MetricRuntimeContext(isStill = true, isSleeping = false, onWrist = true))
        val blocked = adapter.normalize(61.0, 1000L, MetricRuntimeContext(isStill = false, isSleeping = false, onWrist = true))

        assertNotNull(ok)
        assertNull(blocked)
    }

    @Test
    fun `hrv adapter requires valid value and restful context`() {
        val adapter = HrvRmssdMetricAdapter()
        val ok = adapter.normalize(30.0, 1000L, MetricRuntimeContext(isStill = true, isSleeping = false, onWrist = true))
        val invalid = adapter.normalize(-1.0, 1000L, MetricRuntimeContext(isStill = true, isSleeping = false, onWrist = true))
        val moving = adapter.normalize(30.0, 1000L, MetricRuntimeContext(isStill = false, isSleeping = false, onWrist = true))

        assertNotNull(ok)
        assertNull(invalid)
        assertNull(moving)
    }

    @Test
    fun `hrv selected outputs map includes threshold and direction`() {
        val adapter = HrvRmssdMetricAdapter()
        val baseline = ComputedBaseline(
            metricId = "hrv.rmssd_ms",
            value = 50.0,
            windowStartTs = 0L,
            windowEndTs = 1L,
            sampleCount = 30,
            confidence = BaselineConfidence.MEDIUM,
            lastUpdatedTs = 1L,
            status = BaselineStatus.READY_MEDIUM_CONFIDENCE
        )
        val trend = MetricTrendResult(
            metricId = "hrv.rmssd_ms",
            direction = TrendDirection.DECLINING,
            deviationFromBaseline = -10.0,
            percentDeviationFromBaseline = -20.0,
            shortTermAverage = 40.0,
            longTermAverage = 50.0,
            ts = 1L
        )

        val outputs = adapter.selectedOutputs(baseline, trend)
        assertEquals(40.0, outputs["selected.thresholds.hrv.low_rmssd_ms"])
        assertEquals(40.0, outputs["selected.thresholds.hrv.low_rmssd_ms_warn"])
        assertEquals("DECLINING", outputs["selected.trends.hrv.direction"])
    }
}
