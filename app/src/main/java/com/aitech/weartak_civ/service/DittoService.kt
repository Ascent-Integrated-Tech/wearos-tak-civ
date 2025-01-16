/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

/*
 *  DittoService handles Ditto's sync lifecycle (startSync, stopSync) and related operations.
 */

package com.aitech.weartak_civ.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aitech.weartak_civ.DittoApplication
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import live.ditto.DittoDocumentId
import live.ditto.DittoError
import live.ditto.DittoSyncSubscription
import live.ditto.transports.DittoTransportConfig
import java.text.SimpleDateFormat
import java.util.Calendar

class DittoService : Service() {

    private lateinit var dataRepository: DataRepository
    private var dittoUpdateHandler: Handler? = null
    private var dittoUpdateThread: HandlerThread? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO) // Single CoroutineScope for background I/O tasks, ensuring lifecycle management, error isolation, and cleaner code.
    private val ditto = DittoApplication.getDittoInstance()
    private val uuid = MainActivity.device_uid
    private var subscription: DittoSyncSubscription? = null
    private var localPeerKeyString = ditto.presence.graph.localPeer.peerKeyString

    override fun onCreate() {
        super.onCreate()
        dataRepository = MainActivity.dataRepository // or another injection approach
        Log.d(TAG, "Oncreate Called")
    }

    private fun configureDitto() {
        val config = DittoTransportConfig()
        config.enableAllPeerToPeer() //Enable all peer to peer transports
        ditto.transportConfig = config
    }

    private val dittoUpdateRunnable = object : Runnable {
        override fun run() {
            ioScope.launch {

                val location = dataRepository.latestLocation.value
                var heartRate = dataRepository.latestHeartRate.value
                val battery = dataRepository.latestBatteryPercentage.value

                if (location != null && battery != null) {
                    if (heartRate == null) {
                        heartRate = -1
                    }
                    updateTrackDocument(location)
//                    logAllDevices()
                } else {
                    Log.w(TAG, "Missing data for Ditto database. Not sending.")
                }
            }

            // Schedule the next run
            dittoUpdateHandler?.postDelayed(this, dittoUpdateIntervalSecs * 1000L)
        }
    }

    private fun startDispatchScheduler() {
        dittoUpdateThread = HandlerThread("CotDispatchThread")
        dittoUpdateThread?.start()
        dittoUpdateHandler = dittoUpdateThread?.looper?.let { Handler(it) }

        // Schedule the first run
        dittoUpdateHandler?.postDelayed(dittoUpdateRunnable, DISPATCH_INTERVAL)
    }

    private fun updateTrackDocument(location: Location) {
//        Log.d(TAG, "Updating track Document for $uuid")
        ioScope.launch{
//            val timeMs = Calendar.getInstance().timeInMillis
            val timeMs = System.currentTimeMillis()

            Log.d(TAG, "timeMs is $timeMs type is: " + timeMs.javaClass.kotlin)
            val trackDocument = mapOf(
                "_id" to uuid,
                "authorCs" to callsign,
                "authorId" to uuid,
                "authorType" to "a-f-G-U-C",
                "cs" to callsign,
                "lat" to location?.latitude,
                "lon" to location?.longitude,
                "pks" to localPeerKeyString,
                "removed" to false,
                "schver" to 1,
                "staleMs" to 100000,
                "takUid" to uuid,
                "timeMs" to timeMs,
                "type" to "a-f-G-U-C",
                "visible" to "true"
            )
            val insertQuery = "INSERT INTO track DOCUMENTS (:trackDocument) ON ID CONFLICT DO UPDATE"
            ditto.store.execute(insertQuery, mapOf("trackDocument" to trackDocument))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop syncing when the service is destroyed
        try {
            ditto.stopSync()
            Log.d("DittoService", "Sync stopped")
        } catch (e: DittoError) {
            Log.e("DittoService", "Error stopping sync: ${e.message}")
        }
        stopForeground(true)
        Log.d(TAG, "Subscription has been closed.")
        subscription?.close() // stop subscribing to track collection
        subscription = null
        ioScope.cancel() // cancel all coroutines when service is destroyed
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        isRunning = true
        startForegroundService() // service needs to work in the background when screen is off
        startDispatchScheduler() // we want to update our ditto data at fixed interval
        Log.d(TAG, "Subscribing to [ track ] collection")
        subscription = ditto.sync.registerSubscription("SELECT * FROM track")

        configureDitto() // configure all peer transports
        try {
            ditto.startSync() // Start syncing
            Log.d("DittoService", "Sync started")
        } catch (e: DittoError) {
            Log.e("DittoService", "Error starting sync: ${e.message}")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Not a bound service
    }

    private fun startForegroundService() {
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        } else {
            "DittoService"
        }

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
        val notification = notificationBuilder.setOngoing(true)
            .setSmallIcon(R.drawable.splash_icon)
            .setContentText("WearTAK-CIV Ditto service running")
            .setPriority(Context.BIND_WAIVE_PRIORITY)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        try {
            startForeground(DITTO_SERVICE_NOTIFICATION_ID, notification)
        } catch (e: NullPointerException) {
            Log.e(TAG, "Failed to set Ditto ForegroundService", e )
        }
    }

    private fun createNotificationChannel(): String {
        val channelId = "Ditto_Service"
        val channelName = "Ditto Background Service"
        val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
        chan.lightColor = Color.GRAY
        chan.importance = NotificationManager.IMPORTANCE_NONE
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)
        return channelId
    }

    companion object {
        private val TAG = "WearTAK-" + DittoService::class.java.simpleName
        private val DITTO_SERVICE_NOTIFICATION_ID = 3333
        var isRunning : Boolean = false
        private const val DISPATCH_INTERVAL = 3_000L // 3 seconds
        var dittoUpdateIntervalSecs = 5
    }

}
