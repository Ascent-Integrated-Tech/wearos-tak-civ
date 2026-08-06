package com.aitech.weartak_civ.cep.metric

import com.aitech.weartak_civ.cep.engine.FeatureStore
import com.aitech.weartak_civ.cep.engine.RingConfig
import com.aitech.weartak_civ.cep.engine.WindowStore
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.Topics
import com.aitech.weartak_civ.cep.model.payloads.SkinTempPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricThresholdAndSkinTemperatureTest {

    @Test
    fun restingThresholdsAreDerivedFromOneCanonicalBaseline() {
        val processor = BaselineTrendProcessor()
        val spec = MetricSpec(
            id = "hr.resting_bpm.resting",
            source = "heart_rate",
            processor = MetricProcessorType.BASELINE_TREND,
            gating = MetricGatingPolicy(),
            baseline = MetricBaselinePolicy(
                windowSec = 86_400,
                minSamples = 1,
                maxStalenessSec = 86_400,
                thresholdPolicy = MetricThresholdPolicy(
                    lowAlertPercent = 0.20,
                    lowWarningPercent = 0.10,
                    highWarningPercent = 0.10,
                    highAlertPercent = 0.20
                ),
                confidenceThresholds = MetricConfidenceThresholds(5, 10, 20, 40)
            ),
            trend = MetricTrendPolicy(300, 1_800, 6.0, 4.0),
            selectedOutputs = MetricSelectedOutputMapping(emptyMap())
        )
        val history = MetricHistoryView(
            hotSamples = listOf(
                NormalizedMetricSample(60.0, 1_000L),
                NormalizedMetricSample(60.0, 2_000L),
                NormalizedMetricSample(60.0, 3_000L)
            ),
            warmBuckets = emptyList(),
            coldBuckets = emptyList(),
            hotWindowSec = 72 * 3600L,
            warmWindowSec = 7 * 24 * 3600L,
            coldWindowSec = 30 * 24 * 3600L
        )

        val core = processor.computeCore(spec, history)

        assertEquals(48.0, core.lowThreshold!!, 0.0)
        assertEquals(54.0, core.lowThresholdWarn!!, 0.0)
        assertEquals(66.0, core.highThresholdWarn!!, 0.0)
        assertEquals(72.0, core.highThreshold!!, 0.0)
    }

    @Test
    fun skinTemperatureHookReturnsMedianAndRejectsInvalidValues() {
        val hook = PeripheralSkinTemperatureMedianHook()
        val context = MetricGatingContext(
            onWrist = true,
            isStill = true,
            isSleeping = false,
            isAwake = true,
            lastSampleAgeMs = 0L,
            nowMs = 1_000L,
            isFresh = true,
            rawSourceKey = "skin_temp"
        )

        val sample = hook.normalize(
            SkinTempPayload(listOf(32.0, 100.0, 30.0, 31.0, 33.0), ts = 1_000L),
            context
        )
        assertEquals(31.5, sample?.value ?: 0.0, 0.0)
        assertEquals(1_000L, sample?.tsMs)

        assertNull(hook.normalize(SkinTempPayload(listOf(100.0, -20.0), ts = 2_000L), context))
    }

    @Test
    fun skinTemperatureIsPublishedWithFreshnessTimestamp() {
        val featureStore = FeatureStore()
        val timestamp = 12_345L
        WindowStore(RingConfig.constrained()).ingest(
            CepEvent(Topics.SKIN_TEMP, SkinTempPayload(listOf(31.0, 32.0), ts = timestamp), timestamp),
            featureStore,
            timestamp
        )

        assertTrue(featureStore.any("skin_temp") is List<*>)
        assertEquals(0.0, featureStore.ageSeconds("skin_temp", timestamp)!!, 0.0)
    }

    @Test
    fun personalizedRestingThresholdsReplaceStaticValuesOnlyWhenEstablished() {
        val featureStore = FeatureStore()
        featureStore.put("thresholds.hr.rest_high_bpm", 120.0, 0L)
        featureStore.put("thresholds.hr.rest_low_bpm", 45.0, 0L)
        featureStore.put("selected.baseline.hr.state", "WARMING", 0L)
        featureStore.put("selected.thresholds.hr.high_bpm", 72.0, 0L)
        featureStore.put("selected.thresholds.hr.low_bpm", 48.0, 0L)
        val orchestrator = MetricRuntimeOrchestrator(emptyList())

        orchestrator.applyRestingHrThresholds(featureStore, 1_000L)
        assertEquals(120.0, featureStore.latestAsDouble("thresholds.hr.rest_high_bpm")!!, 0.0)
        assertEquals(45.0, featureStore.latestAsDouble("thresholds.hr.rest_low_bpm")!!, 0.0)

        featureStore.put("selected.baseline.hr.state", "ESTABLISHED", 2_000L)
        orchestrator.applyRestingHrThresholds(featureStore, 2_000L)
        assertEquals(72.0, featureStore.latestAsDouble("thresholds.hr.rest_high_bpm")!!, 0.0)
        assertEquals(48.0, featureStore.latestAsDouble("thresholds.hr.rest_low_bpm")!!, 0.0)
    }
}
