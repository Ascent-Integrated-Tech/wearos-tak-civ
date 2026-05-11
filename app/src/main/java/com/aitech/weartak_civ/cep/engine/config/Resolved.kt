package com.aitech.weartak_civ.cep.engine.config

import com.aitech.weartak_civ.cep.engine.CepConfig
import com.aitech.weartak_civ.cep.engine.FeatureDef
import com.aitech.weartak_civ.cep.engine.RuleDef

/**
 * SourceLayer attribution for registry side-maps and diagnostics.
 *
 * Per WEAR-508 design, source attribution lives in registry side-maps (this enum + the
 * source maps on [Resolved]) — `RuleDef`/`FeatureDef` do not carry a `source` field in v1.
 */
enum class SourceLayer { BUILT_IN, MANAGED_CONFIG, PLUGIN, RUNTIME, BOOTSTRAP }

/**
 * Result of merge + validate. `engineConfig` is the immutable definitional config used to
 * build a new `CepEngine`. The side-maps record per-id provenance for diagnostics and
 * higher-layer-collision rejection.
 */
data class Resolved(
    val engineConfig: CepConfig,
    val featureSource: Map<String, SourceLayer>,
    val ruleSource: Map<String, SourceLayer>,
    /** Canonical hash of the resolved definitional surface (engine cadence + features + rules). */
    val effectiveHash: String,
    /**
     * Compositional diagnostics emitted while resolving (eg. `clear_supersedes_rules_array`,
     * `noop_recompose`). Validation-failure rejections are emitted via
     * [com.aitech.weartak_civ.cep.engine.config.DiagnosticRecorder] instead.
     */
    val infoDiagnostics: List<String> = emptyList()
) {
    val features: List<FeatureDef> get() = engineConfig.features
    val rules: List<RuleDef> get() = engineConfig.rules
}

/**
 * Result of overlay parse. Mode strings are normalized to "MERGE"/"REPLACE" and unknown
 * values are surfaced as validation errors before reaching the merger.
 */
data class ParsedManagedOverlay(
    val schemaVersion: Int,
    val cepResetEpoch: Long,
    val rulesMode: String,
    val featuresMode: String,
    val clearCepRules: Boolean,
    val clearCepFeatures: Boolean,
    val engineCadence: com.aitech.weartak_civ.cep.engine.RuleCompiler.EngineCadence?,
    val features: List<FeatureDef>,
    val rules: List<RuleDef>,
    val disabledRuleIds: Set<String>,
    val disabledFeatureIds: Set<String>,
    val tenant: String?,
    val issuer: String?,
    val rawCanonical: String,
    val payloadHash: String,
    val sawClearSupersedesRules: Boolean,
    val sawClearSupersedesFeatures: Boolean
) {
    companion object {
        const val MODE_MERGE = "MERGE"
        const val MODE_REPLACE = "REPLACE"
    }
}

/**
 * Validation outcome. A success carries the merged + validated [Resolved]; failures carry a
 * structured reason code so callers can persist a diagnostic.
 */
sealed class ValidationResult {
    data class Ok(val resolved: Resolved) : ValidationResult()
    data class Err(
        val reasonCode: String,
        val message: String,
        val details: Map<String, String> = emptyMap()
    ) : ValidationResult()
}

/** Stable reason-code enum; persisted into diagnostics. */
object ReasonCodes {
    const val MALFORMED_JSON = "malformed_json"
    const val SCHEMA_MISMATCH = "schema_mismatch"
    const val UNSUPPORTED_PATCH_SHAPE = "unsupported_patch_shape"
    const val DUPLICATE_ID = "duplicate_id"
    const val UNSUPPORTED_OPERATOR = "unsupported_operator"
    const val UNSUPPORTED_FEATURE_TYPE = "unsupported_feature_type"
    const val UNSUPPORTED_RULE_TYPE = "unsupported_rule_type"
    const val UNSUPPORTED_SEVERITY = "unsupported_severity"
    const val UNSUPPORTED_MODE = "unsupported_mode"
    const val DERIVED_CYCLE = "derived_cycle"
    const val DERIVED_DEPTH_EXCEEDED = "derived_depth_exceeded"
    const val CADENCE_OUT_OF_BOUNDS = "cadence_out_of_bounds"
    const val PAYLOAD_TOO_LARGE = "payload_too_large"
    const val MAX_RULES_EXCEEDED = "max_rules_exceeded"
    const val MAX_FEATURES_EXCEEDED = "max_features_exceeded"
    const val REPLACE_ASYMMETRY_ORPHANS_RULE = "replace_asymmetry_orphans_rule"
    const val POST_COMPOSE_ORPHAN = "post_compose_orphan"
    const val RESERVED_ID = "reserved_id"
    const val NEGATIVE_DURATION = "negative_duration"
    const val CACHED_OVERLAY_NOW_INVALID = "cached_overlay_now_invalid"
    const val NOOP_RECOMPOSE = "noop_recompose"
    const val CLEAR_SUPERSEDES_RULES_ARRAY = "clear_supersedes_rules_array"
    const val CLEAR_SUPERSEDES_FEATURES_ARRAY = "clear_supersedes_features_array"
    const val MDM_PAYLOAD_POISON_PILL_HOLDOFF = "mdm_payload_poison_pill_holdoff"
}

/** Validator/registry tuneables. Defaults match design guardrails. */
data class CepConfigBounds(
    /** Max ManagedConfiguration payload bytes (UTF-8). 256KiB default. */
    val maxPayloadBytes: Int = 256 * 1024,
    val maxRules: Int = 512,
    val maxFeatures: Int = 512,
    val maxDerivedDepth: Int = 16,
    /** Inclusive lower bound for `engine.window_tick_ms` and `rule_eval_interval_ms`. */
    val minTickMs: Int = 100,
    /** Inclusive upper bound. */
    val maxTickMs: Int = 60_000
)
