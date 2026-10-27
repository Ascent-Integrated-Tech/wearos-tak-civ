package com.aitech.weartak_civ.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/** A write-ahead journal survives process death, including between saving and writing. */
internal class ChargingWirelessDebugging(private val context: Context) {
    private val journal = context.getSharedPreferences("debug_wireless_originals", Context.MODE_PRIVATE)
    private val resolver = context.contentResolver
    private var observing = false
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            safely { detectOverrides() }
        }
    }

    fun reconcile(plugged: Boolean) {
        if (!hasPermission(context)) {
            Log.i(TAG, "WRITE_SECURE_SETTINGS missing; no settings writes")
            return
        }
        if (!plugged || !DebugStayAwakePreferences(context).wirelessDebuggingEnabled) {
            restore()
            return
        }
        if (!safely {
            if (!observing) {
                observing = true
                KEYS.forEach { resolver.registerContentObserver(Settings.Global.getUriFor(it), false, observer) }
            }
            detectOverrides()
            for (key in KEYS) {
                if (journal.getBoolean("$key.overridden", false)) continue
                val before = Settings.Global.getString(resolver, key)
                if (before == "1") continue
                // contains() distinguishes an absent setting (null) from no saved original.
                if (!journal.contains("$key.pending")) {
                    check(journal.edit().putBoolean("$key.pending", true)
                        .putString("$key.original", before).commit()) { "Cannot journal $key" }
                }
                write(key, before, "1")
            }
        }) stop()
    }

    fun stop() {
        if (observing) {
            if (safely { resolver.unregisterContentObserver(observer) }) observing = false
        }
        restore()
    }

    private fun detectOverrides() {
        for (key in KEYS) {
            if (journal.contains("$key.pending") && Settings.Global.getString(resolver, key) != "1") {
                check(journal.edit().putBoolean("$key.overridden", true).commit()) {
                    "Cannot record external change to $key"
                }
                Log.i(TAG, "$key changed externally; relinquishing ownership")
            }
        }
    }

    private fun restore() {
        if (!hasPermission(context)) return
        // Reverse enable order: wireless ADB before general ADB.
        for (key in KEYS.reversed()) safely {
            if (journal.contains("$key.pending")) {
                val current = Settings.Global.getString(resolver, key)
                val original = journal.getString("$key.original", null)
                if (!journal.getBoolean("$key.overridden", false) && current == "1") {
                    write(key, current, original)
                } else {
                    Log.i(TAG, "Preserving $key=$current (saved=$original)")
                }
                check(journal.edit().remove("$key.pending").remove("$key.original")
                    .remove("$key.overridden").commit()) { "Cannot clear journal for $key" }
            }
        }
    }

    private fun write(key: String, before: String?, after: String?) {
        val accepted = Settings.Global.putString(resolver, key, after)
        val actual = Settings.Global.getString(resolver, key)
        Log.i(TAG, "$key: before=$before requested=$after after=$actual accepted=$accepted")
        check(accepted) { "Firmware rejected $key=$after" }
        check(actual == after) { "Firmware did not retain $key=$after (actual=$actual)" }
    }

    private inline fun safely(action: () -> Unit): Boolean {
        try {
            action()
            return true
        } catch (error: SecurityException) {
            Log.e(TAG, "Secure settings permission denied; pending originals retained", error)
        } catch (error: IllegalStateException) {
            Log.e(TAG, "Secure settings operation failed; pending originals retained", error)
        } catch (error: IllegalArgumentException) {
            Log.e(TAG, "Firmware rejected secure settings operation", error)
        }
        return false
    }

    companion object {
        private const val TAG = "WearTAK-ChargingADB"
        private val KEYS = listOf(Settings.Global.ADB_ENABLED, "adb_wifi_enabled")

        fun hasPermission(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED

        fun requested(context: Context): Boolean =
            hasPermission(context) &&
                DebugStayAwakePreferences(context).wirelessDebuggingEnabled

        fun setEnabled(context: Context, enabled: Boolean) {
            if (enabled && !hasPermission(context)) {
                Log.w(TAG, "Cannot enable wireless debugging without secure settings grant")
                return
            }
            DebugStayAwakePreferences(context).setWirelessDebuggingEnabled(enabled)
            if (!enabled) restorePending(context)
        }

        fun restorePending(context: Context) {
            ChargingWirelessDebugging(context).restore()
        }
    }
}
