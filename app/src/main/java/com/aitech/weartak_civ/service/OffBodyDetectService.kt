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
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.events.IsDeviceWornEvent
import com.aitech.weartak_civ.events.IsDeviceWornEvent.Companion.IS_NOT_WORN
import com.aitech.weartak_civ.events.IsDeviceWornEvent.Companion.IS_WORN
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.runBlocking
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.atomic.AtomicBoolean

class OffBodyDetectService: Service() {
    private lateinit var sensorManager: SensorManager
    private var offBodySensor: Sensor? = null
    var permission: Boolean = false

    private val dataRepository = MainActivity.dataRepository

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "OffBodyDetectService onStartCommand called w/ isRunning=$isRunning")
        if (!isRunning.getAndSet(true)) {
            startForegroundService()
            runBlocking {
                dataRepository.storeLatestIsDeviceWorn(false)
            }
            startOffBodySensor()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning.set(false)
        stopOffBodySensor()
        stopForeground(true)
        Log.d(TAG, "OffbodyDetect Service Destroyed")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startForegroundService() {
        Log.d(TAG, "Starting offbody detect foreground service")
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        } else {
            "OffBodyDetect Service"
        }

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
        val notification = notificationBuilder.setOngoing(true)
            .setSmallIcon(R.drawable.splash_icon)
            .setContentText("WearTAK-CIV OffBodyDetect service running")
            .setPriority(Context.BIND_WAIVE_PRIORITY)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        try {
            startForeground(OFFBODY_DETECT_SERVICE_NOTIFICATION_ID, notification)
        } catch (e: NullPointerException) {
            Log.e(TAG, "Failed to set location ForegroundService", e )
        }
    }

    private fun createNotificationChannel(): String {
        val channelId = "OffBodyDetect_Service"
        val channelName = "OffBodyDetect Foreground Service"
        val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
        chan.lightColor = Color.GRAY
        chan.importance = NotificationManager.IMPORTANCE_HIGH
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)
        return channelId
    }

    fun startOffBodySensor() {
        Log.d(TAG, "startOffBodySensor called")
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        offBodySensor = sensorManager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
        //permission = checkPermissions()
        if (null != offBodySensor) {
            Log.d(TAG, "OffBody Sensor callback registered")
            sensorManager.registerListener(sensorEventListener, offBodySensor, SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            Log.e(TAG, "OffBody Sensor is null. CANNOT read off body status")
        }
    }

    fun stopOffBodySensor() {
        sensorManager.unregisterListener(sensorEventListener)
    }


    val sensorEventListener = object : SensorEventListener {
        var offBodyDataFloat : Float = 0.0F
        var accuracyVal = -1
        //var lastTimeHrSent : Long = 0L
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            Log.d(TAG, "onAccuracyChanged called w/ accuracy: $accuracy")
            accuracyVal = accuracy
        }

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT) {
                offBodyDataFloat = event.values[0]
                Log.d(TAG, "offBody onSensorChanged events with accuracy $accuracyVal and offbody $offBodyDataFloat")
                if (Math.abs(offBodyDataFloat - 1.0) > TOLERANCE) {
                    runBlocking {
                        dataRepository.storeLatestIsDeviceWorn(false)
                        Log.d(TAG, "Stored isDeviceWorn as false");
                        EventBus.getDefault().post(IsDeviceWornEvent(IS_NOT_WORN))
                    }
                } else {
                    runBlocking {
                        dataRepository.storeLatestIsDeviceWorn(true)
                        Log.d(TAG, "Stored isDeviceWorn as true");
                        EventBus.getDefault().post(IsDeviceWornEvent(IS_WORN))
                    }
                }
            }
        }
    }

    private fun checkPermissions(): Boolean {
        if (ContextCompat.checkSelfPermission(MainActivity.getActivity(), "android.permission.BODY_SENSORS") != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return true
    }

    companion object {
        private val TAG = "WearTAK-" + OffBodyDetectService::class.java.simpleName
        private val OFFBODY_DETECT_SERVICE_NOTIFICATION_ID = 103
        private val TOLERANCE = 0.001F
        val offBodyDetectLiveData = MutableLiveData<Boolean>(true)
        var isRunning = AtomicBoolean(false)
        private lateinit var instance : OffBodyDetectService
    }
}
