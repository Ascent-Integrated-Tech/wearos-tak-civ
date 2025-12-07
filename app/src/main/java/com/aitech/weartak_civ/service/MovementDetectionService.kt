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
import androidx.core.app.NotificationCompat
import com.aitech.weartak_civ.events.LocationUpdateRequired
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.data.DataRepository
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
    private val bhRepo by lazy { (applicationContext as WearTakApplication).bhRepo as BloodhoundRepository }

    // --- WearTAK Service requirements: Binder / scope ---
    private val binder = MovementDetectionServiceBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // --- Movement detection state ---
    private val gravity = FloatArray(3)
    private var isUserMoving = false
    private val transitionHandler = Handler(Looper.getMainLooper())
    private var highCount = 0
    private var lowCount = 0


    private val confirmStillRunnable = Runnable {
        if (isUserMoving) {
            isUserMoving = false
            motionStartCandidateTime = 0L
            stillStartCandidateTime = 0L
            Log.d(TAG, "🛑 Confirmed STILL after delay")
            updateCotInterval(true, INTERVAL_STILL)
        }
    }
    // --- Heading state ---
    private var lastAzimuthTrueDeg: Double? = null

    // --- at class level ---
    private val cepScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var simJob: Job? = null
    private var simLoopJob: Job? = null
    private var lastSimMs: Long = 0L
    private val SIM_INTERVAL_MS = 1000L // run at most once per second

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
        if (!isRunning.getAndSet(true)) {
            isRunning.set(true)
            startForeground(MOVEMENT_DETECTION_SERVICE_NOTIFICAITON_ID, createNotification())
            Log.d(TAG, "Movement detection service started")

            // DATA SIMULATION LOOP
            //startSimulationLoop()

            sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            accelerometer?.let {
                sensorManager.registerListener(this, it, ACCELEROMETER_MAX_SENSOR_DELAY_US)
            }
            //gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            /*
            gyroscope?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
            */

            isUserMoving = false
            //currentInterval = INTERVAL_STILL
            updateCotInterval(true, INTERVAL_STILL)
        }
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

        /*when (event.sensor.type) {
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

                //val isPotentialStep = accelMag > MOVEMENT_THRESHOLD

                if (accelMag > MOVEMENT_THRESHOLD) {
                    highCount++
                    lowCount = 0

                    // Cancel any pending still confirmation
                    transitionHandler.removeCallbacks(confirmStillRunnable)

                    if (!isUserMoving && (highCount >= REQUIRED_CONSECUTIVE_HIGHS)) {
                        isUserMoving = true
                        updateCotInterval(false, INTERVAL_MOVING)
                        Log.d(TAG, "✅ MOVING (3 consecutive HIGHs)")
                        highCount = 0
                    }
                } else {
                    highCount = 0
                    lowCount++

                    if (isUserMoving && lowCount >= REQUIRED_CONSECUTIVE_LOWS) {
                        if (lowCount == REQUIRED_CONSECUTIVE_LOWS) {
                            Log.d(TAG, "⏳ 3 LOWs detected. Scheduling STILL confirmation in $STILL_CONFIRM_DELAY_MS ms")
                            transitionHandler.removeCallbacks(confirmStillRunnable)
                            transitionHandler.postDelayed(confirmStillRunnable, STILL_CONFIRM_DELAY_MS)
                        }
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
                            transitionHandler.postDelayed(confirmStillRunnable, STILL_CONFIRM_DELAY_MS)
                            Log.d(TAG, "⏳ Scheduling STILL confirmation in ${STILL_CONFIRM_DELAY_MS}ms")
                        }
                    } else {
                        stillStartCandidateTime = 0L
                    }
                }*/
            }

            /*
            Sensor.TYPE_GYROSCOPE -> {
                gyroValues = event.values.clone()
            }

             */

            /*
            Sensor.TYPE_STEP_DETECTOR -> {
                val steps = event.values[0].toInt() // Usually 1 per trigger
                Log.d("StepDetection", "Step detected! Steps = $steps")
            }

             */
        }*/
        when (event.sensor.type) {
//            Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event.values)
            Sensor.TYPE_ROTATION_VECTOR -> handleRotationVector(event.values)
        }
    }

    /*private fun startSimulationLoop() {
        if (simLoopJob?.isActive == true) return
        simLoopJob = cepScope.launch {
            Log.i(TAG, "CEP simulation loop started, interval=${SIM_INTERVAL_MS}ms")
            while (isActive) {
                try {
                    simulateValues() // your suspend function below
                } catch (t: Throwable) {
                    Log.e(TAG, "simulateValues() failed: ${t.message}", t)
                }
                delay(SIM_INTERVAL_MS)
            }
        }
    }*/

    /*suspend fun simulateValues(){

        Log.d("CEP", "starting simulation")

        val now = System.currentTimeMillis()

        // Basic toggles & randomization
        val rnd = kotlin.random.Random(now)
        val activity = listOf("STILL", "WALK", "RUN").random()
        val onWrist = true
        val isSleeping = false
        val covered = rnd.nextBoolean()

        // Environment
        val ambientC = 12.0 + rnd.nextDouble(-0.5, 0.5)
        val windMps = 3.0 + rnd.nextDouble(-0.3, 0.3)
        val humidityPct = 65.0 + rnd.nextDouble(-2.0, 2.0)
        val lux = if (covered) 5.0 else 300.0 + rnd.nextDouble(-20.0, 20.0)

        // Cardiovascular
        val hr = listOf(72.0, 100.0, 140.0).random()
        val hrv = listOf(40.0, 25.0, 10.0).random()
        val spo2 = 97.0 + rnd.nextDouble(-1.0, 1.0)
        val ppgAmp = 1.0 - rnd.nextDouble(0.0, 0.3)

        // Motion / orientation
        val accelMag = listOf(0.02, 0.4, 1.2).random()
        val cadence = listOf(null, 110.0, 170.0).random()
        val gyroRms = listOf(1.0, 10.0, 40.0).random()

        // Skin / water
        val skinWrist = 32.0 + rnd.nextDouble(-1.0, 1.0)
        val skinHand  = 30.0 + rnd.nextDouble(-1.0, 1.0)
        val wet = rnd.nextInt(0, 10) == 0
        val immersion = wet && rnd.nextBoolean()
        val waterTemp = if (immersion) 8f else null

        // ---- Emit all Topics ----
        com.aitech.weartak_civ.cep.bus.CepBus.emit(com.aitech.weartak_civ.cep.model.CepEvent(
            com.aitech.weartak_civ.cep.model.Topics.ACTIVITY,
            com.aitech.weartak_civ.cep.model.payloads.ActivityPayload(activity)
        ))

        com.aitech.weartak_civ.cep.bus.CepBus.emit(com.aitech.weartak_civ.cep.model.CepEvent(
            com.aitech.weartak_civ.cep.model.Topics.OFFBODY,
            com.aitech.weartak_civ.cep.model.payloads.OffBodyPayload(true)
        ))

        com.aitech.weartak_civ.cep.bus.CepBus.emit(com.aitech.weartak_civ.cep.model.CepEvent(
            com.aitech.weartak_civ.cep.model.Topics.SLEEP,
            com.aitech.weartak_civ.cep.model.payloads.SleepPayload(true)
        ))

        com.aitech.weartak_civ.cep.bus.CepBus.emit(com.aitech.weartak_civ.cep.model.CepEvent(
            com.aitech.weartak_civ.cep.model.Topics.HEART_RATE,
            com.aitech.weartak_civ.cep.model.payloads.HeartRatePayload(bpm = hr)
        ))

        com.aitech.weartak_civ.cep.bus.CepBus.emit(com.aitech.weartak_civ.cep.model.CepEvent(
            com.aitech.weartak_civ.cep.model.Topics.HRV,
            com.aitech.weartak_civ.cep.model.payloads.HrvPayload(rmssd_ms = hrv)
        ))

        com.aitech.weartak_civ.cep.bus.CepBus.emit(com.aitech.weartak_civ.cep.model.CepEvent(
            com.aitech.weartak_civ.cep.model.Topics.SPO2,
            com.aitech.weartak_civ.cep.model.payloads.Spo2Payload(pct = spo2)
        ))

        com.aitech.weartak_civ.cep.bus.CepBus.emit(com.aitech.weartak_civ.cep.model.CepEvent(
            com.aitech.weartak_civ.cep.model.Topics.PPG,
            com.aitech.weartak_civ.cep.model.payloads.PpgPayload(
                amplitude = ppgAmp.toFloat(),
                quality = 1.0f,
                wavelength = "green"
            )
        ))

        com.aitech.weartak_civ.cep.bus.CepBus.emit(com.aitech.weartak_civ.cep.model.CepEvent(
            com.aitech.weartak_civ.cep.model.Topics.ACCEL,
            com.aitech.weartak_civ.cep.model.payloads.AccelPayload(
                magnitude_g = accelMag,
                cadence_spm = cadence
            )
        ))

        com.aitech.weartak_civ.cep.bus.CepBus.emit(com.aitech.weartak_civ.cep.model.CepEvent(
            com.aitech.weartak_civ.cep.model.Topics.GYRO,
            com.aitech.weartak_civ.cep.model.payloads.GyroPayload(
                x = rnd.nextFloat() * 2f - 1f,
                y = rnd.nextFloat() * 2f - 1f,
                z = rnd.nextFloat() * 2f - 1f,
                rms = gyroRms.toFloat()
            )
        ))

        com.aitech.weartak_civ.cep.bus.CepBus.emit(com.aitech.weartak_civ.cep.model.CepEvent(
            com.aitech.weartak_civ.cep.model.Topics.AMBIENT,
            com.aitech.weartak_civ.cep.model.payloads.AmbientPayload(
                temp_c = ambientC,
                wind_mps = windMps,
                humidity_pct = humidityPct
            )
        ))

        com.aitech.weartak_civ.cep.bus.CepBus.emit(com.aitech.weartak_civ.cep.model.CepEvent(
            com.aitech.weartak_civ.cep.model.Topics.WIND,
            com.aitech.weartak_civ.cep.model.payloads.WindPayload(
                speedMps = windMps.toFloat(),
                directionDeg = rnd.nextInt(0, 360).toFloat()
            )
        ))

        com.aitech.weartak_civ.cep.bus.CepBus.emit(com.aitech.weartak_civ.cep.model.CepEvent(
            com.aitech.weartak_civ.cep.model.Topics.HUMIDITY,
            com.aitech.weartak_civ.cep.model.payloads.HumidityPayload(relativePercent = humidityPct.toFloat())
        ))

        com.aitech.weartak_civ.cep.bus.CepBus.emit(com.aitech.weartak_civ.cep.model.CepEvent(
            com.aitech.weartak_civ.cep.model.Topics.LIGHT,
            com.aitech.weartak_civ.cep.model.payloads.LightPayload(
                lux = lux.toFloat(),
                covered = covered
            )
        ))

        com.aitech.weartak_civ.cep.bus.CepBus.emit(com.aitech.weartak_civ.cep.model.CepEvent(
            com.aitech.weartak_civ.cep.model.Topics.SKIN_TEMP,
            com.aitech.weartak_civ.cep.model.payloads.SkinTempPayload(
                c_peripheral = listOf(skinWrist, skinHand)
            )
        ))

        com.aitech.weartak_civ.cep.bus.CepBus.emit(com.aitech.weartak_civ.cep.model.CepEvent(
            com.aitech.weartak_civ.cep.model.Topics.IMMERSION,
            com.aitech.weartak_civ.cep.model.payloads.ImmersionPayload(
                wet_flag = wet,
                immersion_flag = immersion
            )
        ))

        com.aitech.weartak_civ.cep.bus.CepBus.emit(com.aitech.weartak_civ.cep.model.CepEvent(
            com.aitech.weartak_civ.cep.model.Topics.WATER,
            com.aitech.weartak_civ.cep.model.payloads.WaterPayload(
                wet = wet,
                temperatureC = waterTemp
            )
        ))
    }*/

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
}