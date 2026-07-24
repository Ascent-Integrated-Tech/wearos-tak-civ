package com.aitech.weartak_civ.cep.engine

import android.content.Context
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.cep.metric.MetricBaselinePolicy
import com.aitech.weartak_civ.cep.metric.MetricCalculationPolicy
import com.aitech.weartak_civ.cep.metric.MetricActivityContext
import com.aitech.weartak_civ.cep.metric.MetricConfidenceThresholds
import com.aitech.weartak_civ.cep.metric.MetricGatingPolicy
import com.aitech.weartak_civ.cep.metric.MetricEstablishmentPolicy
import com.aitech.weartak_civ.cep.metric.MetricValidityPolicy
import com.aitech.weartak_civ.cep.metric.MetricProcessorType
import com.aitech.weartak_civ.cep.metric.MetricSelectedOutputMapping
import com.aitech.weartak_civ.cep.metric.MetricSpec
import com.aitech.weartak_civ.cep.metric.MetricTrendPolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * RuleCompiler is the single parser authority for CEP definitions.
 *
 * WEAR-508 [Update v1.1 — U2]: built-in JSON and ManagedConfiguration overlay JSON must be
 * parsed by the same entry points so identical input yields identical [FeatureDef]/[RuleDef]
 * shapes regardless of source. The shared, throwing entry points are:
 *  - [parseEngineCadence]
 *  - [parseFeatureDefs]
 *  - [parseRuleDefs]
 *
 * `loadFromRaw` and `parse` (built-in path) and `ManagedConfigOverlayParser` (overlay path)
 * both delegate to these, so any parser divergence is structurally impossible.
 */
object RuleCompiler {
    /** Defaults applied when an `engine` object is omitted from the JSON. */
    data class EngineCadence(val tickHz: Int, val ruleEvalIntervalMs: Int)

    fun loadFromRaw(context: Context, rawResId: Int = R.raw.cep_rules): CepConfig {
        val json = context.resources.openRawResource(rawResId).bufferedReader().use { it.readText() }
        return parse(JSONObject(json))
    }

    /**
     * Built-in path: tolerant parse used at process start. Errors collapse to an empty config so
     * the engine can still start. Overlay parsers must NOT use this; they must call the strict
     * shared entry points so malformed payloads surface as validation errors.
     */
    fun parse(root: JSONObject): CepConfig {
        val engine = root.optJSONObject("engine")
        val tickMs = engine?.optInt("window_tick_ms", 1000) ?: 1000
        val tickHz = (1000 / tickMs).coerceAtLeast(1)
        val ruleEvalIntervalMs = engine?.optInt("rule_eval_interval_ms", tickMs) ?: tickMs
        val features = parseFeatures(root.optJSONArray("features"))
        val rules = if (root.has("rules")) parseRules(root.optJSONArray("rules")) else emptyList()
        val metrics = if (root.has("metrics")) parseMetrics(root.optJSONArray("metrics")) else emptyList()
        return CepConfig(tickHz, features, rules, metrics, ruleEvalIntervalMs)
    }

