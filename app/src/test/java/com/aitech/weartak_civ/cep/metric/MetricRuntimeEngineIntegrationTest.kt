package com.aitech.weartak_civ.cep.metric

import com.aitech.weartak_civ.cep.engine.CepEngine
import com.aitech.weartak_civ.cep.engine.CepConfig
import com.aitech.weartak_civ.cep.engine.FeatureStore
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.Topics
import com.aitech.weartak_civ.cep.model.payloads.ActivityPayload
import com.aitech.weartak_civ.cep.model.payloads.HeartRatePayload
import com.aitech.weartak_civ.cep.model.payloads.HrvPayload
import com.aitech.weartak_civ.cep.model.payloads.OffBodyPayload
import com.aitech.weartak_civ.cep.model.payloads.SleepPayload
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MetricRuntimeEngineIntegrationTest {

    @Test
    fun hrvIntegration_confidenceRises_andSelectedOutputsPublish() = runBlocking {
        val engine = newEngine()
        engine.initializeMetricRuntimeForTest()
        var ts = 0L
        repeat(30) { idx ->
            emitCommonGatingEvents(engine, ts)
            engine.processEventForTest(
                CepEvent(
                    topic = Topics.HRV,
                    payload = HrvPayload(rmssd_ms = 35.0 + (idx % 5), ts = ts),
                    ts = ts
                )
            )
            ts += 3_600_000L
        }

        val earlyConfidence = run {
            val earlyEngine = newEngine()
            earlyEngine.initializeMetricRuntimeForTest()
            var earlyTs = 0L
            repeat(5) { idx ->
                emitCommonGatingEvents(earlyEngine, earlyTs)
                earlyEngine.processEventForTest(
                    CepEvent(
                        topic = Topics.HRV,
                        payload = HrvPayload(rmssd_ms = 35.0 + (idx % 5), ts = earlyTs),
                        ts = earlyTs
                    )
                )
                earlyTs += 3_600_000L
            }
            earlyEngine.featureSnapshotForTest(listOf("selected.baseline.hrv.confidence"))
                .getValue("selected.baseline.hrv.confidence") as Double
        }

        val snapshot = engine.featureSnapshotForTest(
            listOf(
                "selected.baseline.hrv.confidence",
                "selected.trends.hrv.direction",
                "selected.trends.hrv.short_term",
                "selected.trends.hrv.long_term",
                "selected.baseline.hrv.state"
            )
        )
        val finalConfidence = snapshot["selected.baseline.hrv.confidence"] as? Double
        assertNotNull(finalConfidence)
        assertTrue(finalConfidence!! in 0.0..1.0)
        assertTrue("final=$finalConfidence early=$earlyConfidence", finalConfidence > earlyConfidence)
        assertNotNull(snapshot["selected.trends.hrv.direction"])
        assertNotNull(snapshot["selected.trends.hrv.short_term"])
        assertNotNull(snapshot["selected.trends.hrv.long_term"])
        assertNotNull(snapshot["selected.baseline.hrv.state"])
    }

    @Test
    fun restingHeartRate_ingestionRejectsActiveSamples() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("metric_gating").toFile()
        val spec = MetricSpec(
            id = "hr.gated",
            source = "heart_rate",
            processor = MetricProcessorType.BASELINE_TREND,
            gating = MetricGatingPolicy(onWrist = true, stillOrSleeping = true),
            validity = MetricValidityPolicy(minValue = 20.0, maxValue = 240.0),
            baseline = MetricBaselinePolicy(
                windowSec = 3600,
                minSamples = 1,
                maxStalenessSec = 3600,
                confidenceThresholds = MetricConfidenceThresholds(5, 10, 20, 40)
            ),
            trend = MetricTrendPolicy(60, 300, 5.0, 3.0),
            selectedOutputs = MetricSelectedOutputMapping(emptyMap())
        )
        val store = FileBackedMetricHistoryStore(dir)
        val orchestrator = MetricRuntimeOrchestrator(
            dataRepository = null,
            metricSpecs = listOf(spec),
            appContext = null,
            historyStore = store
        )
        orchestrator.initialize()
        val featureStore = FeatureStore()
        featureStore.put("offbody.on_wrist", 1.0, 0L)
        featureStore.put("activity.state", "WALKING", 0L)
        featureStore.put("sleep.is_sleeping", false, 0L)

        orchestrator.onEvent(
            CepEvent(Topics.HEART_RATE, HeartRatePayload(150.0, 1_000L), 1_000L),
            featureStore
        )
        assertEquals(0, store.loadHotSamples("hr.gated", 0L).size)

        featureStore.put("activity.state", "STILL", 2_000L)
        orchestrator.onEvent(
            CepEvent(Topics.HEART_RATE, HeartRatePayload(65.0, 2_000L), 2_000L),
            featureStore
        )
        assertEquals(1, store.loadHotSamples("hr.gated", 0L).size)
    }

    @Test
    fun restingHrIntegration_noHookPath_publishesConfidenceAndTrendOutputs() = runBlocking {
        val engine = newEngine()
        engine.initializeMetricRuntimeForTest()
        var ts = 0L
        repeat(25) { idx ->
            emitCommonGatingEvents(engine, ts)
            engine.processEventForTest(
                CepEvent(
                    topic = Topics.HEART_RATE,
                    payload = HeartRatePayload(bpm = 58.0 + (idx % 4), ts = ts),
                    ts = ts
                )
            )
            ts += 1000L
        }

        val snapshot = engine.featureSnapshotForTest(
            listOf(
                "selected.baseline.hr.confidence",
                "selected.trends.hr.direction",
                "selected.trends.hr.short_term",
                "selected.trends.hr.long_term"
            )
        )
        val confidence = snapshot["selected.baseline.hr.confidence"] as? Double
        assertNotNull(confidence)
        assertTrue(confidence!! in 0.0..1.0)
        assertNotNull(snapshot["selected.trends.hr.direction"])
        assertNotNull(snapshot["selected.trends.hr.short_term"])
        assertNotNull(snapshot["selected.trends.hr.long_term"])
    }

    @Test
    fun engineE2e_tickBasedStalenessInvalidation_clearsPublishedConfidence() = runBlocking {
        val engine = newEngine()
        engine.initializeMetricRuntimeForTest()
        val startTs = 0L
        repeat(25) { idx ->
            val ts = startTs + idx * 1000L
            emitCommonGatingEvents(engine, ts)
            engine.processEventForTest(
                CepEvent(
                    topic = Topics.HEART_RATE,
                    payload = HeartRatePayload(bpm = 62.0, ts = ts),
                    ts = ts
                )
            )
        }
        val pre = engine.featureSnapshotForTest(listOf("selected.baseline.hr.confidence"))
        assertNotNull(pre["selected.baseline.hr.confidence"])

        val staleNow = 7_300_000L // > 7200s max staleness for hr.resting_bpm
        engine.processMetricTickForTest(staleNow)

        val post = engine.featureSnapshotForTest(
            listOf(
                "selected.baseline.hr.confidence",
                "selected.trends.hr.direction"
            )
        )
        assertEquals(null, post["selected.baseline.hr.confidence"])
        assertEquals("unknown", post["selected.trends.hr.direction"])
    }

    @Test
    fun baselineTrendSelectedOutputContract_isFrozenAndComplete() {
        val expected = setOf(
            "trend_direction",
            "current_value",
            "trend_short_term",
            "trend_long_term",
            "trend_deviation",
            "trend_percent_deviation",
            "low_threshold",
            "low_threshold_warn",
            "high_threshold",
            "high_threshold_warn",
            "baseline_confidence",
            "baseline_state",
            "baseline_sample_count",
            "baseline_coverage_sec",
            "baseline_covered_sec",
            "sampling_state"
        )
        assertEquals(expected, MetricRuntimeOrchestrator.BASELINE_TREND_CANONICAL_SLOTS)
    }

    @Test
    fun heartRateReserveHook_usesProfileRestAndMaximum() {
        val featureStore = FeatureStore()
        featureStore.put("profile.hr_rest_bpm", 60.0, 0L)
        featureStore.put("profile.hr_max_bpm", 190.0, 0L)
        val context = MetricGatingContext(
            onWrist = true,
            isStill = false,
            isSleeping = false,
            isAwake = true,
            lastSampleAgeMs = 0L,
            nowMs = 1_000L,
            isFresh = true,
            rawSourceKey = "heart_rate",
            hrRestBpm = featureStore.latestAsDouble("profile.hr_rest_bpm"),
            hrMaxBpm = featureStore.latestAsDouble("profile.hr_max_bpm")
        )

        val sample = HeartRateReservePercentHook().normalize(
            HeartRatePayload(bpm = 125.0, ts = 1_000L),
            context
        )

        assertNotNull(sample)
        assertEquals(50.0, sample!!.value, 0.0001)
        assertEquals(1_000L, sample.tsMs)
    }

    @Test
    fun activeContext_dwellPreventsImmediateIngestion() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("metric_activity_context").toFile()
        val spec = MetricSpec(
            id = "hr.active.test",
            source = "heart_rate",
            processor = MetricProcessorType.BASELINE_TREND,
            gating = MetricGatingPolicy(
                onWrist = true,
                activityContext = MetricActivityContext.ACTIVE,
                minContextDwellSec = 10L
            ),
            validity = MetricValidityPolicy(minValue = 20.0, maxValue = 240.0),
            baseline = MetricBaselinePolicy(
                windowSec = 3600,
                minSamples = 1,
                maxStalenessSec = 3600,
                confidenceThresholds = MetricConfidenceThresholds(5, 10, 20, 40)
            ),
            trend = MetricTrendPolicy(60, 300, 5.0, 3.0),
            selectedOutputs = MetricSelectedOutputMapping(emptyMap())
        )
        val store = FileBackedMetricHistoryStore(dir)
        val orchestrator = MetricRuntimeOrchestrator(
            dataRepository = null,
            metricSpecs = listOf(spec),
            appContext = null,
            historyStore = store
        )
        orchestrator.initialize()
        val featureStore = FeatureStore()
        featureStore.put("offbody.on_wrist", 1.0, 0L)
        featureStore.put("activity.state", "WALKING", 0L)
        featureStore.put("sleep.is_sleeping", false, 0L)

        orchestrator.onEvent(CepEvent(Topics.HEART_RATE, HeartRatePayload(120.0, 0L), 0L), featureStore)
        assertEquals(0, store.loadHotSamples("hr.active.test", 0L).size)

        orchestrator.onEvent(CepEvent(Topics.HEART_RATE, HeartRatePayload(121.0, 11_000L), 11_000L), featureStore)
        assertEquals(1, store.loadHotSamples("hr.active.test", 0L).size)
        assertEquals(121.0, store.loadHotSamples("hr.active.test", 0L).single().value, 0.0)
    }

    @Test
    fun contextualRestingAndActiveBaselines_canBecomeEstablished() = runBlocking {
        suspend fun assertEstablished(
            activityState: String,
            activityContext: MetricActivityContext,
            metricId: String,
            outputKey: String
        ) {
            val dir = java.nio.file.Files.createTempDirectory("metric_establishment").toFile()
            val spec = MetricSpec(
                id = metricId,
                source = "heart_rate",
                processor = MetricProcessorType.BASELINE_TREND,
                gating = MetricGatingPolicy(
                    onWrist = true,
                    activityContext = activityContext
                ),
                validity = MetricValidityPolicy(minValue = 20.0, maxValue = 240.0),
                baseline = MetricBaselinePolicy(
                    windowSec = 172_800,
                    minSamples = 2,
                    maxStalenessSec = 200_000,
                    confidenceThresholds = MetricConfidenceThresholds(5, 10, 20, 40),
                    establishment = MetricEstablishmentPolicy(
                        minCoverageSec = 1L,
                        minActiveDays = 2,
                        minConfidence = 0.0,
                        maxGapSec = 200_000L
                    ),
                    coverageTargetSec = 1L,
                    stabilityWeight = if (activityContext == MetricActivityContext.ACTIVE) 0.0 else 1.0
                ),
                trend = MetricTrendPolicy(3600, 86_400, 10.0, 7.0),
                selectedOutputs = MetricSelectedOutputMapping(
                    mapOf("baseline_state" to outputKey)
                )
            )
            val store = FileBackedMetricHistoryStore(dir)
            val orchestrator = MetricRuntimeOrchestrator(
                dataRepository = null,
                metricSpecs = listOf(spec),
                appContext = null,
                historyStore = store
            )
            orchestrator.initialize()
            val featureStore = FeatureStore()
            featureStore.put("offbody.on_wrist", 1.0, 0L)
            featureStore.put("activity.state", activityState, 0L)
            featureStore.put("sleep.is_sleeping", false, 0L)

            listOf(0L, 86_400_000L).forEach { ts ->
                orchestrator.onEvent(
                    CepEvent(Topics.HEART_RATE, HeartRatePayload(65.0, ts), ts),
                    featureStore
                )
            }

            assertEquals("ESTABLISHED", featureStore.any(outputKey))
        }

        assertEstablished("STILL", MetricActivityContext.RESTING, "hr.resting.establish", "selected.test.resting.state")
        assertEstablished("WALKING", MetricActivityContext.ACTIVE, "hr.active.establish", "selected.test.active.state")
    }

    @Test
    fun productionLoop_periodicMetricTick_clearsStaleOutputsWithoutNewSensorEvents() = runBlocking {
        val config = CepConfig(
            tickHz = 1,
            features = emptyList(),
            rules = emptyList(),
            metrics = listOf(
                MetricSpec(
                    id = "hr.tick.stale",
                    source = "heart_rate",
                    processor = MetricProcessorType.BASELINE_TREND,
                    sourceHookId = null,
                    gating = MetricGatingPolicy(onWrist = true, stillOrSleeping = false, freshWithinSec = 1),
                    baseline = MetricBaselinePolicy(
                        estimator = "trimmed_mean",
                        windowSec = 3600,
                        minSamples = 2,
                        maxStalenessSec = 1,
                        confidenceThresholds = MetricConfidenceThresholds(5, 10, 20, 40)
                    ),
                    trend = MetricTrendPolicy(
                        shortWindowSec = 60,
                        longWindowSec = 300,
                        enterPercent = 5.0,
                        returnToStableInnerPercent = 3.0
                    ),
                    selectedOutputs = MetricSelectedOutputMapping(
                        slotsToSelectedKeys = mapOf(
                            "trend_direction" to "selected.trends.tick.direction",
                            "trend_short_term" to "selected.trends.tick.short_term",
                            "trend_long_term" to "selected.trends.tick.long_term",
                            "low_threshold" to "selected.thresholds.tick.low",
                            "low_threshold_warn" to "selected.thresholds.tick.low_warn",
                            "baseline_confidence" to "selected.baseline.tick.confidence"
                        )
                    )
                )
            ),
            ruleEvalIntervalMs = 100
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = CepEngine(
            appContext = RuntimeEnvironment.getApplication(),
            scope = scope,
            config = config,
            dataRepository = mockk(relaxed = true)
        )
        engine.initializeMetricRuntimeForTest()
        val ts = 1_000L
        emitCommonGatingEvents(engine, ts)
        engine.processEventForTest(CepEvent(Topics.HEART_RATE, HeartRatePayload(62.0, ts), ts))
        engine.processEventForTest(CepEvent(Topics.HEART_RATE, HeartRatePayload(63.0, ts + 50L), ts + 50L))
        val pre = engine.featureSnapshotForTest(listOf("selected.baseline.tick.confidence"))
        assertNotNull(pre["selected.baseline.tick.confidence"])

        // Exercise the exact production tick path (featureStore tick + orchestrator.onTick + rules + alert clearing).
        engine.runProductionTickForTest(ts + 2_000L)
        val post = engine.featureSnapshotForTest(
            listOf("selected.baseline.tick.confidence", "selected.trends.tick.direction")
        )
        assertEquals(null, post["selected.baseline.tick.confidence"])
        assertEquals("unknown", post["selected.trends.tick.direction"])
    }

    private suspend fun emitCommonGatingEvents(engine: CepEngine, ts: Long) {
        engine.processEventForTest(CepEvent(Topics.OFFBODY, OffBodyPayload(1), ts))
        engine.processEventForTest(CepEvent(Topics.ACTIVITY, ActivityPayload("STILL"), ts))
        engine.processEventForTest(CepEvent(Topics.SLEEP, SleepPayload(false), ts))
    }

    private fun newEngine(): CepEngine {
        val filesDir = RuntimeEnvironment.getApplication().filesDir
        filesDir.resolve("metric_history").deleteRecursively()
        filesDir.resolve("metric_results").deleteRecursively()
        val config = CepConfig(
            tickHz = 1,
            features = emptyList(),
            rules = emptyList(),
            metrics = testMetrics(),
            ruleEvalIntervalMs = 1000
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return CepEngine(
            appContext = RuntimeEnvironment.getApplication(),
            scope = scope,
            config = config,
            dataRepository = mockk(relaxed = true)
        )
    }

    private fun testMetrics(): List<MetricSpec> {
        return listOf(
            MetricSpec(
                id = "hrv.rmssd_ms",
                source = "hrv.rmssd_ms",
                processor = MetricProcessorType.BASELINE_TREND,
                sourceHookId = "samsung_hrv_rmssd",
                gating = MetricGatingPolicy(
                    onWrist = true,
                    stillOrSleeping = true,
                    freshWithinSec = 10
                ),
                baseline = MetricBaselinePolicy(
                    estimator = "trimmed_mean",
                    windowSec = 604800,
                    minSamples = 20,
                    maxStalenessSec = 43200,
                    confidenceThresholds = MetricConfidenceThresholds(
                        warming = 5,
                        readyLow = 10,
                        medium = 20,
                        high = 40
                    )
                ),
                trend = MetricTrendPolicy(
                    shortWindowSec = 3600,
                    longWindowSec = 86400,
                    enterPercent = 10.0,
                    returnToStableInnerPercent = 7.0
                ),
                selectedOutputs = MetricSelectedOutputMapping(
                    slotsToSelectedKeys = mapOf(
                        "trend_direction" to "selected.trends.hrv.direction",
                        "trend_short_term" to "selected.trends.hrv.short_term",
                        "trend_long_term" to "selected.trends.hrv.long_term",
                        "low_threshold" to "selected.thresholds.hrv.low_rmssd_ms",
                        "low_threshold_warn" to "selected.thresholds.hrv.low_rmssd_ms_warn",
                        "baseline_confidence" to "selected.baseline.hrv.confidence",
                        "baseline_state" to "selected.baseline.hrv.state",
                        "sampling_state" to "selected.baseline.hrv.sampling_state"
                    )
                )
            ),
            MetricSpec(
                id = "hr.resting_bpm",
                source = "heart_rate",
                processor = MetricProcessorType.BASELINE_TREND,
                sourceHookId = null,
                gating = MetricGatingPolicy(
                    onWrist = true,
                    stillOrSleeping = true,
                    freshWithinSec = 5
                ),
                baseline = MetricBaselinePolicy(
                    estimator = "trimmed_mean",
                    windowSec = 86400,
                    minSamples = 20,
                    maxStalenessSec = 7200,
                    confidenceThresholds = MetricConfidenceThresholds(
                        warming = 5,
                        readyLow = 10,
                        medium = 20,
                        high = 40
                    )
                ),
                trend = MetricTrendPolicy(
                    shortWindowSec = 300,
                    longWindowSec = 1800,
                    enterPercent = 6.0,
                    returnToStableInnerPercent = 4.0
                ),
                selectedOutputs = MetricSelectedOutputMapping(
                    slotsToSelectedKeys = mapOf(
                        "trend_direction" to "selected.trends.hr.direction",
                        "trend_short_term" to "selected.trends.hr.short_term",
                        "trend_long_term" to "selected.trends.hr.long_term",
                        "low_threshold" to "selected.thresholds.hr.low_bpm",
                        "low_threshold_warn" to "selected.thresholds.hr.low_bpm_warn",
                        "baseline_confidence" to "selected.baseline.hr.confidence",
                        "baseline_state" to "selected.baseline.hr.state",
                        "sampling_state" to "selected.baseline.hr.sampling_state"
                    )
                )
            )
        )
    }
}
