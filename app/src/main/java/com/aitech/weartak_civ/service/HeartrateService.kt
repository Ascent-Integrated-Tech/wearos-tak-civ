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
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.IsDeviceWornEvent
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.runBlocking
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.concurrent.atomic.AtomicBoolean

class HeartrateService: Service() {
    // TODO: Change to https://developer.android.com/reference/android/hardware/Sensor
    private lateinit var sensorManager: SensorManager
    private var heartRateSensor: Sensor? = null
    var permission: Boolean = false
    private val dataRepository = MainActivity.dataRepository
    var deviceWorn = false

    // Instead of creating a new DataRepository, reference the same one from MainActivity
    private val sharedRepository: DataRepository // TODO: Use Constructor Injection for a more robust use of a shared DataRepository. Using hilt or dagger, you’d define a single @Singleton repository and annotate each service as @AndroidEntryPoint. Then each service receives the same repository instance automatically.
        get() = MainActivity.dataRepository

    override fun onCreate() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        super.onCreate()
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "HeartrateService onStartCommand called w/ isRunning=$isRunning")
        if (!isRunning.getAndSet(true)) {
            deviceWorn = dataRepository.latestIsDeviceWorn.value
            startForegroundService()
            if (deviceWorn) {
                startSensors()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning.set(false)
        deviceWorn = false
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        stopSensors()
        stopForeground(true)
        Log.d(TAG, "Heartrate Service Destroyed")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startForegroundService() {
        Log.d(TAG, "Starting heartrate foreground service")
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        } else {
            "Heartrate Service"
        }

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
        val notification = notificationBuilder.setOngoing(true)
            .setSmallIcon(R.drawable.splash_icon)
            .setContentText("WearTAK-CIV heartrate service running")
            .setPriority(Context.BIND_WAIVE_PRIORITY)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        try {
            startForeground(HEARTRATE_SERVICE_NOTIFICATION_ID, notification)
        } catch (e: NullPointerException) {
            Log.e(TAG, "Failed to set location ForegroundService", e )
        }
    }

    private fun createNotificationChannel(): String {
        val channelId = "Heartrate_Service"
        val channelName = "Heartrate Foreground Service"
        val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
        chan.lightColor = Color.GRAY
        chan.importance = NotificationManager.IMPORTANCE_HIGH
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)
        return channelId
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public fun onMessageEvent(event: IsDeviceWornEvent) {
        val currDeviceWorn = event.isDeviceWorn
        Log.d(TAG, "Received IsDeviceWornEvent w/ isDeviceWorn=$currDeviceWorn & prev deviceWorn=$deviceWorn")
        if (deviceWorn != currDeviceWorn) {
            if (currDeviceWorn) {
                startSensors()
            } else {
                stopSensors()
                hr = -1
                runBlocking {
                    //Log.d(TAG, "Posting hr value to hrStringLiveData")
                    hrStringLiveData.postValue("N/A")
                    sharedRepository.storeLatestHeartRate(hr)  // <---- use sharedRepository

                }
            }
            deviceWorn = currDeviceWorn
        }
    }

    fun startSensors() {
        Log.d(TAG, "HeartrateService startSensors called")
//        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        permission = checkPermissions()
        if (null != heartRateSensor) {
            Log.d(TAG, "Heartrate sensor callback registered")
            sensorManager.registerListener(
                sensorEventListener,
                heartRateSensor,
                SAMPLING_PERIOD_US
            )
        } else {
            Log.e(TAG, "Heartrate Sensor is null. CANNOT read heartrate")
        }
    }

    fun stopSensors() {
        sensorManager.unregisterListener(sensorEventListener)
    }


    val sensorEventListener = object : SensorEventListener {
        var localHr = 0
        var accuracyVal = -1
        var lastTimeHrSent : Long = 0L
        var highAlertThreshold : Int = 140
        var lowAlertThreshold : Int = 40
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            Log.d(TAG, "onAccuracyChanged called w/ accuracy: $accuracy")
            accuracyVal = accuracy
        }

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_HEART_RATE) {
                localHr = event.values[0].toInt()
                //Log.d(TAG, "heartrate onSensorChanged w/ accuracy=$accuracyVal, hr=$localHr")
                if ((accuracyVal != 3)) {
                    //Log.d(TAG, "Ignoring hr($localHr) with accuracyVal=$accuracyVal")
                    return
                }
                hr = localHr
                //heartRateTextView.text = "Heart rate: $hr"
                //Log.d(TAG, "Obtained heartrate: $hr")

                val currTime = System.currentTimeMillis()
                if ((lastTimeHrSent == 0L) || ((currTime - lastTimeHrSent) > CotDispatchService.reportingUpdateIntervalSecs * 1000L)) {
                    lastTimeHrSent = currTime
                    runBlocking {
                        //Log.d(TAG, "Posting hr value to hrStringLiveData")
                        hrStringLiveData.postValue(hr.toString())
//                        dataRepository.storeLatestHeartRate(hr)
                        sharedRepository.storeLatestHeartRate(hr)  // <---- use sharedRepository

                    }

//                    val stickyEvent = EventBus.getDefault().getStickyEvent(
//                        MessageEvent::class.java
//                    )
//                    if (stickyEvent == null) {
//                        if (hr > highAlertThreshold) {
//                            emergency = "HIGH HEART RATE"
//                            EventBus.getDefault().postSticky(MessageEvent(SEND_EMERGENCY_MESSAGE))
//                        } else if (hr < lowAlertThreshold) {
//                            emergency = "LOW HEART RATE"
//                            EventBus.getDefault().postSticky(MessageEvent(SEND_EMERGENCY_MESSAGE))
//                        }
//                    } else {
//                        if (hr > highAlertThreshold && emergency != "HIGH HEART RATE") {
//                            emergency = "HIGH HEART RATE"
//                            EventBus.getDefault()
//                                .postSticky(MessageEvent(SEND_EMERGENCY_MESSAGE))
//                        } else if (hr < lowAlertThreshold && emergency != "LOW HEART RATE") {
//                            emergency = "LOW HEART RATE"
//                            EventBus.getDefault()
//                                .postSticky(MessageEvent(SEND_EMERGENCY_MESSAGE))
//                        }
//                        Log.d(TAG, stickyEvent.message)
//                    }
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
        private val TAG = "WearTAK-" + HeartrateService::class.java.simpleName
        private val HEARTRATE_SERVICE_NOTIFICATION_ID = 105
        private val SAMPLING_PERIOD_US = 2_000_000   // Every 2 seconds; NOTE: Could also be SensorManager.SENSOR_DELAY_NORMAL as it does not seem to matter. The HR sensor is polled every 1 second.
        private const val BIO_PART1 = "<?xml version='1.0' encoding='UTF-8' standalone='yes'?><event version='2.0' uid='"
        private const val BIO_PART2 = "' type='t-x-c-heartRate' time='"
        private const val BIO_PART3 = "' how='m-g' access='Undefined'><point lat='"
        private const val BIO_PART4 = "' /><detail><status serialNumber='"
        private const val BIO_PART5 = "'/></detail></event>"
        var hr: Int = -1
//        var heartrateSendIntervalSecs : Int = 5
        val hrStringLiveData = MutableLiveData<String>("N/A")
        var isRunning = AtomicBoolean(false)
    }
}
