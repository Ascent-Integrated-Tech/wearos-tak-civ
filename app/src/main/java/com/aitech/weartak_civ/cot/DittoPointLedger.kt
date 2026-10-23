package com.aitech.weartak_civ.cot

import android.content.SharedPreferences
import org.json.JSONObject

/** Shared-key mission writers are trusted; these records enforce application ownership, not signatures. */
internal class DittoPointLedger(private val preferences: SharedPreferences) {
    private val records = preferences.all.mapValues { it.value as String }.toMutableMap()
    private var failedKey: String? = null

    @Synchronized
    fun owns(uid: String): Boolean = DittoCotDocument.digest(uid) in records

    @Synchronized
    fun created(uid: String, owner: String) {
        require(uid.isNotBlank() && uid.length <= 512 && owner.isNotBlank())
        val key = DittoCotDocument.digest(uid)
        val existing = read(key)
        require(existing == null) { "Ditto point UID already used; create a new UID" }
        require(records.size < MAX_ENTRIES) { "Ditto local point ledger full" }
        save(key, JSONObject().put("uid", uid).put("owner", owner).put("deleted", false))
    }

    @Synchronized
    fun publish(document: Map<String, Any?>, write: (Map<String, Any?>) -> Unit): Boolean {
        val decoded = DittoCotDocument.decode(document)
        val key = DittoCotDocument.digest(decoded.uid)
        val record = read(key) ?: return false
        require(record.getString("owner") == decoded.author) { "Ditto local point owner mismatch" }
        if (record.getBoolean("deleted")) return false
        require(CotMessageParser.isMarkerType(decoded.type)) { "Not a supported Ditto point" }
        val previous = record.optJSONObject("document")
        if (previous != null) {
            val prior = DittoCotDocument.decode(toMap(previous))
            require(decoded.peer == prior.peer) { "Ditto point origin changed; create a new UID" }
            if (decoded.time < prior.time) return false
            require(decoded.time != prior.time || decoded.fingerprint == prior.fingerprint) {
                "Conflicting equal-time local point revision"
            }
        }
        val metadata = mapOf(
            "version" to 1, "owner" to decoded.author,
            "revision" to decoded.fingerprint, "deleted" to false
        )
        val doc = document + (METADATA to metadata)
        record.put("document", JSONObject(doc)).put("pending", true)
        save(key, record)
        flush(key, record, write)
        return true
    }

    /** Persists intent before local removal; unpublished points close without a mesh tombstone. */
    @Synchronized
    fun delete(uid: String, owner: String, now: Long) {
        val key = DittoCotDocument.digest(uid)
        val record = read(key) ?: return
        require(record.getString("owner") == owner) { "Cannot delete another local point owner" }
        if (record.getBoolean("deleted")) return
        val document = record.optJSONObject("document")
        if (document != null) {
            val metadata = document.getJSONObject(METADATA)
            metadata.put("deleted", true).put("deletedAt", maxOf(now, document.getLong("b")))
            document.put("_r", true).put("visible", false).put("f", false)
            record.put("pending", true)
        }
        record.put("deleted", true)
        save(key, record)
    }

    @Synchronized
    fun retry(write: (Map<String, Any?>) -> Unit, onError: (Exception) -> Unit) {
        records.keys.toList().forEach { key ->
            try {
                val record = requireNotNull(read(key))
                if (record.optBoolean("pending")) flush(key, record, write)
            } catch (error: Exception) {
                onError(error)
            }
        }
    }

    private fun flush(key: String, record: JSONObject, write: (Map<String, Any?>) -> Unit) {
        write(toMap(record.getJSONObject("document")))
        record.put("pending", false)
        save(key, record)
    }

    private fun read(key: String): JSONObject? = records[key]?.let(::JSONObject)

    private fun save(key: String, record: JSONObject) {
        val value = record.toString()
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_RECORD_BYTES) { "Ditto point record too large" }
        val oldSize = records[key]?.toByteArray(Charsets.UTF_8)?.size ?: 0
        val bytes = records.values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
        require(bytes - oldSize + value.toByteArray(Charsets.UTF_8).size <= MAX_TOTAL_BYTES) {
            "Ditto point ledger byte capacity reached"
        }
        failedKey?.let { failed ->
            val repair = preferences.edit()
            records[failed]?.let { repair.putString(failed, it) } ?: repair.remove(failed)
            check(repair.commit()) { "Could not repair unacknowledged Ditto point intent" }
            failedKey = null
        }
        val editor = preferences.edit()
        if (!editor.putString(key, value).commit()) {
            failedKey = key
            throw IllegalStateException("Could not persist Ditto point intent")
        }
        records[key] = value
    }

    companion object {
        const val METADATA = "weartakPoint"
        const val MAX_ENTRIES = 4096
        const val MAX_RECORD_BYTES = 384 * 1024
        const val MAX_TOTAL_BYTES = 8 * 1024 * 1024

        private fun toMap(json: JSONObject): Map<String, Any?> = json.keys().asSequence().associateWith {
            when (val value = json.get(it)) {
                is JSONObject -> toMap(value)
                JSONObject.NULL -> null
                else -> value
            }
        }
    }
}
