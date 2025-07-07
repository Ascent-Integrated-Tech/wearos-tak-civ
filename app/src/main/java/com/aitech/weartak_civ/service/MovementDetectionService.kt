package com.aitech.weartak_civ.service

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.hardware.*
import android.location.Location
import android.os.*
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aitech.weartak_civ.algorithm.EnhancedKalmanEstimator
import com.google.android.gms.location.*
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

class MovementDetectionService : Service(), SensorEventListener {
    enum class AltitudeMode { GPS_ONLY, BARO_SENSOR, KALMAN_ESTIMATED }
    private var altitudeMode: AltitudeMode = AltitudeMode.GPS_ONLY

    private lateinit var kalmanEstimator: EnhancedKalmanEstimator
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val orientation = FloatArray(3)

    private var lastKalmanPredictionTime = 0L
    private val MIN_KALMAN_INTERVAL_MS = 1000L

    private val gravity = FloatArray(3)
    private val gyro = FloatArray(3)
    private var smoothedAccel = FloatArray(3) { 0f }
    private val smoothingAlpha = 0.1f
    private var filterType = 2

    private var lastCorrectedLat: Float? = null
    private var lastCorrectedLon: Float? = null
    private var referenceLat: Double? = null
    private var referenceLon: Double? = null
    private var referenceAlt: Double? = null

    private var lastPredictedLat: Float? = null
    private var lastPredictedLon: Float? = null
    private var lastPredictedAlt: Float = 0f

    private val GPS_DRIFT_THRESHOLD_DEGREES = 0.0005f
    private var lastGpsRequestTime = 0L
    private val GPS_REQUEST_COOLDOWN_MS = 15000L

    private lateinit var logFile: File
    private lateinit var logWriter: PrintWriter
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val isLogging = true

    private fun logToFile(message: String) {
        if (::logWriter.isInitialized && isLogging) {
            val timestamp = timeFormat.format(Date())
            logWriter.println("[$timestamp] $message")
            logWriter.flush()
        }
        Log.d("MovementDetection", message)
    }

    private var isStationary = false
    private val STATIONARY_TIMEOUT_MS = 5000L
    private var lastStepTimestamp = System.currentTimeMillis()

    private val MAX_ALTITUDE_DELTA_STATIONARY = 10f
    private val MAX_ALTITUDE_DELTA_MOVING = 100f

    private var barometer: Sensor? = null
    private var basePressure: Float? = null
    private var currentAltitude: Float = 0f

