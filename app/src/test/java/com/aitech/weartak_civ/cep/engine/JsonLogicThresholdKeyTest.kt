package com.aitech.weartak_civ.cep.engine

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WEARTAK-227: comparator thresholds may be feature keys. Before the fix a string threshold
 * was read with optDouble(..., -inf), so HIGH RESTING HR fired at any heart rate.
 */
class JsonLogicThresholdKeyTest {

    private fun store(hr: Double?, threshold: Double?) = FeatureStore().apply {
        if (hr != null) put("features.heart_rate", hr)
        if (threshold != null) put("features.hr.high_threshold_bpm", threshold)
    }

    private fun gteFresh() = JSONObject(
        """{"gte_key_fresh":["features.heart_rate","features.hr.high_threshold_bpm",5]}"""
    )

    @Test fun gteKeyFreshWithKeyThresholdDoesNotFireBelowThreshold() {
        assertFalse(JsonLogic.eval(gteFresh(), store(hr = 109.0, threshold = 150.0)))
    }

    @Test fun gteKeyFreshWithKeyThresholdFiresAtOrAboveThreshold() {
        assertTrue(JsonLogic.eval(gteFresh(), store(hr = 150.0, threshold = 150.0)))
        assertTrue(JsonLogic.eval(gteFresh(), store(hr = 160.0, threshold = 150.0)))
    }

    @Test fun gteKeyFreshWithMissingThresholdKeyIsFalse() {
        assertFalse(JsonLogic.eval(gteFresh(), store(hr = 200.0, threshold = null)))
    }

    @Test fun gteKeyWithKeyThreshold() {
        val node = JSONObject("""{"gte_key":["features.heart_rate","features.hr.high_threshold_bpm"]}""")
        assertFalse(JsonLogic.eval(node, store(hr = 109.0, threshold = 150.0)))
        assertTrue(JsonLogic.eval(node, store(hr = 151.0, threshold = 150.0)))
    }

    @Test fun lteKeyFreshWithKeyThreshold() {
        val node = JSONObject(
            """{"lte_key_fresh":["features.heart_rate","features.hr.high_threshold_bpm",5]}"""
        )
        assertTrue(JsonLogic.eval(node, store(hr = 109.0, threshold = 150.0)))
        assertFalse(JsonLogic.eval(node, store(hr = 151.0, threshold = 150.0)))
    }

    @Test fun numericThresholdsStillWork() {
        val s = store(hr = 100.0, threshold = null)
        assertTrue(JsonLogic.eval(JSONObject("""{"gte_key":["features.heart_rate",100.0]}"""), s))
        assertFalse(JsonLogic.eval(JSONObject("""{"gte_key":["features.heart_rate",101.0]}"""), s))
        assertTrue(JsonLogic.eval(JSONObject("""{"lte_key_fresh":["features.heart_rate",100.0,5]}"""), s))
        assertTrue(JsonLogic.eval(JSONObject("""{"gte_key":["features.heart_rate"]}"""), s))
    }
}
