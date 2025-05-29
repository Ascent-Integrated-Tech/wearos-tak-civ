package com.aitech.weartak_civ.algorithm

import kotlin.math.pow
import kotlin.math.sqrt

class KalmanEstimator {

    private val TAG = "KalmanEstimator"

    // State vector: [x, y, z, vx, vy, vz]
    private var state = FloatArray(6)
    private var lastTimestamp: Long = -1L

    // Process noise and measurement noise
    private val processNoise = 1e-3f
    private val measurementNoise = 1.0f

    // Position estimation threshold to trigger GPS update (in meters)
    private val positionDriftThreshold = 10.0f

    // Kalman matrices
    private var P = Array(6) { FloatArray(6) }  // Covariance matrix

    // External callback for GPS refresh trigger
    var onGpsUpdateRequired: (() -> Unit)? = null

    fun reset(position: FloatArray, velocity: FloatArray, timestamp: Long) {
        state[0] = position[0]
        state[1] = position[1]
        state[2] = position[2]
        state[3] = velocity[0]
        state[4] = velocity[1]
        state[5] = velocity[2]
        lastTimestamp = timestamp
        P = Array(6) { i -> FloatArray(6) { if (i == it) 1f else 0f } }
    }

    fun predict(accel: FloatArray, timestamp: Long) {
        if (lastTimestamp == -1L) return

        val dt = (timestamp - lastTimestamp) / 1000f  // Convert ms to s
        lastTimestamp = timestamp

        // State update
        for (i in 0..2) {
            state[i] += state[i + 3] * dt + 0.5f * accel[i] * dt.pow(2)
            state[i + 3] += accel[i] * dt
        }

        // Estimate error growth
        for (i in 0..5) {
            P[i][i] += processNoise
        }

        // Check drift
        val drift = sqrt(state[0].pow(2) + state[1].pow(2) + state[2].pow(2))
        if (drift > positionDriftThreshold) {
            onGpsUpdateRequired?.invoke()
        }
    }

    fun updateWithGps(position: FloatArray, accuracy: FloatArray) {
        for (i in 0..2) {
            val kGain = P[i][i] / (P[i][i] + accuracy[i].pow(2))
            state[i] = state[i] + kGain * (position[i] - state[i])
            P[i][i] *= (1 - kGain)
        }
    }

    fun getEstimatedPosition(): FloatArray = state.copyOfRange(0, 3)
    fun getEstimatedVelocity(): FloatArray = state.copyOfRange(3, 6)

    fun getState(): FloatArray = state.copyOf()
}
