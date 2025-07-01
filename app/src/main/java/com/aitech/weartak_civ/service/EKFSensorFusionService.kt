/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.hardware.*
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aitech.weartak_civ.Utils.writeToLogFileLocation
import com.aitech.weartak_civ.presentation.MainActivity.Constants.logFile
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.*

/**
 * Full EKF + Madgwick 9‑DOF fusion service.
 *
 *  State vector x[0..9]
 *  ┌─────────────┬─────────────────────────────────────────────┐
 *  │ Index       │ Meaning                                     │
 *  ├─────────────┼─────────────────────────────────────────────┤
 *  │ 0..2        │ Position  (m ‑ East-North-Up (ENU))         │
 *  │ 3..5        │ Velocity  (m/s ‑ ENU)                       │
 *  │ 6..9        │ Quaternion (q0,q1,q2,q3) – rotation from    │
 *  │             │                           body→ENU frame    │
 *  └─────────────┴─────────────────────────────────────────────┘
 */
// TODO: createProcessNoiseMatrix
class EKFSensorFusionService : Service(), SensorEventListener {

    /*‑‑ Types & constants ‑‑*/
    data class EKFState(
        val position: FloatArray = FloatArray(3),
        val velocity: FloatArray = FloatArray(3),
        val orientation: FloatArray = FloatArray(4) // [q0,q1,q2,q3]
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as EKFState

            if (!position.contentEquals(other.position)) return false
            if (!velocity.contentEquals(other.velocity)) return false
            if (!orientation.contentEquals(other.orientation)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = position.contentHashCode()
            result = 31 * result + velocity.contentHashCode()
            result = 31 * result + orientation.contentHashCode()
            return result
        }
    }

    private val TAG = "WearTAK-" + EKFSensorFusionService::class.java.simpleName
    private val STATE_SIZE = 10
    private val ORI_OFFSET = 6

    /*‑‑ Android sensors & runtime vars ‑‑*/
    private lateinit var sensorManager: SensorManager
    private val accel = FloatArray(3)
    private val gyro = FloatArray(3)
    private val magnet = FloatArray(3)

    /*‑‑ EKF state & covariances ‑‑*/
    private val x = FloatArray(STATE_SIZE) // [E,N,U, Vx,Vy,Vz, q0,q1,q2,q3]
    private val P = Array(STATE_SIZE) { FloatArray(STATE_SIZE) }

    /*‑‑ Dynamic accel‑noise window for process‑noise tuning ‑‑*/
    private val accelWindow = mutableListOf<Float>()
    private val windowSize = 50

    /*‑‑ 9‑DOF Madgwick filter – authoritative attitude measurement ‑‑*/
    // TODO: May need to increase beta based on linear acceleration
    // if (linearAccel > threshold) beta = 0.2f else beta = 0.1f
    private val madgwick = MadgwickAHRS(0.02f, 0.1f)

