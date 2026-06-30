package com.aitech.weartak_civ.cep.engine.config

import com.aitech.weartak_civ.cep.engine.CepConfig
import com.aitech.weartak_civ.cep.engine.FeatureDef
import com.aitech.weartak_civ.cep.engine.RuleDef
import com.aitech.weartak_civ.cep.engine.RuleCompiler

/**
 * Pure merger for the WEAR-508 definitional layers.
 *
 * Precedence (per design, MANAGED_CONFIG ≻ BUILT_IN ≻ PLUGIN). PLUGIN is wired but unused
 * by WEAR-508; passing an empty list keeps WEAR-509 wiring straightforward.
 *
 * The registry always retains the parsed built-in [CepConfig] for cold-start and clear/reset;
 * [ParsedManagedOverlay.MODE_REPLACE] removes built-in (and plugin) entries from the **effective**
 * merged candidate for that dimension so validation can surface orphans (e.g. rules referencing
 * removed features). Scoped clears (`clearCepRules` / `clearCepFeatures`) and reset recompose
 * without a REPLACE overlay restore the normal MERGE surface from built-ins. Disable lists apply
 * after merge.
 */
object CepConfigMerger {

    /**
     * Merge layers into a [CepConfig] + side-maps.
     *
     * @param builtIn parsed built-in definitions (registry baseline; not mutated)
     * @param managed parsed managed overlay (null = no overlay)
     * @param plugin parsed plugin definitions for WEAR-509 (empty for WEAR-508-only)
     * @param defaultCadence engine cadence when [managed] is non-null but omits `engine`
     */
    fun merge(
        builtIn: CepConfig,
        managed: ParsedManagedOverlay?,
        plugin: List<PluginContribution> = emptyList(),
        defaultCadence: RuleCompiler.EngineCadence
    ): MergeResult {
        val state = MergeState()
        mergePluginLayer(state, plugin)
        mergeBuiltInLayer(state, builtIn)
        applyManagedLayer(state, managed)
        applyDisableLists(state, managed)

        val finalCadence = managed?.engineCadence ?: when {
            managed == null -> RuleCompiler.EngineCadence(builtIn.tickHz, builtIn.ruleEvalIntervalMs)
            else -> defaultCadence
        }
        return buildMergeResult(state, finalCadence)
    }

    private fun mergePluginLayer(state: MergeState, plugin: List<PluginContribution>) {
        for (p in plugin) {
            for (f in p.features) {
                if (state.featuresById.containsKey(f.id)) {
                    state.collisions += PluginCollision(
                        pluginId = p.pluginId,
                        entryId = f.id,
                        entryKind = PluginCollision.Kind.FEATURE,
                        blockedBy = SourceLayer.PLUGIN,
                    )
                    continue
                }
                state.featuresById[f.id] = f
                state.featureSource[f.id] = SourceLayer.PLUGIN
                state.pluginFeatureOwners[f.id] = p.pluginId
            }
            for (r in p.rules) {
                if (state.rulesById.containsKey(r.id)) {
                    state.collisions += PluginCollision(
                        pluginId = p.pluginId,
                        entryId = r.id,
                        entryKind = PluginCollision.Kind.RULE,
                        blockedBy = SourceLayer.PLUGIN,
                    )
                    continue
                }
                state.rulesById[r.id] = r
                state.ruleSource[r.id] = SourceLayer.PLUGIN
                state.pluginRuleOwners[r.id] = p.pluginId
            }
        }
    }

    private fun mergeBuiltInLayer(state: MergeState, builtIn: CepConfig) {
        for (f in builtIn.features) {
            if (state.featureSource[f.id] == SourceLayer.PLUGIN) {
                state.collisions += PluginCollision(
                    pluginId = state.pluginFeatureOwners[f.id] ?: "",
                    entryId = f.id,
                    entryKind = PluginCollision.Kind.FEATURE,
                    blockedBy = SourceLayer.BUILT_IN,
                )
            }
            state.featuresById[f.id] = f
            state.featureSource[f.id] = SourceLayer.BUILT_IN
        }
        for (r in builtIn.rules) {
            if (state.ruleSource[r.id] == SourceLayer.PLUGIN) {
                state.collisions += PluginCollision(
                    pluginId = state.pluginRuleOwners[r.id] ?: "",
                    entryId = r.id,
                    entryKind = PluginCollision.Kind.RULE,
                    blockedBy = SourceLayer.BUILT_IN,
                )
            }
            state.rulesById[r.id] = r
            state.ruleSource[r.id] = SourceLayer.BUILT_IN
        }
    }

    private fun applyManagedLayer(state: MergeState, managed: ParsedManagedOverlay?) {
        if (managed == null) return
        applyManagedFeatures(state, managed)
        applyManagedRules(state, managed)
    }

