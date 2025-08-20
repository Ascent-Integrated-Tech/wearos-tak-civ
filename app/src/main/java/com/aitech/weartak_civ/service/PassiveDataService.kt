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
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.concurrent.futures.await
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.HealthEvent
import androidx.health.services.client.data.PassiveListenerConfig
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.CustomEmergencyEvent
import com.aitech.weartak_civ.events.CustomEmergencyEvent.Companion.CAT_FALL_DETECTED
import com.aitech.weartak_civ.events.CustomEmergencyEvent.Companion.DESC_GOOGLE_SDK_FALL_DETECTED
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service to receive data from Health Services.
 *
 * Passive data is delivered from Health Services to this service. Override the appropriate methods
 * in [PassiveListenerService] to receive updates for new data points, goals achieved etc.
 */
class PassiveDataService : PassiveListenerService() {
    private val repository = DataRepository(this)
    private val dateTimeFormatter: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

    private val job = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + job)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PassiveDataService created")
        serviceScope.launch {
            try {
                registerFallEvents()
                Log.i(TAG, "Registered for FALL_DETECTED")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to register fall events: ${t.message}", t)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceCompat(
            NOTIF_ID,
            importance = android.app.NotificationManager.IMPORTANCE_LOW
        )
        /*
        when (intent?.action) {
            ACTION_START_FOREGROUND -> startInForeground()
            ACTION_STOP_FOREGROUND -> stopForegroundMode()
        }

         */
        return START_STICKY
    }

    private fun startInForeground() {
        if (isForeground.get()) return
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Monitoring falls")
            .setContentText("Running (foreground)")
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setOngoing(true)
            .build()
        // If you target Android 14+, also declare the type (see manifest snippet below).
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            notification,
            /* foregroundServiceType = */ 0  // Defaults to manifest-declared types.
        )
        isForeground.set(true)
        Log.d(TAG, "Entered foreground mode")
    }

    private fun stopForegroundMode() {
        if (!isForeground.get()) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        isForeground.set(false)
        Log.d(TAG, "Exited foreground mode")
    }

    // Logs any passive datapoints you’ve subscribed to (not required for falls).
    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        Log.d(TAG, "Passive data types: ${dataPoints.dataTypes}")
        super.onNewDataPointsReceived(dataPoints)
    }

    // The one thing we care about right now: detect and log a fall.
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

            // Removed. Sending out a custom event to TAK
            //broadcastFallDetected(event)
        }
        super.onHealthEventReceived(event)
    }

    private fun broadcastFallDetected(event: HealthEvent) {
        // Use current time for robustness across API versions
        val nowMs = System.currentTimeMillis()
        val nowIso = dateTimeFormatter.format(Date(nowMs))

        val intent = Intent(ACTION_FALL_DETECTED).apply {
            // Make it app-private (safer + more reliable delivery on modern Android)
            setPackage(packageName)

            putExtra(EXTRA_OCCURRED_AT_EPOCH_MS, nowMs)
            putExtra(EXTRA_OCCURRED_AT_ISO_UTC, nowIso)
            putExtra(EXTRA_EVENT_TYPE, event.type.name)
        }

        sendBroadcast(intent)
        Log.i(TAG, "Broadcasted ACTION_FALL_DETECTED ($nowIso)")
    }

    private suspend fun registerFallEvents() {
        val client = HealthServices.getClient(this).passiveMonitoringClient
        val config = PassiveListenerConfig.builder()
            .setHealthEventTypes(setOf(HealthEvent.Type.FALL_DETECTED))
            .build()
        client.setPassiveListenerServiceAsync(PassiveDataService::class.java, config).await()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isForeground.get()) stopForegroundMode()
        job.cancel()
    }

    companion object {
        val isRunning = AtomicBoolean(false)
        private val TAG = "WearTAK-" + PassiveDataService::class.java.simpleName

        private const val CHANNEL_ID = "fall_monitor"
        private const val NOTIF_ID = 42

        const val ACTION_FALL_DETECTED = "com.aitech.weartak_civ.ACTION_FALL_DETECTED"
        const val EXTRA_OCCURRED_AT_EPOCH_MS = "occurred_at_epoch_ms"
        const val EXTRA_OCCURRED_AT_ISO_UTC = "occurred_at_iso_utc"
        const val EXTRA_EVENT_TYPE = "event_type"

        const val ACTION_START_FOREGROUND =
            "com.aitech.weartak_civ.service.PassiveDataService.START_FG"
        const val ACTION_STOP_FOREGROUND =
            "com.aitech.weartak_civ.service.PassiveDataService.STOP_FG"

        val isForeground = AtomicBoolean(false)

        // Helpers you can call from an Activity/Receiver:
        fun startForegroundMode(ctx: Context) =
            ctx.startForegroundService(Intent(ctx, PassiveDataService::class.java).apply {
                action = ACTION_START_FOREGROUND
            })

        fun stopForegroundMode(ctx: Context) =
            ctx.startService(Intent(ctx, PassiveDataService::class.java).apply {
                action = ACTION_STOP_FOREGROUND
            })
    }
}


/***
 *Service declarations (inside <application>):
 *    <!-- Fall monitor service (Health Services passive listener) -->
 *    <service
 *        android:name=".service.PassiveDataService"
 *        android:exported="true"
 *        android:permission="com.google.android.wearable.healthservices.permission.PASSIVE_DATA_BINDING"
 *        android:foregroundServiceType="health" />
 *
 *    <!-- App-private receiver to react to fall events -->
 *    <receiver
 *        android:name=".service.FallDetectedReceiver"
 *        android:exported="false">
 *        <intent-filter>
 *            <action android:name="com.aitech.weartak_civ.ACTION_FALL_DETECTED" />
 *        </intent-filter>
 *    </receiver>
 *
 *    <!-- Optional: Foreground service for SOS network send -->
 *    <service
 *        android:name=".service.SosService"
 *        android:exported="false"
 *        android:foregroundServiceType="dataSync" />
 *
 *RECEIVER IMPLEMENTATION (APP-PRIVATE)
 * ====================================
 * Purpose: Listen for ACTION_FALL_DETECTED and trigger SOS pipeline.
 *
 * class FallDetectedReceiver : BroadcastReceiver() {
 *     override fun onReceive(context: Context, intent: Intent) {
 *         if (intent.action != PassiveDataService.ACTION_FALL_DETECTED) return
 *
 *         val whenMs = intent.getLongExtra(PassiveDataService.EXTRA_OCCURRED_AT_EPOCH_MS, System.currentTimeMillis())
 *         val whenIso = intent.getStringExtra(PassiveDataService.EXTRA_OCCURRED_AT_ISO_UTC) ?: "unknown"
 *         // Optional: val type = intent.getStringExtra(PassiveDataService.EXTRA_EVENT_TYPE) ?: "FALL_DETECTED"
 *
 *         // Option A: Start a short-lived Foreground Service to send SOS
 *         val svc = Intent(context, SosService::class.java).apply {
 *             action = SosService.ACTION_SEND_SOS_FALL
 *             putExtra(SosService.EXTRA_OCCURRED_AT_EPOCH_MS, whenMs)
 *             putExtra(SosService.EXTRA_OCCURRED_AT_ISO_UTC, whenIso)
 *         }
 *         context.startForegroundService(svc)
 *
 *         // Option B (if you already have a persistent sender): call it directly here.
 *         // TAKConnectService.sendDataTAKServers(payload)
 *     }
 * }
 */