    // Timestamp bookkeeping (nanoseconds)
    private var lastTimestampNs: Long = -1L

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private fun calcDt(nowNs: Long): Float {
        if (lastTimestampNs < 0) {            // first sample
            lastTimestampNs = nowNs
            // Can also return 0
            return -1f
        }
        val dt = (nowNs - lastTimestampNs) / 1_000_000_000.0f
        lastTimestampNs = nowNs
        // Clamp to dodge rare OS hiccups or batching
        return dt.coerceIn(0.005f, 0.05f)     // 5ms to 50ms
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        requestGpsFix()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        // NOTE:
        // SENSOR_DELAY_GAME	~50 Hz	~0.02 seconds
        // SENSOR_DELAY_UI	    ~16 Hz	~0.0625 seconds
        //S ENSOR_DELAY_FASTEST	~100–200 Hz (or more)	~0.005–0.01 s (varies by device)
        // TODO: SENSOR_DELAY_NORMAL
        val delay = SensorManager.SENSOR_DELAY_GAME
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), delay)
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE), delay)
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD), delay)

        initState()
    }

    private fun initState() {
        x[ORI_OFFSET] = 1f  // identity quaternion
        for (i in P.indices) P[i][i] = 0.1f  // moderate initial uncertainty
    }

    /*‑‑ Android Sensor callbacks ‑‑*/
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER   -> {
                val logStr = "onSensorChanged: accel(${event.values[0]}, ${event.values[1]}, ${event.values[2]})"
                Log.d(TAG, logStr)
                writeToLogFileLocation(logFile, logStr)
                System.arraycopy(event.values, 0, accel, 0, 3)
            }
            Sensor.TYPE_GYROSCOPE       -> {
                val logStr = "onSensorChanged: gyro(${event.values[0]}, ${event.values[1]}, ${event.values[2]})"
                Log.d(TAG, logStr)
                writeToLogFileLocation(logFile, logStr)
                System.arraycopy(event.values, 0, gyro, 0, 3)
            }
            Sensor.TYPE_MAGNETIC_FIELD  -> {
                val logStr = "onSensorChanged: mag(${event.values[0]}, ${event.values[1]}, ${event.values[2]})"
                Log.d(TAG, logStr)
                writeToLogFileLocation(logFile, logStr)
                System.arraycopy(event.values, 0, magnet, 0, 3)
            }
        }

        // TODO: Do we updateFusion when any of the sensors change OR only on accelerometer
        val dt = calcDt(event.timestamp)
        if ((dt <= 0f) || accel.any { it == 0f } || gyro.any { it == 0f } || magnet.any { it == 0f }) return

        updateFusion(dt)
    }

    // TODO: Could use this to not update filter while accuracy of any of the sensors is bad
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        when (sensor?.type) {
            Sensor.TYPE_ACCELEROMETER   -> {val logStr = "onAccuracyChanged: accel($accuracy) w/ ${SensorManager.SENSOR_STATUS_ACCURACY_HIGH} for high accuracy"
                                            Log.d(TAG, logStr)
                                            writeToLogFileLocation(logFile, logStr)}
            Sensor.TYPE_GYROSCOPE       -> {val logStr = "onAccuracyChanged: gyroscope($accuracy) w/ ${SensorManager.SENSOR_STATUS_ACCURACY_HIGH} for high accuracy"
                                            Log.d(TAG, logStr)
                                            writeToLogFileLocation(logFile, logStr)}
            Sensor.TYPE_MAGNETIC_FIELD  -> {val logStr = "onAccuracyChanged: magnetometer($accuracy) w/ ${SensorManager.SENSOR_STATUS_ACCURACY_HIGH} for high accuracy"
                                            Log.d(TAG, logStr)
                                            writeToLogFileLocation(logFile, logStr)}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        super.onDestroy()
    }

    /*‑‑ Main fusion pipeline ‑‑*/
    private fun updateFusion(dt: Float) {
        // 1️⃣  Madgwick step – gives us an attitude measurement & gravity vector
        madgwick.samplePeriod = dt
        madgwick.update(
            gyro[0], gyro[1], gyro[2],
            accel[0], accel[1], accel[2],
            magnet[0], magnet[1], magnet[2]
        )
        val gravity = madgwick.getGravityVector()
        val linear = FloatArray(3) { i -> accel[i] - gravity[i] }
        val logStr = "updateFusion: linearAcc (${linear[0]}, ${linear[1]}, ${linear[2]})"
        Log.d(TAG, logStr)
        writeToLogFileLocation(logFile, logStr)

        // 2️⃣  EKF time‑update (predict)
        ekfPredict(linear, dt)

        // 3️⃣  EKF measurement‑update with attitude from Madgwick
        ekfUpdateOrientation(madgwick.getQuaternion())

        // Trigger GPS if uncertainty is too high
        if (shouldForceGps()) requestGpsFix()
        //shouldForceGps()
    }

    private fun requestGpsFix() {
        // TODO: hook into LocationService to send a broadcast to request a GPS sample
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 0).apply {
            setMaxUpdates(1)
        }.build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                /*
                referenceLat = loc.latitude
                referenceLon = loc.longitude
                referenceAlt = loc.altitude

                 */

                // TODO: What to do & where to store GPS fix?
                // TODO: Need to do a EKF update
                //kalmanEstimator.reset(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f), System.currentTimeMillis())

                /*
                lastPredictedLocation = Location("initial_gps_fix").apply {
                    latitude = loc.latitude
                    longitude = loc.longitude
                    altitude = loc.altitude
                    time = System.currentTimeMillis()
                }

                 */

                val logStr = "GPS Fix: lat=${loc.latitude}, lon=${loc.longitude}, alt=${loc.altitude}"
                Log.d(TAG, logStr)
                writeToLogFileLocation(logFile, logStr)
                fusedLocationClient.removeLocationUpdates(this)
            }
        }
        if ((ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) ||
            (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)) {
            fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        }
    }

    /*‑‑ EKF predict step ‑‑*/
    private fun ekfPredict(a: FloatArray, dt: Float) {
        /* 2.1 translate‑motion model (constant acceleration) */
        for (i in 0..2) {
            x[i] += x[i + 3] * dt + 0.5f * a[i] * dt * dt // pos += vel * dt + 0.5 a*dt*dt
            x[i + 3] += a[i] * dt  // vel += a*dt; TODO: why is velocity updated after position updated
        }

        /* 2.2 attitude – integrate gyro → quaternion */
        integrateGyro(dt)

        /* 2.3 covariance propagation (very simplified) */
        val accelNoise = dynamicAccelNoise()
        val dt2 = dt * dt
        val accelVar = accelNoise * accelNoise
        for (i in 0..2) {
            // Pos‑pos
            P[i][i] += 0.25f * dt2 * dt2 * accelVar
            // Pos‑vel and vel‑pos
            P[i][i + 3] += 0.5f * dt2 * accelVar
            P[i + 3][i] = P[i][i + 3]
            // Vel‑vel
            P[i + 3][i + 3] += dt2 * accelVar
        }
        // orientation diagonal grow with gyro noise
        val gyroNoise = 0.0025f
        val oriVar = gyroNoise * gyroNoise * dt
        for (i in ORI_OFFSET until ORI_OFFSET + 4) P[i][i] += oriVar
    }

    /*‑‑ EKF orientation measurement update (Madgwick quaternion) ‑‑*/
    private fun ekfUpdateOrientation(qMeasRaw: FloatArray) {
        // Resolve quaternion sign ambiguity
        val qMeas = qMeasRaw.copyOf()
        val dot = qMeas[0] * x[ORI_OFFSET] + qMeas[1] * x[ORI_OFFSET+1] +
                qMeas[2] * x[ORI_OFFSET+2] + qMeas[3] * x[ORI_OFFSET+3]
        if (dot < 0) for (i in 0..3) qMeas[i] = -qMeas[i]

        // Scalar measurement model z = Hx,  H selects quaternion components.
        val r = 0.0025f
        for (k in 0..3) {
            val idx = ORI_OFFSET + k
            val error = qMeas[k] - x[idx]
            val denom = P[idx][idx] + r
            if (denom == 0f) continue
            val K = FloatArray(STATE_SIZE) { j -> P[j][idx] / denom }
            // state update
            for (j in 0 until STATE_SIZE) x[j] += K[j] * error
            // covariance update (simple Joseph form omitted for brev.)
            for (j in 0 until STATE_SIZE) P[j][idx] *= (1f - K[idx])
        }
        normalizeQuaternion()
    }

    /*‑‑ Helpers – gyro integration & quaternion utils ‑‑*/
    private fun integrateGyro(dt: Float) {
        val q0 = x[ORI_OFFSET]
        val q1 = x[ORI_OFFSET + 1]
        val q2 = x[ORI_OFFSET + 2]
        val q3 = x[ORI_OFFSET + 3]
        val wx = gyro[0]; val wy = gyro[1]; val wz = gyro[2]
        val halfDt = 0.5f * dt
        val dq0 = -halfDt * (q1 * wx + q2 * wy + q3 * wz)
        val dq1 =  halfDt * (q0 * wx + q2 * wz - q3 * wy)
        val dq2 =  halfDt * (q0 * wy - q1 * wz + q3 * wx)
        val dq3 =  halfDt * (q0 * wz + q1 * wy - q2 * wx)
        val qNew0 = q0 + dq0
        val qNew1 = q1 + dq1
        val qNew2 = q2 + dq2
        val qNew3 = q3 + dq3
        val norm = 1f / sqrt(qNew0 * qNew0 + qNew1 * qNew1 + qNew2 * qNew2 + qNew3 * qNew3)
        x[ORI_OFFSET]     = qNew0 * norm
        x[ORI_OFFSET + 1] = qNew1 * norm
        x[ORI_OFFSET + 2] = qNew2 * norm
        x[ORI_OFFSET + 3] = qNew3 * norm
    }

    private fun normalizeQuaternion() {
        val q0 = x[ORI_OFFSET]
        val q1 = x[ORI_OFFSET + 1]
        val q2 = x[ORI_OFFSET + 2]
        val q3 = x[ORI_OFFSET + 3]
        val norm = 1f / sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
        x[ORI_OFFSET]     = q0 * norm
        x[ORI_OFFSET + 1] = q1 * norm
        x[ORI_OFFSET + 2] = q2 * norm
        x[ORI_OFFSET + 3] = q3 * norm
    }

    private fun dynamicAccelNoise(): Float {
        val magnitude = sqrt(accel.map { it * it }.sum())
        accelWindow.add(magnitude)
        if (accelWindow.size > windowSize) accelWindow.removeAt(0)
        val mean = accelWindow.average().toFloat()
        return sqrt(accelWindow.map { (it - mean).pow(2) }.average().toFloat())
    }

    /*‑‑ GPS update (unchanged, still position‑only) ‑‑*/
    fun ekfUpdateGPS(gpsPos: FloatArray, hAcc: Float, vAcc: Float) {
        val R = floatArrayOf(hAcc * hAcc, hAcc * hAcc, vAcc * vAcc)
        for (i in 0..2) {
            val error = gpsPos[i] - x[i]
            val denom = P[i][i] + R[i]
            if (denom == 0f) continue
            val K = FloatArray(STATE_SIZE) { j -> P[j][i] / denom }
            for (j in 0 until STATE_SIZE) x[j] += K[j] * error
            for (j in 0 until STATE_SIZE) P[j][i] *= (1f - K[i])
        }
    }

    /*
    private fun shouldForceGps(): Boolean {
        val posErr = sqrt(P[0][0] + P[1][1] + P[2][2])
        val yawUnc = sqrt(P[9][9])                // ~σ of yaw component
        return posErr > 8f || yawUnc > 0.35f
    }

     */

    /*‑‑ Logic to decide when to wake GPS ‑‑*/
    fun shouldForceGps(): Boolean {
        val posErr = sqrt(P[0][0] + P[1][1] + P[2][2])
        val velErr = sqrt(P[3][3] + P[4][4] + P[5][5])
        val yawUncert = sqrt(P[ORI_OFFSET + 3][ORI_OFFSET + 3]) // crude yaw σ
        val speed = sqrt(x[3] * x[3] + x[4] * x[4] + x[5] * x[5])
        val logStr = "shouldForceGps: posErr($posErr), velErr($velErr), yawUncert($yawUncert), speed($speed)"
        Log.d(TAG, logStr)
        writeToLogFileLocation(logFile, logStr)
        return posErr > 8f || velErr > 3f || yawUncert > 0.35f || speed > 5f
    }

    /*‑‑ Convenience helpers ‑‑*/
    fun getState(): EKFState = EKFState(
        position = x.sliceArray(0..2),
        velocity = x.sliceArray(3..5),
        orientation = x.sliceArray(ORI_OFFSET until ORI_OFFSET + 4)
    )

    fun getCurrentPositionAsLatLng(startLat: Double, startLng: Double): Pair<Double, Double> {
        val earthRadius = 6_378_137.0
        val dLat = x[1].toDouble() / earthRadius
        val dLng = x[0].toDouble() / (earthRadius * cos(Math.toRadians(startLat)))
        return Pair(startLat + Math.toDegrees(dLat), startLng + Math.toDegrees(dLng))
    }

    /*‑‑ Foreground notification boilerplate ‑‑*/
    private fun startForegroundService() {
        val channelId = "EKFFusionChannel"
        val channel = NotificationChannel(channelId, "EKF Fusion Service", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("EKF Sensor Fusion Running")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(1, notification)
    }
}

