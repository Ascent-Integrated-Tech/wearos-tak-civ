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
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerCapability
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.PpgType
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
        private const val SPO2_INTERVAL_MINUTES: Long = 60
        private const val STILL_RECENT_WINDOW_MS: Long = 10_000

        // ===== SPO2 RETRY POLICY =====
        private const val MAX_SPO2_RETRIES = 2
        private val RETRY_BACKOFF_MS = longArrayOf(5_000L, 10_000L)
        private const val SPO2_MAX_DURATION_MS = 40_000L

        // ===== PPG SCHEDULER + POLICY (mirrors SpO2) =====
        private const val PPG_INTERVAL_MINUTES: Long = 60
        private const val MAX_PPG_RETRIES = 2
        private val PPG_RETRY_BACKOFF_MS = longArrayOf(5_000L, 10_000L)
        private const val PPG_MAX_DURATION_MS = 20_000L   // hard cap in case of hang
        private const val PPG_SAMPLE_WINDOW_MS = 8_000L   // collect window per session (on-demand)
        private const val PPG_MIN_POINTS = 10             // require at least N points before finalizing
    }

    private val repo: DataRepository
        get() = MainActivity.dataRepository

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var healthService: HealthTrackingService
    private lateinit var skinTracker: HealthTracker
    private lateinit var spo2Tracker: HealthTracker
    private lateinit var ppgTracker: HealthTracker

    private lateinit var healthTrackerCapability: HealthTrackerCapability
    private lateinit var availableTrackersList: List<HealthTrackerType>
    private lateinit var handlerThread: HandlerThread
    private lateinit var backgroundSkinTempHandler: Handler
    private lateinit var backgroundSpo2Handler: Handler
    private lateinit var backgroundPpgHandler: Handler

    @Volatile private var isSkTHandlerRunning = false
    @Volatile private var isSpo2HandlerRunning = false
    @Volatile private var isPpgHandlerRunning = false

    @Volatile private var deviceWorn: Boolean = false

    // Supported --> the watch has these sensors true/false
    // Available --> no other on demand tracker is running true/false (via samsung sdk docs, can't run two on demands at the same time).
    @Volatile private var skinTempSupported: Boolean = false
    @Volatile private var spo2Supported: Boolean     = false
    @Volatile private var ppgSupported: Boolean      = false
    @Volatile private var skinTempAvailable: Boolean = false
    @Volatile private var spo2Available: Boolean     = false
    @Volatile private var ppgAvailable: Boolean      = false

    // Uptime targets (not wall clock)
    private var nextFireSkinTempMs = 0L
    private var nextFireSpo2Ms = 0L
    private var nextFirePpgMs = 0L

    private val nowUptime get() = SystemClock.uptimeMillis()

    // ===== SPO2 RUNTIME STATE =====
    @Volatile private var spo2SessionActive = false
    @Volatile private var spo2MeasurementPending: Boolean = false
    @Volatile private var lastSpo2AtMs: Long = 0L
    @Volatile private var latestStillAtMs: Long = 0L
    @Volatile private var spo2Attempt: Int = 0

    private val spO2IntervalMs get() = SPO2_INTERVAL_MINUTES * 60 * 1000L

    // ===== PPG RUNTIME STATE =====
    @Volatile private var ppgSessionActive = false
    @Volatile private var ppgMeasurementPending: Boolean = false
    @Volatile private var lastPpgAtMs: Long = 0L
    @Volatile private var ppgAttempt: Int = 0

    private val ppgIntervalMs get() = PPG_INTERVAL_MINUTES * 60 * 1000L

    private fun setTrackerTracking(type: HealthTrackerType) {
        trackerTrackingMap.replaceAll { _, _ -> false }
        trackerTrackingMap[type] = true
    }
    private lateinit var trackerTrackingMap: HashMap<HealthTrackerType, Boolean>


    // ===== Skin Temp Scheduling =====
    private fun scheduleNextSkinTempReading() {
        if (!skinTempSupported) return
        if (!deviceWorn) return
        val target = nowUptime + DISPATCH_INTERVAL_MS
        nextFireSkinTempMs = target
        backgroundSkinTempHandler.postAtTime(::startSkinTracker, target)
    }

    // ===== SPO2 Scheduler (arm, not start) =====
    private val spo2ArmRunnable = Runnable {
        if (!spo2Supported) return@Runnable
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
        if (!spo2Supported) return
        if (!deviceWorn) return
        val target = nowUptime + minutes * 60 * 1000L
        nextFireSpo2Ms = target
        backgroundSpo2Handler.removeCallbacks(spo2ArmRunnable)
        backgroundSpo2Handler.postAtTime(spo2ArmRunnable, target)
        Log.i(TAG, "SPO2 arm scheduled for t=$target (in ${minutes}m)")
    }

    // ===== PPG Scheduler (arm, not start) =====
    private val ppgArmRunnable = Runnable {
        if (!ppgSupported) return@Runnable
        ppgMeasurementPending = true
        Log.i(TAG, "PPG scheduler armed (pending=true)")
        val now = nowUptime
        val recentStill = (now - latestStillAtMs) <= STILL_RECENT_WINDOW_MS
        if (deviceWorn && recentStill && !isPpgHandlerRunning) {
            Log.i(TAG, "PPG scheduler: recent STILL; starting measurement now.")
            requestStartPpg(reason = "scheduler-recent-still", resetAttempts = true)
        }
    }

    private fun scheduleNextPpgArm(minutes: Long = PPG_INTERVAL_MINUTES) {
        if (!ppgSupported) return
        if (!deviceWorn) return
        val target = nowUptime + minutes * 60 * 1000L
        nextFirePpgMs = target
        backgroundPpgHandler.removeCallbacks(ppgArmRunnable)
        backgroundPpgHandler.postAtTime(ppgArmRunnable, target)
        Log.i(TAG, "PPG arm scheduled for t=$target (in ${minutes}m)")
    }

    private val logFile: File by lazy {
        File(filesDir, LOG_FILENAME).apply { if (!exists()) parentFile?.mkdirs() }
    }

    // ===== Health Service Connection =====
    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            healthTrackerCapability = healthService.trackingCapability
            availableTrackersList = healthTrackerCapability.supportHealthTrackerTypes
            trackerTrackingMap = HashMap()
            Log.i(TAG, "Health SDK Connected. Available trackers: \n |--------> $availableTrackersList")

            // --- DIAGNOSTIC: prove which trackers can be actually created ---
            runCatching {
                val names = availableTrackersList.joinToString(", ") { it.name }
                Log.i(TAG, "Health SDK Connected. Available trackers:\n |--------> [$names]")
            }.onFailure {
                Log.e(TAG, "Failed to enumerate supported trackers", it)
            }

            skinTempSupported = false
            try {
                if (availableTrackersList.contains(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND)) {
                    skinTracker = healthService.getHealthTracker(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND)
                    skinTempSupported = true
                    trackerTrackingMap[HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND] = false
                } else {
                    Log.w(TAG, "SKIN_TEMPERATURE_ON_DEMAND not supported")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "SKIN_TEMPERATURE_ON_DEMAND unavailable at runtime; disabling Skin Temp", t)
                skinTempSupported = false
                stopSkinTracker(true)
            }

            spo2Supported = false
            try {
                if (availableTrackersList.contains(HealthTrackerType.SPO2_ON_DEMAND)) {
                    spo2Tracker = healthService.getHealthTracker(HealthTrackerType.SPO2_ON_DEMAND)
                    spo2Supported = true
                    trackerTrackingMap[HealthTrackerType.SPO2_ON_DEMAND] = false
                } else {
                    Log.w(TAG, "SPO2_ON_DEMAND not supported")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "SPO2_ON_DEMAND unavailable at runtime; disabling Spo2", t)
                spo2Supported = false
                // make sure nothing remains queued
                backgroundSpo2Handler.removeCallbacks(spo2ArmRunnable)
            }

            ppgSupported = false
            try {
                if (availableTrackersList.contains(HealthTrackerType.PPG_ON_DEMAND)) {
                    val ppgTypes = setOf(PpgType.GREEN)
                    ppgTracker = healthService.getHealthTracker(HealthTrackerType.PPG_ON_DEMAND, ppgTypes)
                    ppgSupported = true
                    trackerTrackingMap[HealthTrackerType.PPG_ON_DEMAND] = false
                    Log.i(TAG, "PPG_ON_DEMAND supported")
                } else {
                    Log.w(TAG, "PPG_ON_DEMAND NOT supported; disabling PPG")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "PPG_ON_DEMAND unavailable at runtime; disabling PPG", t)
                ppgSupported = false
                // make sure nothing remains queued
                backgroundPpgHandler.removeCallbacks(ppgArmRunnable)
            }

            if (deviceWorn) {
                if (skinTempSupported) { startSkinTracker(); scheduleNextSkinTempReading(); }
                if (spo2Supported) { scheduleNextSpo2Arm() } else backgroundSpo2Handler.removeCallbacks(spo2ArmRunnable)
                if (ppgSupported) { scheduleNextPpgArm() } else backgroundPpgHandler.removeCallbacks(ppgArmRunnable)
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
            stopPpgTracker()
            backgroundSpo2Handler.removeCallbacks(spo2ArmRunnable)
            backgroundPpgHandler.removeCallbacks(ppgArmRunnable)
            cancelSpo2Watchdog()
            cancelPpgTimers()
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
            try { spo2Tracker.unsetEventListener() } catch (t: Throwable) {
                Log.e(TAG, "Failed to unset SpO₂ listener", t)
            } finally {
                cancelSpo2Watchdog()
                Log.i(TAG, "SpO₂ stopped (attempt=$spo2Attempt)")
            }
        }
    }

    // ===== PPG timers (watchdog + sample-window) =====
    private val ppgWatchdog = Runnable {
        Log.w(TAG, "PPG watchdog fired — stopping after ${PPG_MAX_DURATION_MS}ms (attempt=$ppgAttempt)")
        finalizePpgSession(reason = "watchdog")
    }

    private val ppgSampleStopper = Runnable {
        Log.i(TAG, "PPG sample window ended (${PPG_SAMPLE_WINDOW_MS}ms, attempt=$ppgAttempt)")
        finalizePpgSession(reason = "window-complete")
    }

    private fun cancelPpgTimers() {
        backgroundPpgHandler.removeCallbacks(ppgWatchdog)
        backgroundPpgHandler.removeCallbacks(ppgSampleStopper)
    }

    // ===== PPG Start/Stop/Retry =====
    private fun requestStartPpg(reason: String, resetAttempts: Boolean) {
        if (resetAttempts) ppgAttempt = 0
        startPpgTrackerInternal(reason)
    }

    private fun schedulePpgRetry(reason: String) {
        if (ppgAttempt >= MAX_PPG_RETRIES) {
            Log.w(TAG, "PPG retry cap reached (attempt=$ppgAttempt/$MAX_PPG_RETRIES). Will wait for next arm.")
            scheduleNextPpgArm()
            return
        }
        val backoff = PPG_RETRY_BACKOFF_MS.getOrNull(ppgAttempt.coerceAtLeast(0)) ?: PPG_RETRY_BACKOFF_MS.last()
        ppgAttempt += 1
        Log.w(TAG, "Scheduling PPG retry #$ppgAttempt in ${backoff}ms (reason=$reason)")
        backgroundPpgHandler.postDelayed({ startPpgTrackerInternal("retry-$ppgAttempt") }, backoff)
    }

    private fun startPpgTrackerInternal(reason: String) {
        if (!this::ppgTracker.isInitialized) {
            Log.w(TAG, "PPG tracker not initialized; cannot start (reason=$reason)")
            return
        }
        if (isPpgHandlerRunning) {
            Log.i(TAG, "PPG already running; skip start (reason=$reason)")
            return
        }
        if (!deviceWorn) {
            Log.w(TAG, "Device not worn; skip PPG start (reason=$reason)")
            return
        }
        val supports = availableTrackersList.contains(HealthTrackerType.PPG_ON_DEMAND)
        if (!supports) {
            Log.w(TAG, "PPG_ON_DEMAND not supported; cannot start (reason=$reason)")
            return
        }

        // reset accumulation
        synchronized(ppgAccumLock) {
            ppgCount = 0
            sumRed = 0.0
            sumIr = 0.0
            sumGreen = 0.0
            hadError = false
        }

        isPpgHandlerRunning = true
        ppgSessionActive = true
        Log.i(TAG, "Starting PPG (attempt=$ppgAttempt, reason=$reason) ...")

        backgroundPpgHandler.post {
            try {
                ppgTracker.setEventListener(ppgTrackerEventListener)
                // Arm timers
                backgroundPpgHandler.postDelayed(ppgSampleStopper, PPG_SAMPLE_WINDOW_MS)
                backgroundPpgHandler.postDelayed(ppgWatchdog, PPG_MAX_DURATION_MS)
                Log.i(TAG, "PPG listener set (attempt=$ppgAttempt)")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to set PPG listener", t)
                isPpgHandlerRunning = false
                ppgSessionActive = false
            }
        }
    }

    private fun stopPpgTracker() {
        if (!isPpgHandlerRunning) {
            Log.i(TAG, "StopPpgTracker: no-op (already stopped)")
            return
        }
        isPpgHandlerRunning = false
        ppgSessionActive = false
        Log.i(TAG, "Stopping PPG (attempt=$ppgAttempt) ...")
        backgroundPpgHandler.post {
            try { ppgTracker.unsetEventListener() } catch (t: Throwable) {
                Log.e(TAG, "Failed to unset PPG listener", t)
            } finally {
                cancelPpgTimers()
                Log.i(TAG, "PPG stopped (attempt=$ppgAttempt)")
            }
        }
    }

    // ===== PPG accumulation + finalization =====
    private val ppgAccumLock = Any()
    @Volatile private var ppgCount = 0
    @Volatile private var sumRed = 0.0
    @Volatile private var sumIr = 0.0
    @Volatile private var sumGreen = 0.0
    @Volatile private var hadError = false

    private fun finalizePpgSession(reason: String) {
        // compute summary
        val (cnt, avgRed, avgIr, avgGreen, error) = synchronized(ppgAccumLock) {
            val c = ppgCount
            val r = if (c > 0) sumRed / c else 0.0
            val i = if (c > 0) sumIr / c else 0.0
            val g = if (c > 0) sumGreen / c else 0.0
            val e = hadError
            ppgCount = 0; sumRed = 0.0; sumIr = 0.0; sumGreen = 0.0; hadError = false
            arrayOf(c, r, i, g, e)
        }.let { arr -> Quint(arr[0] as Int, arr[1] as Double, arr[2] as Double, arr[3] as Double, arr[4] as Boolean) }

        stopPpgTracker()
        lastPpgAtMs = nowUptime

        if (cnt >= PPG_MIN_POINTS && !error) {
            Log.i(TAG, "✅ PPG session complete (reason=$reason, points=$cnt) avgRed=${"%.1f".format(avgRed)} avgIR=${"%.1f".format(avgIr)} avgGreen=${"%.1f".format(avgGreen)} (attempt=$ppgAttempt)")
            ppgMeasurementPending = false
            scheduleNextPpgArm()
        } else {
            Log.w(TAG, "⚠️ PPG session insufficient (reason=$reason, points=$cnt, hadError=$error, attempt=$ppgAttempt)")
            val recentStill = (nowUptime - latestStillAtMs) <= STILL_RECENT_WINDOW_MS
            if (deviceWorn && recentStill) {
                schedulePpgRetry(reason = "insufficient")
            } else {
                scheduleNextPpgArm()
            }
        }
    }

    private data class Quint(val c: Int, val r: Double, val i: Double, val g: Double, val e: Boolean)

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

                    lastSpo2AtMs = nowUptime
                    stopSpo2Tracker()

                    if (status == 2) {
                        spo2MeasurementPending = false
                        scheduleNextSpo2Arm()
                    } else {
                        val recentStill = (nowUptime - latestStillAtMs) <= STILL_RECENT_WINDOW_MS
                        if (deviceWorn && recentStill) {
                            scheduleSpo2Retry(reason = "terminal-$status")
                        } else {
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
            val recentStill = (nowUptime - latestStillAtMs) <= STILL_RECENT_WINDOW_MS
            if (deviceWorn && recentStill) {
                scheduleSpo2Retry(reason = "error-${error.name}")
            } else {
                scheduleNextSpo2Arm()
            }
        }
    }

    // ===== PPG Listener =====
    private val ppgTrackerEventListener = object : HealthTracker.TrackerEventListener {
        private var startedAtUptime: Long = 0L
        override fun onDataReceived(points: List<DataPoint?>) {
            if (!ppgSessionActive) return
            if (startedAtUptime == 0L) startedAtUptime = nowUptime

            for (p in points) {
                val point = p ?: continue

                // Raw channels
                val red   = point.getValue(ValueKey.PpgSet.PPG_RED)   ?: 0
                val ir    = point.getValue(ValueKey.PpgSet.PPG_IR)    ?: 0
                val green = point.getValue(ValueKey.PpgSet.PPG_GREEN) ?: 0

                // Status flags (0 = normal, -1 = error for ON_DEMAND)
                val redSt   = point.getValue(ValueKey.PpgSet.RED_STATUS)   ?: 0
                val irSt    = point.getValue(ValueKey.PpgSet.IR_STATUS)    ?: 0
                val greenSt = point.getValue(ValueKey.PpgSet.GREEN_STATUS) ?: 0

                val elapsed = nowUptime - startedAtUptime
                Log.i(TAG, "PPG upd: red=$red(irSt=$irSt) ir=$ir(redSt=$redSt) green=$green(greenSt=$greenSt) elapsed=${elapsed}ms (attempt=$ppgAttempt)")

                synchronized(ppgAccumLock) {
                    if (redSt == -1 || irSt == -1 || greenSt == -1) {
                        hadError = true
                    }
                    sumRed += red
                    sumIr += ir
                    sumGreen += green
                    ppgCount += 1
                }
            }
        }

        override fun onFlushCompleted() { /* no-op */ }

        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(TAG, "PPG tracker error: $error (attempt=$ppgAttempt)")
            finalizePpgSession(reason = "error-${error.name}")
        }
    }

    // ===== Skin Temperature Listener (unchanged logic) =====
    private val skinTempTrackerEventListener = object : HealthTracker.TrackerEventListener {

        override fun onDataReceived(dataPoints: List<DataPoint>) {
            stopSkinTracker(false)

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

                scope.launch {
                    repo.storeLatestSkinTemp(tempF)
                }
            }
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
        backgroundPpgHandler = Handler(handlerThread.looper)

        healthService = HealthTrackingService(connectionListener, applicationContext)
        healthService.connectService()

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

        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
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
        stopPpgTracker()
        backgroundSpo2Handler.removeCallbacks(spo2ArmRunnable)
        backgroundPpgHandler.removeCallbacks(ppgArmRunnable)
        cancelSpo2Watchdog()
        cancelPpgTimers()

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

        backgroundSkinTempHandler.removeCallbacksAndMessages(null)

        if (nowWorn) {
            deviceWorn = true
            startSkinTracker()
            scheduleNextSkinTempReading()

            val spo2Overdue = (nowUptime - lastSpo2AtMs) >= spO2IntervalMs
            if (spo2Overdue) { spo2MeasurementPending = true; Log.i(TAG, "SPO2 is overdue; pending=true") }
            scheduleNextSpo2Arm()

            val ppgOverdue = (nowUptime - lastPpgAtMs) >= ppgIntervalMs
            if (ppgOverdue) { ppgMeasurementPending = true; Log.i(TAG, "PPG is overdue; pending=true") }
            scheduleNextPpgArm()

            writeToLogFileLocation(logFile, "deviceWorn=true.")
        } else {
            deviceWorn = false
            stopSkinTracker(true)
            stopSpo2Tracker()
            stopPpgTracker()
            backgroundSpo2Handler.removeCallbacks(spo2ArmRunnable)
            backgroundPpgHandler.removeCallbacks(ppgArmRunnable)
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

                        // SpO2 due?
                        val spo2DueByInterval = (nowUptime - lastSpo2AtMs) >= spO2IntervalMs
                        val spo2Due = (spo2MeasurementPending || spo2DueByInterval)
                        if (spo2Due && !isSpo2HandlerRunning) {
                            val wasPending = spo2MeasurementPending
                            spo2MeasurementPending = false
                            Log.i(TAG, "STILL trigger → start SpO2 (dueByInterval=$spo2DueByInterval, wasPending=$wasPending)")
                            requestStartSpo2(reason = "still", resetAttempts = true)
                        }

                        // PPG due?
                        val ppgDueByInterval = (nowUptime - lastPpgAtMs) >= ppgIntervalMs
                        val ppgDue = (ppgMeasurementPending || ppgDueByInterval)
                        if (ppgDue && !isPpgHandlerRunning) {
                            val wasPendingPpg = ppgMeasurementPending
                            ppgMeasurementPending = false
                            Log.i(TAG, "STILL trigger → start PPG (dueByInterval=$ppgDueByInterval, wasPending=$wasPendingPpg)")
                            requestStartPpg(reason = "still", resetAttempts = true)
                        }
                    }
                }
        }
    }

    // ===== Manual marker flow (optional: start/stop SpO2; mirrored for PPG) =====
    private fun collectMarkerFlows() {
        scope.launch(Dispatchers.IO) {
            repo.dropRequests.collect { req ->
                if (!deviceWorn) return@collect
                Log.i(TAG, "Received drop request with device worn...")
                if (req.markerType == DataRepository.MarkerType.FRIENDLY) {
                    Log.i(TAG, "---------> Start SPO2 (manual)")
                    requestStartSpo2(reason = "manual", resetAttempts = true)

                    Log.i(TAG, "---------> Start PPG (manual)")
                    requestStartPpg(reason = "manual", resetAttempts = true)
                }
                if (req.markerType == DataRepository.MarkerType.HOSTILE) {
                    Log.i(TAG, "---------> Stop SPO2 (manual)")
                    stopSpo2Tracker()

                    Log.i(TAG, "---------> Stop PPG (manual)")
                    stopPpgTracker()
                }
            }
        }
    }

    // ===== SkinTemp start/stop (unchanged logic) =====
    private fun startSkinTracker() {
        if (!::skinTracker.isInitialized || isSkTHandlerRunning || !skinTempSupported) return
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