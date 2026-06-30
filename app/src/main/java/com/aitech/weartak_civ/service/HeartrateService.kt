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
import android.content.ComponentName
import android.content.Context
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
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.cep.bus.CepBus
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.Topics
import com.aitech.weartak_civ.cep.model.payloads.HeartRatePayload
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.CAT_PHYSIOLOGICAL_ALERT
import com.aitech.weartak_civ.DESC_HIGH_HEART_RATE_ALERT
import com.aitech.weartak_civ.events.IsDeviceWornEvent
import com.aitech.weartak_civ.events.MessageEvent
import com.aitech.weartak_civ.events.MessageEvent.Companion.HR_ALERT_THRESHOLDS_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.PHYSIOLOGICAL_ALERTS_ENABLED_CHANGED
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivity
import com.aitech.weartak_civ.presentation.requestPhysioStatusAndAlertingComplicationUpdate
import com.google.common.collect.EvictingQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class HeartrateService: Service() {
    // TODO: Change to https://developer.android.com/reference/android/hardware/Sensor
    private lateinit var sensorManager: SensorManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var heartRateSensor: Sensor? = null
    var permission: Boolean = false
    private var deviceWorn = false

    private var offWristHrClearJob: Job? = null

    private var userAge: Int? = null
    private var restingHeartRate: Int? = null

    private val sharedRepository: DataRepository
        get() = MainActivity.dataRepository

    private fun observeMetrics() {
        sharedRepository.physiologicalMetricAge
            .combine(sharedRepository.restingHeartRate) { age, resting ->
                age to resting
            }
            .filter { (age, resting) -> age != null && resting != null }
            .onEach { (age, resting) ->
                userAge = age!!
                restingHeartRate = resting!!
            }
            .launchIn(scope)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "HeartrateService onCreate()")
        isRunning.set(true)
        startForegroundServiceCompat(
            notificationId = HEARTRATE_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )

        observeMetrics()

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        deviceWorn = sharedRepository.latestIsDeviceWorn.value

        CoroutineScope(Dispatchers.Main).launch {
            if (deviceWorn) {
                startSensors()
            }
        }

        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "HeartrateService onStartCommand()")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        //RestingHrManager.stop()
        //cancelOffWristHrClearJob()
        scope.cancel()
        super.onDestroy()
        Log.d(TAG, "HeartrateService onDestroy()")
        isRunning.set(false)
        deviceWorn = false
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        stopSensors()
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public fun onIsDeviceWornEvent(event: IsDeviceWornEvent) {
        val currDeviceWorn = event.isDeviceWorn
        Log.d(TAG, "HeartrateService IsDeviceWornEvent(): isDeviceWorn=$currDeviceWorn & Previous isDeviceWorn=$deviceWorn")
        if (deviceWorn != currDeviceWorn) {
            if (currDeviceWorn) {
                //cancelOffWristHrClearJob()
                startSensors()
            } else {
                //scheduleOffWristHrAlertClear()
                stopSensors()
                hr = -1
                runBlocking {
                    hrStringLiveData.postValue("N/A")
                    requestPhysioStatusAndAlertingComplicationUpdate(context)
                    Log.e(
                        "HR_STORE",
                        "OFF WRIST storing heartRate=$hr"
                    )
                    sharedRepository.storeLatestHeartRate(hr)  // <---- use sharedRepository
                }
            }
            deviceWorn = currDeviceWorn
        }
    }

    private fun cancelOffWristHrClearJob() {
        offWristHrClearJob?.cancel()
        offWristHrClearJob = null
    }

    private fun scheduleOffWristHrAlertClear() {
        cancelOffWristHrClearJob()
        offWristHrClearJob = scope.launch {
            delay(OFF_WRIST_HR_ALERT_CLEAR_DELAY_MS)
            if (sharedRepository.latestIsDeviceWorn.value) return@launch
            clearActiveHrOriginAlerts()
        }
    }

    private suspend fun clearActiveHrOriginAlerts() {
        val activeHrAlerts = sharedRepository.physioAndAutoGenAlertList.value.filter {
            it.alertState == DataRepository.AlertState.ALERT && HR_ALERT_CATEGORIES.contains(it.category)
        }

        if (activeHrAlerts.isEmpty()) return

        Log.i(TAG, "Clearing ${activeHrAlerts.size} active HR alert(s) after sustained off-wrist")
        activeHrAlerts.forEach { alert ->
            sharedRepository.clearPhysioAndAutoGenAlertRequestUid(alert.uuid)
        }
    }

    init {
        scope.launch {
            sharedRepository.latestExertion
                .collect { exertion ->
                    val logString = "Exertion updated: $exertion"
                    Log.d(TAG, logString)
                }
        }
    }

    fun startSensors() {
        Log.d(TAG, "HeartrateService startSensors()")
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        permission = checkPermissions()
        if (null != heartRateSensor) {
            Log.i(TAG, "HeartrateService heartRateSensor callback registered")
            sensorManager.registerListener(sensorEventListener, heartRateSensor, SAMPLING_PERIOD_US)
        } else {
            Log.e(TAG, "HeartrateService heartRateSensor is null. CANNOT read heartrate")
        }
    }

    fun stopSensors() {
        Log.d(TAG, "HeartrateService stopSensors()")
        sensorManager.unregisterListener(sensorEventListener)
    }

    private val sensorEventListener = object : SensorEventListener {
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
                //Log.i(TAG, "heartrate onSensorChanged w/ accuracy=$accuracyVal, hr=$localHr")
                Log.e(
                    "HR_SENSOR",
                    "rawHr=$localHr accuracy=$accuracyVal"
                )
                if ((accuracyVal != 3)) {
                    //Log.d(TAG, "Ignoring hr($localHr) with accuracyVal=$accuracyVal")
                    return
                }

                if (!deviceWorn) {
                    if (hr != -1) {
                        hr = -1

                        runBlocking {
                            hrStringLiveData.postValue("N/A")
                            requestPhysioStatusAndAlertingComplicationUpdate(context)
                            sharedRepository.storeLatestHeartRate(hr)
                        }
                    }
                    return
                }

                if (localHr <= 0) {
                    Log.e(
                        "HR_STORE",
                        "Ignoring invalid sensor sample localHr=$localHr"
                    )
                    return
                }
                // --- NEW: feed real HR into CEP ---
                scope.launch {
                    try {
                        CepBus.emit(
                            CepEvent(
                                topic = Topics.HEART_RATE,
                                payload = HeartRatePayload(bpm = localHr.toDouble())
                            )
                        )

                        //Log.d("CEP-HR", "Emitted HR=${localHr} bpm to CEP")
                    } catch (ex: Exception) {
                        Log.e("CEP-HR", "Failed to emit HR sample to CEP", ex)
                    }
                }

                hr = localHr
                //Log.d(TAG, "Obtained heartrate: $hr")

                val currTime = System.currentTimeMillis()
                if ((lastTimeHrSent == 0L) || ((currTime - lastTimeHrSent) > CotDispatchService.actualReportingIntervalSecs * 1000L)) {
                    lastTimeHrSent = currTime
                    runBlocking {
                        hrStringLiveData.postValue(hr.toString())
                        requestPhysioStatusAndAlertingComplicationUpdate(context)
//                        RestingHrManager.onHeartRateSample(localHr.toDouble()) // push all raw hr to manager for auto calculation of resting heart rate.
                        Log.e("HR_STORE", "VALID HR storing heartRate=$hr")
                        sharedRepository.storeLatestHeartRate(hr)  // <---- use sharedRepository
                    }
                }
            }
        }
    }

    private fun checkPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(MainActivity.getActivity(), "android.permission.BODY_SENSORS") == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private val TAG = "WearTAK-" + HeartrateService::class.java.simpleName
        private const val HEARTRATE_SERVICE_NOTIFICATION_ID = 105
        private const val OFF_WRIST_HR_ALERT_CLEAR_DELAY_MS = 10_000L
        private const val SAMPLING_PERIOD_US = 1_000_000   // Every 1 seconds; NOTE: Could also be SensorManager.SENSOR_DELAY_NORMAL as it does not seem to matter. The HR sensor is polled every 1 second.
        private val HR_ALERT_CATEGORIES = setOf(
            "CAT_HR_LOW",
            "CAT_HR_REST_BRADY",
            "CAT_HR_HIGH",
            "CAT_HR_ALERT",
            "CAT_HR_EMERGENCY"
        )
        var hr: Int = -1
        val hrStringLiveData = MutableLiveData("N/A")
        val isRunning = AtomicBoolean(false)
        val context: Context = mainActivity.applicationContext
    }
}
