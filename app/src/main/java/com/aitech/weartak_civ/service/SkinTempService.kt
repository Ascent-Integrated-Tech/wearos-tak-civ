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
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.Topics
import com.aitech.weartak_civ.cep.model.payloads.PpgPayload
import com.aitech.weartak_civ.cep.model.payloads.SkinTempPayload
import com.aitech.weartak_civ.cep.model.payloads.Spo2Payload
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.ValueKey
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
    private lateinit var ppgTracker: HealthTracker

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
            try {
                // Prefer green channel on-demand if available
                ppgTracker = healthService.getHealthTracker(HealthTrackerType.PPG_ON_DEMAND)
                Log.i(TAG, "PPG tracker initialized (on-demand)")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to init PPG tracker", t)
            }
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

            // Trigger SpO2 and PPG bursts only if worn
            if (deviceWorn) {
                requestSpo2Once()
                requestPpgBurst(5_000L)
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

    // ---- SpO2 (one-shot) ----
    private val spo2EventListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            stopSpo2Tracker()
            val dp = dataPoints.firstOrNull() ?: return
            try {
                val status = dp.getValue(ValueKey.SpO2Set.STATUS)
                val spo2 = dp.getValue(ValueKey.SpO2Set.SPO2)
                lastSpo2Pct = spo2.toDouble()
                Log.i(TAG, "SkinTempService SpO2 (cached): status=$status spo2=$spo2 %")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to parse SpO2 datapoint", t)
            }
        }
        override fun onFlushCompleted() {}
        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(TAG, "SpO2 TrackerError: $error")
        }
    }

    private fun requestSpo2Once() {
        if (!::spo2Tracker.isInitialized) return
        if (spo2Running) return
        backgroundHandler.post {
            try {
                spo2Tracker.setEventListener(spo2EventListener)
                spo2Running = true
                Log.d(TAG, "SpO2 one-shot requested")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start SpO2 one-shot", t)
            }
        }
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
                    // Capture raw PPG sample for on-demand PPG tracker
                    val sample = dp.getValue(ValueKey.PpgSet.PPG_GREEN) as Float
                    ppgSamples.add(sample)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed reading PPG datapoints", t)
            }

            // Stop after duration
            if (now - ppgStartUptimeMs >= ppgBurstDurationMs) {
                stopPpgTracker()
                if (ppgSamples.size >= 5) {
                    val amp = computeAmplitudeP5P95(ppgSamples).toFloat()
                    val quality = computeQuality(amp)
                    lastPpgAmp = amp
                    lastPpgQuality = quality
                    Log.i(TAG, "SkinTempService PPG burst (cached): samples=${ppgSamples.size} amp=$amp q=$quality")
                } else {
                    Log.w(TAG, "SkinTempService PPG burst: insufficient samples (${ppgSamples.size})")
                }
                ppgSamples.clear()
                ppgStartUptimeMs = 0L
            }
        }
        override fun onFlushCompleted() {}
        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(TAG, "PPG TrackerError: $error")
        }
    }

    private fun requestPpgBurst(durationMs: Long) {
        if (!::ppgTracker.isInitialized) return
        if (ppgRunning) return
        ppgStartUptimeMs = 0L
        ppgBurstDurationMs = durationMs
        ppgSamples.clear()
        backgroundHandler.post {
            try {
                ppgTracker.setEventListener(ppgEventListener)
                ppgRunning = true
                Log.d(TAG, "PPG burst requested: ${durationMs}ms")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start PPG burst", t)
            }
        }
    }

    private fun stopPpgTracker() {
        if (!::ppgTracker.isInitialized) return
        backgroundHandler.post {
            try {
                ppgTracker.unsetEventListener()
                ppgRunning = false
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to stop PPG tracker", t)
            }
        }
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
            lastPpgAmp?.let { amp ->
                CepBus.emit(
                    CepEvent(
                        topic = Topics.PPG,
                        payload = PpgPayload(amplitude = amp, quality = (lastPpgQuality ?: 0f), wavelength = "green")
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