package com.aitech.weartak_civ.service

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal class DebugStayAwakePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("debug_stay_awake", Context.MODE_PRIVATE)

    // Reuse the legacy session key so enabled users migrate regardless of the old update opt-in.
    val enabled: Boolean get() = preferences.getBoolean("session_active", false)

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
}
