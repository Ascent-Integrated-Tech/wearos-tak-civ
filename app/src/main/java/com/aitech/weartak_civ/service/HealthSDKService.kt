package com.aitech.weartak_civ.service

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.service.healthsdk.HealthSdkError
import com.aitech.weartak_civ.service.healthsdk.HealthStreamBus
import com.aitech.weartak_civ.service.healthsdk.HealthStreamEvent
import com.aitech.weartak_civ.service.healthsdk.HeartRateIbiTracker
import com.aitech.weartak_civ.service.healthsdk.computeRmssdMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class HealthSDKService : Service() {

    companion object {
        private const val TAG = "WearTAK-HealthSDKService"
        private const val NOTIF_ID = 106
        val isRunning = AtomicBoolean(false)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val bus = HealthStreamBus()

    @Volatile
    private var hrTracker: HeartRateIbiTracker? = null

    override fun onCreate() {
        super.onCreate()
        Log.i("HealthSDKService", "onCreate() PID=${android.os.Process.myPid()}")
        isRunning.set(true)

        // Foreground first (health FGS type must be in manifest)
        startForegroundServiceCompat(
            NOTIF_ID,
            importance = android.app.NotificationManager.IMPORTANCE_LOW
        )
        Log.i("HealthSDKService", "startForeground done")

        // 1) Create tracker and start measurement (assume worn)
        Log.i("HealthSDKService", "Initializing HeartRateIbiTracker + connecting + requestStart")
        hrTracker = HeartRateIbiTracker(applicationContext, HealthStreamBus.shared).also {
            it.registerScreenReceiver()
            it.connect()
            it.requestStart()
        }

        // 2) Collect events
        scope.launch {
            bus.events.collectLatest { event ->
                when (event) {
                    is HealthStreamEvent.HeartRateSample -> {
                        // TODO: forward into CEP / emit event / update cache
                        Log.i(TAG, "HR bpm=${event.bpm} status=${event.status}")
                    }
                    is HealthStreamEvent.IbiBatch -> {
                        val rmssd = computeRmssdMs(event.ibiMs)
                        Log.i(TAG, "IBI count=${event.ibiMs.size} rmssd_ms=$rmssd ibi=${event.ibiMs}")
                        // TODO: forward IBI + RMSSD into HRV module / CEP
                    }
                    is HealthStreamEvent.Error -> {
                        Log.e(TAG, "HealthStream error=${event.error}")
                        handleSdkError(event.error)
                    }

                    else -> {
                        Log.d(TAG, "NOTHING")
                    }
                }
            }
        }

        // no-op: tracker handles connect/start
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("HealthSDKService", "onStartCommand(flags=$flags startId=$startId) intent=$intent")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w("HealthSDKService", "onDestroy()")
        isRunning.set(false)
        runCatching {
            hrTracker?.requestStop()
            hrTracker?.unregisterScreenReceiver()
            hrTracker?.disconnect()
        }
        scope.cancel()
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopHrTracker() {
        runCatching { hrTracker?.requestStop() }
        hrTracker = null
    }

    private fun handleSdkError(err: HealthSdkError) {
        when (err) {
            HealthSdkError.ResolutionRequired -> {
                // IMPORTANT: service cannot call e.resolve(activity)
                // Show a persistent notification instructing user to open the app / grant consent.
                Log.e(TAG, "Resolution required: user must open app to grant consent / install Health Platform / update.")
                // TODO: showNotification("Action required", "Open WearTAK to enable Samsung Health permissions/consent.")
            }
            HealthSdkError.PermissionDenied -> {
                Log.e(TAG, "Permission denied: BODY_SENSORS / READ_HEART_RATE missing.")
                // TODO: showNotification("Permission required", "Grant heart rate permission in Settings.")
            }
            HealthSdkError.SdkPolicyError -> {
                Log.e(TAG, "SDK policy error: blocked by platform policy.")
                // TODO: showNotification("Health policy blocked", "Samsung Health policy prevents this stream.")
            }
            is HealthSdkError.Failed -> {
                Log.e(TAG, "SDK failed: ${err.message}")
            }
        }
    }
}