/*────────────────────────────────────────────────────────────────────────────*/
/* MADGWICK 9‑DOF Attitude and Heading Reference System (AHRS)                */
/*────────────────────────────────────────────────────────────────────────────*/
class MadgwickAHRS(var samplePeriod: Float = 1f / 256f, private var beta: Float = 0.1f) {
    private var q0 = 1f; private var q1 = 0f; private var q2 = 0f; private var q3 = 0f

    fun update(
        gx: Float, gy: Float, gz: Float,
        ax: Float, ay: Float, az: Float,
        mx: Float, my: Float, mz: Float
    ) {
        var axn = ax; var ayn = ay; var azn = az
        var mxn = mx; var myn = my; var mzn = mz
        if (axn == 0f && ayn == 0f && azn == 0f) return // accel invalid

        /* normalise accel */
        var recipNorm = invSqrt(axn * axn + ayn * ayn + azn * azn)
        axn *= recipNorm; ayn *= recipNorm; azn *= recipNorm

        /* normalise mag */
        if (!(mxn == 0f && myn == 0f && mzn == 0f)) {
            recipNorm = invSqrt(mxn * mxn + myn * myn + mzn * mzn)
            mxn *= recipNorm; myn *= recipNorm; mzn *= recipNorm
        }

        /* auxiliary variables */
        val _2q0mx = 2f * q0 * mxn
        val _2q0my = 2f * q0 * myn
        val _2q0mz = 2f * q0 * mzn
        val _2q1mx = 2f * q1 * mxn
        val _2q0 = 2f * q0; val _2q1 = 2f * q1; val _2q2 = 2f * q2; val _2q3 = 2f * q3
        val q0q0 = q0 * q0; val q0q1 = q0 * q1; val q0q2 = q0 * q2; val q0q3 = q0 * q3
        val q1q1 = q1 * q1; val q1q2 = q1 * q2; val q1q3 = q1 * q3
        val q2q2 = q2 * q2; val q2q3 = q2 * q3; val q3q3 = q3 * q3

        /* reference direction of Earth mag */
        val hx = mxn * q0q0 - _2q0my * q3 + _2q0mz * q2 + mxn * q1q1 + _2q1 * myn * q2 + _2q1 * mzn * q3 - mxn * q2q2 - mxn * q3q3
        val hy = _2q0mx * q3 + myn * q0q0 - _2q0mz * q1 + _2q1mx * q2 - myn * q1q1 + myn * q2q2 + _2q2 * mzn * q3 - myn * q3q3
        var _2bx = sqrt(hx * hx + hy * hy)
        var _2bz = -_2q0mx * q2 + _2q0my * q1 + mzn * q0q0 + _2q1mx * q3 - mzn * q1q1 + _2q2 * myn * q3 - mzn * q2q2 + mzn * q3q3
        _2bx *= 2f; _2bz *= 2f

        /* gradient descent corrective step */
        val s0 = -_2q2 * (2f * (q1q3 - q0q2) - axn) + _2q1 * (2f * (q0q1 + q2q3) - ayn) - _2bz * q2 * (_2bx * (0.5f - q2q2 - q3q3) + _2bz * (q1q3 - q0q2) - mxn) + (-_2bx * q3 + _2bz * q1) * (_2bx * (q1q2 - q0q3) + _2bz * (q0q1 + q2q3) - myn) + _2bx * q2 * (_2bx * (q0q2 + q1q3) + _2bz * (0.5f - q1q1 - q2q2) - mzn)
        val s1 =  _2q3 * (2f * (q1q3 - q0q2) - axn) + _2q0 * (2f * (q0q1 + q2q3) - ayn) - 4f * q1 * (1f - 2f * (q1q1 + q2q2) - azn) + _2bz * q3 * (_2bx * (0.5f - q2q2 - q3q3) + _2bz * (q1q3 - q0q2) - mxn) + (_2bx * q2 + _2bz * q0) * (_2bx * (q1q2 - q0q3) + _2bz * (q0q1 + q2q3) - myn) + (_2bx * q3 - _2bz * q1) * (_2bx * (q0q2 + q1q3) + _2bz * (0.5f - q1q1 - q2q2) - mzn)
        val s2 = -_2q0 * (2f * (q1q3 - q0q2) - axn) + _2q3 * (2f * (q0q1 + q2q3) - ayn) - 4f * q2 * (1f - 2f * (q1q1 + q2q2) - azn) + (-_2bx * q2 - _2bz * q0) * (_2bx * (0.5f - q2q2 - q3q3) + _2bz * (q1q3 - q0q2) - mxn) + (_2bx * q1 + _2bz * q3) * (_2bx * (q1q2 - q0q3) + _2bz * (q0q1 + q2q3) - myn) + (_2bx * q0 - _2bz * q2) * (_2bx * (q0q2 + q1q3) + _2bz * (0.5f - q1q1 - q2q2) - mzn)
        val s3 =  _2q1 * (2f * (q1q3 - q0q2) - axn) + _2q2 * (2f * (q0q1 + q2q3) - ayn) + (-_2bx * q3 + _2bz * q1) * (_2bx * (0.5f - q2q2 - q3q3) + _2bz * (q1q3 - q0q2) - mxn) + (-_2bx * q0 + _2bz * q2) * (_2bx * (q1q2 - q0q3) + _2bz * (q0q1 + q2q3) - myn) + _2bx * q1 * (_2bx * (q0q2 + q1q3) + _2bz * (0.5f - q1q1 - q2q2) - mzn)

        recipNorm = invSqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3)
        val s0n = s0 * recipNorm; val s1n = s1 * recipNorm; val s2n = s2 * recipNorm; val s3n = s3 * recipNorm

