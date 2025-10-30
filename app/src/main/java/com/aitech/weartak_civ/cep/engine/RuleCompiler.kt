package com.aitech.weartak_civ.cep.engine

import android.content.Context
import com.aitech.weartak_civ.R
import org.json.JSONArray
import org.json.JSONObject

object RuleCompiler {

    fun loadFromRaw(context: Context, rawResId: Int = R.raw.cep_rules): CepConfig {
        val json = context.resources.openRawResource(rawResId).bufferedReader().use { it.readText() }
        return parse(JSONObject(json))
    }

    fun parse(root: JSONObject): CepConfig {
        return try {
            val engine = root.optJSONObject("engine")
            val tickMs = engine?.optInt("window_tick_ms", 1000) ?: 1000
            val tickHz = (1000 / tickMs).coerceAtLeast(1)
            val features = try { parseFeatures(root.optJSONArray("features")) } catch (ex: Throwable) {
                android.util.Log.e("CEP", "Failed to parse features", ex); emptyList()
            }
            val rules = try {
                if (root.has("rules")) parseRules(root.optJSONArray("rules")) else emptyList()
            } catch (ex: Throwable) {
                android.util.Log.e("CEP", "Failed to parse rules", ex); emptyList()
            }
            CepConfig(tickHz, features, rules)
        } catch (t: Throwable) {
            android.util.Log.e("CEP", "Failed to parse CEP config", t)
            CepConfig(1, emptyList(), emptyList())
        }
    }

    private fun parseFeatures(arr: JSONArray?): List<FeatureDef> {
        if (arr == null) return emptyList()
        val out = mutableListOf<FeatureDef>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("id", o.optString("name"))
            when (o.getString("type")) {
                "window" -> out += FeatureDef.Window(
                    id = id,
                    source = o.getString("source"),
                    op = o.optString("op", o.optString("agg", "min")),
                    windowSec = o.getInt("window_sec")
                )
                "window_multi" -> out += FeatureDef.MultiWindow(
                    id = id,
                    source = o.getString("source"),
                    multiPolicy = o.getString("multi_policy"),
                    op = o.optString("op", o.optString("agg", "min")),
                    windowSec = o.getInt("window_sec")
                )
                "slope_multi" -> out += FeatureDef.SlopeMulti(
                    id = id,
                    source = o.getString("source"),
                    multiPolicy = o.getString("multi_policy"),
                    windowSec = o.getInt("window_sec"),
                    unitsPerSec = o.optString("units_per_sec", "per_second")
                )
                "delta" -> out += FeatureDef.Delta(
                    id = id,
                    source = o.getString("source"),
                    horizonSec = o.getInt("horizon_sec")
                )
                "derived", "compute", "threshold", "logic", "slope" -> out += FeatureDef.Derived(
                    id = id,
                    expr = o.getJSONObject("expr")
                )
            }
        }
        return out
    }

    private fun parseRules(arr: JSONArray?): List<RuleDef> {
        if (arr == null) return emptyList()
        val out = mutableListOf<RuleDef>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val action = o.getJSONObject("action")
            out += RuleDef(
                id = o.getString("id"),
                description = o.optString("description", null),
                condition = o.getJSONObject("when"),
                sustainedForSec = o.optLong("sustained_for_sec", 0),
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
                    sendSms = action.optBoolean("sendSms", false)
                ),
                snapshotKeys = o.optJSONArray("snapshot_keys")?.let { ja ->
                    List(ja.length()) { idx -> ja.getString(idx) }
                } ?: emptyList()
            )
        }
        return out
    }
}


