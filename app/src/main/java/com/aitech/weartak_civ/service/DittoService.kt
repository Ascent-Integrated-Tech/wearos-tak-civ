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
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.DittoDocumentEvent
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.roleValue
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import live.ditto.Ditto
import live.ditto.DittoError
import live.ditto.DittoIdentity
import live.ditto.DittoSyncSubscription
import live.ditto.android.DefaultAndroidDittoDependencies
import live.ditto.transports.DittoTransportConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class DittoService : Service() {

    private lateinit var dataRepository: DataRepository
    private var dittoUpdateHandler: Handler? = null
    private var dittoUpdateThread: HandlerThread? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO) // Single CoroutineScope for background I/O tasks, ensuring lifecycle management, error isolation, and cleaner code.
    private val uuid = MainActivity.device_uid
    private var subscriptionTrack: DittoSyncSubscription? = null
    private var subscriptionAlert: DittoSyncSubscription? = null
    private var subscriptionMapItem: DittoSyncSubscription? = null

    private val model = android.os.Build.MODEL
    private val manufacturer = android.os.Build.MANUFACTURER

    override fun onCreate() {
        super.onCreate()
        dataRepository = MainActivity.dataRepository // or another injection approach

        if (null == getDittoInstance()) {
            initializeDitto(applicationContext, dataRepository)
        }
        // listen for ditto specific events for markers, alerts.
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }

        localPeerKeyString = ditto.presence.graph.localPeer.peerKeyString
        Log.d(TAG, "Oncreate Called")
    }

    private fun configureDitto() {
        val config = DittoTransportConfig()
        config.enableAllPeerToPeer() //Enable all peer to peer transports
        ditto.deviceName = callsign // set deviceName for presence viewer
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
                    updateTrackDocument()
//                    logAllDevices()
                } else {
                    Log.w(TAG, "Missing data for Ditto database. Not sending.")
                }
            }

            // Schedule the next run
            dittoUpdateHandler?.postDelayed(this, CotDispatchService.currReportingIntervalInSecs * 1000L)
        }
    }

    private fun startDispatchScheduler() {
        dittoUpdateThread = HandlerThread("CotDispatchThread")
        dittoUpdateThread?.start()
        dittoUpdateHandler = dittoUpdateThread?.looper?.let { Handler(it) }

        // Schedule the first run
        dittoUpdateHandler?.postDelayed(dittoUpdateRunnable, DISPATCH_INTERVAL)
    }

    /** Fired whenever any dispatch code posts a DittoDocumentEvent */
    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onDittoDoc(evt: DittoDocumentEvent) {
        ioScope.launch {
            try {
                val raw    = evt.cotXml
                val id     = extract(raw, "uid")
                val lat    = extract(raw, "lat").toDouble()
                val lon    = extract(raw, "lon").toDouble()
                val timeMs = Instant.parse(extract(raw, "time")).toEpochMilli()
                val staleS = extract(raw, "stale")
                val staleMs = Instant.parse(staleS).toEpochMilli() - timeMs
                val type   = extract(raw, "type")
                val authorType = extractLinkAttrOrNull(raw, "type") ?: "a-f-G-U-C"
                val how    = extractOrNull(raw, "how")
                val cs     = extractOrNull(raw, "callsign") ?: callsign
                val pks    = extractOrNull(raw, "pks") ?: localPeerKeyString

                val doc = when(evt.collection) {
                    "mapitem" -> mapOf<String, Any?>(
                        "_id"        to id,
                        "authorCs"   to callsign,
                        "authorId"   to uuid,
                        "authorType" to authorType,
                        "cs"         to cs,
                        "lat"        to lat,
                        "lon"        to lon,
                        "pks"        to pks,
                        "removed"    to false,
                        "schver"     to 1,
                        "staleMs"    to staleMs,
                        "takUid"     to id,
                        "timeMs"     to timeMs,
                        "type"       to type,
                        "visible"    to "true"
                    )
                    "alert" -> mapOf<String, Any?>(
                        "_id"        to id,             // or "$uuid-$timeMs" if you need uniqueness
                        "authorId"   to uuid,
                        "authorType" to authorType,
                        "cs"         to "$callsign-Alert",
                        "how"        to (how ?: "m-g"),
                        "lat"        to lat,
                        "lon"        to lon,
                        "pks"        to pks,
                        "removed"    to false,
                        "schver"     to 1,
                        "staleMs"    to staleMs,
                        "timeMs"     to timeMs,
                        "type"       to type
                    )
                    else -> return@launch
                }

                val query = "INSERT INTO ${evt.collection} DOCUMENTS (:doc) ON ID CONFLICT DO UPDATE"
                ditto.store.execute(query, mapOf("doc" to doc))
                Log.v(TAG, "Wrote to ${evt.collection}: $doc")

            } catch (t: Throwable) {
                Log.e(TAG, "Ditto write failed", t)
            }
        }
    }

    private fun updateTrackDocument() {
//        Log.d(TAG, "Updating track Document for $uuid")
        ioScope.launch{
//            val timeMs = Calendar.getInstance().timeInMillis
            val timeMs = System.currentTimeMillis()
            val staleMsOffset = 3000 * CotDispatchService.currReportingIntervalInSecs
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

            if (heartRate == null) { heartRate = -1 }

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
                            "8" to mapOf(
                                "a" to emptyMap<String, Any?>(),
                                "d" to mapOf(
                                    "0" to mapOf(
                                        "a" to emptyMap<String, Any?>(),
                                        "d" to mapOf(
                                            "0" to mapOf(
                                                "a" to emptyMap<String, Any?>(),
                                                "d" to emptyMap<String, Any?>(),
                                                "i" to model,
                                                "n" to "model"
                                            ),
                                            "1" to mapOf(
                                                "a" to emptyMap<String, Any?>(),
                                                "d" to emptyMap<String, Any?>(),
                                                "i" to uuid,
                                                "n" to "uid"
                                            ),
                                            "2" to mapOf(
                                                "a" to emptyMap<String, Any?>(),
                                                "d" to emptyMap<String, Any?>(),
                                                "i" to heartRate.toString(),
                                                "n" to "hr"
                                            ),
                                            "3" to mapOf(
                                                "a" to emptyMap<String, Any?>(),
                                                "d" to emptyMap<String, Any?>(),
                                                "i" to battery.toString(),
                                                "n" to "bat"
                                            ),
                                        ),
                                        "i" to null,
                                        "n" to "device"
                                    ),
                                ),
                                "i" to null,
                                "n" to "biometrics"
                            )
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
                "cot" to cotMap,
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
            )

            val insertQuery = "INSERT INTO track DOCUMENTS (:trackDocument) ON ID CONFLICT DO UPDATE"
            Log.d(TAG, "Updating track document: $trackDocument")
            ditto.store.execute(insertQuery, mapOf("trackDocument" to trackDocument))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRunning.getAndSet(false)) {
            stopForeground(true)
            // Stop syncing when the service is destroyed
            try {
                ditto.stopSync()
                Log.d("DittoService", "Sync stopped")
            } catch (e: DittoError) {
                Log.e("DittoService", "Error stopping sync: ${e.message}")
            }
            subscriptionTrack?.close() // stop subscribing to track collection
            subscriptionAlert?.close()
            subscriptionAlert?.close()
            Log.d(TAG, "Subscriptions have been closed.")

        }

        subscriptionTrack = null
        subscriptionAlert = null
        subscriptionMapItem = null
        ioScope.cancel() // cancel all coroutines when service is destroyed

        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if (!isRunning.getAndSet(true)) {
            startForegroundService() // service needs to work in the background when screen is off
            startDispatchScheduler() // we want to update our ditto data at fixed interval
            Log.d(TAG, "Subscribing to [ track, alert, mapitem ] collection")

            subscriptionTrack = ditto.sync.registerSubscription("SELECT * FROM track")
            subscriptionMapItem = ditto.sync.registerSubscription("SELECT * FROM mapitem")
            subscriptionAlert = ditto.sync.registerSubscription("SELECT * FROM alert")

            configureDitto() // configure all peer transports
            try {
                ditto.startSync() // Start syncing
                Log.d("DittoService", "Sync started")
            } catch (e: DittoError) {
                Log.e("DittoService", "Error starting sync: ${e.message}")
            }
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

    //––––––––– | Helper tools for Cot Parsing from DittoDocumentEvent
    /** Pulls a required attribute or throws if missing */
    private fun extract(raw: String, name: String): String =
        """$name=['"]([^'"]+)['"]""".toRegex()
            .find(raw)!!.groupValues[1]

    /** Pulls an optional attribute or null if absent */
    private fun extractOrNull(raw: String, name: String): String? =
        """$name=['"]([^'"]+)['"]""".toRegex()
            .find(raw)?.groupValues?.get(1)

    /** Pulls the type of the cot messages author **/
    private fun extractLinkAttrOrNull(raw: String, attrName: String): String? =
        """<link[^>]*\b$attrName=['"]([^'"]+)['"]""".toRegex()
            .find(raw)
            ?.groupValues
            ?.get(1)

    companion object {
        private val TAG = "WearTAK-" + DittoService::class.java.simpleName
        private val DITTO_SERVICE_NOTIFICATION_ID = 3333
        val isRunning : AtomicBoolean = AtomicBoolean(false)
        private const val DISPATCH_INTERVAL = 3_000L // 3 seconds
        lateinit var localPeerKeyString: String
        private lateinit var ditto: Ditto


        fun getDittoInstance(): Ditto? =
            if (::ditto.isInitialized) ditto else null

        private fun initializeDitto(
            applicationContext: Context,
            dataRepository: DataRepository
        ) {
            Log.d(TAG, "Initializing Ditto")

            if (::ditto.isInitialized) return                 // already done

            try {
                // ⬇️  ONE-LINER FIX: block just long enough to get the first value
                val appId  = runBlocking { dataRepository.getDittoAppId.first() }
                val token  = runBlocking { dataRepository.getDittoAuthToken.first() }

                val androidDeps = DefaultAndroidDittoDependencies(applicationContext)
                val identity    = DittoIdentity.OnlinePlayground(
                    androidDeps,
                    appId = appId,
                    token = token
                )

                ditto = Ditto(androidDeps, identity)
                Log.d(TAG, "Ditto initialized with $appId")

            } catch (e: DittoError) {
                Log.e(TAG, "Failed to initialize Ditto: ${e.message}", e)
            }
        }
    }

}
