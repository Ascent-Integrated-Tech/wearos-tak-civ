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
import com.aitech.weartak_civ.BuildConfig
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.cep.di.CepProvider
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.math.min

class MovementDetectionService : Service(), SensorEventListener {

    // --- Sensors / manager ---
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var rotationVector: Sensor? = null

    // --- Data Layers ---
    private val repo by lazy { (applicationContext as WearTakApplication).repo as DataRepository }
    private val bhRepo by lazy { (applicationContext as WearTakApplication).bhRepo as BloodhoundRepository }
    private val cepEngine by lazy { CepProvider.getOrCreateEngine(applicationContext) }

    // --- WearTAK Service requirements: Binder / scope ---
    private val binder = MovementDetectionServiceBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // --- Movement detection state ---
    private val gravity = FloatArray(3)
    private var isUserMoving = false
    private val transitionHandler = Handler(Looper.getMainLooper())
    private var highCount = 0
    private var lowCount = 0
    private var lastEmitAtMs: Long = 0L
    private val EMIT_INTERVAL_MS: Long = 1000L  // at most 1 Hz to CEP

    // ---- Low-motion (accelerometer variance over rolling window) ----
    private val accelMagWindowSec = 5.0
    private val accelNominalFs = 50.0 // assume ~50 Hz; adaptive sizing later if desired
    private val accelBufSize = (accelMagWindowSec * accelNominalFs).toInt().coerceAtLeast(50)
    private val accelRing = DoubleArray(accelBufSize)
    private var accelIdx = 0
    private var accelCount = 0
    private var accelSum = 0.0
    private var accelSumSq = 0.0
    private var accelLowMotion = false
    // Hysteresis thresholds (tunable): variance in (m/s^2)^2
    private val VAR_ON = 0.05   // enter low-motion when below for ON_SEC
    private val VAR_OFF = 0.10  // exit low-motion when above for OFF_SEC
    private val ON_SEC = 5.0
    private val OFF_SEC = 2.0
    private var stateOnAccumSec = 0.0
    private var stateOffAccumSec = 0.0
    private var lastAccelSampleElapsedMs: Long = 0L

