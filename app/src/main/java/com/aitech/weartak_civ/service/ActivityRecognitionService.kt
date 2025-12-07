/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service

import android.Manifest
import android.app.Service
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.concurrent.futures.await
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.Utils.writeToLogFileLocation
import androidx.health.services.client.HealthServices
import androidx.health.services.client.HealthServicesClient
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.HealthEvent
import androidx.health.services.client.data.MeasureCapabilities
import com.aitech.weartak_civ.manager.RestingHrManager
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.SampleDataPoint
import androidx.health.services.client.getCapabilities
import com.aitech.weartak_civ.events.CustomEmergencyEvent
import com.aitech.weartak_civ.events.CustomEmergencyEvent.Companion.CAT_FALL_DETECTED
import com.aitech.weartak_civ.events.CustomEmergencyEvent.Companion.DESC_GOOGLE_SDK_FALL_DETECTED
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import com.aitech.weartak_civ.presentation.MainActivity.Constants.logFile
import org.greenrobot.eventbus.EventBus

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.aitech.weartak_civ.cep.bus.CepBus
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.Topics
import com.aitech.weartak_civ.cep.model.payloads.ActivityPayload


class ActivityRecognitionService : Service() {
    companion object {
        private const val TAG = "WearTAK-ActRecService"
        private const val UPDATE_INTERVAL_MS = 5_000L
        private const val ACTION_RECOGNITION = "com.aitech.weartak_civ.ACTION_RECOGNITION"
        private const val NOTIF_ID = 192
        private const val LOG_FILENAME = "/sdcard/Download/activity_detection.log"
        val isRunning: AtomicBoolean = AtomicBoolean(false)
        enum class ActivityRecognized {
            RUNNING, WALKING, TILTING, STILL, BICYCLING, IN_VEHICLE, ON_FOOT, UNKNOWN
        }
    }

    //private val logFile by lazy { File(LOG_FILENAME) }

    private lateinit var activityRecognitionClient: ActivityRecognitionClient
    private lateinit var callbackIntent: PendingIntent
    private val repo: DataRepository
        get() = MainActivity.dataRepository

    private val healthClient by lazy { HealthServices.getClient(this) }
    private val passiveMonitoringClient by lazy { healthClient.passiveMonitoringClient }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    //private val healthConnectClient by lazy { HealthConnectClient.getOrCreate(this) }

    @Volatile private var exerciseActive = false
    private val EX_CONF_MIN = 60 // min confidence to (auto)start exercise

    private var cumulativeSteps:     Long?   = null
    private var cumulativeCalories:  Double? = null
    private var cumulativeFloors:    Double? = null
    private var cumulativeElevation: Double? = null

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        Log.i(TAG, "ActivityRecognitionService onCreate()")
        //writeToLogFileLocation(logFile, logStr)
        activityRecognitionClient = ActivityRecognition.getClient(this)
        startForegroundServiceCompat(NOTIF_ID, importance = android.app.NotificationManager.IMPORTANCE_LOW)

        // Instead of a BroadcastReceiver, send the Intent back to this Service:
        val intent = Intent(this, ActivityRecognitionService::class.java).setAction(ACTION_RECOGNITION)

        callbackIntent = PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

