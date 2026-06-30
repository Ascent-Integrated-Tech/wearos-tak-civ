package com.aitech.weartak_civ.cep.plugin

import com.aitech.weartak_civ.cep.engine.RuleCompiler
import com.aitech.weartak_civ.cep.engine.FeatureDef
import com.aitech.weartak_civ.cep.engine.RuleDef
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

        val schemaVersion = requireSchemaVersion(root)
        if (schemaVersion != 1) {
            throw ParserException(ReasonCodes.SCHEMA_MISMATCH, "plugin schemaVersion must be 1")
        }

        if (root.has("engine") && !root.isNull("engine")) {
            throw ParserException(ReasonCodes.PLUGIN_ENGINE_NOT_ALLOWED, "plugin overlays may not set engine cadence")
        }

        val pluginId = requirePluginId(root)

        val pluginVersion = root.optString("pluginVersion", "").trim().takeIf { it.isNotEmpty() }

        val leaseSec = root.optLong("leaseSec", 0L).coerceAtLeast(0L)
        val features = parseFeatures(root)
        val rules = parseRules(root)
        validateNamespace(pluginId, features, rules)
        validateDuplicateIds(features, rules)

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

    private fun requireSchemaVersion(root: JSONObject): Int {
        return if (root.has("schemaVersion") && !root.isNull("schemaVersion")) {
            root.optInt("schemaVersion", -1)
        } else {
            -1
        }
    }

    private fun requirePluginId(root: JSONObject): String {
        val pluginId = root.optString("pluginId", "").trim()
        if (pluginId.isEmpty()) {
            throw ParserException(ReasonCodes.MALFORMED_JSON, "pluginId is required")
        }
        return pluginId
    }

    private fun parseFeatures(root: JSONObject) = try {
        RuleCompiler.parseFeatureDefs(root.optJSONArray("features"))
    } catch (t: Throwable) {
        throw ParserException(ReasonCodes.UNSUPPORTED_FEATURE_TYPE, "failed to parse plugin features: ${t.message}")
    }

    private fun parseRules(root: JSONObject) = try {
        RuleCompiler.parseRuleDefs(root.optJSONArray("rules"))
    } catch (t: Throwable) {
        throw ParserException(ReasonCodes.UNSUPPORTED_RULE_TYPE, "failed to parse plugin rules: ${t.message}")
    }

    private fun validateNamespace(pluginId: String, features: List<FeatureDef>, rules: List<RuleDef>) {
        if (features.any { !it.id.startsWith("$pluginId.") } || rules.any { !it.id.startsWith("$pluginId.") }) {
            throw ParserException(ReasonCodes.PLUGIN_NAMESPACE_VIOLATION, "plugin ids must be namespaced under pluginId")
        }
    }

    private fun validateDuplicateIds(features: List<FeatureDef>, rules: List<RuleDef>) {
        val seenFeatureIds = HashSet<String>(features.size)
        for (feature in features) {
            if (!seenFeatureIds.add(feature.id)) {
                throw ParserException(
                    ReasonCodes.PLUGIN_PAYLOAD_DUPLICATE_ID,
                    "duplicate plugin feature id ${feature.id}"
                )
            }
        }
        val seenRuleIds = HashSet<String>(rules.size)
        for (rule in rules) {
            if (!seenRuleIds.add(rule.id)) {
                throw ParserException(
                    ReasonCodes.PLUGIN_PAYLOAD_DUPLICATE_ID,
                    "duplicate plugin rule id ${rule.id}"
                )
            }
        }
    }
}
