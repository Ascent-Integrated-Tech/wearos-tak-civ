package com.aitech.weartak_civ.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.util.Log
import com.aitech.weartak_civ.complication.CoreTempEstimationComplicationService
import kotlin.math.roundToInt

class CoreTempEstimationService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var heartRateSensor: Sensor? = null
    private val hrBuffer = mutableListOf<Float>()
    private val estimator = CoreTempEstimationComplicationService(37.0)
    private var lastTimestamp: Long = 0L

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)

        heartRateSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent?) {
        val hr = event?.values?.firstOrNull() ?: return
        val now = System.currentTimeMillis()

        if (hr > 0 && now - lastTimestamp > 1000) {
            hrBuffer.add(hr)
            lastTimestamp = now
        }

        if (hrBuffer.size >= 60) {
            val avgHR = hrBuffer.average()
            val ct = estimator.estimate(listOf(avgHR)).last()

            // Output to Logcat only, for now
            Log.d("CoreTempEstimator", "Avg HR: ${avgHR.roundToInt()} bpm, Estimated CT: %.2f°C".format(ct))

            hrBuffer.clear()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
