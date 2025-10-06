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
import com.aitech.weartak_civ.DEFAULT_REPORTING_INTERVAL
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.Utils.writeToLogFileLocation
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.IsDeviceWornEvent
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.logFile
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
        var DISPATCH_INTERVAL_MS = DEFAULT_REPORTING_INTERVAL * 1_000L
        private const val MIN_SKIN_TEMP_INTERVAL_MS = 10_000L
        val LOG_FILENAME = "/sdcard/Download/skin_temp.log"
    }

    private val sharedRepo: DataRepository
        get() = MainActivity.dataRepository

    private lateinit var healthService: HealthTrackingService
    private lateinit var skinTracker: HealthTracker

    private lateinit var handlerThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private var isHandlerRunning = false
    private var deviceWorn: Boolean = false

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

    //private val logFile: File by lazy { File(LOG_FILENAME) }

    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            skinTracker = healthService.getHealthTracker(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND)
            Log.i(TAG, "Health SDK Connected")
            if (deviceWorn) {
                startSkinTracker()     // only kick off if worn
                scheduleNextReading()  //
            }
        }

        override fun onConnectionFailed(e: HealthTrackerException) {
            Log.e(TAG, "HealthPlatform connection failed", e)
            if (e.hasResolution()) {
                try { e.resolve(null) } catch (ex: Exception) { Log.e(TAG, "Resolution failed", ex) }
            }
        }

        override fun onConnectionEnded() {
            Log.w(TAG, "HealthPlatform DISCONNECTED")
            // Clean up when disconnected
            stopSkinTracker(true)
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
                    Log.i(TAG, "Skin Temp: ${"%.1f".format(tempF)}°F; Ambient Temp: ${ambientTemp.toFahrenheit()}")

                    //val logLine = "SkinTemp status=$status objectF=${tempF}°F objectC=${objectTemp}°C " +
                    //            "ambientF=${ambientTemp.toFahrenheit()}°F ambientC=${ambientTemp}°C"
                    //writeToLogFileLocation(logFile, logLine)
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
            Log.e(TAG, "Tracker error: $error")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SkinTempService onCreate")

        CoroutineScope(Dispatchers.IO).launch{
            sharedRepo.reportingUpdateIntervalSecs.collect { secs ->
                val newIntervalMs = (secs * 1_000L).coerceAtLeast(MIN_SKIN_TEMP_INTERVAL_MS)
                if (newIntervalMs != DISPATCH_INTERVAL_MS) {
                    //writeToLogFileLocation(logFile, "Dispatch Interval Changed from ${DISPATCH_INTERVAL_MS/1000}s to ${newIntervalMs/1000}s"); Log.i(TAG, "Reporting interval updated -> $secs s")
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

        // Promote to foreground to avoid being killed
        startForegroundServiceCompat(
            NOTIF_ID,
            importance = android.app.NotificationManager.IMPORTANCE_LOW
        )

        // Listen for wrist wear state changes
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand, isRunning=${isRunning.get()}")
        //writeToLogFileLocation(logFile, "---------------New Session of SkinTempService ---------------\n")
        if (!isRunning.getAndSet(true)) {
            deviceWorn = sharedRepo.latestIsDeviceWorn.value ?: false

            if (deviceWorn && ::skinTracker.isInitialized) {
                startSkinTracker()
                scheduleNextReading()
            }

        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")

        isRunning.set(false)
        deviceWorn = false
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }

        // Ensure listener is removed and thread stopped
        stopSkinTracker(true)

        healthService.disconnectService()
        handlerThread.quitSafely()

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
            //writeToLogFileLocation(logFile, "deviceWorn=true.")
        } else {
            deviceWorn = false
            CoroutineScope(Dispatchers.IO).launch {
                sharedRepo.storeLatestSkinTemp(-1.00F)
            }
            stopSkinTracker(true)     // shut it down
            //writeToLogFileLocation(logFile, "deviceWorn=false.")
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

}
