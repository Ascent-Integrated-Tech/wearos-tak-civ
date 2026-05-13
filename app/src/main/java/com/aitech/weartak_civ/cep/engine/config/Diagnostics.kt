package com.aitech.weartak_civ.cep.engine.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * Structured diagnostic record persisted by the registry whenever a payload is rejected or
 * a bootstrap apply fails. Persistence is required so cold-start fallback events survive
 * process restart (per `[Update v1.1 — U7]`).
 */
data class CepDiagnosticRecord(
    val timestamp: Long,
    val sourceLayer: SourceLayer,
    val reasonCode: String,
    val schemaVersion: Int? = null,
    val payloadHash: String? = null,
    val effectiveHashBefore: String? = null,
    val effectiveHashAfter: String? = null,
    val fallbackDecision: String? = null,
    val pluginId: String? = null,
    /** Plugin-declared version string from the overlay envelope (design §494). */
    val pluginVersion: String? = null,
    val tenant: String? = null,
    val issuer: String? = null,
    val message: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("timestamp", timestamp)
        put("sourceLayer", sourceLayer.name)
        put("reasonCode", reasonCode)
        if (schemaVersion != null) put("schemaVersion", schemaVersion)
        if (payloadHash != null) put("payloadHash", payloadHash)
        if (effectiveHashBefore != null) put("effectiveHashBefore", effectiveHashBefore)
        if (effectiveHashAfter != null) put("effectiveHashAfter", effectiveHashAfter)
        if (fallbackDecision != null) put("fallbackDecision", fallbackDecision)
        if (pluginId != null) put("pluginId", pluginId)
        if (pluginVersion != null) put("pluginVersion", pluginVersion)
        if (tenant != null) put("tenant", tenant)
        if (issuer != null) put("issuer", issuer)
        if (message != null) put("message", message)
    }

    companion object {
        const val FALLBACK_KEPT_PREVIOUS = "kept_previous_effective"
        const val FALLBACK_TO_BUILTINS = "fell_back_to_builtins"

        fun fromJson(obj: JSONObject): CepDiagnosticRecord = CepDiagnosticRecord(
            timestamp = obj.optLong("timestamp", 0L),
            sourceLayer = runCatching { SourceLayer.valueOf(obj.optString("sourceLayer", "BOOTSTRAP")) }
                .getOrDefault(SourceLayer.BOOTSTRAP),
            reasonCode = obj.optString("reasonCode", "unknown"),
            schemaVersion = if (obj.has("schemaVersion") && !obj.isNull("schemaVersion")) obj.optInt("schemaVersion") else null,
            payloadHash = if (obj.has("payloadHash") && !obj.isNull("payloadHash")) obj.optString("payloadHash") else null,
            effectiveHashBefore = if (obj.has("effectiveHashBefore") && !obj.isNull("effectiveHashBefore")) obj.optString("effectiveHashBefore") else null,
            effectiveHashAfter = if (obj.has("effectiveHashAfter") && !obj.isNull("effectiveHashAfter")) obj.optString("effectiveHashAfter") else null,
            fallbackDecision = if (obj.has("fallbackDecision") && !obj.isNull("fallbackDecision")) obj.optString("fallbackDecision") else null,
            pluginId = if (obj.has("pluginId") && !obj.isNull("pluginId")) obj.optString("pluginId") else null,
            pluginVersion = if (obj.has("pluginVersion") && !obj.isNull("pluginVersion")) obj.optString("pluginVersion") else null,
            tenant = if (obj.has("tenant") && !obj.isNull("tenant")) obj.optString("tenant") else null,
            issuer = if (obj.has("issuer") && !obj.isNull("issuer")) obj.optString("issuer") else null,
            message = if (obj.has("message") && !obj.isNull("message")) obj.optString("message") else null
        )
    }
}

/**
 * Persistence-agnostic interface so unit tests can recorded diagnostics in-memory without
 * pulling in the DataStore-backed implementation.
 */
interface CepDiagnosticSink {
    fun record(record: CepDiagnosticRecord)
    fun recent(): List<CepDiagnosticRecord>
}

/** Bounded in-memory ring used by tests and by the persistent sink as a hot cache. */
class InMemoryDiagnosticSink(private val capacity: Int = 64) : CepDiagnosticSink {
    private val buffer = ArrayDeque<CepDiagnosticRecord>(capacity)

    override fun record(record: CepDiagnosticRecord) {
        synchronized(buffer) {
            if (buffer.size >= capacity) buffer.removeFirst()
            buffer.addLast(record)
        }
    }

    override fun recent(): List<CepDiagnosticRecord> = synchronized(buffer) { buffer.toList() }
}

/**
 * JSON helpers for persisting/restoring the bounded diagnostic ring. The on-disk shape is
 *   { "records": [ {...}, {...}, ... ] }
 * with newest-last ordering.
 */
object DiagnosticsCodec {
    fun encode(records: List<CepDiagnosticRecord>): String {
        val arr = JSONArray()
        records.forEach { arr.put(it.toJson()) }
        val root = JSONObject().put("records", arr)
        return root.toString()
    }

    fun decode(json: String?): List<CepDiagnosticRecord> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val root = JSONObject(json)
            val arr = root.optJSONArray("records") ?: return emptyList()
            val out = mutableListOf<CepDiagnosticRecord>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                out += CepDiagnosticRecord.fromJson(obj)
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
