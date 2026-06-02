package com.aitech.weartak_civ.cep.metric

import com.aitech.weartak_civ.cep.engine.FeatureStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MetricOutputMapperConfidenceTest {

    @Test
    fun publishesBaselineConfidenceToSelectedKey() {
        val featureStore = FeatureStore()
        val mapping = MetricSelectedOutputMapping(
            slotsToSelectedKeys = mapOf(
                "baseline_confidence" to "selected.baseline.hrv.confidence"
            )
        )
        val result = MetricComputationResult(
            valuesBySlot = mapOf("baseline_confidence" to 0.42),
            confidence = 0.42
        )

        MetricOutputMapper.publish(featureStore, mapping, result, nowMs = 1234L)

        val published = featureStore.latestAsDouble("selected.baseline.hrv.confidence")
        assertNotNull(published)
        assertEquals(0.42, published!!, 0.000001)
    }

    @Test
    fun lowConfidencePublishesInsteadOfDisappearing() {
        val featureStore = FeatureStore()
        val mapping = MetricSelectedOutputMapping(
            slotsToSelectedKeys = mapOf(
                "baseline_confidence" to "selected.baseline.hr.confidence"
            )
        )
        val result = MetricComputationResult(
            valuesBySlot = mapOf("baseline_confidence" to 0.07),
            confidence = 0.07
        )

        MetricOutputMapper.publish(featureStore, mapping, result, nowMs = 5678L)

        val published = featureStore.latestAsDouble("selected.baseline.hr.confidence")
        assertNotNull(published)
        assertEquals(0.07, published!!, 0.000001)
    }

    @Test
    fun stalenessStyleUnknownClearsMappedConfidenceOutput() {
        val featureStore = FeatureStore()
        val key = "selected.baseline.hrv.confidence"
        featureStore.put(key, 0.93, 1000L)

        val mapping = MetricSelectedOutputMapping(
            slotsToSelectedKeys = mapOf("baseline_confidence" to key)
        )
        val unknownResult = MetricComputationResult(
            valuesBySlot = mapOf("baseline_confidence" to null),
            confidence = 0.0
        )

        MetricOutputMapper.publish(featureStore, mapping, unknownResult, nowMs = 2000L)

        assertEquals(null, featureStore.any(key))
    }
}
