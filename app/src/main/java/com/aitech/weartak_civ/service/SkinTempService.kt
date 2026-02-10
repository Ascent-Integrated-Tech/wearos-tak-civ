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
import android.os.PowerManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.lifecycle.MutableLiveData
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.Utils.writeToLogFileLocation
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.IsDeviceWornEvent
import com.aitech.weartak_civ.presentation.MainActivity
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.ValueKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private lateinit var hrTracker: HealthTracker

    private lateinit var handlerThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private var isHandlerRunning = false
    private var deviceWorn: Boolean = false
    private var isHrRunning = false

    private var nextFireMs = 0L          // wall-clock target for the next run
    // One monotonic clock – the same one Handler uses
    private val nowUptime get() = SystemClock.uptimeMillis()

    // Screen state + flush policy for HR
    private var screenOffFlushEnabled = false
    private val SCREEN_OFF_FLUSH_INTERVAL_MS = 2 * 60 * 1_000L
    private val FLUSH_START_DELAY_MS = 5_000L
    private val powerManager by lazy { getSystemService(POWER_SERVICE) as PowerManager }
    private val isInteractiveNow get() = powerManager.isInteractive

    // Rolling snapshot for HR/IBI
    @Volatile private var lastNonZeroHr = 0
    @Volatile private var lastNonZeroIbi = 0
    @Volatile private var lastCallbackElapsed = 0L
    @Volatile private var lastValidHrElapsed = 0L

    // HRV / RMSSD buffer + params
    private val nnBuffer = ArrayDeque<Int>(128)
    private val MIN_NN_FOR_RMSSD = 10
    private val MAX_NN_BUFFER = 60
    private val NN_MIN_MS = 300
    private val NN_MAX_MS = 2000
    @Volatile private var lastNonZeroRmssdMs: Double = 0.0

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
            hrTracker = healthService.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
            Log.i(TAG, "Health SDK Connected: skinTracker + hrTracker initialized")
            if (deviceWorn) {
                // HR: start continuous based on current screen state
                startHrTracker()
                if (!isInteractiveNow) {
                    enableScreenOffFlushLoop()
                    flushHr("initial_screen_off")
                } else {
                    disableScreenOffFlushLoop()
                }
                // SkinTemp on-demand snapshot + schedule next run
                startSkinTracker()
                scheduleNextReading()
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

    private val hrEventListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            lastCallbackElapsed = SystemClock.elapsedRealtime()
            var batchMostRecentHr = 0
            var batchMostRecentHrStatus = Int.MIN_VALUE
            var batchMostRecentIbi = 0

            var firstIbiList: List<Int>? = null
            var firstIbiStatusList: List<Int>? = null

            for (i in dataPoints.indices) {
                val dp = dataPoints[i]
                try {
                    val status = dp.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS)
                    val hr = dp.getValue(ValueKey.HeartRateSet.HEART_RATE)
                    batchMostRecentHr = hr
                    batchMostRecentHrStatus = status
                    if (status == 1 && hr > 0) {
                        lastValidHrElapsed = SystemClock.elapsedRealtime()
                        lastNonZeroHr = hr
                    }
                    if (i == 0) {
                        val ibiList = dp.getValue(ValueKey.HeartRateSet.IBI_LIST)
                        val ibiStatusList = dp.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST)
                        firstIbiList = ibiList
                        firstIbiStatusList = ibiStatusList
                        if (ibiList != null && ibiList.isNotEmpty()) {
                            val lastIbi = ibiList.last()
                            batchMostRecentIbi = lastIbi
                            if (lastIbi > 0) lastNonZeroIbi = lastIbi
                            Log.i(TAG, "[0] IBI_LIST(ms)=$ibiList")
                            Log.i(TAG, "[0] IBI_STATUS_LIST=$ibiStatusList")
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "HR parse error", t)
                }
            }
            val effectiveHr = if (batchMostRecentHr > 0 && batchMostRecentHrStatus == 1) batchMostRecentHr else lastNonZeroHr
            val effectiveIbi = if (batchMostRecentIbi > 0) batchMostRecentIbi else lastNonZeroIbi
            val reason = if (isInteractiveNow) "interactive" else "flush/off"
            Log.i(
                TAG,
                "HRIBI-SNAPSHOT: HR=$effectiveHr (raw=$batchMostRecentHr status=$batchMostRecentHrStatus lastNonZero=$lastNonZeroHr) " +
                    "IBI=$effectiveIbi (raw=$batchMostRecentIbi lastNonZero=$lastNonZeroIbi) reason=$reason"
            )

            // Update NN buffer with newly received (valid) intervals and compute RMSSD
            addValidNnIntervals(firstIbiList, firstIbiStatusList)
            val rmssd = computeRmssdMs()
            if (rmssd != null && rmssd > 0.0 && !rmssd.isNaN()) {
                lastNonZeroRmssdMs = rmssd
            }
            val effectiveRmssd = if (rmssd != null && rmssd > 0.0 && !rmssd.isNaN()) rmssd else lastNonZeroRmssdMs
            Log.i(TAG, "HRV-RMSSD: rmssdMs=${"%.1f".format(effectiveRmssd)} nnCount=${nnBuffer.size} source=$reason")
        }

        override fun onFlushCompleted() {
            Log.i(TAG, "HR flush completed t=${SystemClock.elapsedRealtime()}")
        }

        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(TAG, "HR tracker error: $error")
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context, intent: Intent) {
            val interactive = isInteractiveNow
            Log.w(TAG, "SCREEN EVENT: ${intent.action} | interactive=$interactive | deviceWorn=$deviceWorn | hrRunning=$isHrRunning")
            if (!deviceWorn) return
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    enableScreenOffFlushLoop()
                    flushHr("screen_off_event")
                }
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    disableScreenOffFlushLoop()
                }
            }
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
                    sharedRepo.storeLatestSkinTemp(tempF)
                }
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
        // Screen on/off receiver
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, f)
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
        try { unregisterReceiver(screenReceiver) } catch (_: Throwable) {}

        // Ensure listener is removed and thread stopped
        stopSkinTracker(true)
        stopHrTracker(true)
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
            // HR continuous starts and follow screen policy
            startHrTracker()
            if (!isInteractiveNow) {
                enableScreenOffFlushLoop()
                flushHr("worn_event_screen_off")
            } else {
                disableScreenOffFlushLoop()
            }
            // Skin temp snapshot and schedule
            startSkinTracker()
            scheduleNextReading()
            writeToLogFileLocation(logFile, "deviceWorn=true.")
        } else {
            deviceWorn = false
            stopHrTracker(true)
            stopSkinTracker(true)     // shut it down
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

    private fun startHrTracker() {
        if (!::hrTracker.isInitialized || isHrRunning) return
        isHrRunning = true
        backgroundHandler.post { hrTracker.setEventListener(hrEventListener) }
        Log.i(TAG, "HR tracker started (HEART_RATE_CONTINUOUS)")
    }

    private fun stopHrTracker(clearQueue: Boolean = false) {
        if (!isHrRunning) return
        isHrRunning = false
        disableScreenOffFlushLoop()
        backgroundHandler.post {
            try { hrTracker.unsetEventListener() } catch (_: Throwable) {}
            if (clearQueue) backgroundHandler.removeCallbacksAndMessages(null)
        }
        Log.i(TAG, "HR tracker stopped")
    }

    private val hrFlushRunnable = object : Runnable {
        override fun run() {
            if (!screenOffFlushEnabled || !deviceWorn || !isHrRunning) return
            flushHr("screen_off_timer")
            backgroundHandler.postDelayed(this, SCREEN_OFF_FLUSH_INTERVAL_MS)
        }
    }

    private fun addValidNnIntervals(ibiList: List<Int>?, ibiStatusList: List<Int>?) {
        if (ibiList.isNullOrEmpty()) return
        val hasStatus = !ibiStatusList.isNullOrEmpty() && ibiStatusList.size == ibiList.size
        for (i in ibiList.indices) {
            val ibi = ibiList[i]
            val statusOk = if (hasStatus) (ibiStatusList!![i] == 0) else true
            if (!statusOk) continue
            if (ibi < NN_MIN_MS || ibi > NN_MAX_MS) continue
            nnBuffer.addLast(ibi)
            while (nnBuffer.size > MAX_NN_BUFFER) nnBuffer.removeFirst()
        }
    }

    private fun computeRmssdMs(): Double? {
        if (nnBuffer.size < MIN_NN_FOR_RMSSD) return null
        var sumSq = 0.0
        var n = 0
        var prev = nnBuffer.first()
        for (i in 1 until nnBuffer.size) {
            val cur = nnBuffer.elementAt(i)
            val diff = (cur - prev).toDouble()
            sumSq += diff * diff
            n++
            prev = cur
        }
        if (n <= 0) return null
        return kotlin.math.sqrt(sumSq / n)
    }

    private fun enableScreenOffFlushLoop() {
        if (screenOffFlushEnabled) return
        screenOffFlushEnabled = true
        Log.w(TAG, "SCREEN-OFF FLUSH: ENABLED intervalMs=$SCREEN_OFF_FLUSH_INTERVAL_MS")
        backgroundHandler.removeCallbacks(hrFlushRunnable)
        backgroundHandler.postDelayed(hrFlushRunnable, FLUSH_START_DELAY_MS)
    }

    private fun disableScreenOffFlushLoop() {
        if (!screenOffFlushEnabled) return
        screenOffFlushEnabled = false
        Log.w(TAG, "SCREEN-OFF FLUSH: DISABLED")
        backgroundHandler.removeCallbacks(hrFlushRunnable)
    }

    private fun flushHr(reason: String) {
        if (!::hrTracker.isInitialized) return
        Log.i(TAG, "FLUSH-REQUEST: reason=$reason t=${SystemClock.elapsedRealtime()}")
        try { hrTracker.flush() } catch (t: Throwable) { Log.e(TAG, "flushHr failed reason=$reason", t) }
    }

}