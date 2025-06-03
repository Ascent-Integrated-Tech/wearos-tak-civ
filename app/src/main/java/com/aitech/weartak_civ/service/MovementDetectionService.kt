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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

class MovementDetectionService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gravity = FloatArray(3)
    private var smoothedAccel = FloatArray(3) { 0f }
    private val smoothingAlpha = 0.1f
    private var lastKalmanPredictionTime = 0L
    private val MIN_KALMAN_INTERVAL_MS = 100L

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

    //GPS THROTTLING
    private var lastGpsRequestTime = 0L
    private val GPS_REQUEST_COOLDOWN_MS = 5000L
    val KALMAN_UPDATE_INTERVAL_MS = 500L

    private val TAG = "MovementDetectionService"

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        startForeground(1, createNotification())

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }

        isUserMoving = false
        updateCotInterval(INTERVAL_STILL)

        kalmanEstimator = KalmanEstimator()
        kalmanEstimator.onGpsUpdateRequired = {
            val pos = kalmanEstimator.getEstimatedPosition()
            val (estLat, estLon) = metersToLatLon(pos[0], pos[1])
            val latDrift = abs(estLat - (lastCorrectedLat ?: estLat))
            val lonDrift = abs(estLon - (lastCorrectedLon ?: estLon))
            val driftExceeded = latDrift > GPS_DRIFT_THRESHOLD_DEGREES || lonDrift > GPS_DRIFT_THRESHOLD_DEGREES

            val now = System.currentTimeMillis()
            if (driftExceeded && (now - lastGpsRequestTime > GPS_REQUEST_COOLDOWN_MS)) {
                lastGpsRequestTime = now
                //Log.d(TAG, "📍 Drift > threshold — requesting GPS fix")
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    requestInitialGpsFix()
                }
            } else {
                //Log.d(TAG, "🧭 Using predicted lat/lon: $estLat, $estLon")
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

            val accelMag = sqrt(linearAccel[0].pow(2) + linearAccel[1].pow(2) + linearAccel[2].pow(2))

            smoothedAccel[0] = smoothingAlpha * linearAccel[0] + (1 - smoothingAlpha) * smoothedAccel[0]
            smoothedAccel[1] = smoothingAlpha * linearAccel[1] + (1 - smoothingAlpha) * smoothedAccel[1]
            smoothedAccel[2] = smoothingAlpha * linearAccel[2] + (1 - smoothingAlpha) * smoothedAccel[2]

            if (currentTime - lastKalmanPredictionTime >= MIN_KALMAN_INTERVAL_MS) {
                kalmanEstimator.predict(smoothedAccel, currentTime)
                lastKalmanPredictionTime = currentTime
            }

            if (accelMag > MOVEMENT_THRESHOLD) {
                highCount++
                lowCount = 0
                transitionHandler.removeCallbacks(confirmStillRunnable)
                if (!isUserMoving && highCount >= REQUIRED_CONSECUTIVE_HIGHS) {
                    isUserMoving = true
                    updateCotInterval(INTERVAL_MOVING)
                    highCount = 0
                }
            } else {
                highCount = 0
                lowCount++
                if (isUserMoving && lowCount >= REQUIRED_CONSECUTIVE_LOWS) {
                    transitionHandler.postDelayed(confirmStillRunnable, stillConfirmDelay)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

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

                lastCorrectedLat = referenceLat!!.toFloat()
                lastCorrectedLon = referenceLon!!.toFloat()

                kalmanEstimator.reset(
                    floatArrayOf(0f, 0f, location.altitude.toFloat()),
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
        val dLat = x / 111320.0
        val dLon = y / (111320.0 * cos(Math.toRadians(lat0)))
        return Pair((lat0 + dLat).toFloat(), (lon0 + dLon).toFloat())
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
    }
}
