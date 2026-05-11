package com.aitech.weartak_civ.cep.engine.config

import com.aitech.weartak_civ.cep.engine.RuleCompiler
import org.json.JSONException
import org.json.JSONObject

/**
 * Parses a ManagedConfiguration `cep_configuration_json` payload into a
 * [ParsedManagedOverlay] using the SHARED RuleCompiler entry points
 * (`parseFeatureDefs`, `parseRuleDefs`, `parseEngineCadence`).
 *
 * Per WEAR-508 [Update v1.1 — U2], built-in JSON and overlay JSON must produce identical
 * parsed shapes for identical input. This parser does not duplicate parsing semantics; it
 * only owns:
 *  - schema-envelope checks (`schemaVersion`, modes, clears, disabled lists, metadata)
 *  - within-payload precedence (`clearCepRules` supersedes `rules` array, etc.)
 *  - early structural rejection (PATCH-shape, malformed JSON)
 *
 * Validation of the merged candidate (cycle detection, orphan checks, cadence bounds, etc.)
 * is performed by [CepConfigValidator] after [CepConfigMerger] has composed layers.
 */
object ManagedConfigOverlayParser {

    /** Empty-string sentinel = "no ManagedConfiguration overlay". Must never reach the parser. */
    fun isEmpty(raw: String?): Boolean = raw.isNullOrBlank()

    /**
     * Parse a managed overlay payload.
     * @throws ParserException with a stable [ReasonCodes] reasonCode on any structural issue.
     */
    @Throws(ParserException::class)
    fun parse(raw: String, bounds: CepConfigBounds = CepConfigBounds()): ParsedManagedOverlay {
        if (raw.toByteArray(Charsets.UTF_8).size > bounds.maxPayloadBytes) {
            throw ParserException(ReasonCodes.PAYLOAD_TOO_LARGE,
                "managed overlay payload exceeds ${bounds.maxPayloadBytes} bytes")
        }

        val canonical = CanonicalJson.canonicalizeOrNull(raw)
            ?: throw ParserException(ReasonCodes.MALFORMED_JSON, "managed overlay is not valid JSON")
        val payloadHash = CanonicalJson.sha256(canonical)

        val root: JSONObject = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            throw ParserException(ReasonCodes.MALFORMED_JSON, "managed overlay is not a JSON object")
        }

        // PATCH detection — explicit rejection per [Update v1.1 — U6].
        // Reject any of: top-level "patch", "ops", "operations", or shapes that include a
        // schemaVersion of "1+patch", or fields named "$patch" / "$op".
        if (looksLikePatch(root)) {
            throw ParserException(
                ReasonCodes.UNSUPPORTED_PATCH_SHAPE,
                "PATCH-shaped overlays are not supported in v1"
            )
        }

        val schemaVersion = if (root.has("schemaVersion") && !root.isNull("schemaVersion")) {
            try {
                root.getInt("schemaVersion")
            } catch (e: JSONException) {
                throw ParserException(ReasonCodes.SCHEMA_MISMATCH,
                    "schemaVersion must be an integer")
            }
        } else {
            throw ParserException(ReasonCodes.SCHEMA_MISMATCH, "schemaVersion is required")
        }
        if (schemaVersion != 1) {
            throw ParserException(ReasonCodes.SCHEMA_MISMATCH,
                "schemaVersion=$schemaVersion is unsupported in v1",
                mapOf("schemaVersion" to schemaVersion.toString()))
        }

        val cepResetEpoch = if (root.has("cepResetEpoch") && !root.isNull("cepResetEpoch")) {
            root.optLong("cepResetEpoch", 0L).coerceAtLeast(0L)
        } else 0L

        val rulesMode = normalizeMode(root.optString("rulesMode", ParsedManagedOverlay.MODE_MERGE))
            ?: throw ParserException(ReasonCodes.UNSUPPORTED_MODE,
                "rulesMode must be MERGE or REPLACE")
        val featuresMode = normalizeMode(root.optString("featuresMode", ParsedManagedOverlay.MODE_MERGE))
            ?: throw ParserException(ReasonCodes.UNSUPPORTED_MODE,
                "featuresMode must be MERGE or REPLACE")