    private fun parseMetrics(arr: JSONArray?): List<MetricSpec> {
        if (arr == null) return emptyList()
        val out = mutableListOf<MetricSpec>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val confidence = o.getJSONObject("baseline").getJSONObject("confidence_thresholds")
            val selectedObj = o.getJSONObject("selected_outputs")
            val selectedMap = mutableMapOf<String, String>()
            selectedObj.keys().forEach { slot -> selectedMap[slot] = selectedObj.getString(slot) }
            out += MetricSpec(
                id = o.getString("id"),
                source = o.getString("source"),
                processor = MetricProcessorType.fromJson(o.getString("processor")),
                sourceHookId = if (o.has("source_hook") && !o.isNull("source_hook")) o.getString("source_hook") else null,
                gating = o.optJSONObject("gating")?.let { gating ->
                    MetricGatingPolicy(
                        onWrist = gating.optBoolean("on_wrist", false),
                        stillOrSleeping = gating.optBoolean("still_or_sleeping", false),
                        freshWithinSec = if (gating.has("fresh_within_sec") && !gating.isNull("fresh_within_sec")) {
                            gating.getInt("fresh_within_sec")
                        } else {
                            null
                        },
                        activityContext = when (gating.optString("activity_context", "ANY").uppercase()) {
                            "RESTING" -> MetricActivityContext.RESTING
                            "ACTIVE" -> MetricActivityContext.ACTIVE
                            else -> MetricActivityContext.ANY
                        },
                        minContextDwellSec = gating.optLong("min_context_dwell_sec", 0L),
                        restingCooldownSec = gating.optLong("resting_cooldown_sec", 0L)
                    )
                } ?: MetricGatingPolicy(),
                validity = o.optJSONObject("validity")?.let { validity ->
                    MetricValidityPolicy(
                        minValue = if (validity.has("min_value") && !validity.isNull("min_value")) {
                            validity.getDouble("min_value")
                        } else null,
                        maxValue = if (validity.has("max_value") && !validity.isNull("max_value")) {
                            validity.getDouble("max_value")
                        } else null
                    )
                } ?: MetricValidityPolicy(),
                baseline = MetricBaselinePolicy(
                    estimator = o.getJSONObject("baseline").optString("estimator", "trimmed_mean"),
                    transform = o.getJSONObject("baseline").optString("transform", "none"),
                    windowSec = o.getJSONObject("baseline").getInt("window_sec"),
                    minSamples = o.getJSONObject("baseline").getInt("min_samples"),
                    maxStalenessSec = o.getJSONObject("baseline").getInt("max_staleness_sec"),
                    establishment = o.optJSONObject("baseline")?.optJSONObject("establishment")?.let { establishment ->
                        MetricEstablishmentPolicy(
                            minCoverageSec = establishment.optLong("min_coverage_sec", 0L),
                            minActiveDays = establishment.optInt("min_active_days", 0),
                            minConfidence = establishment.optDouble("min_confidence", 0.0),
                            maxGapSec = establishment.optLong("max_gap_sec", 0L)
                        )
                    } ?: MetricEstablishmentPolicy(),
                    coverageTargetSec = o.getJSONObject("baseline").optLong("coverage_target_sec", 0L)
                        .takeIf { it > 0L },
                    stabilityWeight = o.getJSONObject("baseline").optDouble("stability_weight", 1.0),
                    calculation = o.getJSONObject("baseline").optJSONObject("calculation_cache")?.let { cache ->
                        MetricCalculationPolicy(
                            warmingMaxAgeSec = cache.optLong("warming_max_age_sec", 900L),
                            warmingSampleTrigger = cache.optLong("warming_sample_trigger", 10L),
                            establishedMaxAgeSec = cache.optLong("established_max_age_sec", 21_600L)
                        )
                    } ?: MetricCalculationPolicy(),
                    confidenceThresholds = MetricConfidenceThresholds(
                        warming = confidence.getInt("warming"),
                        readyLow = confidence.getInt("ready_low"),
                        medium = confidence.getInt("medium"),
                        high = confidence.getInt("high")
                    )
                ),
                trend = MetricTrendPolicy(
                    shortWindowSec = o.getJSONObject("trend").getInt("short_window_sec"),
                    longWindowSec = o.getJSONObject("trend").getInt("long_window_sec"),
                    enterPercent = o.getJSONObject("trend").getDouble("enter_percent"),
                    returnToStableInnerPercent = o.getJSONObject("trend").getDouble("return_to_stable_inner_percent")
                ),
                selectedOutputs = MetricSelectedOutputMapping(selectedMap),
                topic = if (o.has("topic") && !o.isNull("topic")) o.getString("topic") else null,
                scalarField = if (o.has("scalar_field") && !o.isNull("scalar_field")) {
                    o.getString("scalar_field")
                } else null
            )
        return try {
            val cadence = parseEngineCadence(root.optJSONObject("engine"))
            val features = try {
                parseFeatureDefs(root.optJSONArray("features"))
            } catch (ex: Throwable) {
                android.util.Log.e("CEP", "Failed to parse features", ex); emptyList()
            }
            val rules = try {
                if (root.has("rules")) parseRuleDefs(root.optJSONArray("rules")) else emptyList()
            } catch (ex: Throwable) {
                android.util.Log.e("CEP", "Failed to parse rules", ex); emptyList()
            }
            CepConfig(cadence.tickHz, features, rules, cadence.ruleEvalIntervalMs)
        } catch (t: Throwable) {
            android.util.Log.e("CEP", "Failed to parse CEP config", t)
            CepConfig(1, emptyList(), emptyList())
        }
        return out
    }

