package com.aitech.weartak_civ.service

import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.data.DataRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicBoolean

@AndroidEntryPoint
class ServicesManager : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @Inject lateinit var dr: DataRepository

    companion object {
        private val TAG = "WearTAK-${ServicesManager::class.java.simpleName}"
        private const val NOTIFICATION_ID = 111
        val isRunning = AtomicBoolean(false)
    }

    override fun onCreate() {
        super.onCreate()
        // Make this a foreground service so it survives low-memory kills
        startForegroundServiceCompat(NOTIFICATION_ID, importance = IMPORTANCE_LOW)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        if (!isRunning.getAndSet(true)) {
            observeFlowStates()
            Log.d(TAG, "ServicesManager is now observing flows")
        }
        return START_NOT_STICKY
    }

    private fun observeFlowStates() {
        // 1) License invalid → stop everything
        dr.isLicenseValid
            .onEach { valid ->
                if (!valid) {
                    Log.i(TAG, "License is invalid → stopping all services")
                    stopAllServices()
                }
            }
            .launchIn(scope)

        // 2) Master Physio toggle → stop all physiological sub-services
        dr.enablePhysioServices
            .onEach { enabled ->
                if (!enabled) {
                    Log.i(TAG, "Physio services disabled → stopping all")
                    stopAllPhysioServices()
                }
            }
            .launchIn(scope)

        // 3) Heart-rate toggle
        dr.enableHeartRateService
            .onEach { enabled ->
                if (enabled) startHeartRateService()
                else stopHeartRateService()
            }
            .launchIn(scope)

        // 4) Skin-temp toggle
        dr.enableSkinTempService
            .onEach { enabled ->
                if (enabled) startSkinTempService()
                else stopSkinTempService()
            }
            .launchIn(scope)
    }

    // Helpers to start/stop each service
    private fun startHeartRateService() {
        Log.i(TAG, "Starting HeartRateService")
        startService(Intent(this, HeartrateService::class.java))
    }

    private fun stopHeartRateService() {
        Log.i(TAG, "Stopping HeartRateService")
        stopService(Intent(this, HeartrateService::class.java))
    }

    private fun startSkinTempService() {
        Log.i(TAG, "Starting SkinTempService")
        startService(Intent(this, SkinTempService::class.java))
    }

    private fun stopSkinTempService() {
        Log.i(TAG, "Stopping SkinTempService")
        stopService(Intent(this, SkinTempService::class.java))
    }

    // Stops every physo-related service in one go
    private fun stopAllPhysioServices() {
        Log.d(TAG, "Stopping all physio services")
        stopHeartRateService()
        stopSkinTempService()
        // TODO: add other physio services
    }

    // Stops every service in one go
    private fun stopAllServices() {
        Log.d(TAG, "Stopping all physio services")
        stopHeartRateService()
        stopSkinTempService()
        // TODO: stop other services here (DittoService, TakConnectService, CotDispatchService, etc.)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: cleaning up")
        isRunning.set(false)
        // Ensure everything is stopped
        stopAllPhysioServices()
        // Cancel all coroutines
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}