    // ---- Shiver (gyro band-pass 6–12 Hz, RMS over 2s) ----
    private val gyroNominalFs = 50.0
    private val bandCenterHz = 9.0
    private val bandQ = 1.0
    private val biquad = BiquadBandpass(gyroNominalFs, bandCenterHz, bandQ)
    private var shiverRmsWindowSec = 2.0
    private var rmsN = (shiverRmsWindowSec * gyroNominalFs).toInt().coerceAtLeast(10)
    private var sq = DoubleArray(rmsN)
    private var sqIdx = 0
    private var sqSum = 0.0
    private var shiverEnergy = 0.0
    private var energyBaseline = 1e-3
    private val alpha = 0.02  // EWMA baseline update
    private val R_ON = 2.5
    private val R_OFF = 1.8
    private var shiverPresent = false
    private var shiverOnDwellSec = 0.0
    private var shiverOffDwellSec = 0.0
    private var shiverDurationSec = 0.0
    private var lastGyroElapsedMs: Long = 0L

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
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        // Register listeners
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
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
            Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event.values, event.timestamp)
            Sensor.TYPE_GYROSCOPE -> handleGyroscope(event.values, event.timestamp)
            Sensor.TYPE_ROTATION_VECTOR -> handleRotationVector(event.values)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "MovementDetectionService onAccuracyChanged(): sensor(${sensor?.name}) accuracy($accuracy)")
        // If needed, you can gate outputs on low accuracy here.
    }

    // --- Accelerometer-based movement detection ---
    private fun handleAccelerometer(values: FloatArray, tsNanos: Long) {
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

        // --- Rolling variance for low-motion feature ---
        val prev = accelRing[accelIdx]
        accelSum -= prev
        accelSumSq -= prev * prev
        accelRing[accelIdx] = accelMag.toDouble()
        accelSum += accelRing[accelIdx]
        accelSumSq += accelRing[accelIdx] * accelRing[accelIdx]
        accelIdx = (accelIdx + 1) % accelBufSize
        if (accelCount < accelBufSize) accelCount++

        val n = max(1, accelCount)
        val mean = accelSum / n
        val variance = (accelSumSq / n) - (mean * mean)

        // Estimate dt from elapsedRealtime if available; otherwise nominal fs
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val dtSec = if (lastAccelSampleElapsedMs == 0L) (1.0 / accelNominalFs) else ((nowElapsed - lastAccelSampleElapsedMs) / 1000.0)
        lastAccelSampleElapsedMs = nowElapsed

        if (!accelLowMotion) {
            if (variance < VAR_ON) {
                stateOnAccumSec += dtSec
                if (stateOnAccumSec >= ON_SEC) {
                    accelLowMotion = true
                    stateOffAccumSec = 0.0
                    // immediate emit on state change
                    emitCepIfDue(force = true)
                }
            } else {
                stateOnAccumSec = 0.0
            }
        } else {
            if (variance > VAR_OFF) {
                stateOffAccumSec += dtSec
                if (stateOffAccumSec >= OFF_SEC) {
                    accelLowMotion = false
                    stateOnAccumSec = 0.0
                    // immediate emit on state change
                    emitCepIfDue(force = true)
                }
            } else {
                stateOffAccumSec = 0.0
            }
        }
    }

    private var lastPublishAtMs: Long = 0L
    private val PUBLISH_INTERVAL_MS: Long = 100L   // ~3 times/sec*/
    private var count: Long = 0L   // if you still want to track events
    // private var cachedDeclinationDeg: Double? = null // optional cache

    private fun handleGyroscope(values: FloatArray, tsNanos: Long) {
        // Magnitude of angular velocity (rad/s)
        val wx = values[0].toDouble()
        val wy = values[1].toDouble()
        val wz = values[2].toDouble()
        val wMag = sqrt(wx * wx + wy * wy + wz * wz)

        // Band-pass through 6–12 Hz centered ~9 Hz
        val y = biquad.process(wMag)

        // Rolling RMS over 2s (based on nominal fs)
        val y2 = y * y
        sqSum -= sq[sqIdx]
        sq[sqIdx] = y2
        sqSum += y2
        sqIdx = (sqIdx + 1) % rmsN
        val mean = sqSum / rmsN
        shiverEnergy = kotlin.math.sqrt(max(0.0, mean))

        // Update baseline only when in low-motion to reduce false positives
        if (accelLowMotion) {
            energyBaseline = (1 - alpha) * energyBaseline + alpha * shiverEnergy
        }
        val denom = max(energyBaseline, 1e-6)
        val ratio = shiverEnergy / denom

        // Timers via elapsedRealtime
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val dtSec = if (lastGyroElapsedMs == 0L) (1.0 / gyroNominalFs) else ((nowElapsed - lastGyroElapsedMs) / 1000.0)
        lastGyroElapsedMs = nowElapsed

        // Gate shiver detection by low-motion; use dwell timers and true ON-duration semantics
        if (accelLowMotion) {
            if (!shiverPresent) {
                if (ratio > R_ON) {
                    shiverOnDwellSec += dtSec
                    if (shiverOnDwellSec >= 2.5) {
                        shiverPresent = true
                        shiverOnDwellSec = 0.0
                        shiverOffDwellSec = 0.0
                        shiverDurationSec = 0.0
                        emitCepIfDue(force = true)
                    }
                } else {
                    shiverOnDwellSec = 0.0
                }
            } else {
                // shiver is ON: accumulate monotonic duration
                shiverDurationSec += dtSec
                if (ratio < R_OFF) {
                    shiverOffDwellSec += dtSec
                    if (shiverOffDwellSec >= 2.0) {
                        shiverPresent = false
                        shiverOnDwellSec = 0.0
                        shiverOffDwellSec = 0.0
                        shiverDurationSec = 0.0
                        emitCepIfDue(force = true)
                    }
                } else {
                    shiverOffDwellSec = 0.0
                }
            }
        } else {
            // Not in low-motion: block entry; if ON, require exit dwell to turn OFF
            shiverOnDwellSec = 0.0
            if (shiverPresent) {
                shiverOffDwellSec += dtSec
                if (shiverOffDwellSec >= 2.0) {
                    shiverPresent = false
                    shiverOnDwellSec = 0.0
                    shiverOffDwellSec = 0.0
                    shiverDurationSec = 0.0
                    emitCepIfDue(force = true)
                }
            }
        }

        // Periodic CEP emission (at most 1 Hz)
        emitCepIfDue(force = false)
    }

    private fun emitCepIfDue(force: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && (now - lastEmitAtMs) < EMIT_INTERVAL_MS) return
        lastEmitAtMs = now
        try {
            // Booleans: store as 1.0/0.0 for interoperability
            cepEngine.ingest("features.accel_low_motion", if (accelLowMotion) 1.0 else 0.0)
            cepEngine.ingest("features.shiver_present", if (shiverPresent) 1.0 else 0.0)
            cepEngine.ingest("features.shiver_duration_s", shiverDurationSec)
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "CEP ingest: accel_low_motion=$accelLowMotion shiver_present=$shiverPresent shiver_duration_s=${"%.1f".format(shiverDurationSec)}")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed CEP ingest (movement/shiver features)", t)
        }
    }

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
        azimuthMagDeg = normalize0to360(azimuthMagDeg)

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

        val azimuthTrue = normalize0to360(azimuthMagDeg + declinationDeg)

        // Smooth and publish at throttled rate
        val smoothed = lastAzimuthTrueDeg?.let {
            smoothAngle(it, azimuthTrue, HEADING_SMOOTH_ALPHA)
        } ?: azimuthTrue
        lastAzimuthTrueDeg = smoothed
        bhRepo.updateMagAzimuthTrue(smoothed)
    }
    // --- Helpers ---

    private fun normalize0to360(deg: Double): Double =
        ((deg % 360.0) + 360.0) % 360.0

    private fun smoothAngle(prevDeg: Double, newDeg: Double, alpha: Double): Double {
        var delta = newDeg - prevDeg
        if (delta > 180) delta -= 360.0
        if (delta < -180) delta += 360.0
        return normalize0to360(prevDeg + alpha * delta)
    }

    private var currentInterval = INTERVAL_STILL

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MovementDetectionService onDestroy()")
        isRunning.set(false)
        sensorManager.unregisterListener(this)
        bhRepo.updateMagAzimuthTrue(null) // clear compass needle
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

    // ---------- Minimal Biquad Band-pass for shiver detection ----------
    private class BiquadBandpass(
        private val fs: Double,
        private val f0: Double,   // center frequency
        private val q: Double     // quality factor
    ) {
        // Direct Form I coefficients/state
        private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0
        private var a1 = 0.0; private var a2 = 0.0
        private var x1 = 0.0; private var x2 = 0.0
        private var y1 = 0.0; private var y2 = 0.0

        init { recalc() }

        fun recalc() {
            val w0 = 2.0 * Math.PI * (f0 / fs)
            val cosw0 = Math.cos(w0)
            val sinw0 = Math.sin(w0)
            val alpha = sinw0 / (2.0 * q)

            val a0 = 1.0 + alpha
            b0 = alpha / a0
            b1 = 0.0 / a0
            b2 = -alpha / a0
            a1 = (-2.0 * cosw0) / a0
            a2 = (1.0 - alpha) / a0
        }

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            return y
        }

        fun reset() { x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0 }
    }
}