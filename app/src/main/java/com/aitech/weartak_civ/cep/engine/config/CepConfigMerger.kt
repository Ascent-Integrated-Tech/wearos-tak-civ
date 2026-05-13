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
        val featureSource = LinkedHashMap<String, SourceLayer>()
        val ruleSource = LinkedHashMap<String, SourceLayer>()
        // Track plugin-entry ownership so we can attribute a shadow event back to the plugin.
        val pluginFeatureOwners = LinkedHashMap<String, String>() // entryId -> pluginId
        val pluginRuleOwners = LinkedHashMap<String, String>()
        val collisions = mutableListOf<PluginCollision>()

        // Stage 1: PLUGIN (lowest definition layer). Plugin entries colliding with higher
        // layers are dropped here in favor of higher layers (MANAGED_CONFIG / BUILT_IN);
        // those shadows are surfaced as `plugin_blocked_by_*` diagnostics below.
        val featuresById = LinkedHashMap<String, FeatureDef>()
        val rulesById = LinkedHashMap<String, RuleDef>()
        for (p in plugin) {
            for (f in p.features) {
                if (featuresById.containsKey(f.id)) {
                    // Two plugins claim the same feature id: keep the first registrant and
                    // mark the second as blocked by a higher (i.e. earlier-registered) plugin.
                    collisions += PluginCollision(
                        pluginId = p.pluginId,
                        entryId = f.id,
                        entryKind = PluginCollision.Kind.FEATURE,
                        blockedBy = SourceLayer.PLUGIN
                    )
                    continue
                }
                featuresById[f.id] = f
                featureSource[f.id] = SourceLayer.PLUGIN
                pluginFeatureOwners[f.id] = p.pluginId
            }
            for (r in p.rules) {
                if (rulesById.containsKey(r.id)) {
                    collisions += PluginCollision(
                        pluginId = p.pluginId,
                        entryId = r.id,
                        entryKind = PluginCollision.Kind.RULE,
                        blockedBy = SourceLayer.PLUGIN
                    )
                    continue
                }
                rulesById[r.id] = r
                ruleSource[r.id] = SourceLayer.PLUGIN
                pluginRuleOwners[r.id] = p.pluginId
            }
        }

        // Stage 2: BUILT_IN. Always wins over PLUGIN; built-ins are the warranted defaults.
        for (f in builtIn.features) {
            if (featureSource[f.id] == SourceLayer.PLUGIN) {
                collisions += PluginCollision(
                    pluginId = pluginFeatureOwners[f.id] ?: "",
                    entryId = f.id,
                    entryKind = PluginCollision.Kind.FEATURE,
                    blockedBy = SourceLayer.BUILT_IN
                )
            }
            featuresById[f.id] = f
            featureSource[f.id] = SourceLayer.BUILT_IN
        }
        for (r in builtIn.rules) {
            if (ruleSource[r.id] == SourceLayer.PLUGIN) {
                collisions += PluginCollision(
                    pluginId = pluginRuleOwners[r.id] ?: "",
                    entryId = r.id,
                    entryKind = PluginCollision.Kind.RULE,
                    blockedBy = SourceLayer.BUILT_IN
                )
            }
            rulesById[r.id] = r
            ruleSource[r.id] = SourceLayer.BUILT_IN
        }

        // Stage 3: MANAGED_CONFIG. Highest precedence.
        if (managed != null) {
            // Features
            when (managed.featuresMode) {
                ParsedManagedOverlay.MODE_REPLACE -> {
                    val toRemove = featureSource.filterValues {
                        it == SourceLayer.BUILT_IN || it == SourceLayer.PLUGIN
                    }.keys.toList()
                    for (k in toRemove) {
                        if (featureSource[k] == SourceLayer.PLUGIN) {
                            collisions += PluginCollision(
                                pluginId = pluginFeatureOwners[k] ?: "",
                                entryId = k,
                                entryKind = PluginCollision.Kind.FEATURE,
                                blockedBy = SourceLayer.MANAGED_CONFIG
                            )
                        }
                        featureSource.remove(k)
                        featuresById.remove(k)
                    }
                }
                else -> Unit
            }
            for (f in managed.features) {
                if (featureSource[f.id] == SourceLayer.PLUGIN) {
                    collisions += PluginCollision(
                        pluginId = pluginFeatureOwners[f.id] ?: "",
                        entryId = f.id,
                        entryKind = PluginCollision.Kind.FEATURE,
                        blockedBy = SourceLayer.MANAGED_CONFIG
                    )
                }
                featuresById[f.id] = f
                featureSource[f.id] = SourceLayer.MANAGED_CONFIG
            }

            // Rules
            when (managed.rulesMode) {
                ParsedManagedOverlay.MODE_REPLACE -> {
                    val toRemove = ruleSource.filterValues {
                        it == SourceLayer.BUILT_IN || it == SourceLayer.PLUGIN
                    }.keys.toList()
                    for (k in toRemove) {
                        if (ruleSource[k] == SourceLayer.PLUGIN) {
                            collisions += PluginCollision(
                                pluginId = pluginRuleOwners[k] ?: "",
                                entryId = k,
                                entryKind = PluginCollision.Kind.RULE,
                                blockedBy = SourceLayer.MANAGED_CONFIG
                            )
                        }
                        ruleSource.remove(k)
                        rulesById.remove(k)
                    }
                }
                else -> Unit
            }
            for (r in managed.rules) {
                if (ruleSource[r.id] == SourceLayer.PLUGIN) {
                    collisions += PluginCollision(
                        pluginId = pluginRuleOwners[r.id] ?: "",
                        entryId = r.id,
                        entryKind = PluginCollision.Kind.RULE,
                        blockedBy = SourceLayer.MANAGED_CONFIG
                    )
                }
                rulesById[r.id] = r
                ruleSource[r.id] = SourceLayer.MANAGED_CONFIG
            }
        }

        // Stage 4: disable lists apply after merge. Disabled IDs that target nothing are silent.
        val disabledFeatureIds = managed?.disabledFeatureIds ?: emptySet()
        val disabledRuleIds = managed?.disabledRuleIds ?: emptySet()
        for (id in disabledFeatureIds) {
            featuresById.remove(id)
        }
        for (id in disabledRuleIds) {
            rulesById.remove(id)
        }

        val finalCadence = when {
            managed?.engineCadence != null -> managed.engineCadence!!
            managed == null -> RuleCompiler.EngineCadence(builtIn.tickHz, builtIn.ruleEvalIntervalMs)
            else -> defaultCadence
        }
        val mergedConfig = CepConfig(
            tickHz = finalCadence.tickHz,
            features = featuresById.values.toList(),
            rules = rulesById.values.toList(),
            ruleEvalIntervalMs = finalCadence.ruleEvalIntervalMs
        )

        return MergeResult(
            config = mergedConfig,
            featureSource = featureSource.filterKeys { featuresById.containsKey(it) },
            ruleSource = ruleSource.filterKeys { rulesById.containsKey(it) },
            pluginCollisions = collisions.toList()
        )
    }

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
