package com.aitech.weartak_civ.service

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.Utils.writeToLogFileLocation
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.IsDeviceWornEvent
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.service.ActivityRecognitionService.Companion.ActivityRecognized
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerCapability
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.ValueKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class SkinTempService : Service() {
    companion object {
        private const val TAG = "WearTAK-SkinTService"
        private const val NOTIF_ID = 177

        var tempF: Float = -1f
        val skinTempLiveData = MutableLiveData<String>("N/A")
        val isRunning = AtomicBoolean(false)
        var DISPATCH_INTERVAL_MS = 60 * 1_000L
        private const val MIN_SKIN_TEMP_INTERVAL_MS = 10_000L
        const val LOG_FILENAME = "skin_temp.log"

        // ===== SCHEDULER + POLICY =====
        private const val SPO2_INTERVAL_MINUTES: Long = 1          // your target interval
        private const val STILL_RECENT_WINDOW_MS: Long = 10_000 // how "recent" a STILL must be for immediate start
    }

    private val repo: DataRepository
        get() = MainActivity.dataRepository

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var healthService: HealthTrackingService
    private lateinit var skinTracker: HealthTracker
    private lateinit var spo2Tracker: HealthTracker

    private lateinit var healthTrackerCapability: HealthTrackerCapability
    private lateinit var availableTrackersList: List<HealthTrackerType>
    private lateinit var handlerThread: HandlerThread
    private lateinit var bckgrndSkinTmpHandler: Handler
    private lateinit var bckgrndSpo2Handler: Handler

    private var isSkTHandlerRunning = false
    private var isSpo2HandlerRunning = false

    private var deviceWorn: Boolean = false

    private var nextFireSkinTempMs = 0L          // wall-clock target for the next run (skin temp)
    private var nextFireSpo2Ms = 0L      // wall-clock target for the next run (spo2)
    // One monotonic clock – the same one Handler uses
    private val nowUptime get() = SystemClock.uptimeMillis()
    @Volatile private var spo2SessionActive = false
    private val SPO2_MAX_DURATION_MS = 40_000L

    // ===== RUNTIME STATE =====
    private var lastSpo2AtMs: Long = 0L                     // when we last finished (uptime)
    @Volatile private var spo2MeasurementPending: Boolean = false
    private var latestStillAtMs: Long = 0L                  // updated on each STILL

    // Handy alias (you already have nowUptime)
    private val spO2IntervalMs get() = SPO2_INTERVAL_MINUTES * 60 * 1000L



    // One function that always schedules the NEXT reading
    private fun scheduleNextSkinTempReading() {
        if (!deviceWorn) return                     // skip if watch off-wrist
        val target = nowUptime + DISPATCH_INTERVAL_MS        // absolute uptime timestamp
        nextFireSkinTempMs = target                         // purely for diagnostics
        bckgrndSkinTmpHandler.postAtTime(::startSkinTracker, target)
//        writeToLogFileLocation(
//            logFile,
//            "Next reading scheduled for ${DISPATCH_INTERVAL_MS/1_000}s (at $target)"
//        )
    }

    // Runnable the scheduler posts: ARM the pending flag; start now if STILL is recent
    private val spo2ArmRunnable = Runnable {
        spo2MeasurementPending = true
        Log.i(TAG, "SPO2 scheduler armed (pending=true)")
        val now = nowUptime
        val recentStill = (now - latestStillAtMs) <= STILL_RECENT_WINDOW_MS
        if (deviceWorn && recentStill && !isSpo2HandlerRunning) {
            Log.i(TAG, "SPO2 scheduler: recent STILL found; starting measurement now.")
            startSpo2Tracker()
        }
    }

    // Schedule the next ARM time (not a direct start)
    private fun scheduleNextSpo2Arm(minutes: Long = SPO2_INTERVAL_MINUTES) {
        if (!deviceWorn) return
        val target = nowUptime + minutes * 60 * 1000L
        nextFireSpo2Ms = target
        bckgrndSpo2Handler.removeCallbacks(spo2ArmRunnable)
        bckgrndSpo2Handler.postAtTime(spo2ArmRunnable, target)
        Log.d(TAG, "SPO2 arm scheduled for t=$target (in ${minutes}m)")
    }

    private val logFile: File by lazy {
        File(filesDir, LOG_FILENAME).apply {
            if (!exists()) parentFile?.mkdirs()
        }
    }

    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            healthTrackerCapability = healthService.trackingCapability
            availableTrackersList = healthTrackerCapability.supportHealthTrackerTypes
            skinTracker = healthService.getHealthTracker(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND)
            spo2Tracker = healthService.getHealthTracker(HealthTrackerType.SPO2_ON_DEMAND)
            Log.i(TAG, "Health SDK Connected")
            if (deviceWorn) {
                startSkinTracker()     // only kick off if worn
                scheduleNextSkinTempReading()
                scheduleNextSpo2Arm()
            }
        }

        override fun onConnectionFailed(e: HealthTrackerException) {
            Log.e(TAG, "SkinTempService onConnectionFailed(): HealthPlatform connection failed", e)
            if (e.hasResolution()) {
                try { e.resolve(null) } catch (ex: Exception) { Log.e(TAG, "SkinTempService Resolution failed", ex) }
            }
        }

        override fun onConnectionEnded() {
            Log.w(TAG, "SkinTempService onConnectionEnded(): HealthPlatform DISCONNECTED")
            // Clean up when disconnected
            stopSkinTracker(true)
        }
    }

    /*
     *  SPO2 --> ValueKey.SPO2Set.STATUS
     * Status | Description
     * ------------------------------------
     *   -6	    Time out
     *   -5	    Signal quality is low
     *   -4	    Device moved during measurement
     *    0	    Calculating SpO2
     *    2	    SpO2 measurement was completed
     *
     */

    // on your class:
    private val spo2Watchdog = Runnable {
        Log.w(TAG, "SpO₂ watchdog fired — stopping after ${SPO2_MAX_DURATION_MS}ms")
        stopSpo2Tracker()
        // Re-arm next cycle even on timeout
        lastSpo2AtMs = nowUptime
        scheduleNextSpo2Arm()
    }

    private fun cancelSpo2Watchdog() {
        bckgrndSpo2Handler.removeCallbacks(spo2Watchdog)
    }

    private fun startSpo2Tracker() {
        if (!::spo2Tracker.isInitialized || isSpo2HandlerRunning) return
        isSpo2HandlerRunning = true
        spo2SessionActive = true

        val supports = availableTrackersList.contains(HealthTrackerType.SPO2_ON_DEMAND)
        if (!supports) {
            Log.w(TAG, "SPO2_ON_DEMAND not supported")
            isSpo2HandlerRunning = false
            spo2SessionActive = false
            return
        }
        Log.i(TAG, "Starting SpO₂...")

        bckgrndSpo2Handler.post {
            try {
                spo2Tracker.setEventListener(spO2TrackerEventListener)
                bckgrndSpo2Handler.postDelayed(spo2Watchdog, SPO2_MAX_DURATION_MS)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to set SpO₂ listener", t)
                isSpo2HandlerRunning = false
                spo2SessionActive = false
            }
        }
    }


    private fun stopSpo2Tracker() {
        Log.i(TAG, "Attempting to StopSpo2Tracker ...")
        if (!isSpo2HandlerRunning) return
        isSpo2HandlerRunning = false
        spo2SessionActive = false
        bckgrndSpo2Handler.post {
            try { spo2Tracker.unsetEventListener() } catch (t: Throwable) {
                Log.e(TAG, "Failed to unset SpO₂ listener", t)
            } finally {
                Log.i(TAG, "Stopped Spo2Tracker ...")
                bckgrndSpo2Handler.removeCallbacks(spo2Watchdog)
            }
        }
    }

    private val spO2TrackerEventListener = object : HealthTracker.TrackerEventListener {
        private var startedAtUptime: Long = 0L
        override fun onDataReceived(points: List<DataPoint?>) {
            if (!spo2SessionActive) return
            if (startedAtUptime == 0L) startedAtUptime = nowUptime

            for (p in points) {
                val point = p ?: continue
                val spo2 = point.getValue(ValueKey.SpO2Set.SPO2)
                val hr = point.getValue(ValueKey.SpO2Set.HEART_RATE)
                val status = point.getValue(ValueKey.SpO2Set.STATUS)
                val accuracy = point.getValue(ValueKey.SpO2Set.ACCURACY_FLAG)

                val terminal = when (status) { 2, -6, -5, -4 -> true; else -> false }
                Log.i(TAG, "SpO₂ upd: status=$status acc=$accuracy spo2=$spo2 hr=$hr elapsed=${nowUptime - startedAtUptime}ms")

                if (terminal) {
                    when (status) {
                        2   -> Log.i(TAG, "✅ SpO₂ complete: $spo2% (HR=$hr, acc=$accuracy)")
                        -6  -> Log.w(TAG, "⚠️ SpO₂ TIMEOUT")
                        -5  -> Log.w(TAG, "⚠️ SpO₂ LOW SIGNAL")
                        -4  -> Log.w(TAG, "⚠️ SpO₂ MOVED")
                    }

                    // mark completion time and plan next cycle
                    lastSpo2AtMs = nowUptime
                    stopSpo2Tracker()
                    scheduleNextSpo2Arm()
                    break
                }
            }
        }

        override fun onFlushCompleted() { /* no-op */ }

        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(TAG, "SpO₂ tracker error: $error")
            lastSpo2AtMs = nowUptime
            stopSpo2Tracker()
            scheduleNextSpo2Arm()
        }
    }

    private val skinTempTrackerEventListener = object : HealthTracker.TrackerEventListener {

        override fun onDataReceived(dataPoints: List<DataPoint>) {
            // 1️⃣  Stop sensor immediately (but keep queue)
            stopSkinTracker(false)

            // 2️⃣  Collect temp data point  ──────────────────────────────
            dataPoints.firstOrNull()?.let { dp ->
                val status = dp.getValue(ValueKey.SkinTemperatureSet.STATUS)

                if (status >= 0) {
                    val objectTemp = dp.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE)
                    val ambientTemp = dp.getValue(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE)
                    tempF = objectTemp.toFahrenheit()
                    Log.i(TAG, "SkinTempService T= ${"%.1f".format(tempF)}°F")

                    val logLine =
                        "SkinTempService status=$status objectF=${tempF}°F objectC=${objectTemp}°C " +
                                "ambientF=${ambientTemp.toFahrenheit()}°F ambientC=${ambientTemp}°C"
                    writeToLogFileLocation(logFile, logLine)
                } else {
                    tempF = -1f
                    skinTempLiveData.postValue("N/A")
                }

                CoroutineScope(Dispatchers.IO).launch {
                    repo.storeLatestSkinTemp(tempF)
                }
            }
            // 3️⃣  Schedule the next run *once*, with the helper
            scheduleNextSkinTempReading()
        }

        override fun onFlushCompleted() {
            // No-op for on-demand service
        }

        fun Float.toFahrenheit(): Float = this * 9f/5f + 32f

        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(TAG, "SkinTempService HealthTracker.TrackerError: $error")
        }

    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SkinTempService onCreate()")
        isRunning.set(true)
        startForegroundServiceCompat(NOTIF_ID, importance = android.app.NotificationManager.IMPORTANCE_LOW)

        // Start a dedicated thread for handling callbacks
        handlerThread = HandlerThread("SkinTempThread").apply { start() }
        bckgrndSkinTmpHandler = Handler(handlerThread.looper)
        bckgrndSpo2Handler = Handler(handlerThread.looper)

        // Connect to the Samsung Health Tracking Service
        healthService = HealthTrackingService(connectionListener, applicationContext)
        healthService.connectService()

        // Listen for wrist wear state changes
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
        collectActivityRecFlow()
        collectMarkerFlows()

        CoroutineScope(Dispatchers.IO).launch{
            repo.reportingUpdateIntervalSecs.collect { secs ->
                val newIntervalMs = (secs * 1_000L).coerceAtLeast(MIN_SKIN_TEMP_INTERVAL_MS)
                if (newIntervalMs != DISPATCH_INTERVAL_MS) {
//                    writeToLogFileLocation(logFile, "Dispatch Interval Changed from ${DISPATCH_INTERVAL_MS/1000}s to ${newIntervalMs/1000}s"); Log.i(TAG, "Reporting interval updated -> $secs s")
                    DISPATCH_INTERVAL_MS = newIntervalMs

                    if (deviceWorn) {
                        bckgrndSkinTmpHandler.removeCallbacksAndMessages(null)
                        scheduleNextSkinTempReading()
                        startSkinTracker()
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "SkinTempService OnStartCommand()")
        writeToLogFileLocation(logFile, "---------------New Session of SkinTempService ---------------\n")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "SkinTempService onDestroy()")
        isRunning.set(false)
        deviceWorn = false
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }

        // Ensure listener is removed and thread stopped
        stopSkinTracker(true)
        healthService.disconnectService()
        handlerThread.quitSafely()
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Whenever the device is put on, we want fresh skin temp, not wait for the dispatch interval
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onIsDeviceWornEvent(event: IsDeviceWornEvent) {
        val nowWorn = event.isDeviceWorn
        Log.d(TAG, "Wear event: new=$nowWorn")
        // Clear old callbacks so they don’t pile up
        bckgrndSkinTmpHandler.removeCallbacksAndMessages(null)

        if (nowWorn) {
            deviceWorn = true
            startSkinTracker()    // immediate snapshot
            scheduleNextSkinTempReading()

            // If we’re already overdue, arm immediately so the next STILL fires a reading.
            val overdue = (nowUptime - lastSpo2AtMs) >= spO2IntervalMs
            if (overdue) {
                spo2MeasurementPending = true
                Log.i(TAG, "SPO2 is overdue; pending=true")
            }

            // Always (re)schedule the next arm tick.
            scheduleNextSpo2Arm()

            writeToLogFileLocation(logFile, "deviceWorn=true.")
        } else {
            deviceWorn = false
            stopSkinTracker(true)     // shut it down
            stopSpo2Tracker()
            bckgrndSpo2Handler.removeCallbacks(spo2ArmRunnable)
            writeToLogFileLocation(logFile, "deviceWorn=false.")
        }
    }


    private fun collectActivityRecFlow() {
        scope.launch {
            repo.getLatestActivity
                .filterNotNull()
                .collect { act ->
                    if (act.activity == ActivityRecognized.STILL && act.confidence >= 70) {
                        latestStillAtMs = nowUptime

                        if (!deviceWorn) {
                            Log.w(TAG, "STILL but deviceWorn=false; ignoring")
                            return@collect
                        }

                        val dueByInterval = (nowUptime - lastSpo2AtMs) >= spO2IntervalMs
                        val due = (spo2MeasurementPending || dueByInterval)

                        if (due && !isSpo2HandlerRunning) {
                            // Consume the pending flag so we don't double-trigger
                            val wasPending = spo2MeasurementPending
                            spo2MeasurementPending = false

                            Log.i(
                                TAG,
                                "STILL trigger → start SpO2 (dueByInterval=$dueByInterval, " +
                                        "wasPending=$wasPending, conf=${act.confidence})"
                            )
                            startSpo2Tracker()
                        } else {
                            Log.d(
                                TAG,
                                "STILL trigger but not due (pending=$spo2MeasurementPending, " +
                                        "running=$isSpo2HandlerRunning)"
                            )
                        }
                    }
                }
        }
    }

    private fun collectMarkerFlows() {
        scope.launch(Dispatchers.IO) {
            repo.dropRequests.collect { req ->
                if (!deviceWorn) return@collect
                Log.i(TAG, "Received drop request with device worn...")
                if (req.markerType == DataRepository.MarkerType.FRIENDLY) {
                    Log.i(TAG, "---------> Start SPO2")
                    startSpo2Tracker()
                }
                if (req.markerType == DataRepository.MarkerType.HOSTILE) {
                    Log.i(TAG, "---------> Stop SPO2")
                    stopSpo2Tracker()
                }
            }
        }
    }

    private fun startSkinTracker() {
        if (!::skinTracker.isInitialized || isSkTHandlerRunning) return
        isSkTHandlerRunning = true

        if (availableTrackersList.contains(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND)) {
            bckgrndSkinTmpHandler.post { skinTracker.setEventListener(skinTempTrackerEventListener) }
        } else {
            Log.w(TAG, "Device does not support SKIN_TEMPERATURE_ON_DEMAND")
            isSkTHandlerRunning = false
        }
    }

    private fun stopSkinTracker(clearQueue: Boolean = false) {
        if (!isSkTHandlerRunning) return
        isSkTHandlerRunning = false
        bckgrndSkinTmpHandler.post {
            skinTracker.unsetEventListener()
            if (clearQueue) bckgrndSkinTmpHandler.removeCallbacksAndMessages(null)
        }
    }



}