package com.aitech.weartak_civ.cep.engine

data class CepConfig(
    val tickHz: Int,
    val features: List<FeatureDef>,
    val rules: List<RuleDef>,
    // Phase 1: explicit rule-evaluation interval (may differ from ingest tick in later phases)
    val ruleEvalIntervalMs: Int = 1000
)

sealed interface FeatureDef {
    val id: String
    /**
     * Phase 3: how often this feature is recomputed (ms).
     * Default 1000ms preserves the legacy 1-Hz behaviour.
     * Set a larger value (e.g. 5000) for expensive or slow-changing features;
     * set a smaller value (e.g. 500) for features that must be fresher than the
     * rule-eval cadence.  The rule-eval loop reads the last cached value regardless
     * of when the feature last computed.
     */
    val updateIntervalMs: Long

    data class Window(
        override val id: String,
        val source: String,
        val op: String,
        val windowSec: Int,
        // Phase 1: optional freshness guard — feature is only emitted if source data age ≤ maxAgeSec
        val maxAgeSec: Long? = null,
        // Phase 3: per-feature compute cadence
        override val updateIntervalMs: Long = 1000L
    ): FeatureDef
    data class MultiWindow(
        override val id: String,
        val source: String,
        val multiPolicy: String, // "min", "max"
        val op: String,
        val windowSec: Int,
        // Phase 1: optional freshness guard
        val maxAgeSec: Long? = null,
        // Phase 3: per-feature compute cadence
        override val updateIntervalMs: Long = 1000L
    ): FeatureDef
    data class SlopeMulti(
        override val id: String,
        val source: String,
        val multiPolicy: String, // "min_channel"
        val windowSec: Int,
        val unitsPerSec: String, // "per_second" | "per_minute"
        // Phase 1: optional freshness guard
        val maxAgeSec: Long? = null,
        // Phase 3: per-feature compute cadence
        override val updateIntervalMs: Long = 1000L
    ): FeatureDef
    data class Delta(
        override val id: String,
        val source: String,
        val horizonSec: Int,
        // Phase 3: per-feature compute cadence
        override val updateIntervalMs: Long = 1000L
    ): FeatureDef
    data class Derived(
        override val id: String,
        val expr: org.json.JSONObject,
        // Phase 3: per-feature compute cadence
        override val updateIntervalMs: Long = 1000L
    ): FeatureDef
}

data class RuleDef(
    val id: String,
    val description: String?,
    val condition: org.json.JSONObject,
    val sustainedForSec: Long,
    val sustainedForSecWarn: Long? = null,
    val holdDownSec: Long,
    val action: RuleAction,
    val snapshotKeys: List<String> = emptyList(),
    val allowSustainOverride: Boolean = false
)

data class RuleAction(
    val type: String,
    val severity: String,
    val message: String,
    val tags: List<String> = emptyList(),
    val category: String? = null,     // e.g., "CAT_HR_EMERGENCY"
    val isTak: Boolean = false,
    val isLocal: Boolean = false,
    val priorityFrom: String? = "severity", // "severity" or null
    val priority: Int? = null,        // explicit fallback if not deriving
    val sendSms: Boolean = false,
    val timeoutSec: Long? = null,
    val uid: String? = "",
    val monitor: List<ExitCondition> = emptyList(),
    val exitSustainSec: Long? = null,
    val exitLogic: String? = null // "AND" or "OR", default to "AND" if null
)

data class ExitCondition(
    val feature: String,
    val exitBelow: Double? = null,
    val exitAbove: Double? = null,
    val exitBelowKey: String? = null,
    val exitAboveKey: String? = null,
    val hysteresis: Double = 0.0
)



