package com.aitech.weartak_civ.cep.engine

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

// FeatureStore.kt  (patch)
class FeatureStore {
    // values + timestamps
    private val kv = ConcurrentHashMap<String, Any?>()
    private val ts = ConcurrentHashMap<String, Long>()

    // per-tick collection of newly computed (derived) features.
    // CopyOnWriteArrayList: Phase 3 feature coroutines call putDerived() concurrently
    // while the rule-eval coroutine calls beginTick()/logDerivedTick().  A plain
    // mutableListOf() (ArrayList) is not thread-safe and corrupts under concurrent
    // add() calls, leaving null slots that crash the joinToString destructure lambda.
    private val tickDerived = CopyOnWriteArrayList<Pair<String, Any?>>()

    // If it's already a dotted path (e.g., "ambient.temp_c" or "features.hr.hrr"), don't prefix.
    fun namespacedId(id: String): String =
        if (id.contains(".")) id else "features.$id"

    /** RAW/Generic write (do NOT auto-log). Use this for raw inputs. */
    fun put(id: String, value: Any?, nowMs: Long = System.currentTimeMillis()) {
        if (value == null) return   // ConcurrentHashMap rejects null values
        if (value is Double && value.isNaN()) {
            Log.w("CEP", "[FeatureStore] NaN rejected for key=$id — retaining last valid value")
            return
        }
        if (value is Float && value.isNaN()) {
            Log.w("CEP", "[FeatureStore] NaN rejected for key=$id — retaining last valid value")
            return
        }
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
        if (value == null) return   // ConcurrentHashMap rejects null values; callers should guard upstream
        if (value is Double && value.isNaN()) {
            Log.w("CEP", "[FeatureStore] NaN rejected (derived) for key=$id — retaining last valid value")
            return
        }
        if (value is Float && value.isNaN()) {
            Log.w("CEP", "[FeatureStore] NaN rejected (derived) for key=$id — retaining last valid value")
            return
        }
        val k = namespacedId(id)
        kv[k] = value
        ts[k] = nowMs
        tickDerived.add(k to value)
        //if (k.startsWith("features.pressure.")) Log.d("CEP_P6", "[DERIVED] $k=$value")
    }

    /** Clear a key (useful to invalidate stale signals). */
    fun clear(key: String) {
        val k = resolve(key)
        kv.remove(k); ts.remove(k)
    }

    fun latestAsDouble(id: String): Double? {
        val v = kv[id] ?: return null

        return when (v) {
            is Double  -> v
            is Float   -> v.toDouble()
            is Int     -> v.toDouble()
            is Long    -> v.toDouble()
            is Number  -> v.toDouble()
            is String  -> v.toDoubleOrNull()
            is Boolean -> if (v) 1.0 else 0.0   // same Boolean-coercion as double()
            else       -> null
        }
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

    fun double(key: String): Double? {
        return when (val v = kv[resolve(key)]) {
            is Number  -> v.toDouble()
            is Boolean -> if (v) 1.0 else 0.0   // derived bool features stored as Boolean; gte_key/lte_key need a Double
            else       -> null
        }
    }
    fun bool(key: String): Boolean? = kv[resolve(key)] as? Boolean
    fun str(key: String): String? = kv[resolve(key)] as? String
    fun any(key: String): Any? = kv[resolve(key)]

    /**
     * Phase 4 — Freshness-aware read.
     *
     * Returns the Double value for [key] only if it exists AND its timestamp is within
     * [maxAgeSec] seconds of [nowMs].  Returns null if the key is missing OR if its
     * last-write timestamp is older than [maxAgeSec], so callers (JsonLogic operators)
     * can treat null as "data too stale to act on".
     */
    fun doubleIfFresh(key: String, maxAgeSec: Double, nowMs: Long = System.currentTimeMillis()): Double? {
        val age = ageSeconds(key, nowMs) ?: return null   // key missing → null
        if (age > maxAgeSec) return null                   // stale → null
        return double(key)
    }

    fun snapshot(keys: List<String>): Map<String, Any?> =
        if (keys.isEmpty()) kv.toMap() else keys.associateWith { kv[resolve(it)] }

    /**
     * Returns a detached snapshot of all current key-value pairs.
     *
     * Namespace prefixes are stripped from keys in the returned map so assertions can use
     * clean feature ids regardless of how this store is scoped internally.
     *
     * @return A copied map of current key/value entries suitable for debug and tests.
     */
    fun debugSnapshot(): Map<String, Any?> {
        val prefix = namespacedId("")
        return kv.entries.associate { (k, v) ->
            val stripped = if (k.startsWith(prefix)) k.removePrefix(prefix) else k
            stripped to v
        }
    }

    // -------- per-tick logging --------

    fun beginTick() {
        tickDerived.clear()
    }

    /** One compact log line with all derived writes this tick. */
    fun logDerivedTick(tag: String = "CEP") {
        if (tickDerived.isEmpty()) return
        // Use entry.first/entry.second instead of destructuring: the destructure lambda
        // generates a non-null parameter check that crashes if a null Pair somehow
        // reaches the iterator (defensive, in addition to the CopyOnWriteArrayList fix).
        val body = tickDerived.joinToString { entry -> "${entry.first}=${entry.second}" }
        //Log.i(tag, "[CEP][DERIVED] $body")
    }
}
