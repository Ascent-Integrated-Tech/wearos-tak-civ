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
import android.os.Handler
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.device_uid
import com.aitech.weartak_civ.service.BatteryService.Companion.batteryPercentage
import com.aitech.weartak_civ.service.LocationService.Companion.ce
import com.aitech.weartak_civ.service.LocationService.Companion.hae
import com.aitech.weartak_civ.service.LocationService.Companion.lat
import com.aitech.weartak_civ.service.LocationService.Companion.le
import com.aitech.weartak_civ.service.LocationService.Companion.lon
import com.aitech.weartak_civ.service.TAKConnectService.Companion.sendDataTAKServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class HeartrateService: Service() {
    // TODO: Change to https://developer.android.com/reference/android/hardware/Sensor
    private lateinit var sensorManager: SensorManager
    private var heartRateSensor: Sensor? = null
    private val handler = Handler()
    var permission: Boolean = false
    private val dateTimeFormatter: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
//    private val dataRepository = DataRepository(this)

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
                //heartRateTextView.text = "Heart rate: $hr"
                //Log.d(TAG, "Obtained heartrate: $hr")

                val currTime = System.currentTimeMillis()
                if ((lastTimeHrSent == 0L) || ((currTime - lastTimeHrSent) > heartrateSendIntervalSecs * 1000L)) {
                    lastTimeHrSent = currTime
//                    sendBiometrics(callsign, hr)
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
/*
    fun sendBiometrics(callsign: String, heartrate: Int) {
        // Parse and manipulate timestamps
        dateTimeFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        val bioDateTime = Date()
        val bioTimeStr = dateTimeFormatter.format(bioDateTime)

        // Create a Calendar object and set it to the given date
        val calendar: Calendar = Calendar.getInstance()
        calendar.setTime(bioDateTime)

        // 3 times the heartrateSendIntervalSecs to the calendar
        calendar.add(Calendar.SECOND, heartrateSendIntervalSecs*3)

        val bioDateTimeFuture = calendar.time
        val bioFutureTimeStr = dateTimeFormatter.format(bioDateTimeFuture)
        //Log.d(TAG, "Current biometrics time string: $bioTimeStr")

        // Construct CoT message
        var cotMsg: String = BIO_PART1 + device_uid + BIO_PART2 +
                bioTimeStr + "' start='" + bioTimeStr + "' stale='" + bioFutureTimeStr + BIO_PART3
        if ((null != lat) && (null != lon)) {
            val haeActual = hae ?: "0.0"
            val ceActual = ce ?: "9999999.0"
            val leActual = le ?: "9999999.0"
            // TODO: Serial number should be unique. Hash android id to it
            cotMsg = cotMsg + lat + "' lon='" + lon + "' hae='" + java.lang.String.format(
                Locale.ENGLISH,
                "%.2f",
                haeActual
            ) +
                    "' ce='" + java.lang.String.format(
                Locale.ENGLISH,
                "%.2f",
                ceActual
            ) + "' le='" + java.lang.String.format(
                Locale.ENGLISH, "%.2f", leActual
            ) +
                    BIO_PART4 + "WOS-" + callsign +
                    "' heartRate='$heartrate' " +
                    "callsign='" + callsign +
                    "' model='WEAROS" +
                    "' batteryLevel='$batteryPercentage" + BIO_PART5
        } else {
            cotMsg = cotMsg + "0.0' lon='0.0' hae='9999999.0' ce='9999999.0' le='9999999.0" +
                    BIO_PART4 + "WOS-" + callsign +
                    "' heartRate='$heartrate' " +
                    "callsign='" + callsign +
                    "' model='WEAROS" +
                    "' batteryLevel='$batteryPercentage" + BIO_PART5
        }

        // Send the CoT message
        //Log.d(TAG, "Sending biometric COT msg: $cotMsg")
        // sendDataTAKServer(cotMsg)
    }
*/

    private fun processHeartRate(heartRate: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            sharedRepository.storeLatestHeartRate(heartRate)
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