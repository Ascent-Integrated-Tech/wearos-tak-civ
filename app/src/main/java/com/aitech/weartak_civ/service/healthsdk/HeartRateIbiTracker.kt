package com.aitech.weartak_civ.service.healthsdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import java.util.concurrent.atomic.AtomicBoolean

class HeartRateIbiTracker(
    private val context: Context,
    private val bus: HealthStreamBus
) {
    companion object {
        private const val TAG = "HeartRateIbiTracker"

        private const val CALLBACK_STALE_MS = 25_000L
        private const val VALID_HR_STALE_INTERACTIVE_MS = 25_000L
        private const val VALID_HR_STALE_SCREEN_OFF_MS = 3 * 60 * 1000L

        private const val SCREEN_OFF_FLUSH_INTERVAL_MS = 2 * 60 * 1000L
        private const val WATCHDOG_INTERVAL_MS = 10_000L

        private const val MAX_RESTARTS_IN_WINDOW = 6
        private const val RESTART_WINDOW_MS = 2 * 60 * 1000L
    }

    private val connected = AtomicBoolean(false)
    private val isMeasurementRequested = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)
    private val screenOffFlushEnabled = AtomicBoolean(false)

    private var reconnectAttempt = 0
    private var restartCount = 0
    private var restartWindowStartElapsed = 0L

    private var wakeLock: PowerManager.WakeLock? = null

    private var hts: HealthTrackingService? = null
    private var tracker: HealthTracker? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val flushHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // timestamps + last-known-good
    private var lastCallbackElapsed = 0L
    private var lastValidHrElapsed = 0L
    private var lastNonZeroHr = 0
    private var lastNonZeroIbi = 0

    // batch most recent
    private var batchMostRecentHr = 0
    private var batchMostRecentHrStatus = Int.MIN_VALUE
    private var batchMostRecentIbi = 0

    // --- Connection management ------------------------------------------------
    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            Log.i(TAG, "HTS CONNECTED t=${SystemClock.elapsedRealtime()}")
            connected.set(true)
            reconnectAttempt = 0
            initHeartTracker()
            if (isMeasurementRequested.get()) start()
        }

        override fun onConnectionEnded() {
            Log.e(TAG, "HTS DISCONNECTED t=${SystemClock.elapsedRealtime()}")
            connected.set(false)
            if (isMeasurementRequested.get()) scheduleReconnect()
        }

        override fun onConnectionFailed(e: HealthTrackerException) {
            Log.e(TAG, "HTS CONNECTION FAILED t=${SystemClock.elapsedRealtime()} err=$e")
            connected.set(false)
            if (isMeasurementRequested.get()) scheduleReconnect()
        }
    }

    fun connect() {
        try {
            hts = HealthTrackingService(connectionListener, context.applicationContext)
            hts?.connectService()
            // Log-only subscription to device worn state (no gating yet)
            scope.launch {
                bus.deviceWorn.collectLatest { worn ->
                    Log.w(TAG, "deviceWorn update: $worn (NOT gating yet)")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create/connect HTS", t)
            connected.set(false)
            if (isMeasurementRequested.get()) scheduleReconnect()
        }
    }

    fun disconnect() {
        try { hts?.disconnectService() } catch (_: Throwable) {}
        connected.set(false)
        scope.cancel()
    }

    private fun initHeartTracker() {
        val local = hts ?: return
        tracker = try {
            local.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
        } catch (t: Throwable) {
            Log.e(TAG, "Tracker init failed", t)
            null
        }
        Log.i(TAG, "Tracker init: HEART_RATE_CONTINUOUS tracker=${tracker != null}")
    }

    // --- Listener + snapshot parsing -----------------------------------------
    private val trackerListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(list: MutableList<DataPoint>) {
            lastCallbackElapsed = SystemClock.elapsedRealtime()
            Log.i(TAG, "onDataReceived: points=${list.size}")

            // reset batch tracking
            batchMostRecentHr = 0
            batchMostRecentHrStatus = Int.MIN_VALUE
            batchMostRecentIbi = 0

            var ibiLoggedOnce = false

            for (i in list.indices) {
                val dp = list[i]
                try {
                    val status = dp.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS)
                    val hr = dp.getValue(ValueKey.HeartRateSet.HEART_RATE)

                    batchMostRecentHr = hr
                    batchMostRecentHrStatus = status

                    if (status == 1 && hr > 0) {
                        lastValidHrElapsed = SystemClock.elapsedRealtime()
                        lastNonZeroHr = hr
                    }

                    @Suppress("UNCHECKED_CAST")
                    val ibiList = dp.getValue(ValueKey.HeartRateSet.IBI_LIST) as? List<Int>
                    @Suppress("UNCHECKED_CAST")
                    val ibiStatusList = dp.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST) as? List<Int>

                    if (i == 0 && !ibiList.isNullOrEmpty()) {
                        val lastIbiInList = ibiList.last()
                        batchMostRecentIbi = lastIbiInList
                        if (lastIbiInList > 0) lastNonZeroIbi = lastIbiInList
                    }

                    if (!ibiList.isNullOrEmpty() && !ibiLoggedOnce) {
                        Log.i(TAG, "[0] IBI_LIST(ms)=$ibiList")
                        Log.i(TAG, "[0] IBI_STATUS_LIST=$ibiStatusList")
                        ibiLoggedOnce = true
                    }

                } catch (t: Throwable) {
                    Log.e(TAG, "Error parsing datapoint idx=$i", t)
                }
            }

            val effectiveHr = if (batchMostRecentHr > 0) batchMostRecentHr else lastNonZeroHr
            val effectiveIbi = if (batchMostRecentIbi > 0) batchMostRecentIbi else lastNonZeroIbi

            Log.i(
                TAG,
                "LATEST-SNAPSHOT: HR=$effectiveHr (raw=$batchMostRecentHr, status=$batchMostRecentHrStatus, lastNonZero=$lastNonZeroHr) " +
                        "IBI=$effectiveIbi (raw=$batchMostRecentIbi, lastNonZero=$lastNonZeroIbi)"
            )

            bus.onSamsungHrIbiSnapshot(
                hr = effectiveHr,
                hrStatus = batchMostRecentHrStatus,
                ibiMs = effectiveIbi,
                lastCallbackElapsedRealtime = lastCallbackElapsed
            )
        }

        override fun onFlushCompleted() {
            Log.i(TAG, "Flush completed t=${SystemClock.elapsedRealtime()}")
        }

        override fun onError(trackerError: HealthTracker.TrackerError) {
            Log.e(TAG, "Tracker error: $trackerError")
            val mapped = when (trackerError) {
                HealthTracker.TrackerError.PERMISSION_ERROR -> HealthSdkError.PermissionDenied
                HealthTracker.TrackerError.SDK_POLICY_ERROR -> HealthSdkError.SdkPolicyError
                else -> HealthSdkError.Failed(trackerError.toString())
            }
            bus.emit(HealthStreamEvent.Error(mapped, System.currentTimeMillis()))
        }
    }

    // --- Public control (assume worn) ----------------------------------------
    fun requestStart() {
        isMeasurementRequested.set(true)
        start()
    }

    fun requestStop() {
        isMeasurementRequested.set(false)
        stop()
    }

    private fun start() {
        if (!connected.get()) {
            Log.w(TAG, "START BLOCKED: not connected")
            return
        }
        val t = tracker
        if (t == null) {
            Log.w(TAG, "START BLOCKED: tracker null")
            return
        }
        ensureWakeLock()
        if (wakeLock?.isHeld != true) {
            try { wakeLock?.acquire() } catch (_: Throwable) {}
            Log.i(TAG, "WakeLock ACQUIRE held=${wakeLock?.isHeld}")
        }
        mainHandler.post { t.setEventListener(trackerListener) }
        isRunning.set(true)
        Log.i(TAG, "Measurement started.")
        startWatchdog()
    }

    private fun stop() {
        try { tracker?.unsetEventListener() } catch (_: Throwable) {}
        isRunning.set(false)
        stopScreenOffFlushLoop()
        stopWatchdog()
        if (wakeLock?.isHeld == true) {
            try { wakeLock?.release() } catch (_: Throwable) {}
            Log.i(TAG, "WakeLock RELEASE")
        }
        Log.i(TAG, "Measurement stopped.")
    }

    private fun ensureWakeLock() {
        if (wakeLock == null) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager?
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "weartak:HeartRateIbiTracker")?.apply {
                setReferenceCounted(false)
            }
        }
    }

    // --- Screen off flush -----------------------------------------------------
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager?
            val interactive = pm?.isInteractive ?: false
            Log.w(TAG, "SCREEN EVENT: ${intent.action} | isInteractive=$interactive | running=${isRunning.get()}")

            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    startScreenOffFlushLoop()
                    Log.i(TAG, "FLUSH-REQUEST: reason=screen_off_event t=${SystemClock.elapsedRealtime()}")
                    flush("screen_off_event")
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> stopScreenOffFlushLoop()
            }
        }
    }

    fun registerScreenReceiver() {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        context.registerReceiver(screenReceiver, f)
    }

    fun unregisterScreenReceiver() {
        try { context.unregisterReceiver(screenReceiver) } catch (_: Throwable) {}
    }

    private val flushRunnable = object : Runnable {
        override fun run() {
            try {
                if (!screenOffFlushEnabled.get()) return
                if (!isMeasurementRequested.get() || !isRunning.get()) {
                    Log.i(TAG, "FLUSH-TIMER: skipping (not running)")
                    scheduleNext()
                    return
                }
                if (!connected.get() || tracker == null) {
                    Log.w(TAG, "FLUSH-TIMER: not ready connected=${connected.get()} tracker=${tracker != null}")
                    scheduleNext()
                    return
                }
                Log.i(TAG, "FLUSH-TIMER: triggering flush (screen off mode)")
                Log.i(TAG, "FLUSH-REQUEST: reason=screen_off_timer t=${SystemClock.elapsedRealtime()}")
                flush("screen_off_timer")
            } catch (t: Throwable) {
                Log.e(TAG, "FLUSH-TIMER error", t)
            } finally {
                scheduleNext()
            }
        }

        private fun scheduleNext() {
            if (screenOffFlushEnabled.get()) flushHandler.postDelayed(this, SCREEN_OFF_FLUSH_INTERVAL_MS)
        }
    }

    private fun startScreenOffFlushLoop() {
        if (screenOffFlushEnabled.compareAndSet(false, true)) {
            Log.w(TAG, "SCREEN-OFF FLUSH: ENABLED intervalMs=$SCREEN_OFF_FLUSH_INTERVAL_MS")
            flushHandler.removeCallbacks(flushRunnable)
            flushHandler.postDelayed(flushRunnable, 5_000L)
        }
    }

    private fun stopScreenOffFlushLoop() {
        if (screenOffFlushEnabled.compareAndSet(true, false)) {
            Log.w(TAG, "SCREEN-OFF FLUSH: DISABLED")
            flushHandler.removeCallbacks(flushRunnable)
        }
    }

    private fun flush(reason: String) {
        val t = tracker ?: run {
            Log.w(TAG, "flush skipped: tracker null reason=$reason")
            return
        }
        try {
            Log.i(TAG, "flush() calling tracker.flush reason=$reason")
            t.flush()
        } catch (e: Throwable) {
            Log.e(TAG, "flush error reason=$reason", e)
        }
    }

    // --- Watchdog with restart limiter ---------------------------------------
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            try {
                if (isMeasurementRequested.get() && isRunning.get()) {
                    val now = SystemClock.elapsedRealtime()
                    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager?
                    val interactive = pm?.isInteractive ?: false
                    val validThreshold = if (interactive) VALID_HR_STALE_INTERACTIVE_MS else VALID_HR_STALE_SCREEN_OFF_MS

                    val cbDelta = if (lastCallbackElapsed == 0L) Long.MAX_VALUE else (now - lastCallbackElapsed)
                    val validDelta = if (lastValidHrElapsed == 0L) Long.MAX_VALUE else (now - lastValidHrElapsed)

                    Log.w(TAG, "WATCHDOG: interactive=$interactive now=$now lastCb=$lastCallbackElapsed (Δ=$cbDelta) lastValid=$lastValidHrElapsed (Δ=$validDelta) thresholds cb=$CALLBACK_STALE_MS valid=$validThreshold")

                    val cbStale = cbDelta > CALLBACK_STALE_MS
                    val validStale = (lastCallbackElapsed != 0L) && (validDelta > validThreshold)

                    if (cbStale || validStale) {
                        Log.w(TAG, "WATCHDOG: stale detected cbStale=$cbStale validStale=$validStale -> restartListener")
                        if (canRestartNow(interactive)) restartListener()
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Watchdog error", t)
            } finally {
                watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
    }

    private fun startWatchdog() {
        watchdogHandler.removeCallbacks(watchdogRunnable)
        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
    }

    private fun stopWatchdog() {
        watchdogHandler.removeCallbacksAndMessages(null)
    }

    private fun restartListener() {
        val t = tracker ?: return
        try { t.unsetEventListener() } catch (_: Throwable) {}
        try { mainHandler.post { t.setEventListener(trackerListener) } } catch (_: Throwable) {}
        Log.i(TAG, "Tracker listener restarted.")
    }

    private fun canRestartNow(interactive: Boolean): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (restartWindowStartElapsed == 0L || (now - restartWindowStartElapsed) > RESTART_WINDOW_MS) {
            restartWindowStartElapsed = now
            restartCount = 0
        }
        if (screenOffFlushEnabled.get() && !interactive && restartCount >= 1) {
            Log.w(TAG, "RESTART suppressed during screen-off flush window (avoid thrash).")
            return false
        }
        restartCount++
        if (restartCount > MAX_RESTARTS_IN_WINDOW) {
            Log.e(TAG, "RESTART LIMIT reached: $restartCount in window; suppressing.")
            return false
        }
        return true
    }

    // --- Reconnect backoff ----------------------------------------------------
    private fun scheduleReconnect() {
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(6)
        val delayMs = (1L shl reconnectAttempt) * 1000L
        Log.i(TAG, "Scheduling reconnect attempt=$reconnectAttempt in ${delayMs}ms")

        watchdogHandler.postDelayed({
            if (!isMeasurementRequested.get()) {
                Log.w(TAG, "Reconnect skipped: measurement no longer requested.")
                return@postDelayed
            }
            Log.i(TAG, "Attempting reconnect...")
            connect()
        }, delayMs)
    }
}


