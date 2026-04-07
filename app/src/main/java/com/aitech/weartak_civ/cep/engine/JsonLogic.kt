package com.aitech.weartak_civ.cep.engine

import com.aitech.weartak_civ.cep.util.Stats
import org.json.JSONArray
import org.json.JSONObject

object JsonLogic {
    private fun asDoubleOrKey(fs: FeatureStore, any: Any): Double? =
        when (any) { is Number -> any.toDouble(); is String -> fs.double(any); else -> null }

    fun eval(node: JSONObject, fs: FeatureStore): Boolean {
        return try {
            when {
                node.has("and") -> node.getJSONArray("and").allJson { eval(it as JSONObject, fs) }
                node.has("or")  -> node.getJSONArray("or").anyJson { eval(it as JSONObject, fs) }
                node.has("not") -> !eval(node.getJSONObject("not"), fs)

                node.has("all") -> {
                    val arr = node.getJSONArray("all"); arr.allJson { eval(it as JSONObject, fs) }
                }
                node.has("any") -> {
                    val arr = node.getJSONArray("any"); arr.anyJson { eval(it as JSONObject, fs) }
                }

                node.has("ref") && node.has("is") -> {
                    val key = node.getString("ref")
                    val want = node.get("is")
                    when (want) {
                        is Boolean -> (fs.bool(key) == want)
                        is String  -> (fs.str(key) == want)
                        is Number  -> (fs.double(key)?.let { it == want.toDouble() } ?: false)
                        else -> false
                    }
                }

                node.has("eq_key") -> {
                    val a = node.getJSONArray("eq_key")
                    val k = a.getString(0)
                    val wantAny = a.get(1)
                    when (wantAny) {
                        is Boolean -> (fs.bool(k) == wantAny)
                        is String  -> (fs.str(k) == wantAny)
                        is Number  -> (fs.double(k)?.let { it == wantAny.toDouble() } ?: false)
                        else -> false
                    }
                }

                node.has("ne_key") -> {
                    val a = node.getJSONArray("ne_key")
                    val k = a.getString(0)
                    val wantAny = a.get(1)
                    when (wantAny) {
                        is Boolean -> (fs.bool(k) != wantAny)
                        is String  -> (fs.str(k) != wantAny)
                        is Number  -> (fs.double(k)?.let { it != wantAny.toDouble() } ?: false)
                        else -> false
                    }
                }

                node.has("lte_key") -> {
                    // Phase 4: null feature (missing or stale) → false, not +infinity.
                    val a = node.getJSONArray("lte_key")
                    val x = fs.double(a.getString(0)) ?: return false
                    val y = a.optDouble(1, Double.POSITIVE_INFINITY)
                    x <= y
                }

                node.has("lte_key_fresh") -> {
                    // Phase 4: like lte_key but enforces max_age_sec freshness guard.
                    // Syntax: ["featureKey", threshold, maxAgeSec]
                    // Returns false if feature is missing OR older than maxAgeSec.
                    val a = node.getJSONArray("lte_key_fresh")
                    val x = fs.doubleIfFresh(a.getString(0), a.getDouble(2)) ?: return false
                    val y = a.optDouble(1, Double.POSITIVE_INFINITY)
                    x <= y
                }

                node.has("gte_key") -> {
                    // Phase 4: null feature (missing or stale) → false, not -infinity.
                    val a = node.getJSONArray("gte_key")
                    val x = fs.double(a.getString(0)) ?: return false
                    val y = a.optDouble(1, Double.NEGATIVE_INFINITY)
                    x >= y
                }

                node.has("gte_key_fresh") -> {
                    // Phase 4: like gte_key but enforces max_age_sec freshness guard.
                    // Syntax: ["featureKey", threshold, maxAgeSec]
                    // Returns false if feature is missing OR older than maxAgeSec.
                    val a = node.getJSONArray("gte_key_fresh")
                    val x = fs.doubleIfFresh(a.getString(0), a.getDouble(2)) ?: return false
                    val y = a.optDouble(1, Double.NEGATIVE_INFINITY)
                    x >= y
                }

                node.has("gte_keys") -> {
                    // Phase 4: either key null → false.
                    val a = node.getJSONArray("gte_keys")
                    val x = fs.double(a.getString(0)) ?: return false
                    val y = fs.double(a.getString(1)) ?: return false
                    x >= y
                }

                node.has("lte_keys") -> {
                    // Phase 4: either key null → false.
                    val a = node.getJSONArray("lte_keys")
                    val x = fs.double(a.getString(0)) ?: return false
                    val y = fs.double(a.getString(1)) ?: return false
                    x <= y
                }

                node.has("lt_key") -> {
                    // Phase 4: null feature → false.
                    val a = node.getJSONArray("lt_key")
                    val x = fs.double(a.getString(0)) ?: return false
                    val y = a.getDouble(1)
                    x < y
                }
                node.has("gt_key") -> {
                    // Phase 4: null feature → false.
                    val a = node.getJSONArray("gt_key")
                    val x = fs.double(a.getString(0)) ?: return false
                    val y = a.getDouble(1)
                    x > y
                }
                node.has("between") -> {
                    val a = node.getJSONArray("between")
                    val v = fs.double(a.getString(0)) ?: return false
                    val low = a.getDouble(1); val high = a.getDouble(2)
                    v in low..high
                }
                node.has("zscore_lt") -> {
                    val a = node.getJSONArray("zscore_lt")
                    val seriesKey = a.getString(0)
                    val window = a.getInt(1)
                    val thr = a.getDouble(2)
                    val s = SeriesRepo.get(seriesKey, window) ?: return false
                    val z = Stats.zscore(s) ?: return false
                    z < thr
                }
                node.has("zscore_gt") -> {
                    val a = node.getJSONArray("zscore_gt")
                    val seriesKey = a.getString(0)
                    val window = a.getInt(1)
                    val thr = a.getDouble(2)
                    val s = SeriesRepo.get(seriesKey, window) ?: return false
                    val z = Stats.zscore(s) ?: return false
                    z > thr
                }
                node.has("recently_in") -> {
                    val a = node.getJSONArray("recently_in")
                    val key = a.getString(0); val sec = a.getLong(1)
                    StateRepo.tracker.recentlyIn(key, sec, System.currentTimeMillis())
                }
                else -> false
            }
        } catch (t: Throwable) {
            android.util.Log.w("CEP", "JsonLogic error: $node", t)
            false
        }
    }

    private inline fun JSONArray.allJson(pred: (Any) -> Boolean): Boolean {
        for (i in 0 until length()) if (!pred(get(i))) return false
        return true
    }
    private inline fun JSONArray.anyJson(pred: (Any) -> Boolean): Boolean {
        for (i in 0 until length()) if (pred(get(i))) return true
        return false
    }
}

object SeriesRepo {
    private val cache = mutableMapOf<String, ArrayDeque<Double>>()
    fun add(key: String, value: Double, maxLen: Int) {
        val dq = cache.getOrPut(key) { ArrayDeque() }
        dq.addLast(value)
        while (dq.size > maxLen) dq.removeFirst()
    }
    fun get(key: String, window: Int): List<Double>? = cache[key]?.takeLast(window)
}

object StateRepo {
    val tracker = StateTracker()
}


