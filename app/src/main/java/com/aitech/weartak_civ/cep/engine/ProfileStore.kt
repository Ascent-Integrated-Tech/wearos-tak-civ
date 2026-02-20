package com.aitech.weartak_civ.cep.engine

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.max

class ProfileStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("cep_profile", Context.MODE_PRIVATE)

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
}


