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
import com.aitech.weartak_civ.presentation.MainActivity.Constants.role
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamValue
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
    private val model = android.os.Build.MODEL
    private val manufacturer = android.os.Build.MANUFACTURER


    override fun onCreate() {
        super.onCreate()
        localPeerKeyString = ditto.presence.graph.localPeer.peerKeyString
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
            dittoUpdateHandler?.postDelayed(this, CotDispatchService.reportingUpdateIntervalSecs * 1000L)
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
            val staleMsOffset = 10000
            val staleTimeMs = timeMs+staleMsOffset
            val location = dataRepository.latestLocation.value
            var heartRate = dataRepository.latestHeartRate.value
            val battery = dataRepository.latestBatteryPercentage.value
            val latActual = location?.latitude ?: 0.0
            val lonActual = location?.longitude ?: 0.0
            val haeActual = location?.altitude ?: 0.0
            var ceActual = location?.accuracy ?: 9999999.0F
            if (ceActual.isNaN() == true) ceActual = 9999999.0F
            var leActual : Float = location?.verticalAccuracyMeters ?: 9999999.0F
            if (leActual.isNaN() == true) leActual = 9999999.0F
            val bearingActual = location?.bearing ?: 0.0F
            val speedActual = location?.speed ?: 0.0F
            val deviceModel = manufacturer + " " + model

            val cotStringValue = "{a=Undefined, c=null, d={0={a={}, d={0={a={device=$deviceModel, os=34, platform=WEARTAK-CIV, version=5.3.0.10 (37fe1ccd) [playstore].1737557220-CIV}, d={}, i=null, n=takv}, 1={a={callsign=$callsign, endpoint=0.0.0.0:4242:tcp, phone=123456789}, d={}, i=null, n=contact}, 2={a={Droid=$callsign}, d={}, i=null, n=uid},3={a={altsrc=NETD, geopointsrc=NETD}, d={}, i=null, n=precisionlocation},4={a={name=$teamValue, role=$roleValue}, d={}, i=null, n=_group},5={a={battery=$battery}, d={}, i=null, n=status},6={a={deviceName=$callsign, ip=0.0.0.0, pks=$localPeerKeyString, version=AndJ4.8.1_9afa7353b2), d={}, i=null, n=ditto},7={a={course=$bearingActual, speed=$speedActual}, d={}, i=null, n=track}}, i=null, n=detail}}, h=m-g, o=null, p={c=$ceActual, h=$haeActual, la=$latActual,le=$leActual, 1o=$lonActual}, q=null, r=null, t={ms=$timeMs}, t1={ms=$timeMs}, t2={ms=$staleTimeMs}, u=$uuid, v=2.0, y=a-f-G-U-C}"

            // _id: WEAROS_2fffd2c2438ca082
//            val trackDocument = mapOf(
//                "_id" to uuid,
//                "authorCs" to callsign,
//                "authorId" to uuid,
//                "authorType" to "a-f-G-U-C",
//                "cs" to callsign,
//                "lat" to latActual,
//                "lon" to lonActual,
//                "pks" to localPeerKeyString,
//                "removed" to false,
//                "schver" to 1,
//                "staleMs" to staleMsOffset,
//                "takUid" to uuid,
//                "timeMs" to timeMs.toDouble(),
//                "type" to "a-f-G-U-C",
//                "visible" to "true",
//                "cot" to cotStringValue
//            )

            val cotMap = mapOf(
                "a" to "Undefined",
                "c" to null,
                "d" to mapOf(
                    "0" to mapOf(
                        "a" to emptyMap<String, Any?>(),
                        "d" to mapOf(
                            "0" to mapOf(
                                "a" to mapOf(
                                    "device" to deviceModel,
                                    "os" to "34",
                                    "platform" to "WearTAK-CIV",
                                    "version" to "0.34"
                                ),
                                "d" to emptyMap<String, Any?>(),
                                "i" to null,
                                "n" to "takv"
                            ),
                            "1" to mapOf(
                                "a" to mapOf(
                                    "callsign" to callsign,
                                    "endpoint" to "0.0.0.0:4242:tcp",
                                    "phone" to "1234567890"
                                ),
                                "d" to emptyMap<String, Any?>(),
                                "i" to null,
                                "n" to "contact"
                            ),
                            "2" to mapOf(
                                "a" to mapOf(
                                    "Droid" to callsign
                                ),
                                "d" to emptyMap<String, Any?>(),
                                "i" to null,
                                "n" to "uid"
                            ),
                            "3" to mapOf(
                                "a" to mapOf(
                                    "altsrc" to "GPS",
                                    "geopointsrc" to "GPS"
                                ),
                                "d" to emptyMap<String, Any?>(),
                                "i" to null,
                                "n" to "precisionlocation"
                            ),
                            "4" to mapOf(
                                "a" to mapOf(
                                    "name" to teamValue,
                                    "role" to roleValue,
                                ),
                                "d" to emptyMap<String, Any?>(),
                                "i" to null,
                                "n" to "__group"
                            ),
                            "5" to mapOf(
                                "a" to mapOf(
                                    "battery" to battery.toString(),
                                ),
                                "d" to emptyMap<String, Any?>(),
                                "i" to null,
                                "n" to "status"
                            ),
                            "6" to mapOf(
                                "a" to mapOf(
                                    "deviceName" to callsign,
                                    "ip" to "0.0.0.0",
                                    "pks" to localPeerKeyString,
                                    "version" to "AndJ4.8.1_9afa7353b2"
                                ),
                                "d" to emptyMap<String, Any?>(),
                                "i" to null,
                                "n" to "ditto"
                            ),
                            "7" to mapOf(
                                "a" to mapOf(
                                    "course" to bearingActual.toString(),
                                    "speed" to speedActual.toString()
                                ),
                                "d" to emptyMap<String, Any?>(),
                                "i" to null,
                                "n" to "track"
                            ),
                        ),
                        "i" to null,
                        "n" to "detail"
                    ),
                ),
                "h" to "m-g",
                "o" to null,
                "p" to mapOf(
                    "c" to ceActual.toString(),
                    "h" to haeActual.toString(),
                    "la" to latActual.toString(),
                    "le" to leActual.toString(),
                    "lo" to lonActual.toString()
                ),
                "q" to null,
                "r" to null,
                "t" to mapOf(
                    "ms" to timeMs.toString(),
                ),
                "t1" to mapOf(
                    "ms" to timeMs.toString()
                ),
                "t2" to mapOf(
                    "ms" to staleTimeMs.toString()
                ),
                "u" to uuid,
                "v" to "2.0",
                "y" to "a-f-G-U-C"
            )

            val trackDocument = mapOf(
                "_id" to uuid,
                "authorCs" to callsign,
                "authorId" to uuid,
                "authorType" to "a-f-G-U-C",
                "cs" to callsign,
                "lat" to latActual,
                "lon" to lonActual,
                "pks" to localPeerKeyString,
                "removed" to false,
                "schver" to 1,
                "staleMs" to staleMsOffset,
                "takUid" to uuid,
                "timeMs" to timeMs.toDouble(),
                "type" to "a-f-G-U-C",
                "cot" to cotMap
                )

//            val trackDocument = mapOf(
//                "_id" to uuid,
//                "removed" to false,
//                "la" to latActual,
//                "lo" to lonActual,
//                "authorId" to uuid,
//                "uid" to uuid,
//                "y" to "a-f-G",
//                "t" to timeMs.toDouble(),
//                "t1" to 0,
//                "t2" to 60000,
//                "authorCs" to "Test Track",
//                "authorType" to "a-f-G-U-G",
//                "cs" to "Tak Test $callsign",
//                "pks" to localPeerKeyString,
//            )
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
        lateinit var localPeerKeyString: String
    }

}
