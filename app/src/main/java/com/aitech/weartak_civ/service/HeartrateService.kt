/*
 * Copyright (C) 2024, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.runBlocking

class HeartrateService: Service() {
    // TODO: Change to https://developer.android.com/reference/android/hardware/Sensor
    private lateinit var sensorManager: SensorManager
    private var heartRateSensor: Sensor? = null
    var permission: Boolean = false

    // Instead of creating a new DataRepository, reference the same one from MainActivity
    private val sharedRepository: DataRepository // TODO: Use Constructor Injection for a more robust use of a shared DataRepository. Using hilt or dagger, you’d define a single @Singleton repository and annotate each service as @AndroidEntryPoint. Then each service receives the same repository instance automatically.
        get() = MainActivity.dataRepository

    override fun onCreate() {
        super.onCreate()
        startSensors()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSensors()
        Log.d(TAG, "Heartrate Service Destroyed")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    fun startSensors() {
        Log.d(TAG, "startSensors called")
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        permission = checkPermissions()
        if (null != heartRateSensor) {
            Log.d(TAG, "Hearrate sensor callback registered with SAMPLING_PERIOD_US=$SAMPLING_PERIOD_US")
            sensorManager.registerListener(
                sensorEventListener,
                heartRateSensor,
                SAMPLING_PERIOD_US
            )
            /*
            handler.postDelayed(object : Runnable {
                override fun run() {
                    if (heartRateSensor != null && permission) {
                        getHeartRateValue()
                    }
                    handler.postDelayed(this, 1000)
                }
            }, 0)

             */
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
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            Log.d(TAG, "onAccuracyChanged called w/ accuracy: $accuracy")
            accuracyVal = accuracy
        }

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_HEART_RATE) {
                localHr = event.values[0].toInt()
                if ((accuracyVal != 3) || (localHr == 0)) {
                    //Log.d(TAG, "Ignoring hr($localHr) with accuracyVal=$accuracyVal")
                    return
                }
                hr = localHr
                //Log.d(TAG, "Received heartrate value (HeartrateService): $hr")

                //heartRateTextView.text = "Heart rate: $hr"
                //Log.d(TAG, "Obtained heartrate: $hr")

                val currTime = System.currentTimeMillis()
                if ((lastTimeHrSent == 0L) || ((currTime - lastTimeHrSent) > heartrateSendIntervalSecs * 1000L)) {
                    lastTimeHrSent = currTime
                    runBlocking {
                        //Log.d(TAG, "Posting hr value to hrStringLiveData")
                        hrStringLiveData.postValue(hr.toString())
//                        dataRepository.storeLatestHeartRate(hr)
                        sharedRepository.storeLatestHeartRate(hr)  // <---- use sharedRepository

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
        private val TAG = "WearTAK-" + HeartrateService::class.java.simpleName
        private val HEARTRATE_SERVICE_NOTIFICATION_ID = 105
        private val SAMPLING_PERIOD_US = 2_000_000   // Every 2 seconds; Could also bve SensorManager.SENSOR_DELAY_NORMAL
        private const val BIO_PART1 = "<?xml version='1.0' encoding='UTF-8' standalone='yes'?><event version='2.0' uid='"
        private const val BIO_PART2 = "' type='t-x-c-heartRate' time='"
        private const val BIO_PART3 = "' how='m-g' access='Undefined'><point lat='"
        private const val BIO_PART4 = "' /><detail><status serialNumber='"
        private const val BIO_PART5 = "'/></detail></event>"
        var hr: Int = -1
        var heartrateSendIntervalSecs : Int = 5
        val hrStringLiveData = MutableLiveData<String>("N/A")
    }
}