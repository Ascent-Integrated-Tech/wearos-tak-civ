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

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cot.CotDetailBuilder
import com.aitech.weartak_civ.cot.DittoChatDocument
import com.aitech.weartak_civ.cot.DittoCotReceiver
import com.aitech.weartak_civ.cot.CotMessageParser
import com.aitech.weartak_civ.cot.CotXml
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.DittoDocumentEvent
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.role
import com.aitech.weartak_civ.presentation.MainActivity.Constants.teamValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import live.ditto.Ditto
import live.ditto.DittoError
import live.ditto.DittoIdentity
import live.ditto.DittoSyncSubscription
import live.ditto.DittoStore
import live.ditto.DittoCounter
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
    private val ioScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO) // Single CoroutineScope for background I/O tasks, ensuring lifecycle management, error isolation, and cleaner code.
    private val uuid = MainActivity.device_uid
    private var subscriptionTrack: DittoSyncSubscription? = null
    private var subscriptionAlert: DittoSyncSubscription? = null
    private var subscriptionMapItem: DittoSyncSubscription? = null
    private var subscriptionChat: DittoSyncSubscription? = null
    private var storeObservers: DittoStoreObservers? = null
    private var changeCounterTrackDocument: Int = 1
    private var ownsDitto = false

    private val model = Build.MODEL
    private val manufacturer = Build.MANUFACTURER
    private lateinit var dittoVersion: String

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "DittoService onCreate()")
        isRunning.set(true)
        startForegroundServiceCompat(
            notificationId = DITTO_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )
        val application = applicationContext as WearTakApplication
        dataRepository = application.repo
        activeService.value = this
        if (reconfiguring.value) {
            stopSelf()
            return
        }

        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }

        if (getDittoInstance() == null) {
            val initialized = initializeDitto(
                applicationContext,
                dataRepository
            )

            if (!initialized) {
                startupResult.value = false
                stopSelf()
                return
            }
        }

        ownsDitto = true
        localPeerKeyString = ditto.presence.graph.localPeer.peerKeyString

        try {
            configureDitto()
            subscribeToCollections()
            evictExpiredChatDocuments()
            storeObservers = DittoStoreObservers(
                ditto.store, ioScope, DittoCotReceiver(
                    repository = dataRepository,
                    bloodhounds = application.bhRepo,
                    journal = getSharedPreferences("ditto-cot-replay-v2", Context.MODE_PRIVATE),
                    deviceUid = { MainActivity.device_uid },
                    callsign = { callsign },
                    localPeer = { localPeerKeyString }
                )
            )
            ditto.startSync() // Start syncing
            ioScope.launch {
                while (isActive) {
                    retryPointPublications()
                    evictExpiredChatDocuments()
                    delay(10_000)
                }
            }
            startDispatchScheduler()
            dataRepository.setEndpointConnected(DataRepository.Endpoint.DITTO)
            startupResult.value = true
            Log.i(TAG, "DittoService startSync()")
        } catch (e: Exception) {
            Log.e(TAG, "DittoService failed to start")
            startupResult.value = false
            storeObservers?.close()
            storeObservers = null
            unSubscribeToCollections()
            isRunning.set(false)
            dataRepository.setEndpointDisconnected(DataRepository.Endpoint.DITTO)
            stopSelf()
        }
    }

    private fun subscribeToCollections() {
        Log.d(TAG, "DittoService subscribeToCollections(): [ track, alert, mapitem, chat ]")
        subscriptionTrack = ditto.sync.registerSubscription("SELECT * FROM track")
        subscriptionMapItem = ditto.sync.registerSubscription("SELECT * FROM mapitem")
        subscriptionAlert = ditto.sync.registerSubscription("SELECT * FROM alert")
        subscriptionChat = ditto.sync.registerSubscription("SELECT * FROM chat")

    }

    private fun unSubscribeToCollections() {
        Log.d(TAG, "Subscriptions have been closed.")
        subscriptionTrack?.close()
        subscriptionAlert?.close()
        subscriptionMapItem?.close()
        subscriptionChat?.close()

        subscriptionTrack = null
        subscriptionAlert = null
        subscriptionMapItem = null
        subscriptionChat = null
    }

    private fun configureDitto() {
        val config = DittoTransportConfig()
        config.enableAllPeerToPeer() // Enable all peer to peer transports
        ditto.deviceName = callsign // set deviceName for presence viewer
        dittoVersion = ditto.sdkVersion // used in the cot detail for ditto
        ditto.transportConfig = config
    }

    private val dittoUpdateRunnable = object : Runnable {
        override fun run() {
            ioScope.launch {

                val battery = dataRepository.latestBatteryPercentage.value

                if (battery != null) {
                    updateTrackDocument()
//                    logAllDevices()
                } else {
                    Log.w(TAG, "Missing data for Ditto database. Not sending.")
                }
            }

            // Schedule the next run
            dittoUpdateHandler?.postDelayed(
                this,
                CotDispatchService.actualReportingIntervalSecs * 1000L
            )
        }
    }

    private fun startDispatchScheduler() {
        dittoUpdateThread = HandlerThread("CotDispatchThread")
        dittoUpdateThread?.start()
        dittoUpdateHandler = dittoUpdateThread?.looper?.let { Handler(it) }

        // Schedule the first run
        dittoUpdateHandler?.postDelayed(dittoUpdateRunnable, DISPATCH_INTERVAL)
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onDittoDoc(evt: DittoDocumentEvent) {
        ioScope.launch {
            try {
                val raw = evt.cotXml
                Log.v(TAG, "Publishing Ditto document collection=${evt.collection}")
                if (evt.collection == "chat") {
                    publishDocument("chat", DittoChatDocument.encode(raw, localPeerKeyString))
                    return@launch
                }

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
                if (publishDocument(evt.collection, doc)) {
                    Log.v(TAG, "Wrote to ${evt.collection}: $doc")
                }

            } catch (t: Throwable) {
                Log.e(TAG, "Ditto write failed", t)
            }

        }
    }

    internal fun retryPointPublications() {
        if (dataRepository.isCompanionConnected.value) return
        dataRepository.dittoPointLedger.retry(
            write = ::writeOwnedPointDocument,
            onError = { Log.e(TAG, "Ditto point publication pending; retrying later", it) }
        )
    }

    internal fun evictExpiredChatDocuments(
        store: DittoStore = ditto.store,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        try {
            // Local eviction, not replicated deletion: expiry never removes inbox history.
            store.collection("chat").find("o <= $nowMillis").evict()
            store.collection("chat").find("b <= ${nowMillis - DittoChatDocument.TTL_MILLIS}").evict()
        } catch (error: Exception) {
            Log.e(TAG, "Could not evict expired Ditto chat; retrying later", error)
        }
    }

    internal fun publishDocument(collection: String, document: Map<String, Any?>): Boolean {
        if (dataRepository.isCompanionConnected.value) {
            Log.d(TAG, "Suppressing direct Ditto publication while bonded companion is connected")
            return false
        }
        if (collection == "mapitem" && CotMessageParser.isMarkerType(document["w"] as? String ?: "")) {
            val published = dataRepository.dittoPointLedger.publish(document, ::writeOwnedPointDocument)
            if (!published) {
                Log.w(TAG, "Ditto point not published: no active local-origin ownership")
            }
            return published
        } else {
            writeDocument(collection, document)
            return true
        }
    }

    internal fun writeDocument(collection: String, document: Map<String, Any?>) {
        val properties = if (collection == "chat") document + ("_c" to DittoCounter()) else document
        ditto.store.write { tx -> tx.scoped(collection).upsert(properties) }
    }

    private fun writeOwnedPointDocument(document: Map<String, Any?>) {
        require(document["a"] == localPeerKeyString) { "Pending Ditto point belongs to another local peer store" }
        writeDocument("mapitem", document)
    }

    private fun updateTrackDocument() {
        ioScope.launch {
            val timeMs = System.currentTimeMillis()
            val staleMsOffset = 3000 * CotDispatchService.actualReportingIntervalSecs
            val staleTimeMs = timeMs + staleMsOffset
            val location = dataRepository.snapshotPliLocation()
            if (location == null) {
                Log.i(TAG, "Skipping Ditto track: awaiting first valid location fix")
                return@launch
            }
            val heartRate = dataRepository.latestHeartRate.value ?: -1
            val battery = dataRepository.latestBatteryPercentage.value ?: -1
            val latActual = location.lat
            val lonActual = location.lon
            val haeActual = location.hae ?: 0.0
            var ceActual = location.ce ?: 9999999.0F
            if (ceActual.isNaN()) ceActual = 9999999.0F
            var leActual: Float = location.le ?: 9999999.0F
            if (leActual.isNaN()) leActual = 9999999.0F
            val bearingActual = location.course ?: 0.0F
            val speedActual = location.speed ?: 0.0F
            val typeString = "a-f-G-U-C"
            val access = "Undefined"
            val how = "m-g"


            // 2) Merged version (original + ditto + remarks/biometrics/track)
            val cotDetail = CotDetailBuilder.dittoTrack(
                // your original args:
                heartRate = heartRate,
                battery = battery,
                callsign = callsign,
                role = role,
                team = teamValue,
                uid = uuid,
                course = bearingActual,
                speed = speedActual,
                // if you need a custom endpoint/phone (these also have defaults):
                endpoint = "*:-1:stcp",
                // ditto payload:
                dittoA = localPeerKeyString,                     // "a-f-G-U-C"
                dittoIp = "0.0.0.0",                         // "Undefined"
                dittoVersion = dittoVersion,                            // "m-g"
                dittoDeviceName = callsign
            ).let { detail ->
                if (timeMs - location.timeMillis > 3_000) {
                    detail.replace("</detail>", CotXml.element("weartak_location",
                        listOf("timeMillis" to location.timeMillis)) + "</detail>")
                } else detail
            }

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
                "h" to ceActual.toDouble(),   // CE
                "i" to haeActual,             // HAE
                "j" to latActual,             // LAT
                "k" to leActual.toDouble(),   // LE
                "l" to lonActual,             // LON

                // — timing & lifecycle —
                "n" to timeMs,          // start
                "o" to staleTimeMs,     // stale

                // — how/access/detail —
                "p" to how,
                "q" to access,
                "r" to cotDetail,      // must be the full `<detail>…</detail>` string

                // — optional telemetry aliases —
                "r1" to speedActual.toString(),   // speed
                "r2" to haeActual.toString(),     // sometimes used for additional metric

                // — type string for this document —
                "w" to typeString     // e.g. "a-f-G-U-C"
            )

            try {
                if (dataRepository.isCompanionConnected.value) return@launch
                writeDocument("track", trackDocument)
                changeCounterTrackDocument++
                Log.v(TAG, "Wrote to track: $trackDocument")
            } catch (t: Throwable) {
                Log.e(TAG, "Ditto write failed", t)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "DittoService onDestroy()")
        isRunning.set(false)
        stopForeground(STOP_FOREGROUND_DETACH)
        dittoUpdateHandler?.removeCallbacks(dittoUpdateRunnable)
        dittoUpdateThread?.quitSafely()
        dittoUpdateHandler = null
        dittoUpdateThread = null
        storeObservers?.close()
        storeObservers = null
        ioScope.cancel()

        val initializedDitto = getDittoInstance()

        if (ownsDitto && initializedDitto != null) {
            try {
                initializedDitto.stopSync()
                Log.d(TAG, "Sync stopped")
            } catch (error: DittoError) {
                Log.e(
                    TAG,
                    "Error stopping sync"
                )
            }
        } else {
            Log.d(TAG, "Ditto was not initialized; skipping stopSync")
        }

        unSubscribeToCollections()
        dataRepository.setEndpointDisconnected(DataRepository.Endpoint.DITTO)

        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        if (activeService.value === this) activeService.value = null
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d(TAG, "DittoService onStartCommand()")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Not a bound service
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
        val isRunning: AtomicBoolean = AtomicBoolean(false)
        private const val DISPATCH_INTERVAL = 3_000L // 3 seconds
        lateinit var localPeerKeyString: String
        private var dittoInstance: Ditto? = null
        private val ditto: Ditto get() = requireNotNull(dittoInstance)
        private val activeService = MutableStateFlow<DittoService?>(null)
        private val startupResult = MutableStateFlow<Boolean?>(null)
        private val reconfiguring = MutableStateFlow(false)
        val isReconfiguring = reconfiguring.asStateFlow()
        private val identityMutex = Mutex()

        fun getDittoInstance(): Ditto? = dittoInstance

        suspend fun applySelectedIdentity(context: Context, repo: DataRepository): Boolean =
            identityMutex.withLock {
                reconfiguring.value = true
                try {
                    val previous = activeService.value
                    val thread = previous?.dittoUpdateThread
                    withContext(Dispatchers.Main) {
                        context.stopService(Intent(context, DittoService::class.java))
                    }
                    withTimeout(15_000) {
                        activeService.first { it == null }
                        // Cancellation is not completion: wait for store writes/observers before closing JNI.
                        previous?.ioScope?.coroutineContext?.get(Job)?.join()
                        if (thread != null) withContext(Dispatchers.IO) { thread.join(10_000) }
                        check(thread?.isAlive != true) { "Ditto worker did not stop" }
                    }
                    withContext(Dispatchers.Main) {
                        dittoInstance?.close()
                        dittoInstance = null
                        check(initializeDitto(context, repo)) { "Ditto identity could not be applied" }
                    }
                    startupResult.value = null
                } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                    Log.e(TAG, "Ditto identity apply timed out")
                    return@withLock false
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.e(TAG, "Ditto identity apply failed: ${error.javaClass.simpleName}")
                    return@withLock false
                } finally {
                    reconfiguring.value = false
                }
                val app = context.applicationContext as WearTakApplication
                val config = ServiceConfiguration(context, repo, app.audioRepo)
                if (!config.getServiceEnabledFlow(ReactiveServiceManager.ServiceType.DITTO).first() ||
                    !config.isServiceAvailable(ReactiveServiceManager.ServiceType.DITTO)) {
                    // The identity is applied, but the normal companion/license/permission gates still win.
                    return@withLock true
                }
                if (!ReactiveServiceManager.isRunning.get()) {
                    Log.e(TAG, "Ditto identity applied but service manager is unavailable")
                    return@withLock false
                }
                try {
                    withTimeout(20_000) { startupResult.filterNotNull().first() }
                } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                    Log.e(TAG, "Ditto identity applied but service startup timed out")
                    false
                }
            }

        private fun initializeDitto(
            applicationContext: Context,
            dataRepository: DataRepository
        ): Boolean {
            Log.d(TAG, "Initializing Ditto")

            // Ditto has already been initialized
            if (dittoInstance != null) {
                return true
            }

            // Load the selected Community or Custom PDL
            val encodedPdl = runBlocking {
                dataRepository.getEffectiveDittoPdl.first()
            }.trim()

            // Do not log the actual PDL because it contains credentials
            Log.d(
                TAG,
                "Ditto identity present=${encodedPdl.isNotBlank()}, " +
                        "length=${encodedPdl.length}"
            )

            // Decode and validate the PDL
            val license = try {
                DittoCommunityLicenseHelper.parse(encodedPdl)
            } catch (error: Exception) {
                Log.e(TAG, "Invalid PDL")
                Toast.makeText(applicationContext, "Invalid PDL", Toast.LENGTH_LONG).show()
                return false
            }

            // Stop if the PDL has expired
            if (license.isExpired) {
                Log.e(TAG, "Expired PDL")
                Toast.makeText(applicationContext, "Expired PDL", Toast.LENGTH_LONG).show()
                return false
            }

            // Initialize Ditto using the identity contained in the PDL
            var candidate: Ditto? = null
            return try {
                val androidDependencies =
                    DefaultAndroidDittoDependencies(applicationContext)

                val identity = DittoIdentity.SharedKey(
                    androidDependencies,
                    appId = license.appId,
                    sharedKey = license.sharedKey
                )

                candidate = Ditto(
                    androidDependencies,
                    identity
                )

                // Apply the PDL's offline license token
                candidate.setOfflineOnlyLicenseToken(
                    license.licenseToken
                )

                dittoInstance = candidate
                Log.i(TAG, "Ditto initialized successfully")
                true
            } catch (error: Exception) {
                candidate?.close()
                Log.e(TAG, "Failed to initialize Ditto")
                Toast.makeText(applicationContext, "Ditto failed to start", Toast.LENGTH_LONG).show()
                false
            }
        }
    }
}