        /* rate of change */
        val qDot0 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz) - beta * s0n
        val qDot1 = 0.5f * ( q0 * gx + q2 * gz - q3 * gy) - beta * s1n
        val qDot2 = 0.5f * ( q0 * gy - q1 * gz + q3 * gx) - beta * s2n
        val qDot3 = 0.5f * ( q0 * gz + q1 * gy - q2 * gx) - beta * s3n

        q0 += qDot0 * samplePeriod
        q1 += qDot1 * samplePeriod
        q2 += qDot2 * samplePeriod
        q3 += qDot3 * samplePeriod

        /* normalise */
        recipNorm = invSqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
        q0 *= recipNorm; q1 *= recipNorm; q2 *= recipNorm; q3 *= recipNorm
    }

    fun getQuaternion(): FloatArray = floatArrayOf(q0, q1, q2, q3)

    // q0 = w; q1: x; q2: y; q3 = z
    // floatArrayOf(
    //        2f * (q.x * q.z - q.w * q.y),
    //        2f * (q.w * q.x + q.y * q.z),
    //        q.w * q.w - q.x * q.x - q.y * q.y + q.z * q.z
    //    )
    fun getGravityVector(): FloatArray = floatArrayOf(
        2f * (q1 * q3 - q0 * q2),
        2f * (q0 * q1 + q2 * q3),
        q0 * q0 - q1 * q1 - q2 * q2 + q3 * q3
    )

    private fun invSqrt(x: Float) = 1f / kotlin.math.sqrt(x)
}