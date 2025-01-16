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
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class TAKConnectService: Service() {

    override fun onCreate() {
        Log.d(TAG, "TAKConnectService onCreate")
        return super.onCreate()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        startForegroundService()
        CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "onStartCommand: calling connectTAKServer")
            connectTAKServer(TAK_CERT, TAK_CERT_PASSWORD, TAK_SERVER)
        }
        return START_NOT_STICKY
    }

    fun connectTAKServerUsernamePassword(server: String, username: String, password: String, port: Int): Boolean {
        //connectTAKServer(TAK_CERT, TAK_CERT_PASSWORD, TAK_SERVER)
        return true
    }

    private fun startForegroundService() {
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        } else {
            "TAK Connect Service"
        }

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
        val notification = notificationBuilder.setOngoing(true)
            .setSmallIcon(R.drawable.splash_icon)
            .setContentText("WearTAK-CIV TAK Connect service running")
            .setPriority(Context.BIND_WAIVE_PRIORITY)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        startForeground(TAK_CONNECT_SERVICE_NOTIFICATION_ID, notification)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(): String {
        val channelId = "TAK Connect Service"
        val channelName = "TAK Connect Background Service"
        val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
        chan.lightColor = Color.GRAY
        chan.importance = NotificationManager.IMPORTANCE_NONE
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)
        return channelId
    }

    override fun onBind(intent: Intent): IBinder? {
        throw UnsupportedOperationException("Not yet implemented")
    }

    override fun onDestroy() {
        super.onDestroy()
        CoroutineScope(Dispatchers.IO).launch {
            secureSocket?.close()
            Log.d(TAG, "TAK Connect Service Destroyed")
        }
    }



    companion object {
        private val TAG = "WearTAK-" + TAKConnectService::class.java.simpleName
        const private val TAK_CONNECT_SERVICE_NOTIFICATION_ID = 101
        private var secureSocket : SSLSocket? = null
        var isConnecting : AtomicBoolean = AtomicBoolean(false)
        const private val TAK_SERVER = "tak.test.ops.ascentitech.com"
        const private val TAK_CERT_PASSWORD = "atakatak"
        private val TAK_CERT = R.raw.tak_test_ascent_user
        var isConnected : Boolean = false
        val isConnectedLiveData = MutableLiveData<Boolean>(false)

        /*
        const private val TAK_SERVER = "56.136.23.111"
        const private val TAK_CERT_PASSWORD = "QeD6HaCYlQ6l1Tr"
        private val TAK_CERT = R.raw.tak_af_event_user
         */

        fun connectTAKServer(userCertP12ResourceId: Int, userCertP12Password: String, takServer: String) {
            isConnected = false
            isConnectedLiveData.postValue(isConnected)

            Log.d(TAG,"connectTAKServer called")
            if (isConnecting.getAndSet(true) == false) {
                try {
                    // Load client certificate
                    val keyStore = KeyStore.getInstance("PKCS12")
                    keyStore.load(
                        MainActivity.mainActivity.resources.openRawResource(
                            userCertP12ResourceId
                        ), userCertP12Password.toCharArray()
                    )

                    // Initialize the KeyManagerFactory with the client certificate
                    val keyManagerFactory =
                        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                    keyManagerFactory.init(keyStore, userCertP12Password.toCharArray())

                    // Initialize SSL Context with TrustManager
                    val sslContext = SSLContext.getInstance("TLS")
                    sslContext.init(
                        keyManagerFactory.keyManagers, arrayOf<TrustManager>(object :
                            X509TrustManager {
                            override fun checkClientTrusted(
                                certs: Array<X509Certificate>,
                                authType: String
                            ) {
                            }

                            override fun checkServerTrusted(
                                certs: Array<X509Certificate>,
                                authType: String
                            ) {
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
                    isConnected = true
                    isConnectedLiveData.postValue(isConnected)

                    Log.d(TAG, "Successfully connected to $takServer on port 8089")
                } catch (e: Exception) {
                    e.printStackTrace()
                    isConnected = false
                    isConnectedLiveData.postValue(isConnected)

                    Log.e(TAG, "Error connectTAKServer:", e)
                }
            }
            isConnecting.set(false)
        }

        fun sendDataTAKServer(msg: String) {
            if (isConnecting.get()) {
                Log.i(TAG, "Connecting to TAK server. Hence not sending this message: $msg")
                return
            }
            CoroutineScope(Dispatchers.IO).launch {
                if (isLicenseValid()) {
                    Log.d(TAG,"License Valid - Connecting to Server")
                    try {
                        if ((null != secureSocket) && secureSocket!!.isConnected) {
                            val outputStream = secureSocket!!.getOutputStream()
                            outputStream.write(msg.toByteArray(charset("UTF-8")))
                            outputStream.flush()
                        } else {
                            secureSocket?.close()
                            connectTAKServer(
                                TAK_CERT,
                                TAK_CERT_PASSWORD,
                                TAK_SERVER
                            )
                        }
                    } catch (e: IOException) {
                        e.printStackTrace()
                        System.err.println("Error sending biometric COT message: " + e.message)
                        secureSocket?.close()
                        connectTAKServer(
                            TAK_CERT,
                            TAK_CERT_PASSWORD,
                            TAK_SERVER
                        )
                    } catch (e: java.lang.Exception) {
                        e.printStackTrace()
                        System.err.println("Unexpected error sending biometric COT message: " + e.message)
                        secureSocket?.close()
                        connectTAKServer(
                            TAK_CERT,
                            TAK_CERT_PASSWORD,
                            TAK_SERVER
                        )
                    }
                } else {
                    Log.d(TAG,"License Invalid - no Connection to Server")
                }
            }
        }
    }
}