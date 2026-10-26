package com.aitech.weartak_civ.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.util.Log
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.Utils.stopForegroundServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.aitech.weartak_civ.cep.bus.CepBus
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.Topics
import com.aitech.weartak_civ.cep.model.payloads.HumidityPayload
import com.aitech.weartak_civ.cep.model.payloads.LightPayload
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
    private var rHumidity: Sensor ?= null   // TYPE_RELATIVE_HUMIDITY (%) - may be null

    // last-known values; used for throttled consolidated logging
    private var lastTempC: Float? = null
    private var lastPressureHpa: Float? = null
    private var lastLux: Float? = null
    private var lastHumidity: Float? = null

    // Phase 4: CEP events are now emitted at native sensor rate directly from onSensorChanged.
    // The 1 Hz throttle below applies only to logSnapshot, not CEP emission.
    private var lastNotifMs: Long = 0
    private val minNotifIntervalMs: Long = 1000

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)


    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)

        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        //sTemp = sm.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
        sPressure = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
        sLight = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
        rHumidity = sm.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY)

        //if (sTemp == null) Log.w(TAG, "Ambient temperature sensor not available.")
        if (sPressure == null) Log.w(TAG, "Barometer (pressure) sensor not available.")
        if (sLight == null) Log.w(TAG, "Ambient light sensor not available.")
        if (rHumidity == null) Log.w(TAG, "Relative humidity sensor not available.")

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote to foreground immediately for background longevity
        startForegroundServiceCompat(NOTIF_ID, importance = NotificationManager.IMPORTANCE_LOW)

        // Register sensors
        //sTemp?.let     { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sPressure?.let { sensor -> DiagnosticResources.acquire(DiagnosticResource.ENV_PRESSURE, this,
            periodUs = 200_000, successful = { it }) { sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL) } }
        sLight?.let { sensor -> DiagnosticResources.acquire(DiagnosticResource.ENV_LIGHT, this,
            periodUs = 200_000, successful = { it }) { sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL) } }
        rHumidity?.let { sensor -> DiagnosticResources.acquire(DiagnosticResource.ENV_HUMIDITY, this,
            periodUs = 200_000, successful = { it }) { sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL) } }

        // Phase 4: CEP emission is now driven by onSensorChanged at native rate.
        // No separate emit loop needed.

        Log.i(TAG, "EnvSensorService started in foreground; listeners registered.")
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            serviceScope.cancel()
        } catch (_: Throwable) { }
        val resources = listOf(DiagnosticResource.ENV_PRESSURE, DiagnosticResource.ENV_LIGHT, DiagnosticResource.ENV_HUMIDITY)
        resources.forEach { DiagnosticResources.note(it, this, ResourceAction.REMOVAL_REQUESTED) }
        var unregistered = false
        try {
            sm.unregisterListener(this)
            unregistered = true
        } finally {
            resources.forEach {
                if (unregistered) DiagnosticResources.end(it, this)
                else DiagnosticResources.note(it, this, ResourceAction.UNCERTAIN)
            }
        }
        isRunning.set(false)
        stopForegroundServiceCompat()
        Log.i(TAG, "EnvSensorService destroyed; listeners unregistered and foreground stopped.")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- SensorEventListener ----
    override fun onSensorChanged(ev: SensorEvent) {
        val now = System.currentTimeMillis()

        // Phase 4: update cached value and emit to CepBus at native sensor rate.
        when (ev.sensor.type) {
            Sensor.TYPE_AMBIENT_TEMPERATURE -> lastTempC = ev.values.getOrNull(0)
            Sensor.TYPE_PRESSURE            -> lastPressureHpa = ev.values.getOrNull(0)
            Sensor.TYPE_LIGHT               -> {
                lastLux = ev.values.getOrNull(0)
                lastLux?.let { lux ->
                    val covered = lux < 5.0f
                    serviceScope.launch {
                        try {
                            CepBus.emit(CepEvent(topic = Topics.LIGHT, payload = LightPayload(lux = lux, covered = covered), ts = now))
                        } catch (t: Throwable) { Log.e(TAG, "CEP emit light failed", t) }
                    }
                }
            }
            Sensor.TYPE_RELATIVE_HUMIDITY   -> {
                lastHumidity = ev.values.getOrNull(0)
                lastHumidity?.let { rh ->
                    serviceScope.launch {
                        try {
                            CepBus.emit(CepEvent(topic = Topics.HUMIDITY, payload = HumidityPayload(relativePercent = rh), ts = now))
                        } catch (t: Throwable) { Log.e(TAG, "CEP emit humidity failed", t) }
                    }
                }
            }
        }

        // Throttle log updates to 1 Hz, not CEP emission.
        if (now - lastNotifMs >= minNotifIntervalMs) {
            lastNotifMs = now
            logSnapshot(now)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        sensor ?: return
        Log.d(TAG, "Accuracy changed: type=${sensor.type} accuracy=$accuracy")
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
        private const val NOTIF_ID = 2701
    }
}
