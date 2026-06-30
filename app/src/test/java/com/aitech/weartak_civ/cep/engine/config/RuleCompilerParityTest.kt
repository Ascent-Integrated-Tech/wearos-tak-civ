package com.aitech.weartak_civ.cep.engine.config

import com.aitech.weartak_civ.cep.engine.FeatureDef
import com.aitech.weartak_civ.cep.engine.RuleCompiler
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * U2 — parser equivalence: identical JSON parsed via the built-in shared entry points and
 * via the overlay parser must produce identical [FeatureDef] / [RuleDef] shapes.
 *
 * The overlay parser's job is to enforce envelope semantics; once it dispatches the
 * `features` and `rules` arrays into the shared RuleCompiler entry points, any divergence
 * would surface as a difference here.
 */
class RuleCompilerParityTest {

    @Test fun featuresArrayProducesIdenticalFeatureDefsViaBothPaths() {
        val featuresArr = """
          [
            {"id":"features.window.a","type":"window","source":"src","op":"min","window_sec":60,"max_age_sec":120},
            {"id":"features.derived.b","type":"derived","expr":{"ref":"features.window.a"}}
          ]
        """.trimIndent()

        val builtInDefs = RuleCompiler.parseFeatureDefs(JSONArray(featuresArr))

        // Wrap as a managed overlay payload so the overlay parser sees the same array.
        val payload = JSONObject().apply {
            put("schemaVersion", 1)
            put("rulesMode", "MERGE"); put("featuresMode", "MERGE")
            put("clearCepRules", false); put("clearCepFeatures", false)
            put("features", JSONArray(featuresArr))
            put("rules", JSONArray())
            put("disabledRuleIds", JSONArray()); put("disabledFeatureIds", JSONArray())
        }.toString()
        val parsed = ManagedConfigOverlayParser.parse(payload)

        assertEquals(builtInDefs.size, parsed.features.size)
        for ((b, o) in builtInDefs.zip(parsed.features)) {
            // Compare structurally — same kotlin class and same id.
            assertEquals(b::class, o::class)
            assertEquals(b.id, o.id)
            when (b) {
                is FeatureDef.Window -> {
                    o as FeatureDef.Window
                    assertEquals(b.source, o.source); assertEquals(b.op, o.op)
                    assertEquals(b.windowSec, o.windowSec); assertEquals(b.maxAgeSec, o.maxAgeSec)
                }
                is FeatureDef.Derived -> {
                    o as FeatureDef.Derived
                    assertEquals(b.expr.toString(), o.expr.toString())
                }
                else -> Unit
            }
        }
    }

    @Test fun rulesArrayProducesIdenticalRuleDefsViaBothPaths() {
        val rulesArr = """
          [
            {
              "id":"r1","description":"d","sustained_for_sec":30,"hold_down_sec":60,
              "when":{"and":[{"gte_key_fresh":["features.x",180.0,5]}]},
              "action":{"type":"alert","severity":"ALERT","message":"hi","isTak":true,"isLocal":true,
                        "monitor":[{"feature":"features.x","exit_below":170.0,"hysteresis":2.0}],
                        "exit_sustain_sec":60,"exit_logic":"AND"}
            }
          ]
        """.trimIndent()

        val builtInDefs = RuleCompiler.parseRuleDefs(JSONArray(rulesArr))
        val payload = JSONObject().apply {
            put("schemaVersion", 1)
            put("rulesMode", "MERGE"); put("featuresMode", "MERGE")
            put("clearCepRules", false); put("clearCepFeatures", false)
            put("features", JSONArray()); put("rules", JSONArray(rulesArr))
            put("disabledRuleIds", JSONArray()); put("disabledFeatureIds", JSONArray())
        }.toString()
        val parsed = ManagedConfigOverlayParser.parse(payload)

        assertEquals(1, builtInDefs.size)
        assertEquals(builtInDefs.size, parsed.rules.size)
        val a = builtInDefs.first()
        val b = parsed.rules.first()
        assertEquals(a.id, b.id)
        assertEquals(a.sustainedForSec, b.sustainedForSec)
        assertEquals(a.holdDownSec, b.holdDownSec)
        assertEquals(a.action.type, b.action.type)
        assertEquals(a.action.severity, b.action.severity)
        assertEquals(a.action.message, b.action.message)
        assertEquals(a.action.exitSustainSec, b.action.exitSustainSec)
        assertEquals(a.action.exitLogic, b.action.exitLogic)
        assertEquals(a.action.monitor.size, b.action.monitor.size)
        assertEquals(a.action.monitor.first().exitBelow, b.action.monitor.first().exitBelow)
    }

    @Test fun engineCadenceParsesIdenticallyViaBothPaths() {
        val engineObj = JSONObject("""{"window_tick_ms":500,"rule_eval_interval_ms":250}""")
        val builtIn = RuleCompiler.parseEngineCadence(engineObj)
        val payload = JSONObject().apply {
            put("schemaVersion", 1)
            put("rulesMode", "MERGE"); put("featuresMode", "MERGE")
            put("clearCepRules", false); put("clearCepFeatures", false)
            put("engine", engineObj)
            put("features", JSONArray()); put("rules", JSONArray())
        }.toString()
        val parsed = ManagedConfigOverlayParser.parse(payload).engineCadence
        assertEquals(builtIn.tickHz, parsed?.tickHz)
        assertEquals(builtIn.ruleEvalIntervalMs, parsed?.ruleEvalIntervalMs)
    }
}
