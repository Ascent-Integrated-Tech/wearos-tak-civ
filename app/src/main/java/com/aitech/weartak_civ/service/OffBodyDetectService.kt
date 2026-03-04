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
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.util.Log
//import androidx.lifecycle.MutableLiveData
import com.aitech.weartak_civ.Utils.SensorLogType
import com.aitech.weartak_civ.Utils.addToSensorLogFileLocation
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.cep.bus.CepBus
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.Topics
import com.aitech.weartak_civ.cep.model.payloads.OffBodyPayload
import com.aitech.weartak_civ.data.DataRepository
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

    private val dataRepository : DataRepository
        get() = MainActivity.dataRepository

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OffBodyDetectService onCreate()")
        isRunning.set(true)
        startForegroundServiceCompat(
            notificationId = OFFBODY_DETECT_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )

        runBlocking {
            dataRepository.storeLatestIsDeviceWorn(false)
            // Device is not worn at service start
            CepBus.emit(CepEvent(Topics.OFFBODY, OffBodyPayload(on_wrist = 0)))
        }
        startOffBodySensor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "OffBodyDetectService onStartCommand()")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "OffBodyDetectService onDestroy()")
        isRunning.set(false)
        stopOffBodySensor()
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    fun startOffBodySensor() {
        Log.d(TAG, "OffBodyDetectService startOffBodySensor()")
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        offBodySensor = sensorManager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
        if (null != offBodySensor) {
            Log.d(TAG, "OffBody Sensor callback registered")
            sensorManager.registerListener(sensorEventListener, offBodySensor, SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            Log.e(TAG, "OffBody Sensor is null. CANNOT read off body status")
        }
    }

    fun stopOffBodySensor() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(sensorEventListener)
        }
    }

    val sensorEventListener = object : SensorEventListener {
        var offBodyDataFloat : Float = 0.0F
        var accuracyVal = -1
        //var lastTimeHrSent : Long = 0L
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            Log.d(TAG, "OffBodyDetectService onAccuracyChanged(): accuracy= $accuracy")
            accuracyVal = accuracy
        }

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT) {
                offBodyDataFloat = event.values[0]
                addToSensorLogFileLocation(SensorLogType.DEVICE_WORN, accuracyVal, "$offBodyDataFloat")
                Log.i(TAG, "OffBodyDetectService onSensorChanged(): accuracy= $accuracyVal, offbody= $offBodyDataFloat")
                if (Math.abs(offBodyDataFloat - 1.0) > TOLERANCE) {
                    runBlocking {
                        addToSensorLogFileLocation(SensorLogType.DEVICE_WORN, accuracyVal, "0")
                        dataRepository.storeLatestIsDeviceWorn(false)
                        Log.i(TAG, "OffBodyDetectService Device is not worn")
                        EventBus.getDefault().post(IsDeviceWornEvent(IS_NOT_WORN))
                        CepBus.emit(CepEvent(Topics.OFFBODY, OffBodyPayload(on_wrist = 0)))
                    }
                } else {
                    runBlocking {
                        addToSensorLogFileLocation(SensorLogType.DEVICE_WORN, accuracyVal, "1")
                        dataRepository.storeLatestIsDeviceWorn(true)
                        Log.i(TAG, "OffBodyDetectService Device is worn")
                        EventBus.getDefault().post(IsDeviceWornEvent(IS_WORN))
                        CepBus.emit(CepEvent(Topics.OFFBODY, OffBodyPayload(on_wrist = 1)))
                    }
                }
            }
        }
    }

    companion object {
        private val TAG = "WearTAK-" + OffBodyDetectService::class.java.simpleName
        private val OFFBODY_DETECT_SERVICE_NOTIFICATION_ID = 103
        private val TOLERANCE = 0.001F
//        val offBodyDetectLiveData = MutableLiveData<Boolean>(true)
        val isRunning = AtomicBoolean(false)
        private lateinit var instance : OffBodyDetectService
    }
}
