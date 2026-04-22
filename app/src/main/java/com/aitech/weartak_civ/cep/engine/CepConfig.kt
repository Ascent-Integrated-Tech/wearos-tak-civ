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
        // Gap 1: optional freshness guard for derived inputs.
        val maxAgeSec: Double? = null,
        // Phase 3: per-feature compute cadence
        override val updateIntervalMs: Long = 1000L
    ): FeatureDef

    /**
     * Sample-count rolling window aggregate.
     * Operates over the last [sampleCount] samples inserted into the ring,
     * regardless of wall-clock time.
     *
     * @param source       Ring key (e.g. Topics.PRESSURE = "pressure")
     * @param op           Aggregation: "avg", "min", "max", "rms", "median", "variance", "stddev"
     * @param sampleCount  Number of recent samples to include.
     * @param minSamples   Minimum samples required before the feature is written (default 1).
     * @param maxAgeSec    If set, the most-recent sample must be younger than this; otherwise
     *                     feature is suppressed (write-time staleness guard).
     */
    data class SampleWindow(
        override val id: String,
        val source: String,
        val op: String,
        val sampleCount: Int,
        val minSamples: Int = 1,
        val maxAgeSec: Long? = null,
        override val updateIntervalMs: Long = 1000L
    ): FeatureDef

    /**
     * Sample-count delta: (last sample) − (sample [horizonSamples] positions ago).
     * Returns null and writes nothing if fewer than horizonSamples+1 samples are available.
     *
     * @param horizonSamples  How many samples back to look (e.g. 5 = "delta over last 5 samples").
     * @param maxAgeSec       Freshness guard on the most-recent sample (same semantics as SampleWindow).
     */
    data class SampleDelta(
        override val id: String,
        val source: String,
        val horizonSamples: Int,
        val maxAgeSec: Long? = null,
        override val updateIntervalMs: Long = 1000L
    ): FeatureDef

    /**
     * Sample-count slope via least-squares regression over [sampleCount] recent samples.
     * Uses real sensor timestamps as the x-axis so slope is in signal-units per second (or
     * per minute when [unitsPerSec] = "per_minute").
     *
     * Suppressed (writes nothing) when:
     *  - fewer than 2 samples are available
     *  - the timestamp span of the window is < MIN_SLOPE_SPAN_MS (avoids ÷0 near-singularity)
     *  - [maxAgeSec] is set and newest sample is stale
     *
     * @param sampleCount  Number of samples to include in the regression.
     * @param unitsPerSec  "per_second" (default) or "per_minute".
     */
    data class SampleSlope(
        override val id: String,
        val source: String,
        val sampleCount: Int,
        val unitsPerSec: String = "per_second",
        val maxAgeSec: Long? = null,
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


