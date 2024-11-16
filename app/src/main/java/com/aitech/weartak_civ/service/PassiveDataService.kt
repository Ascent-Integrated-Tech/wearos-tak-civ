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
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.latestHeartRate
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.BIO_PART1
import com.aitech.weartak_civ.presentation.MainActivity.Constants.BIO_PART2
import com.aitech.weartak_civ.presentation.MainActivity.Constants.BIO_PART3
import com.aitech.weartak_civ.presentation.MainActivity.Constants.BIO_PART4
import com.aitech.weartak_civ.presentation.MainActivity.Constants.BIO_PART5
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.device_uid
import com.aitech.weartak_civ.service.BatteryMonitorService.Companion.batteryPercentage
import com.aitech.weartak_civ.service.LocationService.Companion.ce
import com.aitech.weartak_civ.service.LocationService.Companion.hae
import com.aitech.weartak_civ.service.LocationService.Companion.lat
import com.aitech.weartak_civ.service.LocationService.Companion.le
import com.aitech.weartak_civ.service.LocationService.Companion.lon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Service to receive data from Health Services.
 *
 * Passive data is delivered from Health Services to this service. Override the appropriate methods
 * in [PassiveListenerService] to receive updates for new data points, goals achieved etc.
 */
class PassiveDataService : PassiveListenerService() {
    private val repository = DataRepository(this)
    private val dateTimeFormatter: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    private var secureSocket : SSLSocket? = null
    private var isConnecting : AtomicBoolean = AtomicBoolean(false)

    override fun onCreate() {
        CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "PassiveDataService: onCreate called")
            connectTAKServer(R.raw.tak_il_ascent_user, "atakatak", "tak.il.ops.ascentitech.com")
        }
        return super.onCreate()
    }

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        var latestHeartRate: Double? = null
        Log.d(TAG, "onNewDataPointsReceived")
        runBlocking {
            dataPoints.getData(DataType.HEART_RATE_BPM).latestHeartRate()?.let {
                repository.storeLatestHeartRate(it)
                latestHeartRate = it
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            processBiometrics(secureSocket, callsign, latestHeartRate!!)
        }
    }

    fun processBiometrics(secureSock: SSLSocket?, callsign: String, heartrate: Double) {
        try {
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
                cotMsg = cotMsg + lat + "' lon='" + lon + "' hae='" + java.lang.String.format(Locale.ENGLISH, "%.2f", haeActual) +
                        "' ce='" + java.lang.String.format(Locale.ENGLISH, "%.2f", ceActual) + "' le='" + java.lang.String.format(
                    Locale.ENGLISH, "%.2f", leActual) +
                        BIO_PART4 + "SGWOS" + callsign +
                        "' heartRate='$heartrate' " +
                        "callsign='" + callsign +
                        "' model='WEAROS" +
                        "' batteryLevel='$batteryPercentage" + BIO_PART5
            } else {
                cotMsg = cotMsg + "0.0' lon='0.0' hae='9999999.0' ce='9999999.0' le='9999999.0" +
                        BIO_PART4 + "SG6WOS" + callsign +
                        "' heartRate='$heartrate' " +
                        "callsign='" + callsign +
                        "' model='WEAROS" +
                        "' batteryLevel='$batteryPercentage" + BIO_PART5
            }

            // Send the CoT message
            Log.d(TAG,"Sending biometric COT msg: $cotMsg")
            if (isConnecting.get()) {
                Log.i(TAG, "In the process of connecting to TAK server. Not sending this biometric message...")
                return
            }
            if ((null != secureSock) && secureSock.isConnected) {
                val outputStream = secureSock.getOutputStream()
                outputStream.write(cotMsg.toByteArray(charset("UTF-8")))
                outputStream.flush()
            } else {
                if (null != secureSock) {
                    secureSock.close()
                }
                connectTAKServer( R.raw.tak_il_ascent_user, "atakatak", "tak.il.ops.ascentitech.com")
            }
        } catch (e: IOException) {
            e.printStackTrace()
            System.err.println("Error sending biometric COT message: " + e.message)
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
            System.err.println("Unexpected error sending biometric COT message: " + e.message)
        }
    }

    fun connectTAKServer(userCertP12ResourceId: Int, userCertP12Password: String, takServer: String) {
        isConnecting.set(true)
        Log.d(TAG,"connectTAKServer called")
        try {
            // Load client certificate
            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(MainActivity.mainActivity.resources.openRawResource(userCertP12ResourceId), userCertP12Password.toCharArray())

            // Initialize the KeyManagerFactory with the client certificate
            val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keyManagerFactory.init(keyStore, userCertP12Password.toCharArray())

            // Initialize SSL Context with TrustManager
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(keyManagerFactory.keyManagers, arrayOf<TrustManager>(object :
                X509TrustManager {
                override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {
                }

                override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> {
                    return emptyArray()
                }
            }),
                null
            )

            // Create the socket and connect
            val socketFactory = sslContext.socketFactory
            secureSocket = socketFactory.createSocket(takServer, 8089) as SSLSocket
            secureSocket!!.startHandshake()

            Log.d(TAG,"Successfully connected to $takServer on port 8089")
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "Error connectTAKServer:", e)
        }
        isConnecting.set(false)
    }

    companion object {
        private val TAG = "WearTAK-" + PassiveDataService::class.java.simpleName
    }
}
