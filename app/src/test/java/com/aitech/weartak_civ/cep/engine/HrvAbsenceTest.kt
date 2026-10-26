package com.aitech.weartak_civ.cep.engine

import android.app.Application
import com.aitech.weartak_civ.R
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HrvAbsenceTest {
    @Test fun missingHrvDoesNotBecomeZeroOrAColdScoreOrTriggerItsCorroborators() {
        val root = RuntimeEnvironment.getApplication().resources.openRawResource(R.raw.cep_rules)
            .bufferedReader().use { JSONObject(it.readText()) }
        val config = RuleCompiler.parse(root)
        val fs = FeatureStore()
        val drop = config.features.filterIsInstance<FeatureDef.Derived>()
            .single { it.id == "features.hrv.rmssd_drop_ratio" }
        val cold = config.features.filterIsInstance<FeatureDef.Derived>()
            .single { it.id == "features.cold.score" }
        DerivedEval.eval(drop, fs)
        assertNull(fs.double("features.hrv.rmssd_drop_ratio"))
        assertNull(fs.double("features.hrv.rmssd_recent_1m"))
        DerivedEval.eval(cold, fs)
        assertEquals(0.0, fs.double(cold.id)!!, 0.0)
        fs.put("settings.physio.alerts_enabled", 1.0)
        fs.put("features.accel_low_motion", true)
        fs.put("features.motion.active", true)
        fs.put("features.hr.above_hrmax", true)
        val rules = root.getJSONArray("rules")
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            if (rule.getString("id") in listOf("hrv_drop_alert", "hr_above_hrmax_recalib_candidate")) {
                assertFalse(rule.getString("id"), JsonLogic.eval(rule.getJSONObject("when"), fs))
            }
        }
        fs.put("features.hrv.rmssd_baseline_24h", 100.0)
        fs.put("features.hrv.rmssd_recent_1m", 70.0)
        DerivedEval.eval(drop, fs)
        assertEquals(0.3, fs.double(drop.id)!!, 0.0)
        DerivedEval.eval(cold, fs)
        assertEquals(3.0, fs.double(cold.id)!!, 0.0)
        fs.put("features.hrv.rmssd_recent_1m", 1.0, System.currentTimeMillis() - 121_000L)
        DerivedEval.eval(drop, fs)
        assertNull(fs.double(drop.id))
        DerivedEval.eval(cold, fs)
        assertEquals(0.0, fs.double(cold.id)!!, 0.0)
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            if (rule.getString("id") in listOf("hrv_drop_alert", "hr_above_hrmax_recalib_candidate")) {
                assertFalse(rule.getString("id"), JsonLogic.eval(rule.getJSONObject("when"), fs))
            }
        }
    }
}
