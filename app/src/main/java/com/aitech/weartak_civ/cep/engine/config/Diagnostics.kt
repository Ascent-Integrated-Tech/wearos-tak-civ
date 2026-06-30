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
        putIfNotNull("schemaVersion", schemaVersion)
        putIfNotNull("payloadHash", payloadHash)
        putIfNotNull("effectiveHashBefore", effectiveHashBefore)
        putIfNotNull("effectiveHashAfter", effectiveHashAfter)
        putIfNotNull("fallbackDecision", fallbackDecision)
        putIfNotNull("pluginId", pluginId)
        putIfNotNull("pluginVersion", pluginVersion)
        putIfNotNull("tenant", tenant)
        putIfNotNull("issuer", issuer)
        putIfNotNull("message", message)
    }

    companion object {
        const val FALLBACK_KEPT_PREVIOUS = "kept_previous_effective"
        const val FALLBACK_TO_BUILTINS = "fell_back_to_builtins"

        fun fromJson(obj: JSONObject): CepDiagnosticRecord = CepDiagnosticRecord(
            timestamp = obj.optLong("timestamp", 0L),
            sourceLayer = runCatching { SourceLayer.valueOf(obj.optString("sourceLayer", "BOOTSTRAP")) }
                .getOrDefault(SourceLayer.BOOTSTRAP),
            reasonCode = obj.optString("reasonCode", "unknown"),
            schemaVersion = obj.optIntOrNull("schemaVersion"),
            payloadHash = obj.optStringOrNull("payloadHash"),
            effectiveHashBefore = obj.optStringOrNull("effectiveHashBefore"),
            effectiveHashAfter = obj.optStringOrNull("effectiveHashAfter"),
            fallbackDecision = obj.optStringOrNull("fallbackDecision"),
            pluginId = obj.optStringOrNull("pluginId"),
            pluginVersion = obj.optStringOrNull("pluginVersion"),
            tenant = obj.optStringOrNull("tenant"),
            issuer = obj.optStringOrNull("issuer"),
            message = obj.optStringOrNull("message")
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

private fun JSONObject.putIfNotNull(name: String, value: Any?) {
    if (value != null) put(name, value)
}

private fun JSONObject.optStringOrNull(name: String): String? =
    if (has(name) && !isNull(name)) optString(name) else null

private fun JSONObject.optIntOrNull(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null