    private fun applyManagedFeatures(state: MergeState, managed: ParsedManagedOverlay) {
        if (managed.featuresMode == ParsedManagedOverlay.MODE_REPLACE) {
            removeOverriddenFeatures(state)
        }
        for (feature in managed.features) {
            recordFeatureCollisionIfNeeded(state, feature.id, SourceLayer.MANAGED_CONFIG)
            state.featuresById[feature.id] = feature
            state.featureSource[feature.id] = SourceLayer.MANAGED_CONFIG
        }
    }

    private fun applyManagedRules(state: MergeState, managed: ParsedManagedOverlay) {
        if (managed.rulesMode == ParsedManagedOverlay.MODE_REPLACE) {
            removeOverriddenRules(state)
        }
        for (rule in managed.rules) {
            recordRuleCollisionIfNeeded(state, rule.id, SourceLayer.MANAGED_CONFIG)
            state.rulesById[rule.id] = rule
            state.ruleSource[rule.id] = SourceLayer.MANAGED_CONFIG
        }
    }

    private fun removeOverriddenFeatures(state: MergeState) {
        val toRemove = state.featureSource.filterValues {
            it == SourceLayer.BUILT_IN || it == SourceLayer.PLUGIN
        }.keys.toList()
        for (key in toRemove) {
            recordFeatureCollisionIfNeeded(state, key, SourceLayer.MANAGED_CONFIG)
            state.featureSource.remove(key)
            state.featuresById.remove(key)
        }
    }

    private fun removeOverriddenRules(state: MergeState) {
        val toRemove = state.ruleSource.filterValues {
            it == SourceLayer.BUILT_IN || it == SourceLayer.PLUGIN
        }.keys.toList()
        for (key in toRemove) {
            recordRuleCollisionIfNeeded(state, key, SourceLayer.MANAGED_CONFIG)
            state.ruleSource.remove(key)
            state.rulesById.remove(key)
        }
    }

    private fun recordFeatureCollisionIfNeeded(
        state: MergeState,
        entryId: String,
        blockedBy: SourceLayer,
    ) {
        if (state.featureSource[entryId] == SourceLayer.PLUGIN) {
            state.collisions += PluginCollision(
                pluginId = state.pluginFeatureOwners[entryId] ?: "",
                entryId = entryId,
                entryKind = PluginCollision.Kind.FEATURE,
                blockedBy = blockedBy,
            )
        }
    }

    private fun recordRuleCollisionIfNeeded(
        state: MergeState,
        entryId: String,
        blockedBy: SourceLayer,
    ) {
        if (state.ruleSource[entryId] == SourceLayer.PLUGIN) {
            state.collisions += PluginCollision(
                pluginId = state.pluginRuleOwners[entryId] ?: "",
                entryId = entryId,
                entryKind = PluginCollision.Kind.RULE,
                blockedBy = blockedBy,
            )
        }
    }

    private fun applyDisableLists(state: MergeState, managed: ParsedManagedOverlay?) {
        val disabledFeatureIds = managed?.disabledFeatureIds ?: emptySet()
        val disabledRuleIds = managed?.disabledRuleIds ?: emptySet()
        for (id in disabledFeatureIds) {
            state.featuresById.remove(id)
        }
        for (id in disabledRuleIds) {
            state.rulesById.remove(id)
        }
    }

    private fun buildMergeResult(state: MergeState, finalCadence: RuleCompiler.EngineCadence): MergeResult {
        val mergedConfig = CepConfig(
            tickHz = finalCadence.tickHz,
            features = state.featuresById.values.toList(),
            rules = state.rulesById.values.toList(),
            ruleEvalIntervalMs = finalCadence.ruleEvalIntervalMs,
        )
        return MergeResult(
            config = mergedConfig,
            featureSource = state.featureSource.filterKeys { state.featuresById.containsKey(it) },
            ruleSource = state.ruleSource.filterKeys { state.rulesById.containsKey(it) },
            pluginCollisions = state.collisions.toList(),
        )
    }

    private data class MergeState(
        val featureSource: LinkedHashMap<String, SourceLayer> = LinkedHashMap(),
        val ruleSource: LinkedHashMap<String, SourceLayer> = LinkedHashMap(),
        val pluginFeatureOwners: LinkedHashMap<String, String> = LinkedHashMap(),
        val pluginRuleOwners: LinkedHashMap<String, String> = LinkedHashMap(),
        val collisions: MutableList<PluginCollision> = mutableListOf(),
        val featuresById: LinkedHashMap<String, FeatureDef> = LinkedHashMap(),
        val rulesById: LinkedHashMap<String, RuleDef> = LinkedHashMap(),
    )

    data class MergeResult(
        val config: CepConfig,
        val featureSource: Map<String, SourceLayer>,
        val ruleSource: Map<String, SourceLayer>,
        /** Plugin entries shadowed by a higher-priority layer; emitted as info diagnostics. */
        val pluginCollisions: List<PluginCollision> = emptyList()
    )

    /** Placeholder type used so WEAR-509 can plug into the same merger without changes. */
    data class PluginContribution(
        val pluginId: String,
        val features: List<FeatureDef> = emptyList(),
        val rules: List<RuleDef> = emptyList()
    )
}
