package com.aitech.weartak_civ.cep.engine

import android.util.Log
import org.greenrobot.eventbus.android.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow
import kotlin.math.max


object DerivedEval {

    private const val TAG = "CEP"

    private inline fun logDerived(id: String, value: Any?) {
        // Avoid noise in release builds
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "[CEP][DERIVED][DerivedEval] $id=$value")
        }

        //Log.d(TAG, "[CEP][DERIVED][DerivedEval] $id=$value")
    }

    fun eval(def: FeatureDef.Derived, fs: FeatureStore, tsMs: Long = System.currentTimeMillis()) {
        val id = def.id
        val expr = def.expr

        // ---------------------------
        // 1) Specialized handlers
        // ---------------------------
        when {
            expr.has("wind_chill") -> {
                val cfg = expr.getJSONObject("wind_chill")
                val t = fs.double(cfg.getString("temp_key")) ?: return
                val w = fs.double(cfg.getString("wind_key")) ?: return
                // m/s → km/h for Canadian wind-chill
                val v = w * 3.6
                val wc = if (v <= 4.8) t else 13.12 + 0.6215 * t - 11.37 * v.pow(0.16) + 0.3965 * t * v.pow(0.16)
                logDerived(id, wc)
                fs.putDerived(id, wc, tsMs)
                return
            }
            expr.has("zone_min_bpm") -> {
                val cfg = expr.getJSONObject("zone_min_bpm")
                val rest = fs.double(cfg.optString("profile_rest_key", "profile.hr_rest_bpm")) ?: 60.0
                val maxHr = fs.double(cfg.optString("profile_max_key",  "profile.hr_max_bpm")) ?: 190.0
                val pct  = cfg.getDouble("lower_pct")
                val bpm = ZoneHelper.karvonenMinBpm(rest, maxHr, pct)
                logDerived(id, bpm)
                fs.putDerived(id, bpm, tsMs)
                return
            }
            expr.has("mark_state") -> {
                val stateKey = expr.getString("mark_state")
                val value = fs.bool(stateKey) == true
                StateRepo.tracker.mark(stateKey, value, System.currentTimeMillis())
                logDerived(id, value)
                fs.putDerived(id, value, tsMs)
                return
            }
            expr.has("karvonen_ratio") -> {
                val cfg = expr.getJSONObject("karvonen_ratio")
                val hr    = fs.double(cfg.optString("hr_key",   "features.heart_rate")) ?: return
                val hrRes = fs.double(cfg.optString("rest_key", "profile.hr_rest_bpm")) ?: 60.0
                val hrMax = fs.double(cfg.optString("max_key",  "profile.hr_max_bpm"))  ?: 190.0
                val minDen = cfg.optDouble("min_den", 1.0)
                val denom = kotlin.math.max(minDen, hrMax - hrRes)
                val ratio = (hr - hrRes) / denom
                logDerived(id, ratio)
                fs.putDerived(id, ratio, tsMs)
                return
            }
        }

        // ---------------------------
        // 2) Generic evaluation
        // ---------------------------

        // (a) Categorical → string label (used by features.cold.risk_state, etc.)
        if (expr.has("categorical")) {
            val label = evalCategorical(expr.getJSONObject("categorical"), fs)
            if (label != null) {
                logDerived(id, label)
                fs.putDerived(id, label, tsMs)
                return
            }
        }

        // (b) Try numeric math (div/mul/sub/add/max/min/sum/case + { "key": ... })
        evalNumber(expr, fs)?.let { num ->
            logDerived(id, num)
            fs.putDerived(id, num, tsMs)
            return
        }

        // (c) Try boolean logic (lte/gte/lt/gt/eq/and/or/not/ref)
        evalBoolean(expr, fs)?.let { bool ->
            logDerived(id, bool)
            fs.putDerived(id, bool, tsMs)
            return
        }

        // (d) Last resort: legacy JsonLogic (if you still use it for some forms)
        val res = JsonLogic.eval(expr, fs)
        logDerived(id, res)
        fs.putDerived(id, res, tsMs)
    }


    private fun evalNumber(node: Any?, fs: FeatureStore): Double? {
        return when (node) {
            null -> null
            is Number -> node.toDouble()
            is JSONObject -> {
                // { "key": "features.something" }
                if (node.has("key")) return fs.double(node.getString("key"))

                fun asNum(any: Any?): Double? = evalNumber(any, fs)

                when {
                    node.has("div") -> {
                        val arr = node.getJSONArray("div")
                        val a = asNum(arr.opt(0)) ?: return null
                        val b = max(1e-9, asNum(arr.opt(1)) ?: return null)
                        a / b
                    }
                    node.has("mul") -> {
                        val arr = node.getJSONArray("mul")
                        var acc = 1.0
                        for (i in 0 until arr.length()) acc *= (asNum(arr.opt(i)) ?: return null)
                        acc
                    }
                    node.has("sub") -> {
                        val arr = node.getJSONArray("sub")
                        val a = asNum(arr.opt(0)) ?: return null
                        val b = asNum(arr.opt(1)) ?: return null
                        a - b
                    }
                    node.has("add") -> {
                        val arr = node.getJSONArray("add")
                        var acc = 0.0
                        for (i in 0 until arr.length()) acc += (asNum(arr.opt(i)) ?: return null)
                        acc
                    }
                    node.has("max") -> {
                        val arr = node.getJSONArray("max")
                        var best = Double.NEGATIVE_INFINITY
                        for (i in 0 until arr.length()) {
                            val v = asNum(arr.opt(i)) ?: return null
                            if (v > best) best = v
                        }
                        best
                    }
                    node.has("min") -> {
                        val arr = node.getJSONArray("min")
                        var best = Double.POSITIVE_INFINITY
                        for (i in 0 until arr.length()) {
                            val v = asNum(arr.opt(i)) ?: return null
                            if (v < best) best = v
                        }
                        best
                    }
                    node.has("sum") -> {
                        val arr = node.getJSONArray("sum")
                        var acc = 0.0
                        for (i in 0 until arr.length()) acc += (asNum(arr.opt(i)) ?: return null)
                        acc
                    }
                    node.has("case") -> {
                        val arr = node.getJSONArray("case")
                        // Iterate in order; take first matching branch; else if present
                        var elseVal: Double? = null
                        for (i in 0 until arr.length()) {
                            val br = arr.getJSONObject(i)
                            if (br.has("else")) {
                                elseVal = asNum(br.get("else"))
                                continue
                            }
                            val cond = evalBoolean(br, fs) ?: false
                            if (cond) return asNum(br.get("then"))
                        }
                        elseVal
                    }
                    else -> null
                }
            }
            is JSONArray -> {
                // Convenience: sum array if purely numeric
                var acc = 0.0
                for (i in 0 until node.length()) acc += (evalNumber(node.opt(i), fs) ?: return null)
                acc
            }
            else -> null
        }
    }

    // ------------------------------------------------------------------------
    // Boolean evaluator: supports lte/gte/lt/gt/eq/and/or/not and { "ref":"k","is":true }
    // ------------------------------------------------------------------------
    private fun evalBoolean(node: Any?, fs: FeatureStore): Boolean? {
        return when (node) {
            null -> null
            is Boolean -> node
            is JSONObject -> {
                // Compatibility: { "ref": "features.xx", "is": true/false }
                if (node.has("ref") && node.has("is")) {
                    val v = fs.any(node.getString("ref"))
                    return v == node.get("is")
                }

                fun cmp2(op: String, f: (Double, Double) -> Boolean): Boolean? {
                    val arr = node.getJSONArray(op)
                    val a = evalNumber(arr.opt(0), fs) ?: return null
                    val b = evalNumber(arr.opt(1), fs) ?: return null
                    return f(a, b)
                }

                when {
                    node.has("lte") -> return cmp2("lte") { a, b -> a <= b }
                    node.has("gte") -> return cmp2("gte") { a, b -> a >= b }
                    node.has("lt")  -> return cmp2("lt")  { a, b -> a <  b }
                    node.has("gt")  -> return cmp2("gt")  { a, b -> a >  b }
                    node.has("eq")  -> {
                        val arr = node.getJSONArray("eq")
                        // try numeric first, else raw compare
                        val ln = evalNumber(arr.opt(0), fs)
                        val rn = evalNumber(arr.opt(1), fs)
                        return if (ln != null && rn != null) ln == rn else arr.opt(0) == arr.opt(1)
                    }
                    node.has("and") -> {
                        val arr = node.getJSONArray("and")
                        for (i in 0 until arr.length()) if (evalBoolean(arr.opt(i), fs) != true) return false
                        return true
                    }
                    node.has("or") -> {
                        val arr = node.getJSONArray("or")
                        for (i in 0 until arr.length()) if (evalBoolean(arr.opt(i), fs) == true) return true
                        return false
                    }
                    node.has("not") -> {
                        val inner = node.get("not")
                        return evalBoolean(inner, fs)?.let { !it }
                    }
                    else -> null
                }
            }
            is JSONArray -> null
            else -> null
        }
    }

    // ------------------------------------------------------------------------
    // Categorical: maps a numeric key to a label based on bin lower-bounds.
    // { "categorical": { "key":"features.cold.score", "bins":[0,4,7,11], "labels":["NORMAL","WARN","ALERT","EMERGENCY"] } }
    // ------------------------------------------------------------------------
    private fun evalCategorical(cfg: JSONObject, fs: FeatureStore): String? {
        val v = evalNumber(cfg.get("key"), fs) ?: return null
        val bins = cfg.getJSONArray("bins")
        val labels = cfg.getJSONArray("labels")
        // Pick the largest index i where v >= bins[i]
        var idx = 0
        for (i in 0 until bins.length()) {
            if (v >= bins.getDouble(i)) idx = i
        }
        // Clamp to labels length - 1
        val safeIdx = idx.coerceIn(0, labels.length() - 1)
        return labels.getString(safeIdx)
    }
}
