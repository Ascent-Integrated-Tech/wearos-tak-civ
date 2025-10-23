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
import com.aitech.weartak_civ.events.LocationUpdateRequired
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.sqrt

class MovementDetectionService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private val binder = MovementDetectionServiceBinder()

        private var accelerometer: Sensor? = null
    //private var gyroscope: Sensor? = null

    private var gravity = FloatArray(3)
    //private var gyroValues = FloatArray(3)

    private var lastStepTime: Long = 0

    private var isUserMoving = false
    private var lastMovementTime: Long = 0L

    // Debounce control
    private var motionStartCandidateTime: Long = 0L
    private var stillStartCandidateTime: Long = 0L

    private val MOVEMENT_THRESHOLD = 8.0f

    // COT packet interval management

    private var currentInterval = INTERVAL_MOVING

    private val transitionHandler = Handler(Looper.getMainLooper())

    private val HIGH_THRESHOLD = MOVEMENT_THRESHOLD
    private var lastHighTimestamp = 0L
    private val MAX_HIGH_INTERVAL = 1000L // max time between highs to be considered consecutive

    private var highCount = 0
    private var lowCount = 0
    private val REQUIRED_CONSECUTIVE_HIGHS = 3
    private val REQUIRED_CONSECUTIVE_LOWS = 3

    private val confirmStillRunnable = Runnable {
        if (isUserMoving) {
            isUserMoving = false
            motionStartCandidateTime = 0L
            stillStartCandidateTime = 0L
            Log.d(TAG, "MovementDetectionService 🛑 Confirmed STILL after delay")
            updateCotInterval(true, INTERVAL_STILL)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MovementDetectionService onCreate()")
        isRunning.set(true)
        startForegroundServiceCompat(
            notificationId = MOVEMENT_DETECTION_SERVICE_NOTIFICAITON_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            sensorManager.registerListener(this, it, ACCELEROMETER_MAX_SENSOR_DELAY_US)
        }
        isUserMoving = false
        //gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        /*
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        */
        //currentInterval = INTERVAL_STILL
        updateCotInterval(true, INTERVAL_STILL)
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d(TAG, "MovementDetectionService onStartCommand()")
        return START_NOT_STICKY
    }

    inner class MovementDetectionServiceBinder: Binder() {
        val service: MovementDetectionService
            get() = this@MovementDetectionService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        //val currentTime = System.currentTimeMillis()
        //Log.d(TAG, "MovementDetectionService onSensorChanged: name(${event.sensor.name}) type:${event.sensor.type}")

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

                //val isPotentialStep = accelMag > MOVEMENT_THRESHOLD

                if (accelMag > MOVEMENT_THRESHOLD) {
                    highCount++
                    lowCount = 0

                    // Cancel any pending still confirmation
                    transitionHandler.removeCallbacks(confirmStillRunnable)

                    if (!isUserMoving && (highCount >= REQUIRED_CONSECUTIVE_HIGHS)) {
                        isUserMoving = true
                        updateCotInterval(false, INTERVAL_MOVING)
                        Log.d(TAG, "MovementDetectionService ✅ MOVING (3 consecutive HIGHs)")
                        highCount = 0
                    }
                } else {
                    highCount = 0
                    lowCount++

                    if (isUserMoving && lowCount >= REQUIRED_CONSECUTIVE_LOWS) {
                        if (lowCount == REQUIRED_CONSECUTIVE_LOWS) {
                            Log.d(TAG, "MovementDetectionService ⏳ 3 LOWs detected. Scheduling STILL confirmation in $STILL_CONFIRM_DELAY_MS ms")
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
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "MovementDetectionService onAccuracyChanged(): sensor(${sensor?.name}) accuracy($accuracy)")
    }

    private fun updateCotInterval(isStill: Boolean, newInterval: Int) {
        if (newInterval != currentInterval) {
            //Log.d(TAG, "updateCotInterval: something changed")
            currentInterval = newInterval

            /*
            // Only update CotDispatchService if running
            if (CotDispatchService.isRunning.get()) {
                //val newIntervalSecs = newInterval
                if (CotDispatchService.currReportingIntervalInSecs != newInterval) {
                    CotDispatchService.currReportingIntervalInSecs = newInterval
                    //Log.d(TAG, "CotDispatchService interval updated to ${newInterval}s")
                }
            }
             */
            if (CotDispatchService.isRunning.get()) {
                if (isStill) {
                    CotDispatchService.currReportingIntervalInSecs = min(3*CotDispatchService.reportingUpdateIntervalSecs, 60)
                    //MainActivity.mainActivity.vibrateWatch(MainActivity.mainActivity, 100, VibrationEffect.DEFAULT_AMPLITUDE)
                } else { // Moving
                    // Go back to fastest set via MDM
                    CotDispatchService.currReportingIntervalInSecs = CotDispatchService.reportingUpdateIntervalSecs
                    //MainActivity.mainActivity.vibrateWatch(MainActivity.mainActivity, 1000, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                EventBus.getDefault().post(LocationUpdateRequired())
                Log.d(TAG, "MovementDetectionService updateCotInterval(): Set CotDispatchService.currReportingIntervalInSecs to ${CotDispatchService.currReportingIntervalInSecs}")
            }
        }
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
        Log.d(TAG, "MovementDetectionService onDestroy()")
        isRunning.set(false)
        sensorManager.unregisterListener(this)
        stopForeground(STOP_FOREGROUND_DETACH)
        //cotRunnable?.let { handler.removeCallbacks(it) }
    }

    companion object {
        val isRunning = AtomicBoolean(false)
        private val TAG = "WearTAK-" + MovementDetectionService::class.java.simpleName
        private const val MOVEMENT_DETECTION_SERVICE_NOTIFICAITON_ID = 112
        private const val ACCELEROMETER_MAX_SENSOR_DELAY_US = 100_000 // 100ms or 10 Hz
        private const val STILL_CONFIRM_DELAY_MS = 5000L // 5 seconds
        private const val INTERVAL_MOVING = 5    // 5 sec
        private const val INTERVAL_STILL = 30    // 30 sec
        private const val STILL = 1
        private const val MOVING = 2
    }
}