    /**
     * Shared entry point: parse `engine` cadence object.
     * Returns defaults when [engine] is null (omitted in payload).
     */
    fun parseEngineCadence(engine: JSONObject?): EngineCadence {
        val tickMs = engine?.optInt("window_tick_ms", 1000) ?: 1000
        val tickHz = (1000 / tickMs).coerceAtLeast(1)
        val ruleEvalIntervalMs = engine?.optInt("rule_eval_interval_ms", tickMs) ?: tickMs
        return EngineCadence(tickHz, ruleEvalIntervalMs)
    }

    /**
     * Shared entry point: parse a `features` JSON array into [FeatureDef]s.
     * Throws [org.json.JSONException] on malformed entries; callers (validator/overlay parser)
     * decide whether to reject the whole candidate or fall back.
     */
    fun parseFeatureDefs(arr: JSONArray?): List<FeatureDef> {
        if (arr == null) return emptyList()
        val out = mutableListOf<FeatureDef>()
        for (i in 0 until arr.length()) {
            out += parseFeatureEntry(arr.getJSONObject(i))
        }
        return out
    }

    /**
     * Shared entry point: parse a `rules` JSON array into [RuleDef]s.
     * Throws [org.json.JSONException] on malformed entries.
     */
    fun parseRuleDefs(arr: JSONArray?): List<RuleDef> {
        if (arr == null) return emptyList()
        val out = mutableListOf<RuleDef>()
        for (i in 0 until arr.length()) {
            out += parseRuleEntry(arr.getJSONObject(i))
        }
        return out
    }

    private fun parseFeatureEntry(o: JSONObject): FeatureDef {
        val id = o.optString("id", o.optString("name"))
        val updateIntervalMs = if (o.has("update_interval_ms") && !o.isNull("update_interval_ms"))
            o.getLong("update_interval_ms") else 1000L
        val maxAgeSec = if (o.has("max_age_sec") && !o.isNull("max_age_sec")) o.getLong("max_age_sec") else null
        val derivedMaxAgeSec = if (o.has("max_age_sec") && !o.isNull("max_age_sec")) o.getDouble("max_age_sec") else null
        return when (o.getString("type")) {
            "window" -> FeatureDef.Window(
                id = id,
                source = o.getString("source"),
                op = o.optString("op", o.optString("agg", "min")),
                windowSec = o.getInt("window_sec"),
                maxAgeSec = maxAgeSec,
                updateIntervalMs = updateIntervalMs
            )
            "window_multi" -> FeatureDef.MultiWindow(
                id = id,
                source = o.getString("source"),
                multiPolicy = o.getString("multi_policy"),
                op = o.optString("op", o.optString("agg", "min")),
                windowSec = o.getInt("window_sec"),
                maxAgeSec = maxAgeSec,
                updateIntervalMs = updateIntervalMs
            )
            "slope_multi" -> FeatureDef.SlopeMulti(
                id = id,
                source = o.getString("source"),
                multiPolicy = o.getString("multi_policy"),
                windowSec = o.getInt("window_sec"),
                unitsPerSec = o.optString("units_per_sec", "per_second"),
                maxAgeSec = maxAgeSec,
                updateIntervalMs = updateIntervalMs
            )
            "delta" -> FeatureDef.Delta(
                id = id,
                source = o.getString("source"),
                horizonSec = o.getInt("horizon_sec"),
                updateIntervalMs = updateIntervalMs
            )
            "derived", "compute", "threshold", "logic", "slope" -> FeatureDef.Derived(
                id = id,
                expr = o.getJSONObject("expr"),
                maxAgeSec = derivedMaxAgeSec,
                updateIntervalMs = updateIntervalMs
            )
            "sample_window" -> FeatureDef.SampleWindow(
                id = id,
                source = o.getString("source"),
                op = o.optString("op", "avg"),
                sampleCount = o.getInt("sample_count"),
                minSamples = o.optInt("min_samples", 1),
                maxAgeSec = maxAgeSec,
                updateIntervalMs = updateIntervalMs
            )
            "sample_delta" -> FeatureDef.SampleDelta(
                id = id,
                source = o.getString("source"),
                horizonSamples = o.getInt("horizon_samples"),
                maxAgeSec = maxAgeSec,
                updateIntervalMs = updateIntervalMs
            )
            "sample_slope" -> FeatureDef.SampleSlope(
                id = id,
                source = o.getString("source"),
                sampleCount = o.getInt("sample_count"),
                unitsPerSec = o.optString("units_per_sec", "per_second"),
                maxAgeSec = maxAgeSec,
                updateIntervalMs = updateIntervalMs
            )
            else -> throw IllegalArgumentException("Unsupported feature type: ${o.getString("type")}")
        }
    }

