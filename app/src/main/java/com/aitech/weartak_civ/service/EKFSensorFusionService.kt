/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service

// Foreground Service that ALSO implements SensorEventListener.
// - Collects raw sensor data (gyro, accel, mag, baro) on a HandlerThread
// - Forwards data into EKFSensorFusion core via handle* methods
// - Exposes fused updates via Callback *and* raw events via RawCallback for reuse elsewhere.
// - Subscribes to FusedLocationProviderClient for GNSS pos+vel and computes world mag reference via GeomagneticField.

import android.app.*
import android.content.*
import android.hardware.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aitech.weartak_civ.algorithms.EKFSensorFusion
import com.google.android.gms.location.*
import kotlin.math.*

class EKFSensorFusionService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "WearTAK-EKFService"
        private const val CHANNEL_ID = "ekf_fusion_channel"
        private const val NOTIF_ID = 42
        private const val UPDATE_MS = 200L

        // --- GNSS duty-cycling knobs ---
        private const val FORCE_BURST_INTERVAL_MS = 180_000L   // safety burst every 3 minutes
        private const val BURST_MAX_DURATION_MS = 3_000L       // cap burst length
        private const val BURST_MIN_ELAPSED_MS = 750L          // avoid stop-too-soon after subscribe
        private const val BURST_MIN_FIXES = 1                  // stop after first fix (if elapsed satisfied)
    }

    // Binder
    inner class LocalBinder : Binder() { fun getService(): EKFSensorFusionService = this@EKFSensorFusionService }
    private val binder = LocalBinder()

    // Fused-state callback
    interface Callback { fun onFusionUpdate(state: FusionState) }
    data class FusionState(
        val e: Double, val n: Double, val u: Double,
        val vE: Double, val vN: Double, val vU: Double,
        val q: DoubleArray,
        val bg: DoubleArray, val ba: DoubleArray,
        val lat: Double?, val lon: Double?, val alt: Double?
    )

    // Raw sensor/GNSS events for other use cases
    sealed class RawEvent {
        data class Gyro(val tsNs: Long, val wx: Double, val wy: Double, val wz: Double): RawEvent()
        data class Accel(val tsNs: Long, val ax: Double, val ay: Double, val az: Double): RawEvent()
        data class Mag(val tsNs: Long, val mx: Double, val my: Double, val mz: Double, val status: Int): RawEvent()
        data class Baro(val tsNs: Long, val pressure_hPa: Double): RawEvent()
        data class GpsPosition(val lat: Double, val lon: Double, val alt: Double, val hAcc: Double, val vAcc: Double): RawEvent()
        data class GpsVelocity(val vE: Double, val vN: Double, val sv: Double): RawEvent()
    }
    interface RawCallback { fun onRaw(event: RawEvent) }

    private val stateListeners = mutableSetOf<Callback>()
    private val rawListeners = mutableSetOf<RawCallback>()

    // Android sensors
    private lateinit var sensorManager: SensorManager
    private var gyro: Sensor? = null
    private var accel: Sensor? = null
    private var mag: Sensor? = null
    private var baro: Sensor? = null

    // EKF core
    private lateinit var ekf: EKFSensorFusion

    // GNSS
    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // Duty-cycling state
    private var gnssActive = false
    private var burstFixCount = 0
    private var burstStartNs = 0L
    private var lastGnssFixNs = 0L

    private fun elapsedMsSince(ns: Long, nowNs: Long): Long =
        if (ns == 0L) Long.MAX_VALUE else (nowNs - ns) / 1_000_000L

    // Threads
    private lateinit var sensorThread: HandlerThread
    private lateinit var sensorHandler: Handler

    private val mainHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            // Push state to listeners like before
            pushStateToListeners()

            // --- GNSS duty-cycle decision ---
            val nowNs = android.os.SystemClock.elapsedRealtimeNanos()
            val needByUncertainty = ekf.needsGps()
            val needByTimeout = elapsedMsSince(lastGnssFixNs, nowNs) >= FORCE_BURST_INTERVAL_MS

            if (!gnssActive && (needByUncertainty || needByTimeout)) {
                startGnssBurst(nowNs)
            }

            if (gnssActive) {
                val burstElapsedMs = elapsedMsSince(burstStartNs, nowNs)
                val okToStopByFix = (burstFixCount >= BURST_MIN_FIXES) && (burstElapsedMs >= BURST_MIN_ELAPSED_MS)
                val hitMaxDuration = burstElapsedMs >= BURST_MAX_DURATION_MS
                if (okToStopByFix || hitMaxDuration) {
                    stopGnssBurst()
                }
            }

            // Schedule next tick
            mainHandler.postDelayed(this, UPDATE_MS)
        }
    }

    private fun startGnssBurst(nowNs: Long = android.os.SystemClock.elapsedRealtimeNanos()) {
        if (gnssActive) return
        gnssActive = true
        burstFixCount = 0
        burstStartNs = nowNs

        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, /* interval */ 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(true)
            .build()

        try {
            fusedClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
            Log.d(TAG, "GNSS burst START")
        } catch (se: SecurityException) {
            Log.e(TAG, "Missing location permission", se)
        }
    }

    private fun stopGnssBurst() {
        if (!gnssActive) return
        try { fusedClient.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
        gnssActive = false
        Log.d(TAG, "GNSS burst STOP")
    }


    override fun onCreate() {
        super.onCreate()
        ekf = EKFSensorFusion()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        baro = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Initializing sensor fusion…"))

        // Sensor thread
        sensorThread = HandlerThread("ekf-sensors", Process.THREAD_PRIORITY_MORE_FAVORABLE)
        sensorThread.start()
        sensorHandler = Handler(sensorThread.looper)

        // Register sensors (explicit µs rates)
        register(gyro, 10_000)
        register(accel, 10_000)
        register(mag, 50_000)
        register(baro, 100_000)

        // GNSS subscription
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) {
                    val nowNs = android.os.SystemClock.elapsedRealtimeNanos()
                    lastGnssFixNs = nowNs
                    burstFixCount += 1

                    val lat = loc.latitude
                    val lon = loc.longitude
                    val alt = if (loc.hasAltitude()) loc.altitude else Double.NaN
                    val hAcc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 8.0
                    val vAcc = if (android.os.Build.VERSION.SDK_INT >= 26 && loc.hasVerticalAccuracy())
                        loc.verticalAccuracyMeters.toDouble() else Double.NaN

                    // Feed EKF (position)
                    ekf.updateWithGps(lat, lon, alt, hAcc, vAcc)
                    dispatchRaw(RawEvent.GpsPosition(lat, lon, alt, hAcc, vAcc))

                    // Set world magnetic reference (GeomagneticField -> ENU)
                    try {
                        val gf = android.hardware.GeomagneticField(
                            lat.toFloat(), lon.toFloat(), alt.toFloat(), System.currentTimeMillis()
                        )
                        val enuX = gf.y.toDouble()      // east
                        val enuY = gf.x.toDouble()      // north
                        val enuZ = -gf.z.toDouble()     // up (negate D)
                        ekf.setWorldMagReference(enuX, enuY, enuZ, gf.fieldStrength.toDouble())
                    } catch (_: Throwable) { }

                    // Feed EKF (ground speed/bearing -> ENU velocity)
                    if (loc.hasSpeed() && loc.hasBearing()) {
                        val sp = loc.speed.toDouble()
                        val br = Math.toRadians(loc.bearing.toDouble())
                        val vN = sp * kotlin.math.cos(br)
                        val vE = sp * kotlin.math.sin(br)
                        val sv = if (android.os.Build.VERSION.SDK_INT >= 26 && loc.hasSpeedAccuracy())
                            kotlin.math.max(0.5, loc.speedAccuracyMetersPerSecond.toDouble()) else 1.0
                        ekf.updateWithGnssVelocity(vE, vN, sv)
                        dispatchRaw(RawEvent.GpsVelocity(vE, vN, sv))
                    }
                }
            }
        }

        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(false)
            .build()

        mainHandler.postDelayed(updateRunnable, UPDATE_MS)
    }

    private fun register(s: Sensor?, rateUs: Int) {
        if (s == null) return
        sensorManager.registerListener(this, s, rateUs, rateUs, sensorHandler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("subtitle")?.let { subtitle ->
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(subtitle))
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun registerListener(l: Callback) { stateListeners.add(l); pushStateTo(l) }
    fun unregisterListener(l: Callback) { stateListeners.remove(l) }

    fun registerRawListener(l: RawCallback) { rawListeners.add(l) }
    fun unregisterRawListener(l: RawCallback) { rawListeners.remove(l) }

    private fun pushStateToListeners() { for (l in stateListeners) pushStateTo(l) }
    private fun pushStateTo(l: Callback) {
        val s = ekf.snapshot()
        l.onFusionUpdate(FusionState(s.e, s.n, s.u, s.vE, s.vN, s.vU, s.q, s.bg, s.ba, s.lat, s.lon, s.alt))
    }

    private fun dispatchRaw(ev: RawEvent) { for (l in rawListeners) l.onRaw(ev) }

    // --- SensorEventListener ---
    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> {
                val wx = e.values[0].toDouble(); val wy = e.values[1].toDouble(); val wz = e.values[2].toDouble()
                ekf.handleGyro(e.timestamp, wx, wy, wz)
                dispatchRaw(RawEvent.Gyro(e.timestamp, wx, wy, wz))
            }
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> {
                val ax = e.values[0].toDouble(); val ay = e.values[1].toDouble(); val az = e.values[2].toDouble()
                ekf.handleAccel(e.timestamp, ax, ay, az)
                dispatchRaw(RawEvent.Accel(e.timestamp, ax, ay, az))
            }
            Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> {
                val mx = e.values[0].toDouble(); val my = e.values[1].toDouble(); val mz = e.values[2].toDouble()
                val reliable = e.accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE
                ekf.handleMag(e.timestamp, mx, my, mz, reliable)
                dispatchRaw(RawEvent.Mag(e.timestamp, mx, my, mz, e.accuracy))
            }
            Sensor.TYPE_PRESSURE -> {
                val p_hPa = e.values[0].toDouble()
                ekf.handleBaro(e.timestamp, p_hPa)
                dispatchRaw(RawEvent.Baro(e.timestamp, p_hPa))
            }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }

    override fun onDestroy() {
        super.onDestroy()
        try { fusedClient.removeLocationUpdates(locationCallback) } catch (_: Exception) {}
        try { sensorManager.unregisterListener(this) } catch (_: Exception) {}
        try { sensorThread.quitSafely() } catch (_: Exception) {}
        mainHandler.removeCallbacksAndMessages(null)
    }

    // --- Notification ---
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Sensor Fusion", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Runs sensor fusion in the foreground"; setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }
    private fun buildNotification(subtitle: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EKF Sensor Fusion")
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
