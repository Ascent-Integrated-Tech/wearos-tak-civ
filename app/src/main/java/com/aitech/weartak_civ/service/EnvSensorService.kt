package com.aitech.weartak_civ.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.round

/**
 * WEAR-270: Logs ambient sensors (temp °C, pressure hPa, light lux).
 * Runs as a foreground service to remain alive in background.
 * No UI, no networking, no event buses.
 */
class EnvSensorService : Service(), SensorEventListener {

    private lateinit var sm: SensorManager
    //private var sTemp: Sensor? = null       // TYPE_AMBIENT_TEMPERATURE (°C) - may be null
    private var sPressure: Sensor? = null   // TYPE_PRESSURE (hPa)
    private var sLight: Sensor? = null      // TYPE_LIGHT (lux)

    // last-known values; used for throttled consolidated logging
    private var lastTempC: Float? = null
    private var lastPressureHpa: Float? = null
    private var lastLux: Float? = null

    // emit at most 1x per second
    private var lastEmitMs: Long = 0
    private val minEmitIntervalMs: Long = 1000

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)

        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        //sTemp = sm.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
        sPressure = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
        sLight = sm.getDefaultSensor(Sensor.TYPE_LIGHT)

        //if (sTemp == null) Log.w(TAG, "Ambient temperature sensor not available.")
        if (sPressure == null) Log.w(TAG, "Barometer (pressure) sensor not available.")
        if (sLight == null) Log.w(TAG, "Ambient light sensor not available.")

        // Prepare the foreground notification channel (API 26+)
        createNotificationChannelIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote to foreground immediately for background longevity
        startForeground(NOTIF_ID, buildPersistentNotification())

        // Register sensors
        //sTemp?.let     { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sPressure?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sLight?.let    { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }


        Log.i(TAG, "EnvSensorService started in foreground; listeners registered.")
        return START_STICKY
    }

    override fun onDestroy() {
        sm.unregisterListener(this)
        isRunning.set(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "EnvSensorService destroyed; listeners unregistered and foreground stopped.")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- SensorEventListener ----
    override fun onSensorChanged(ev: SensorEvent) {
        when (ev.sensor.type) {
            Sensor.TYPE_AMBIENT_TEMPERATURE -> lastTempC = ev.values.getOrNull(0)
            Sensor.TYPE_PRESSURE            -> lastPressureHpa = ev.values.getOrNull(0)
            Sensor.TYPE_LIGHT               -> lastLux = ev.values.getOrNull(0)
        }

        val now = System.currentTimeMillis()
        if (now - lastEmitMs >= minEmitIntervalMs) {
            lastEmitMs = now
            logSnapshot(now)
            // Optionally refresh notification content text to reflect latest snapshot
            // (kept silent/ongoing to avoid user spam)
            updateForegroundNotification()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        sensor ?: return
        Log.d(TAG, "Accuracy changed: type=${sensor.type} accuracy=$accuracy")
    }

    // ---- Foreground notification helpers ----
    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Environment Sensors",
                    NotificationManager.IMPORTANCE_MIN // silent, low-visibility
                ).apply {
                    description = "Background logging of ambient temperature, pressure, and light"
                    setShowBadge(false)
                    enableVibration(false)
                    enableLights(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildPersistentNotification(): Notification {
        val content = "Logging env sensors (temp/pressure/light)"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setSmallIcon(android.R.drawable.ic_menu_compass) // system icon; no resource change needed
            .setContentTitle("Environment Sensors")
            .setContentText(content)
            .build()
    }

    private fun updateForegroundNotification() {
        // Keep it minimal; update with a one-line snapshot for quick glance
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val text = "T=${lastTempC?.let { round1(it) } ?: "—"}°C • " +
                   "P=${lastPressureHpa?.let { round1(it) } ?: "—"}hPa • " +
                   "L=${lastLux?.let { round1(it) } ?: "—"}lux"

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Environment Sensors")
            .setContentText(text)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    // ---- Logging helpers ----
    private fun logSnapshot(tsMs: Long) {
        val tStr = lastTempC?.let { "${round1(it)}°C" } ?: "—"
        val pStr = lastPressureHpa?.let { "${round1(it)} hPa" } ?: "—"
        val lStr = lastLux?.let { "${round1(it)} lux" } ?: "—"
        //Log.d(TAG, "ts=$tsMs | ambientTemp=$tStr | pressure=$pStr | light=$lStr")
    }

    private fun round1(v: Float): String {
        val scaled = round(v * 10f) / 10f
        return if (scaled % 1.0f == 0f) "%.1f".format(scaled) else scaled.toString()
    }

    companion object {
        val isRunning = AtomicBoolean(false)
        private val TAG = "WearTAK-" + EnvSensorService::class.java.simpleName
        private const val CHANNEL_ID = "env_sensor_channel"
        private const val NOTIF_ID = 2701
    }
}


