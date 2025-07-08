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
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
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
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.Utils.writeToLogFileLocation
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.getCapabilities
import com.aitech.weartak_civ.manager.RestingHrManager
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

class ActivityRecognitionService : Service() {
    companion object {
        private const val TAG = "WearTAK-ActRecService"
        private const val UPDATE_INTERVAL_MS = 5_000L
        private const val ACTION_RECOGNITION = "com.aitech.weartak_civ.ACTION_RECOGNITION"
        private const val NOTIF_ID = 192
        private const val LOG_FILENAME = "/sdcard/Download/activity_detection.log"
        val isRunning: AtomicBoolean = AtomicBoolean(false)
    }

    private val logFile by lazy { File(LOG_FILENAME) }

    private lateinit var activityRecognitionClient: ActivityRecognitionClient
    private lateinit var callbackIntent: PendingIntent
    private val repo: DataRepository
        get() = MainActivity.dataRepository

    private val healthClient by lazy { HealthServices.getClient(this) }
    private val passiveMonitoringClient by lazy { healthClient.passiveMonitoringClient }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ActivityRecognitionService onCreate")

        activityRecognitionClient = ActivityRecognition.getClient(this)
        startForegroundServiceCompat(
            NOTIF_ID,
            importance = android.app.NotificationManager.IMPORTANCE_LOW
        )

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
                val capabilities = passiveMonitoringClient.getCapabilities()

                val supportsSteps  =
                    DataType.STEPS_DAILY in capabilities.supportedDataTypesPassiveMonitoring
                val supportsCals   =
                    DataType.CALORIES_DAILY in capabilities.supportedDataTypesPassiveMonitoring
                val supportsFloors =
                    DataType.FLOORS_DAILY in capabilities.supportedDataTypesPassiveMonitoring

                if (supportsSteps && supportsCals && supportsFloors) {
                    val passiveListenerConfig = PassiveListenerConfig.builder()
                        .setDataTypes(
                            setOf(
                                DataType.CALORIES_DAILY,
                                DataType.STEPS_DAILY,
                                DataType.FLOORS_DAILY
                            )
                        )
                        .build()

                    passiveMonitoringClient.setPassiveListenerCallback(
                        passiveListenerConfig,
                        passiveDailyListenerCallback
                    )

                    Log.i(TAG, "Passive daily listener registered")
                } else {
                    Log.w(TAG, "Device does not support one or more requested data types")
                }

            } catch (t: Throwable) {
                Log.e(TAG, "Failed to register passive listener", t)
            }
        }

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle every ACTION_RECOGNITION intent
        if (intent?.action == ACTION_RECOGNITION &&
            ActivityRecognitionResult.hasResult(intent)) {

            val result = ActivityRecognitionResult.extractResult(intent)
            val act = result?.mostProbableActivity
            val type = act?.type ?: DetectedActivity.UNKNOWN
            val name = when (type) {
                DetectedActivity.RUNNING    -> "RUNNING"
                DetectedActivity.WALKING    -> "WALKING"
                DetectedActivity.TILTING    -> "TILTING"
                DetectedActivity.STILL      -> "STILL"
                DetectedActivity.ON_BICYCLE -> "BICYCLING"
                DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
                DetectedActivity.ON_FOOT    -> "ON_FOOT"
                DetectedActivity.UNKNOWN    -> "UNKNOWN"
                else                        -> "UNKNOWN"
            }
            // TODO: Need to look at confidence and do sthg if confidence is less than 50?
            val line = "Detected $name  confidence=${act?.confidence ?: 100}"
            Log.i(TAG, line)
            //writeToLogFileLocation(logFile, line)

            // Persist in repo (backend-only)
            scope.launch {
                //RestingHrManager.onActivityRecognized(name)
                repo.storeLatestActivityRecognized(name)
                repo.storeLatestActivityConfidence(act?.confidence ?: 100)
            }
            return START_NOT_STICKY
        }

        return START_STICKY          // keep service alive
    }

    override fun onDestroy() {
        super.onDestroy()

        if (!isRunning.getAndSet(false)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return
        scope.cancel()
        passiveMonitoringClient.clearPassiveListenerCallbackAsync()
        activityRecognitionClient.removeActivityUpdates(callbackIntent).addOnSuccessListener { callbackIntent.cancel() }.addOnFailureListener { Log.e(TAG, "Failed to remove activity updates", it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val passiveDailyListenerCallback = object : PassiveListenerCallback {
        override fun onNewDataPointsReceived(container: DataPointContainer) {
            // Each call may contain 0-many data points for each requested type
            val steps    = container.getData(DataType.STEPS_DAILY).firstOrNull()?.value
            val calories = container.getData(DataType.CALORIES_DAILY).firstOrNull()?.value
            val floors   = container.getData(DataType.FLOORS_DAILY).firstOrNull()?.value

            if (steps != null || calories != null || floors != null) {
                scope.launch {
                    // Store into your repository; adjust model as needed
                    val nowHour = java.time.Instant.now()
                        .truncatedTo(java.time.temporal.ChronoUnit.HOURS)

                    repo.upsertDailyHealthMetrics(
                        timestampHour = nowHour,
                        steps         = steps    ?: 0,
                        calories      = calories ?: 0.0,
                        elevationM    = floors?.times(3.048) ?: 0.0 // 1 floor ≈ 3.048 m
                    )
                }
            }
        }
    }
}
