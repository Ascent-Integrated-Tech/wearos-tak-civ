package com.aitech.weartak_civ.service

import android.content.Context
import android.provider.Settings

internal class DebugStayAwakePreferences(private val context: Context) {
    private val preferences = context.getSharedPreferences("debug_stay_awake", Context.MODE_PRIVATE)

    var resumeAfterUpdate: Boolean
        get() = preferences.getBoolean("resume_after_update", false)
        set(value) {
            check(preferences.edit().putBoolean("resume_after_update", value).commit()) {
                "Unable to save Stay awake update preference"
            }
        }

    fun recordSession(active: Boolean) {
        check(preferences.edit()
            .putBoolean("session_active", active)
            .putInt("session_boot", bootCount())
            .commit()) {
            "Unable to save Stay awake session state"
        }
    }

    fun shouldResumeAfterUpdate(): Boolean {
        // Session intent survives package replacement, but must not carry across a reboot.
        val boot = bootCount()
        return resumeAfterUpdate && preferences.getBoolean("session_active", false) &&
            boot >= 0 && preferences.getInt("session_boot", -1) == boot
    }

    private fun bootCount() =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
}
