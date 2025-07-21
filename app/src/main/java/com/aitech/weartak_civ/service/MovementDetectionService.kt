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
//import com.aitech.weartak_civ.algorithm.CoordinateLimiter
import com.aitech.weartak_civ.algorithm.EnhancedKalmanEstimator
import com.aitech.weartak_civ.algorithm.StepDetector
import com.google.android.gms.location.*
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.toFloatArray
import kotlin.math.*

enum class MovementState {
    STATIONARY, WALKING, RUNNING, DRIVING
}

class MovementDetectionService : Service(), SensorEventListener {
    enum class AltitudeMode { GPS_ONLY, BARO_SENSOR, KALMAN_ESTIMATED }
    private var altitudeMode: AltitudeMode = AltitudeMode.GPS_ONLY

    private lateinit var kalmanEstimator: EnhancedKalmanEstimator
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val orientation = FloatArray(3)

    private var lastKalmanPredictionTime = 0L
    private val MIN_KALMAN_INTERVAL_MS = 100L

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

    private val GPS_DRIFT_THRESHOLD_DEGREES = 0.005f
    private var lastGpsRequestTime = 0L
    private val GPS_REQUEST_COOLDOWN_MS = 15000L

    private lateinit var logFile: File
    private lateinit var logWriter: PrintWriter
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val isLogging = false

    private fun logToFile(message: String) {
        if (::logWriter.isInitialized && isLogging) {
            val timestamp = timeFormat.format(Date())
            logWriter.println("[$timestamp] $message")
            logWriter.flush()
        }
        //Log.d("MovementDetection", message)
    }

    private var isStationary = false
    private val STATIONARY_TIMEOUT_MS = 5000L
    private var lastStepTimestamp = System.currentTimeMillis()

    private val MAX_ALTITUDE_DELTA_STATIONARY = 10f
    private val MAX_ALTITUDE_DELTA_MOVING = 100f

    private var barometer: Sensor? = null
    private var magnetometer: Sensor? = null
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var basePressure: Float? = null
    private var currentAltitude: Float = 0f

    private var movementState: MovementState = MovementState.STATIONARY

    //private val stepDetector = StepDetector()
    private var lastStepLogTime = 0L

    private var orientationQuat = Quaternion(1f, 0f, 0f, 0f)
    private val mag = FloatArray(3)

    override fun onCreate() {
        super.onCreate()

        val dir = getExternalFilesDir(null)
        if (dir == null) {
            Log.e("MovementDetection", "❌ getExternalFilesDir() returned null. Stopping service.")
            stopSelf()
            return
        }

        val dateSuffix = SimpleDateFormat("dd_MM_yy", Locale.US).format(Date())
        val file_name = "enhanced_kalman_log_" + dateSuffix + ".txt"
        logFile = File(dir, file_name)
        //logFile = File(dir, "enhanced_kalman_log.txt")
        logWriter = PrintWriter(FileWriter(logFile, true))
        logToFile("🟢 Enhanced MovementDetectionService started")
        logToFile("🔧 onCreate() called")

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        barometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        barometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }

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

            logToFile("📍 PredictionGPSUpdate: lat=$estLat, lon=$estLon, alt=$estAlt")

            val latDrift = abs(estLat - (lastCorrectedLat ?: estLat))
            val lonDrift = abs(estLon - (lastCorrectedLon ?: estLon))
            val driftExceeded = latDrift > GPS_DRIFT_THRESHOLD_DEGREES || lonDrift > GPS_DRIFT_THRESHOLD_DEGREES

            val now = System.currentTimeMillis()
            val timeSinceLastFix = now - lastGpsRequestTime
            val forceFixDue = timeSinceLastFix > 5 * 60 * 1000L  // 5 minutes

