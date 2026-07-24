package com.aitech.weartak_civ.cep.metric

import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.NumericSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricDispatchRouterTest {

    @Test
    fun configuredTopicRoutesConfiguredSourceWithoutSourceSwitch() {
        val spec = metricSpec(source = "skin_temp", topic = "sensor_numeric", scalarField = "value")
        val router = MetricDispatchRouter(listOf(spec))

        assertEquals(setOf("skin_temp"), router.sourcesForEvent(CepEvent("sensor_numeric", NumericSample("sensor", "value", 31.5), 10L)))
        assertTrue(router.sourcesForEvent(CepEvent("unconfigured", NumericSample("sensor", "value", 31.5), 10L)).isEmpty())
    }

    @Test
    fun configuredScalarFieldExtractsGenericNumericPayload() {
        val spec = metricSpec(source = "skin_temp", topic = "sensor_numeric", scalarField = "value")
        val router = MetricDispatchRouter(listOf(spec))

        val value = router.extractScalar(
            CepEvent("sensor_numeric", NumericSample("sensor", "value", 31.5), 10L),
            spec
        )

        assertEquals(31.5, value!!, 0.0)
    }

    private fun metricSpec(source: String, topic: String, scalarField: String) = MetricSpec(
        id = "metric.$source",
        source = source,
        processor = MetricProcessorType.BASELINE_TREND,
        gating = MetricGatingPolicy(),
        baseline = MetricBaselinePolicy(
            windowSec = 86_400,
            minSamples = 2,
            maxStalenessSec = 3_600,
            confidenceThresholds = MetricConfidenceThresholds(5, 10, 20, 40)
        ),
        trend = MetricTrendPolicy(60, 300, 5.0, 3.0),
        selectedOutputs = MetricSelectedOutputMapping(emptyMap()),
        topic = topic,
        scalarField = scalarField
    )
}
