package com.aitech.weartak_civ.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat

/** Watches power only while the service manager runs; the existing service owns all locks. */
class ChargingStayAwake(private val context: Context) {
    private val preferences = DebugStayAwakePreferences(context)
    private val store = context.getSharedPreferences("debug_stay_awake", Context.MODE_PRIVATE)
    private var monitoring = false
    private val wirelessDebugging = ChargingWirelessDebugging(context)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "when_charging" || key == "wireless_when_charging") reconcile(powerConnected)
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!monitoring) return
            val connected = when (intent.action) {
                Intent.ACTION_POWER_DISCONNECTED -> false
                Intent.ACTION_BATTERY_CHANGED -> plugged(intent)
                else -> isPlugged(context)
            }
            val changed = powerConnected != connected
            powerConnected = connected
            if (changed || intent.action == Intent.ACTION_POWER_CONNECTED) reconcile(connected)
        }
    }

    fun start() {
        if (monitoring) return
        monitoring = true
        store.registerOnSharedPreferenceChangeListener(listener)
        val sticky = ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }, ContextCompat.RECEIVER_EXPORTED)
        powerConnected = plugged(sticky)
        reconcile(powerConnected)
    }

    fun stop() {
        if (!monitoring) return
        monitoring = false
        powerConnected = false
        store.unregisterOnSharedPreferenceChangeListener(listener)
        context.unregisterReceiver(receiver)
        wirelessDebugging.stop()
        if (!preferences.enabled) context.stopService(Intent(context, DebugStayAwakeService::class.java))
    }

    private fun reconcile(plugged: Boolean) {
        if (!monitoring) return
        val connected = plugged && isPlugged(context)
        wirelessDebugging.reconcile(connected)
        if (preferences.enabled) return
        if ((preferences.chargingEnabled || ChargingWirelessDebugging.requested(context)) && connected) {
            DebugStayAwakeService.restoreIfEnabled(context)
        } else {
            context.stopService(Intent(context, DebugStayAwakeService::class.java))
        }
    }

    companion object {
        internal var powerConnected = false
            private set

        internal fun isPlugged(context: Context): Boolean =
            plugged(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))

        private fun plugged(battery: Intent?): Boolean {
            val value = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            if (value < 0) Log.w("WearTAK-ChargingAwake", "Unknown power state; stay awake disabled")
            return value > 0
        }
    }
}
