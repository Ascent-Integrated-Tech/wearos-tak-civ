package com.aitech.weartak_civ.cep.engine.config

import com.aitech.weartak_civ.cep.engine.CepConfig
import com.aitech.weartak_civ.cep.engine.FeatureDef
import com.aitech.weartak_civ.cep.engine.RuleAction
import com.aitech.weartak_civ.cep.engine.RuleCompiler
import com.aitech.weartak_civ.cep.engine.RuleDef
import com.aitech.weartak_civ.cep.metric.MetricBaselinePolicy
import com.aitech.weartak_civ.cep.metric.MetricConfidenceThresholds
import com.aitech.weartak_civ.cep.metric.MetricGatingPolicy
import com.aitech.weartak_civ.cep.metric.MetricProcessorType
import com.aitech.weartak_civ.cep.metric.MetricSelectedOutputMapping
import com.aitech.weartak_civ.cep.metric.MetricSpec
import com.aitech.weartak_civ.cep.metric.MetricTrendPolicy
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

    private fun metric(id: String) = MetricSpec(
        id = id,
        source = "heart_rate",
        processor = MetricProcessorType.BASELINE_TREND,
        gating = MetricGatingPolicy(),
        baseline = MetricBaselinePolicy(
            windowSec = 3600,
            minSamples = 1,
            maxStalenessSec = 3600,
            confidenceThresholds = MetricConfidenceThresholds(5, 10, 20, 40),
        ),
        trend = MetricTrendPolicy(60, 300, 5.0, 3.0),
        selectedOutputs = MetricSelectedOutputMapping(emptyMap()),
    )

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
        val builtIn = CepConfig(1, listOf(feat("features.a")), listOf(rule("r1")), ruleEvalIntervalMs = 1000)
        val managedFeature = FeatureDef.Window(id = "features.a", source = "newsrc", op = "max", windowSec = 30)
        val res = CepConfigMerger.merge(builtIn, overlay(features = listOf(managedFeature)), emptyList(), cadence)
        val survivor = res.config.features.first { it.id == "features.a" } as FeatureDef.Window
        assertEquals("newsrc", survivor.source)
        assertEquals(SourceLayer.MANAGED_CONFIG, res.featureSource["features.a"])
    }

    @Test fun pluginCannotOverrideBuiltIn() {
        val builtIn = CepConfig(1, listOf(feat("features.a")), emptyList(), ruleEvalIntervalMs = 1000)
        val pluginVersion = FeatureDef.Window(id = "features.a", source = "plugin", op = "avg", windowSec = 5)
        val plugin = listOf(CepConfigMerger.PluginContribution(pluginId = "p", features = listOf(pluginVersion)))
        val res = CepConfigMerger.merge(builtIn, managed = null, plugin = plugin, defaultCadence = cadence)
        val survivor = res.config.features.first { it.id == "features.a" } as FeatureDef.Window
        assertEquals("src", survivor.source)
        assertEquals(SourceLayer.BUILT_IN, res.featureSource["features.a"])
    }

    @Test fun clearOnlyAffectsManagedOverlay() {
        val builtIn = CepConfig(1, listOf(feat("features.a")), listOf(rule("r1")), ruleEvalIntervalMs = 1000)
        val managed = overlay(features = listOf(feat("features.b")), rules = emptyList())
        val first = CepConfigMerger.merge(builtIn, managed, emptyList(), cadence)
        assertEquals(2, first.config.features.size)
        // Now clear managed features overlay; built-in re-emerges, managed feature gone.
        val cleared = CepConfigMerger.merge(builtIn, overlay(clearCepFeatures = true), emptyList(), cadence)
        assertEquals(listOf("features.a"), cleared.config.features.map { it.id })
        assertEquals(SourceLayer.BUILT_IN, cleared.featureSource["features.a"])
    }

    @Test fun replaceModeRemovesBuiltInFeaturesFromEffectiveSet() {
        val builtIn = CepConfig(1, listOf(feat("features.a")), emptyList(), ruleEvalIntervalMs = 1000)
        val managed = overlay(featuresMode = "REPLACE", features = emptyList())
        val res = CepConfigMerger.merge(builtIn, managed, emptyList(), cadence)
        assertTrue(res.config.features.isEmpty())
    }

    @Test fun replaceModeRulesDropBuiltInsUnlessListedInOverlay() {
        val builtIn = CepConfig(1, emptyList(), listOf(rule("r_builtin")), ruleEvalIntervalMs = 1000)
        val managed = overlay(
            rulesMode = "REPLACE",
            rules = emptyList(),
            features = listOf(feat("features.a"))
        )
        val res = CepConfigMerger.merge(builtIn, managed, emptyList(), cadence)
        assertTrue(res.config.rules.isEmpty())
    }

    @Test fun disableListsAppliedAfterMerge() {
        val builtIn = CepConfig(1, listOf(feat("features.a")), listOf(rule("r1")), ruleEvalIntervalMs = 1000)
        val managed = overlay(disabledRuleIds = setOf("r1"))
        val res = CepConfigMerger.merge(builtIn, managed, emptyList(), cadence)
        assertTrue(res.config.rules.isEmpty())
        assertNull(res.ruleSource["r1"])
    }

    @Test fun disabledIdNoSurvivorIsSilentNoop() {
        val builtIn = CepConfig(1, listOf(feat("features.a")), emptyList(), ruleEvalIntervalMs = 1000)
        // Targets nothing — must not fail.
        val managed = overlay(disabledRuleIds = setOf("r-nonexistent"))
        val res = CepConfigMerger.merge(builtIn, managed, emptyList(), cadence)
        assertEquals(1, res.config.features.size)
    }

    @Test fun managedOverlayFromCadence() {
        val builtIn = CepConfig(1, emptyList(), emptyList(), ruleEvalIntervalMs = 1000)
        val cadenceOverride = RuleCompiler.EngineCadence(tickHz = 10, ruleEvalIntervalMs = 200)
        val managed = overlay().copy(engineCadence = cadenceOverride)
        val res = CepConfigMerger.merge(builtIn, managed, emptyList(), cadence)
        assertEquals(10, res.config.tickHz)
        assertEquals(200, res.config.ruleEvalIntervalMs)
    }

    @Test fun emptyOverlayLeavesBuiltInsIntact() {
        val builtIn = CepConfig(1, listOf(feat("features.a")), listOf(rule("r1")), ruleEvalIntervalMs = 1000)
        val res = CepConfigMerger.merge(builtIn, managed = null, plugin = emptyList(), defaultCadence = cadence)
        assertEquals(1, res.config.features.size)
        assertEquals(1, res.config.rules.size)
        assertEquals(SourceLayer.BUILT_IN, res.featureSource["features.a"])
        assertEquals(SourceLayer.BUILT_IN, res.ruleSource["r1"])
    }

    @Test fun emptyOverlayPreservesBuiltInMetrics() {
        val builtIn = CepConfig(
            tickHz = 1,
            features = emptyList(),
            rules = emptyList(),
            metrics = listOf(metric("hr.resting_bpm.resting")),
            ruleEvalIntervalMs = 1000,
        )
        val res = CepConfigMerger.merge(
            builtIn = builtIn,
            managed = null,
            plugin = emptyList(),
            defaultCadence = cadence,
        )
        assertEquals(listOf("hr.resting_bpm.resting"), res.config.metrics.map { it.id })
    }

    @Test fun pluginCollidingWithBuiltInProducesBlockedByBuiltinDiagnostic() {
        val builtIn = CepConfig(1, listOf(feat("features.a")), emptyList(), ruleEvalIntervalMs = 1000)
        val pluginVersion = FeatureDef.Window(id = "features.a", source = "plugin", op = "avg", windowSec = 5)
        val plugin = listOf(CepConfigMerger.PluginContribution(pluginId = "com.acme.p", features = listOf(pluginVersion)))
        val res = CepConfigMerger.merge(builtIn, managed = null, plugin = plugin, defaultCadence = cadence)
        assertEquals(1, res.pluginCollisions.size)
        val col = res.pluginCollisions.first()
        assertEquals("com.acme.p", col.pluginId)
        assertEquals("features.a", col.entryId)
        assertEquals(SourceLayer.BUILT_IN, col.blockedBy)
    }

    @Test fun pluginCollidingWithManagedProducesBlockedByMdmDiagnostic() {
        val builtIn = CepConfig(1, emptyList(), emptyList(), ruleEvalIntervalMs = 1000)
        val pluginRule = rule("rule.shared")
        val managedRule = rule("rule.shared")
        val managed = overlay(rules = listOf(managedRule))
        val plugin = listOf(CepConfigMerger.PluginContribution(pluginId = "com.acme.p", rules = listOf(pluginRule)))
        val res = CepConfigMerger.merge(builtIn, managed, plugin, cadence)
        val cols = res.pluginCollisions.filter { it.blockedBy == SourceLayer.MANAGED_CONFIG }
        assertEquals(1, cols.size)
        assertEquals("com.acme.p", cols.first().pluginId)
        assertEquals("rule.shared", cols.first().entryId)
    }

    @Test fun replaceModeManagedShadowingPluginRecordsCollision() {
        val builtIn = CepConfig(1, emptyList(), emptyList(), ruleEvalIntervalMs = 1000)
        val pluginFeat = FeatureDef.Window(id = "features.from_plugin", source = "p", op = "min", windowSec = 5)
        val plugin = listOf(CepConfigMerger.PluginContribution(pluginId = "com.acme.p", features = listOf(pluginFeat)))
        val managed = overlay(featuresMode = "REPLACE", features = emptyList())
        val res = CepConfigMerger.merge(builtIn, managed, plugin, cadence)
        assertEquals(1, res.pluginCollisions.size)
        assertEquals(SourceLayer.MANAGED_CONFIG, res.pluginCollisions.first().blockedBy)
        assertEquals("features.from_plugin", res.pluginCollisions.first().entryId)
        assertTrue(res.config.features.isEmpty())
    }
}
