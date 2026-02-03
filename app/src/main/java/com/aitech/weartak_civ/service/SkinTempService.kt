/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

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
import com.aitech.weartak_civ.cep.bus.CepBus
import com.aitech.weartak_civ.cep.di.CepProvider
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.Topics
import com.aitech.weartak_civ.cep.model.payloads.PpgPayload
import com.aitech.weartak_civ.cep.model.payloads.SkinTempPayload
import com.aitech.weartak_civ.cep.model.payloads.Spo2Payload
//import com.aitech.weartak_civ.cep.CepProvider
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.ValueKey
import com.samsung.android.service.health.tracking.data.PpgType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class SkinTempService : Service() {
    companion object {
        private const val TAG = "WearTAK-SkinTService"
        private const val NOTIF_ID = 177

        var tempF: Float = -1f
        val skinTempLiveData = MutableLiveData<String>("N/A")
        val isRunning = AtomicBoolean(false)
        var DISPATCH_INTERVAL_MS = 60 * 1_000L
        private const val MIN_SKIN_TEMP_INTERVAL_MS = 10_000L
        val LOG_FILENAME = "skin_temp.log"
    }


    private val sharedRepo: DataRepository
        get() = MainActivity.dataRepository

    private lateinit var healthService: HealthTrackingService
    private lateinit var skinTracker: HealthTracker
    private lateinit var spo2Tracker: HealthTracker
    // PPG capability model
    private var ppgTracker: HealthTracker? = null
    private var ppgTrackerType: HealthTrackerType? = null
    private var ppgAvailable: Boolean = false
    /*
     * Rationale for pausing PPG_CONTINUOUS during SpO2:
     * - On Samsung wearables, the optical stack can exhibit resource contention when a continuous PPG
     *   listener is active concurrently with an on‑demand SpO2 measurement. This often manifests as
     *   SpO2 remaining in interim states (e.g., 0/WAIT, -4, -6) and failing to reach STATUS==2 before timeout.
     * - To eliminate contention, we temporarily pause the continuous PPG listener immediately before
     *   starting the SpO2 measurement, and resume it as soon as SpO2 completes, errors, or times out.
     * - This preserves a stable rolling PPG amplitude for cold‑stress detection while restoring reliable
     *   SpO2 completion behavior.
     */
    private var ppgPausedForSpo2: Boolean = false
    private var lastSpo2AttemptWallMs: Long = 0L
    // Short on‑demand SpO2 session timeout and backoff
    /*
     * Session model alignment (Samsung SpO2 codelab):
     * - SpO2 is acquired as a short, on‑demand "measurement session": attach a listener, observe STATUS on
     *   each callback, only read SPO2 when STATUS==2 (MEASUREMENT_COMPLETED), then promptly stop/unset.
     * - Non‑terminal interim statuses (e.g., CALCULATING) should not prolong the session beyond a strict timeout.
     * - TIMEOUT is considered terminal by SHS; in our logs it manifests as STATUS=-6. We end the session
     *   immediately on this status and apply a longer backoff to prevent repeated churn under poor conditions.
     * - PPG continuous is paused for the duration of the SpO2 session to avoid optical contention,
     *   then resumed regardless of the exit path (complete/error/timeout).
     */
    private val SPO2_TIMEOUT_MS: Long = 30_000L
    private val SPO2_BACKOFF_AFTER_TIMEOUT_MS: Long = 30 * 60_000L // 30 minutes after timeout
    private val SPO2_MIN_GAP_MS: Long = 10 * 60_000L
    // SHS "TIMEOUT" appears as status = -6
    private val SPO2_STATUS_TIMEOUT: Int = -6
    // Stillness signal placeholder (to be wired to Activity/Accel)
    @Volatile private var motionActive: Boolean = false
    // Track last SpO2 outcome for backoff and diagnostics
    @Volatile private var lastSpo2OutcomeWasTimeout: Boolean = false
    @Volatile private var lastSpo2OutcomeWallMs: Long = 0L
    // Rolling 20s PPG window + amplitude cache
    private data class TimedSample(val tUptimeMs: Long, val v: Float)
    private val ppgWindow = ArrayDeque<TimedSample>(4096)
    private val PPG_WINDOW_MS = 20_000L
    @Volatile private var lastPpgSampleCount: Int = 0
    private var lastPpgComputeUptimeMs: Long = 0L
    private val PPG_COMPUTE_PERIOD_MS = 2_000L

    // CEP emitter loop
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var emitJob: Job? = null
    private val EMIT_INTERVAL_MS = 1000L

    private lateinit var handlerThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private var isHandlerRunning = false
    private var deviceWorn: Boolean = false

    // Running flags for trackers
    @Volatile private var spo2Running: Boolean = false
    @Volatile private var ppgRunning: Boolean = false

    // Cached latest values
    @Volatile private var lastSkinTempC: Float? = null
    @Volatile private var lastSpo2Pct: Double? = null
    @Volatile private var lastPpgAmp: Float? = null
    @Volatile private var lastPpgQuality: Float? = null

    private var nextFireMs = 0L          // wall-clock target for the next run
    // One monotonic clock – the same one Handler uses
    private val nowUptime get() = SystemClock.uptimeMillis()

    // One function that always schedules the NEXT reading
    private fun scheduleNextReading() {
        if (!deviceWorn) return                     // skip if watch off-wrist
        val target = nowUptime + DISPATCH_INTERVAL_MS        // absolute uptime timestamp
        nextFireMs = target                         // purely for diagnostics
        backgroundHandler.postAtTime(::startSkinTracker, target)
//        writeToLogFileLocation(
//            logFile,
//            "Next reading scheduled for ${DISPATCH_INTERVAL_MS/1_000}s (at $target)"
//        )
    }

    private val logFile: File by lazy {
        File(filesDir, LOG_FILENAME).apply {
            if (!exists()) parentFile?.mkdirs()
        }
    }

    // ---- Strict measurement state machine for SpO2 → PPG ----
    private enum class MeasurementState { IDLE, SPO2_RUNNING, PPG_RUNNING }
    private val measureState = AtomicReference(MeasurementState.IDLE)
    private var spo2TimeoutRunnable: Runnable? = null
    private var ppgTimeoutRunnable: Runnable? = null
    @Volatile private var lastSpo2Ts: Long? = null
    @Volatile private var lastSpo2Status: Int? = null
    @Volatile private var lastPpgAmplitude: Double? = null
    @Volatile private var lastPpgTs: Long? = null

    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            skinTracker = healthService.getHealthTracker(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND)
            // Initialize on-demand SpO2 and PPG trackers as a hub
            try {
                spo2Tracker = healthService.getHealthTracker(HealthTrackerType.SPO2_ON_DEMAND)
                Log.i(TAG, "SpO2 tracker initialized (on-demand)")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to init SpO2 tracker", t)
            }
            // PPG capability detection with fallbacks
            initPpgCapability()
            startPpgContinuousIfSelected()
            // Emit inputs_ok flag for cold stress into CEP FeatureStore
            pushColdInputsOkFlag()
            Log.i(TAG, "Health SDK Connected")
            if (deviceWorn) {
                startSkinTracker()     // only kick off if worn
                scheduleNextReading()
                startEmitLoopIfNeeded()
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
            stopSpo2Tracker()
            stopPpgTracker()
            stopEmitLoop()
        }
    }

    private val trackerEventListener = object : HealthTracker.TrackerEventListener {

        override fun onDataReceived(dataPoints: List<DataPoint>) {
            // 1️⃣  Stop sensor immediately (but keep queue)
            stopSkinTracker(false)

            // 2️⃣  Collect temp data point  ──────────────────────────────
            dataPoints.firstOrNull()?.let { dp ->
                val status = dp.getValue(ValueKey.SkinTemperatureSet.STATUS)

                if (status >= 0) {
                    val objectTemp = dp.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE)
                    val ambientTemp = dp.getValue(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE)
                    lastSkinTempC = objectTemp
                    tempF = objectTemp.toFahrenheit()
                    Log.i(TAG, "SkinTempService skinTemp: object=${"%.1f".format(tempF)}°F (${objectTemp}°C) ambient=${ambientTemp}°C")

                    val logLine =
                        "SkinTempService status=$status objectF=${tempF}°F objectC=${objectTemp}°C " +
                                "ambientF=${ambientTemp.toFahrenheit()}°F ambientC=${ambientTemp}°C"
                    writeToLogFileLocation(logFile, logLine)
                } else {
                    tempF = -1f
                    skinTempLiveData.postValue("N/A")
                }

                CoroutineScope(Dispatchers.IO).launch {
                    sharedRepo.storeLatestSkinTemp(tempF)
                }
            }

            // Only attempt SpO2 when idle; startSpo2ThenPpgCycle() also applies rate-limit/backoff.
            if (deviceWorn && measureState.get() == MeasurementState.IDLE) {
                startSpo2ThenPpgCycle()
            }
            // 3️⃣  Schedule the next run *once*, with the helper
            scheduleNextReading()
        }

        override fun onFlushCompleted() {
            // No-op for on-demand service
        }

        fun Float.toFahrenheit(): Float = this * 9f/5f + 32f

        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(TAG, "SkinTempService HealthTracker.TrackerError: $error")
        }

    }

    private fun pausePpgContinuousForSpo2() {
        if (ppgTrackerType != HealthTrackerType.PPG_CONTINUOUS) return
        if (!ppgRunning) return
        if (ppgPausedForSpo2) return
        try {
            ppgTracker?.unsetEventListener()
            ppgRunning = false
            ppgPausedForSpo2 = true
            Log.i(TAG, "[PPG] Paused PPG_CONTINUOUS for SpO2")
        } catch (t: Throwable) {
            Log.e(TAG, "[PPG] Failed to pause PPG_CONTINUOUS for SpO2", t)
        }
    }

    private fun resumePpgContinuousAfterSpo2() {
        if (!ppgPausedForSpo2) return
        ppgPausedForSpo2 = false
        startPpgContinuousIfSelected()
        Log.i(TAG, "[PPG] Resumed PPG_CONTINUOUS after SpO2")
    }

    // Initialize PPG capability: prefer continuous if available, fall back to on-demand
    private fun initPpgCapability() {
        val ppgValueSet: Set<PpgType> = hashSetOf(PpgType.GREEN)
        val candidates = listOf(
            HealthTrackerType.PPG_CONTINUOUS,
            HealthTrackerType.PPG_ON_DEMAND
        )

        ppgTracker = null
        ppgTrackerType = null
        ppgAvailable = false

        for (type in candidates) {
            try {
                val tracker = healthService.getHealthTracker(type, ppgValueSet)
                ppgTracker = tracker
                ppgTrackerType = type
                ppgAvailable = true
                Log.i(TAG, "[PPG] Using tracker type=$type with PpgTypeSet=$ppgValueSet")
                break
            } catch (t: Throwable) {
                Log.w(TAG, "[PPG] Failed to create tracker for type=$type (will try next)", t)
            }
        }

        if (!ppgAvailable) {
            Log.e(TAG, "[PPG] No supported PPG tracker available. Cold stress detection cannot run.")
        }
    }

    private fun startPpgContinuousIfSelected() {
        val tracker = ppgTracker ?: return
        if (!ppgAvailable) return
        if (ppgTrackerType != HealthTrackerType.PPG_CONTINUOUS) return
        try {
            tracker.setEventListener(ppgEventListener)
            ppgRunning = true
            Log.i(TAG, "[PPG] Continuous listener started")
        } catch (t: Throwable) {
            Log.e(TAG, "[PPG] Failed to start continuous listener", t)
            ppgAvailable = false
            ppgTracker = null
            ppgTrackerType = null
        }
    }

    // Emit cold-stress inputs_ok flag to FeatureStore (numeric 1.0/0.0)
    private fun pushColdInputsOkFlag() {
        val ok = if (ppgAvailable) 1.0 else 0.0
        runCatching {
            CepProvider.getOrCreateEngine(applicationContext).ingest("features.cold.inputs_ok", ok)
            if (ok == 0.0) {
                Log.w(TAG, "[COLD] inputs_ok=false (PPG_UNAVAILABLE) — gating cold stress")
            } else {
                Log.i(TAG, "[COLD] inputs_ok=true")
            }
        }.onFailure { t ->
            Log.e(TAG, "[COLD] Failed to push inputs_ok flag", t)
        }
    }

    // ---- Strict SpO2 phase (only accept STATUS == 2, 40s timeout) ----
    private val spo2StrictListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            backgroundHandler.post {
                if (measureState.get() != MeasurementState.SPO2_RUNNING) return@post
                if (!::spo2Tracker.isInitialized) return@post
                try {
                    dataPoints.forEach { dp ->
                        val status: Int? = try { dp.getValue(ValueKey.SpO2Set.STATUS) } catch (_: Throwable) { null }
                        if (status == null) return@forEach
                        if (status == 2) {
                            val spo2Val: Int? = try { dp.getValue(ValueKey.SpO2Set.SPO2) } catch (_: Throwable) { null }
                            lastSpo2Status = status
                            lastSpo2Pct = spo2Val?.toDouble()
                            lastSpo2Ts = System.currentTimeMillis()
                            lastSpo2OutcomeWasTimeout = false
                            lastSpo2OutcomeWallMs = System.currentTimeMillis()
                            Log.i(TAG, "[SPO2] Completed status=$status spo2=${lastSpo2Pct ?: -1.0}")
                            stopSpo2Session("completed")
                            transitionToPpgPhase()
                            return@post
                        } else if (status == SPO2_STATUS_TIMEOUT) {
                            lastSpo2Status = status
                            lastSpo2Pct = null
                            lastSpo2Ts = System.currentTimeMillis()
                            lastSpo2OutcomeWasTimeout = true
                            lastSpo2OutcomeWallMs = System.currentTimeMillis()
                            Log.w(TAG, "[SPO2] TIMEOUT status=$status → stopping session early")
                            stopSpo2Session("status_timeout")
                            transitionToPpgPhase()
                            return@post
                        } else {
                            if (lastSpo2Status != status) {
                                Log.d(TAG, "[SPO2] Interim status=$status …")
                                lastSpo2Status = status
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "[SPO2] onDataReceived error", t)
                    lastSpo2Status = -1
                    stopSpo2Session("error")
                    transitionToPpgPhase()
                }
            }
        }
        override fun onFlushCompleted() { Log.d(TAG, "[SPO2] onFlushCompleted") }
        override fun onError(error: HealthTracker.TrackerError) {
            backgroundHandler.post {
                if (measureState.get() != MeasurementState.SPO2_RUNNING) return@post
                Log.e(TAG, "[SPO2] Error: $error")
                lastSpo2Status = -1
                stopSpo2Session("onError:$error")
                transitionToPpgPhase()
            }
        }
    }

    private fun startSpo2ThenPpgCycle() {
        // Rate-limit, timeout backoff, and stillness gate before transitioning to SPO2_RUNNING
        val nowWall = System.currentTimeMillis()
        val requiredGap = if (lastSpo2OutcomeWasTimeout) SPO2_BACKOFF_AFTER_TIMEOUT_MS else SPO2_MIN_GAP_MS
        val sinceLastAttempt = nowWall - lastSpo2AttemptWallMs
        if (sinceLastAttempt < requiredGap) {
            val reason = if (lastSpo2OutcomeWasTimeout) "timeout backoff" else "rate limit"
            Log.d(TAG, "[SPO2] Skipping ($reason). sinceLastAttempt=${sinceLastAttempt}ms")
            return
        }
        if (motionActive) {
            Log.d(TAG, "[SPO2] Skipping (motion active)")
            return
        }
        lastSpo2AttemptWallMs = nowWall
        // If backoff elapsed after a timeout, clear the timeout flag
        if (lastSpo2OutcomeWasTimeout && sinceLastAttempt >= requiredGap) {
            lastSpo2OutcomeWasTimeout = false
        }

        if (!measureState.compareAndSet(MeasurementState.IDLE, MeasurementState.SPO2_RUNNING)) {
            Log.d(TAG, "[MEAS] Cycle request ignored, state=${measureState.get()}")
            return
        }
        Log.i(TAG, "[MEAS] Cycle start → SPO2_RUNNING")
        backgroundHandler.post {
            // Pause continuous PPG to avoid optical contention
            pausePpgContinuousForSpo2()
            if (!::spo2Tracker.isInitialized) {
                Log.w(TAG, "[SPO2] Tracker unavailable, skipping to PPG")
                // Ensure PPG continuous is resumed if we paused
                resumePpgContinuousAfterSpo2()
                measureState.set(MeasurementState.PPG_RUNNING)
                startPpgPhase()
                return@post
            }
            try {
                spo2Tracker.setEventListener(spo2StrictListener)
                Log.i(TAG, "[SPO2] Started (timeout ${SPO2_TIMEOUT_MS/1000}s)")
            } catch (ex: Throwable) {
                Log.e(TAG, "[SPO2] start failed", ex)
                // Ensure PPG continuous is resumed if we paused
                resumePpgContinuousAfterSpo2()
                measureState.set(MeasurementState.PPG_RUNNING)
                startPpgPhase()
                return@post
            }
            spo2TimeoutRunnable = Runnable {
                if (measureState.get() == MeasurementState.SPO2_RUNNING) {
                    Log.w(TAG, "[SPO2] Timeout reached → stopping session, moving to PPG")
                    lastSpo2Status = SPO2_STATUS_TIMEOUT
                    lastSpo2Pct = null
                    lastSpo2OutcomeWasTimeout = true
                    lastSpo2OutcomeWallMs = System.currentTimeMillis()
                    stopSpo2Session("timeout_runnable")
                    measureState.set(MeasurementState.PPG_RUNNING)
                    startPpgPhase()
                }
            }
            backgroundHandler.postDelayed(spo2TimeoutRunnable!!, SPO2_TIMEOUT_MS)
        }
    }

    private fun transitionToPpgPhase() {
        if (!measureState.compareAndSet(MeasurementState.SPO2_RUNNING, MeasurementState.PPG_RUNNING)) {
            Log.d(TAG, "[MEAS] Unexpected state ${measureState.get()} when transitioning to PPG")
            measureState.set(MeasurementState.PPG_RUNNING)
        }
        startPpgPhase()
    }

    private fun spo2TimeoutCleanup() {
        spo2TimeoutRunnable?.let { backgroundHandler.removeCallbacks(it) }
        spo2TimeoutRunnable = null
    }
    private fun stopSpo2Session(reason: String) {
        spo2TimeoutCleanup()
        if (::spo2Tracker.isInitialized) {
            try {
                spo2Tracker.unsetEventListener()
            } catch (t: Throwable) {
                Log.e(TAG, "[SPO2] unset error", t)
            }
        }
        resumePpgContinuousAfterSpo2()
        lastSpo2OutcomeWallMs = System.currentTimeMillis()
        Log.i(TAG, "[SPO2] Session end ($reason)")
    }
    private fun stopSpo2Tracker() {
        if (!::spo2Tracker.isInitialized) return
        backgroundHandler.post {
            try {
                spo2Tracker.unsetEventListener()
                spo2Running = false
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to stop SpO2 tracker", t)
            }
        }
    }

    // ---- PPG burst (green) ----
    private var ppgStartUptimeMs: Long = 0L
    private var ppgBurstDurationMs: Long = 0L
    private val ppgSamples = mutableListOf<Float>()

    private val ppgEventListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            val now = SystemClock.uptimeMillis()
            if (ppgStartUptimeMs == 0L) ppgStartUptimeMs = now
            try {
                dataPoints.forEach { dp ->
                    // Capture raw PPG sample (PPG_GREEN is Int)
                    try {
                        val raw: Int = dp.getValue(ValueKey.PpgSet.PPG_GREEN)
                        // Maintain rolling 20s window
                        appendPpgSampleAndPrune(raw)
                        // For on-demand burst, also store in burst buffer
                        ppgSamples.add(raw.toFloat())
                    } catch (_: Throwable) {
                        // ignore bad datapoint
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed reading PPG datapoints", t)
            }

            // Throttled rolling amplitude compute (useful in continuous mode)
            maybeComputePpgThrottled()

            // Stop-after-duration is ONLY for on-demand (burst) mode.
            if (ppgTrackerType == HealthTrackerType.PPG_ON_DEMAND) {
                if (now - ppgStartUptimeMs >= ppgBurstDurationMs) {
                    completePpgPhase("window end")
                }
            }
        }
        override fun onFlushCompleted() {}
        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(TAG, "PPG TrackerError: $error")
            completePpgPhase("error")
        }
    }

    private fun startPpgPhase() {
        backgroundHandler.post {
            val t = ppgTracker
            if (!ppgAvailable || t == null) {
                Log.e(TAG, "[PPG] Unavailable — cannot proceed; cold stress disabled")
                pushColdInputsOkFlag()
                finishMeasurementCycle()
                return@post
            }
            // If tracker type is continuous, we assume listener already running — do not touch burst timers, just wait and compute
            if (ppgTrackerType == HealthTrackerType.PPG_CONTINUOUS) {
                Log.i(TAG, "[PPG] Continuous mode — waiting 20s then computing amplitude (do not stop listener)")
                ppgTimeoutRunnable = Runnable {
                    if (measureState.get() == MeasurementState.PPG_RUNNING) {
                        computeAndCachePpgAmplitudeIfPossible("continuous phase end")
                        ppgTimeoutCleanup()
                        finishMeasurementCycle()
                    }
                }
                backgroundHandler.postDelayed(ppgTimeoutRunnable!!, 20_000L)
                return@post
            } else {
                // ON_DEMAND burst path — reset timers/buffers
                ppgStartUptimeMs = 0L
                ppgBurstDurationMs = 20_000L
                ppgSamples.clear()
                // On-demand: start 20s burst
                try {
                    t.setEventListener(ppgEventListener)
                    ppgRunning = true
                    Log.i(TAG, "[PPG] Started (20s window)")
                } catch (ex: Throwable) {
                    Log.e(TAG, "[PPG] start failed", ex)
                    finishMeasurementCycle()
                    return@post
                }
                // Ensure completion if no data callbacks arrive
                ppgTimeoutRunnable = Runnable {
                    if (measureState.get() == MeasurementState.PPG_RUNNING) {
                        completePpgPhase("timeout")
                    }
                }
                backgroundHandler.postDelayed(ppgTimeoutRunnable!!, 20_000L)
            }
        }
    }

    private fun stopPpgTracker() {
        if (ppgTracker == null) return
        backgroundHandler.post {
            try {
                ppgTracker?.unsetEventListener()
                ppgRunning = false
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to stop PPG tracker", t)
            }
        }
    }

    private fun ppgTimeoutCleanup() {
        ppgTimeoutRunnable?.let { backgroundHandler.removeCallbacks(it) }
        ppgTimeoutRunnable = null
    }

    // Rolling window helpers
    private fun appendPpgSampleAndPrune(rawInt: Int) {
        val now = SystemClock.uptimeMillis()
        val v = rawInt.toFloat()
        ppgWindow.addLast(TimedSample(now, v))
        val cutoff = now - PPG_WINDOW_MS
        while (ppgWindow.isNotEmpty() && ppgWindow.first().tUptimeMs < cutoff) {
            ppgWindow.removeFirst()
        }
        lastPpgSampleCount = ppgWindow.size
    }
    private fun computeAndCachePpgAmplitudeIfPossible(reason: String) {
        if (ppgWindow.isEmpty()) {
            lastPpgAmplitude = null
            lastPpgTs = System.currentTimeMillis()
            Log.w(TAG, "[PPG] No samples in window; amplitude=null ($reason)")
            return
        }
        // Robust amplitude using 5th–95th percentile
        val values = ppgWindow.map { it.v }
        val amp = computeAmplitudeP5P95(values)
        lastPpgAmplitude = amp
        lastPpgTs = System.currentTimeMillis()
        // reuse quality heuristic
        lastPpgQuality = (amp.toFloat() / (amp.toFloat() + 50f)).coerceIn(0f, 1f)
        Log.i(TAG, "[PPG] amplitude=$amp samples=${ppgWindow.size} ($reason)")
    }
    private fun maybeComputePpgThrottled() {
        val now = SystemClock.uptimeMillis()
        if (now - lastPpgComputeUptimeMs >= PPG_COMPUTE_PERIOD_MS) {
            lastPpgComputeUptimeMs = now
            computeAndCachePpgAmplitudeIfPossible("throttled")
        }
    }

    private fun completePpgPhase(reason: String) {
        // Only stop the tracker for on-demand mode; keep continuous listener running
        if (ppgTrackerType != HealthTrackerType.PPG_CONTINUOUS) {
            stopPpgTracker()
        }
        ppgTimeoutCleanup()
        // Compute amplitude from appropriate buffer
        if (ppgTrackerType == HealthTrackerType.PPG_CONTINUOUS) {
            computeAndCachePpgAmplitudeIfPossible(reason)
        } else {
            if (ppgSamples.isNotEmpty()) {
                val min = ppgSamples.minOrNull() ?: 0f
                val max = ppgSamples.maxOrNull() ?: 0f
                val amp = (max - min).toDouble()
                lastPpgAmplitude = if (amp.isFinite()) amp else null
                lastPpgTs = System.currentTimeMillis()
                lastPpgQuality = (amp.toFloat() / (amp.toFloat() + 50f)).coerceIn(0f, 1f)
                Log.i(TAG, "[PPG] Completed ($reason) samples=${ppgSamples.size} amp=${lastPpgAmplitude ?: -1.0} q=${lastPpgQuality}")
            } else {
                lastPpgAmplitude = null
                lastPpgTs = System.currentTimeMillis()
                lastPpgQuality = 0f
                Log.w(TAG, "[PPG] No samples collected ($reason)")
            }
        }
        finishMeasurementCycle()
        ppgSamples.clear()
        ppgStartUptimeMs = 0L
    }

    private fun finishMeasurementCycle() {
        measureState.set(MeasurementState.IDLE)
        Log.i(TAG, "[MEAS] Cycle end → IDLE")
    }
    private fun computeAmplitudeP5P95(samples: List<Float>): Double {
        if (samples.isEmpty()) return 0.0
        val sorted = samples.sorted()
        fun pct(p: Double): Double {
            val idx = ((p / 100.0) * (sorted.size - 1)).coerceIn(0.0, (sorted.size - 1).toDouble())
            val i = idx.toInt()
            val frac = idx - i
            val a = sorted[i].toDouble()
            val b = sorted[(i + 1).coerceAtMost(sorted.size - 1)].toDouble()
            return a + (b - a) * frac
        }
        val p5 = pct(5.0)
        val p95 = pct(95.0)
        return (p95 - p5).coerceAtLeast(0.0)
    }

    private fun computeQuality(amplitude: Float): Float {
        // Simple heuristic: normalize amplitude to [0..1] with soft cap
        val norm = amplitude / 1.0f
        return norm.coerceIn(0f, 1f)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SkinTempService onCreate()")
        isRunning.set(true)
        startForegroundServiceCompat(NOTIF_ID, importance = android.app.NotificationManager.IMPORTANCE_LOW)

        CoroutineScope(Dispatchers.IO).launch{
            sharedRepo.reportingUpdateIntervalSecs.collect { secs ->
                val newIntervalMs = (secs * 1_000L).coerceAtLeast(MIN_SKIN_TEMP_INTERVAL_MS)
                if (newIntervalMs != DISPATCH_INTERVAL_MS) {
//                    writeToLogFileLocation(logFile, "Dispatch Interval Changed from ${DISPATCH_INTERVAL_MS/1000}s to ${newIntervalMs/1000}s"); Log.i(TAG, "Reporting interval updated -> $secs s")
                    DISPATCH_INTERVAL_MS = newIntervalMs

                    if (deviceWorn) {
                        backgroundHandler.removeCallbacksAndMessages(null)
                        scheduleNextReading()
                        startSkinTracker()
                    }
                }
            }
        }

        // Start a dedicated thread for handling callbacks
        handlerThread = HandlerThread("SkinTempThread").apply { start() }
        backgroundHandler = Handler(handlerThread.looper)

        // Connect to the Samsung Health Tracking Service
        healthService = HealthTrackingService(connectionListener, applicationContext)
        healthService.connectService()

        // Listen for wrist wear state changes
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
        // Stop emitter loop
        try {
            emitJob?.cancel()
            serviceScope.cancel()
        } catch (_: Throwable) { }
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }

        // Ensure listener is removed and thread stopped
        stopSkinTracker(true)
        stopSpo2Tracker()
        stopPpgTracker()
        spo2TimeoutCleanup()
        ppgTimeoutCleanup()
        measureState.set(MeasurementState.IDLE)
        // Push cold-stress inputs flag (PPG will be unavailable after destroy)
        ppgAvailable = false
        pushColdInputsOkFlag()
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
        backgroundHandler.removeCallbacksAndMessages(null)

        if (nowWorn) {
            deviceWorn = true
            startSkinTracker()    // immediate snapshot
            scheduleNextReading()
            startEmitLoopIfNeeded()
            writeToLogFileLocation(logFile, "deviceWorn=true.")
        } else {
            deviceWorn = false
            stopSkinTracker(true)     // shut it down
            stopSpo2Tracker()
            stopPpgTracker()
            stopEmitLoop()
            spo2TimeoutCleanup()
            ppgTimeoutCleanup()
            measureState.set(MeasurementState.IDLE)
            writeToLogFileLocation(logFile, "deviceWorn=false.")
        }
    }

    private fun startSkinTracker() {
        if (!::skinTracker.isInitialized || isHandlerRunning) return
        isHandlerRunning = true
        backgroundHandler.post { skinTracker.setEventListener(trackerEventListener) }
    }

    private fun stopSkinTracker(clearQueue: Boolean = false) {
        if (!isHandlerRunning) return
        isHandlerRunning = false
        backgroundHandler.post {
            skinTracker.unsetEventListener()
            if (clearQueue) backgroundHandler.removeCallbacksAndMessages(null)
        }
    }

    // CEP emission loop
    private fun startEmitLoopIfNeeded() {
        if (emitJob?.isActive == true) return
        emitJob = serviceScope.launch {
            while (isActive) {
                emitLatestCep()
                delay(EMIT_INTERVAL_MS)
            }
        }
    }

    private fun stopEmitLoop() {
        try {
            emitJob?.cancel()
            emitJob = null
        } catch (_: Throwable) { }
    }

    private suspend fun emitLatestCep() {
        try {
            lastSpo2Pct?.let { pct ->
                CepBus.emit(CepEvent(topic = Topics.SPO2, payload = Spo2Payload(pct = pct)))
            }
            lastPpgAmplitude?.let { amp ->
                CepBus.emit(
                    CepEvent(
                        topic = Topics.PPG,
                        payload = PpgPayload(amplitude = amp.toFloat(), quality = (lastPpgQuality ?: 0f), wavelength = "green")
                    )
                )
            }
            lastSkinTempC?.let { c ->
                 CepBus.emit(
                     CepEvent(
                         topic = Topics.SKIN_TEMP,
                         payload = SkinTempPayload(
                             c_peripheral = listOf(c.toDouble()),
                             labels = listOf("hand")
                         )
                     )
                 )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "CEP emit loop error", t)
        }
    }
}