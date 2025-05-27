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
import com.aitech.weartak_civ.Utils
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
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

class DittoService : Service() {

    private lateinit var dataRepository: DataRepository
    private var dittoUpdateHandler: Handler? = null
    private var dittoUpdateThread: HandlerThread? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO) // Single CoroutineScope for background I/O tasks, ensuring lifecycle management, error isolation, and cleaner code.
    private val uuid = MainActivity.device_uid
    private var subscriptionTrack: DittoSyncSubscription? = null
    private var subscriptionAlert: DittoSyncSubscription? = null
    private var subscriptionMapItem: DittoSyncSubscription? = null
    private var changeCounterTrackDocument: Int = 1

    private val model = android.os.Build.MODEL
    private val manufacturer = android.os.Build.MANUFACTURER
    private lateinit var dittoVersion: String

    override fun onCreate() {
        super.onCreate()
        dataRepository = MainActivity.dataRepository // or another injection approach

        // listen for ditto specific events for markers, alerts.
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }

        if (null == getDittoInstance()) {
            initializeDitto(applicationContext, dataRepository)
        }

        localPeerKeyString = ditto.presence.graph.localPeer.peerKeyString
        Log.d(TAG, "Oncreate Called")
    }

    private fun configureDitto() {
        val config = DittoTransportConfig()
        config.enableAllPeerToPeer() //Enable all peer to peer transports
        ditto.deviceName = callsign // set deviceName for presence viewer
        dittoVersion = ditto.sdkVersion // used in the cot detail for ditto
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

/*
    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onDittoDoc(evt: DittoDocumentEvent) {
        ioScope.launch {
            try {
                val raw       = evt.cotXml
                // 1) Core CoT parsing
                val id        = extract(raw, "uid")
                val timeStart = Instant.parse(extract(raw, "time")).toEpochMilli()
                val timeStale = Instant.parse(extract(raw, "stale")).toEpochMilli()

                // 2) Point attributes
                val lat  = extract(raw, "lat").toDouble()
                val lon  = extract(raw, "lon").toDouble()
                val hae  = extract(raw, "hae").toDouble()
                val ce   = extract(raw, "ce").toDouble()
                val le   = extract(raw, "le").toDouble()

                // 3) Detail + author info
                val how       = extractOrNull(raw, "how") ?: ""
                val access    = extractOrNull(raw, "access") ?: "Undefined"
                val detailXml = raw
                    .substringAfter("<detail>", "")
                    .takeIf     { it.contains("</detail>") }
                    ?.let       { "<detail>${it.substringBefore("</detail>")}</detail>" }
                    ?: ""
                val typeAlias = extract(raw, "type")
                val cs        = extractOrNull(raw, "callsign") ?: callsign
                val pks       = extractOrNull(raw, "pks")      ?: localPeerKeyString

                // 4) Build v2‐style doc for mapitem vs alert
                val doc = when (evt.collection) {
                    "mapitem" -> mapOf<String, Any?>(
                        // — Ditto‐managed metadata (_c, _r, _v) are omitted here —
                        "_id" to id,
                        "_r" to false,
                        "_v" to 2,

                        "authorCs" to callsign,
                        "AuthorId" to uuid,
                        "authorType" to typeAlias,
                        "visible" to true,

                        // — common props + visibility —
                        "a" to pks,
                        "b" to timeStart,
                        "c" to cs,           // map‐item title
                        "d" to id,
                        "e" to cs,
                        "f" to true,         // visible

                        // — CoT point & timing —
                        "g" to "2.0",
                        "h" to ce,
                        "i" to hae,
                        "j" to lat,
                        "k" to le,
                        "l" to lon,
                        "n" to timeStart,
                        "o" to timeStale,

                        // — how/access/detail —
                        "p" to how,
                        "q" to access,
                        "r" to detailXml,

                        // — type alias —
                        "w" to typeAlias
                    )

                    "alert" -> mapOf<String, Any?>(
                        "_id" to id,
                        "_r" to false,
                        "_v" to 2,
                        // only common & CoT props—no c/f for alerts
                        "a" to pks,
                        "b" to timeStart,
                        "d" to id,
                        "e" to "$cs-Alert",

                        "authorCs" to callsign,
                        "AuthorId" to uuid,
                        "authorType" to typeAlias,
                        "visible" to true,

                        "g" to "2.0",
                        "h" to ce,
                        "i" to hae,
                        "j" to lat,
                        "k" to le,
                        "l" to lon,
                        "n" to timeStart,
                        "o" to timeStale,

                        "p" to how.ifEmpty { "m-g" },
                        "q" to access,
                        "r" to detailXml,

                        "w" to typeAlias
                    )

                    else -> return@launch
                }

                // 5) Upsert into Ditto
                ditto.store.write { tx ->
                    tx.scoped(evt.collection).upsert(doc)
                }
                Log.v(TAG, "Wrote to ${evt.collection}: $doc")

            } catch (t: Throwable) {
                Log.e(TAG, "Ditto write failed", t)
            }
        }
    }
*/

    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onDittoDoc(evt: DittoDocumentEvent) {
        ioScope.launch {
            try {
                val raw = evt.cotXml
                Log.v(TAG, "raw value is: $raw")

                // 1) Core CoT parsing using XmlPullParser
                val parser = XmlPullParserFactory.newInstance()
                    .newPullParser().apply {
                        setInput(raw.reader())
                    }
                var id = ""
                var timeStart = 0L
                var timeStale = 0L
                var lat = 0.0
                var lon = 0.0
                var hae = 0.0
                var ce = 0.0
                var le = 0.0

                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "event" -> {
                                parser.getAttributeValue(null, "uid")?.let { id = it }
                                    ?: throw IllegalArgumentException("Missing uid in CoT XML")
                                parser.getAttributeValue(null, "time")?.let {
                                    timeStart = Instant.parse(it).toEpochMilli()
                                } ?: throw IllegalArgumentException("Missing time in CoT XML")
                                parser.getAttributeValue(null, "stale")?.let {
                                    timeStale = Instant.parse(it).toEpochMilli()
                                } ?: throw IllegalArgumentException("Missing stale in CoT XML")
                            }
                            "point" -> {
                                parser.getAttributeValue(null, "lat")?.let { lat = it.toDouble() }
                                    ?: throw IllegalArgumentException("Missing lat in CoT XML")
                                parser.getAttributeValue(null, "lon")?.let { lon = it.toDouble() }
                                    ?: throw IllegalArgumentException("Missing lon in CoT XML")
                                parser.getAttributeValue(null, "hae")?.let { hae = it.toDouble() }
                                    ?: throw IllegalArgumentException("Missing hae in CoT XML")
                                parser.getAttributeValue(null, "ce")?.let { ce = it.toDouble() }
                                    ?: throw IllegalArgumentException("Missing ce in CoT XML")
                                parser.getAttributeValue(null, "le")?.let { le = it.toDouble() }
                                    ?: throw IllegalArgumentException("Missing le in CoT XML")
                                break
                            }
                        }
                    }
                    parser.next()
                }

                // 2) Point attributes parsed via parser, skip original extract code

                // 3) Detail + author info
                val how = extractOrNull(raw, "how") ?: ""
                val access = extractOrNull(raw, "access") ?: "Undefined"
                val detailXml = raw
                    .substringAfter("<detail>", "")
                    .takeIf { it.contains("</detail>") }
                    ?.let { "<detail>${it.substringBefore("</detail>")}</detail>" }
                    ?: ""
                val typeAlias = extract(raw, "type")
                val cs = extractOrNull(raw, "callsign") ?: callsign
                val pks = extractOrNull(raw, "pks") ?: localPeerKeyString

                // 4) Build v2‐style doc for mapitem vs alert
                val doc = when (evt.collection) {
                    "mapitem" -> mapOf<String, Any?>(
                        // — Ditto‐managed metadata (_c, _r, _v) are omitted here —
                        "_id" to id,
                        "_r" to false,
                        "_v" to 2,

                        "authorCs" to callsign,
                        "AuthorId" to uuid,
                        "authorType" to typeAlias,
                        "visible" to true,

                        // — common props + visibility —
                        "a" to pks,
                        "b" to timeStart,
                        "c" to cs,
                        "d" to id,
                        "e" to cs,
                        "f" to true,

                        // — CoT point & timing —
                        "g" to "2.0",
                        "h" to ce,
                        "i" to hae,
                        "j" to lat,
                        "k" to le,
                        "l" to lon,
                        "n" to timeStart,
                        "o" to timeStale,

                        // — how/access/detail —
                        "p" to how,
                        "q" to access,
                        "r" to detailXml,

                        // — type alias —
                        "w" to typeAlias
                    )

                    "alert" -> mapOf<String, Any?>(
                        "_id" to id,
                        "_r" to false,
                        "_v" to 2,

                        // only common & CoT props—no c/f for alerts
                        "a" to pks,
                        "b" to timeStart,
                        "d" to id,
                        "e" to "${cs}-Alert",

                        "authorCs" to callsign,
                        "AuthorId" to uuid,
                        "authorType" to typeAlias,
                        "visible" to true,

                        "g" to "2.0",
                        "h" to ce,
                        "i" to hae,
                        "j" to lat,
                        "k" to le,
                        "l" to lon,
                        "n" to timeStart,
                        "o" to timeStale,

                        "p" to how.ifEmpty { "m-g" },
                        "q" to access,
                        "r" to detailXml,

                        "w" to typeAlias
                    )

                    else -> return@launch
                }

                // 5) Upsert into Ditto
                ditto.store.write { tx ->
                    tx.scoped(evt.collection).upsert(doc)
                }
                Log.v(TAG, "Wrote to ${evt.collection}: $doc")

            } catch (t: Throwable) {
                Log.e(TAG, "Ditto write failed", t)
            }
        }
    }

    private fun updateTrackDocument() {
        ioScope.launch{
            val timeMs = System.currentTimeMillis()
            val staleMsOffset = 3000 * CotDispatchService.currReportingIntervalInSecs
            val staleTimeMs = timeMs+staleMsOffset
            val location = dataRepository.latestLocation.value
            var heartRate = dataRepository.latestHeartRate.value ?: -1
            val battery = dataRepository.latestBatteryPercentage.value ?: -1
            val latActual = location?.latitude ?: 0.0
            val lonActual = location?.longitude ?: 0.0
            val haeActual = location?.altitude ?: 0.0
            var ceActual = location?.accuracy ?: 9999999.0F
            if (ceActual.isNaN()) ceActual = 9999999.0F
            var leActual : Float = location?.verticalAccuracyMeters ?: 9999999.0F
            if (leActual.isNaN()) leActual = 9999999.0F
            val bearingActual = location?.bearing ?: 0.0F
            val speedActual = location?.speed ?: 0.0F
            val deviceModel = "$manufacturer $model"
            val typeString = "a-f-G-U-C"
            val access = "Undefined"
            val how = "m-g"


            // 2) Merged version (original + ditto + remarks/biometrics/track)
            val cotDetail = Utils.buildCotDetailMerged(
                // your original args:
                heartRate     = heartRate,
                battery       = battery,
                callsign      = callsign,
                roleValue     = roleValue,
                teamValue     = teamValue,
                uid           = uuid,
                bearingActual = bearingActual,
                speedActual   = speedActual,
                // if you need a custom endpoint/phone (these also have defaults):
                endpoint      = "*:-1:stcp",
                phone         = "11234567890",
                // takv info:
                takvOs        = "WEAROS",           // e.g. "14"
                takvVersion   = "WearTAK-${Build.VERSION.RELEASE}",        // your APK version
                takvDevice    = deviceModel,                     // "$manufacturer $model"
                takvPlatform  = "ATAK-CIV",
                // ditto payload:
                dittoA        = localPeerKeyString,                     // "a-f-G-U-C"
                dittoIp       = "0.0.0.0",                         // "Undefined"
                dittoVersion  = dittoVersion,                            // "m-g"
                dittoDeviceName = ditto.deviceName                    // same as above, or override
            )

            val trackDocument = mapOf<String, Any?>(
                // — common properties —
                "_id" to uuid,
//                "_c" to changeCounterTrackDocument.toDouble(),
                "_r" to false,
                "_v" to 2,

                "authorCs" to callsign,
                "AuthorId" to uuid,
                "authorType" to typeString,
                "visible" to true,

                "a" to localPeerKeyString,
                "b" to timeMs,
                "c" to callsign,
                "d" to uuid,
                "e" to callsign,
                "f" to true,            // visible (tracks are always “on”)
                "g" to "2.0",           // your app version or Cot version

                // — CoT point fields —
                "h"  to ceActual.toDouble(),   // CE
                "i"  to haeActual,             // HAE
                "j"  to latActual,             // LAT
                "k"  to leActual.toDouble(),   // LE
                "l"  to lonActual,             // LON

                // — timing & lifecycle —
                "n" to timeMs,          // start
                "o" to staleTimeMs,     // stale

                // — how/access/detail —
                "p"  to how,
                "q"  to access,
                "r"  to cotDetail,      // must be the full `<detail>…</detail>` string

                // — optional telemetry aliases —
                "r1" to speedActual.toString(),   // speed
                "r2" to haeActual.toString(),     // sometimes used for additional metric

                // — type string for this document —
                "w"  to typeString     // e.g. "a-f-G-U-C"
            )

            try {
                ditto.store.write { tx ->
                    tx.scoped("track").upsert(trackDocument)
                    changeCounterTrackDocument++
                }
                Log.v(TAG, "Wrote to track: $trackDocument")
            } catch (t: Throwable) {
                Log.e(TAG, "Ditto write failed", t)
            }
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
