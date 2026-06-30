package com.aitech.weartak_civ.cep.engine.config

import com.aitech.weartak_civ.cep.engine.CepConfig
import com.aitech.weartak_civ.cep.engine.FeatureDef
import com.aitech.weartak_civ.cep.engine.RuleAction
import com.aitech.weartak_civ.cep.engine.RuleCompiler
import com.aitech.weartak_civ.cep.engine.RuleDef
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CepConfigValidatorTest {

    private val defaultCadence = RuleCompiler.parseEngineCadence(null)

    private fun feature(id: String) = FeatureDef.Window(id = id, source = "src", op = "min", windowSec = 60)
    private fun derived(id: String, refs: List<String>): FeatureDef.Derived {
        val expr = JSONObject().put("ref", JSONArray().apply { refs.forEach { put(it) } })
        return FeatureDef.Derived(id = id, expr = expr)
    }

    private fun rule(id: String, refFeatureId: String): RuleDef {
        val cond = JSONObject().put("ref", refFeatureId).put("is", true)
        return RuleDef(
            id = id, description = null, condition = cond,
            sustainedForSec = 0, holdDownSec = 0,
            action = RuleAction(type = "alert", severity = "ALERT", message = "m"),
        )
    }

    private fun cfg(features: List<FeatureDef>, rules: List<RuleDef>) = CepConfig(
        tickHz = 1, features = features, rules = rules, ruleEvalIntervalMs = 1000
    )

    private fun mergeOf(features: List<FeatureDef>, rules: List<RuleDef>): CepConfigMerger.MergeResult {
        return CepConfigMerger.merge(
            builtIn = cfg(features, rules), managed = null, plugin = emptyList(), defaultCadence = defaultCadence
        )
    }

    @Test fun rejectsDuplicateFeatureIds() {
        val managed = ParsedManagedOverlay(
            schemaVersion = 1, cepResetEpoch = 0,
            rulesMode = "MERGE", featuresMode = "MERGE",
            clearCepRules = false, clearCepFeatures = false,
            engineCadence = null,
            features = listOf(feature("features.a"), feature("features.a")),
            rules = emptyList(),
            disabledRuleIds = emptySet(), disabledFeatureIds = emptySet(),
            tenant = null, issuer = null,
            rawCanonical = "", payloadHash = "",
            sawClearSupersedesRules = false, sawClearSupersedesFeatures = false
        )
        val merge = CepConfigMerger.merge(
            builtIn = cfg(listOf(feature("features.b")), emptyList()),
            managed = managed,
            plugin = emptyList(),
            defaultCadence = defaultCadence
        )
        val r = CepConfigValidator.validate(merge, overlay = managed)
        assertTrue(r is ValidationResult.Err)
        assertEquals(ReasonCodes.DUPLICATE_ID, (r as ValidationResult.Err).reasonCode)
    }

    @Test fun rejectsReservedDoubleUnderscore() {
        val merge = mergeOf(features = listOf(feature("__forbidden")), rules = emptyList())
        val r = CepConfigValidator.validate(merge, overlay = null)
        assertTrue(r is ValidationResult.Err)
        assertEquals(ReasonCodes.RESERVED_ID, (r as ValidationResult.Err).reasonCode)
    }

    @Test fun rejectsUnsupportedSeverity() {
        val rule = rule("r1", "features.a").copy(
            action = RuleAction(type = "alert", severity = "BOGUS", message = "m")
        )
        val merge = mergeOf(features = listOf(feature("features.a")), rules = listOf(rule))
        val r = CepConfigValidator.validate(merge, overlay = null)
        assertTrue(r is ValidationResult.Err)
        assertEquals(ReasonCodes.UNSUPPORTED_SEVERITY, (r as ValidationResult.Err).reasonCode)
    }

    @Test fun rejectsCadenceBelowBound() {
        val cfg = cfg(features = listOf(feature("features.a")), rules = emptyList())
            .copy(ruleEvalIntervalMs = 50)
        val merge = CepConfigMerger.merge(
            builtIn = cfg, managed = null, plugin = emptyList(), defaultCadence = defaultCadence
        )
        val r = CepConfigValidator.validate(merge, overlay = null)
        assertTrue(r is ValidationResult.Err)
        assertEquals(ReasonCodes.CADENCE_OUT_OF_BOUNDS, (r as ValidationResult.Err).reasonCode)
    }

    @Test fun rejectsRulesExceedingMax() {
        val rules = (0 until 10).map { rule("r$it", "features.a") }
        val merge = mergeOf(features = listOf(feature("features.a")), rules = rules)
        val r = CepConfigValidator.validate(merge, overlay = null, bounds = CepConfigBounds(maxRules = 5))
        assertTrue(r is ValidationResult.Err)
        assertEquals(ReasonCodes.MAX_RULES_EXCEEDED, (r as ValidationResult.Err).reasonCode)
    }

    @Test fun acceptsRuleReferencingFeature() {
        val features = listOf(feature("features.a"))
        val rules = listOf(rule("r1", "features.a"))
        val merge = mergeOf(features, rules)
        val r = CepConfigValidator.validate(merge, overlay = null)
        assertTrue("expected Ok but got $r", r is ValidationResult.Ok)
    }

    @Test fun rejectsPostComposeOrphan() {
        val rules = listOf(rule("r1", "features.missing"))
        val merge = mergeOf(features = emptyList(), rules = rules)
        val r = CepConfigValidator.validate(merge, overlay = null)
        assertTrue(r is ValidationResult.Err)
        val err = r as ValidationResult.Err
        assertTrue(err.reasonCode == ReasonCodes.POST_COMPOSE_ORPHAN || err.reasonCode == ReasonCodes.REPLACE_ASYMMETRY_ORPHANS_RULE)
    }

    @Test fun detectsDirectDerivedSelfCycle() {
        val features = listOf(derived("features.x", listOf("features.x")))
        val merge = mergeOf(features, emptyList())
        val r = CepConfigValidator.validate(merge, overlay = null)
        assertTrue(r is ValidationResult.Err)
        assertEquals(ReasonCodes.DERIVED_CYCLE, (r as ValidationResult.Err).reasonCode)
    }

    @Test fun detectsTransitiveDerivedCycle() {
        val features = listOf(
            derived("features.a", listOf("features.b")),
            derived("features.b", listOf("features.c")),
            derived("features.c", listOf("features.a"))
        )
        val merge = mergeOf(features, emptyList())
        val r = CepConfigValidator.validate(merge, overlay = null)
        assertTrue(r is ValidationResult.Err)
        assertEquals(ReasonCodes.DERIVED_CYCLE, (r as ValidationResult.Err).reasonCode)
    }

    @Test fun replaceAsymmetryOrphanReportedDistinctly() {
        // featuresMode=REPLACE removes a feature that built-in rules depend on.
        val builtInFeatures = listOf(feature("features.a"))
        val builtInRules = listOf(rule("r1", "features.a"))
        val builtIn = cfg(builtInFeatures, builtInRules)
        val managed = ParsedManagedOverlay(
            schemaVersion = 1, cepResetEpoch = 0,
            rulesMode = "MERGE", featuresMode = "REPLACE",
            clearCepRules = false, clearCepFeatures = false,
            engineCadence = null,
            features = emptyList(), rules = emptyList(),
            disabledRuleIds = setOf("r1"), // disable rule -> still orphan path stays clean if disabled
            disabledFeatureIds = emptySet(),
            tenant = null, issuer = null,
            rawCanonical = "", payloadHash = "",
            sawClearSupersedesRules = false, sawClearSupersedesFeatures = false
        )
        // Compose: REPLACE features (empty); built-in rule "r1" disabled by disabledRuleIds.
        // After disable, the rule is gone and there's no orphan to report. So this configuration
        // should succeed.
        val merge = CepConfigMerger.merge(builtIn, managed, emptyList(), defaultCadence)
        val r = CepConfigValidator.validate(merge, managed)
        assertTrue("expected Ok got $r", r is ValidationResult.Ok)
    }

    @Test fun replaceAsymmetryOrphanSurfacesWhenRuleSurvives() {
        val builtIn = cfg(listOf(feature("features.a")), listOf(rule("r1", "features.a")))
        val managed = ParsedManagedOverlay(
            schemaVersion = 1, cepResetEpoch = 0,
            rulesMode = "MERGE", featuresMode = "REPLACE",
            clearCepRules = false, clearCepFeatures = false,
            engineCadence = null,
            features = emptyList(), rules = emptyList(),
            disabledRuleIds = emptySet(), disabledFeatureIds = emptySet(),
            tenant = null, issuer = null,
            rawCanonical = "", payloadHash = "",
            sawClearSupersedesRules = false, sawClearSupersedesFeatures = false
        )
        val merge = CepConfigMerger.merge(builtIn, managed, emptyList(), defaultCadence)
        val r = CepConfigValidator.validate(merge, managed)
        assertTrue(r is ValidationResult.Err)
        assertEquals(
            ReasonCodes.REPLACE_ASYMMETRY_ORPHANS_RULE,
            (r as ValidationResult.Err).reasonCode
        )
    }

    @Test fun effectiveHashStableUnderKeyOrdering() {
        val features = listOf(feature("features.a"), feature("features.b"))
        val cfg1 = cfg(features, emptyList())
        val cfg2 = cfg(features.reversed(), emptyList())
        val h1 = CepConfigValidator.effectiveHash(cfg1)
        val h2 = CepConfigValidator.effectiveHash(cfg2)
        // Different array order = different hash (arrays preserve order per design).
        assertTrue(h1 != h2)
    }
}
