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
import com.aitech.weartak_civ.algorithm.KalmanEstimator
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

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var gravity = FloatArray(3)
    private var smoothedAccel = FloatArray(3) { 0f }
    private var gyro = FloatArray(3) { 0f }
    private val smoothingAlpha = 0.1f
    private var lastKalmanPredictionTime = 0L
    private val MIN_KALMAN_INTERVAL_MS = 1000L

    private val MOVEMENT_THRESHOLD = 8.0f
    private val INTERVAL_MOVING = 5000L
    private val INTERVAL_STILL = 30000L
    private var currentInterval = INTERVAL_STILL

    private val HIGH_THRESHOLD = MOVEMENT_THRESHOLD
    private var highCount = 0
    private var lowCount = 0
    private val REQUIRED_CONSECUTIVE_HIGHS = 3
    private val REQUIRED_CONSECUTIVE_LOWS = 3

    private val stillConfirmDelay = 5000L
    private val transitionHandler = Handler(Looper.getMainLooper())
    private val confirmStillRunnable = Runnable {
        if (isUserMoving) {
            isUserMoving = false
            updateCotInterval(INTERVAL_STILL)
        }
    }

    private var isUserMoving = false

    private lateinit var kalmanEstimator: KalmanEstimator
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var lastCorrectedLat: Float? = null
    private var lastCorrectedLon: Float? = null
    private var referenceLat: Double? = null
    private var referenceLon: Double? = null
    private val GPS_DRIFT_THRESHOLD_DEGREES = 0.005f

    private var lastGpsRequestTime = 0L
    private val GPS_REQUEST_COOLDOWN_MS = 15000L
    private var lastPredictedLat: Float? = null
    private var lastPredictedLon: Float? = null

    var lastPredictedAlt: Float = 0f
    var lastCorrectedAlt: Float? = null
    var referenceAlt: Double? = null

    private var lastUpdateTimeMs: Long = 0L
    private var lastUpdateLat: Float = 0f
    private var lastUpdateLon: Float = 0f

    // Step detection
    private var lastStepTimestamp = 0L
    private var stepCount = 0
    private val stepTimestamps = ArrayDeque<Long>()
    private val STEP_DETECTION_THRESHOLD = 1.2f
    private val MIN_STEP_INTERVAL_MS = 300L
    private val STEP_RATE_WINDOW_MS = 5000L

    // Speed smoothing
    private val speedWindow = ArrayDeque<Float>()
    private val SPEED_WINDOW_SIZE = 5

    // 🧾 File logging utilities
    private lateinit var logFile: File
    private lateinit var logWriter: PrintWriter
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private var filterType = 2

    private var isLogging = false

    private fun logToFile(message: String) {
        if(isLogging){
            val timestamp = timeFormat.format(Date())
            logWriter.println("[$timestamp] $message")
            logWriter.flush()
        }
    }

    private val TAG = "MovementDetectionService"

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        startForeground(1, createNotification())

        logFile = File(getExternalFilesDir(null), "movement_log.txt")
        logWriter = PrintWriter(FileWriter(logFile, true))
        logToFile("🟢 MovementDetectionService started")

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }

        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }

        isUserMoving = false
        updateCotInterval(INTERVAL_STILL)

        kalmanEstimator = KalmanEstimator()
        kalmanEstimator.onGpsUpdateRequired = {
            val pos = kalmanEstimator.getEstimatedPosition()
            val (estLat, estLon) = metersToLatLon(pos[0], pos[1])
            val estAlt = (referenceAlt?.toFloat() ?: 0f) + pos[2]

            val estLocation = Location("kalman_prediction").apply {
                latitude = estLat.toDouble()
                longitude = estLon.toDouble()
                altitude = estAlt.toDouble()
                time = System.currentTimeMillis()
            }


            lastPredictedLat = estLat
            lastPredictedLon = estLon
            lastPredictedAlt = estAlt

            lastPredictedLocation = estLocation

            predictedAlt = lastPredictedAlt
            predictedLat = lastPredictedLat!!
            predictedLon = lastPredictedLon!!

            logToFile("🧮 Estimated GPS: lat=$estLat, lon=$estLon, alt=$estAlt")

            if (lastUpdateTimeMs == 0L) {
                lastUpdateTimeMs = System.currentTimeMillis()
                lastUpdateLat = lastCorrectedLat ?: 0f
                lastUpdateLon = lastCorrectedLon ?: 0f
            }

            val latDrift = abs(estLat - (lastCorrectedLat ?: estLat))
            val lonDrift = abs(estLon - (lastCorrectedLon ?: estLon))
            val driftExceeded = latDrift > GPS_DRIFT_THRESHOLD_DEGREES || lonDrift > GPS_DRIFT_THRESHOLD_DEGREES

            val now = System.currentTimeMillis()
            if (driftExceeded && (now - lastGpsRequestTime > GPS_REQUEST_COOLDOWN_MS)) {
                lastGpsRequestTime = now
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    logToFile("📡 Requesting new GPS fix due to drift")
                    requestInitialGpsFix()
                    lastUpdateTimeMs = System.currentTimeMillis()
                    lastUpdateLat = lastCorrectedLat ?: 0f
                    lastUpdateLon = lastCorrectedLon ?: 0f
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            requestInitialGpsFix()
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val currentTime = System.currentTimeMillis()
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val alpha = 0.8f
            gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
            gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
            gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]

            val linearAccel = FloatArray(3)
            linearAccel[0] = event.values[0] - gravity[0]
            linearAccel[1] = event.values[1] - gravity[1]
            linearAccel[2] = event.values[2] - gravity[2]

            val linearAccelMag = sqrt(linearAccel[0].pow(2) + linearAccel[1].pow(2) + linearAccel[2].pow(2))
            val now = System.currentTimeMillis()

            if (linearAccelMag > STEP_DETECTION_THRESHOLD && now - lastStepTimestamp > MIN_STEP_INTERVAL_MS) {
                stepCount++
                lastStepTimestamp = now
                stepTimestamps.addLast(now)
            }

            while (stepTimestamps.isNotEmpty() && now - stepTimestamps.first() > STEP_RATE_WINDOW_MS) {
                stepTimestamps.removeFirst()
            }

            //val accelMag = linearAccelMag

            /*smoothedAccel[0] = smoothingAlpha * linearAccel[0] + (1 - smoothingAlpha) * smoothedAccel[0]
            smoothedAccel[1] = smoothingAlpha * linearAccel[1] + (1 - smoothingAlpha) * smoothedAccel[1]
            smoothedAccel[2] = smoothingAlpha * linearAccel[2] + (1 - smoothingAlpha) * smoothedAccel[2]*/

            smoothedAccel = when (filterType) {
                0 -> lowpassFilter(linearAccel, smoothedAccel, smoothingAlpha)
                1 -> complementaryFilter(linearAccel, smoothedAccel, smoothingAlpha)
                2 -> madgwickFilter(smoothedAccel, gyro, linearAccel)
                3 -> mahonyFilter(smoothedAccel, gyro, linearAccel)
                else -> linearAccel
            }

            if (currentTime - lastKalmanPredictionTime >= MIN_KALMAN_INTERVAL_MS) {
                kalmanEstimator.predict(smoothedAccel, currentTime)
                lastKalmanPredictionTime = currentTime

                val predLat = lastPredictedLat ?: -1f
                val predLon = lastPredictedLon ?: -1f
                val corrLat = lastCorrectedLat ?: -1f
                val corrLon = lastCorrectedLon ?: -1f

                val timeDeltaSec = (currentTime - lastUpdateTimeMs) / 1000f
                if (lastUpdateTimeMs != 0L && timeDeltaSec > 0.1f) {
                    val distanceMeters = haversineDistance(
                        lastUpdateLat.toDouble(), lastUpdateLon.toDouble(),
                        predLat.toDouble(), predLon.toDouble()
                    )
                    userSpeedMps = distanceMeters / timeDeltaSec
                }

                speedWindow.addLast(userSpeedMps)
                if (speedWindow.size > SPEED_WINDOW_SIZE) speedWindow.removeFirst()
                val avgSpeed = speedWindow.average().toFloat()

                lastUpdateTimeMs = currentTime
                lastUpdateLat = predLat
                lastUpdateLon = predLon

                val stepRateHz = stepTimestamps.size / (STEP_RATE_WINDOW_MS / 1000f)

                val activity = when {
                    avgSpeed < 0.1f && stepRateHz < 0.5f -> "Still"
                    avgSpeed < 2.0f && stepRateHz in 0.5f..2.0f -> "Walking"
                    avgSpeed >= 2.0f && stepRateHz > 1.5f -> "Running"
                    avgSpeed > 3.0f && stepRateHz < 0.5f -> "Driving or Cycling"
                    else -> "Unknown"
                }

                Log.d(TAG, "🧭 Prediction Update -> Predicted: ($predLat, $predLon, $predictedAlt), Corrected: ($corrLat, $corrLon, $lastCorrectedAlt)")
                Log.d(TAG, "🚶 Avg Speed: %.2f m/s, Step Rate: %.2f Hz, Activity: %s".format(avgSpeed, stepRateHz, activity))

                logToFile("🧭 Prediction Update -> Predicted: ($predLat, $predLon, $predictedAlt), Corrected: ($corrLat, $corrLon, $lastCorrectedAlt)")
                logToFile("🚶 Avg Speed: %.2f m/s, Step Rate: %.2f Hz, Activity: %s".format(avgSpeed, stepRateHz, activity))
            }
        }else if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            gyro[0] = event.values[0]
            gyro[1] = event.values[1]
            gyro[2] = event.values[2]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun lowpassFilter(input: FloatArray, prev: FloatArray, alpha: Float): FloatArray =
        FloatArray(3) { i -> alpha * input[i] + (1 - alpha) * prev[i] }

    private fun complementaryFilter(accel: FloatArray, prev: FloatArray, alpha: Float): FloatArray =
        FloatArray(3) { i -> alpha * accel[i] + (1 - alpha) * prev[i] }

    private fun madgwickFilter(prev: FloatArray, gyro: FloatArray, accel: FloatArray): FloatArray {
        val beta = 0.05f  // smaller correction factor
        val dt = 0.01f    // assumed constant timestep
        return FloatArray(3) { i -> prev[i] + gyro[i] * dt + beta * (accel[i] - prev[i]) }
    }

    private fun mahonyFilter(prev: FloatArray, gyro: FloatArray, accel: FloatArray): FloatArray {
        val kp = 0.2f
        val ki = 0.005f
        return FloatArray(3) { i -> prev[i] + kp * (accel[i] - prev[i]) + ki * gyro[i] }
    }

    private fun updateCotInterval(newInterval: Long) {
        if (newInterval != currentInterval) {
            currentInterval = newInterval
            if (CotDispatchService.isRunning.get()) {
                val newIntervalSecs = (newInterval / 1000L).toInt()
                if (CotDispatchService.currReportingIntervalInSecs != newIntervalSecs) {
                    CotDispatchService.currReportingIntervalInSecs = newIntervalSecs
                }
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION])
    private fun requestInitialGpsFix() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 0).apply {
            setMaxUpdates(1)
            setMinUpdateIntervalMillis(0)
            setWaitForAccurateLocation(true)
        }.build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return

                referenceLat = location.latitude
                referenceLon = location.longitude
                referenceAlt = location.altitude

                lastCorrectedLat = referenceLat!!.toFloat()
                lastCorrectedLon = referenceLon!!.toFloat()
                lastCorrectedAlt = referenceAlt!!.toFloat()

                logToFile("📍 Initial GPS Fix: lat=${referenceLat}, lon=${referenceLon}, alt=${referenceAlt}")

                kalmanEstimator.reset(
                    floatArrayOf(0f, 0f, 0f),
                    floatArrayOf(0f, 0f, 0f),
                    System.currentTimeMillis()
                )

                fusedLocationClient.removeLocationUpdates(this)
            }
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

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

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) *
                cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (R * c).toFloat()
    }

    private fun createNotification(): Notification {
        val channelId = "movement_channel"
        val channelName = "Movement Detection"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Movement Service Running")
            .setContentText("Monitoring your movement in background.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        isRunning.set(false)
        Log.d(TAG, "Movement detection service stopped")
    }

    companion object {
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
