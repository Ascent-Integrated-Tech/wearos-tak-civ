package com.aitech.weartak_civ.cep.engine

import com.aitech.weartak_civ.cep.model.CepEvent
import java.util.concurrent.ConcurrentHashMap

class FeatureStore {
    private val kv = ConcurrentHashMap<String, Any?>()

    fun namespacedId(id: String): String = if (id.startsWith("features.")) id else "features.$id"

    fun put(id: String, value: Any?) { kv[namespacedId(id)] = value }
    fun putAll(map: Map<String, Any?>) {
        map.forEach { (k, v) ->
            val key = if (k.startsWith("features.") || k.contains(".")) k else "features.$k"
            kv[key] = v
        }
    }

    private fun resolve(key: String): String {
        if (kv.containsKey(key)) return key
        val noPrefix = if (key.startsWith("features.")) key.removePrefix("features.") else key
        if (kv.containsKey(noPrefix)) return noPrefix
        val withPrefix = if (key.startsWith("features.")) key else "features.$key"
        return withPrefix
    }

    fun double(key: String): Double? = (kv[resolve(key)] as? Number)?.toDouble()
    fun bool(key: String): Boolean? = kv[resolve(key)] as? Boolean
    fun str(key: String): String? = kv[resolve(key)] as? String
    fun any(key: String): Any? = kv[resolve(key)]

    fun snapshot(keys: List<String>): Map<String, Any?> =
        if (keys.isEmpty()) kv.toMap() else keys.associateWith { kv[resolve(it)] }

    fun putRaw(evt: CepEvent<Any>) {
        // Populate simple “latest” keys by convention (optional)
        // Example: if topic == heart_rate => put("heart_rate", payload.bpm)
        // Left intentionally blank; WindowStore handles ingestion primarily.
    }
}


