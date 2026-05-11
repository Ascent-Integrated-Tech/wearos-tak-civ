package com.aitech.weartak_civ.cep.engine.config

import com.aitech.weartak_civ.cep.engine.CepConfig
import com.aitech.weartak_civ.cep.engine.FeatureDef
import com.aitech.weartak_civ.cep.engine.RuleAction
import com.aitech.weartak_civ.cep.engine.RuleCompiler
import com.aitech.weartak_civ.cep.engine.RuleDef
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CepConfigMergerTest {

    private val cadence = RuleCompiler.parseEngineCadence(null)

    private fun feat(id: String) = FeatureDef.Window(id = id, source = "src", op = "min", windowSec = 60)
    private fun rule(id: String): RuleDef {
        val cond = JSONObject().put("ref", "features.a").put("is", true)
        return RuleDef(
            id = id, description = null, condition = cond,
            sustainedForSec = 0, holdDownSec = 0,
            action = RuleAction(type = "alert", severity = "INFO", message = "m")
        )
    }

    private fun overlay(
        rulesMode: String = "MERGE",
        featuresMode: String = "MERGE",
        features: List<FeatureDef> = emptyList(),
        rules: List<RuleDef> = emptyList(),
        clearCepRules: Boolean = false,
        clearCepFeatures: Boolean = false,
        disabledRuleIds: Set<String> = emptySet(),
        disabledFeatureIds: Set<String> = emptySet()
    ) = ParsedManagedOverlay(
        schemaVersion = 1, cepResetEpoch = 0,
        rulesMode = rulesMode, featuresMode = featuresMode,
        clearCepRules = clearCepRules, clearCepFeatures = clearCepFeatures,
        engineCadence = null,
        features = features, rules = rules,
        disabledRuleIds = disabledRuleIds, disabledFeatureIds = disabledFeatureIds,
        tenant = null, issuer = null,
        rawCanonical = "", payloadHash = "",
        sawClearSupersedesRules = false, sawClearSupersedesFeatures = false
    )

    @Test fun managedFeatureOverridesBuiltIn() {
        val builtIn = CepConfig(1, listOf(feat("features.a")), listOf(rule("r1")), 1000)
        val managedFeature = FeatureDef.Window(id = "features.a", source = "newsrc", op = "max", windowSec = 30)
        val res = CepConfigMerger.merge(builtIn, overlay(features = listOf(managedFeature)), emptyList(), cadence)
        val survivor = res.config.features.first { it.id == "features.a" } as FeatureDef.Window
        assertEquals("newsrc", survivor.source)
        assertEquals(SourceLayer.MANAGED_CONFIG, res.featureSource["features.a"])
    }

    @Test fun pluginCannotOverrideBuiltIn() {
        val builtIn = CepConfig(1, listOf(feat("features.a")), emptyList(), 1000)
        val pluginVersion = FeatureDef.Window(id = "features.a", source = "plugin", op = "avg", windowSec = 5)
        val plugin = listOf(CepConfigMerger.PluginContribution(pluginId = "p", features = listOf(pluginVersion)))
        val res = CepConfigMerger.merge(builtIn, managed = null, plugin = plugin, defaultCadence = cadence)
        val survivor = res.config.features.first { it.id == "features.a" } as FeatureDef.Window
        assertEquals("src", survivor.source)
        assertEquals(SourceLayer.BUILT_IN, res.featureSource["features.a"])
    }

    @Test fun clearOnlyAffectsManagedOverlay() {
        val builtIn = CepConfig(1, listOf(feat("features.a")), listOf(rule("r1")), 1000)
        val managed = overlay(features = listOf(feat("features.b")), rules = emptyList())
        val first = CepConfigMerger.merge(builtIn, managed, emptyList(), cadence)
        assertEquals(2, first.config.features.size)
        // Now clear managed features overlay; built-in re-emerges, managed feature gone.
        val cleared = CepConfigMerger.merge(builtIn, overlay(clearCepFeatures = true), emptyList(), cadence)
        assertEquals(listOf("features.a"), cleared.config.features.map { it.id })
        assertEquals(SourceLayer.BUILT_IN, cleared.featureSource["features.a"])
    }

    @Test fun replaceModeRemovesBuiltInFeaturesFromEffectiveSet() {
        val builtIn = CepConfig(1, listOf(feat("features.a")), emptyList(), 1000)
        val managed = overlay(featuresMode = "REPLACE", features = emptyList())
        val res = CepConfigMerger.merge(builtIn, managed, emptyList(), cadence)
        assertTrue(res.config.features.isEmpty())
    }

    @Test fun replaceModeRulesDropBuiltInsUnlessListedInOverlay() {
        val builtIn = CepConfig(1, emptyList(), listOf(rule("r_builtin")), 1000)
        val managed = overlay(
            rulesMode = "REPLACE",
            rules = emptyList(),
            features = listOf(feat("features.a"))
        )
        val res = CepConfigMerger.merge(builtIn, managed, emptyList(), cadence)
        assertTrue(res.config.rules.isEmpty())
    }

    @Test fun disableListsAppliedAfterMerge() {
        val builtIn = CepConfig(1, listOf(feat("features.a")), listOf(rule("r1")), 1000)
        val managed = overlay(disabledRuleIds = setOf("r1"))
        val res = CepConfigMerger.merge(builtIn, managed, emptyList(), cadence)
        assertTrue(res.config.rules.isEmpty())
        assertNull(res.ruleSource["r1"])
    }

    @Test fun disabledIdNoSurvivorIsSilentNoop() {
        val builtIn = CepConfig(1, listOf(feat("features.a")), emptyList(), 1000)
        // Targets nothing — must not fail.
        val managed = overlay(disabledRuleIds = setOf("r-nonexistent"))
        val res = CepConfigMerger.merge(builtIn, managed, emptyList(), cadence)
        assertEquals(1, res.config.features.size)
    }

    @Test fun managedOverlayFromCadence() {
        val builtIn = CepConfig(1, emptyList(), emptyList(), 1000)
        val cadenceOverride = RuleCompiler.EngineCadence(tickHz = 10, ruleEvalIntervalMs = 200)
        val managed = overlay().copy(engineCadence = cadenceOverride)
        val res = CepConfigMerger.merge(builtIn, managed, emptyList(), cadence)
        assertEquals(10, res.config.tickHz)
        assertEquals(200, res.config.ruleEvalIntervalMs)
    }

    @Test fun emptyOverlayLeavesBuiltInsIntact() {
        val builtIn = CepConfig(1, listOf(feat("features.a")), listOf(rule("r1")), 1000)
        val res = CepConfigMerger.merge(builtIn, managed = null, plugin = emptyList(), defaultCadence = cadence)
        assertEquals(1, res.config.features.size)
        assertEquals(1, res.config.rules.size)
        assertEquals(SourceLayer.BUILT_IN, res.featureSource["features.a"])
        assertEquals(SourceLayer.BUILT_IN, res.ruleSource["r1"])
    }
}
