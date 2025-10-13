package com.aitech.weartak_civ.cep.engine

import org.json.JSONObject
import kotlin.math.pow

object DerivedEval {

    fun eval(def: FeatureDef.Derived, fs: FeatureStore) {
        val id = def.id
        val expr = def.expr

        // Common forms:
        // { "wind_chill": { "temp_key": "ambient.temp_c", "wind_key": "ambient.wind_mps" } }
        // { "or": [ { "lte_key": ["ambient.temp_c", 5] }, { "lte_key": ["env.wind_chill_c", 0] } ] }

        when {
            expr.has("wind_chill") -> {
                val cfg = expr.getJSONObject("wind_chill")
                val t = fs.double(cfg.getString("temp_key")) ?: return
                val w = fs.double(cfg.getString("wind_key")) ?: return
                // Convert m/s → km/h for Canadian wind-chill
                val v = w * 3.6
                val wc = if (v <= 4.8) t else 13.12 + 0.6215*t - 11.37*v.pow(0.16) + 0.3965*t*v.pow(0.16)
                fs.put(id, wc)
            }
            expr.has("zone_min_bpm") -> {
                val cfg = expr.getJSONObject("zone_min_bpm")
                val rest = fs.double(cfg.optString("profile_rest_key", "profile.hr_rest_bpm")) ?: 60.0
                val max  = fs.double(cfg.optString("profile_max_key",  "profile.hr_max_bpm")) ?: 190.0
                val pct  = cfg.getDouble("lower_pct")
                val bpm = ZoneHelper.karvonenMinBpm(rest, max, pct)
                fs.put(id, bpm)
            }
            expr.has("mark_state") -> {
                val stateKey = expr.getString("mark_state")
                val value = fs.bool(stateKey) == true
                StateRepo.tracker.mark(stateKey, value, System.currentTimeMillis())
                fs.put(id, value)
            }
            else -> {
                // boolean expressions evaluated into id as boolean
                val res = JsonLogic.eval(expr, fs)
                fs.put(id, res)
            }
        }
    }
}


