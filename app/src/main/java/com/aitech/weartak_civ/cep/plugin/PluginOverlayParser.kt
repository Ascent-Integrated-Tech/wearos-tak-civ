package com.aitech.weartak_civ.cep.plugin

import com.aitech.weartak_civ.cep.engine.RuleCompiler
import com.aitech.weartak_civ.cep.engine.config.CanonicalJson
import com.aitech.weartak_civ.cep.engine.config.CepConfigBounds
import com.aitech.weartak_civ.cep.engine.config.CepConfigMerger
import com.aitech.weartak_civ.cep.engine.config.ParserException
import com.aitech.weartak_civ.cep.engine.config.ReasonCodes
import org.json.JSONException
import org.json.JSONObject

data class ParsedPluginOverlay(
    val schemaVersion: Int,
    val pluginId: String,
    val leaseSec: Long,
    val contribution: CepConfigMerger.PluginContribution,
    val rawCanonical: String,
    val payloadHash: String,
    /** Optional plugin-declared version, propagated to diagnostic metadata (design §494). */
    val pluginVersion: String? = null,
)

object PluginOverlayParser {
    @Throws(ParserException::class)
    fun parse(raw: String, bounds: CepConfigBounds = CepConfigBounds()): ParsedPluginOverlay {
        if (raw.toByteArray(Charsets.UTF_8).size > bounds.maxPayloadBytes) {
            throw ParserException(ReasonCodes.PAYLOAD_TOO_LARGE, "plugin overlay exceeds ${bounds.maxPayloadBytes} bytes")
        }
        val canonical = CanonicalJson.canonicalizeOrNull(raw)
            ?: throw ParserException(ReasonCodes.MALFORMED_JSON, "plugin overlay is not valid JSON")
        val payloadHash = CanonicalJson.sha256(canonical)
        val root = try {
            JSONObject(raw)
        } catch (_: JSONException) {
            throw ParserException(ReasonCodes.MALFORMED_JSON, "plugin overlay is not a JSON object")
        }

        val schemaVersion = if (root.has("schemaVersion") && !root.isNull("schemaVersion")) {
            root.optInt("schemaVersion", -1)
        } else -1
        if (schemaVersion != 1) {
            throw ParserException(ReasonCodes.SCHEMA_MISMATCH, "plugin schemaVersion must be 1")
        }

        if (root.has("engine") && !root.isNull("engine")) {
            throw ParserException(ReasonCodes.PLUGIN_ENGINE_NOT_ALLOWED, "plugin overlays may not set engine cadence")
        }

        val pluginId = root.optString("pluginId", "").trim()
        if (pluginId.isEmpty()) {
            throw ParserException(ReasonCodes.MALFORMED_JSON, "pluginId is required")
        }

        val pluginVersion = root.optString("pluginVersion", "").trim().takeIf { it.isNotEmpty() }

        val leaseSec = root.optLong("leaseSec", 0L).coerceAtLeast(0L)
        val features = try {
            RuleCompiler.parseFeatureDefs(root.optJSONArray("features"))
        } catch (t: Throwable) {
            throw ParserException(ReasonCodes.UNSUPPORTED_FEATURE_TYPE, "failed to parse plugin features: ${t.message}")
        }
        val rules = try {
            RuleCompiler.parseRuleDefs(root.optJSONArray("rules"))
        } catch (t: Throwable) {
            throw ParserException(ReasonCodes.UNSUPPORTED_RULE_TYPE, "failed to parse plugin rules: ${t.message}")
        }

        if (features.any { !it.id.startsWith("$pluginId.") } || rules.any { !it.id.startsWith("$pluginId.") }) {
            throw ParserException(ReasonCodes.PLUGIN_NAMESPACE_VIOLATION, "plugin ids must be namespaced under pluginId")
        }

        // Duplicate-ID rejection inside a single plugin payload (design §915, §1167). Without
        // this, the merger would silently keep the last-write entry and an admin would never
        // see that the payload was malformed.
        val seenFeatureIds = HashSet<String>(features.size)
        for (f in features) {
            if (!seenFeatureIds.add(f.id)) {
                throw ParserException(
                    ReasonCodes.PLUGIN_PAYLOAD_DUPLICATE_ID,
                    "duplicate plugin feature id ${f.id}"
                )
            }
        }
        val seenRuleIds = HashSet<String>(rules.size)
        for (r in rules) {
            if (!seenRuleIds.add(r.id)) {
                throw ParserException(
                    ReasonCodes.PLUGIN_PAYLOAD_DUPLICATE_ID,
                    "duplicate plugin rule id ${r.id}"
                )
            }
        }

        return ParsedPluginOverlay(
            schemaVersion = schemaVersion,
            pluginId = pluginId,
            leaseSec = leaseSec,
            contribution = CepConfigMerger.PluginContribution(pluginId = pluginId, features = features, rules = rules),
            rawCanonical = canonical,
            payloadHash = payloadHash,
            pluginVersion = pluginVersion
        )
    }
}
