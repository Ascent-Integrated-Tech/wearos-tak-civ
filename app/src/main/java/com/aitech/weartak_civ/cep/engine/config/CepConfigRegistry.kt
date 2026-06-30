package com.aitech.weartak_civ.cep.engine.config

import com.aitech.weartak_civ.cep.engine.CepConfig
import com.aitech.weartak_civ.cep.engine.RuleCompiler

/**
 * Single CEP composition pipeline. Owns parse → merge → validate.
 *
 * The registry is pure: it does NOT touch the engine, persist anything, or perform any
 * runtime swaps. Engine swap orchestration lives on `CepProvider` + `CepEngineHolder`. Tests
 * exercise the registry in isolation.
 *
 * The registry always retains the parsed built-in [CepConfig] for clear/reset and MERGE
 * composition. A managed overlay in `REPLACE` mode may omit built-in definitions from the
 * **effective** merged candidate (see [CepConfigMerger]); there is no public API that mutates
 * the stored built-in baseline.
 */
class CepConfigRegistry(
    private val builtIn: CepConfig,
    private val defaultCadence: RuleCompiler.EngineCadence = RuleCompiler.parseEngineCadence(null),
    private val bounds: CepConfigBounds = CepConfigBounds()
) {

    /**
     * Compose effective config from built-ins ± a managed overlay candidate ± optional
     * plugin contributions. WEAR-508 callers pass an empty plugin list.
     */
    fun compose(
        managed: ParsedManagedOverlay?,
        plugin: List<CepConfigMerger.PluginContribution> = emptyList()
    ): ValidationResult {
        val merge = CepConfigMerger.merge(builtIn, managed, plugin, defaultCadence)
        val infos = mutableListOf<String>()
        if (managed?.sawClearSupersedesRules == true) infos += ReasonCodes.CLEAR_SUPERSEDES_RULES_ARRAY
        if (managed?.sawClearSupersedesFeatures == true) infos += ReasonCodes.CLEAR_SUPERSEDES_FEATURES_ARRAY
        return when (val v = CepConfigValidator.validate(merge, managed, bounds)) {
            is ValidationResult.Ok -> {
                val withInfos = v.resolved.copy(infoDiagnostics = infos)
                ValidationResult.Ok(withInfos)
            }
            is ValidationResult.Err -> v
        }
    }

    /** Compose without any managed overlay — used for cold-start fallback to built-ins. */
    fun composeWithoutManagedOverlay(): ValidationResult = compose(managed = null)

    /** Returns true when `candidate` is identical to the previous authoritative effective hash. */
    fun isNoOpAgainst(prevHash: String?, candidate: Resolved): Boolean {
        if (prevHash.isNullOrEmpty()) return false
        return prevHash == candidate.effectiveHash
    }

    /** Snapshot of the current built-in surface. */
    val builtInConfig: CepConfig get() = builtIn

    /** Cadence used when an overlay omits the `engine` block. */
    val cadence: RuleCompiler.EngineCadence get() = defaultCadence

    /** Validator bounds (exposed so callers can use the same payload-byte cap during ingestion). */
    val configBounds: CepConfigBounds get() = bounds
}