        if (ContextCompat.checkSelfPermission( this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
            activityRecognitionClient.requestActivityUpdates(UPDATE_INTERVAL_MS, callbackIntent)
                .addOnSuccessListener { Log.i(TAG, "Activity updates requested every $UPDATE_INTERVAL_MS ms") }
                .addOnFailureListener { Log.e(TAG, "Failed to request activity updates", it) }
        } else {
            Log.w(TAG, "Cannot request activity updates: permission not granted")
        }

        scope.launch {
            try {
                val caps = passiveMonitoringClient.getCapabilities()

                Log.i(TAG, "Passive caps => ${caps.supportedDataTypesPassiveMonitoring}")

                val wanted = buildSet {
                    if (DataType.STEPS_DAILY   in caps.supportedDataTypesPassiveMonitoring)
                        add(DataType.STEPS_DAILY)
                    if (DataType.CALORIES_DAILY in caps.supportedDataTypesPassiveMonitoring)
                        add(DataType.CALORIES_DAILY)
                    if (DataType.FLOORS_DAILY  in caps.supportedDataTypesPassiveMonitoring)
                        add(DataType.FLOORS_DAILY)
                    if (DataType.ELEVATION_GAIN_DAILY in caps.supportedDataTypesPassiveMonitoring)
                        add(DataType.ELEVATION_GAIN_DAILY)
                    //  VO₂ Max (only if the device reports it passively)
                    //if (DataType.VO2_MAX in caps.supportedDataTypesPassiveMonitoring) add(DataType.VO2_MAX)
                }

                if (wanted.isEmpty()) {
                    Log.w(TAG, "No requested passive data types supported"); return@launch
                }


                val cfg = PassiveListenerConfig
                    .builder()
                    .setDataTypes(wanted)
                    .setHealthEventTypes(setOf(HealthEvent.Type.FALL_DETECTED))
                    .build()

                passiveMonitoringClient.setPassiveListenerCallback(cfg, passiveDailyListenerCallback)
                val logStr = "Registered passive listener for $wanted"
                Log.d(TAG, logStr)
                //writeToLogFileLocation(logFile, logStr)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to register passive listener", t)
            }
        }

        //syncVo2MaxLast30Days()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "ActivityRecognitionService onStartCommand()")
        // Handle every ACTION_RECOGNITION intent
        if (intent?.action == ACTION_RECOGNITION &&
            ActivityRecognitionResult.hasResult(intent)) {

            val result = ActivityRecognitionResult.extractResult(intent)
            val act = result?.mostProbableActivity
            val type = act?.type ?: DetectedActivity.UNKNOWN
            val detectedActivity = when (type) {
                DetectedActivity.RUNNING    -> ActivityRecognized.RUNNING
                DetectedActivity.WALKING    -> ActivityRecognized.WALKING
                DetectedActivity.TILTING    -> ActivityRecognized.TILTING
                DetectedActivity.STILL      -> ActivityRecognized.STILL
                DetectedActivity.ON_BICYCLE -> ActivityRecognized.BICYCLING
                DetectedActivity.IN_VEHICLE -> ActivityRecognized.IN_VEHICLE
                DetectedActivity.ON_FOOT    -> ActivityRecognized.ON_FOOT
                DetectedActivity.UNKNOWN    -> ActivityRecognized.UNKNOWN
                else                        -> ActivityRecognized.UNKNOWN
            }
            //val line = "Detected ${detectedActivity.name}  confidence=${act?.confidence ?: 100}"
            //Log.i(TAG, line)
            //writeToLogFileLocation(logFile, line)

            // TODO: Need to look at confidence and do sthg if confidence is less than 50? Maybe store in a data class
            if ((act?.confidence ?: 0) >= 50) {

                val cepState = when (detectedActivity) {
                    ActivityRecognized.RUNNING          -> "RUNNING"
                    ActivityRecognized.WALKING,
                    ActivityRecognized.ON_FOOT          -> "WALKING"
                    ActivityRecognized.STILL            -> "STILL"
                    ActivityRecognized.BICYCLING        -> "CYCLING"
                    ActivityRecognized.IN_VEHICLE       -> "DRIVING"
                    ActivityRecognized.TILTING,
                    ActivityRecognized.UNKNOWN          -> "UNKNOWN"
                }

                // Emit to CEP inside coroutine scope
                ioScope.launch {
                    CepBus.emit(
                        CepEvent(
                            topic = Topics.ACTIVITY,
                            payload = ActivityPayload(state = cepState)
                        )
                    )
                }

                // Persist in repo (backend-only)
                ioScope.launch {
                    //RestingHrManager.onActivityRecognized(detectedActivity.name)
                    repo.storeLatestActivityRecognized(detectedActivity)
                    repo.storeLatestActivityConfidence(act?.confidence ?: 100)
                }
            }
            return START_NOT_STICKY
        }

        return START_STICKY          // keep service alive
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "ActivityRecognitionService onDestroy()")

