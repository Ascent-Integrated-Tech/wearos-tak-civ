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
import android.os.SystemClock
import android.util.Log
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.HealthEvent
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.SampleDataPoint
import androidx.health.services.client.getCapabilities
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.latestHeartRate
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.device_uid
import com.aitech.weartak_civ.service.BatteryService.Companion.batteryPercentage
import com.aitech.weartak_civ.service.LocationService.Companion.ce
import com.aitech.weartak_civ.service.LocationService.Companion.hae
import com.aitech.weartak_civ.service.LocationService.Companion.lat
import com.aitech.weartak_civ.service.LocationService.Companion.le
import com.aitech.weartak_civ.service.LocationService.Companion.lon
import com.aitech.weartak_civ.service.TAKConnectService.Companion.sendDataTAKServers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Instant

import java.util.Locale
import java.util.TimeZone

/**
 * Service to receive data from Health Services.
 *
 * Passive data is delivered from Health Services to this service. Override the appropriate methods
 * in [PassiveListenerService] to receive updates for new data points, goals achieved etc.
 */
class PassiveDataService : PassiveListenerService() {
    private val repository = DataRepository(this)
    private val dateTimeFormatter: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

    // TODO: Deprecate this service? Leave as an example?
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PassiveDataService created")
        serviceScope.launch {
            try {
                registerFallEvents()
                //Log.i(TAG, "Registered for FALL_DETECTED")
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
        Log.d(TAG, "PassiveDataService onCreate()")
    }

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        Log.d(TAG, "Passive data types: ${dataPoints.dataTypes}")

        //val bootInstant = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())

        dataPoints.getData(DataType.VO2_MAX).forEach { dp: SampleDataPoint<Double> ->
            val vo2Max: Double = dp.value                     // e.g., mL·kg⁻¹·min⁻¹
            //val whenMeasured: Instant = dp.getTimeInstant(bootInstant)
            Log.i(TAG, "VO2 Max: $vo2Max")

            // Persist if you want
            // serviceScope.launch(Dispatchers.IO) {
            //     repository.storeVo2Max(vo2Max, whenMeasured)
            // }
        }

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
        var latestHeartRate: Int? = null
        Log.d(TAG, "onNewDataPointsReceived")
        runBlocking {
            dataPoints.getData(DataType.HEART_RATE_BPM).latestHeartRate()?.let {
                repository.storeLatestHeartRate(it.toInt())
                latestHeartRate = it.toInt()
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            processBiometrics(callsign, latestHeartRate!!)
        }
    }

    /*private suspend fun registerFallEvents() {
        val client = HealthServices.getClient(this).passiveMonitoringClient
        val config = PassiveListenerConfig.builder()
            .setHealthEventTypes(setOf(HealthEvent.Type.FALL_DETECTED))
            .build()
        client.setPassiveListenerServiceAsync(PassiveDataService::class.java, config).await()
    }*/

    private suspend fun registerFallEvents() {
        val client = HealthServices.getClient(this).passiveMonitoringClient

        // Ask capabilities, then only request what the device supports
        val supported = client.getCapabilities().supportedDataTypesPassiveMonitoring

        val wanted = setOf(
            androidx.health.services.client.data.DataType.VO2_MAX
        ).intersect(supported)

        val config = androidx.health.services.client.data.PassiveListenerConfig
            .builder()
            .setHealthEventTypes(setOf(androidx.health.services.client.data.HealthEvent.Type.FALL_DETECTED))
            .setDataTypes(wanted)
            .build()

        client.setPassiveListenerServiceAsync(PassiveDataService::class.java, config).await()
    }


    override fun onDestroy() {
        super.onDestroy()
        if (isForeground.get()) stopForegroundMode()
        job.cancel()
    fun processBiometrics(callsign: String, heartrate: Int) {
        // Parse and manipulate timestamps
        dateTimeFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        val bioDateTime = Date()
        val bioTimeStr = dateTimeFormatter.format(bioDateTime)

        // Create a Calendar object and set it to the given date
        val calendar: Calendar = Calendar.getInstance()
        calendar.setTime(bioDateTime)

        // Add 120 seconds to the calendar
        calendar.add(Calendar.SECOND, 120)

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
        Log.d(TAG, "Sending biometric COT msg: $cotMsg")
        sendDataTAKServers(cotMsg, false)
    }

    companion object {
        private val TAG = "WearTAK-" + PassiveDataService::class.java.simpleName
        private const val BIO_PART1 = "<?xml version='1.0' encoding='UTF-8' standalone='yes'?><event version='2.0' uid='"
        private const val BIO_PART2 = "' type='t-x-c-heartRate' time='"
        private const val BIO_PART3 = "' how='m-g' access='Undefined'><point lat='"
        private const val BIO_PART4 = "' /><detail><status serialNumber='"
        private const val BIO_PART5 = "'/></detail></event>"
    }
}
