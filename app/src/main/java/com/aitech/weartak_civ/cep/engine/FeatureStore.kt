package com.aitech.weartak_civ.cep.engine

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

// FeatureStore.kt  (patch)
class FeatureStore {
    // values + timestamps
    private val kv = ConcurrentHashMap<String, Any?>()
    private val ts = ConcurrentHashMap<String, Long>()

    // per-tick collection of newly computed (derived) features
    private val tickDerived = mutableListOf<Pair<String, Any?>>()

    // If it's already a dotted path (e.g., "ambient.temp_c" or "features.hr.hrr"), don't prefix.
    fun namespacedId(id: String): String =
        if (id.contains(".")) id else "features.$id"

    /** RAW/Generic write (do NOT auto-log). Use this for raw inputs. */
    fun put(id: String, value: Any?, nowMs: Long = System.currentTimeMillis()) {
        val k = namespacedId(id)
        kv[k] = value
        ts[k] = nowMs
    }

    /** Batch RAW write. */
    fun putAll(map: Map<String, Any?>, nowMs: Long = System.currentTimeMillis()) {
        map.forEach { (k, v) -> put(k, v, nowMs) }
    }

    /** DERIVED write (will be logged once per tick). */
    fun putDerived(id: String, value: Any?, nowMs: Long = System.currentTimeMillis()) {
        val k = namespacedId(id)
        kv[k] = value
        ts[k] = nowMs
        tickDerived.add(k to value)
    }

    /** Clear a key (useful to invalidate stale signals). */
    fun clear(key: String) {
        val k = resolve(key)
        kv.remove(k); ts.remove(k)
    }

    /** Age in seconds of a key (for freshness guards). */
    fun ageSeconds(key: String, nowMs: Long = System.currentTimeMillis()): Double? {
        val k = resolve(key)
        val t = ts[k] ?: return null
        return (nowMs - t) / 1000.0
    }

    // -------- lookup helpers --------

    private fun resolve(key: String): String {
        if (kv.containsKey(key)) return key
        val withPrefix = if (key.startsWith("features.")) key else "features.$key"
        if (kv.containsKey(withPrefix)) return withPrefix
        val noPrefix = key.removePrefix("features.")
        return if (kv.containsKey(noPrefix)) noPrefix else key
    }

    fun double(key: String): Double? = (kv[resolve(key)] as? Number)?.toDouble()
    fun bool(key: String): Boolean? = kv[resolve(key)] as? Boolean
    fun str(key: String): String? = kv[resolve(key)] as? String
    fun any(key: String): Any? = kv[resolve(key)]

    fun snapshot(keys: List<String>): Map<String, Any?> =
        if (keys.isEmpty()) kv.toMap() else keys.associateWith { kv[resolve(it)] }

    // -------- per-tick logging --------

    fun beginTick() {
        tickDerived.clear()
    }

    /** One compact log line with all derived writes this tick. */
    fun logDerivedTick(tag: String = "CEP") {
        if (tickDerived.isEmpty()) return
        val body = tickDerived.joinToString { (k, v) -> "$k=$v" }
        Log.d(tag, "[CEP][DERIVED] $body")
    }
}