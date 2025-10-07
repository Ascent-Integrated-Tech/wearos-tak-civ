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
import kotlinx.coroutines.cancel
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

        // ===== SPO2 SCHEDULER + POLICY =====
        private const val SPO2_INTERVAL_MINUTES: Long = 60        // target interval in minutes
        private const val STILL_RECENT_WINDOW_MS: Long = 10_000   // how "recent" a STILL must be for immediate start

        // ===== SPO2 RETRY POLICY =====
        private const val MAX_SPO2_RETRIES = 2                      // In the event we get weak signal, movement during sample, how many times should we retry?
        private val RETRY_BACKOFF_MS = longArrayOf(5_000L, 10_000L) // per attempt (1..MAX), safe bounds
        private const val SPO2_MAX_DURATION_MS = 40_000L            // watchdog: hard stop if session hangs
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
    private lateinit var backgroundSkinTempHandler: Handler
    private lateinit var backgroundSpo2Handler: Handler

    @Volatile private var isSkTHandlerRunning = false
    @Volatile private var isSpo2HandlerRunning = false

    @Volatile private var deviceWorn: Boolean = false

    // Uptime targets (not wall clock)
    private var nextFireSkinTempMs = 0L
    private var nextFireSpo2Ms = 0L

    private val nowUptime get() = SystemClock.uptimeMillis()

    // ===== SPO2 RUNTIME STATE =====
    @Volatile private var spo2SessionActive = false
    @Volatile private var spo2MeasurementPending: Boolean = false
    @Volatile private var lastSpo2AtMs: Long = 0L
    @Volatile private var latestStillAtMs: Long = 0L
    @Volatile private var spo2Attempt: Int = 0 // 0 = first attempt, 1..MAX for retries

    private val spO2IntervalMs get() = SPO2_INTERVAL_MINUTES * 60 * 1000L

    // ===== Skin Temp Scheduling =====
    private fun scheduleNextSkinTempReading() {
        if (!deviceWorn) return
        val target = nowUptime + DISPATCH_INTERVAL_MS
        nextFireSkinTempMs = target
        backgroundSkinTempHandler.postAtTime(::startSkinTracker, target)
    }

    // ===== SPO2 Scheduler (arm, not start) =====
    private val spo2ArmRunnable = Runnable {
        spo2MeasurementPending = true
        Log.i(TAG, "SPO2 scheduler armed (pending=true)")
        val now = nowUptime
        val recentStill = (now - latestStillAtMs) <= STILL_RECENT_WINDOW_MS
        if (deviceWorn && recentStill && !isSpo2HandlerRunning) {
            Log.i(TAG, "SPO2 scheduler: recent STILL; starting measurement now.")
            requestStartSpo2(reason = "scheduler-recent-still", resetAttempts = true)
        }
    }

    private fun scheduleNextSpo2Arm(minutes: Long = SPO2_INTERVAL_MINUTES) {
        if (!deviceWorn) return
        val target = nowUptime + minutes * 60 * 1000L
        nextFireSpo2Ms = target
        backgroundSpo2Handler.removeCallbacks(spo2ArmRunnable)
        backgroundSpo2Handler.postAtTime(spo2ArmRunnable, target)
        Log.i(TAG, "SPO2 arm scheduled for t=$target (in ${minutes}m)")
    }

    private val logFile: File by lazy {
        File(filesDir, LOG_FILENAME).apply { if (!exists()) parentFile?.mkdirs() }
    }

    // ===== Health Service Connection =====
    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            healthTrackerCapability = healthService.trackingCapability
            availableTrackersList = healthTrackerCapability.supportHealthTrackerTypes
            skinTracker = healthService.getHealthTracker(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND)
            spo2Tracker = healthService.getHealthTracker(HealthTrackerType.SPO2_ON_DEMAND)
            Log.i(TAG, "Health SDK Connected")

            if (deviceWorn) {
                startSkinTracker()
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
            stopSkinTracker(true)
            stopSpo2Tracker()
            backgroundSpo2Handler.removeCallbacks(spo2ArmRunnable)
            cancelSpo2Watchdog()
        }
    }

    // ===== SPO2 Watchdog =====
    private val spo2Watchdog = Runnable {
        Log.w(TAG, "SpO₂ watchdog fired — stopping after ${SPO2_MAX_DURATION_MS}ms (attempt=$spo2Attempt)")
        stopSpo2Tracker()
        lastSpo2AtMs = nowUptime
        scheduleNextSpo2Arm()
    }

    private fun cancelSpo2Watchdog() {
        backgroundSpo2Handler.removeCallbacks(spo2Watchdog)
    }

    // ===== SPO2 Start/Stop (with attempt + logging) =====
    private fun requestStartSpo2(reason: String, resetAttempts: Boolean) {
        if (resetAttempts) spo2Attempt = 0
        startSpo2TrackerInternal(reason)
    }

    private fun scheduleSpo2Retry(reason: String) {
        if (spo2Attempt >= MAX_SPO2_RETRIES) {
            Log.w(TAG, "SPO2 retry cap reached (attempt=$spo2Attempt/$MAX_SPO2_RETRIES). Will wait for next arm.")
            scheduleNextSpo2Arm()
            return
        }
        val backoff = RETRY_BACKOFF_MS.getOrNull(spo2Attempt.coerceAtLeast(0)) ?: RETRY_BACKOFF_MS.last()
        spo2Attempt += 1
        Log.w(TAG, "Scheduling SpO₂ retry #$spo2Attempt in ${backoff}ms (reason=$reason)")
        backgroundSpo2Handler.postDelayed({ startSpo2TrackerInternal("retry-$spo2Attempt") }, backoff)
    }

    private fun startSpo2TrackerInternal(reason: String) {
        if (!::spo2Tracker.isInitialized) {
            Log.w(TAG, "SPO2 tracker not initialized; cannot start (reason=$reason)")
            return
        }
        if (isSpo2HandlerRunning) {
            Log.i(TAG, "SPO2 already running; skip start (reason=$reason)")
            return
        }
        if (!deviceWorn) {
            Log.w(TAG, "Device not worn; skip SpO₂ start (reason=$reason)")
            return
        }

        val supports = availableTrackersList.contains(HealthTrackerType.SPO2_ON_DEMAND)
        if (!supports) {
            Log.w(TAG, "SPO2_ON_DEMAND not supported; cannot start (reason=$reason)")
            return
        }

        isSpo2HandlerRunning = true
        spo2SessionActive = true
        Log.i(TAG, "Starting SpO₂ (attempt=$spo2Attempt, reason=$reason) ...")

        backgroundSpo2Handler.post {
            try {
                spo2Tracker.setEventListener(spO2TrackerEventListener)
                backgroundSpo2Handler.postDelayed(spo2Watchdog, SPO2_MAX_DURATION_MS)
                Log.i(TAG, "SpO₂ listener set (attempt=$spo2Attempt)")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to set SpO₂ listener", t)
                isSpo2HandlerRunning = false
                spo2SessionActive = false
            }
        }
    }

    private fun stopSpo2Tracker() {
        if (!isSpo2HandlerRunning) {
            Log.i(TAG, "StopSpO2Tracker: no-op (already stopped)")
            return
        }
        isSpo2HandlerRunning = false
        spo2SessionActive = false
        Log.i(TAG, "Stopping SpO₂ (attempt=$spo2Attempt) ...")
        backgroundSpo2Handler.post {
            try {
                spo2Tracker.unsetEventListener()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to unset SpO₂ listener", t)
            } finally {
                cancelSpo2Watchdog()
                Log.i(TAG, "SpO₂ stopped (attempt=$spo2Attempt)")
            }
        }
    }

    // ===== SPO2 Listener =====
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

                val elapsed = nowUptime - startedAtUptime
                Log.i(TAG, "SpO₂ upd: status=$status acc=$accuracy spo2=$spo2 hr=$hr elapsed=${elapsed}ms (attempt=$spo2Attempt)")

                val terminal = when (status) { 2, -6, -5, -4 -> true; else -> false }

                if (terminal) {
                    when (status) {
                        2   -> Log.i(TAG, "✅ SpO₂ complete: $spo2% (HR=$hr, acc=$accuracy, attempt=$spo2Attempt)")
                        -6  -> Log.w(TAG, "⚠️ SpO₂ TIMEOUT (attempt=$spo2Attempt)")
                        -5  -> Log.w(TAG, "⚠️ SpO₂ LOW SIGNAL (attempt=$spo2Attempt)")
                        -4  -> Log.w(TAG, "⚠️ SpO₂ MOVED (attempt=$spo2Attempt)")
                    }

                    // Finish current session
                    lastSpo2AtMs = nowUptime
                    stopSpo2Tracker()

                    // On success → re-arm next interval; on failure → retry a few times, else re-arm
                    if (status == 2) {
                        spo2MeasurementPending = false
                        scheduleNextSpo2Arm()
                    } else {
                        // retry only if still conditions are likely okay
                        val recentStill = (nowUptime - latestStillAtMs) <= STILL_RECENT_WINDOW_MS
                        if (deviceWorn && recentStill) {
                            scheduleSpo2Retry(reason = "terminal-$status")
                        } else {
                            Log.w(TAG, "Skip retry (deviceWorn=$deviceWorn, recentStill=$recentStill)")
                            scheduleNextSpo2Arm()
                        }
                    }
                    break
                }
            }
        }

        override fun onFlushCompleted() { /* no-op */ }

        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(TAG, "SpO₂ tracker error: $error (attempt=$spo2Attempt)")
            lastSpo2AtMs = nowUptime
            stopSpo2Tracker()
            // Treat as failure; try retry path if appropriate
            val recentStill = (nowUptime - latestStillAtMs) <= STILL_RECENT_WINDOW_MS
            if (deviceWorn && recentStill) {
                scheduleSpo2Retry(reason = "error-${error.name}")
            } else {
                scheduleNextSpo2Arm()
            }
        }
    }

    // ===== Skin Temperature Listener (unchanged logic) =====
    private val skinTempTrackerEventListener = object : HealthTracker.TrackerEventListener {

        override fun onDataReceived(dataPoints: List<DataPoint>) {
            // 1️⃣  Stop sensor immediately (but keep queue)
            stopSkinTracker(false)

            // 2️⃣  Collect temp data point
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

                // Use service scope (prevents leaks)
                scope.launch {
                    repo.storeLatestSkinTemp(tempF)
                }
            }
            // 3️⃣  Schedule the next run *once*, with the helper
            scheduleNextSkinTempReading()
        }

        override fun onFlushCompleted() { /* no-op for on-demand */ }

        fun Float.toFahrenheit(): Float = this * 9f/5f + 32f

        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(TAG, "SkinTempService HealthTracker.TrackerError: $error")
        }
    }

    // ===== Lifecycle =====
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SkinTempService onCreate()")
        isRunning.set(true)
        startForegroundServiceCompat(NOTIF_ID, importance = android.app.NotificationManager.IMPORTANCE_LOW)

        handlerThread = HandlerThread("SkinTempThread").apply { start() }
        backgroundSkinTempHandler = Handler(handlerThread.looper)
        backgroundSpo2Handler = Handler(handlerThread.looper)

        healthService = HealthTrackingService(connectionListener, applicationContext)
        healthService.connectService()

        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
        collectActivityRecFlow()
        collectMarkerFlows()

        scope.launch {
            repo.reportingUpdateIntervalSecs.collect { secs ->
                val newIntervalMs = (secs * 1_000L).coerceAtLeast(MIN_SKIN_TEMP_INTERVAL_MS)
                if (newIntervalMs != DISPATCH_INTERVAL_MS) {
                    DISPATCH_INTERVAL_MS = newIntervalMs
                    if (deviceWorn) {
                        backgroundSkinTempHandler.removeCallbacksAndMessages(null)
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

        stopSpo2Tracker()
        backgroundSpo2Handler.removeCallbacks(spo2ArmRunnable)
        cancelSpo2Watchdog()

        stopSkinTracker(true)
        healthService.disconnectService()

        scope.cancel()
        handlerThread.quitSafely()
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ===== Wear state =====
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onIsDeviceWornEvent(event: IsDeviceWornEvent) {
        val nowWorn = event.isDeviceWorn
        Log.d(TAG, "Wear event: new=$nowWorn")

        // Clear old skin callbacks so they don’t pile up
        backgroundSkinTempHandler.removeCallbacksAndMessages(null)

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

            scheduleNextSpo2Arm()
            writeToLogFileLocation(logFile, "deviceWorn=true.")
        } else {
            deviceWorn = false
            stopSkinTracker(true)
            stopSpo2Tracker()
            backgroundSpo2Handler.removeCallbacks(spo2ArmRunnable)
            writeToLogFileLocation(logFile, "deviceWorn=false.")
        }
    }

    // ===== Activity recognition (STILL trigger path) =====
    private fun collectActivityRecFlow() {
        scope.launch {
            repo.getLatestActivity
                .filterNotNull()
                .collect { act ->
                    if (act.activity == ActivityRecognized.STILL && act.confidence >= 70) {
                        latestStillAtMs = nowUptime
                        Log.i(TAG, "STILL observed (conf=${act.confidence})")

                        if (!deviceWorn) {
                            Log.w(TAG, "STILL but deviceWorn=false; ignoring")
                            return@collect
                        }

                        val dueByInterval = (nowUptime - lastSpo2AtMs) >= spO2IntervalMs
                        val due = (spo2MeasurementPending || dueByInterval)

                        if (due && !isSpo2HandlerRunning) {
                            val wasPending = spo2MeasurementPending
                            spo2MeasurementPending = false
                            Log.i(TAG, "STILL trigger → start SpO2 (dueByInterval=$dueByInterval, wasPending=$wasPending)")
                            requestStartSpo2(reason = "still", resetAttempts = true)
                        } else {
                            Log.d(TAG, "STILL but not due (pending=$spo2MeasurementPending, running=$isSpo2HandlerRunning)")
                        }
                    }
                }
        }
    }

    // ===== Manual marker flow (start/stop by user action) =====
    private fun collectMarkerFlows() {
        scope.launch(Dispatchers.IO) {
            repo.dropRequests.collect { req ->
                if (!deviceWorn) return@collect
                Log.i(TAG, "Received drop request with device worn...")
                if (req.markerType == DataRepository.MarkerType.FRIENDLY) {
                    Log.i(TAG, "---------> Start SPO2 (manual)")
                    requestStartSpo2(reason = "manual", resetAttempts = true)
                }
                if (req.markerType == DataRepository.MarkerType.HOSTILE) {
                    Log.i(TAG, "---------> Stop SPO2 (manual)")
                    stopSpo2Tracker()
                }
            }
        }
    }

    // ===== SkinTemp start/stop (unchanged logic) =====
    private fun startSkinTracker() {
        if (!::skinTracker.isInitialized || isSkTHandlerRunning) return
        isSkTHandlerRunning = true

        if (availableTrackersList.contains(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND)) {
            backgroundSkinTempHandler.post { skinTracker.setEventListener(skinTempTrackerEventListener) }
        } else {
            Log.w(TAG, "Device does not support SKIN_TEMPERATURE_ON_DEMAND")
            isSkTHandlerRunning = false
        }
    }

    private fun stopSkinTracker(clearQueue: Boolean = false) {
        if (!isSkTHandlerRunning) return
        isSkTHandlerRunning = false
        backgroundSkinTempHandler.post {
            skinTracker.unsetEventListener()
            if (clearQueue) backgroundSkinTempHandler.removeCallbacksAndMessages(null)
        }
    }
}