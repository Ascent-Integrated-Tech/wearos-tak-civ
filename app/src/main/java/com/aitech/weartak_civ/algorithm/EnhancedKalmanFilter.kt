package com.aitech.weartak_civ.algorithm

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class EnhancedKalmanEstimator {

    private var state = FloatArray(6) // [x, y, z, vx, vy, vz]
    private var P = Array(6) { FloatArray(6) }
    private var lastTimestamp: Long = -1L

    private val processNoise = 0.001f // ↓ Reduced trust in model dynamics
    private val measurementNoise = 8.0f // ↑ Slightly more trust in GPS when updating

    private val velocityCap = 40.0f // m/s cap for damping predictions

    private val velocityJumpCap = 22.0f // m/s cap for damping predictions

    var onGpsUpdateRequired: (() -> Unit)? = null

    fun reset(position: FloatArray, velocity: FloatArray, timestamp: Long) {
        for (i in 0..2) {
            state[i] = position[i]
            state[i + 3] = velocity[i]
        }
        lastTimestamp = timestamp
        P = Array(6) { i -> FloatArray(6) { if (i == it) 1f else 0f } }
    }

    fun predict(accelBody: FloatArray, orientation: FloatArray, timestamp: Long) {
        if (lastTimestamp == -1L) return
        val dt = (timestamp - lastTimestamp) / 1000f
        lastTimestamp = timestamp

        val cbn = computeDCM(orientation)
        val accelNav = matrixMultiply(cbn, accelBody)

        // 1) remove gravity (z-up in nav frame)
        //accelNav[2] -= 9.80665f

        // --- anti-drift: dead-band tiny accelerations ---
        for (i in 0..2) {
            if (kotlin.math.abs(accelNav[i]) < 0.08f) accelNav[i] = 0f
        }

        for (i in 0..2) {
            // basic integration with damping
            state[i] += state[i + 3] * dt + 0.5f * accelNav[i] * dt.pow(2)
            state[i + 3] += accelNav[i] * dt

            // clamp velocity to prevent wild drift
            state[i + 3] = state[i + 3].coerceIn(-velocityCap, velocityCap)

            // --- anti-drift: bleed velocity toward zero when accel is zero ---
            if (accelNav[i] == 0f) state[i + 3] *= 0.97f
        }

        for (i in 0..5) {
            P[i][i] += processNoise
        }

        val drift = sqrt(state[0].pow(2) + state[1].pow(2))
        if (drift > 50) {
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
        for (i in 3..5) state[i] = 0f
    }

    private fun computeDCM(euler: FloatArray): Array<FloatArray> {
        val phi = euler[0] // roll
        val theta = euler[1] // pitch
        val psi = euler[2] // yaw

        val cosPhi = cos(phi)
        val sinPhi = sin(phi)
        val cosTheta = cos(theta)
        val sinTheta = sin(theta)
        val cosPsi = cos(psi)
        val sinPsi = sin(psi)

        return arrayOf(
            floatArrayOf(
                cosTheta * cosPsi,
                cosTheta * sinPsi,
                -sinTheta
            ),
            floatArrayOf(
                sinPhi * sinTheta * cosPsi - cosPhi * sinPsi,
                sinPhi * sinTheta * sinPsi + cosPhi * cosPsi,
                sinPhi * cosTheta
            ),
            floatArrayOf(
                cosPhi * sinTheta * cosPsi + sinPhi * sinPsi,
                cosPhi * sinTheta * sinPsi - sinPhi * cosPsi,
                cosPhi * cosTheta
            )
        )
    }

    private fun matrixMultiply(mat: Array<FloatArray>, vec: FloatArray): FloatArray {
        val result = FloatArray(3)
        for (i in 0..2) {
            result[i] = mat[i][0] * vec[0] + mat[i][1] * vec[1] + mat[i][2] * vec[2]
        }
        return result
    }
}
