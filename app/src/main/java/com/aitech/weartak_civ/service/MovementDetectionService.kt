package com.aitech.weartak_civ.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aitech.weartak_civ.algorithm.KalmanEstimator
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class MovementDetectionService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager

    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    private var gravity = FloatArray(3)
    private var gyroValues = FloatArray(3)

    private var lastStepTime: Long = 0

    private var isUserMoving = false
    private var lastMovementTime: Long = 0L

    // Debounce control
    private var motionStartCandidateTime: Long = 0L
    private var stillStartCandidateTime: Long = 0L

    private val MOVEMENT_THRESHOLD = 8.0f

    // COT packet interval management
    private val INTERVAL_MOVING = 5000L    // 5 sec
    private val INTERVAL_STILL = 30000L    // 30 sec
    private var currentInterval = INTERVAL_STILL

    private val transitionHandler = Handler(Looper.getMainLooper())
    private val stillConfirmDelay = 5000L // 5 seconds

    private val HIGH_THRESHOLD = MOVEMENT_THRESHOLD
    private var lastHighTimestamp = 0L
    private val MAX_HIGH_INTERVAL = 1000L // max time between highs to be considered consecutive

    private var highCount = 0
    private var lowCount = 0
    private val REQUIRED_CONSECUTIVE_HIGHS = 3
    private val REQUIRED_CONSECUTIVE_LOWS = 3

    //KALMAN ESTIMATOR VARS
    private lateinit var kalmanEstimator: KalmanEstimator
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var lastCorrectedLat: Float? = null
    private var lastCorrectedLon: Float? = null
    private val DRIFT_THRESHOLD_DEGREES = 0.01f // ~111m per 0.001 degree
    private var smoothedAccel = FloatArray(3) { 0f }
    private val smoothingAlpha = 0.1f // you can tune this (lower = smoother)
    private var lastKalmanPredictionTime = 0L
    private val MIN_KALMAN_INTERVAL_MS = 100L // Adjust this to your needs (e.g. 100ms = 10Hz)


    private val TAG = "MovementDetectionService"

    private val confirmStillRunnable = Runnable {
        if (isUserMoving) {
            isUserMoving = false
            motionStartCandidateTime = 0L
            stillStartCandidateTime = 0L
            Log.d(TAG, "🛑 Confirmed STILL after delay")
            updateCotInterval(INTERVAL_STILL)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)

        startForeground(1, createNotification())

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        Log.d(TAG, "Movement detection service started")

        isUserMoving = false
        currentInterval = INTERVAL_STILL

        updateCotInterval(INTERVAL_STILL)

        // 🔄 Kalman Integration: Initialize estimator
        kalmanEstimator = KalmanEstimator()
        kalmanEstimator.onGpsUpdateRequired = {
            val pos = kalmanEstimator.getEstimatedPosition()
            val vel = kalmanEstimator.getEstimatedVelocity()
            val lat = pos[0]
            val lon = pos[1]

            val speedSquared = vel[0]*vel[0] + vel[1]*vel[1] + vel[2]*vel[2]
            val isEffectivelyStill = speedSquared < 1e-2 // sqrt(1e-6) ≈ 0.001 m/s

            if (isEffectivelyStill) {
                Log.d(TAG, "🛑 Skipping GPS update: velocity ≈ 0")
                //return
            }

            if (lastCorrectedLat != null && lastCorrectedLon != null) {
                val latDrift = kotlin.math.abs(lat - lastCorrectedLat!!)
                val lonDrift = kotlin.math.abs(lon - lastCorrectedLon!!)

                if (latDrift > DRIFT_THRESHOLD_DEGREES || lonDrift > DRIFT_THRESHOLD_DEGREES) {
                    Log.d(TAG, "📍 Drift > threshold and moving — requesting GPS fix")
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        requestInitialGpsFix()
                    }
                }
            } else {
                Log.d(TAG, "📍 No baseline yet — requesting first GPS fix")
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    requestInitialGpsFix()
                }
            }
        }


        // 🚀 Trigger initial GPS fix to reset Kalman with real coordinates
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            requestInitialGpsFix()
        } else {
            Log.w(TAG, "⚠️ Skipping initial GPS fix: fine location permission not granted")
        }

    }

    override fun onBind(intent: Intent): IBinder {
        TODO("Return the communication channel to the service.")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val currentTime = System.currentTimeMillis()

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // Gravity filtering
                val alpha = 0.8f
                gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
                gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
                gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]

                // Linear acceleration
                val linearAccel = FloatArray(3)
                linearAccel[0] = event.values[0] - gravity[0]
                linearAccel[1] = event.values[1] - gravity[1]
                linearAccel[2] = event.values[2] - gravity[2]

                val accelMag = sqrt(
                    linearAccel[0] * linearAccel[0] +
                            linearAccel[1] * linearAccel[1] +
                            linearAccel[2] * linearAccel[2]
                )

                // Low-pass filter
                smoothedAccel[0] = smoothingAlpha * linearAccel[0] + (1 - smoothingAlpha) * smoothedAccel[0]
                smoothedAccel[1] = smoothingAlpha * linearAccel[1] + (1 - smoothingAlpha) * smoothedAccel[1]
                smoothedAccel[2] = smoothingAlpha * linearAccel[2] + (1 - smoothingAlpha) * smoothedAccel[2]

                //val isPotentialStep = accelMag > MOVEMENT_THRESHOLD

                // 🔄 Throttled Kalman prediction
                if (currentTime - lastKalmanPredictionTime >= MIN_KALMAN_INTERVAL_MS) {
                    kalmanEstimator.predict(smoothedAccel, currentTime)
                    lastKalmanPredictionTime = currentTime

                    val pos = kalmanEstimator.getEstimatedPosition()
                    val vel = kalmanEstimator.getEstimatedVelocity()
                    Log.d(TAG, "🔄 Kalman Pos: ${pos.joinToString()} | Vel: ${vel.joinToString()}")
                }

                if (accelMag > MOVEMENT_THRESHOLD) {
                    highCount++
                    lowCount = 0

                    // Cancel any pending still confirmation
                    transitionHandler.removeCallbacks(confirmStillRunnable)

                    if (!isUserMoving && highCount >= REQUIRED_CONSECUTIVE_HIGHS) {
                        isUserMoving = true
                        updateCotInterval(INTERVAL_MOVING)
                        //Log.d(TAG, "✅ MOVING (3 consecutive HIGHs)")
                        highCount = 0
                    }
                } else {
                    highCount = 0
                    lowCount++

                    if (isUserMoving && lowCount >= REQUIRED_CONSECUTIVE_LOWS) {
                        //Log.d(TAG, "⏳ 3 LOWs detected. Scheduling STILL confirmation in 5s")
                        transitionHandler.postDelayed(confirmStillRunnable, stillConfirmDelay)
                    }
                }

                /*if (isPotentialStep) {
                    lastStepTime = currentTime
                    lastMovementTime = currentTime

                    // Cancel pending still confirmation
                    transitionHandler.removeCallbacks(confirmStillRunnable)

                    if (!isUserMoving) {
                        isUserMoving = true
                        motionStartCandidateTime = 0L
                        stillStartCandidateTime = 0L
                        Log.d(TAG, "✅ MOVING (immediate)")
                        updateCotInterval(INTERVAL_MOVING)
                    } else {
                        motionStartCandidateTime = 0L
                        stillStartCandidateTime = 0L
                    }
                } else {
                    if (isUserMoving) {
                        if (stillStartCandidateTime == 0L) {
                            stillStartCandidateTime = currentTime
                            transitionHandler.postDelayed(confirmStillRunnable, stillConfirmDelay)
                            Log.d(TAG, "⏳ Scheduling STILL confirmation in ${stillConfirmDelay}ms")
                        }
                    } else {
                        stillStartCandidateTime = 0L
                    }
                }*/
            }

            /*Sensor.TYPE_GYROSCOPE -> {
                gyroValues = event.values.clone()
            }

            Sensor.TYPE_STEP_DETECTOR -> {
                val steps = event.values[0].toInt() // Usually 1 per trigger
                Log.d("StepDetection", "Step detected! Steps = $steps")
            }*/
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    private fun updateCotInterval(newInterval: Long) {
        if (newInterval != currentInterval) {
            currentInterval = newInterval

            // Only update CotDispatchService if running
            if (CotDispatchService.isRunning.get()) {
                val newIntervalSecs = (newInterval / 1000L).toInt()
                if (CotDispatchService.currReportingIntervalInSecs != newIntervalSecs) {
                    CotDispatchService.currReportingIntervalInSecs = newIntervalSecs
                    //Log.d(TAG, "CotDispatchService interval updated to ${newIntervalSecs}s")
                }
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun requestInitialGpsFix() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 0
        ).apply {
            setMaxUpdates(1)
            setMinUpdateIntervalMillis(0)
            setWaitForAccurateLocation(true)
        }.build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation
                if (location != null) {
                    lastCorrectedLat = location.latitude.toFloat()
                    lastCorrectedLon = location.longitude.toFloat()

                    kalmanEstimator.reset(
                        floatArrayOf(location.latitude.toFloat(), location.longitude.toFloat(), location.altitude.toFloat()),
                        floatArrayOf(0f, 0f, 0f),
                        System.currentTimeMillis()
                    )
                    Log.d(TAG, "📍 Initial GPS fix: ${location.latitude}, ${location.longitude}, ${location.altitude}")
                } else {
                    Log.w(TAG, "⚠️ Initial GPS fix failed or null")
                }

                // Remove listener immediately
                fusedLocationClient.removeLocationUpdates(this)
            }
        }

        // Start one-time location update
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }


    /*private fun confirmMovement() {
        val now = System.currentTimeMillis()
        if (now - movementCandidateTimestamp >= movementDelay) {
            Log.d(TAG, "Confirmed: Moving (after delay)")
            isCurrentlyMoving = true
            lastMovementTimestamp = now
            resetStillnessCheck()
        }
    }

    private fun resetStillnessCheck() {
        Log.d(TAG, "Resetting stillness check timer")
        stillnessHandler.removeCallbacks(stillnessRunnable)
        stillnessHandler.postDelayed(stillnessRunnable, stillTimeout)
    }

    private fun checkStillness() {
        val now = System.currentTimeMillis()
        if (now - lastMovementTimestamp > stillTimeout) {
            if (isCurrentlyMoving) {
                Log.d(TAG, "Still (Timeout) — no movement for ${stillTimeout / 1000} seconds")
                isCurrentlyMoving = false
                // TODO: Notify listeners or broadcast still state
            } else {
                Log.d(TAG, "Still already confirmed")
            }
        } else {
            Log.d(TAG, "Movement detected within timeout — rescheduling check")
            resetStillnessCheck()
        }
    }*/

    override fun onDestroy() {
        super.onDestroy()

        sensorManager.unregisterListener(this)

        //cotRunnable?.let { handler.removeCallbacks(it) }
        isRunning.set(false)

        Log.d(TAG, "Movement detection service stopped")
    }

    private fun createNotification(): Notification {
        val channelId = "movement_channel"
        val channelName = "Movement Detection"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
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

    companion object {
        val isRunning = AtomicBoolean(false)
    }
}