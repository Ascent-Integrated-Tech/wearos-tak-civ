package com.aitech.weartak_civ.cep.engine

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlin.math.max

class ProfileStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("cep_profile", Context.MODE_PRIVATE)
    private val spo2BaselineBuckets = 4

    fun hrRestBpm(): Double = prefs.getFloat("hr_rest_bpm", 60f).toDouble()
    fun hrMaxBpm(ageYears: Int?): Double {
        val est = if (ageYears != null) 208.0 - 0.7 * ageYears else 190.0
        val obs = prefs.getFloat("hr_max_observed", 0f).toDouble()
        return max(est, obs)
    }

    fun updateRestCandidate(value: Double) {
        val current = hrRestBpm()
        if (value < current) prefs.edit().putFloat("hr_rest_bpm", value.toFloat()).apply()
    }
    fun updateObservedMax(value: Double) {
        val current = prefs.getFloat("hr_max_observed", 0f).toDouble()
        if (value > current) prefs.edit().putFloat("hr_max_observed", value.toFloat()).apply()
    }

    private fun sanitizeBucket(altitudeBucket: Int): Int = altitudeBucket.coerceIn(0, spo2BaselineBuckets - 1)
    private fun sanitizeFamily(family: String): String = if (family == "walk") "walk" else "resting"
    private fun spo2BaseKey(metric: String, family: String, bucket: Int): String =
        "spo2_${sanitizeFamily(family)}_baseline_${metric}_b${sanitizeBucket(bucket)}"

    fun spo2Baseline(family: String, altitudeBucket: Int): Double =
        prefs.getFloat(spo2BaseKey("pct", family, altitudeBucket), 97.0f).toDouble()

    fun spo2BaselineProvisional(family: String, altitudeBucket: Int): Boolean =
        prefs.getBoolean(spo2BaseKey("provisional", family, altitudeBucket), true)

    fun spo2BaselineSampleCount(family: String, altitudeBucket: Int): Int =
        prefs.getInt(spo2BaseKey("sample_count", family, altitudeBucket), 0)

    fun spo2BaselineAltitudeBucket(): Int = prefs.getInt("spo2_baseline_altitude_bucket", 0)
    fun spo2BaselineFamily(): String = sanitizeFamily(prefs.getString("spo2_baseline_family", "resting") ?: "resting")

    fun userRiskProfile(): String {
        val v = prefs.getString("user_risk_profile", "default") ?: "default"
        return if (v == "default" || v == "trauma" || v == "high_risk") v else "default"
    }

    fun setUserRiskProfile(profile: String) {
        val normalized = if (profile == "default" || profile == "trauma" || profile == "high_risk") profile else "default"
        prefs.edit().putString("user_risk_profile", normalized).apply()
    }

    fun updateSpo2BaselinesEma(
        validPct: Double,
        nowMs: Long,
        altitudeBucket: Int,
        activityState: String,
        motionOk: Boolean,
    ): Boolean {
        if (!motionOk) return false
        if (!validPct.isFinite() || validPct < 85.0 || validPct > 100.0) return false
        val family = when (activityState) {
            "WALKING", "LIGHT_ACTIVITY" -> "walk"
            "STILL" -> "resting"
            else -> null
        }
        if (family == null) {
            Log.d("CEP_SPO2", "baseline_ema_abstain reason=ACTIVITY_UNCLASSIFIED activity=$activityState pct=$validPct bucket=$altitudeBucket")
            return false
        }
        val bucket = sanitizeBucket(altitudeBucket)
        val currentBaseline = spo2Baseline(family, bucket)
        val countKey = spo2BaseKey("sample_count", family, bucket)
        val count = prefs.getInt(countKey, 0)
        val provisionalKey = spo2BaseKey("provisional", family, bucket)
        val alpha = 2.0 / (288.0 + 1.0)
        val next = if (count < 20) {
            ((currentBaseline * count) + validPct) / (count + 1)
        } else {
            alpha * validPct + (1.0 - alpha) * currentBaseline
        }
        val nextCount = if (count < 20) count + 1 else count
        val provisional = nextCount < 20
        prefs.edit()
            .putFloat(spo2BaseKey("pct", family, bucket), next.toFloat())
            .putInt(countKey, nextCount)
            .putBoolean(provisionalKey, provisional)
            .putLong(spo2BaseKey("updated_ts", family, bucket), nowMs)
            .putInt("spo2_baseline_altitude_bucket", bucket)
            .putString("spo2_baseline_family", family)
            .putLong("spo2_baseline_updated_ts", nowMs)
            .apply()
        Log.d("CEP_SPO2", "baseline_ema pct=$validPct family=$family bucket=$bucket new=$next count=$nextCount provisional=$provisional")
        return true
    }

    fun resetSpo2Baselines() {
        val e = prefs.edit()
        for (bucket in 0 until spo2BaselineBuckets) {
            listOf("resting", "walk").forEach { family ->
                e.putInt(spo2BaseKey("sample_count", family, bucket), 0)
                e.putBoolean(spo2BaseKey("provisional", family, bucket), true)
            }
        }
        e.apply()
    }
}


