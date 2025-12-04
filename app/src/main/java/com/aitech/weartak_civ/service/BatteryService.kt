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
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class BatteryService: Service() {
    private var batteryMonitorHandler: Handler? = null
    private var batteryMonitorThread: HandlerThread? = null
    private val batteryMonitorRunnable = BatteryMonitorRunnable()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BatteryService onCreate()")
        isRunning.set(true)
        startForegroundServiceCompat(
            BATTERY_MONITOR_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )
        startBatteryMonitor()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d(TAG, "BatteryService onStartCommand()")
        return START_NOT_STICKY
    }

    private fun startBatteryMonitor() {
        batteryMonitorThread = HandlerThread("Battery Monitor Thread")
        try {
            batteryMonitorThread?.start()
            batteryMonitorHandler = batteryMonitorThread?.looper?.let { Handler(it) }
            batteryMonitorHandler?.postDelayed(batteryMonitorRunnable, INITIAL_BATTERY_MONITOR_START_DELAY)
        } catch (e: IllegalThreadStateException) {
            Log.e(TAG, "Not able to start the battery monitor thread ", e)
        }
    }

    fun getBatteryPercentage(context: Context): Float {
        try {
            val iFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, iFilter)
            val level = (batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1).toFloat()
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging =
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

            CoroutineScope(Dispatchers.IO).launch {
                // Implement this in dataRepository (see below)
                dataRepository.updateChargingState(charging)
            }
            return (level / scale) * 100
        } catch (e: RuntimeException) {
            Log.e(TAG, "Error in getBatteryPercentage: ", e)
            return (-1000).toFloat()
        }
    }

    private inner class BatteryMonitorRunnable : Runnable {
        override fun run() {
            batteryPercentage = getBatteryPercentage(applicationContext).toInt()
            batteryPercentageLiveData.postValue(batteryPercentage)
//            Log.d(LOG_TAG, "BatteryMonitorRunnable run called with batteryPercentage = ($batteryPercentage)")
//            batteryMonitorHandler?.postDelayed(this, DEFAULT_BATTERY_MONITOR_INTERVAL)
            CoroutineScope(Dispatchers.IO).launch {
                dataRepository.storeLatestBatteryPercentage(batteryPercentage) // TODO: can we remove coroutine scope
            }
            batteryMonitorHandler?.postDelayed(this, DEFAULT_BATTERY_MONITOR_INTERVAL)
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        throw UnsupportedOperationException("Not yet implemented")
    }


    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "BatteryService onDestroy()")
        isRunning.set(false)
        stopForeground(STOP_FOREGROUND_DETACH)

        if ((batteryMonitorThread != null) && (batteryMonitorHandler != null)) {
            batteryMonitorHandler?.removeCallbacks(batteryMonitorRunnable)
            batteryMonitorHandler?.looper?.quit()
            batteryMonitorThread?.quitSafely()
        }
    }

    companion object {
        private val TAG = "WearTAK-" + BatteryService::class.java.simpleName
        const private val INITIAL_BATTERY_MONITOR_START_DELAY = 1_000L // Set to 1 second
        //const private val DEFAULT_BATTERY_MONITOR_INTERVAL = 3_00_000L // 5 minutes
        const private val DEFAULT_BATTERY_MONITOR_INTERVAL = 60_000L // Set to 1 minute
        const private val BATTERY_MONITOR_SERVICE_NOTIFICATION_ID = 102
        var batteryPercentage : Int = 100
        val batteryPercentageLiveData = MutableLiveData<Int>(100)
        val isRunning : AtomicBoolean = AtomicBoolean(false)
    }
}