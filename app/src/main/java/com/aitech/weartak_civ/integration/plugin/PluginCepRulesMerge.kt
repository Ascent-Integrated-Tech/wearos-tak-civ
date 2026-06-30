package com.aitech.weartak_civ.integration.plugin

import org.json.JSONArray
import org.json.JSONObject

/**
 * Merges AIDL `registerCepRules` / `unregisterCepRules` fragments into a full v1 plugin
 * overlay document consumable by [com.aitech.weartak_civ.cep.plugin.PluginOverlayParser].
 *
 * Sensor-scoped replacement uses id prefix `"$pluginId.$sensorKey."` so unregister can strip
 * contributions for that sensor without touching other sensors from the same plugin.
 */
object PluginCepRulesMerge {
    const val DEFAULT_LEASE_SEC: Long = 86_400L

    fun rulePrefix(pluginId: String, sensorKey: String): String = "$pluginId.$sensorKey."

    /**
     * @param rulesJson SDK-validated JSON object; may contain `rules` and/or `features`
     * arrays, or a single rule object (must include top-level `id`).
     */
    fun mergeRegister(
        pluginId: String,
        sensorKey: String,
        rulesJson: JSONObject,
        existingRaw: String?,
        leaseSec: Long = DEFAULT_LEASE_SEC,
    ): String {
        val base = parseOrEmptyOverlay(pluginId, existingRaw, leaseSec)
        val rules = base.optJSONArray("rules") ?: JSONArray().also { base.put("rules", it) }
        val features = base.optJSONArray("features") ?: JSONArray().also { base.put("features", it) }

        val prefix = rulePrefix(pluginId, sensorKey)
        stripIdsStartingWith(rules, prefix)
        stripIdsStartingWith(features, prefix)

        when {
            rulesJson.has("rules") || rulesJson.has("features") -> {
                appendAll(rules, rulesJson.optJSONArray("rules"))
                appendAll(features, rulesJson.optJSONArray("features"))
            }
            rulesJson.has("id") -> {
                rules.put(rulesJson)
            }
            else -> {
                throw IllegalArgumentException("rulesJson must contain rules/features arrays or a rule id")
            }
        }
        return base.toString()
    }

    fun mergeUnregister(
        pluginId: String,
        sensorKey: String,
        existingRaw: String?,
        leaseSec: Long = DEFAULT_LEASE_SEC,
    ): String {
        val base = parseOrEmptyOverlay(pluginId, existingRaw, leaseSec)
        val rules = base.optJSONArray("rules") ?: JSONArray().also { base.put("rules", it) }
        val features = base.optJSONArray("features") ?: JSONArray().also { base.put("features", it) }
        val prefix = rulePrefix(pluginId, sensorKey)
        stripIdsStartingWith(rules, prefix)
        stripIdsStartingWith(features, prefix)
        return base.toString()
    }

    private fun parseOrEmptyOverlay(pluginId: String, existingRaw: String?, leaseSec: Long): JSONObject {
        if (!existingRaw.isNullOrBlank()) {
            runCatching { JSONObject(existingRaw) }.getOrNull()?.let { root ->
                if (root.optString("pluginId", "") == pluginId && root.optInt("schemaVersion", -1) == 1) {
                    return root
                }
            }
        }
        return JSONObject()
            .put("schemaVersion", 1)
            .put("pluginId", pluginId)
            .put("leaseSec", leaseSec)
            .put("features", JSONArray())
            .put("rules", JSONArray())
    }

    private fun stripIdsStartingWith(arr: JSONArray, prefix: String) {
        val keep = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (!id.startsWith(prefix)) keep.put(o)
        }
        while (arr.length() > 0) {
            arr.remove(0)
        }
        for (i in 0 until keep.length()) {
            arr.put(keep.getJSONObject(i))
        }
    }

    private fun appendAll(target: JSONArray, source: JSONArray?) {
        if (source == null) return
        for (i in 0 until source.length()) {
            when (val v = source.get(i)) {
                is JSONObject -> target.put(v)
                else -> Unit
            }
        }
    }
}
