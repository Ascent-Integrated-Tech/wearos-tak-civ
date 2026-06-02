package com.aitech.weartak_civ.cep.metric

import com.aitech.weartak_civ.cep.engine.FeatureStore
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.Topics
import com.aitech.weartak_civ.cep.model.payloads.ActivityPayload
import com.aitech.weartak_civ.cep.model.payloads.HeartRatePayload
import com.aitech.weartak_civ.cep.model.payloads.OffBodyPayload
import com.aitech.weartak_civ.cep.model.payloads.SleepPayload
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MetricHistoryRecoveryIntegrationTest {

    @Test
    fun restart_reconstructsThirtyDayTrendFromPersistedRollups() = runBlocking {
        val metricSpec = MetricSpec(
            id = "metric.history.recovery",
            source = "heart_rate",
            processor = MetricProcessorType.BASELINE_TREND,
            sourceHookId = null,
            gating = MetricGatingPolicy(onWrist = true, stillOrSleeping = false, freshWithinSec = null),
            baseline = MetricBaselinePolicy(
                estimator = "trimmed_mean",
                windowSec = 30 * 24 * 3600,
                minSamples = 20,
                maxStalenessSec = 365 * 24 * 3600,
                confidenceThresholds = MetricConfidenceThresholds(5, 10, 20, 40)
            ),
            trend = MetricTrendPolicy(
                shortWindowSec = 3600,
                longWindowSec = 24 * 3600,
                enterPercent = 5.0,
                returnToStableInnerPercent = 3.0
            ),
            selectedOutputs = MetricSelectedOutputMapping(
                slotsToSelectedKeys = mapOf(
                    "trend_direction" to "selected.trends.recovery.direction",
                    "trend_short_term" to "selected.trends.recovery.short",
                    "trend_long_term" to "selected.trends.recovery.long",
                    "trend_deviation" to "selected.trends.recovery.deviation",
                    "trend_percent_deviation" to "selected.trends.recovery.percent_deviation",
                    "low_threshold" to "selected.thresholds.recovery.low",
                    "low_threshold_warn" to "selected.thresholds.recovery.low_warn",
                    "baseline_confidence" to "selected.baseline.recovery.confidence"
                )
            )
        )

        val dir = Files.createTempDirectory("metric_history_restart").toFile()
        val sharedStore = FileBackedMetricHistoryStore(dir)
        val fs1 = FeatureStore()
        val orchestrator1 = MetricRuntimeOrchestrator(
            dataRepository = null,
            metricSpecs = listOf(metricSpec),
            appContext = null,
            historyStore = sharedStore
        )
        orchestrator1.initialize()

        var ts = 0L
        repeat(35) { day ->
            emitGating(fs1, ts)
            repeat(4) { hourChunk ->
                val value = 58.0 + day * 0.2 + hourChunk * 0.1
                val eventTs = ts + hourChunk * FileBackedMetricHistoryStore.HOUR_MS
                orchestrator1.onEvent(
                    CepEvent(
                        topic = Topics.HEART_RATE,
                        payload = HeartRatePayload(value, eventTs),
                        ts = eventTs
                    ),
                    fs1
                )
            }
            ts += FileBackedMetricHistoryStore.DAY_MS
        }

        val fs2 = FeatureStore()
        val orchestrator2 = MetricRuntimeOrchestrator(
            dataRepository = null,
            metricSpecs = listOf(metricSpec),
            appContext = null,
            historyStore = FileBackedMetricHistoryStore(dir)
        )
        orchestrator2.initialize()
        emitGating(fs2, ts)
        orchestrator2.onTick(ts, fs2)

        val confidence = fs2.latestAsDouble("selected.baseline.recovery.confidence")
        assertNotNull(confidence)
        assertTrue(confidence!! in 0.0..1.0)
        assertNotNull(fs2.any("selected.trends.recovery.direction"))
        assertNotNull(fs2.any("selected.trends.recovery.short"))
        assertNotNull(fs2.any("selected.trends.recovery.long"))
    }

    private fun emitGating(fs: FeatureStore, ts: Long) {
        fs.put("offbody.on_wrist", 1.0, ts)
        fs.put("activity.state", "STILL", ts)
        fs.put("sleep.is_sleeping", false, ts)
    }
}
