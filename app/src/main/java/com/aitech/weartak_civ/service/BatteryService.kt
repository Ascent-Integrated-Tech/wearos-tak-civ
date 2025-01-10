/*
 * Copyright (C) 2024, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.HardwarePropertiesManager
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BatteryService: Service() {
    private var batteryMonitorHandler: Handler? = null
    private var batteryMonitorThread: HandlerThread? = null
    private val batteryMonitorRunnable = BatteryMonitorRunnable()

    override fun onCreate() {
        Log.d(LOG_TAG, "BatteryService onCreate")
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val hardwarePropertiesManager = MainActivity.getActivity().getSystemService(Context.HARDWARE_PROPERTIES_SERVICE) as HardwarePropertiesManager
        startForegroundService()
        startBatteryMonitor()
        return START_NOT_STICKY
    }

    private fun startForegroundService() {
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        } else {
            "Battery Monitor Service"
        }

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
        val notification = notificationBuilder.setOngoing(true)
            .setSmallIcon(R.drawable.splash_icon)
            .setContentText("WearTAK-CIV battery service running")
            .setPriority(Context.BIND_WAIVE_PRIORITY)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        startForeground(BATTERY_MONITOR_SERVICE_NOTIFICATION_ID, notification)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(): String {
        val channelId = "Battery Monitor Service"
        val channelName = "Battery Background Service"
        val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
        chan.lightColor = Color.GRAY
        chan.importance = NotificationManager.IMPORTANCE_NONE
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)
        return channelId
    }

    private fun startBatteryMonitor() {
        batteryMonitorThread = HandlerThread("Battery Monitor Thread")
        try {
            batteryMonitorThread?.start()
            batteryMonitorHandler = batteryMonitorThread?.looper?.let { Handler(it) }
            batteryMonitorHandler?.postDelayed(batteryMonitorRunnable, INITIAL_BATTERY_MONITOR_START_DELAY)
        } catch (e: IllegalThreadStateException) {
            Log.e(LOG_TAG, "Not able to start the battery monitor thread ", e)
        }
    }

    fun getBatteryPercentage(context: Context): Float {
        try {
            val iFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, iFilter)
            val level = (if ((batteryStatus != null)) batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) else -1).toFloat()
            val scale = if ((batteryStatus != null)) batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1) else -1
            //Log.d(LOG_TAG, "getBatteryPercentage: level ($level); scale ($scale)")
            return (level / scale) * 100
        } catch (e: RuntimeException) {
            Log.e(LOG_TAG, "Error in getBatteryPercentage: ", e)
            return (-1000).toFloat()
        }
    }

    private inner class BatteryMonitorRunnable : Runnable {
        override fun run() {
//            batteryPercentage = getBatteryPercentage(applicationContext).toInt()
            batteryPercentageLiveData.postValue(batteryPercentage)
//            Log.d(LOG_TAG, "BatteryMonitorRunnable run called with batteryPercentage = ($batteryPercentage)")
//            batteryMonitorHandler?.postDelayed(this, DEFAULT_BATTERY_MONITOR_INTERVAL)
            val batteryPercentage = getBatteryPercentage(applicationContext).toInt()
            CoroutineScope(Dispatchers.IO).launch {
                dataRepository.storeLatestBatteryPercentage(batteryPercentage)
            }
            batteryMonitorHandler?.postDelayed(this, DEFAULT_BATTERY_MONITOR_INTERVAL)
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        throw UnsupportedOperationException("Not yet implemented")
    }


    override fun onDestroy() {
        super.onDestroy()
        if ((batteryMonitorThread != null) && (batteryMonitorHandler != null)) {
            batteryMonitorHandler?.removeCallbacks(batteryMonitorRunnable)
            batteryMonitorHandler?.looper?.quit()
            batteryMonitorThread?.quitSafely()
        }
        Log.d(LOG_TAG, "BatteryService Destroyed")
    }

    companion object {
        private val LOG_TAG = "WearTAK-" + BatteryService::class.java.simpleName
        const private val INITIAL_BATTERY_MONITOR_START_DELAY = 1_000L // Set to 1 second
        //const private val DEFAULT_BATTERY_MONITOR_INTERVAL = 3_00_000L // 5 minutes
        const private val DEFAULT_BATTERY_MONITOR_INTERVAL = 60_000L // Set to 1 minute
        const private val BATTERY_MONITOR_SERVICE_NOTIFICATION_ID = 102
        var batteryPercentage : Int = 100
        val batteryPercentageLiveData = MutableLiveData<Int>(100)
    }
}