    private fun parseRuleEntry(o: JSONObject): RuleDef {
        val action = o.getJSONObject("action")
        val message = action.optString("message", "")
        val sustainedForSec = o.optLong("sustained_for_sec", 0)
        val rawWarn = if (o.has("sustained_for_sec_warn") && !o.isNull("sustained_for_sec_warn"))
            o.optLong("sustained_for_sec_warn", -1) else -1L
        val sustainedForSecWarn = if (rawWarn in 1 until sustainedForSec) rawWarn else null
        return RuleDef(
            id = o.getString("id"),
            description = o.optString("description", null),
            condition = o.getJSONObject("when"),
            sustainedForSec = sustainedForSec,
            sustainedForSecWarn = sustainedForSecWarn,
            holdDownSec = o.optLong("hold_down_sec", 0),
            action = parseRuleAction(action),
            renderKeys = renderKeysFor(message, parseStringArray(o.optJSONArray("render_keys"))),
            onMissingRender = parseMissingRenderPolicy(o.optString("on_missing_render", null)),
            snapshotKeys = parseStringArray(o.optJSONArray("snapshot_keys")),
            allowSustainOverride = o.optBoolean("allow_sustain_override", false)
        )
    }

    private fun parseRuleAction(action: JSONObject): RuleAction {
        return RuleAction(
            type = action.getString("type"),
            severity = action.optString("severity", "INFO"),
            message = action.optString("message", ""),
            tags = parseStringArray(action.optJSONArray("tags")),
            category = action.optString("category", null),
            isTak = action.optBoolean("isTak", false),
            isLocal = action.optBoolean("isLocal", false),
            priorityFrom = action.optString("priority_from", "severity"),
            priority = if (action.has("priority")) action.optInt("priority") else null,
            sendSms = action.optBoolean("sendSms", false),
            timeoutSec = if (action.has("timeout")) action.optLong("timeout") else null,
            monitor = parseExitConditions(action.optJSONArray("monitor")),
            exitSustainSec = if (action.has("exit_sustain_sec") && !action.isNull("exit_sustain_sec"))
                action.getLong("exit_sustain_sec") else null,
            exitLogic = if (action.has("exit_logic") && !action.isNull("exit_logic"))
                action.getString("exit_logic") else null
        )
    }

    private fun parseExitConditions(arr: JSONArray?): List<ExitCondition> {
        if (arr == null) return emptyList()
        val out = mutableListOf<ExitCondition>()
        for (i in 0 until arr.length()) {
            out += parseExitCondition(arr.getJSONObject(i))
        }
        return out
    }

    private fun parseExitCondition(m: JSONObject): ExitCondition {
        val (exitBelow, exitBelowKey) = parseNumericOrKey(m, "exit_below")
        val (exitAbove, exitAboveKey) = parseNumericOrKey(m, "exit_above")
        val hysteresis = if (m.has("hysteresis") && !m.isNull("hysteresis")) m.getDouble("hysteresis") else 0.0
        return ExitCondition(
            feature = m.getString("feature"),
            exitBelow = exitBelow,
            exitAbove = exitAbove,
            exitBelowKey = exitBelowKey,
            exitAboveKey = exitAboveKey,
            hysteresis = hysteresis
        )
    }

    private fun parseNumericOrKey(o: JSONObject, name: String): Pair<Double?, String?> {
        if (!o.has(name) || o.isNull(name)) return null to null
        return when (val raw = o.get(name)) {
            is Number -> raw.toDouble() to null
            is String -> null to raw
            else -> null to null
        }
    }

    private fun parseStringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return List(arr.length()) { idx -> arr.getString(idx) }
    }

    private fun renderKeysFor(message: String, explicit: List<String>): List<String> {
        val derived = MessageTemplate.extractKeys(message)
        if (explicit.isEmpty()) return derived
        val keys = LinkedHashSet<String>()
        keys.addAll(derived)
        keys.addAll(explicit)
        return keys.toList()
    }

    private fun parseMissingRenderPolicy(raw: String?): MissingRenderPolicy {
        return when (raw?.trim()?.lowercase()) {
            null, "", "suppress" -> MissingRenderPolicy.SUPPRESS
            "degrade" -> MissingRenderPolicy.DEGRADE
            else -> throw IllegalArgumentException("Unsupported on_missing_render: $raw")
        }
    }
}
