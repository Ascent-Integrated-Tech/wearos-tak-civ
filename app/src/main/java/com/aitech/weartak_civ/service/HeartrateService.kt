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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    var enableHrAlerts : Boolean = false
    var highAlertThreshold : Int = 163
    var highAlertNumSecs : Int = 30
    var hrValuesEvictingQ : EvictingQueue<Int>? = null
    var highAlertSent : Boolean = false
    var highHrAlertUid : String = ""
    var lowAlertThreshold : Int = 40

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
            setDataFromDatastore()
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

    private fun createEvictingQ() {
        if (highAlertNumSecs > 0) {
            hrValuesEvictingQ = EvictingQueue.create<Int>(highAlertNumSecs)
            Log.d(TAG, "HeartrateService createEvictingQ(): maxsize=${highAlertNumSecs}, currsize=${hrValuesEvictingQ?.size}")
        }
    }

    suspend private fun setDataFromDatastore() {
        enableHrAlerts = sharedRepository.getEnablePhysiologicalAlerts.first()
        highAlertThreshold = sharedRepository.getHrHighAlertBpmThreshold.first()
        highAlertNumSecs = sharedRepository.getHrHighAlertNumSecsThreshold.first()
    }

    override fun onDestroy() {
        //RestingHrManager.stop()
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
                startSensors()
            } else {
                stopSensors()
                hr = -1
                runBlocking {
                    hrStringLiveData.postValue("N/A")
                    requestPhysioStatusAndAlertingComplicationUpdate(context)
                    sharedRepository.storeLatestHeartRate(hr)  // <---- use sharedRepository
                }
            }
            deviceWorn = currDeviceWorn
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

    @Subscribe(threadMode = ThreadMode.MAIN)
    public fun onMessageEvent(msgEvent: MessageEvent) {
        val msg = msgEvent.message
        if (PHYSIOLOGICAL_ALERTS_ENABLED_CHANGED == msg) {
            enableHrAlerts = (msgEvent.addlIntInfo == 1)
            if (enableHrAlerts) {
                runBlocking {
                    createEvictingQ()
                }
            } else {
                hrValuesEvictingQ = null
            }
        } else if (HR_ALERT_THRESHOLDS_CHANGED == msg) {
            if (enableHrAlerts) {
                runBlocking {
                    val prevHighAlertNumSecs = highAlertNumSecs
                    setDataFromDatastore()
                    if (prevHighAlertNumSecs != highAlertNumSecs) {
                        createEvictingQ()
                    }
                }
            }
        }
    }

    fun startSensors() {
        Log.d(TAG, "HeartrateService startSensors()")
        if (enableHrAlerts) {
            createEvictingQ()
        } else {
            hrValuesEvictingQ = null
        }
//        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
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
        hrValuesEvictingQ = null
    }

    private val sensorEventListener = object : SensorEventListener {
        var localHr = 0
        var accuracyVal = -1
        var lastTimeHrSent : Long = 0L

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            Log.d(TAG, "onAccuracyChanged called w/ accuracy: $accuracy")
            accuracyVal = accuracy
        }

        @Synchronized
        fun generateAlerts() {
            if (enableHrAlerts) {
                hrValuesEvictingQ?.add(localHr)
                if ((hrValuesEvictingQ?.size ?: 0) == highAlertNumSecs) {
                    //Log.d(TAG, "hrValuesEvictingQ has size ${hrValuesEvictingQ?.size}, ${hrValuesEvictingQ.toString()}")
                    //Log.d(TAG, "${(hrValuesEvictingQ?.filter { it >= highAlertThreshold })?.size ?: 0}")
                    val numAboveThreshold = (hrValuesEvictingQ?.filter { it >= highAlertThreshold })?.size ?: 0
                    if (numAboveThreshold >= highAlertNumSecs) {
                        //Log.i(TAG, "All values >= $highAlertThreshold")
                        if (!highAlertSent) {
                            scope.launch {
                                val hrAlertDesc = "$DESC_HIGH_HEART_RATE_ALERT: Heartrate above $highAlertThreshold bpm for $highAlertNumSecs seconds"
                                Log.d(TAG, "Sending alert: ($CAT_PHYSIOLOGICAL_ALERT: $hrAlertDesc)")
                                highHrAlertUid = UUID.randomUUID().toString()
                                val timeMillis = System.currentTimeMillis()
                                sharedRepository.addPhysioAndAutoGenAlertRequest(highHrAlertUid, CAT_PHYSIOLOGICAL_ALERT, hrAlertDesc, 1, true, timeMillis)
                                sharedRepository.addPhysioAlertToFront(Utils.PhysioAlert(com.aitech.weartak_civ.DESC_HIGH_HEART_RATE_ALERT, Utils.AlertChipState.Emergency, timeMillis, ""))
                                requestPhysioStatusAndAlertingComplicationUpdate(applicationContext)
                                highAlertSent = true
                            }
                        }
                    } else {
                        if (highAlertSent) {
                            if (numAboveThreshold == 0) {
                                scope.launch {
                                    Log.d(TAG, "Sending clear high heart rate alert for uid=$highHrAlertUid")
                                    sharedRepository.clearPhysioAndAutoGenAlertRequestUid(highHrAlertUid)
                                    sharedRepository.removePhysioAlerts(com.aitech.weartak_civ.DESC_HIGH_HEART_RATE_ALERT)
                                    requestPhysioStatusAndAlertingComplicationUpdate(applicationContext)
                                    highAlertSent = false
                                }
                            }
                        }
                    }
                }
            }
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
                //Log.d(TAG, "Obtained heartrate: $hr")
                generateAlerts()

                val currTime = System.currentTimeMillis()
                if ((lastTimeHrSent == 0L) || ((currTime - lastTimeHrSent) > CotDispatchService.actualReportingIntervalSecs * 1000L)) {
                    lastTimeHrSent = currTime
                    runBlocking {
                        hrStringLiveData.postValue(hr.toString())
                        requestPhysioStatusAndAlertingComplicationUpdate(context)
//                        RestingHrManager.onHeartRateSample(localHr.toDouble()) // push all raw hr to manager for auto calculation of resting heart rate.
                        sharedRepository.storeLatestHeartRate(hr)  // <---- use sharedRepository
                    }

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
        return ContextCompat.checkSelfPermission(MainActivity.getActivity(), "android.permission.BODY_SENSORS") == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private val TAG = "WearTAK-" + HeartrateService::class.java.simpleName
        private const val HEARTRATE_SERVICE_NOTIFICATION_ID = 105
        private const val SAMPLING_PERIOD_US = 2_000_000   // Every 2 seconds; NOTE: Could also be SensorManager.SENSOR_DELAY_NORMAL as it does not seem to matter. The HR sensor is polled every 1 second.
        var hr: Int = -1
        val hrStringLiveData = MutableLiveData("N/A")
        val isRunning = AtomicBoolean(false)
        val context: Context = mainActivity.applicationContext
    }
}
