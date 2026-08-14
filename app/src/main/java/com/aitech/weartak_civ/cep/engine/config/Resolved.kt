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
    val infoDiagnostics: List<String> = emptyList(),
    /**
     * Plugin entries that were shadowed by a higher-priority layer during merge. The merger
     * keeps `MANAGED_CONFIG ≻ BUILT_IN ≻ PLUGIN` so a plugin entry colliding with a built-in
     * or MDM entry is silently overwritten; this list lets the apply lane emit one
     * `plugin_blocked_by_*` diagnostic per shadowed entry.
     */
    val pluginCollisions: List<PluginCollision> = emptyList()
) {
    val features: List<FeatureDef> get() = engineConfig.features
    val rules: List<RuleDef> get() = engineConfig.rules
}

/**
 * One plugin-vs-higher-layer collision detected at merge time. Used by the apply lane to
 * emit a `plugin_blocked_by_builtin` / `plugin_blocked_by_mdm` info diagnostic per entry.
 */
data class PluginCollision(
    val pluginId: String,
    val entryId: String,
    val entryKind: Kind,
    val blockedBy: SourceLayer
) {
    enum class Kind { FEATURE, RULE }
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
    const val INVALID_CONFIG = "invalid_config"
    const val MISSING_RENDER_VALUES = "missing_render_values"
    const val CACHED_OVERLAY_NOW_INVALID = "cached_overlay_now_invalid"
    const val NOOP_RECOMPOSE = "noop_recompose"
    const val CLEAR_SUPERSEDES_RULES_ARRAY = "clear_supersedes_rules_array"
    const val CLEAR_SUPERSEDES_FEATURES_ARRAY = "clear_supersedes_features_array"
    const val MDM_PAYLOAD_POISON_PILL_HOLDOFF = "mdm_payload_poison_pill_holdoff"
    const val PLUGIN_ID_PACKAGE_MISMATCH = "plugin_id_package_mismatch"
    const val PLUGIN_CERT_CHANGED = "plugin_cert_changed"
    const val PLUGIN_NAMESPACE_VIOLATION = "plugin_namespace_violation"
    const val PLUGIN_ENGINE_NOT_ALLOWED = "plugin_engine_not_allowed"
    const val PLUGIN_OVERLAY_RATE_LIMITED = "plugin_overlay_rate_limited"
    // [Update v1.1 — design §983–994] Plugin-collision and duplicate diagnostics. The merger
    // keeps the higher-layer entry (BUILT_IN ≻ MANAGED_CONFIG ≻ PLUGIN was inverted to
    // MANAGED_CONFIG ≻ BUILT_IN ≻ PLUGIN); these codes make that shadowing observable.
    const val PLUGIN_BLOCKED_BY_BUILTIN = "plugin_blocked_by_builtin"
    const val PLUGIN_BLOCKED_BY_MDM = "plugin_blocked_by_mdm"
    const val PLUGIN_BLOCKED_BY_HIGHER_LAYER = "plugin_blocked_by_higher_layer"
    const val PLUGIN_PAYLOAD_DUPLICATE_ID = "plugin_payload_duplicate_id"
    // Sample-ingress invariants (design §918–920).
    const val PLUGIN_SAMPLE_DRIFT = "plugin_sample_drift"
    const val PLUGIN_SAMPLE_RATE_LIMITED = "plugin_sample_rate_limited"
    const val PLUGIN_SAMPLE_INVALID = "plugin_sample_invalid"
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
