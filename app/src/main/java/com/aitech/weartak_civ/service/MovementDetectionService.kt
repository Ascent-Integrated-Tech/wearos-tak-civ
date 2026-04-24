/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.provider.LocationVectorCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class MovementDetectionService : Service(), SensorEventListener {

    // --- Sensors / manager ---
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var rotationVector: Sensor? = null

    // --- Data Layers ---
    private val repo by lazy { (applicationContext as WearTakApplication).repo as DataRepository }
    // --- WearTAK Service requirements: Binder / scope ---
    private val binder = MovementDetectionServiceBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // --- Movement detection state ---
    private val gravity = FloatArray(3)
    private var isUserMoving = false
    private val transitionHandler = Handler(Looper.getMainLooper())
    private var highCount = 0
    private var lowCount = 0

    // --- Heading state ---
    private var lastAzimuthTrueDeg: Double? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MovementDetectionService onCreate()")
        isRunning.set(true)

        startForegroundServiceCompat(
            notificationId = MOVEMENT_DETECTION_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )

        // Init sensors
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        // Register listeners
//        accelerometer?.let { sensorManager.registerListener(this, it, ACCELEROMETER_DELAY_US) } // not currently using for compass
        rotationVector?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }

        // Initial state
        isUserMoving = false
        highCount = 0
        lowCount = 0
        // currentInterval = INTERVAL_STILL
        // updateCotInterval(isStill = true, newInterval = INTERVAL_STILL)
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d(TAG, "MovementDetectionService onStartCommand()")
        return START_NOT_STICKY
    }

    inner class MovementDetectionServiceBinder : Binder() {
        val service: MovementDetectionService
            get() = this@MovementDetectionService
    }

    override fun onBind(intent: Intent): IBinder = binder

    // --- SensorEventListener ---

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        when (event.sensor.type) {
//            Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event.values)
            Sensor.TYPE_ROTATION_VECTOR -> handleRotationVector(event.values)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "MovementDetectionService onAccuracyChanged(): sensor(${sensor?.name}) accuracy($accuracy)")
        // If needed, you can gate outputs on low accuracy here.
    }

    // --- Accelerometer-based movement detection ---
    private fun handleAccelerometer(values: FloatArray) {
        // Simple gravity filter
        val alpha = 0.8f
        gravity[0] = alpha * gravity[0] + (1 - alpha) * values[0]
        gravity[1] = alpha * gravity[1] + (1 - alpha) * values[1]
        gravity[2] = alpha * gravity[2] + (1 - alpha) * values[2]

        // Linear acceleration magnitude
        val ax = values[0] - gravity[0]
        val ay = values[1] - gravity[1]
        val az = values[2] - gravity[2]
        val accelMag = sqrt(ax * ax + ay * ay + az * az)

        if (accelMag > MOVEMENT_THRESHOLD) {
            highCount++
            lowCount = 0
            // Cancel pending still confirmation
            transitionHandler.removeCallbacks(confirmStillRunnable)

            if (!isUserMoving && highCount >= REQUIRED_CONSECUTIVE_HIGHS) {
                isUserMoving = true
                Log.d(TAG, "MovementDetectionService ✅ MOVING ($REQUIRED_CONSECUTIVE_HIGHS consecutive HIGHs)")
                // updateCotInterval(isStill = false, newInterval = INTERVAL_MOVING)
                highCount = 0
            }
        } else {
            highCount = 0
            lowCount++

            if (isUserMoving && lowCount >= REQUIRED_CONSECUTIVE_LOWS) {
                if (lowCount == REQUIRED_CONSECUTIVE_LOWS) {
                    Log.d(TAG, "MovementDetectionService ⏳ $REQUIRED_CONSECUTIVE_LOWS LOWs, confirming STILL in $STILL_CONFIRM_DELAY_MS ms")
                    transitionHandler.removeCallbacks(confirmStillRunnable)
                    transitionHandler.postDelayed(confirmStillRunnable, STILL_CONFIRM_DELAY_MS)
                }
            }
        }
    }

    private val confirmStillRunnable = Runnable {
        if (isUserMoving) {
            isUserMoving = false
            Log.d(TAG, "MovementDetectionService 🛑 Confirmed STILL")
            // updateCotInterval(isStill = true, newInterval = INTERVAL_STILL)
        }
    }
    private var lastPublishAtMs: Long = 0L
    private val PUBLISH_INTERVAL_MS: Long = 100L   // ~3 times/sec
    private var count: Long = 0L   // if you still want to track events
// private var cachedDeclinationDeg: Double? = null // optional cache

    private fun handleRotationVector(values: FloatArray) {
        count += 1

        // Throttle: only proceed ~3 Hz (monotonic clock)
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (nowMs - lastPublishAtMs < PUBLISH_INTERVAL_MS) return
        lastPublishAtMs = nowMs

        // Build rotation matrix
        val R = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(R, values)

        // Orientation angles (azimuth/pitch/roll)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(R, orientation)

        // Magnetic azimuth (radians → degrees)
        var azimuthMagDeg = Math.toDegrees(orientation[0].toDouble())
        azimuthMagDeg = LocationVectorCalculator.normalize0to360(azimuthMagDeg)

        // Add declination from latest location -> TRUE north
        val loc = repo.latestLocation.value
        val declinationDeg = if (loc != null) {
            GeomagneticField(
                loc.latitude.toFloat(),
                loc.longitude.toFloat(),
                (if (loc.hasAltitude()) loc.altitude else 0.0).toFloat(),
                System.currentTimeMillis()
            ).declination.toDouble()
        } else 0.0

        val azimuthTrue = LocationVectorCalculator.normalize0to360(azimuthMagDeg + declinationDeg)

        // Smooth and publish at throttled rate
        val smoothed = lastAzimuthTrueDeg?.let {
            LocationVectorCalculator.smoothAngle(it, azimuthTrue, HEADING_SMOOTH_ALPHA)
        } ?: azimuthTrue
        lastAzimuthTrueDeg = smoothed
        repo.updateMagAzimuthTrue(smoothed)
    }

    private var currentInterval = INTERVAL_STILL

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MovementDetectionService onDestroy()")
        isRunning.set(false)
        sensorManager.unregisterListener(this)
        repo.updateMagAzimuthTrue(null)
        scope.coroutineContext.cancel()
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    // --- Required by your manager / project conventions ---
    companion object {
        val isRunning = AtomicBoolean(false)
        private val TAG = "WearTAK-MvmtService"

        private const val MOVEMENT_DETECTION_SERVICE_NOTIFICATION_ID = 112
        private const val ACCELEROMETER_DELAY_US = 100_000 // 100 ms (~10 Hz)

        // Movement thresholds
        private const val MOVEMENT_THRESHOLD = 8.0f
        private const val REQUIRED_CONSECUTIVE_HIGHS = 3
        private const val REQUIRED_CONSECUTIVE_LOWS = 3
        private const val STILL_CONFIRM_DELAY_MS = 5_000L

        // (Optional) CoT intervals
        private const val INTERVAL_MOVING = 5   // seconds
        private const val INTERVAL_STILL = 30   // seconds

        // Heading smoothing
        private const val HEADING_SMOOTH_ALPHA = 0.15
    }
}