            if (driftExceeded || forceFixDue) {
                lastGpsRequestTime = now
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    requestInitialGpsFix()
                    logToFile("📡 GPS fix requested [reason=${if (driftExceeded) "drift" else "5min rebase"}]")
                } else {
                    logToFile("❌ GPS fix skipped: permission not granted")
                }
            }
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            requestInitialGpsFix()
        }
    }

    data class Quaternion(var w: Float, var x: Float, var y: Float, var z: Float) {
        fun toEuler(): FloatArray {
            val roll = atan2(2f * (w * x + y * z), 1f - 2f * (x * x + y * y))
            val pitch = asin((2f * (w * y - z * x)).coerceIn(-1f, 1f))
            val yaw = atan2(2f * (w * z + x * y), 1f - 2f * (y * y + z * z))
            return floatArrayOf(roll, pitch, yaw)
        }
    }

    fun normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return if (norm > 0f) FloatArray(3) { i -> v[i] / norm } else v
    }

    fun quatMultiply(q1: Quaternion, q2: Quaternion): Quaternion {
        return Quaternion(
            q1.w * q2.w - q1.x * q2.x - q1.y * q2.y - q1.z * q2.z,
            q1.w * q2.x + q1.x * q2.w + q1.y * q2.z - q1.z * q2.y,
            q1.w * q2.y - q1.x * q2.z + q1.y * q2.w + q1.z * q2.x,
            q1.w * q2.z + q1.x * q2.y - q1.y * q2.x + q1.z * q2.w
        )
    }

    fun trueMadgwickFilter(
        q: Quaternion,
        accel: FloatArray,
        gyro: FloatArray,
        mag: FloatArray,
        beta: Float = 0.1f,
        dt: Float = 0.01f
    ): Quaternion {
        val a = normalize(accel)
        val m = normalize(mag)

        val (w, x, y, z) = q

        val hx = 2f * (m[0] * (0.5f - y * y - z * z) + m[1] * (x * y - w * z) + m[2] * (x * z + w * y))
        val hy = 2f * (m[0] * (x * y + w * z) + m[1] * (0.5f - x * x - z * z) + m[2] * (y * z - w * x))
        val _2bx = sqrt(hx * hx + hy * hy)
        val _2bz = 2f * (m[0] * (x * z - w * y) + m[1] * (y * z + w * x) + m[2] * (0.5f - x * x - y * y))

        val f1 = 2 * (x * z - w * y) - a[0]
        val f2 = 2 * (w * x + y * z) - a[1]
        val f3 = 2 * (0.5f - x * x - y * y) - a[2]

        val s1 = -2f * y * f1 + 2f * x * f2
        val s2 = 2f * z * f1 + 2f * w * f2 - 4f * x * f3
        val s3 = -2f * w * f1 + 2f * z * f2 - 4f * y * f3
        val s4 = 2f * x * f1 + 2f * y * f2

        val normS = sqrt(s1 * s1 + s2 * s2 + s3 * s3 + s4 * s4)
        val grad = floatArrayOf(s1, s2, s3, s4).map { it / normS }.toFloatArray()

        val gyroQuat = Quaternion(0f, gyro[0], gyro[1], gyro[2])
        val qDot = quatMultiply(q, gyroQuat).let {
            Quaternion(0.5f * it.w, 0.5f * it.x, 0.5f * it.y, 0.5f * it.z)
        }

        val newQ = Quaternion(
            w + (qDot.w - beta * grad[0]) * dt,
            x + (qDot.x - beta * grad[1]) * dt,
            y + (qDot.y - beta * grad[2]) * dt,
            z + (qDot.z - beta * grad[3]) * dt
        )

        val norm = sqrt(newQ.w * newQ.w + newQ.x * newQ.x + newQ.y * newQ.y + newQ.z * newQ.z)
        return Quaternion(newQ.w / norm, newQ.x / norm, newQ.y / norm, newQ.z / norm)
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

                //CoordinateLimiter.updateLastFix(loc.latitude, loc.longitude)

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

                //// PREVIOUS HIGH PASS FILTER
                //val alpha = 0.8f
                //for (i in 0..2) gravity[i] = alpha * gravity[i] + (1 - alpha) * event.values[i]
                //val linearAccel = FloatArray(3) { i -> event.values[i] - gravity[i] }

                // Step detection
                /*stepDetector.processAccelerometerData(
                    linearAccel[0],
                    linearAccel[1],
                    linearAccel[2],
                    now
                )*/

                val linearAccel = event.values.clone()  // Use raw acceleration directly

                // Run prediction only if interval has elapsed
                if (now - lastKalmanPredictionTime >= MIN_KALMAN_INTERVAL_MS) {
                    if (filterType == 2) {
                        // Use true Madgwick filter
                        orientationQuat = trueMadgwickFilter(
                            q = orientationQuat,
                            accel = linearAccel,
                            gyro = gyro,
                            mag = mag,
                            dt = 0.01f
                        )
                        val orientation = orientationQuat.toEuler()
                        kalmanEstimator.predict(linearAccel, orientation, now)

                        // Optional: log orientation
                        //logToFile("🧭 Orientation (rad): roll=${orientation[0]}, pitch=${orientation[1]}, yaw=${orientation[2]}")
                        logToFile(
                            "📈 Prediction → Lat: %.6f, Lon: %.6f, Alt: %.2f | Orientation → Roll: %.2f, Pitch: %.2f, Yaw: %.2f".format(
                                predictedLat,
                                predictedLon,
                                predictedAlt,
                                Math.toDegrees(orientation[0].toDouble()),
                                Math.toDegrees(orientation[1].toDouble()),
                                Math.toDegrees(orientation[2].toDouble())
                            )
                        )
                    } else {
                        // Use other smoothing filters if selected
                        smoothedAccel = when (filterType) {
                            0 -> lowpassFilter(linearAccel, smoothedAccel, smoothingAlpha)
                            1 -> complementaryFilter(linearAccel, smoothedAccel, smoothingAlpha)
                            3 -> mahonyFilter(smoothedAccel, gyro, linearAccel)
                            else -> linearAccel
                        }
                        kalmanEstimator.predict(smoothedAccel, orientation, now)
                    }

                    lastKalmanPredictionTime = now
                }

                /*val stepRate = stepDetector.getStepRate().toFloat()
                val stepCount = stepDetector.getStepCount()
                val distanceMoved = stepDetector.getDistanceMeters().toFloat()

                updateMovementState(stepRate, userSpeedMps)*/

                //logToFile("👣 Steps=$stepCount | Distance=${"%.2f".format(distanceMoved)}m | Rate=${"%.2f".format(stepRate)} Hz | State=$movementState")
                lastStepLogTime = now
            }

            Sensor.TYPE_GYROSCOPE -> {
                for (i in 0..2) gyro[i] = event.values[i]
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                for (i in 0..2) mag[i] = event.values[i]
            }
        }
    }

    private fun updateMovementState(stepRate: Float, speedMps: Float) {
        movementState = when {
            stepRate < 0.2f && speedMps > 4.0f -> MovementState.DRIVING
            stepRate < 0.2f && speedMps < 0.5f -> MovementState.STATIONARY
            stepRate > 0.5f && speedMps > 0.5f -> MovementState.WALKING
            stepRate > 2.5f && speedMps > 1.5f  -> MovementState.RUNNING
            else -> movementState
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