    override fun onCreate() {
        super.onCreate()

        val dir = getExternalFilesDir(null)
        if (dir == null) {
            Log.e("MovementDetection", "❌ getExternalFilesDir() returned null. Stopping service.")
            stopSelf()
            return
        }

        logFile = File(dir, "enhanced_kalman_log.txt")
        logWriter = PrintWriter(FileWriter(logFile, true))
        logToFile("🟢 Enhanced MovementDetectionService started")
        logToFile("🔧 onCreate() called")

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        barometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        barometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        logToFile("✅ Sensors registered: accel=${accelerometer != null}, gyro=${gyroscope != null}, pressure=${barometer != null}")

        kalmanEstimator = EnhancedKalmanEstimator()
        kalmanEstimator.onGpsUpdateRequired = {
            val pos = kalmanEstimator.getEstimatedPosition()
            val (estLat, estLon) = metersToLatLon(pos[0], pos[1])
            var estAlt = when (altitudeMode) {
                AltitudeMode.GPS_ONLY -> lastPredictedAlt
                AltitudeMode.BARO_SENSOR -> currentAltitude.takeIf { it != 0f } ?: lastPredictedAlt
                AltitudeMode.KALMAN_ESTIMATED -> (referenceAlt?.toFloat() ?: 0f) + pos[2]
            }

            val deltaAlt = estAlt - lastPredictedAlt
            val maxAllowedDelta = if (isStationary) MAX_ALTITUDE_DELTA_STATIONARY else MAX_ALTITUDE_DELTA_MOVING
            if (abs(deltaAlt) > maxAllowedDelta) {
                estAlt = lastPredictedAlt + maxAllowedDelta * sign(deltaAlt)
            }

            lastPredictedLat = estLat
            lastPredictedLon = estLon
            lastPredictedAlt = estAlt

            predictedLat = estLat
            predictedLon = estLon
            predictedAlt = estAlt

            lastPredictedLocation = Location("kalman_prediction").apply {
                latitude = estLat.toDouble()
                longitude = estLon.toDouble()
                altitude = estAlt.toDouble()
                time = System.currentTimeMillis()
            }

            logToFile("📍 Prediction: lat=$estLat, lon=$estLon, alt=$estAlt")

            val latDrift = abs(estLat - (lastCorrectedLat ?: estLat))
            val lonDrift = abs(estLon - (lastCorrectedLon ?: estLon))
            val driftExceeded = latDrift > GPS_DRIFT_THRESHOLD_DEGREES || lonDrift > GPS_DRIFT_THRESHOLD_DEGREES

            val now = System.currentTimeMillis()
            if (driftExceeded && now - lastGpsRequestTime > GPS_REQUEST_COOLDOWN_MS) {
                lastGpsRequestTime = now
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    requestInitialGpsFix()
                }
            }
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            requestInitialGpsFix()
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION])
    private fun requestInitialGpsFix() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 0).apply {
            setMaxUpdates(1)
        }.build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                referenceLat = loc.latitude
                referenceLon = loc.longitude
                referenceAlt = loc.altitude
                lastCorrectedLat = loc.latitude.toFloat()
                lastCorrectedLon = loc.longitude.toFloat()

                kalmanEstimator.reset(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f), System.currentTimeMillis())

                lastPredictedLocation = Location("initial_gps_fix").apply {
                    latitude = loc.latitude
                    longitude = loc.longitude
                    altitude = loc.altitude
                    time = System.currentTimeMillis()
                }
                predictedLat = loc.latitude.toFloat()
                predictedLon = loc.longitude.toFloat()
                predictedAlt = loc.altitude.toFloat()
                lastPredictedAlt = loc.altitude.toFloat()
                currentAltitude = loc.altitude.toFloat()

                logToFile("📡 GPS Fix: lat=${loc.latitude}, lon=${loc.longitude}, alt=${loc.altitude}")
                fusedLocationClient.removeLocationUpdates(this)
            }
        }

        fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val now = System.currentTimeMillis()

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val alpha = 0.8f
                for (i in 0..2) gravity[i] = alpha * gravity[i] + (1 - alpha) * event.values[i]

                val linearAccel = FloatArray(3) { i -> event.values[i] - gravity[i] }

                smoothedAccel = when (filterType) {
                    0 -> lowpassFilter(linearAccel, smoothedAccel, smoothingAlpha)
                    1 -> complementaryFilter(linearAccel, smoothedAccel, smoothingAlpha)
                    2 -> madgwickFilter(smoothedAccel, gyro, linearAccel)
                    3 -> mahonyFilter(smoothedAccel, gyro, linearAccel)
                    else -> linearAccel
                }

                if (now - lastKalmanPredictionTime >= MIN_KALMAN_INTERVAL_MS) {
                    kalmanEstimator.predict(smoothedAccel, orientation, now)
                    lastKalmanPredictionTime = now
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                for (i in 0..2) gyro[i] = event.values[i]
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun metersToLatLon(x: Float, y: Float): Pair<Float, Float> {
        val lat0 = referenceLat ?: 0.0
        val lon0 = referenceLon ?: 0.0
        val latRad = Math.toRadians(lat0)
        val metersPerLat = 111132.92 - 559.82 * cos(2 * latRad) + 1.175 * cos(4 * latRad) - 0.0023 * cos(6 * latRad)
        val metersPerLon = 111412.84 * cos(latRad) - 93.5 * cos(3 * latRad) + 0.118 * cos(5 * latRad)
        val dLat = x / metersPerLat
        val dLon = y / metersPerLon
        return Pair((lat0 + dLat).toFloat(), (lon0 + dLon).toFloat())
    }

    private fun lowpassFilter(input: FloatArray, prev: FloatArray, alpha: Float): FloatArray =
        FloatArray(3) { i -> alpha * input[i] + (1 - alpha) * prev[i] }

    private fun complementaryFilter(accel: FloatArray, prev: FloatArray, alpha: Float): FloatArray =
        FloatArray(3) { i -> alpha * accel[i] + (1 - alpha) * prev[i] }

    private fun madgwickFilter(prev: FloatArray, gyro: FloatArray, accel: FloatArray): FloatArray {
        val beta = 0.05f
        val dt = 0.01f
        return FloatArray(3) { i -> prev[i] + gyro[i] * dt + beta * (accel[i] - prev[i]) }
    }

    private fun mahonyFilter(prev: FloatArray, gyro: FloatArray, accel: FloatArray): FloatArray {
        val kp = 0.2f
        val ki = 0.005f
        return FloatArray(3) { i -> prev[i] + kp * (accel[i] - prev[i]) + ki * gyro[i] }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        logToFile("🔴 MovementDetectionService stopped")
        logWriter.close()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private lateinit var sensorManager: SensorManager
        private var accelerometer: Sensor? = null
        private var gyroscope: Sensor? = null

        val isRunning = AtomicBoolean(false)
        @Volatile var predictedLat: Float = 0f
        @Volatile var predictedLon: Float = 0f
        @Volatile var predictedAlt: Float = 0f
        @Volatile var userSpeedMps: Float = 0f

        @Volatile
        var lastPredictedLocation: Location = Location("kalman_prediction").apply {
            latitude = 0.0
            longitude = 0.0
            altitude = 0.0
            time = System.currentTimeMillis()
        }
    }
}
