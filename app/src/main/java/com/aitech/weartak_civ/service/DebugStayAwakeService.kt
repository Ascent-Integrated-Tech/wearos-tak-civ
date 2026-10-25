package com.aitech.weartak_civ.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.presentation.WearTAKSettingsActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class DebugStayAwakeService : Service() {
    private var screenLock: PowerManager.WakeLock? = null
    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wifiRequestCallback: ConnectivityManager.NetworkCallback? = null
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val preferences = DebugStayAwakePreferences(this)
        if (intent?.action == ACTION_STOP) {
            preferences.recordSession(false)
            finishSession()
            return START_NOT_STICKY
        }
        if (!preferences.enabled) {
            finishSession()
            return START_NOT_STICKY
        }
        if (running) {
            showNotification()
            return START_STICKY
        }

        try {
            showNotification()
            acquireLocks()
            requestWifi()
        } catch (error: SecurityException) {
            Log.e(TAG, "Cannot start debug stay awake", error)
            Toast.makeText(this, R.string.debug_stay_awake_failed, Toast.LENGTH_LONG).show()
            finishSession()
            return START_NOT_STICKY
        } catch (error: IllegalStateException) {
            Log.e(TAG, "Android prevented debug stay awake", error)
            Toast.makeText(this, R.string.debug_stay_awake_failed, Toast.LENGTH_LONG).show()
            finishSession()
            return START_NOT_STICKY
        }
        running = true
        AppDiagnostics.record(DiagnosticEvent.WAKE_LOCK,
            mapOf(DiagnosticMetric.KIND to 1L, DiagnosticMetric.ACTIVE to 1L))
        mutableActive.value = true
        Log.i(TAG, "Debug stay awake running; enabled intent persists until explicitly stopped")
        return START_STICKY
    }

    private fun requestWifi() {
        // Wi-Fi locks alone do not give WearWifiMediator an active network request.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = ConnectivityManager.NetworkCallback()
        getSystemService(ConnectivityManager::class.java).requestNetwork(request, callback)
        wifiRequestCallback = callback
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val power = getSystemService(PowerManager::class.java)
        // A window flag cannot keep the display awake after leaving the app UI.
        screenLock = power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "$TAG:screen").apply {
            setReferenceCounted(false)
            acquire()
        }
        cpuLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:cpu").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$TAG:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun showNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, getString(R.string.debug_stay_awake_channel), NotificationManager.IMPORTANCE_LOW
        ))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, DebugStayAwakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, WearTAKSettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.splash_icon)
            .setContentTitle(getString(R.string.debug_stay_awake))
            .setContentText(getString(R.string.debug_stay_awake_active))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                getString(R.string.debug_stay_awake_active) + "\n" +
                    getString(R.string.debug_stay_awake_warning)
            ))
            .setContentIntent(open)
            .addAction(0, getString(R.string.debug_stay_awake_stop), stop)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun releaseLocks() {
        val hadLocks = wifiLock != null || cpuLock != null || screenLock != null
        wifiRequestCallback?.let { callback ->
            wifiRequestCallback = null
            try {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Debug stay awake Wi-Fi request was already released", error)
            }
        }
        wifiLock?.let { if (it.isHeld) it.release() }
        cpuLock?.let { if (it.isHeld) it.release() }
        screenLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        cpuLock = null
        screenLock = null
        if (hadLocks) AppDiagnostics.record(DiagnosticEvent.WAKE_LOCK,
            mapOf(DiagnosticMetric.KIND to 1L, DiagnosticMetric.ACTIVE to 0L))
        running = false
        mutableActive.value = false
    }

    private fun finishSession() {
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseLocks()
        Log.i(TAG, "Debug stay awake stopped")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WearTAK-DebugStayAwake"
        internal const val ACTION_STOP = "com.aitech.weartak_civ.DEBUG_STAY_AWAKE_STOP"
        internal const val ACTION_RESTORE = "com.aitech.weartak_civ.DEBUG_STAY_AWAKE_RESTORE"
        private const val CHANNEL_ID = "debug_stay_awake"
        private const val NOTIFICATION_ID = 64_001
        private val mutableActive = MutableStateFlow(false)
        val active = mutableActive.asStateFlow()

        fun setEnabled(context: Context, enabled: Boolean) {
            DebugStayAwakePreferences(context).recordSession(enabled)
            if (enabled) restoreIfEnabled(context) else stop(context)
        }

        fun restoreIfEnabled(context: Context) {
            if (!DebugStayAwakePreferences(context).enabled || active.value) return
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, DebugStayAwakeService::class.java).setAction(ACTION_RESTORE)
                )
            } catch (error: SecurityException) {
                reportRestoreFailure(context, error)
            } catch (error: IllegalStateException) {
                reportRestoreFailure(context, error)
            }
        }

        private fun reportRestoreFailure(context: Context, error: RuntimeException) {
            Log.e(TAG, "Cannot restore Stay awake; saved intent remains enabled", error)
            Toast.makeText(context, R.string.debug_stay_awake_failed, Toast.LENGTH_LONG).show()
        }

        fun stop(context: Context) {
            DebugStayAwakePreferences(context).recordSession(false)
            context.stopService(Intent(context, DebugStayAwakeService::class.java))
        }
    }
}
