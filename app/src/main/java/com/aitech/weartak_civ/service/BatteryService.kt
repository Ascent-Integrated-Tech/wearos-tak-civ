/*
 * Copyright (C) 2024, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.IBinder
import android.util.Log
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class BatteryService: Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastDockType: DockType? = null
    private var lastBatteryPct: Int? = null
    private var lastChargingOrFull: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BatteryService onCreate()")
        isRunning.set(true)
        startForegroundServiceCompat(
            BATTERY_MONITOR_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )
        startBatteryPowerMonitoring()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d(TAG, "BatteryService onStartCommand()")
        return START_NOT_STICKY
    }

    private fun startBatteryPowerMonitoring() {
        registerReceiver(
            batteryPowerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)     // includes EXTRA_PLUGGED + status
                addAction(Intent.ACTION_POWER_CONNECTED)     // edge-trigger signal
                addAction(Intent.ACTION_POWER_DISCONNECTED)  // edge-trigger signal
            }
        )

        Log.d(TAG, "BatteryService: batteryPowerReceiver registered")
    }

    private fun stopBatteryPowerMonitoring() {
        try {
            unregisterReceiver(batteryPowerReceiver)
            Log.d(TAG, "BatteryService: batteryPowerReceiver unregistered")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "BatteryService: batteryPowerReceiver already unregistered?", e)
        }
    }

    private val batteryPowerReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED &&
                intent.action != Intent.ACTION_POWER_CONNECTED &&
                intent.action != Intent.ACTION_POWER_DISCONNECTED
            ) return

            Log.v(TAG, "batteryPowerReceiver: intent.action --> ${intent.action}")

            // ACTION_BATTERY_CHANGED carries the extras we care about.
            // POWER_CONNECTED / DISCONNECTED may not include EXTRA_PLUGGED on all devices,
            // so we fall back to querying the sticky BATTERY_CHANGED intent if needed.
            val batteryIntent = if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                intent
            } else {
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }

            if (batteryIntent == null) {
                Log.w(TAG, "batteryPowerReceiver: batteryIntent=null (cannot determine dock state)")
                return
            }

            val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)

            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) {
                Log.w(TAG, "batteryPowerReceiver: invalid level/scale (level=$level, scale=$scale)")
                return
            }
            val batteryPct = ((level.toFloat() / scale.toFloat()) * 100f).toInt()

            val dockType = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> DockType.WIRELESS
                BatteryManager.BATTERY_PLUGGED_USB      -> DockType.USB
                else                                    -> DockType.NONE
            }

            val chargingOrFull =
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

            // De-dupe to avoid spamming repo/logs if system sends repeated BATTERY_CHANGED
            val dockChanged = dockType != lastDockType
            val chargingChanged = (chargingOrFull != lastChargingOrFull)
            val pctChanged = batteryPct != lastBatteryPct
            if (!dockChanged && !pctChanged && !chargingChanged) return

            lastDockType = dockType
            lastBatteryPct = batteryPct
            lastChargingOrFull = chargingOrFull

            Log.i(TAG, "batteryPowerReceiver: pct=$batteryPct dockType=$dockType (plugged=$plugged, status=$status, chargingOrFull=$chargingOrFull)")

            serviceScope.launch {
                if (pctChanged) dataRepository.storeLatestBatteryPercentage(batteryPct)
                if (dockChanged) dataRepository.updateDockType(dockType)
                if (chargingChanged) dataRepository.updateChargingState(chargingOrFull)
            }
        }
    }

    enum class DockType {
        NONE,
        USB,
        WIRELESS
    }

    override fun onBind(intent: Intent): IBinder? {
        throw UnsupportedOperationException("Not yet implemented")
    }


    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "BatteryService onDestroy()")
        isRunning.set(false)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopBatteryPowerMonitoring()
        serviceScope.cancel()
    }

    companion object {
        private val TAG = "WearTAK-" + BatteryService::class.java.simpleName
        const private val BATTERY_MONITOR_SERVICE_NOTIFICATION_ID = 102
        val isRunning : AtomicBoolean = AtomicBoolean(false)
    }
}