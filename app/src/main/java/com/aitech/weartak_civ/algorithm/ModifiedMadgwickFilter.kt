package com.aitech.weartak_civ.algorithm

import com.aitech.weartak_civ.algorithm.ExtendedKalmanFilter.Companion.QUATERNION_NORM_TOLERANCE
import android.util.Log
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Modified Madgwick Filter for Quaternion-Based Orientation Estimation
 *
 * Based on: "Design of a Modified Madgwick Filter for Quaternion-Based Orientation Estimation Using AHRS"
 * by Al-Fahoum & Abadir.
 *
 * Features:
 * - SLERP-based fusion between gyro integration and LM estimate
 * - Levenberg–Marquardt (LM) optimization with a numerically stable SPD solver (Cholesky + jitter)
 * - dt-aware update() (pass actual sensor period; otherwise defaults to 1/sampleFreq)
 * - Magnetometer reliability gating + LPF warm start
 * - Safe Euler conversion (clamped asin)
 * - Optional fallback gradient-descent step (uses beta) if LM makes no progress
 *
 * Important: The LM update vector is treated as a small rotation (delta-theta). We convert it
 * to a delta quaternion Δq and apply multiplicatively: q_new = q_old ⊗ Δq.
 */
class ModifiedMadgwickFilter(
    private val sampleFreq: Float = 100.0f, // nominal Hz (used if dt not provided)
    private var beta: Float = 0.05f,        // fallback gradient step gain (if LM diverges)
    private var lambda: Float = 0.001f,     // initial LM damping
    private var decayRate: Float = 10.0f,   // LM damping decay/grow factor
    private var sigma: Float = 0.041f,      // gyroscope bias compensation gain
    private var mu: Float = 0.92f,          // SLERP weight toward LM estimate (0..1)
    private val maxIterations: Int = 10     // maximum LM iterations
) {

    companion object {
        // Earth's magnetic field reference (normalized, ENU). Should be calibrated for your locale.
        private val MAGNETIC_REFERENCE = floatArrayOf(0.0f, 0.0f, -1.0f)

        // Gravity reference (normalized, ENU)
        private val GRAVITY_REFERENCE = floatArrayOf(0.0f, 0.0f, 1.0f)

        // Low-pass filter coefficient for magnetometer (normalized stream)
        private const val MAGNETIC_LP_ALPHA = 0.1f

        // Raw magnetometer reliability band (µT). Typical Earth field ≈ 25–65 µT.
        private const val MAG_FIELD_MIN_UT = 20.0f
        private const val MAG_FIELD_MAX_UT = 70.0f

        // Cholesky jitter for SPD solve
        private const val CHOLESKY_EPS = 1e-6f
        private const val CHOLESKY_MAX_RETRY = 5
    }

    // Quaternion representing the orientation [w, x, y, z]
    private var q = floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f)
    private var previousValidQuaternion = q.copyOf()

    // Gyroscope bias estimate
    private var gyroBias = floatArrayOf(0.0f, 0.0f, 0.0f)

    // Low-pass filtered magnetometer data (normalized)
    private var filteredMagnetometer = floatArrayOf(0.0f, 0.0f, 0.0f)
    private var magLpfInitialized = false

    // Previous error for damping parameter adjustment
    private var previousError = Float.MAX_VALUE

    // Current LM damping parameter
    private var currentLambda = lambda

    // Nominal sampling period
    private val defaultSamplePeriod = 1.0f / sampleFreq

    // Magnetometer reliability weight for current update (0..1)
    private var magWeight = 1.0f

    // ----------------- Public API -----------------

    /**
     * Backwards-compatible update (uses default sample period).
     */
    fun update(gyroscope: FloatArray, accelerometer: FloatArray, magnetometer: FloatArray) {
        update(gyroscope, accelerometer, magnetometer, null)
    }

    /**
     * Update the filter with new sensor measurements.
     *
     * @param gyroscope Angular velocity in rad/s [ωx, ωy, ωz]
     * @param accelerometer Acceleration in m/s² [ax, ay, az]
     * @param magnetometer Magnetic field in μT [mx, my, mz]
     * @param dt Optional sample period in seconds (if null, uses 1/sampleFreq)
     */
    fun update(
        gyroscope: FloatArray,
        accelerometer: FloatArray,
        magnetometer: FloatArray,
        dt: Float?
    ) {
        val samplePeriod = dt ?: defaultSamplePeriod

        //Log.d("Madgwick", "🔄 Update start: dt=${"%.4f".format(samplePeriod)}s, gyro=[${gyroscope.joinToString()}], accel=[${accelerometer.joinToString()}], mag=[${magnetometer.joinToString()}]")

        // NEW: IMU inputs sanity check
        val ax = accelerometer[0]; val ay = accelerometer[1]; val az = accelerometer[2]
        val gx = gyroscope[0]; val gy = gyroscope[1]; val gz = gyroscope[2]
        
        if (!ax.isFinite() || !ay.isFinite() || !az.isFinite() ||
            !gx.isFinite() || !gy.isFinite() || !gz.isFinite() || samplePeriod <= 0f) {
            //Log.w("Madgwick", "Bad IMU sample: finite=${ax.isFinite() && ay.isFinite() && az.isFinite()}, dt=$samplePeriod")
            return
        }
        
        // NEW: Spike detector
        val gyroAbs = maxOf(abs(gx), abs(gy), abs(gz))
        //if (gyroAbs > 20f) Log.w("Madgwick", "⚠️ Gyro spike ${"%.1f".format(gyroAbs)} rad/s")

        // --- Magnetometer reliability gating (based on RAW magnitude in µT) ---
        val magRawNorm = fastNorm(magnetometer)
        //magWeight = if (magRawNorm in MAG_FIELD_MIN_UT..MAG_FIELD_MAX_UT) 1.0f else 0.0f

        // TEMP: very wide band just to test
        magWeight = if (magRawNorm in 5f..5000f) 1f else 0f

        // Normalize accelerometer and magnetometer measurements for the LM objective
        val normalizedAccel = normalize(accelerometer)
        val normalizedMag = normalize(magnetometer)

        // LPF warm start + update on normalized magnetometer
        if (!magLpfInitialized) {
            filteredMagnetometer = normalizedMag.copyOf()
            magLpfInitialized = true
        } else {
            applyLowPassFilter(normalizedMag)
        }

        // Directional mag gating (scale-agnostic): use cosine similarity
        // predict body-frame mag by rotating nav reference into body with current q
        val predictedBodyMag = rotateVector(q /* body->nav */, MAGNETIC_REFERENCE)
        val dotBM = (predictedBodyMag[0]*filteredMagnetometer[0]
                + predictedBodyMag[1]*filteredMagnetometer[1]
                + predictedBodyMag[2]*filteredMagnetometer[2])
        val cosSim = dotBM.coerceIn(-1f, 1f)               // -1..1
        val fit = (cosSim + 1f) * 0.5f                     // 0..1
        val weightFromFit = fit * fit                      // emphasize good alignment

        // very loose magnitude sanity to reject garbage, regardless of units
        //val magRawNorm = fastNorm(magnetometer)
        val magOk = magRawNorm in 5f..5000f

        magWeight = if (magOk) weightFromFit else 0f


        // Orientation estimate from accel+mag via LM (mag rows scaled by magWeight)
        val (qEst, lmImproved) = estimateOrientationLM(normalizedAccel, filteredMagnetometer)

        // Orientation estimate from gyroscope integration
        val qGyro = integrateGyroscope(gyroscope, samplePeriod)

        // compute LM error now: sum of squared residuals (acc + mag, mag rows scaled by magWeight)
        run {
            val err = calculateErrorVector(qEst, normalize(magnetometer), filteredMagnetometer, magWeight)
            var lmError = 0f; for (e in err) lmError += e*e  // SSE

            val eEst  = quatToEulerDeg(qEst)
            val eGyro = quatToEulerDeg(qGyro)
            val muDynamic = mu * (0.5f + 0.5f * magWeight)  // same as below

            /*Log.d("Madgwick", "↔️ LM vs Gyro | " +
                    "qEst=[${qEst.joinToString(","){ "%.4f".format(it) }}] " +
                    "eEst=(r=${"%.2f".format(eEst[0])}, p=${"%.2f".format(eEst[1])}, y=${"%.2f".format(eEst[2])}) | " +
                    "qGyro=[${qGyro.joinToString(","){ "%.4f".format(it) }}] " +
                    "eGyro=(r=${"%.2f".format(eGyro[0])}, p=${"%.2f".format(eGyro[1])}, y=${"%.2f".format(eGyro[2])}) | " +
                    "magW=${"%.2f".format(magWeight)} μd=${"%.2f".format(muDynamic)} λ=${"%.3g".format(currentLambda)} " +
                    "LMerr=${"%.3e".format(lmError)} imp=$lmImproved")*/
        }

        val eEst = quatToEulerDeg(qEst)
        val eGyro = quatToEulerDeg(qGyro)
        /*Log.d("Madgwick", "↔️ LM vs Gyro: " +
                "qEst=[${qEst.joinToString(","){ "%.4f".format(it) }}], " +
                "eEst=(r=${"%.2f".format(eEst[0])}, p=${"%.2f".format(eEst[1])}, y=${"%.2f".format(eEst[2])}) | " +
                "qGyro=[${qGyro.joinToString(","){ "%.4f".format(it) }}], " +
                "eGyro=(r=${"%.2f".format(eGyro[0])}, p=${"%.2f".format(eGyro[1])}, y=${"%.2f".format(eGyro[2])})")*/


        // Fuse with SLERP (bias toward qEst by mu, but temper if mag is unreliable)
        val muDynamic = mu * (0.5f + 0.5f * magWeight) // reduce influence when magWeight==0
        q = slerp(qGyro, qEst, muDynamic)

        // Normalize and validate quaternion
        q = normalizeQuaternion(q)
        if (!isValidQuaternion(q)) {
            q = previousValidQuaternion.copyOf()
        } else {
            previousValidQuaternion = q.copyOf()
        }

        // If LM failed to improve (or mag is unreliable), optionally nudge using a small gradient step
        if (!lmImproved && beta > 0f) {
            val grad = jtErrorGradient(q, normalizedAccel, filteredMagnetometer, magWeight)
            val gradNorm = fastNorm(grad)
            if (gradNorm > 0f) {
                val step = FloatArray(4) { i -> grad[i] / gradNorm }
                // q_new = q - beta * step
                for (i in 0..3) q[i] -= beta * step[i]
                q = normalizeQuaternion(q)
                if (!isValidQuaternion(q)) q = previousValidQuaternion.copyOf()
            }
        }

        // Update gyroscope bias estimate using the difference between the two estimates
        updateGyroscopeBias(qEst, qGyro, samplePeriod)

        val euler = getEulerAnglesDegrees()
        //Log.d("Madgwick", "✅ Update done: q=[${q.joinToString()}], roll=${"%.2f".format(euler[0])}, pitch=${"%.2f".format(euler[1])}, yaw=${"%.2f".format(euler[2])}, magWeight=$magWeight")

    }

    fun getQuaternion(): FloatArray = q.copyOf()

    fun getEulerAngles(): FloatArray {
        // Convert using the same quaternion convention used in this filter
        val qw = q[0]; val qx = q[1]; val qy = q[2]; val qz = q[3]

        val sinr_cosp = 2.0f * (qw * qx + qy * qz)
        val cosr_cosp = 1.0f - 2.0f * (qx * qx + qy * qy)
        val roll = atan2(sinr_cosp, cosr_cosp)

        val sinp = 2.0f * (qw * qy - qz * qx)
        val pitch = asin(clamp(sinp, -1.0f, 1.0f)) // clamp to avoid NaN

        val siny_cosp = 2.0f * (qw * qz + qx * qy)
        val cosy_cosp = 1.0f - 2.0f * (qy * qy + qz * qz)
        val yaw = atan2(siny_cosp, cosy_cosp)

        return floatArrayOf(roll, pitch, yaw)
    }

    fun getEulerAnglesDegrees(): FloatArray {
        val r = getEulerAngles()
        return floatArrayOf(
            Math.toDegrees(r[0].toDouble()).toFloat(),
            Math.toDegrees(r[1].toDouble()).toFloat(),
            Math.toDegrees(r[2].toDouble()).toFloat()
        )
    }
    
    // Add near other getters
    fun getMagWeight(): Float = magWeight
    
    // If EKF estimates gyro bias, add a setter to disable Madgwick bias learner at runtime
    fun disableGyroBiasLearning() { sigma = 0f }

    fun getRotationMatrix(): Array<FloatArray> {
        val qw = q[0]; val qx = q[1]; val qy = q[2]; val qz = q[3]
        val m = Array(3) { FloatArray(3) }
        m[0][0] = 1.0f - 2.0f * (qy * qy + qz * qz)
        m[0][1] = 2.0f * (qx * qy - qw * qz)
        m[0][2] = 2.0f * (qx * qz + qw * qy)
        m[1][0] = 2.0f * (qx * qy + qw * qz)
        m[1][1] = 1.0f - 2.0f * (qx * qx + qz * qz)
        m[1][2] = 2.0f * (qy * qz - qw * qx)
        m[2][0] = 2.0f * (qx * qz - qw * qy)
        m[2][1] = 2.0f * (qy * qz + qw * qx)
        m[2][2] = 1.0f - 2.0f * (qx * qx + qy * qy)
        return m
    }

    fun reset() {
        q = floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f)
        previousValidQuaternion = q.copyOf()
        gyroBias = floatArrayOf(0.0f, 0.0f, 0.0f)
        filteredMagnetometer = floatArrayOf(0.0f, 0.0f, 0.0f)
        magLpfInitialized = false
        previousError = Float.MAX_VALUE
        currentLambda = lambda
    }

    // Compact snapshot for logging (no behavior change)
    data class AhrsLog(
        val qw: Float, val qx: Float, val qy: Float, val qz: Float,
        val rollDeg: Float, val pitchDeg: Float, val yawDeg: Float,
        val magWeight: Float
    )

    fun snapshotForLogging(): AhrsLog {
        val q = getQuaternion()
        val e = getEulerAnglesDegrees()
        return AhrsLog(
            qw = q[0], qx = q[1], qy = q[2], qz = q[3],
            rollDeg = e[0], pitchDeg = e[1], yawDeg = e[2],
            magWeight = getMagWeight()
        )
    }

    fun setParameters(
        newBeta: Float? = null,
        newLambda: Float? = null,
        newDecayRate: Float? = null,
        newSigma: Float? = null,
        newMu: Float? = null
    ) {
        newBeta?.let { beta = it }
        newLambda?.let { lambda = it; currentLambda = it }
        newDecayRate?.let { decayRate = it }
        newSigma?.let { sigma = it }
        newMu?.let { mu = it }
    }

    // ----------------- LM (Gauss-Newton) internals -----------------

    private fun applyLowPassFilter(magnetometer: FloatArray) {
        // y = α*x + (1-α)*y
        for (i in 0..2) {
            filteredMagnetometer[i] = MAGNETIC_LP_ALPHA * magnetometer[i] +
                    (1.0f - MAGNETIC_LP_ALPHA) * filteredMagnetometer[i]
        }
        // Re-normalize filtered vector to unit length
        filteredMagnetometer = normalize(filteredMagnetometer)
    }

    /**
     * Estimate orientation via LM and return (bestQuaternion, improvedFlag).
     * The LM update is a small rotation applied multiplicatively: q ← q ⊗ Δq.
     */
    private fun estimateOrientationLM(accelerometer: FloatArray, magnetometer: FloatArray): Pair<FloatArray, Boolean> {
        var qEst = q.copyOf()
        var bestError = Float.MAX_VALUE
        var bestQuaternion = qEst.copyOf()
        var improved = false

        for (iteration in 0 until maxIterations) {
            val error = calculateErrorVector(qEst, accelerometer, magnetometer, magWeight)
            var currentError = 0.0f
            for (e in error) currentError += e * e

            if (currentError < bestError - 1e-9f) {
                bestError = currentError
                bestQuaternion = qEst.copyOf()
                improved = true
                currentLambda = maxOf(currentLambda / decayRate, 1e-8f)
            } else {
                currentLambda = minOf(currentLambda * decayRate, 1e6f)
            }

            if (currentError < 1e-6f || abs(currentError - previousError) < 1e-8f) {
                previousError = currentError
                break
            }
            previousError = currentError

            val jacobian = calculateJacobian(qEst, accelerometer, magnetometer, magWeight)
            val hessian = calculateHessian(jacobian)
            val update = calculateLMUpdate(hessian, jacobian, error, currentLambda) // length 4

            // --- Apply update as a small corrective rotation via delta quaternion ---
            // Treat the first three components of Δx as a small rotation vector δθ.
            val step2 = update[0]*update[0] + update[1]*update[1] + update[2]*update[2]
            if (step2.isNaN() || step2.isInfinite()) break

            // Optional clamp to keep small-angle validity (e.g., <= ~0.1 rad)
            val maxStep = 0.1f
            if (step2 > maxStep*maxStep) {
                val scale = maxStep / sqrt(step2)
                update[0] *= scale
                update[1] *= scale
                update[2] *= scale
            }

            // Δq ≈ [1, -½ δθx, -½ δθy, -½ δθz], q_new = q_old ⊗ Δq
            val deltaQ = floatArrayOf(
                1.0f,
                -0.5f * update[0],
                -0.5f * update[1],
                -0.5f * update[2]
            )

            qEst = quaternionMultiply(qEst, deltaQ)
            qEst = normalizeQuaternion(qEst)
            if (!isFiniteQuaternion(qEst)) break
        }

        return Pair(bestQuaternion, improved)
    }

    private fun calculateErrorVector(
        q: FloatArray,
        accelerometer: FloatArray,
        magnetometer: FloatArray,
        magWeight: Float
    ): FloatArray {
        val rotatedGravity = rotateVector(q, GRAVITY_REFERENCE)
        val rotatedMagnetic = rotateVector(q, MAGNETIC_REFERENCE)

        val out = FloatArray(6)
        // Gravity residuals
        out[0] = accelerometer[0] - rotatedGravity[0]
        out[1] = accelerometer[1] - rotatedGravity[1]
        out[2] = accelerometer[2] - rotatedGravity[2]
        // Magnetometer residuals (scaled by reliability)
        out[3] = magWeight * (magnetometer[0] - rotatedMagnetic[0])
        out[4] = magWeight * (magnetometer[1] - rotatedMagnetic[1])
        out[5] = magWeight * (magnetometer[2] - rotatedMagnetic[2])
        return out
    }

    private fun calculateJacobian(
        q: FloatArray,
        accelerometer: FloatArray,
        magnetometer: FloatArray,
        magWeight: Float
    ): Array<FloatArray> {
        val jacobian = Array(6) { FloatArray(4) }

        // Gravity rows (unweighted)
        for (i in 0..2) {
            jacobian[i][0] = calculateGravityPartial(q, i, 0)
            jacobian[i][1] = calculateGravityPartial(q, i, 1)
            jacobian[i][2] = calculateGravityPartial(q, i, 2)
            jacobian[i][3] = calculateGravityPartial(q, i, 3)
        }

        // Magnetic rows (scaled by reliability)
        for (i in 0..2) {
            jacobian[i + 3][0] = magWeight * calculateMagneticPartial(q, i, 0)
            jacobian[i + 3][1] = magWeight * calculateMagneticPartial(q, i, 1)
            jacobian[i + 3][2] = magWeight * calculateMagneticPartial(q, i, 2)
            jacobian[i + 3][3] = magWeight * calculateMagneticPartial(q, i, 3)
        }

        return jacobian
    }

    private fun calculateGravityPartial(q: FloatArray, i: Int, j: Int): Float {
        val qw = q[0]; val qx = q[1]; val qy = q[2]; val qz = q[3]
        return when (i to j) {
            0 to 0 -> 0.0f; 0 to 1 -> 0.0f; 0 to 2 -> -4.0f * qy; 0 to 3 -> -4.0f * qz
            1 to 0 -> 2.0f * qz; 1 to 1 -> 2.0f * qy; 1 to 2 -> 2.0f * qx; 1 to 3 -> 2.0f * qw
            2 to 0 -> -2.0f * qy; 2 to 1 -> 2.0f * qz; 2 to 2 -> -2.0f * qw; 2 to 3 -> 2.0f * qx
            else -> 0.0f
        }
    }

    private fun calculateMagneticPartial(q: FloatArray, i: Int, j: Int): Float {
        val qw = q[0]; val qx = q[1]; val qy = q[2]; val qz = q[3]
        val mx = MAGNETIC_REFERENCE[0]; val my = MAGNETIC_REFERENCE[1]; val mz = MAGNETIC_REFERENCE[2]
        return when (i to j) {
            0 to 0 -> 2.0f * (qy * mz - qz * my)
            0 to 1 -> 2.0f * (qy * my + qz * mz)
            0 to 2 -> 2.0f * (-qw * mz - 2.0f * qx * my + qy * mx)
            0 to 3 -> 2.0f * (qw * my - 2.0f * qx * mz + qz * mx)
            1 to 0 -> 2.0f * (qz * mx - qx * mz)
            1 to 1 -> 2.0f * (-2.0f * qy * mx + qx * my + qw * mz)
            1 to 2 -> 2.0f * (qx * mx + qz * mz)
            1 to 3 -> 2.0f * (-qw * mx - 2.0f * qy * mz + qz * my)
            2 to 0 -> 2.0f * (qy * mx - qx * my)
            2 to 1 -> 2.0f * (qw * my - 2.0f * qz * mx + qx * mz)
            2 to 2 -> 2.0f * (-qw * mx + 2.0f * qz * my - qy * mz)
            2 to 3 -> 2.0f * (qx * mx + qy * my)
            else -> 0.0f
        }
    }

    private fun calculateHessian(jacobian: Array<FloatArray>): Array<FloatArray> {
        // H = J^T J (4x4)
        val hessian = Array(4) { FloatArray(4) }
        for (i in 0..3) {
            for (j in 0..3) {
                var sum = 0.0f
                // 6 residual rows
                sum += jacobian[0][i] * jacobian[0][j]
                sum += jacobian[1][i] * jacobian[1][j]
                sum += jacobian[2][i] * jacobian[2][j]
                sum += jacobian[3][i] * jacobian[3][j]
                sum += jacobian[4][i] * jacobian[4][j]
                sum += jacobian[5][i] * jacobian[5][j]
                hessian[i][j] = sum
            }
        }
        // Ensure symmetry numerically
        for (i in 0..3) {
            for (j in i + 1..3) {
                val s = 0.5f * (hessian[i][j] + hessian[j][i])
                hessian[i][j] = s
                hessian[j][i] = s
            }
        }
        return hessian
    }

    private fun calculateLMUpdate(
        hessian: Array<FloatArray>,
        jacobian: Array<FloatArray>,
        error: FloatArray,
        lambda: Float
    ): FloatArray {
        // Damped Hessian: H_d = H + λ * diag(H)
        val H = Array(4) { FloatArray(4) }
        for (i in 0..3) {
            for (j in 0..3) H[i][j] = hessian[i][j]
            H[i][i] += lambda * hessian[i][i]
        }

        // rhs = J^T * e
        val rhs = FloatArray(4)
        for (i in 0..3) {
            var s = 0.0f
            for (r in 0..5) s += jacobian[r][i] * error[r]
            rhs[i] = s
        }

        // Solve H x = rhs  (SPD)
        return solveSPD(H, rhs) // returns length-4 vector; we use first 3 as δθ
    }

    // ----------------- Gyro + Fusion -----------------

    private fun integrateGyroscope(gyroscope: FloatArray, dt: Float): FloatArray {
        val compensatedGyro = floatArrayOf(
            gyroscope[0] - gyroBias[0],
            gyroscope[1] - gyroBias[1],
            gyroscope[2] - gyroBias[2]
        )
        val omega = floatArrayOf(0.0f, compensatedGyro[0], compensatedGyro[1], compensatedGyro[2])
        val qDot = quaternionMultiply(q, omega)
        // q_{k+1} = q_k + 0.5 * qDot * dt
        val qGyro = FloatArray(4)
        qGyro[0] = q[0] + 0.5f * qDot[0] * dt
        qGyro[1] = q[1] + 0.5f * qDot[1] * dt
        qGyro[2] = q[2] + 0.5f * qDot[2] * dt
        qGyro[3] = q[3] + 0.5f * qDot[3] * dt
        return normalizeQuaternion(qGyro)
    }

    private fun slerp(a: FloatArray, b: FloatArray, t: Float): FloatArray {
        // SLERP between unit quaternions a, b
        var cosOmega =
            a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
        var bb = b
        if (cosOmega < 0f) { // take shortest path
            cosOmega = -cosOmega
            bb = floatArrayOf(-b[0], -b[1], -b[2], -b[3])
        }
        if (1f - cosOmega < 1e-6f) {
            // NLERP fallback
            val out = FloatArray(4) { i -> a[i] + t * (bb[i] - a[i]) }
            return normalizeQuaternion(out)
        }
        val omega = acos(cosOmega.toDouble()).toFloat()
        val s1 = sin(((1f - t) * omega).toDouble()).toFloat() / sin(omega.toDouble()).toFloat()
        val s2 = sin((t * omega).toDouble()).toFloat() / sin(omega.toDouble()).toFloat()
        val out = floatArrayOf(
            s1 * a[0] + s2 * bb[0],
            s1 * a[1] + s2 * bb[1],
            s1 * a[2] + s2 * bb[2],
            s1 * a[3] + s2 * bb[3]
        )
        return normalizeQuaternion(out)
    }

    private fun updateGyroscopeBias(qEst: FloatArray, qGyro: FloatArray, dt: Float) {
        // qError = qEst ⊗ conj(qGyro)
        val qError = quaternionDifference(qEst, qGyro)
        // Vector part approximates small angle error
        val omegaError = floatArrayOf(2.0f * qError[1], 2.0f * qError[2], 2.0f * qError[3])
        for (i in 0..2) {
            gyroBias[i] += sigma * omegaError[i] * dt
        }
    }

    // ----------------- Utilities -----------------

    private fun normalize(vector: FloatArray): FloatArray {
        val n = vector.size
        var mag = 0.0f
        for (i in 0 until n) mag += vector[i] * vector[i]
        mag = sqrt(mag)
        return if (mag > 0.0f) {
            val inv = 1.0f / mag
            FloatArray(n) { i -> vector[i] * inv }
        } else {
            vector.copyOf()
        }
    }

    private fun normalizeQuaternion(q: FloatArray): FloatArray = normalize(q)

    private fun quaternionMultiply(a: FloatArray, b: FloatArray): FloatArray {
        return floatArrayOf(
            a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
            a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
            a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
            a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0]
        )
    }

    private fun quaternionDifference(a: FloatArray, b: FloatArray): FloatArray {
        val bConjugate = floatArrayOf(b[0], -b[1], -b[2], -b[3])
        return quaternionMultiply(a, bConjugate)
    }

    private fun rotateVector(q: FloatArray, v: FloatArray): FloatArray {
        val vQuat = floatArrayOf(0.0f, v[0], v[1], v[2])
        val vQuatConjugate = floatArrayOf(q[0], -q[1], -q[2], -q[3])
        val rotated = quaternionMultiply(quaternionMultiply(q, vQuat), vQuatConjugate)
        return floatArrayOf(rotated[1], rotated[2], rotated[3])
    }

    // Solve SPD 4x4 system via Cholesky with jitter. Returns x for A x = b.
    private fun solveSPD(Ain: Array<FloatArray>, b: FloatArray): FloatArray {
        // Ensure symmetry and copy
        val A = Array(4) { i -> FloatArray(4) { j -> 0.5f * (Ain[i][j] + Ain[j][i]) } }

        var jitter = 0f
        var retry = 0
        val L = Array(4) { FloatArray(4) }

        // Try Cholesky with increasing jitter if needed
        while (retry < CHOLESKY_MAX_RETRY) {
            var ok = true
            for (i in 0..3) {
                for (j in 0..3) L[i][j] = 0f
            }
            for (i in 0..3) {
                for (j in 0..i) {
                    var sum = A[i][j]
                    if (i == j) {
                        for (k in 0 until j) sum -= L[i][k] * L[i][k]
                        sum += jitter
                        if (sum <= 0f || sum.isNaN()) { ok = false; break }
                        L[i][j] = sqrt(sum)
                    } else {
                        for (k in 0 until j) sum -= L[i][k] * L[j][k]
                        val denom = L[j][j]
                        if (denom == 0f || !denom.isFinite()) { ok = false; break }
                        L[i][j] = sum / denom
                    }
                }
                if (!ok) break
            }
            if (ok) break
            jitter = if (jitter == 0f) CHOLESKY_EPS else jitter * 10f
            retry++
        }

        // Forward solve L y = b
        val y = FloatArray(4)
        for (i in 0..3) {
            var sum = b[i]
            for (k in 0 until i) sum -= L[i][k] * y[k]
            y[i] = sum / L[i][i]
        }

        // Backward solve L^T x = y
        val x = FloatArray(4)
        for (i in 3 downTo 0) {
            var sum = y[i]
            for (k in i + 1..3) sum -= L[k][i] * x[k]
            x[i] = sum / L[i][i]
        }
        return x
    }

    // J^T * e gradient (used for optional fallback GD step)
    private fun jtErrorGradient(
        q: FloatArray,
        accel: FloatArray,
        mag: FloatArray,
        magWeight: Float
    ): FloatArray {
        val J = calculateJacobian(q, accel, mag, magWeight)
        val e = calculateErrorVector(q, accel, mag, magWeight)
        val g = FloatArray(4)
        for (i in 0..3) {
            var s = 0.0f
            for (r in 0..5) s += J[r][i] * e[r]
            g[i] = s
        }
        return g
    }

    private fun quatToEulerDeg(q: FloatArray): FloatArray {
        val qw = q[0]; val qx = q[1]; val qy = q[2]; val qz = q[3]
        val sinr_cosp = 2.0f * (qw * qx + qy * qz)
        val cosr_cosp = 1.0f - 2.0f * (qx * qx + qy * qy)
        val roll = atan2(sinr_cosp, cosr_cosp)

        val sinp = 2.0f * (qw * qy - qz * qx)
        val pitch = asin(clamp(sinp, -1.0f, 1.0f))

        val siny_cosp = 2.0f * (qw * qz + qx * qy)
        val cosy_cosp = 1.0f - 2.0f * (qy * qy + qz * qz)
        val yaw = atan2(siny_cosp, cosy_cosp)

        return floatArrayOf(
            Math.toDegrees(roll.toDouble()).toFloat(),
            Math.toDegrees(pitch.toDouble()).toFloat(),
            Math.toDegrees(yaw.toDouble()).toFloat()
        )
    }


    /**
     * Validate quaternion for proper normalization and range
     * @param q Quaternion array [w, x, y, z]
     * @return true if quaternion is valid
     */
    private fun isValidQuaternion(q: FloatArray): Boolean {
        if (q.size != 4) return false
        for (i in 0..3) if (q[i].isNaN() || q[i].isInfinite()) return false
        val norm = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        val normError = abs(norm - 1.0f)
        return normError < QUATERNION_NORM_TOLERANCE
    }

    private fun isFiniteQuaternion(q: FloatArray): Boolean {
        for (i in 0..3) if (!q[i].isFinite()) return false
        return true
    }

    private fun clamp(x: Float, lo: Float, hi: Float): Float =
        if (x < lo) lo else if (x > hi) hi else x

    private fun fastNorm(v: FloatArray): Float {
        var s = 0.0f
        for (x in v) s += x * x
        return sqrt(s)
    }
}
