package com.aitech.weartak_civ.cep.engine

import android.content.Context
import com.aitech.weartak_civ.R
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
            val o = arr.getJSONObject(i)
            val id = o.optString("id", o.optString("name"))
            val updateIntervalMs = if (o.has("update_interval_ms") && !o.isNull("update_interval_ms"))
                o.getLong("update_interval_ms") else 1000L
            when (o.getString("type")) {
                "window" -> out += FeatureDef.Window(
                    id = id,
                    source = o.getString("source"),
                    op = o.optString("op", o.optString("agg", "min")),
                    windowSec = o.getInt("window_sec"),
                    maxAgeSec = if (o.has("max_age_sec") && !o.isNull("max_age_sec")) o.getLong("max_age_sec") else null,
                    updateIntervalMs = updateIntervalMs
                )
                "window_multi" -> out += FeatureDef.MultiWindow(
                    id = id,
                    source = o.getString("source"),
                    multiPolicy = o.getString("multi_policy"),
                    op = o.optString("op", o.optString("agg", "min")),
                    windowSec = o.getInt("window_sec"),
                    maxAgeSec = if (o.has("max_age_sec") && !o.isNull("max_age_sec")) o.getLong("max_age_sec") else null,
                    updateIntervalMs = updateIntervalMs
                )
                "slope_multi" -> out += FeatureDef.SlopeMulti(
                    id = id,
                    source = o.getString("source"),
                    multiPolicy = o.getString("multi_policy"),
                    windowSec = o.getInt("window_sec"),
                    unitsPerSec = o.optString("units_per_sec", "per_second"),
                    maxAgeSec = if (o.has("max_age_sec") && !o.isNull("max_age_sec")) o.getLong("max_age_sec") else null,
                    updateIntervalMs = updateIntervalMs
                )
                "delta" -> out += FeatureDef.Delta(
                    id = id,
                    source = o.getString("source"),
                    horizonSec = o.getInt("horizon_sec"),
                    updateIntervalMs = updateIntervalMs
                )
                "derived", "compute", "threshold", "logic", "slope" -> out += FeatureDef.Derived(
                    id = id,
                    expr = o.getJSONObject("expr"),
                    maxAgeSec = if (o.has("max_age_sec") && !o.isNull("max_age_sec")) o.getDouble("max_age_sec") else null,
                    updateIntervalMs = updateIntervalMs
                )
                "sample_window" -> out += FeatureDef.SampleWindow(
                    id = id,
                    source = o.getString("source"),
                    op = o.optString("op", "avg"),
                    sampleCount = o.getInt("sample_count"),
                    minSamples = o.optInt("min_samples", 1),
                    maxAgeSec = if (o.has("max_age_sec") && !o.isNull("max_age_sec")) o.getLong("max_age_sec") else null,
                    updateIntervalMs = updateIntervalMs
                )
                "sample_delta" -> out += FeatureDef.SampleDelta(
                    id = id,
                    source = o.getString("source"),
                    horizonSamples = o.getInt("horizon_samples"),
                    maxAgeSec = if (o.has("max_age_sec") && !o.isNull("max_age_sec")) o.getLong("max_age_sec") else null,
                    updateIntervalMs = updateIntervalMs
                )
                "sample_slope" -> out += FeatureDef.SampleSlope(
                    id = id,
                    source = o.getString("source"),
                    sampleCount = o.getInt("sample_count"),
                    unitsPerSec = o.optString("units_per_sec", "per_second"),
                    maxAgeSec = if (o.has("max_age_sec") && !o.isNull("max_age_sec")) o.getLong("max_age_sec") else null,
                    updateIntervalMs = updateIntervalMs
                )
            }
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
            val o = arr.getJSONObject(i)
            val action = o.getJSONObject("action")

            // Parse monitor[]
            val monitorArray = action.optJSONArray("monitor")
            val monitorList = mutableListOf<ExitCondition>()
            if (monitorArray != null) {
                for (mi in 0 until monitorArray.length()) {
                    val m = monitorArray.getJSONObject(mi)
                    val feature = m.getString("feature")

                    // exit_below can be number OR feature ref string
                    val exitBelow: Double?
                    val exitBelowKey: String?
                    if (m.has("exit_below") && !m.isNull("exit_below")) {
                        val raw = m.get("exit_below")
                        when (raw) {
                            is Number -> {
                                exitBelow = raw.toDouble()
                                exitBelowKey = null
                            }
                            is String -> {
                                exitBelow = null
                                exitBelowKey = raw
                            }
                            else -> {
                                exitBelow = null
                                exitBelowKey = null
                            }
                        }
                    } else {
                        exitBelow = null
                        exitBelowKey = null
                    }

                    // exit_above can be number OR feature ref string
                    val exitAbove: Double?
                    val exitAboveKey: String?
                    if (m.has("exit_above") && !m.isNull("exit_above")) {
                        val raw = m.get("exit_above")
                        when (raw) {
                            is Number -> {
                                exitAbove = raw.toDouble()
                                exitAboveKey = null
                            }
                            is String -> {
                                exitAbove = null
                                exitAboveKey = raw
                            }
                            else -> {
                                exitAbove = null
                                exitAboveKey = null
                            }
                        }
                    } else {
                        exitAbove = null
                        exitAboveKey = null
                    }

                    val hysteresis =
                        if (m.has("hysteresis") && !m.isNull("hysteresis")) m.getDouble("hysteresis") else 0.0

                    monitorList += ExitCondition(
                        feature = feature,
                        exitBelow = exitBelow,
                        exitAbove = exitAbove,
                        exitBelowKey = exitBelowKey,
                        exitAboveKey = exitAboveKey,
                        hysteresis = hysteresis
                    )
                }
            }

            val exitSustainSec =
                if (action.has("exit_sustain_sec") && !action.isNull("exit_sustain_sec"))
                    action.getLong("exit_sustain_sec") else null

            val exitLogic =
                if (action.has("exit_logic") && !action.isNull("exit_logic"))
                    action.getString("exit_logic") else null

            val sustainedForSec = o.optLong("sustained_for_sec", 0)
            val rawWarn = if (o.has("sustained_for_sec_warn") && !o.isNull("sustained_for_sec_warn"))
                o.optLong("sustained_for_sec_warn", -1) else -1L
            val sustainedForSecWarn = if (rawWarn in 1 until sustainedForSec) rawWarn else null

            out += RuleDef(
                id = o.getString("id"),
                description = o.optString("description", null),
                condition = o.getJSONObject("when"),
                sustainedForSec = sustainedForSec,
                sustainedForSecWarn = sustainedForSecWarn,
                holdDownSec = o.optLong("hold_down_sec", 0),
                action = RuleAction(
                    type = action.getString("type"),
                    severity = action.optString("severity", "INFO"),
                    message = action.optString("message", ""),
                    tags = action.optJSONArray("tags")?.let { ja ->
                        List(ja.length()) { idx -> ja.getString(idx) }
                    } ?: emptyList(),
                    category = action.optString("category", null),
                    isTak = action.optBoolean("isTak", false),
                    isLocal = action.optBoolean("isLocal", false),
                    priorityFrom = action.optString("priority_from", "severity"),
                    priority = if (action.has("priority")) action.optInt("priority") else null,
                    sendSms = action.optBoolean("sendSms", false),
                    timeoutSec = if (action.has("timeout")) action.optLong("timeout") else null,
                    monitor = monitorList,
                    exitSustainSec = exitSustainSec,
                    exitLogic = exitLogic
                ),
                snapshotKeys = o.optJSONArray("snapshot_keys")?.let { ja ->
                    List(ja.length()) { idx -> ja.getString(idx) }
                } ?: emptyList(),
                allowSustainOverride = o.optBoolean("allow_sustain_override", false)
            )
        }
        return out
    }
}