        val clearCepRules = root.optBoolean("clearCepRules", false)
        val clearCepFeatures = root.optBoolean("clearCepFeatures", false)

        val engineCadence = if (root.has("engine") && !root.isNull("engine")) {
            RuleCompiler.parseEngineCadence(root.optJSONObject("engine"))
        } else null

        val features = try {
            if (clearCepFeatures) emptyList()
            else RuleCompiler.parseFeatureDefs(root.optJSONArray("features"))
        } catch (e: Throwable) {
            throw ParserException(ReasonCodes.UNSUPPORTED_FEATURE_TYPE,
                "failed to parse features: ${e.message ?: "unknown"}")
        }

        val rules = try {
            if (clearCepRules) emptyList()
            else RuleCompiler.parseRuleDefs(root.optJSONArray("rules"))
        } catch (e: Throwable) {
            throw ParserException(ReasonCodes.UNSUPPORTED_RULE_TYPE,
                "failed to parse rules: ${e.message ?: "unknown"}")
        }

        val disabledRuleIds = stringSet(root, "disabledRuleIds")
        val disabledFeatureIds = stringSet(root, "disabledFeatureIds")

        val metadata = root.optJSONObject("metadata")
        val tenant = metadata?.optString("tenant", null)?.takeIf { it.isNotBlank() }
        val issuer = metadata?.optString("issuer", null)?.takeIf { it.isNotBlank() }

        val sawClearSupersedesRules = clearCepRules &&
            (root.optJSONArray("rules")?.length() ?: 0) > 0
        val sawClearSupersedesFeatures = clearCepFeatures &&
            (root.optJSONArray("features")?.length() ?: 0) > 0

        return ParsedManagedOverlay(
            schemaVersion = schemaVersion,
            cepResetEpoch = cepResetEpoch,
            rulesMode = rulesMode,
            featuresMode = featuresMode,
            clearCepRules = clearCepRules,
            clearCepFeatures = clearCepFeatures,
            engineCadence = engineCadence,
            features = features,
            rules = rules,
            disabledRuleIds = disabledRuleIds,
            disabledFeatureIds = disabledFeatureIds,
            tenant = tenant,
            issuer = issuer,
            rawCanonical = canonical,
            payloadHash = payloadHash,
            sawClearSupersedesRules = sawClearSupersedesRules,
            sawClearSupersedesFeatures = sawClearSupersedesFeatures
        )
    }

    private fun normalizeMode(mode: String?): String? {
        if (mode.isNullOrBlank()) return ParsedManagedOverlay.MODE_MERGE
        return when (mode.uppercase()) {
            ParsedManagedOverlay.MODE_MERGE -> ParsedManagedOverlay.MODE_MERGE
            ParsedManagedOverlay.MODE_REPLACE -> ParsedManagedOverlay.MODE_REPLACE
            else -> null
        }
    }

    private fun stringSet(root: JSONObject, key: String): Set<String> {
        val arr = root.optJSONArray(key) ?: return emptySet()
        val out = LinkedHashSet<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, null)
            if (!s.isNullOrBlank()) out += s
        }
        return out
    }

    private fun looksLikePatch(root: JSONObject): Boolean {
        if (root.has("patch") || root.has("ops") || root.has("operations")) return true
        if (root.has("\$patch") || root.has("\$op")) return true
        // Detect "schemaVersion": "1+patch" or string forms that try to declare a patch path.
        val sv = root.opt("schemaVersion")
        if (sv is String && sv.contains("patch", ignoreCase = true)) return true
        return false
    }
}

class ParserException(
    val reasonCode: String,
    message: String,
    val details: Map<String, String> = emptyMap()
) : RuntimeException(message)
