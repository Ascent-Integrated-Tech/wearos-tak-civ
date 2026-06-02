package com.aitech.weartak_civ.cep.engine

import com.aitech.weartak_civ.cep.engine.metrics.HrvRmssdMetricAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricFeaturePublisherTest {
    @Test
    fun `warm start baseline map publishes persisted keys before live samples`() {
        val map = MetricFeaturePublisher.baselineMap(
            metricId = "hrv.rmssd_ms",
            value = 36.5,
            sampleCount = 24,
            confidence = BaselineConfidence.MEDIUM,
            status = BaselineStatus.READY_MEDIUM_CONFIDENCE
        )

        assertEquals(36.5, map["baseline.hrv.rmssd_ms"])
        assertEquals(24.0, map["baseline.hrv.rmssd_ms.sample_count"])
        assertEquals(BaselineConfidence.MEDIUM.ordinal.toDouble(), map["baseline.hrv.rmssd_ms.confidence"])
        assertEquals(BaselineStatus.READY_MEDIUM_CONFIDENCE.name, map["baseline.hrv.rmssd_ms.status"])
    }

    @Test
    fun `unknown trend map includes generic and selected hrv direction keys`() {
        val publication = MetricFeaturePublisher.unknownTrendPublication(HrvRmssdMetricAdapter())
        assertEquals("UNKNOWN", publication.outputs["trend.hrv.rmssd_ms.direction"])
        assertEquals("UNKNOWN", publication.outputs["selected.trends.hrv.direction"])
        assertTrue(publication.outputs.containsKey("selected.trends.hrv.direction"))
        assertTrue(publication.clearKeys.contains("selected.trends.hrv.short_term"))
        assertTrue(publication.clearKeys.contains("selected.trends.hrv.long_term"))
        assertTrue(publication.clearKeys.contains("selected.thresholds.hrv.low_rmssd_ms_warn"))
    }
}
