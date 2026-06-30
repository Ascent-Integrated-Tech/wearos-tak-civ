package com.aitech.weartak_civ.cep.engine.config

import com.aitech.weartak_civ.cep.engine.CepConfig
import com.aitech.weartak_civ.cep.engine.FeatureDef
import com.aitech.weartak_civ.cep.engine.RuleAction
import com.aitech.weartak_civ.cep.engine.RuleCompiler
import com.aitech.weartak_civ.cep.engine.RuleDef
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CepConfigRegistryTest {

    private fun feat(id: String) = FeatureDef.Window(id = id, source = "src", op = "min", windowSec = 60)
    private fun rule(id: String, ref: String): RuleDef {
        val cond = JSONObject().put("ref", ref).put("is", true)
        return RuleDef(
            id = id, description = null, condition = cond,
            sustainedForSec = 0, holdDownSec = 0,
            action = RuleAction(type = "alert", severity = "INFO", message = "m")
        )
    }

    private fun registry(features: List<FeatureDef>, rules: List<RuleDef>): CepConfigRegistry {
        val builtIn = CepConfig(1, features, rules, 1000)
        return CepConfigRegistry(builtIn = builtIn, defaultCadence = RuleCompiler.parseEngineCadence(null))
    }

    private fun mkOverlay(rawJson: String): ParsedManagedOverlay = ManagedConfigOverlayParser.parse(rawJson)

    private fun overlayWithFeature(featureId: String): ParsedManagedOverlay {
        val payload = """
          {"schemaVersion":1,"rulesMode":"MERGE","featuresMode":"MERGE",
           "clearCepRules":false,"clearCepFeatures":false,
           "features":[{"id":"$featureId","type":"window","source":"src","window_sec":60,"op":"min"}],
           "rules":[]}
        """.trimIndent()
        return mkOverlay(payload)
    }

    @Test fun composeBuiltinsOnlyValid() {
        val reg = registry(listOf(feat("features.a")), emptyList())
        val r = reg.composeWithoutManagedOverlay()
        assertTrue("expected Ok got $r", r is ValidationResult.Ok)
    }

    @Test fun composeWithCleanOverlayValid() {
        val reg = registry(emptyList(), emptyList())
        val r = reg.compose(overlayWithFeature("features.x"))
        assertTrue(r is ValidationResult.Ok)
        val resolved = (r as ValidationResult.Ok).resolved
        assertEquals(1, resolved.features.size)
        assertEquals(SourceLayer.MANAGED_CONFIG, resolved.featureSource["features.x"])
    }

    @Test fun noOpRecomposeDetectedByHash() {
        val reg = registry(emptyList(), emptyList())
        val r1 = reg.compose(overlayWithFeature("features.x")) as ValidationResult.Ok
        val r2 = reg.compose(overlayWithFeature("features.x")) as ValidationResult.Ok
        assertEquals(r1.resolved.effectiveHash, r2.resolved.effectiveHash)
        assertTrue(reg.isNoOpAgainst(r1.resolved.effectiveHash, r2.resolved))
    }

    @Test fun changedOverlayChangesEffectiveHash() {
        val reg = registry(emptyList(), emptyList())
        val r1 = reg.compose(overlayWithFeature("features.x")) as ValidationResult.Ok
        val r2 = reg.compose(overlayWithFeature("features.y")) as ValidationResult.Ok
        assertNotEquals(r1.resolved.effectiveHash, r2.resolved.effectiveHash)
    }

    @Test fun clearOverlayBringsBackBuiltins() {
        val reg = registry(listOf(feat("features.a")), emptyList())
        val r1 = reg.compose(overlayWithFeature("features.b"))
        val resolved1 = (r1 as ValidationResult.Ok).resolved
        assertEquals(2, resolved1.features.size)

        val cleared = mkOverlay("""
          {"schemaVersion":1,"rulesMode":"MERGE","featuresMode":"MERGE",
           "clearCepRules":false,"clearCepFeatures":true,
           "features":[],"rules":[]}
        """.trimIndent())
        val r2 = reg.compose(cleared) as ValidationResult.Ok
        // Built-in re-emerges; managed overlay feature is gone.
        assertEquals(listOf("features.a"), r2.resolved.features.map { it.id })
        assertEquals(SourceLayer.BUILT_IN, r2.resolved.featureSource["features.a"])
    }

    @Test fun infoDiagnosticEmittedForClearSupersedesArray() {
        val reg = registry(emptyList(), emptyList())
        val payload = """
          {"schemaVersion":1,"rulesMode":"MERGE","featuresMode":"MERGE",
           "clearCepRules":false,"clearCepFeatures":true,
           "features":[{"id":"features.x","type":"window","source":"s","window_sec":1,"op":"min"}],
           "rules":[]}
        """.trimIndent()
        val r = reg.compose(mkOverlay(payload)) as ValidationResult.Ok
        assertTrue(ReasonCodes.CLEAR_SUPERSEDES_FEATURES_ARRAY in r.resolved.infoDiagnostics)
    }

    @Test fun overlayWithoutEngineInheritsBuiltInCadenceFromRegistry() {
        val builtIn = CepConfig(
            tickHz = 5,
            features = listOf(feat("features.a")),
            rules = emptyList(),
            ruleEvalIntervalMs = 400
        )
        val reg = CepConfigRegistry(
            builtIn = builtIn,
            defaultCadence = RuleCompiler.EngineCadence(builtIn.tickHz, builtIn.ruleEvalIntervalMs)
        )
        val payload = """
          {"schemaVersion":1,"rulesMode":"MERGE","featuresMode":"MERGE",
           "clearCepRules":false,"clearCepFeatures":false,
           "features":[],"rules":[]}
        """.trimIndent()
        val overlay = ManagedConfigOverlayParser.parse(payload, reg.configBounds)
        val r = reg.compose(overlay) as ValidationResult.Ok
        assertEquals(5, r.resolved.engineConfig.tickHz)
        assertEquals(400, r.resolved.engineConfig.ruleEvalIntervalMs)
    }

    @Test fun pluginContributionIncludedWhenNoHigherConflict() {
        val reg = registry(emptyList(), emptyList())
        val plugin = listOf(
            CepConfigMerger.PluginContribution(
                pluginId = "com.acme.plugin",
                features = listOf(feat("com.acme.plugin.features.a"))
            )
        )
        val r = reg.compose(managed = null, plugin = plugin) as ValidationResult.Ok
        assertEquals(1, r.resolved.features.size)
        assertEquals(SourceLayer.PLUGIN, r.resolved.featureSource["com.acme.plugin.features.a"])
    }

    @Test fun managedAndBuiltInShadowPluginDeterministically() {
        val builtIn = CepConfig(1, listOf(feat("features.a")), emptyList(), 1000)
        val reg = CepConfigRegistry(builtIn, RuleCompiler.parseEngineCadence(null))
        val plugin = listOf(
            CepConfigMerger.PluginContribution(
                pluginId = "com.acme.plugin",
                features = listOf(feat("features.a"), feat("com.acme.plugin.features.a"))
            )
        )
        val managed = overlayWithFeature("features.a")
        val r = reg.compose(managed = managed, plugin = plugin) as ValidationResult.Ok
        assertEquals(SourceLayer.MANAGED_CONFIG, r.resolved.featureSource["features.a"])
        assertTrue(r.resolved.features.any { it.id == "com.acme.plugin.features.a" })
    }

    @Test fun isNoOpAgainstReturnsFalseForEmptyPrev() {
        val reg = registry(emptyList(), emptyList())
        val r = reg.compose(overlayWithFeature("features.x")) as ValidationResult.Ok
        assertFalse(reg.isNoOpAgainst(prevHash = "", candidate = r.resolved))
        assertFalse(reg.isNoOpAgainst(prevHash = null, candidate = r.resolved))
    }
}
