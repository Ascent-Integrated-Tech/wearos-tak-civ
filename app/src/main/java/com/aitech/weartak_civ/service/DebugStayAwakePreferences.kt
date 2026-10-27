package com.aitech.weartak_civ.service

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal class DebugStayAwakePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("debug_stay_awake", Context.MODE_PRIVATE)

    init {
        synchronized(DebugStayAwakePreferences::class.java) {
            if (!preferences.contains(CHARGING_KEY)) {
                val charging = preferences.getBoolean("when_charging", false) ||
                    preferences.getBoolean("wireless_when_charging", false)
                check(preferences.edit().putBoolean(CHARGING_KEY, charging).commit()) {
                    "Unable to migrate While charging"
                }
            }
        }
    }

    // Reuse the legacy session key so enabled users migrate regardless of the old update opt-in.
    val enabled: Boolean get() = preferences.getBoolean("session_active", false)
    val chargingEnabled: Boolean get() = preferences.getBoolean(CHARGING_KEY, false)

    val chargingEnabledFlow get() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == CHARGING_KEY) trySend(chargingEnabled)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(chargingEnabled)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun setChargingEnabled(enabled: Boolean) {
        check(preferences.edit().putBoolean(CHARGING_KEY, enabled).commit()) {
            "Unable to save While charging"
        }
    }

    val enabledFlow get() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "session_active") trySend(enabled)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(enabled)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun recordSession(active: Boolean) {
        check(preferences.edit()
            .putBoolean("session_active", active)
            .commit()) {
            "Unable to save Stay awake session state"
        }
    }

    companion object {
        const val CHARGING_KEY = "developer_when_charging"
    }
}
