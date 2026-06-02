package com.aitech.weartak_civ.cep.engine

import android.app.Application
import com.aitech.weartak_civ.cep.engine.metrics.HrvRmssdMetricAdapter
import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CepEngineHrvBaselineIntegrationTest {

    private fun newEngineHrvOnly(): CepEngine {
        val app = RuntimeEnvironment.getApplication()
        val repo = DataRepository(app)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val hrv = HrvRmssdMetricAdapter()
        val json = JSONObject(
            "{\"engine\":{\"window_tick_ms\":1000,\"rule_eval_interval_ms\":1000},\"features\":[],\"rules\":[]}"
        )
        val config = RuleCompiler.parse(json)
        return CepEngine(app, scope, config, yearOfBirth = 1990, dataRepository = repo, metricAdaptersOverride = listOf(hrv))
    }

    private fun CepEngine.seedStillOnWrist(t: Long) {
        writeFeatureForTest("activity.state", "STILL", t)
        writeFeatureForTest("sleep.is_sleeping", false, t)
        writeFeatureForTest("offbody.on_wrist", 1.0, t)
    }

    private fun CepEngine.pushHrvQualifiedWindow(base: Long, value: Double) {
        seedStillOnWrist(base)
        writeFeatureForTest("features.hrv.rmssd_ms", value, base)
        runMetricBaselineUpdatesForTest(base)
        seedStillOnWrist(base + 30_000L)
        writeFeatureForTest("features.hrv.rmssd_ms", value, base + 30_000L)
        runMetricBaselineUpdatesForTest(base + 30_000L)
        seedStillOnWrist(base + 60_000L)
        writeFeatureForTest("features.hrv.rmssd_ms", value, base + 60_000L)
        runMetricBaselineUpdatesForTest(base + 60_000L)
    }

    @Test
    fun `hrv baseline trend path through CepEngine covers warmup ready decline off-wrist and transient clears`() {
        val engine = newEngineHrvOnly()
        val t0 = 20_000_000L

        var sawInsufficient = false
        var sawWarming = false
        var sawReadyLow = false
        var sawReadyMedium = false

        repeat(20) { i ->
            val base = t0 + i * 200_000L
            engine.pushHrvQualifiedWindow(base, 35.0)
            val snap = engine.metricFeatureSnapshotForTest("hrv.rmssd_ms")
            val status = snap["baseline.hrv.rmssd_ms.status"] as? String
            when (i) {
                2 -> if (status == BaselineStatus.INSUFFICIENT_DATA.name) sawInsufficient = true
                4 -> if (status == BaselineStatus.WARMING.name) sawWarming = true
                9 -> if (status == BaselineStatus.READY_LOW_CONFIDENCE.name) sawReadyLow = true
                19 -> if (status == BaselineStatus.READY_MEDIUM_CONFIDENCE.name) sawReadyMedium = true
            }
        }

        assertTrue("expected INSUFFICIENT_DATA by 3rd qualified window", sawInsufficient)
        assertTrue("expected WARMING by 5th qualified window", sawWarming)
        assertTrue("expected READY_LOW_CONFIDENCE by 10th qualified window", sawReadyLow)
        assertTrue("expected READY_MEDIUM_CONFIDENCE by 20th qualified window", sawReadyMedium)

        val declineBase = t0 + 10_000_000L
        repeat(3) { j ->
            engine.pushHrvQualifiedWindow(declineBase + j * 200_000L, 22.0)
        }
        val snapDecline = engine.metricFeatureSnapshotForTest("hrv.rmssd_ms")
        assertEquals("DECLINING", snapDecline["selected.trends.hrv.direction"])
        assertNotNull(snapDecline["selected.thresholds.hrv.low_rmssd_ms"])

        val offT = declineBase + 2_000_000L
        engine.writeFeatureForTest("activity.state", "STILL", offT)
        engine.writeFeatureForTest("sleep.is_sleeping", false, offT)
        engine.writeFeatureForTest("offbody.on_wrist", 0.0, offT)
        engine.writeFeatureForTest("features.hrv.rmssd_ms", 22.0, offT)
        engine.runMetricBaselineUpdatesForTest(offT)

        val snapOff = engine.metricFeatureSnapshotForTest("hrv.rmssd_ms")
        assertEquals("UNKNOWN", snapOff["trend.hrv.rmssd_ms.direction"])
        assertEquals("UNKNOWN", snapOff["selected.trends.hrv.direction"])
        assertNull(snapOff["selected.trends.hrv.short_term"])
        assertNull(snapOff["selected.thresholds.hrv.low_rmssd_ms_warn"])
    }
}