        if (!isRunning.getAndSet(false)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return
        scope.cancel()
        ioScope.cancel()
        passiveMonitoringClient.clearPassiveListenerCallbackAsync()
        activityRecognitionClient.removeActivityUpdates(callbackIntent).addOnSuccessListener { callbackIntent.cancel() }.addOnFailureListener { Log.e(TAG, "Failed to remove activity updates", it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /*private fun syncVo2MaxLast30Days() = ioScope.launch {
        // Optional: if you want to double-check permission here
        val need = setOf(HealthPermission.getReadPermission(Vo2MaxRecord::class))
        val granted = healthConnectClient.permissionController.getGrantedPermissions()
        if (!granted.containsAll(need)) {
            Log.w(TAG, "VO₂ Max read permission not granted (UI already handles asking). Skipping read.")
            return@launch
        }

        try {
            val end = Instant.now()
            val start = end.minus(30, ChronoUnit.DAYS)

            val resp = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    recordType = Vo2MaxRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )

            if (resp.records.isEmpty()) {
                Log.i(TAG, "No Vo₂MaxRecord found in last 30 days.")
                return@launch
            }

            // Example: take latest by time (Vo2MaxRecord extends InstantRecord)
            val latest = resp.records.maxByOrNull { it.time }
            latest?.let { r ->
                val method = when (r.measurementMethod) {
                    Vo2MaxRecord.MEASUREMENT_METHOD_METABOLIC_CART -> "Metabolic Cart"
                    Vo2MaxRecord.MEASUREMENT_METHOD_HEART_RATE_RATIO -> "Heart Rate Ratio"
                    Vo2MaxRecord.MEASUREMENT_METHOD_COOPER_TEST -> "Cooper"
                    Vo2MaxRecord.MEASUREMENT_METHOD_MULTISTAGE_FITNESS_TEST -> "MSFT"
                    Vo2MaxRecord.MEASUREMENT_METHOD_ROCKPORT_FITNESS_TEST -> "Rockport"
                    else -> "Other"
                }
                Log.i(TAG, "VO₂ Max latest: ${r.vo2MillilitersPerMinuteKilogram} mL·kg⁻¹·min⁻¹ @ ${r.time} (method=$method)")

                // TODO: persist / publish as needed:
                // DataRepository.getInstance().storeVo2Max(r.vo2, r.time, method)
                // or post to LiveData/EventBus for UI/engine use
            }

            // If you also want to iterate all:
            // resp.records.forEach { Log.d(TAG, "VO₂ Max: ${it.vo2} at ${it.time}") }

        } catch (t: Throwable) {
            Log.e(TAG, "Error reading Vo₂MaxRecord from Health Connect", t)
        }
    }*/

    //Placeholder fn for VO2_MAX
    fun getVo2Max(): Double {
        return 45.0
    }

    private val passiveDailyListenerCallback = object : PassiveListenerCallback {
        override fun onNewDataPointsReceived(container: DataPointContainer) {
            // Read the optional points
            // Each call may contain 0-many data points for each requested type
            container.getData(DataType.STEPS_DAILY).firstOrNull()?.value?.toLong()?.let { cumulativeSteps = it }
            container.getData(DataType.CALORIES_DAILY).firstOrNull()?.value?.toDouble()?.let { cumulativeCalories = it }
            container.getData(DataType.FLOORS_DAILY).firstOrNull()?.value?.toDouble()?.let { cumulativeFloors = it }
            container.getData(DataType.ELEVATION_GAIN_DAILY).firstOrNull()?.value?.toDouble()?.let { cumulativeElevation = it }

            // DEBUG LOG: shows the cached snapshot
            val logStr = "passiveDailyListenerCallback: Steps: $cumulativeSteps, Cals: $cumulativeCalories, Floors: $cumulativeFloors, Elevation: $cumulativeElevation m"
            Log.d(TAG, logStr)
            //writeToLogFileLocation(logFile, logStr)

            // Push snapshot to repository
            scope.launch {
                val nowHour = Instant.now().truncatedTo(ChronoUnit.HOURS)
                //Log.d(TAG, "passiveDailyListenerCallback: nowHour: $nowHour; Steps: $cumulativeSteps, Cals: $cumulativeCalories, Floors: $cumulativeFloors, Elevation: $cumulativeElevation m")
                repo.updateDailyMetrics(
                    timestampHour = nowHour,
                    steps         = cumulativeSteps,
                    calories      = cumulativeCalories,
                    floors        = cumulativeFloors,
                    elevationM    = cumulativeElevation
                )
            }
        }

        override fun onHealthEventReceived(event: HealthEvent) {
            Log.d(TAG, "HealthEvent: ${event.type} at ${event.eventTime}")
            if (event.type == HealthEvent.Type.FALL_DETECTED) {
                Log.w(TAG, "FALL_DETECTED @ ${event.eventTime}")

                // Post Fall Emergency Event
                EventBus.getDefault().post(
                    CustomEmergencyEvent(
                        category = CAT_FALL_DETECTED,
                        description = DESC_GOOGLE_SDK_FALL_DETECTED,
                        priority = 1,
                        sendSms = true   // set false if you don’t want SMS
                    )
                )
            }
            super.onHealthEventReceived(event)
        }
    }
}
