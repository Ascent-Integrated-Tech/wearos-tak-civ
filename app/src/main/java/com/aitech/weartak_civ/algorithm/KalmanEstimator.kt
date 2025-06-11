package com.aitech.weartak_civ.algorithm

import kotlin.math.pow
import kotlin.math.sqrt

class KalmanEstimator {

    private val TAG = "KalmanEstimator"

    // State vector: [x, y, z, vx, vy, vz] in meters
    private var state = FloatArray(6)
    private var lastTimestamp: Long = -1L

    // Noise parameters
    private val processNoise = 1e-3f
    private val measurementNoise = 1.0f

    private val positionDriftThreshold = 10.0f // meters
    private var P = Array(6) { FloatArray(6) }

    var onGpsUpdateRequired: (() -> Unit)? = null

    fun reset(position: FloatArray, velocity: FloatArray, timestamp: Long) {
        for (i in 0..2) {
            state[i] = position[i]
            state[i + 3] = velocity[i]
        }
        lastTimestamp = timestamp
        P = Array(6) { i -> FloatArray(6) { if (i == it) 1f else 0f } }
    }

    fun predict(accel: FloatArray, timestamp: Long) {
        if (lastTimestamp == -1L) return

        val dt = (timestamp - lastTimestamp) / 1000f
        lastTimestamp = timestamp

        for (i in 0..2) {
            state[i] += state[i + 3] * dt + 0.5f * accel[i] * dt.pow(2)
            state[i + 3] += accel[i] * dt
        }

        for (i in 0..5) {
            P[i][i] += processNoise
        }

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

    fun resetVelocity() {
        state[3] = 0f  // vx
        state[4] = 0f  // vy
        state[5] = 0f  // vz
    }
}
