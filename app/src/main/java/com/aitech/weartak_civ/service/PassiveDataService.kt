/*
 * Copyright (C) 2024, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service

import android.util.Log
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
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

    override fun onCreate() {
        return super.onCreate()
    }

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
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
        sendDataTAKServers(cotMsg)
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
