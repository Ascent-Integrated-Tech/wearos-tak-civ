package com.aitech.weartak_civ.algorithm

import com.aitech.weartak_civ.service.MovementState
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.system.measureTimeMillis

class StepDetector {
    // Raw sensor data
    private val magnitudeWindow = mutableListOf<Double>()
    private var avgMag = 0.0
    private var stepCount = 0
    private var totalDistance = 0.0
    private var lastStepTime = 0L
    private val stepTimestamps = mutableListOf<Long>()

    private val peakThreshold = 1.0  // Adjustable based on test
    private val windowSize = 20  // For smoothing and dynamic threshold
    private val maxWindow = 100  // Max size of data kept

    fun processAccelerometerData(x: Float, y: Float, z: Float, timestamp: Long) {
        // Calculate magnitude
        val mag = sqrt((x * x + y * y + z * z).toDouble())

        // Update window and average
        magnitudeWindow.add(mag)
        if (magnitudeWindow.size > maxWindow) magnitudeWindow.removeAt(0)

        avgMag = magnitudeWindow.average()
        val netMag = mag - avgMag

        detectPeak(netMag, timestamp)
    }

    private var lastThree = mutableListOf<Double>()

    private fun detectPeak(netMag: Double, timestamp: Long) {
        lastThree.add(netMag)
        if (lastThree.size < 3) return
        if (lastThree.size > 3) lastThree.removeAt(0)

        if (lastThree[0] < lastThree[1] && lastThree[1] > lastThree[2] && lastThree[1] > peakThreshold) {
            // Detected a peak
            onStepDetected(timestamp, lastThree[1])
        }
    }

    private fun onStepDetected(timestamp: Long, peakValue: Double) {
        stepCount++
        stepTimestamps.add(timestamp)

        // Determine step length using dynamic classification
        val stepSize = peakValue
        val stepCategory = when {
            stepSize < avgMag -> 0.3
            stepSize == avgMag -> 1.0
            else -> 1.5
        }

        totalDistance += stepCategory
        lastStepTime = timestamp
    }

    fun getStepCount(): Int = stepCount

    fun getDistanceMeters(): Double = totalDistance

    fun getStepRate(): Double {
        if (stepTimestamps.size < 2) return 0.0
        val durationSec = (stepTimestamps.last() - stepTimestamps.first()) / 1000.0
        return if (durationSec > 0) stepCount / durationSec else 0.0
    }

    fun isUserMoving(): Boolean {
        // Consider moving if last step occurred within last 2 seconds
        return System.currentTimeMillis() - lastStepTime < 2000
    }

    fun getMovementType(): MovementState {
        val rate = getStepRate()
        return when {
            rate < 0.3 -> MovementState.STATIONARY
            rate < 2.0 -> MovementState.WALKING
            else       -> MovementState.RUNNING
        }
    }


    fun reset() {
        stepCount = 0
        totalDistance = 0.0
        stepTimestamps.clear()
        magnitudeWindow.clear()
        lastThree.clear()
    }
}