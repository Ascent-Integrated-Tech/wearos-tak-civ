package com.aitech.weartak_civ.cot

import android.content.SharedPreferences
import org.json.JSONObject

/** Permanent source/UID ownership and terminal deletion records, separate from receive dedup expiry. */
internal class DittoPointIngress(private val preferences: SharedPreferences) {
    private val records = preferences.all.mapValues { it.value as String }.toMutableMap()
    private var failedKey: String? = null
    data class Point(val document: DittoCotDocument, val deleted: Boolean)

    fun decode(raw: Map<String, Any?>): Point {
        val metadata = raw[DittoPointLedger.METADATA] as? Map<*, *>
            ?: throw IllegalArgumentException("Missing WearTAK point contract")
        require((metadata["version"] as? Number)?.toDouble() == 1.0) { "Unsupported WearTAK point version" }
        val deleted = metadata["deleted"] as? Boolean
            ?: throw IllegalArgumentException("Missing WearTAK point lifecycle")
        require(raw["_r"] == deleted) { "Inconsistent WearTAK point lifecycle" }
        val doc = DittoCotDocument.decode(raw + ("_r" to false))
        require(CotMessageParser.isMarkerType(doc.type)) { "WearTAK deletion only supports points" }
        require(metadata["owner"] == doc.author && !doc.author.isNullOrBlank()) {
            "WearTAK point owner mismatch"
        }
        require(metadata["revision"] == doc.fingerprint) { "WearTAK point revision mismatch" }
        if (deleted) {
            val at = (metadata["deletedAt"] as? Number)?.toDouble()
            require(at != null && at.isFinite() && at >= doc.time &&
                at <= 253402300799999.0 && at == at.toLong().toDouble()) { "Invalid point deletion time" }
        }
        return Point(doc, deleted)
    }

    @Synchronized
    fun accept(point: Point): Boolean {
        val doc = point.document
        val previous = records[doc.key]?.let(::JSONObject)
        if (previous != null) {
            require(previous.getString("owner") == doc.author.orEmpty()) { "Cross-owner Ditto point revision" }
            if (previous.getBoolean("deleted")) {
                return point.deleted && previous.getLong("time") == doc.time &&
                    previous.getString("revision") == doc.fingerprint
            }
            val time = previous.getLong("time")
            if (doc.time < time) return false
            if (doc.time == time && previous.getString("revision") != doc.fingerprint) return false
        } else {
            require(records.size < MAX_ENTRIES) { "Ditto permanent point ledger full" }
        }
        val record = JSONObject().put("owner", doc.author.orEmpty()).put("time", doc.time)
            .put("revision", doc.fingerprint).put("deleted", point.deleted)
        val value = record.toString()
        if (records[doc.key] == value) return true
        val bytes = records.values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
        val oldSize = records[doc.key]?.toByteArray(Charsets.UTF_8)?.size ?: 0
        require(bytes - oldSize + record.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "Ditto permanent point ledger byte capacity reached"
        }
        failedKey?.let { failed ->
            val repair = preferences.edit()
            records[failed]?.let { repair.putString(failed, it) } ?: repair.remove(failed)
            check(repair.commit()) { "Could not repair unacknowledged Ditto point watermark" }
            failedKey = null
        }
        val editor = preferences.edit()
        if (!editor.putString(doc.key, value).commit()) {
            failedKey = doc.key
            throw IllegalStateException("Could not persist Ditto point watermark")
        }
        records[doc.key] = value
        return true
    }

    @Synchronized
    fun deleted(doc: DittoCotDocument): Boolean =
        records[doc.key]?.let { JSONObject(it).getBoolean("deleted") } == true

    companion object {
        const val MAX_ENTRIES = 8192
        const val MAX_BYTES = 8 * 1024 * 1024
    }
}
