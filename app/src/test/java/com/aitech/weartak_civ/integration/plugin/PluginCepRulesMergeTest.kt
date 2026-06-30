package com.aitech.weartak_civ.integration.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginCepRulesMergeTest {

    @Test
    fun mergeRegister_stripsAndAppendsBySensorPrefix() {
        val existing = JSONObject()
            .put("schemaVersion", 1)
            .put("pluginId", "com.example.plugin")
            .put("leaseSec", 3600L)
            .put("features", JSONArray())
            .put(
                "rules",
                JSONArray().put(
                    JSONObject().put("id", "com.example.plugin.sensorA.rule1")
                )
            )
        val fragment = JSONObject().put(
            "rules",
            JSONArray().put(
                JSONObject().put("id", "com.example.plugin.sensorA.rule2")
            )
        )
        val merged = JSONObject(
            PluginCepRulesMerge.mergeRegister(
                pluginId = "com.example.plugin",
                sensorKey = "sensorA",
                rulesJson = fragment,
                existingRaw = existing.toString(),
            )
        )
        val rules = merged.getJSONArray("rules")
        assertEquals(1, rules.length())
        assertEquals("com.example.plugin.sensorA.rule2", rules.getJSONObject(0).getString("id"))
    }

    @Test
    fun mergeUnregister_removesSensorScopedRules() {
        val existing = JSONObject()
            .put("schemaVersion", 1)
            .put("pluginId", "com.example.plugin")
            .put("leaseSec", 3600L)
            .put("features", JSONArray())
            .put(
                "rules",
                JSONArray()
                    .put(JSONObject().put("id", "com.example.plugin.sensorA.r1"))
                    .put(JSONObject().put("id", "com.example.plugin.sensorB.r1"))
            )
        val merged = JSONObject(
            PluginCepRulesMerge.mergeUnregister(
                pluginId = "com.example.plugin",
                sensorKey = "sensorA",
                existingRaw = existing.toString(),
            )
        )
        val rules = merged.getJSONArray("rules")
        assertEquals(1, rules.length())
        assertTrue(rules.getJSONObject(0).getString("id").contains("sensorB"))
    }

    @Test
    fun rulePrefix_matchesMergeConvention() {
        assertEquals("a.b.", PluginCepRulesMerge.rulePrefix("a", "b"))
    }
}
