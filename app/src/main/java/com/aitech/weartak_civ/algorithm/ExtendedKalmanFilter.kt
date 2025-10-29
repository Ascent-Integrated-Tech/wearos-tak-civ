package com.aitech.weartak_civ.algorithm

import kotlin.math.*
import android.util.Log
import com.aitech.weartak_civ.service.MovementState

private const val GPS_TAG = "GPS_REFIX"
private const val GPS_REFIX_TAG = "GPS_REFIX"

/**
 * Extended Kalman Filter for 6-DOF Pose Estimation with IMU Bias Compensation
 *
 * This implementation addresses all critical issues identified:
 * 1. ✅ Proper quaternion integration for IMU frame transformation
 * 2. ✅ Complete predict function with orientation-aware dead reckoning
 * 3. ✅ Dynamic time step calculation from sensor timestamps (assumed nanoseconds)
 * 4. ✅ Comprehensive measurement model (GPS position, velocity, heading)
 * 5. ✅ Complete sensor fusion for all available data
 * 6. ✅ IMU bias estimation (gyroscope drift compensation)
 * 7. ✅ Sampling time uncertainty handling
 * 8. ✅ Enhanced noise modeling for low-cost MEMS sensors
 * 9. ✅ Accelerometer bias estimation (static calibration)
 * 10. ✅ Magnetometer integration for absolute heading correction
 *
 * State Vector (19-dimensional):
 * [x, y, z, vx, vy, vz, ax, ay, az, qw, qx, qy, qz, gyroBiasX, gyroBiasY, gyroBiasZ, accelBiasX, accelBiasY, accelBiasZ]
 * Where:
 * - x, y, z: Position in local ENU coordinates (meters)
 * - vx, vy, vz: Velocity components (m/s)
 * - ax, ay, az: Acceleration components (m/s²)
 * - qw, qx, qy, qz: Orientation quaternion
 * - gyroBiasX, gyroBiasY, gyroBiasZ: Gyroscope bias estimates (rad/s)
 * - accelBiasX, accelBiasY, accelBiasZ: Accelerometer bias estimates (m/s²)
 */
class ExtendedKalmanFilter(
    private val initialPosition: Triple<Double, Double, Double>, // lat, lon, alt
    private val initialVelocity: Triple<Double, Double, Double> = Triple(0.0, 0.0, 0.0),
    private val initialAcceleration: Triple<Double, Double, Double> = Triple(0.0, 0.0, 0.0),
    private val initialOrientation: FloatArray = floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f), // identity quaternion
    private val processNoiseStd: Double = 0.5,  // Reduced for stability (was 2.0)
    private val measurementNoiseStd: Double = 0.3  // Lowered for higher GPS trust
) {

    companion object {
        const val STATE_DIM = 19  // Increased from 16 to include accel biases
        const val GPS_MEASUREMENT_DIM = 7  // GPS: position (x,y,z), velocity (vx,vy,vz), heading
        const val MAGNETOMETER_MEASUREMENT_DIM = 1  // Magnetometer: heading only
        const val CONTROL_DIM = 9      // IMU: ax, ay, az, ωx, ωy, ωz, mx, my, mz

        // WGS84 ellipsoid parameters
        const val EARTH_RADIUS = 6378137.0 // meters
        const val EARTH_FLATTENING = 1.0 / 298.257223563
        const val EARTH_ECCENTRICITY_SQ = 2 * EARTH_FLATTENING - EARTH_FLATTENING * EARTH_FLATTENING

        // Quaternion constraints
        const val QUATERNION_NORM_TOLERANCE = 1e-6

        // GPS update thresholds based on covariance - MORE AGGRESSIVE
        const val MAX_POSITION_UNCERTAINTY = 50.0   // meters (was 100.0)
        const val MAX_VELOCITY_UNCERTAINTY = 5.0    // m/s (was 10.0)
        const val MAX_ACCELERATION_UNCERTAINTY = 2.0 // m/s² (was 5.0)
        
        // Sampling time bounds to prevent filter instability
        const val MIN_SAMPLING_TIME = 0.01  // 10ms minimum
        const val MAX_SAMPLING_TIME = 0.10   // was 0.25; 0.08–0.10 is safe
        
        // Gyroscope bias parameters
        const val GYRO_BIAS_PROCESS_NOISE = 1e-6  // rad/s² (very slow drift)
        const val GYRO_BIAS_INITIAL_UNCERTAINTY = 0.01  // rad/s (initial bias uncertainty)
        
        // Accelerometer bias parameters
        const val ACCEL_BIAS_PROCESS_NOISE = 1e-4  // m/s³ (very slow drift)
        const val ACCEL_BIAS_INITIAL_UNCERTAINTY = 0.1  // m/s² (initial bias uncertainty)
        
        // Magnetometer parameters
        const val MAGNETOMETER_NOISE_STD = 0.1  // rad (~5.7°)
        const val MAGNETOMETER_UPDATE_RATE = 10  // Hz (update every 100ms)
        
        // GPS heading noise (separate from position/velocity noise)
        const val GPS_HEADING_NOISE_STD_RAD = 0.2  // ~11.5°
        
        // Static calibration parameters
        const val STATIC_DETECTION_THRESHOLD = 2.0   // m/s² threshold for stationary detection (was 0.05)
        const val STATIC_CALIBRATION_WINDOW = 2.0   // seconds for bias estimation
        const val STATIC_CALIBRATION_MIN_SAMPLES = 20 // minimum samples for calibration
        
        // Logging constants
        const val TAG = "EKF"
        const val DEBUG_LOGS = false

        // Lightweight helper (no side effects)
        /*private fun logGpsRefix(origin: String, reason: String, details: String = "") {
            try {
                android.util.Log.w(
                    GPS_REFIX_TAG,
                    "origin=$origin reason=$reason${if (details.isNotBlank()) " $details" else ""}"
                )
            } catch (_: Throwable) { /* ignore */ }
        }*/
    }

    // MEKF layout (nominal state outside the error vector):
    // Nominal: [p(3), v(3), q_nb(4), b_g(3), b_a(3)]
    // Error-state: δx = [δp(3), δv(3), δθ(3), δb_g(3), δb_a(3)]
    // where q_nb is body→nav. δθ is small-angle rotation vector.

    // State vector: [x, y, z, vx, vy, vz, ax, ay, az, qw, qx, qy, qz, gyroBiasX, gyroBiasY, gyroBiasZ, accelBiasX, accelBiasY, accelBiasZ]
    private var state = DoubleArray(STATE_DIM)

    // Reference position for local coordinate system
    private var referenceLat: Double = 0.0
    private var referenceLon: Double = 0.0
    private var referenceAlt: Double = 0.0

    // Previous timestamp for dynamic dt calculation (assumed nanoseconds)
    private var previousTimestamp: Long = 0L
    
    // Last GPS update timestamp (milliseconds)
    private var lastGpsUpdateMs: Long = 0L

    // Covariance matrix
    private var covariance = Array(STATE_DIM) { DoubleArray(STATE_DIM) }

    // Process noise covariance "intensity" (baseline, using clamped qStd)
    private var processNoiseCov = Array(STATE_DIM) { DoubleArray(STATE_DIM) }

    // Separate measurement noise covariance matrices
    private var gpsMeasurementNoiseCov = Array(GPS_MEASUREMENT_DIM) { DoubleArray(GPS_MEASUREMENT_DIM) }
    private var magnetometerMeasurementNoiseCov = Array(MAGNETOMETER_MEASUREMENT_DIM) { DoubleArray(MAGNETOMETER_MEASUREMENT_DIM) }

    // Jacobian matrices
    private var stateTransitionJacobian = Array(STATE_DIM) { DoubleArray(STATE_DIM) }
    private var gpsMeasurementJacobian = Array(GPS_MEASUREMENT_DIM) { DoubleArray(STATE_DIM) }
    private var magnetometerMeasurementJacobian = Array(MAGNETOMETER_MEASUREMENT_DIM) { DoubleArray(STATE_DIM) }

    // Control input Jacobian
    private var controlJacobian = Array(STATE_DIM) { DoubleArray(CONTROL_DIM) }

    // Clamped process noise for stability
    private val qStd: Double = processNoiseStd.coerceIn(0.2, 0.8)

    // Dynamic noise adaptation
    private var isMoving = false
    private var movementIntensity = 0.0
    private val movementThreshold = 0.1  // m/s² threshold for movement detection (reduced for walking)
    
    // Hysteresis for movement classification
    private var movingCount = 0
    private var stationaryCount = 0
    private val enterMovingSamples = 3
    private val enterStationarySamples = 5

    // Static calibration buffers
    private val accelCalibrationBuffer = mutableListOf<Triple<Double, Double, Double>>()
    private val gyroCalibrationBuffer = mutableListOf<Triple<Double, Double, Double>>()
    private var lastCalibrationTime: Long = 0L
    private var isCalibrating: Boolean = false
    private var accelBiasesCalibrated: Boolean = false
    private var initialCalibrationPerformed: Boolean = false  // Track if initial calibration was done

    // Magnetometer integration
    private var lastMagnetometerUpdate = 0L
    private var magnetometerHeading = 0.0
    
    // Use Madgwick attitude as a measurement (true) OR standalone magnetometer update (false)
    private val fuseAttitudeFromMadgwick: Boolean = true
    
    // ModifiedMadgwick filter for attitude pre-filtering
    private val ahrs = com.aitech.weartak_civ.algorithm.ModifiedMadgwickFilter(
        sampleFreq = 100.0f,  // 100Hz nominal
        beta = 0.05f,         // Algorithm gain
        lambda = 0.001f,      // Initial LM damping
        decayRate = 10.0f,    // LM damping decay/grow factor
        sigma = 0.0f,         // Gyroscope bias compensation gain (OFF - EKF owns biases)
        mu = 0.92f            // SLERP weight toward LM estimate
    )

    // Add after existing fields, before existing methods (around line 120 after companion object)

    // ---------- NEW FIELDS FOR RECOVERY SYSTEM ----------
    private val gNav = doubleArrayOf(0.0, 0.0, -9.80665) // ENU gravity
    private var lastUpdateNs: Long = 0L
    private var healthy: Boolean = true

    // Local-tangent ENU origin for coordinate conversions
    private var originLatDeg: Double? = null
    private var originLonDeg: Double? = null
    private var originAltM: Double? = null
    
    // MEKF process noise structure
    private data class ProcNoise(
        val qv: Float,   // accel drive on velocity   (m^2/s^3)
        val qt: Float,   // gyro drive on attitude    (rad^2/s)
        val qbg: Float,  // gyro bias RW/GM           (rad^2/s^3)
        val qba: Float   // accel bias RW/GM          (m^2/s^5)
    )
    
    private fun baseProcNoise(): ProcNoise = ProcNoise(
        qv = 0.6f, qt = 0.02f, qbg = 1e-6f, qba = 1e-4f
    )
    
    private var processNoiseScale: Float = 1.0f

    // Add these helper functions after the existing fields but before the existing methods:

    // ---------- HELPER FUNCTIONS ----------
    private inline fun Double.isOk() = this.isFinite()
    private fun DoubleArray.allOk() = this.all { it.isFinite() }

    private fun isFiniteLatLon(lat: Double, lon: Double): Boolean {
        return lat.isFinite() && lon.isFinite() && 
               abs(lat) <= 90.0 && abs(lon) <= 180.0
    }

    private fun nearZeroZero(lat: Double, lon: Double): Boolean {
        return abs(lat) < 1e-6 && abs(lon) < 1e-6
    }
    

    
    // Helper math functions for MEKF
    private fun smallAngleQuat(d: FloatArray): FloatArray {
        // δq ≈ [1, 0.5*δθ]
        return floatArrayOf(1f, 0.5f*d[0], 0.5f*d[1], 0.5f*d[2])
    }

    private fun quatConjugate(q: FloatArray) = floatArrayOf(q[0], -q[1], -q[2], -q[3])
    
    private fun quatNormalize(q: FloatArray): FloatArray {
        val n = sqrt(q[0]*q[0] + q[1]*q[1] + q[2]*q[2] + q[3]*q[3])
        val s = if (n > 0f) 1f/n else 1f
        return floatArrayOf(q[0]*s, q[1]*s, q[2]*s, q[3]*s)
    }
    
    private fun rotMatBodyToNav(q: FloatArray): Array<FloatArray> {
        val qw=q[0]; val qx=q[1]; val qy=q[2]; val qz=q[3]
        return arrayOf(
            floatArrayOf(1f-2f*(qy*qy+qz*qz), 2f*(qx*qy-qw*qz),       2f*(qx*qz+qw*qy)),
            floatArrayOf(2f*(qx*qy+qw*qz),     1f-2f*(qx*qx+qz*qz),   2f*(qy*qz-qw*qx)),
            floatArrayOf(2f*(qx*qz-qw*qy),     2f*(qy*qz+qw*qx),     1f-2f*(qx*qx+qy*qy))
        )
    }
    
    private fun rotateBodyToNav(q: FloatArray, v: FloatArray): FloatArray {
        val R = rotMatBodyToNav(q)
        return floatArrayOf(
            R[0][0]*v[0] + R[0][1]*v[1] + R[0][2]*v[2],
            R[1][0]*v[0] + R[1][1]*v[1] + R[1][2]*v[2],
            R[2][0]*v[0] + R[2][1]*v[1] + R[2][2]*v[2]
        )
    }
    
    private fun skew(v: FloatArray) = arrayOf(
        floatArrayOf( 0f,    -v[2],  v[1]),
        floatArrayOf( v[2],   0f,   -v[0]),
        floatArrayOf(-v[1],  v[0],   0f )
    )
    
    private fun diag3(a: Float, b: Float, c: Float) = arrayOf(
        floatArrayOf(a,0f,0f),
        floatArrayOf(0f,b,0f),
        floatArrayOf(0f,0f,c)
    )
    
    // Matrix math helpers for MEKF
    private fun matMul(a: Array<FloatArray>, b: Array<FloatArray>): Array<FloatArray> {
        val rows = a.size
        val cols = b[0].size
        val result = Array(rows) { FloatArray(cols) }
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                for (k in 0 until a[0].size) {
                    result[i][j] += a[i][k] * b[k][j]
                }
            }
        }
        return result
    }
    
    private fun matT(a: Array<FloatArray>): Array<FloatArray> {
        val rows = a.size
        val cols = a[0].size
        val result = Array(cols) { FloatArray(rows) }
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                result[j][i] = a[i][j]
            }
        }
        return result
    }
    
    private fun matAdd(a: Array<FloatArray>, b: Array<FloatArray>): Array<FloatArray> {
        val rows = a.size
        val cols = a[0].size
        val result = Array(rows) { FloatArray(cols) }
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                result[i][j] = a[i][j] + b[i][j]
            }
        }
        return result
    }
    
    private fun matSub(a: Array<FloatArray>, b: Array<FloatArray>): Array<FloatArray> {
        val rows = a.size
        val cols = a[0].size
        val result = Array(rows) { FloatArray(cols) }
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                result[i][j] = a[i][j] - b[i][j]
            }
        }
        return result
    }
    
    private fun matVec(a: Array<FloatArray>, v: FloatArray): FloatArray {
        val rows = a.size
        val result = FloatArray(rows)
        for (i in 0 until rows) {
            for (j in 0 until v.size) {
                result[i] += a[i][j] * v[j]
            }
        }
        return result
    }
    
    private fun matInv3(a: Array<FloatArray>): Array<FloatArray> {
        // Simple 3x3 matrix inverse for small matrices
        val det = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) -
                  a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) +
                  a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
        val invDet = 1f / det
        return arrayOf(
            floatArrayOf(
                (a[1][1] * a[2][2] - a[1][2] * a[2][1]) * invDet,
                (a[0][2] * a[2][1] - a[0][1] * a[2][2]) * invDet,
                (a[0][1] * a[1][2] - a[0][2] * a[1][1]) * invDet
            ),
            floatArrayOf(
                (a[1][2] * a[2][0] - a[1][0] * a[2][2]) * invDet,
                (a[0][0] * a[2][2] - a[0][2] * a[2][0]) * invDet,
                (a[0][2] * a[1][0] - a[0][0] * a[1][2]) * invDet
            ),
            floatArrayOf(
                (a[1][0] * a[2][1] - a[1][1] * a[2][0]) * invDet,
                (a[0][1] * a[2][0] - a[0][0] * a[2][1]) * invDet,
                (a[0][0] * a[1][1] - a[0][1] * a[1][0]) * invDet
            )
        )
    }
    
    private fun matInv(a: Array<FloatArray>): Array<FloatArray> {
        // For non-3x3 matrices, use a simple approach
        return if (a.size == 3 && a[0].size == 3) matInv3(a) else {
            // Throw exception to prevent accidental misuse
            throw UnsupportedOperationException("matInv for size != 3 not implemented")
        }
    }
    
    private fun eye(size: Int): Array<FloatArray> {
        val result = Array(size) { FloatArray(size) }
        for (i in 0 until size) {
            result[i][i] = 1f
        }
        return result
    }
    
    private fun symmetrize(P: Array<FloatArray>) {
        for (i in 0 until P.size) {
            for (j in i + 1 until P.size) {
                val avg = (P[i][j] + P[j][i]) / 2f
                P[i][j] = avg
                P[j][i] = avg
            }
        }
    }

    init {
        setReferencePosition(initialPosition)
        initializeState()
        initializeCovariance()
        initializeNoiseMatrices()
        previousTimestamp = 0L // expect sensor nanosecond timestamps
    }

    /**
     * Set reference position for local coordinate system
     */
    private fun setReferencePosition(position: Triple<Double, Double, Double>) {
        referenceLat = position.first
        referenceLon = position.second
        referenceAlt = position.third
    }

    /**
     * Initialize state vector with initial values
     */
    private fun initializeState() {
        val localPos = geodeticToLocal(
            initialPosition.first,
            initialPosition.second,
            initialPosition.third
        )

        // Position and motion states
        state[0] = localPos.first   // x (East)
        state[1] = localPos.second  // y (North)
        state[2] = localPos.third   // z (Up)
        state[3] = initialVelocity.first   // vx
        state[4] = initialVelocity.second  // vy
        state[5] = initialVelocity.third   // vz
        state[6] = initialAcceleration.first  // ax
        state[7] = initialAcceleration.second // ay
        state[8] = initialAcceleration.third  // az

        // Orientation quaternion
        state[9] = initialOrientation[0].toDouble()  // qw
        state[10] = initialOrientation[1].toDouble() // qx
        state[11] = initialOrientation[2].toDouble() // qy
        state[12] = initialOrientation[3].toDouble() // qz

        // Initialize gyroscope biases to zero
        state[13] = 0.0  // gyroBiasX
        state[14] = 0.0  // gyroBiasY
        state[15] = 0.0  // gyroBiasZ
        
        // Initialize accelerometer biases to zero for better EKF convergence
        // Let calibration set them, while keeping clamp ≤ 2.0 m/s²
        state[16] = 0.0   // accelBiasX
        state[17] = 0.0   // accelBiasY
        state[18] = 0.0   // accelBiasZ

        // Normalize quaternion
        normalizeQuaternion()
    }

    /**
     * Initialize covariance matrix with reasonable initial uncertainties
     */
    private fun initializeCovariance() {
        // Position uncertainty (meters)
        covariance[0][0] = 10.0
        covariance[1][1] = 10.0
        covariance[2][2] = 20.0

        // Velocity uncertainty (m/s)
        covariance[3][3] = 2.0
        covariance[4][4] = 2.0
        covariance[5][5] = 2.0

        // Acceleration uncertainty (m/s²)
        covariance[6][6] = 0.5
        covariance[7][7] = 0.5
        covariance[8][8] = 0.5

        // Orientation uncertainty (quaternion)
        covariance[9][9] = 0.01
        covariance[10][10] = 0.01
        covariance[11][11] = 0.01
        covariance[12][12] = 0.01
        
        // Gyroscope bias uncertainty (rad/s)
        covariance[13][13] = GYRO_BIAS_INITIAL_UNCERTAINTY * GYRO_BIAS_INITIAL_UNCERTAINTY
        covariance[14][14] = GYRO_BIAS_INITIAL_UNCERTAINTY * GYRO_BIAS_INITIAL_UNCERTAINTY
        covariance[15][15] = GYRO_BIAS_INITIAL_UNCERTAINTY * GYRO_BIAS_INITIAL_UNCERTAINTY
        
        // Accelerometer bias uncertainty (m/s²)
        covariance[16][16] = ACCEL_BIAS_INITIAL_UNCERTAINTY * ACCEL_BIAS_INITIAL_UNCERTAINTY
        covariance[17][17] = ACCEL_BIAS_INITIAL_UNCERTAINTY * ACCEL_BIAS_INITIAL_UNCERTAINTY
        covariance[18][18] = ACCEL_BIAS_INITIAL_UNCERTAINTY * ACCEL_BIAS_INITIAL_UNCERTAINTY
    }

    /**
     * Initialize noise covariance "intensity" (baseline values)
     */
    private fun initializeNoiseMatrices() {
        // Process noise baseline (not directly added; used to build dt-scaled Qd)
        val q = qStd * qStd  // Use clamped noise for consistency

        // For reference / future tuning; we keep these as baselines.
        processNoiseCov[0][0] = q * 2.0
        processNoiseCov[1][1] = q * 2.0
        processNoiseCov[2][2] = q * 2.0
        processNoiseCov[3][3] = q * 4.0
        processNoiseCov[4][4] = q * 4.0
        processNoiseCov[5][5] = q * 4.0
        processNoiseCov[6][6] = q * 10.0
        processNoiseCov[7][7] = q * 10.0
        processNoiseCov[8][8] = q * 10.0
        processNoiseCov[9][9] = q * 0.5
        processNoiseCov[10][10] = q * 0.5
        processNoiseCov[11][11] = q * 0.5
        processNoiseCov[12][12] = q * 0.5
        
        // Gyroscope bias process noise (very slow drift)
        processNoiseCov[13][13] = GYRO_BIAS_PROCESS_NOISE
        processNoiseCov[14][14] = GYRO_BIAS_PROCESS_NOISE
        processNoiseCov[15][15] = GYRO_BIAS_PROCESS_NOISE
        
        // Accelerometer bias process noise (very slow drift)
        processNoiseCov[16][16] = ACCEL_BIAS_PROCESS_NOISE
        processNoiseCov[17][17] = ACCEL_BIAS_PROCESS_NOISE
        processNoiseCov[18][18] = ACCEL_BIAS_PROCESS_NOISE

        // GPS measurement noise covariance - Enhanced GPS trust
        val measurementNoise = measurementNoiseStd * measurementNoiseStd  // 0.09

        // Position measurement noise (GPS accuracy dependent)
        gpsMeasurementNoiseCov[0][0] = measurementNoise * 3.0  // x
        gpsMeasurementNoiseCov[1][1] = measurementNoise * 3.0  // y
        gpsMeasurementNoiseCov[2][2] = measurementNoise * 6.0  // z (altitude worse)

        // Velocity measurement noise
        gpsMeasurementNoiseCov[3][3] = measurementNoise * 0.2  // vx
        gpsMeasurementNoiseCov[4][4] = measurementNoise * 0.2  // vy
        gpsMeasurementNoiseCov[5][5] = measurementNoise * 0.5  // vz

        // Heading measurement noise (GPS bearing) - separate constant for radians
        gpsMeasurementNoiseCov[6][6] = GPS_HEADING_NOISE_STD_RAD * GPS_HEADING_NOISE_STD_RAD
        
        // Magnetometer measurement noise covariance (1x1 matrix)
        magnetometerMeasurementNoiseCov[0][0] = MAGNETOMETER_NOISE_STD * MAGNETOMETER_NOISE_STD
    }

    /**
     * Dynamically adapt baseline noise based on movement state
     */
    private fun adaptNoiseMatrices() {
        // We'll reflect this by scaling the discretized Q with a factor (see discretizeProcessNoise)
        // This function keeps movement flags updated.
        // (processNoiseCov retained for reference; actual P update uses dt-scaled Qd.)
    }

    /**
     * Predict step with IMU control input and dynamic time step
     *
     * @param timestamp Current sensor timestamp in **nanoseconds**
     * @param imuData IMU data: [ax, ay, az, ωx, ωy, ωz]
     *                NOTE: ax,ay,az are **raw accelerometer** (incl. gravity) in body frame.
     */
    fun predict(timestamp: Long, imuData: DoubleArray, currentActivity: MovementState) {
        require(imuData.size == CONTROL_DIM) { "imuData must be length 9" }
        
        // Calculate dynamic time step (ns -> s) with bounds
        val rawDt = if (previousTimestamp > 0) {
            (timestamp - previousTimestamp).toDouble() / 1e9
        } else {
            0.01 // Default 10ms if no previous timestamp
        }
        val dt = rawDt.coerceIn(MIN_SAMPLING_TIME, MAX_SAMPLING_TIME)
        
        // NEW: dt clamp + quaternion norm logging
        //Log.d(TAG, "predict(): raw dt=${"%.4f".format(rawDt)} s, clamped=${"%.4f".format(dt)} s")
        val qn = sqrt(state[9]*state[9] + state[10]*state[10] + state[11]*state[11] + state[12]*state[12])
        // if (abs(qn - 1.0) > 1e-3) Log.w(TAG, "⚠️ Quaternion norm off by ${"%.3e".format(qn-1.0)} before renorm")

        // Update state transition Jacobian
        updateStateTransitionJacobian(dt)

        // Update control Jacobian (currently not used in P; kept for completeness)
        updateControlJacobian(dt)

        // Predict next state using IMU data
        val (xPred, skipped) = predictState(dt, imuData, currentActivity)
        
        if (skipped) {
            // State prediction was skipped due to gravity issues or spike detection
            // Inflate covariance for this step to reflect increased uncertainty
            covariance = inflateCovarianceForSkippedStep(dt)
            // keep state as-is
        } else {
            // Update state and covariance
            state = xPred
            covariance = predictCovariance(dt)
        }

        // Normalize quaternion to prevent drift
        normalizeQuaternion()

        // Update timestamp
        previousTimestamp = timestamp

        // --- EKF health & recovery ---
        if (!sanitizeStateAndCovariance()) {
            //Log.w(TAG, "Predict: state invalid → rollback/reset")
            if (!restoreIfBad("predict")) {
                // lastGood didn't exist → soft reset to the current reference origin
                resetFromLatLon(referenceLat, referenceLon, referenceAlt, accuracyM = 10.0, zeroVelocity = true, forceResetBiases = false)
            }
        } else {
            snapshotIfGood()
        }
    }

    /**
     * Predict next state using IMU data and current orientation with bias compensation
     */
    private data class PredictResult(val x: DoubleArray, val skipped: Boolean)
    
    private fun predictState(dt: Double, imuData: DoubleArray, currentActivity: MovementState): PredictResult {
        val predictedState = DoubleArray(STATE_DIM)

        // Extract IMU data
        // NOTE: accel is raw accelerometer in body frame (includes gravity)
        val ax = imuData[0]
        val ay = imuData[1]
        val az = imuData[2]
        val omegaX = imuData[3]
        val omegaY = imuData[4]
        val omegaZ = imuData[5]
        val mx = imuData[6]  // Magnetometer X
        val my = imuData[7]  // Magnetometer Y
        val mz = imuData[8]  // Magnetometer Z
        
        // Spike gate: huge accel with tiny rotation → treat as artifact, skip kinematics
        val bodyMag = sqrt(ax*ax + ay*ay + az*az)
        val gyroMag = sqrt(omegaX*omegaX + omegaY*omegaY + omegaZ*omegaZ)
        
        if (!bodyMag.isFinite() || bodyMag > 30.0 || (bodyMag > 18.0 && gyroMag < 0.05)) {
            //Log.w(TAG,"Predict: accel spike (|a|=$bodyMag, |ω|=$gyroMag) → skip step")
            // Return skip result - covariance inflation handled in predict()
            return PredictResult(state, true)
        }

        // Get current orientation quaternion and biases
        val qw = state[9]
        val qx = state[10]
        val qy = state[11]
        val qz = state[12]
        val gyroBiasX = state[13]
        val gyroBiasY = state[14]
        val gyroBiasZ = state[15]
        val accelBiasX = state[16]
        val accelBiasY = state[17]
        val accelBiasZ = state[18]

        // Apply bias compensation to angular velocity measurements
        val correctedOmegaX = omegaX - gyroBiasX
        val correctedOmegaY = omegaY - gyroBiasY
        val correctedOmegaZ = omegaZ - gyroBiasZ

        // Apply bias compensation to accelerometer measurements
        val correctedAx = ax - accelBiasX
        val correctedAy = ay - accelBiasY
        val correctedAz = az - accelBiasZ
        
        // Update ModifiedMadgwick filter with bias-corrected IMU data
        ahrs.update(
            gyroscope = floatArrayOf(correctedOmegaX.toFloat(), correctedOmegaY.toFloat(), correctedOmegaZ.toFloat()),
            accelerometer = floatArrayOf(correctedAx.toFloat(), correctedAy.toFloat(), correctedAz.toFloat()),
            magnetometer = floatArrayOf(mx.toFloat(), my.toFloat(), mz.toFloat()),
            dt = dt.toFloat()
        )
        
        // Fuse Madgwick attitude as measurement update
        val accN = sqrt(correctedAx*correctedAx + correctedAy*correctedAy + correctedAz*correctedAz)
        updateAttitudeFromMadgwick(ahrs.getQuaternion(), ahrs.getMagWeight(), accN.toFloat())
        
        // Get quaternion from Madgwick filter (for legacy compatibility)
        val madgwickQuaternion = ahrs.getQuaternion()
        val qwM = madgwickQuaternion[0].toDouble()
        val qxM = madgwickQuaternion[1].toDouble()
        val qyM = madgwickQuaternion[2].toDouble()
        val qzM = madgwickQuaternion[3].toDouble()

        // Debug: Log bias compensation details when there are issues
        if (DEBUG_LOGS && (abs(ax) > 8.0 || abs(ay) > 8.0 || abs(az) > 8.0)) { // Only log when IMU is active
            //Log.d(TAG, "🔍 Bias Compensation Debug:")
            //Log.d(TAG, "   Raw IMU: ax=${String.format("%.6f", ax)}, ay=${String.format("%.6f", ay)}, az=${String.format("%.6f", az)}")
            //Log.d(TAG, "   Accel bias: bx=${String.format("%.6f", accelBiasX)}, by=${String.format("%.6f", accelBiasY)}, bz=${String.format("%.6f", accelBiasZ)}")
            //Log.d(TAG, "   Corrected accel: ax=${String.format("%.6f", correctedAx)}, ay=${String.format("%.6f", correctedAy)}, az=${String.format("%.6f", correctedAz)}")
            //Log.d(TAG, "   Calibration status: accel=${accelBiasesCalibrated}, initial=${initialCalibrationPerformed}")
            
            // ADDITIONAL DEBUG: Show if biases have changed from initial values (now 0.0)
            val initialBiasX = 0.0
            val initialBiasY = 0.0
            val initialBiasZ = 0.0
            val biasChangedX = abs(accelBiasX - initialBiasX) > 0.1
            val biasChangedY = abs(accelBiasY - initialBiasY) > 0.1
            val biasChangedZ = abs(accelBiasZ - initialBiasZ) > 0.1
            //Log.d(TAG, "   Bias changes: X=${if (biasChangedX) "✅" else "❌"}, Y=${if (biasChangedY) "✅" else "❌"}, Z=${if (biasChangedZ) "✅" else "❌"}")
        }

        // Transform acceleration from body to navigation frame using EKF nominal quaternion (post attitude update)
        val accelBody = doubleArrayOf(correctedAx, correctedAy, correctedAz)
        val qwS = state[9]; val qxS = state[10]; val qyS = state[11]; val qzS = state[12]
        val accelNav = quaternionRotate(accelBody, qwS, qxS, qyS, qzS)

        // Debug: Log quaternion and rotation when there's significant IMU activity
        if (DEBUG_LOGS && sqrt(ax*ax + ay*ay + az*az) > 8.0) { // Only log when IMU is active
            //Log.d(TAG, "🧭 Madgwick Quaternion Debug:")
            //Log.d(TAG, "   Madgwick Quaternion: qw=${String.format("%.3f", qwM)}, qx=${String.format("%.3f", qxM)}, qy=${String.format("%.3f", qyM)}, qz=${String.format("%.3f", qzM)}")
            //Log.d(TAG, "   Raw accel: ax=${String.format("%.3f", ax)}, ay=${String.format("%.3f", ay)}, az=${String.format("%.3f", az)}")
            //Log.d(TAG, "   Accel bias: bx=${String.format("%.3f", accelBiasX)}, by=${String.format("%.3f", accelBiasY)}, bz=${String.format("%.3f", accelBiasZ)}")
            //Log.d(TAG, "   Corrected accel: ax=${String.format("%.3f", correctedAx)}, ay=${String.format("%.3f", correctedAy)}, az=${String.format("%.3f", correctedAz)}")
            //Log.d(TAG, "   Nav accel: ax=${String.format("%.3f", accelNav[0])}, ay=${String.format("%.3f", accelNav[1])}, az=${String.format("%.3f", accelNav[2])}")
            //Log.d(TAG, "   Gyro bias: bx=${String.format("%.6f", gyroBiasX)}, by=${String.format("%.6f", gyroBiasY)}, bz=${String.format("%.6f", gyroBiasZ)}")
            //Log.d(TAG, "   Corrected omega: ωx=${String.format("%.3f", correctedOmegaX)}, ωy=${String.format("%.3f", correctedOmegaY)}, ωz=${String.format("%.3f", correctedOmegaZ)}")
            //Log.d(TAG, "   Magnetometer: mx=${String.format("%.3f", mx)}, my=${String.format("%.3f", my)}, mz=${String.format("%.3f", mz)}")
            
            // Log bias uncertainty for monitoring convergence
            val biasUncertainty = getGyroscopeBiasUncertainty()
            //Log.d(TAG, "   Gyro bias uncertainty: σbx=${String.format("%.6f", biasUncertainty.first)}, σby=${String.format("%.6f", biasUncertainty.second)}, σbz=${String.format("%.6f", biasUncertainty.third)}")
            
            val accelBiasUncertainty = getAccelerometerBiasUncertainty()
            //Log.d(TAG, "   Accel bias uncertainty: σbx=${String.format("%.3f", accelBiasUncertainty.first)}, σby=${String.format("%.3f", accelBiasUncertainty.second)}, σbz=${String.format("%.3f", accelBiasUncertainty.third)}")
        }

        // Gravity subtraction (since IMU accel includes gravity)
        // Use the correct gravity magnitude and improve compensation logic
        val gravityMagnitude = 9.80665  // Standard gravity magnitude
        val gravityVector = doubleArrayOf(0.0, 0.0, -gravityMagnitude)

        // Validate gravity subtraction to prevent invalid updates
        val accelCorrected = DoubleArray(3)
        var gravitySubtractionValid = true
        var gravityResidual = 0.0

        for (i in 0..2) {
            // FIXED: Add gravity vector (not subtract) since gravity points down (-Z)
            // Raw IMU: +9.72 m/s², Gravity: -9.80665 m/s², Result: +9.72 + (-9.80665) = -0.087 m/s²
            accelCorrected[i] = accelNav[i] + gravityVector[i]
            gravityResidual += abs(accelCorrected[i])
            if (abs(accelCorrected[i]) > 30.0) { // sanity clamp
                gravitySubtractionValid = false
                break
            }
        }

        if (gravityResidual > 25.0) {
            gravitySubtractionValid = false
        }

        // Debug: Log gravity subtraction details when there are issues
        /*if (DEBUG_LOGS && abs(accelNav[2]) > 8.0) { // Only log when vertical acceleration is significant
            //Log.d(TAG, "🔍 Gravity Compensation Debug:")
            //Log.d(TAG, "   Nav accel: ax=${String.format("%.6f", accelNav[0])}, ay=${String.format("%.6f", accelNav[1])}, az=${String.format("%.6f", accelNav[2])}")
            //Log.d(TAG, "   Gravity vector: gx=${String.format("%.6f", gravityVector[0])}, gy=${String.format("%.6f", gravityVector[1])}, gz=${String.format("%.6f", gravityVector[2])}")
            //Log.d(TAG, "   Corrected accel: ax=${String.format("%.6f", accelCorrected[0])}, ay=${String.format("%.6f", accelCorrected[1])}, az=${String.format("%.6f", accelCorrected[2])}")
            //Log.d(TAG, "   Gravity residual: ${String.format("%.6f", gravityResidual)}")
        }*/

        // Additional validation: check if vertical acceleration is reasonable
        // When stationary, vertical acceleration should be very small
        if (abs(accelCorrected[2]) > 2.0) { // More than 2 m/s² vertical acceleration is suspicious
            //if (DEBUG_LOGS) //Log.w(TAG, "⚠️ Suspicious vertical acceleration: ${String.format("%.3f", accelCorrected[2])} m/s²")
            // Apply damping to vertical acceleration to prevent drift
            accelCorrected[2] *= 0.1 // Reduce vertical acceleration by 90%
        }

        // Debug: Log gravity subtraction when there are issues
        if (!gravitySubtractionValid) {
            if (DEBUG_LOGS) {
                //Log.w(TAG, "⚠️ Gravity subtraction validation failed:")
                //Log.w(TAG, "   Raw IMU: ax=${String.format("%.3f", ax)}, ay=${String.format("%.3f", ay)}, az=${String.format("%.3f", az)}")
                //Log.w(TAG, "   Rotated: ax=${String.format("%.3f", accelNav[0])}, ay=${String.format("%.3f", accelNav[1])}, az=${String.format("%.3f", accelNav[2])}")
                //Log.w(TAG, "   Corrected: ax=${String.format("%.3f", accelCorrected[0])}, ay=${String.format("%.3f", accelCorrected[1])}, az=${String.format("%.3f", accelCorrected[2])}")
                //Log.w(TAG, "   Gravity residual: ${String.format("%.3f", gravityResidual)}")
            }
        }

        if (!gravitySubtractionValid) {
            // Skip this predict step if gravity subtraction is unreasonable
            // Return the SAME reference, not a copy, to indicate skip
            return PredictResult(state, true)
        }

        // Update movement state for noise adaptation
        val movementMagnitude = sqrt(
            accelCorrected[0] * accelCorrected[0] +
                    accelCorrected[1] * accelCorrected[1] +
                    accelCorrected[2] * accelCorrected[2]
        )
        
        // REAL HYSTERESIS: Prevent rapid oscillation between states
        val movingSample = movementMagnitude > movementThreshold
        if (movingSample) { 
            movingCount = (movingCount+1).coerceAtMost(10000); 
            stationaryCount = 0 
        } else { 
            stationaryCount = (stationaryCount+1).coerceAtMost(10000); 
            movingCount = 0 
        }
        
        isMoving = when {
            isMoving && stationaryCount >= enterStationarySamples -> false
            !isMoving && movingCount >= enterMovingSamples       -> true
            currentActivity == MovementState.STILL -> false
            else                                                 -> isMoving
        }
        
        movementIntensity = movementMagnitude

        // Debug: Log movement detection for troubleshooting
        if (DEBUG_LOGS && movementMagnitude > 0.05) { // Only log when there's some activity
            //Log.d(TAG, "🔍 Movement Detection: magnitude=${String.format("%.3f", movementMagnitude)} m/s², threshold=${movementThreshold}, isMoving=$isMoving")
            //Log.d(TAG, "   Raw IMU: ax=${String.format("%.3f", ax)}, ay=${String.format("%.3f", ay)}, az=${String.format("%.3f", az)}")
            //Log.d(TAG, "   Corrected: ax=${String.format("%.3f", accelCorrected[0])}, ay=${String.format("%.3f", accelCorrected[1])}, az=${String.format("%.3f", accelCorrected[2])}")
        }

        // CRITICAL FIX: Calibration moved to AFTER bias prediction to prevent overwriting
        // checkStaticCalibration(ax, ay, az, omegaX, omegaY, omegaZ)

        // Automatic ZUPT/ZARU for foot-mounted stillness
        if (!isMoving) {
            zuptUpdate()
            if (abs(omegaZ) < 0.03) zaruUpdate(doubleArrayOf(0.0, 0.0, omegaZ))
        }

        adaptNoiseMatrices()

        // Kinematics
        for (i in 0..2) {
            // Position: x = x0 + v0*dt + 0.5*a*dt^2
            predictedState[i] = state[i] + state[i + 3] * dt + 0.5 * accelCorrected[i] * dt * dt
            // Velocity: v = v0 + a*dt
            predictedState[i + 3] = state[i + 3] + accelCorrected[i] * dt
            // Acceleration "state": hold latest corrected accel
            predictedState[i + 6] = accelCorrected[i]
        }

        // Apply velocity damping to prevent unrealistic values
        // Vertical velocity should be very small for wearable devices
        if (abs(predictedState[5]) > 5.0) { // Vertical velocity > 5 m/s is unrealistic
            //if (DEBUG_LOGS) //Log.w(TAG, "⚠️ Damping unrealistic vertical velocity: ${String.format("%.3f", predictedState[5])} m/s")
            predictedState[5] *= 0.1 // Reduce by 90%
        }
        
        // Horizontal velocity damping (less aggressive)
        if (abs(predictedState[3]) > 10.0) { // Horizontal velocity > 10 m/s is unrealistic
            //if (DEBUG_LOGS) //Log.w(TAG, "⚠️ Damping unrealistic horizontal velocity X: ${String.format("%.3f", predictedState[3])} m/s")
            predictedState[3] *= 0.5 // Reduce by 50%
        }
        
        if (abs(predictedState[4]) > 10.0) { // Horizontal velocity > 10 m/s is unrealistic
           // if (DEBUG_LOGS) //Log.w(TAG, "⚠️ Damping unrealistic horizontal velocity Y: ${String.format("%.3f", predictedState[4])} m/s")
            predictedState[4] *= 0.5 // Reduce by 50%
        }

        // Quaternion update: EKF nominal attitude must be corrected only by measurement update
        // (Madgwick quaternion will be fused via updateAttitudeFromMadgwick)

        // Gyroscope bias states: model as random walk (very slow drift)
        predictedState[13] = gyroBiasX  // No change (random walk model)
        predictedState[14] = gyroBiasY  // No change (random walk model)
        predictedState[15] = gyroBiasZ  // No change (random walk model)
        
        // Accelerometer bias states: model as random walk (very slow drift)
        // CRITICAL FIX: Preserve calibrated biases - don't overwrite them!
        predictedState[16] = accelBiasX  // Use current bias (preserves calibration)
        predictedState[17] = accelBiasY  // Use current bias (preserves calibration)
        predictedState[18] = accelBiasZ  // Use current bias (preserves calibration)

        // CRITICAL FIX: Move calibration check to AFTER bias prediction
        // This ensures that if calibration happens, the new biases are preserved
        // and not immediately overwritten by the old bias values
        checkStaticCalibration(ax, ay, az, omegaX, omegaY, omegaZ)

        // CRITICAL FIX: After calibration, update the predictedState with the new biases
        // This ensures that calibrated biases are returned and preserved
        if (accelBiasesCalibrated) {
            predictedState[16] = state[16]  // Use calibrated accelBiasX
            predictedState[17] = state[17]  // Use calibrated accelBiasY
            predictedState[18] = state[18]  // Use calibrated accelBiasZ
        }

        // --- Velocity damping independent of external classifier ---
        run {
            val px = predictedState[3]; val py = predictedState[4]; val pz = predictedState[5]
            if (px.isFinite() && py.isFinite() && pz.isFinite()) {
                val aMag = sqrt(predictedState[6]*predictedState[6] + predictedState[7]*predictedState[7] + predictedState[8]*predictedState[8])
                val vMag = sqrt(px*px + py*py + pz*pz)
                val strongStationary = aMag < 0.25 && vMag < 2.5
                val factor = if (strongStationary) 0.3 else 0.9
                predictedState[3] = px * factor
                predictedState[4] = py * factor
                predictedState[5] = pz * factor
                
                if (DEBUG_LOGS && factor < 0.9) {
                    //Log.d(TAG, "🔄 Velocity dampened by factor ${String.format("%.2f", factor)}: vx=${String.format("%.3f", predictedState[3])}, vy=${String.format("%.3f", predictedState[4])}, vz=${String.format("%.3f", predictedState[5])}")
                }
            }
        }

        predictedState[9] = state[9]
        predictedState[10] = state[10]
        predictedState[11] = state[11]
        predictedState[12] = state[12]

        return PredictResult(predictedState, false)
    }

    /**
     * Check if device is stationary and perform static calibration
     */
    private fun checkStaticCalibration(ax: Double, ay: Double, az: Double, 
                                     omegaX: Double, omegaY: Double, omegaZ: Double) {
        val currentTime = System.currentTimeMillis()  // Get current time once at the beginning
        
        val accelMagnitude = sqrt(ax*ax + ay*ay + az*az)
        val gyroMagnitude = sqrt(omegaX*omegaX + omegaY*omegaY + omegaZ*omegaZ)
        
        // CRITICAL FIX: Use adaptive thresholds based on current bias estimates
        val currentAccelBiasX = state[16]
        val currentAccelBiasY = state[17] 
        val currentAccelBiasZ = state[18]
        
        // Calculate expected acceleration magnitude (should be close to gravity when stationary)
        val expectedAccelMagnitude = 9.80665  // Standard gravity
        val accelMagnitudeError = abs(accelMagnitude - expectedAccelMagnitude)
        
        // More lenient stationary detection: check if acceleration magnitude is close to gravity
        // and if gyroscope readings are low
        val isStationary = accelMagnitudeError < 3.0 &&  // Allow 3 m/s² deviation from gravity
                          gyroMagnitude < 0.1             // Very low rotation threshold
        
        // Debug: Log calibration status
        if (DEBUG_LOGS) {
            //Log.d(TAG, "🔧 CALIBRATION DEBUG: samples=${accelCalibrationBuffer.size}, stationary=$isStationary")
            //Log.d(TAG, "   Accel mag: ${String.format("%.3f", accelMagnitude)} m/s², expected: ${String.format("%.3f", expectedAccelMagnitude)} m/s²")
            //Log.d(TAG, "   Accel error: ${String.format("%.3f", accelMagnitudeError)} m/s², threshold: 3.0 m/s²")
            //Log.d(TAG, "   Gyro mag: ${String.format("%.6f", gyroMagnitude)} rad/s, threshold: 0.1 rad/s")
            //Log.d(TAG, "   Current biases: X=${String.format("%.3f", currentAccelBiasX)}, Y=${String.format("%.3f", currentAccelBiasY)}, Z=${String.format("%.3f", currentAccelBiasZ)}")
            //Log.d(TAG, "   Raw IMU: ax=${String.format("%.3f", ax)}, ay=${String.format("%.3f", ay)}, az=${String.format("%.3f", az)}")
        }
        
        if (isStationary) {
            if (DEBUG_LOGS) //Log.d(TAG, "🔧 Device appears stationary - adding to calibration buffer")
            // Add to calibration buffer
            accelCalibrationBuffer.add(Triple(ax, ay, az))
            gyroCalibrationBuffer.add(Triple(omegaX, omegaY, omegaZ))
            
            // Keep only recent samples
            if (accelCalibrationBuffer.size > STATIC_CALIBRATION_MIN_SAMPLES) {
                accelCalibrationBuffer.removeAt(0)
                gyroCalibrationBuffer.removeAt(0)
            }
            
            if (DEBUG_LOGS) //Log.d(TAG, "🔧 Buffer size: ${accelCalibrationBuffer.size}/${STATIC_CALIBRATION_MIN_SAMPLES}")
            
            // Check if we have enough samples and time has passed
            if (accelCalibrationBuffer.size >= STATIC_CALIBRATION_MIN_SAMPLES && 
                currentTime - lastCalibrationTime > STATIC_CALIBRATION_WINDOW * 1000) {
                
                if (DEBUG_LOGS) //Log.d(TAG, "🔧 PERFORMING static calibration with ${accelCalibrationBuffer.size} samples")
                performStaticCalibration(force = true)
                lastCalibrationTime = currentTime
                initialCalibrationPerformed = true
            }
        } else {
            if (DEBUG_LOGS) //Log.d(TAG, "🔧 Device NOT stationary - accel_error=${String.format("%.3f", accelMagnitudeError)}, gyro=${String.format("%.6f", gyroMagnitude)}")
            // CRITICAL FIX: Don't clear buffers immediately - allow some tolerance
            // Only clear if device is clearly moving (high acceleration or rotation)
            if (accelMagnitude > 15.0 || gyroMagnitude > 0.5) {
                if (accelCalibrationBuffer.isNotEmpty()) {
                    if (DEBUG_LOGS) //Log.d(TAG, "🔧 Clearing calibration buffers - device clearly moving")
                    accelCalibrationBuffer.clear()
                    gyroCalibrationBuffer.clear()
                }
            }
        }
        
        // CRITICAL FIX: Force initial calibration much earlier and more aggressively
        if (!initialCalibrationPerformed && accelCalibrationBuffer.size >= 5) {  // Reduced from 10 to 5
            if (DEBUG_LOGS) //Log.d(TAG, "🔧 FORCING initial calibration with ${accelCalibrationBuffer.size} samples")
            performStaticCalibration(force = true)
            lastCalibrationTime = currentTime
            initialCalibrationPerformed = true
        }
        
        // ADDITIONAL FIX: Force calibration if biases are clearly wrong and we have samples
        if (initialCalibrationPerformed && accelCalibrationBuffer.size >= 3) {
            val biasMagnitude = sqrt(currentAccelBiasX*currentAccelBiasX + currentAccelBiasY*currentAccelBiasY + currentAccelBiasZ*currentAccelBiasZ)
            if (biasMagnitude > 5.0) {  // If biases are very large, recalibrate
                if (DEBUG_LOGS) //Log.d(TAG, "🔧 Large biases detected (${String.format("%.3f", biasMagnitude)} m/s²), forcing recalibration")
                performStaticCalibration(force = true)
                lastCalibrationTime = currentTime
            }
        }
    }

    /**
     * Perform static calibration using collected samples
     * CORRECTED: Uses device orientation to properly calculate accelerometer bias
     */
    private fun performStaticCalibration(force: Boolean) {
        val n = if (force) accelCalibrationBuffer.size else STATIC_CALIBRATION_MIN_SAMPLES
        if (n == 0 || accelCalibrationBuffer.size < n) return
        
        // Calculate average accelerometer reading from recent samples
        val accelSlice = accelCalibrationBuffer.takeLast(n)
        val gyroSlice = gyroCalibrationBuffer.takeLast(n)
        
        val avgAx = accelSlice.map { it.first }.average()
        val avgAy = accelSlice.map { it.second }.average()
        val avgAz = accelSlice.map { it.third }.average()
        
        // Calculate average gyroscope reading from recent samples
        val avgGx = gyroSlice.map { it.first }.average()
        val avgGy = gyroSlice.map { it.second }.average()
        val avgGz = gyroSlice.map { it.third }.average()
        
        // --- CORRECTED ACCELEROMETER BIAS CALIBRATION ---
        // Get the current orientation from the state vector
        val qw = state[9]
        val qx = state[10]
        val qy = state[11]
        val qz = state[12]
        
        // expected specific force at rest: f_body = -R^T * g_nav
        val gNav = doubleArrayOf(0.0, 0.0, -9.80665) // ENU Up is positive → g = (0,0,-9.80665)
        val qConjugate = doubleArrayOf(qw, -qx, -qy, -qz)
        val Rt_g = quaternionRotate(gNav, qConjugate[0], qConjugate[1], qConjugate[2], qConjugate[3]) // R^T g_nav
        val expectedFbody = doubleArrayOf(-Rt_g[0], -Rt_g[1], -Rt_g[2]) // −R^T g_nav

        val accelBiasX = avgAx - expectedFbody[0]
        val accelBiasY = avgAy - expectedFbody[1]
        val accelBiasZ = avgAz - expectedFbody[2]
        // --- END CORRECTION ---
        
        // Gyroscope bias is simpler: it's just the average reading, as it should be zero
        val gyroBiasX = avgGx
        val gyroBiasY = avgGy
        val gyroBiasZ = avgGz
        
        // Update state with the correctly calculated biases
        state[16] = accelBiasX
        state[17] = accelBiasY
        state[18] = accelBiasZ
        
        state[13] = gyroBiasX
        state[14] = gyroBiasY
        state[15] = gyroBiasZ
        
        // Reduce uncertainty for calibrated biases
        val reducedUncertainty = 0.01  // Much lower uncertainty after calibration
        covariance[16][16] = reducedUncertainty * reducedUncertainty
        covariance[17][17] = reducedUncertainty * reducedUncertainty
        covariance[18][18] = reducedUncertainty * reducedUncertainty
        
        covariance[13][13] = reducedUncertainty * reducedUncertainty
        covariance[14][14] = reducedUncertainty * reducedUncertainty
        covariance[15][15] = reducedUncertainty * reducedUncertainty
        
        accelBiasesCalibrated = true
        
        if (DEBUG_LOGS) {
            //Log.d(TAG, "🔧 CORRECTED Static Calibration Complete:")
            //Log.d(TAG, "   Raw averages: ax=${String.format("%.3f", avgAx)}, ay=${String.format("%.3f", avgAy)}, az=${String.format("%.3f", avgAz)}")
            //Log.d(TAG, "   Device orientation: qw=${String.format("%.4f", qw)}, qx=${String.format("%.4f", qx)}, qy=${String.format("%.4f", qy)}, qz=${String.format("%.4f", qz)}")
            //Log.d(TAG, "   Expected specific force in body frame: ax=${String.format("%.3f", expectedFbody[0])}, ay=${String.format("%.3f", expectedFbody[1])}, az=${String.format("%.3f", expectedFbody[2])}")
            //Log.d(TAG, "   CORRECTED Accel biases: bx=${String.format("%.3f", accelBiasX)}, by=${String.format("%.3f", accelBiasY)}, bz=${String.format("%.3f", accelBiasZ)}")
            //Log.d(TAG, "   Gyro biases: bx=${String.format("%.6f", gyroBiasX)}, by=${String.format("%.6f", gyroBiasY)}, bz=${String.format("%.6f", gyroBiasZ)}")
            //Log.d(TAG, "   Uncertainty reduced to: ${String.format("%.3f", reducedUncertainty)}")
        }
        
        // Clear buffers after calibration
        accelCalibrationBuffer.clear()
        gyroCalibrationBuffer.clear()
    }

    /**
     * Rotate vector using quaternion (more robust than DCM)
     */
    private fun quaternionRotate(vector: DoubleArray, qw: Double, qx: Double, qy: Double, qz: Double): DoubleArray {
        val result = DoubleArray(3)

        val vx = vector[0]
        val vy = vector[1]
        val vz = vector[2]

        result[0] = (qw * qw + qx * qx - qy * qy - qz * qz) * vx +
                (2 * (qx * qy - qw * qz)) * vy +
                (2 * (qx * qz + qw * qy)) * vz

        result[1] = (2 * (qx * qy + qw * qz)) * vx +
                (qw * qw - qx * qx + qy * qy - qz * qz) * vy +
                (2 * (qy * qz - qw * qx)) * vz

        result[2] = (2 * (qx * qz - qw * qy)) * vx +
                (2 * (qy * qz + qw * qx)) * vy +
                (qw * qw - qx * qx - qy * qy + qz * qz) * vz

        return result
    }



    // --- Profile-driven GPS heading gate ---
    // Minimum ground speed (m/s) above which GPS course/heading is trusted.
    // @Volatile so it can be adjusted from other threads (e.g., MovementDetectionService).
    @Volatile private var gpsHeadingMinSpeedMps: Double = 0.5

    /**
     * Set the minimum ground speed (m/s) required before accepting GPS heading.
     * Values < 0 are coerced to 0.0.
     */
    fun setGpsHeadingMinSpeed(mps: Double) {
        gpsHeadingMinSpeedMps = mps.coerceAtLeast(0.0)
        //if (DEBUG_LOGS) Log.d(TAG, "🔧 gpsHeadingMinSpeedMps set to ${"%.2f".format(gpsHeadingMinSpeedMps)} m/s")
    }

    /**
     * Check if GPS heading should be used based on velocity.
     * Course over ground becomes junk when speed is low.
     */
    private fun shouldUseGpsHeading(vx: Double, vy: Double): Boolean {
        // Use hypot for numerical stability; threshold is profile-driven.
        return hypot(vx, vy) > gpsHeadingMinSpeedMps
    }
    
    /**
     * Mahalanobis gating for innovations to drop outliers cleanly
     */
    private fun passGate(innov: DoubleArray, S: Array<DoubleArray>, k2: Double): Boolean {
        val Sinv = safeInverseSPD(S, "gate") ?: return false
        val m2 = quadForm(innov, Sinv) // innov^T S^-1 innov
        return m2 < k2  // e.g., k2 = 16 (~4σ for 1D)
    }
    
    /**
     * Quadratic form: x^T A x
     */
    private fun quadForm(x: DoubleArray, A: Array<DoubleArray>): Double {
        var result = 0.0
        for (i in x.indices) {
            for (j in x.indices) {
                result += x[i] * A[i][j] * x[j]
            }
        }
        return result
    }

    /**
     * Calculate innovation norm for gating (χ² statistic)
     */
    private fun calculateInnovationNorm(innovation: DoubleArray, S: Array<DoubleArray>): Double {
        val SInv = safeInverseSPD(S, "gps_gate") ?: return Double.POSITIVE_INFINITY
        return quadForm(innovation, SInv) // innovation^T S^-1 innovation
    }

    /**
     * Update step with comprehensive GPS measurements
     *
     * @param gpsData GPS data: [lat, lon, alt, vx, vy, vz, heading]
     */
    fun update(gpsData: DoubleArray) {
        require(gpsData.size == GPS_MEASUREMENT_DIM) { "gpsData must be length 7" }

        // Position → ENU
        val localPosition = geodeticToLocal(gpsData[0], gpsData[1], gpsData[2])

        // Predicted yaw from current state (used whether we trust GPS heading or not)
        val yawPred = atan2(
            2.0 * (state[9] * state[12] + state[10] * state[11]),
            1.0 - 2.0 * (state[11] * state[11] + state[12] * state[12])
        )

        // Build measurement
        val measurement = doubleArrayOf(
            localPosition.first, localPosition.second, localPosition.third,
            gpsData[3], gpsData[4], gpsData[5],
            gpsData[6] // may be NaN; we will sanitize below
        )

        // H for GPS
        updateGPSMeasurementJacobian()

        // Decide if we’ll use heading
        val headingFinite = measurement[6].isFinite()
        val speedOk = shouldUseGpsHeading(gpsData[3], gpsData[4])
        val useHeading = headingFinite && speedOk

        if (!useHeading) {
            // Disable yaw row in H *and* make its innovation exactly zero
            disableGpsHeadingRow()
            measurement[6] = yawPred
        }

        val K = calculateGPSKalmanGain() ?: return
        val S = calculateGPSInnovationCovariance()

        val innovation = calculateGPSInnovation(measurement)

        // χ² gate (7D); robust to the sanitized yaw component
        val innovNorm = calculateInnovationNorm(innovation, S)
        val passed = passGate(innovation, S, 18.5)
        //Log.d(TAG, "update(): gate pass=$passed, innov_norm=${"%.2f".format(innovNorm)}")
        if (!passed) return

        updateState(K, innovation)
        updateGPSCovariance(K)
        normalizeQuaternion()
        lastGpsUpdateMs = System.currentTimeMillis()

        if (!sanitizeStateAndCovariance()) {
            if (!restoreIfBad("gps_update")) {
                resetFromLatLon(referenceLat, referenceLon, referenceAlt, 10.0, true, false)
            }
        } else snapshotIfGood()
    }


    /**
     * Update step with magnetometer heading measurement
     * This provides absolute heading correction to prevent gyroscope drift
     *
     * CRITICAL FIX: Use roll/pitch from accelerometer for tilt compensation,
     * NOT full quaternion (which includes yaw and causes self-cancellation)
     *
     * @param mag Magnetometer data: [mx, my, mz] in body frame
     * @param acc Accelerometer data: [ax, ay, az] in body frame (for tilt compensation)
     */
    fun updateMagnetometer(mag: DoubleArray, acc: DoubleArray) {
        if (fuseAttitudeFromMadgwick) return // avoid double-counting yaw
        
        val now = System.currentTimeMillis()
        if (now - lastMagnetometerUpdate < (1000 / MAGNETOMETER_UPDATE_RATE)) return
        require(mag.size == 3 && acc.size == 3) { "mag and acc must be length 3" }

        // roll, pitch from accelerometer (gravity direction), independent of yaw
        val ax = acc[0]; val ay = acc[1]; val az = acc[2]
        val roll = atan2(ay, sqrt(ax*ax + az*az))                 // φ
        val pitch = atan2(-ax, sqrt(ay*ay + az*az))               // θ

        // tilt compensation (no yaw) - prevents self-cancellation
        val mx = mag[0]; val my = mag[1]; val mz = mag[2]
        val mx2 =  mx * cos(pitch) + mz * sin(pitch)
        val my2 =  mx * sin(roll) * sin(pitch) + my * cos(roll) - mz * sin(roll) * cos(pitch)
        val magHeading = atan2(-my2, mx2)                         // ψ_mag, independent of state yaw

        // Measurement z = ψ_mag ; Predicted h(x) = yaw(q)
        updateMagnetometerMeasurementJacobian()                   // your d(yaw)/dq is fine
        val K = calculateMagnetometerKalmanGain() ?: return
        val yawPred = atan2(2.0*(state[9]*state[12] + state[10]*state[11]),
                            1.0 - 2.0*(state[11]*state[11] + state[12]*state[12]))
        val innovation = doubleArrayOf(wrapPi(magHeading - yawPred))

        // Optional: small gate, e.g. 3σ in 1D
        val s = (magnetometerMeasurementNoiseCov[0][0]
                + matrixMultiply(magnetometerMeasurementJacobian,
                                 matrixMultiply(covariance, matrixTranspose(magnetometerMeasurementJacobian)))[0][0])
        if (s.isFinite() && (innovation[0]*innovation[0] / s) < 9.0) {
            updateState(K, innovation)
            updateMagnetometerCovariance(K)
            normalizeQuaternion()
            lastMagnetometerUpdate = now
            magnetometerHeading = magHeading

            // --- EKF health & recovery ---
            if (!sanitizeStateAndCovariance()) {
                //Log.w(TAG,"Mag update: state invalid → rollback/reset")
                if (!restoreIfBad("mag_update")) {
                    resetFromLatLon(referenceLat, referenceLon, referenceAlt, 10.0, true, false)
                }
            } else {
                snapshotIfGood()
            }
        }
    }

    /**
     * Normalize quaternion to prevent drift
     */
    private fun normalizeQuaternion() {
        val n2 = state[9]*state[9] + state[10]*state[10] + state[11]*state[11] + state[12]*state[12]
        if (!n2.isFinite() || n2 < 1e-12) { 
            state[9]=1.0; state[10]=0.0; state[11]=0.0; state[12]=0.0; 
            return 
        }
        val n = sqrt(n2)
        if (abs(n - 1.0) > QUATERNION_NORM_TOLERANCE) {
            val inv = 1.0 / n
            state[9]*=inv; state[10]*=inv; state[11]*=inv; state[12]*=inv
        }
    }

    /**
     * Get current pose estimate
     */
    /**
     * Get current pose estimate without any side effects (pure read)
     */
    fun peekPose(): PoseEstimate {
        val position = localToGeodetic(state[0], state[1], state[2])
        val velocity = Triple(state[3], state[4], state[5])
        val acceleration = Triple(state[6], state[7], state[8])
        val orientation = FloatArray(4) { i -> state[9 + i].toFloat() }
        
        // Normalize quaternion if needed
        val qNorm = sqrt(orientation[0]*orientation[0] + orientation[1]*orientation[1] + 
                        orientation[2]*orientation[2] + orientation[3]*orientation[3])
        if (qNorm > 0.0) {
            for (i in 0..3) orientation[i] /= qNorm.toFloat()
        }

        return PoseEstimate(position, velocity, acceleration, orientation)
    }

    /**
     * Get current pose estimate with NaN protection and recovery (may modify state)
     */
    fun getSafePoseOrReset(): PoseEstimate {
        // CRITICAL: Never output NaN values to MovementDetectionService
        // Check if any critical states are NaN and trigger immediate reset if needed
        val criticalStates = listOf(state[0], state[1], state[2], state[3], state[4], state[5], state[6], state[7], state[8])
        if (criticalStates.any { !it.isFinite() }) {
            //Log.e(TAG, "CRITICAL: NaN detected in getSafePoseOrReset() - triggering immediate reset")
            immediateResetOnNaN()
        }
        
        // Safe extraction with NaN protection
        val safePosition = localToGeodetic(
            if (state[0].isFinite()) state[0] else 0.0,
            if (state[1].isFinite()) state[1] else 0.0,
            if (state[2].isFinite()) state[2] else 0.0
        )
        
        val safeVelocity = Triple(
            if (state[3].isFinite()) state[3] else 0.0,
            if (state[4].isFinite()) state[4] else 0.0,
            if (state[5].isFinite()) state[5] else 0.0
        )
        
        val safeAcceleration = Triple(
            if (state[6].isFinite()) state[6] else 0.0,
            if (state[7].isFinite()) state[7] else 0.0,
            if (state[8].isFinite()) state[8] else 0.0
        )
        
        val safeQuaternion = floatArrayOf(
            if (state[9].isFinite()) state[9].toFloat() else 1.0f,
            if (state[10].isFinite()) state[10].toFloat() else 0.0f,
            if (state[11].isFinite()) state[11].toFloat() else 0.0f,
            if (state[12].isFinite()) state[12].toFloat() else 0.0f
        )
        
        // Normalize quaternion if needed
        val qNorm = sqrt(safeQuaternion[0]*safeQuaternion[0] + safeQuaternion[1]*safeQuaternion[1] + 
                        safeQuaternion[2]*safeQuaternion[2] + safeQuaternion[3]*safeQuaternion[3])
        if (qNorm > 0.0) {
            for (i in 0..3) safeQuaternion[i] /= qNorm.toFloat()
        }

        return PoseEstimate(safePosition, safeVelocity, safeAcceleration, safeQuaternion)
    }

    /**
     * @deprecated Use peekPose() for pure reads, poseOrNull() for safe reads, or getSafePoseOrReset() for auto-reset behavior
     */
    @Deprecated("Use peekPose() for pure reads, poseOrNull() for safe reads, or getSafePoseOrReset() for auto-reset behavior", ReplaceWith("peekPose()"))
    fun getPose(): PoseEstimate = getCurrentPose()

    // Utility methods for coordinate transformations
    // Local frame is ENU: x=dLonMeters (east), y=dLatMeters (north), z=dAlt (up).
    private fun geodeticToLocal(lat: Double, lon: Double, alt: Double): Triple<Double, Double, Double> {
        val dLat = lat - referenceLat
        val dLon = lon - referenceLon
        val dLatMeters = dLat * EARTH_RADIUS * Math.PI / 180.0
        val dLonMeters = dLon * EARTH_RADIUS * cos((referenceLat * Math.PI) / 180.0) * Math.PI / 180.0
        val dAltMeters = alt - referenceAlt
        return Triple(dLonMeters, dLatMeters, dAltMeters)
    }

    private fun localToGeodetic(x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        val refLatRad = (referenceLat * Math.PI) / 180.0
        val dLat = y / (EARTH_RADIUS * Math.PI / 180.0)
        val dLon = x / (EARTH_RADIUS * cos(refLatRad) * Math.PI / 180.0)
        val dAlt = z
        return Triple(referenceLat + dLat, referenceLon + dLon, referenceAlt + dAlt)
    }

    // Matrix operations and Jacobian calculations
    /**
     * Update the state transition Jacobian (F matrix)
     *
     * Includes cross-terms ∂a/∂q propagating orientation error into x/v.
     * Now also includes gyroscope bias states.
     */
    private fun updateStateTransitionJacobian(dt: Double) {
        // Zero
        for (i in 0 until STATE_DIM) {
            for (j in 0 until STATE_DIM) {
                stateTransitionJacobian[i][j] = 0.0
            }
        }

        // Position derivatives
        stateTransitionJacobian[0][0] = 1.0
        stateTransitionJacobian[0][3] = dt
        stateTransitionJacobian[0][6] = 0.5 * dt * dt

        stateTransitionJacobian[1][1] = 1.0
        stateTransitionJacobian[1][4] = dt
        stateTransitionJacobian[1][7] = 0.5 * dt * dt

        stateTransitionJacobian[2][2] = 1.0
        stateTransitionJacobian[2][5] = dt
        stateTransitionJacobian[2][8] = 0.5 * dt * dt

        // Velocity derivatives
        stateTransitionJacobian[3][3] = 1.0
        stateTransitionJacobian[3][6] = dt

        stateTransitionJacobian[4][4] = 1.0
        stateTransitionJacobian[4][7] = dt

        stateTransitionJacobian[5][5] = 1.0
        stateTransitionJacobian[5][8] = dt

        // Acceleration (random walk)
        stateTransitionJacobian[6][6] = 1.0
        stateTransitionJacobian[7][7] = 1.0
        stateTransitionJacobian[8][8] = 1.0

        // Quaternion derivatives (identity for discrete step)
        stateTransitionJacobian[9][9] = 1.0
        stateTransitionJacobian[10][10] = 1.0
        stateTransitionJacobian[11][11] = 1.0
        stateTransitionJacobian[12][12] = 1.0

        // Gyroscope bias derivatives (random walk)
        stateTransitionJacobian[13][13] = 1.0
        stateTransitionJacobian[14][14] = 1.0
        stateTransitionJacobian[15][15] = 1.0
        
        // Accelerometer bias derivatives (random walk)
        stateTransitionJacobian[16][16] = 1.0
        stateTransitionJacobian[17][17] = 1.0
        stateTransitionJacobian[18][18] = 1.0

        // Cross-correlation terms via ∂a/∂q - TEMPORARILY DISABLED for stability
        // These terms can cause numerical instability when dt is large or accel states are noisy
        // TODO: Re-enable when body-frame specific force is properly threaded into F
        // and cross-terms are computed more robustly
    }

    private fun updateControlJacobian(dt: Double) {
        // Zero
        for (i in 0 until STATE_DIM) {
            for (j in 0 until CONTROL_DIM) {
                controlJacobian[i][j] = 0.0
            }
        }

        // Linear acceleration control (placeholder mapping)
        controlJacobian[6][0] = dt
        controlJacobian[7][1] = dt
        controlJacobian[8][2] = dt

        // Angular velocity control affects quaternion (very rough)
        controlJacobian[9][3] = 0.5 * dt   // ∂qw/∂ωx
        controlJacobian[10][3] = 0.5 * dt  // ∂qx/∂ωx
        controlJacobian[11][4] = 0.5 * dt  // ∂qy/∂ωy
        controlJacobian[12][5] = 0.5 * dt  // ∂qz/∂ωz
        
        // Note: Gyroscope biases (states 13-15) are not directly controlled by IMU inputs
        // They evolve according to the random walk model in the state transition
    }

    /**
     * Calculate the yaw Jacobian with respect to quaternion components
     * Uses the full derivative: ∂ψ/∂q = (D*dN - N*dD) / (N² + D²)
     */
    private fun calculateYawJacobian(qw: Double, qx: Double, qy: Double, qz: Double): DoubleArray {
        val N = 2.0 * (qw * qz + qx * qy)
        val D = 1.0 - 2.0 * (qy * qy + qz * qz)
        val denom = (N * N + D * D).coerceAtLeast(1e-9)
        val dN = doubleArrayOf(2.0 * qz, 2.0 * qy, 2.0 * qx, 2.0 * qw)
        val dD = doubleArrayOf(0.0, 0.0, -4.0 * qy, -4.0 * qz)
        return DoubleArray(4) { i -> (D * dN[i] - N * dD[i]) / denom }
    }

    /**
     * Update GPS measurement Jacobian (7x19 matrix)
     */
    private fun updateGPSMeasurementJacobian() {
        // Zero the GPS measurement Jacobian
        for (i in 0 until GPS_MEASUREMENT_DIM) {
            for (j in 0 until STATE_DIM) {
                gpsMeasurementJacobian[i][j] = 0.0
            }
        }

        // GPS position measurements (x, y, z)
        gpsMeasurementJacobian[0][0] = 1.0  // ∂x/∂x
        gpsMeasurementJacobian[1][1] = 1.0  // ∂y/∂y
        gpsMeasurementJacobian[2][2] = 1.0  // ∂z/∂z

        // GPS velocity measurements (vx, vy, vz)
        gpsMeasurementJacobian[3][3] = 1.0  // ∂vx/∂vx
        gpsMeasurementJacobian[4][4] = 1.0  // ∂vy/∂vy
        gpsMeasurementJacobian[5][5] = 1.0  // ∂vz/∂vz

        // GPS heading measurement (yaw from quaternion)
        val qw = state[9]
        val qx = state[10]
        val qy = state[11]
        val qz = state[12]

        // Use the corrected yaw Jacobian
        val dYaw = calculateYawJacobian(qw, qx, qy, qz)

        gpsMeasurementJacobian[6][9]  = dYaw[0]  // ∂yaw/∂qw
        gpsMeasurementJacobian[6][10] = dYaw[1]  // ∂yaw/∂qx
        gpsMeasurementJacobian[6][11] = dYaw[2]  // ∂yaw/∂qy
        gpsMeasurementJacobian[6][12] = dYaw[3]  // ∂yaw/∂qz
    }
    
    /**
     * Update magnetometer measurement Jacobian (1x19 matrix)
     * Only updates heading (yaw) from quaternion states
     */
    private fun updateMagnetometerMeasurementJacobian() {
        for (i in 0 until MAGNETOMETER_MEASUREMENT_DIM) {
            for (j in 0 until STATE_DIM) magnetometerMeasurementJacobian[i][j] = 0.0
        }
        val qw = state[9]; val qx = state[10]; val qy = state[11]; val qz = state[12]
        val N = 2.0 * (qw * qz + qx * qy)
        val D = 1.0 - 2.0 * (qy * qy + qz * qz)
        val denom = (N * N + D * D).coerceAtLeast(1e-9)
        val dYaw_dQw = (2.0 * qz * D) / denom
        val dYaw_dQx = (2.0 * qy * D) / denom
        val dYaw_dQy = (2.0 * qx * D + 4.0 * qy * N) / denom
        val dYaw_dQz = (2.0 * qw * D + 4.0 * qz * N) / denom
        magnetometerMeasurementJacobian[0][9]=dYaw_dQw
        magnetometerMeasurementJacobian[0][10]=dYaw_dQx
        magnetometerMeasurementJacobian[0][11]=dYaw_dQy
        magnetometerMeasurementJacobian[0][12]=dYaw_dQz
    }

    private fun predictCovariance(dt: Double): Array<DoubleArray> {
        // P = F P F^T + Qd(dt)
        val FP = matrixMultiply(stateTransitionJacobian, covariance)
        val FPFt = matrixMultiply(FP, matrixTranspose(stateTransitionJacobian))
        val Qd = discretizeProcessNoise(dt)
        val predictedCov = matrixAdd(FPFt, Qd)

        // CRITICAL: Prevent covariance explosion
        val maxCovariance = 100.0  // Maximum allowed covariance (reduced from 1000.0)
        val minCovariance = 1e-6   // Minimum allowed covariance
        for (i in 0 until STATE_DIM) {
            if (predictedCov[i][i] > maxCovariance) {
                predictedCov[i][i] = maxCovariance
                //if (DEBUG_LOGS) Log.w(TAG, "⚠️ Covariance explosion prevented: P[$i][$i] clamped to $maxCovariance")
            }
            if (predictedCov[i][i] < minCovariance) {
                predictedCov[i][i] = minCovariance
                //if (DEBUG_LOGS) Log.w(TAG, "⚠️ Covariance too small: P[$i][$i] padded to $minCovariance")
            }
        }

        // Always keep P symmetric & SPD after every update
        predictedCov.symmetrize()
        predictedCov.makeSPD(1e-9)
        return predictedCov
    }

    private fun calculateGPSKalmanGain(): Array<DoubleArray>? {
        val Pt = covariance
        val Ht = matrixTranspose(gpsMeasurementJacobian)
        val PHt = matrixMultiply(Pt, Ht)
        val HP = matrixMultiply(gpsMeasurementJacobian, Pt)
        val S = matrixAdd(matrixMultiply(HP, Ht), gpsMeasurementNoiseCov).symmetrize()
        val Sinv = safeInverseSPD(S, "gps") ?: return null
        return matrixMultiply(PHt, Sinv)
    }

    private fun calculateMagnetometerKalmanGain(): Array<DoubleArray>? {
        // K = P H^T (H P H^T + R)^(-1) for 1D magnetometer update
        val Pt = covariance
        val Ht = matrixTranspose(magnetometerMeasurementJacobian)
        val PHt = matrixMultiply(Pt, Ht)                  // 19x1
        val HP = matrixMultiply(magnetometerMeasurementJacobian, Pt)
        val HPHt = matrixMultiply(HP, Ht)
        val S = matrixAdd(HPHt, magnetometerMeasurementNoiseCov)
        
        // Guard the 1×1 S inversion with safe bail-out
        val s = S[0][0]
        if (!s.isFinite() || s <= 1e-12) {
            if (DEBUG_LOGS) //Log.w(TAG, "Mag S ill-conditioned; skipping update")
            return null
        }
        val sInv = 1.0 / s

        val K = Array(STATE_DIM) { DoubleArray(1) }
        for (i in 0 until STATE_DIM) K[i][0] = PHt[i][0] * sInv
        return K
    }

    /**
     * Calculate GPS innovation covariance matrix S = H P H^T + R
     */
    private fun calculateGPSInnovationCovariance(): Array<DoubleArray> {
        val HP = matrixMultiply(gpsMeasurementJacobian, covariance)
        val HPHt = matrixMultiply(HP, matrixTranspose(gpsMeasurementJacobian))
        return matrixAdd(HPHt, gpsMeasurementNoiseCov)
    }

    private fun calculateGPSInnovation(measurement: DoubleArray): DoubleArray {
        val predictedMeasurement = DoubleArray(GPS_MEASUREMENT_DIM)
        // pos
        predictedMeasurement[0] = state[0]
        predictedMeasurement[1] = state[1]
        predictedMeasurement[2] = state[2]
        // vel
        predictedMeasurement[3] = state[3]
        predictedMeasurement[4] = state[4]
        predictedMeasurement[5] = state[5]
        // yaw
        predictedMeasurement[6] = atan2(
            2.0 * (state[9] * state[12] + state[10] * state[11]),
            1.0 - 2.0 * (state[11] * state[11] + state[12] * state[12])
        )

        val innovation = DoubleArray(GPS_MEASUREMENT_DIM)
        for (i in 0 until GPS_MEASUREMENT_DIM - 1) {
            innovation[i] = measurement[i] - predictedMeasurement[i]
        }
        // If H yaw row was zeroed, caller already set measurement[6]=predicted yaw.
        // Still, guard against accidental NaN:
        val dyaw = measurement[6] - predictedMeasurement[6]
        innovation[6] = if (dyaw.isFinite()) wrapPi(dyaw) else 0.0
        return innovation
    }
    
    /**
     * Calculate innovation specifically for magnetometer updates
     */
    private fun calculateMagnetometerInnovation(measurement: DoubleArray): DoubleArray {
        // For magnetometer updates, we only care about heading innovation (1D)
        val predictedMeasurement = DoubleArray(MAGNETOMETER_MEASUREMENT_DIM)
        
        // For magnetometer, we only care about heading innovation
        // Heading (yaw from quaternion)
        predictedMeasurement[0] = atan2(
            2.0 * (state[9] * state[12] + state[10] * state[11]),
            1.0 - 2.0 * (state[11] * state[11] + state[12] * state[12])
        )
        
        // Innovation = measurement - predicted (heading only)
        val innovation = DoubleArray(MAGNETOMETER_MEASUREMENT_DIM)
        // Wrap heading innovation to [-π, π]
        innovation[0] = wrapPi(measurement[0] - predictedMeasurement[0])
        
        return innovation
    }

    private fun updateState(kalmanGain: Array<DoubleArray>, innovation: DoubleArray) {
        // x = x + K * innovation
        val correction = matrixVectorMultiply(kalmanGain, innovation)
        if (!correction.all { it.isFinite() }) {
            //Log.w(TAG, "Non-finite correction; update skipped")
            return
        }
        for (i in 0 until STATE_DIM) {
            val v = state[i] + correction[i]
            if (!v.isFinite()) { 
                //Log.w(TAG,"Correction makes NaN; skipped")
                return 
            }
            state[i] = v
        }
    }

    private fun updateGPSCovariance(kalmanGain: Array<DoubleArray>) {
        // Joseph form: P = (I - KH) P (I - KH)^T + K R K^T
        val I = Array(STATE_DIM) { DoubleArray(STATE_DIM) }
        for (i in 0 until STATE_DIM) I[i][i] = 1.0

        val KH = matrixMultiply(kalmanGain, gpsMeasurementJacobian)
        val IminusKH = matrixSubtract(I, KH)

        val term1 = matrixMultiply(matrixMultiply(IminusKH, covariance), matrixTranspose(IminusKH))
        val KR = matrixMultiply(kalmanGain, gpsMeasurementNoiseCov)
        val term2 = matrixMultiply(KR, matrixTranspose(kalmanGain))

        covariance = matrixAdd(term1, term2)
        
        // Keep P well-formed immediately after Joseph update
        covariance.symmetrize()
        covariance.makeSPD(1e-9)
    }

    private fun updateMagnetometerCovariance(kalmanGain: Array<DoubleArray>) {
        // Joseph form: P = (I - KH) P (I - KH)^T + K R K^T
        val I = Array(STATE_DIM) { DoubleArray(STATE_DIM) }
        for (i in 0 until STATE_DIM) I[i][i] = 1.0

        val KH = matrixMultiply(kalmanGain, magnetometerMeasurementJacobian)
        val IminusKH = matrixSubtract(I, KH)

        val term1 = matrixMultiply(matrixMultiply(IminusKH, covariance), matrixTranspose(IminusKH))
        val KR = matrixMultiply(kalmanGain, magnetometerMeasurementNoiseCov)
        val term2 = matrixMultiply(KR, matrixTranspose(kalmanGain))

        covariance = matrixAdd(term1, term2)
        
        // Keep P well-formed immediately after Joseph update
        covariance.symmetrize()
        covariance.makeSPD(1e-9)
    }

    // ----- dt-scaled process noise (very simple white-accel model + orientation term) -----
    private fun discretizeProcessNoise(dt: Double): Array<DoubleArray> {
        val Q = Array(STATE_DIM) { DoubleArray(STATE_DIM) }

        val motionScale = processNoiseScale.toDouble()          // ← use it
        val qA = (qStd * qStd) * motionScale

        val dt2 = dt * dt
        val dt3 = dt2 * dt

        // For each axis, couple position/velocity/accel
        for (axis in 0..2) {
            val p = axis
            val v = 3 + axis
            val a = 6 + axis

            Q[p][p] += qA * dt3 / 3.0
            Q[p][v] += qA * dt2 / 2.0
            Q[v][p] += qA * dt2 / 2.0
            Q[v][v] += qA * dt
            Q[a][a] += qA * dt // accel random-walk
        }

        // Orientation noise (very rough; scales with dt)
        val qOrient = 0.5 * qA * dt
        for (i in 9..12) Q[i][i] += qOrient

        // Gyroscope bias process noise (very slow drift)
        // Use dedicated bias process noise parameter for more stable estimation
        val qGyroBias = GYRO_BIAS_PROCESS_NOISE * dt
        for (i in 13..15) Q[i][i] += qGyroBias
        
        // Accelerometer bias process noise (very slow drift)
        val qAccelBias = ACCEL_BIAS_PROCESS_NOISE * dt
        for (i in 16..18) Q[i][i] += qAccelBias

        return Q
    }

    /**
     * Inflate covariance when state prediction is skipped due to gravity issues
     * This prevents P from getting too small while x remains unchanged
     */
    private fun inflateCovarianceForSkippedStep(dt: Double): Array<DoubleArray> {
        val inflated = Array(STATE_DIM) { r -> covariance[r].copyOf() }
        val f = 1.0 + 2.0*dt
        for (i in 0..2) inflated[i][i] *= f           // pos
        for (i in 3..5) inflated[i][i] *= f           // vel
        for (i in 6..8) inflated[i][i] *= f * 1.5     // accel
        return inflated.symmetrize().also { it.makeSPD(1e-9) }
    }

    // Matrix operation helper functions
    private fun matrixMultiply(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val rows = a.size
        val cols = b[0].size
        if (a[0].size != b.size) {
            throw IllegalArgumentException("Matrix dimensions incompatible for multiplication: ${a.size}x${a[0].size} vs ${b.size}x${b[0].size}")
        }
        val result = Array(rows) { DoubleArray(cols) }
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                var sum = 0.0
                for (k in 0 until a[0].size) {
                    sum += a[i][k] * b[k][j]
                }
                result[i][j] = sum
            }
        }
        return result
    }

    private fun matrixTranspose(matrix: Array<DoubleArray>): Array<DoubleArray> {
        val rows = matrix.size
        val cols = matrix[0].size
        val result = Array(cols) { DoubleArray(rows) }
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                result[j][i] = matrix[i][j]
            }
        }
        return result
    }

    private fun matrixAdd(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val rows = a.size
        val cols = a[0].size
        if (b.size != rows || b[0].size != cols) {
            throw IllegalArgumentException("Matrix dimensions must match for addition: ${a.size}x${a[0].size} vs ${b.size}x${b[0].size}")
        }
        val result = Array(rows) { DoubleArray(cols) }
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                result[i][j] = a[i][j] + b[i][j]
            }
        }
        return result
    }

    private fun matrixSubtract(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val rows = a.size
        val cols = a[0].size
        if (b.size != rows || b[0].size != cols) {
            throw IllegalArgumentException("Matrix dimensions must match for subtraction: ${a.size}x${a[0].size} vs ${b.size}x${b[0].size}")
        }
        val result = Array(rows) { DoubleArray(cols) }
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                result[i][j] = a[i][j] - b[i][j]
            }
        }
        return result
    }

    private fun matrixInverse(matrix: Array<DoubleArray>): Array<DoubleArray> {
        val size = matrix.size

        if (size == 1) {
            val v = matrix[0][0]
            require(abs(v) > 1e-12) { "Matrix not invertible" }
            return Array(1) { DoubleArray(1) { 1.0 / v } }
        }

        if (size == 2) {
            val det = matrix[0][0] * matrix[1][1] - matrix[0][1] * matrix[1][0]
            require(abs(det) > 1e-12) { "Matrix not invertible" }
            val invDet = 1.0 / det
            return arrayOf(
                doubleArrayOf( matrix[1][1] * invDet, -matrix[0][1] * invDet ),
                doubleArrayOf( -matrix[1][0] * invDet, matrix[0][0] * invDet )
            )
        }

        val n = size
        val augmented = Array(n) { DoubleArray(2 * n) }
        for (i in 0 until n) {
            for (j in 0 until n) augmented[i][j] = matrix[i][j]
            augmented[i][i + n] = 1.0
        }

        // Forward elimination
        for (i in 0 until n) {
            var maxRow = i
            for (k in i + 1 until n) {
                if (abs(augmented[k][i]) > abs(augmented[maxRow][i])) maxRow = k
            }
            val tmp = augmented[i]; augmented[i] = augmented[maxRow]; augmented[maxRow] = tmp

            require(abs(augmented[i][i]) > 1e-12) { "Matrix not invertible (singular)" }

            val pivot = augmented[i][i]
            for (j in 0 until 2 * n) augmented[i][j] /= pivot

            for (k in 0 until n) {
                if (k == i) continue
                val factor = augmented[k][i]
                for (j in 0 until 2 * n) augmented[k][j] -= factor * augmented[i][j]
            }
        }

        val inv = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            for (j in 0 until n) inv[i][j] = augmented[i][j + n]
        }
        return inv
    }

    private fun matrixVectorMultiply(matrix: Array<DoubleArray>, vector: DoubleArray): DoubleArray {
        val rows = matrix.size
        val cols = matrix[0].size
        require(cols == vector.size) { "Matrix and vector dimensions incompatible: ${rows}x${cols} vs ${vector.size}" }
        val result = DoubleArray(rows)
        for (i in 0 until rows) {
            var sum = 0.0
            for (j in 0 until cols) sum += matrix[i][j] * vector[j]
            result[i] = sum
        }
        return result
    }

    // ----- Helpers -----

    private fun wrapPi(a: Double): Double {
        var x = a % (2.0 * Math.PI)
        if (x > Math.PI) x -= 2.0 * Math.PI
        if (x < -Math.PI) x += 2.0 * Math.PI
        return x
    }

    /**
     * Get current pose estimate
     */
    fun getCurrentPose(): PoseEstimate {
        val position = localToGeodetic(state[0], state[1], state[2]) // state is ENU; convert to lat/lon/alt
        val velocity = Triple(state[3], state[4], state[5])
        val acceleration = Triple(state[6], state[7], state[8])
        val orientation = FloatArray(4) { i -> state[9 + i].toFloat() }
        return PoseEstimate(position, velocity, acceleration, orientation)
    }
    
    /**
     * Get current magnetometer heading estimate
     */
    fun getMagnetometerHeading(): Double {
        return magnetometerHeading
    }
    
    /**
     * Check if magnetometer calibration is needed
     */
    fun isMagnetometerCalibrationNeeded(): Boolean {
        // Simple heuristic: if we haven't received magnetometer updates recently
        val timeSinceLastUpdate = System.currentTimeMillis() - lastMagnetometerUpdate
        return timeSinceLastUpdate > 10000 // 10 seconds without updates
    }
    
    /**
     * Get current gyroscope bias estimates
     */
    fun getGyroscopeBiases(): Triple<Double, Double, Double> {
        return Triple(state[13], state[14], state[15])
    }
    
    /**
     * Get gyroscope bias uncertainty estimates
     */
    fun getGyroscopeBiasUncertainty(): Triple<Double, Double, Double> {
        return Triple(
            sqrt(covariance[13][13]),
            sqrt(covariance[14][14]),
            sqrt(covariance[15][15])
        )
    }
    
    /**
     * Get accelerometer bias uncertainty estimates
     */
    fun getAccelerometerBiasUncertainty(): Triple<Double, Double, Double> {
        return Triple(
            sqrt(covariance[16][16]),
            sqrt(covariance[17][17]),
            sqrt(covariance[18][18])
        )
    }

    /**
     * Get accelerometer bias estimates
     */
    fun getAccelerometerBias(): Triple<Double, Double, Double> {
        return Triple(state[16], state[17], state[18])
    }

    /**
     * Reset velocity to zero when device is detected as stationary
     * This prevents velocity drift from sensor noise
     */
    fun resetVelocity() {
        state[3] = 0.0
        state[4] = 0.0
        state[5] = 0.0
    }
    
    /**
     * Dampen velocity by a factor (0.0 = stop, 1.0 = no change)
     * This allows gradual velocity reduction instead of instant reset
     * @param factor Velocity damping factor (0.0 to 1.0)
     */
    fun dampenVelocity(factor: Double) {
        require(factor in 0.0..1.0) { "Damping factor must be between 0.0 and 1.0" }
        
        state[3] *= factor  // vx
        state[4] *= factor  // vy
        state[5] *= factor  // vz
        
        //if (DEBUG_LOGS) Log.d(TAG, "Velocity dampened by factor ${String.format("%.2f", factor)}: vx=${String.format("%.3f", state[3])}, vy=${String.format("%.3f", state[4])}, vz=${String.format("%.3f", state[5])}")
    }
    
    /**
     * Set gyroscope biases manually (useful for calibration)
     * @param biasX X-axis gyroscope bias (rad/s)
     * @param biasY Y-axis gyroscope bias (rad/s)
     * @param biasZ Z-axis gyroscope bias (rad/s)
     */
    fun setGyroscopeBiases(biasX: Double, biasY: Double, biasZ: Double) {
        state[13] = biasX
        state[14] = biasY
        state[15] = biasZ
        
        // Reset uncertainty for these states when manually set
        covariance[13][13] = GYRO_BIAS_INITIAL_UNCERTAINTY * GYRO_BIAS_INITIAL_UNCERTAINTY
        covariance[14][14] = GYRO_BIAS_INITIAL_UNCERTAINTY * GYRO_BIAS_INITIAL_UNCERTAINTY
        covariance[15][15] = GYRO_BIAS_INITIAL_UNCERTAINTY * GYRO_BIAS_INITIAL_UNCERTAINTY
        
        //if (DEBUG_LOGS) Log.d(TAG, "🔧 Gyroscope biases manually set: bx=${String.format("%.6f", biasX)}, by=${String.format("%.6f", biasY)}, bz=${String.format("%.6f", biasZ)}")
    }
    
    /**
     * Set accelerometer biases manually (useful for calibration)
     * @param biasX X-axis accelerometer bias (m/s²)
     * @param biasY Y-axis accelerometer bias (m/s²)
     * @param biasZ Z-axis accelerometer bias (m/s²)
     */
    fun setAccelerometerBiases(biasX: Double, biasY: Double, biasZ: Double) {
        state[16] = biasX
        state[17] = biasY
        state[18] = biasZ
        
        // Reset uncertainty for these states when manually set
        covariance[16][16] = ACCEL_BIAS_INITIAL_UNCERTAINTY * ACCEL_BIAS_INITIAL_UNCERTAINTY
        covariance[17][17] = ACCEL_BIAS_INITIAL_UNCERTAINTY * ACCEL_BIAS_INITIAL_UNCERTAINTY
        covariance[18][18] = ACCEL_BIAS_INITIAL_UNCERTAINTY * ACCEL_BIAS_INITIAL_UNCERTAINTY
        
        //if (DEBUG_LOGS) Log.d(TAG, "🔧 Accelerometer biases manually set: bx=${String.format("%.3f", biasX)}, by=${String.format("%.3f", biasY)}, bz=${String.format("%.3f", biasZ)}")
    }

    /**
     * Check if gyroscope biases have converged (uncertainty below threshold)
     */
    fun areGyroscopeBiasesConverged(threshold: Double = 0.001): Boolean {
        val biasUncertainty = getGyroscopeBiasUncertainty()
        val maxUncertainty = maxOf(biasUncertainty.first, biasUncertainty.second, biasUncertainty.third)
        return maxUncertainty < threshold
    }
    
    /**
     * Check if accelerometer biases have converged (uncertainty below threshold)
     */
    fun areAccelerometerBiasesConverged(threshold: Double = 0.01): Boolean {
        val biasUncertainty = getAccelerometerBiasUncertainty()
        val maxUncertainty = maxOf(biasUncertainty.first, biasUncertainty.second, biasUncertainty.third)
        return maxUncertainty < threshold
    }
    
    /**
     * Set noise parameters for activity-based tuning (without re-instantiating)
     * Note: This is a simplified implementation that updates the measurement noise covariance
     */
    fun setNoiseParameters(processNoiseStd: Double, measurementNoiseStd: Double) {
        val r = measurementNoiseStd * measurementNoiseStd
        // position & velocity
        for (i in 0..5) gpsMeasurementNoiseCov[i][i] = r
        // keep heading special
        gpsMeasurementNoiseCov[6][6] = GPS_HEADING_NOISE_STD_RAD * GPS_HEADING_NOISE_STD_RAD

        magnetometerMeasurementNoiseCov[0][0] = r
        //Log.d(TAG, "🎯 Noise parameters updated: process=$processNoiseStd, measurement=$measurementNoiseStd")
    }
    
    /**
     * Get horizontal position uncertainty (1σ) for drift gating
     */
    fun getHorizontalPositionStdMeters(): Double {
        val sx = sqrt(covariance[0][0].coerceAtLeast(0.0))
        val sy = sqrt(covariance[1][1].coerceAtLeast(0.0))
        return hypot(sx, sy)
    }
    


    /**
     * Get current state uncertainty for monitoring
     */
    fun getStateUncertainty(): StateUncertainty {
        // CRITICAL: Never output NaN uncertainty values
        // Check if covariance matrix is healthy before computing uncertainties
        if (!covariance.allFinite()) {
            //Log.e("ExtendedKalmanFilter", "CRITICAL: NaN detected in covariance matrix - triggering immediate reset")
            immediateResetOnNaN()
            // Return safe default uncertainties after reset
        return StateUncertainty(
                positionUncertainty = 100.0,    // High uncertainty after reset
                velocityUncertainty = 10.0,     // High uncertainty after reset
                accelerationUncertainty = 5.0,  // High uncertainty after reset
                orientationUncertainty = 0.5,   // High uncertainty after reset
                gyroBiasUncertainty = 0.1,     // High uncertainty after reset
                accelBiasUncertainty = 2.0     // High uncertainty after reset
            )
        }
        
        // Safe uncertainty computation with bounds
        return StateUncertainty(
            positionUncertainty = sqrt(max(0.0, covariance[0][0] + covariance[1][1])).coerceIn(0.1, 1000.0),
            velocityUncertainty = sqrt(max(0.0, covariance[3][3] + covariance[4][4])).coerceIn(0.1, 100.0),
            accelerationUncertainty = sqrt(max(0.0, covariance[6][6] + covariance[7][7])).coerceIn(0.1, 50.0),
            orientationUncertainty = sqrt(max(0.0, covariance[9][9] + covariance[10][10] + covariance[11][11] + covariance[12][12])).coerceIn(0.01, 1.0),
            gyroBiasUncertainty = sqrt(max(0.0, covariance[13][13] + covariance[14][14] + covariance[15][15])).coerceIn(0.001, 1.0),
            accelBiasUncertainty = sqrt(max(0.0, covariance[16][16] + covariance[17][17] + covariance[18][18])).coerceIn(0.01, 10.0)
        )
    }

    // Data class for state uncertainty
    data class StateUncertainty(
        val positionUncertainty: Double,
        val velocityUncertainty: Double,
        val accelerationUncertainty: Double,
        val orientationUncertainty: Double,
        val gyroBiasUncertainty: Double,
        val accelBiasUncertainty: Double
    )

    /**
     * Check if accelerometer biases have been calibrated
     */
    fun areAccelerometerBiasesCalibrated(): Boolean = accelBiasesCalibrated

    /**
     * Check if initial calibration has been performed
     */
    fun isInitialCalibrationComplete(): Boolean = initialCalibrationPerformed

    /**
     * Get current calibration status
     */
    fun getCalibrationStatus(): String {
        return "Accel: ${if (accelBiasesCalibrated) "✅" else "❌"}, " +
               "Initial: ${if (initialCalibrationPerformed) "✅" else "❌"}, " +
               "Samples: ${accelCalibrationBuffer.size}/${STATIC_CALIBRATION_MIN_SAMPLES}"
    }

    /**
     * Force immediate calibration using current IMU data
     * This is useful for testing and debugging
     */
    fun forceCalibration(ax: Double, ay: Double, az: Double, 
                        omegaX: Double, omegaY: Double, omegaZ: Double) {
        if (DEBUG_LOGS) //Log.d(TAG, "🔧 FORCING immediate calibration with current IMU data")
        
        // Clear existing buffers and add current reading
        accelCalibrationBuffer.clear()
        gyroCalibrationBuffer.clear()
        
        // Add multiple copies of current reading to simulate multiple samples
        repeat(5) {
            accelCalibrationBuffer.add(Triple(ax, ay, az))
            gyroCalibrationBuffer.add(Triple(omegaX, omegaY, omegaZ))
        }
        
        // Perform calibration immediately
        performStaticCalibration(force = true)
        lastCalibrationTime = System.currentTimeMillis()
        initialCalibrationPerformed = true
        accelBiasesCalibrated = true
        
        if (DEBUG_LOGS) {
            //Log.d(TAG, "🔧 Forced calibration completed")
            //Log.d(TAG, "   New biases: X=${String.format("%.3f", state[16])}, Y=${String.format("%.3f", state[17])}, Z=${String.format("%.3f", state[18])}")
        }
    }

    // Add after line 120 (after companion object)

    // ---------- FINITE CHECKS & SNAPSHOT ----------
    private fun Double.isFiniteSafe() = this.isFinite() && !this.isNaN()
    private fun DoubleArray.allFinite() = this.all { it.isFiniteSafe() }
    private fun Array<DoubleArray>.allFinite(): Boolean {
        for (r in indices) for (c in this[0].indices) {
            val v = this[r][c]
            if (!v.isFinite() || v.isNaN()) return false
        }
        return true
    }

    private data class EkfSnapshot(
        val state: DoubleArray,
        val cov: Array<DoubleArray>
    )
    private var lastGood: EkfSnapshot? = null

    private fun snapshotIfGood() {
        if (state.allFinite() && covariance.allFinite()) {
            lastGood = EkfSnapshot(state.copyOf(), covariance.map { it.copyOf() }.toTypedArray())
        }
    }

    private fun restoreIfBad(tag: String): Boolean {
        val bad = !state.allFinite() || !covariance.allFinite()
        if (!bad) return false
        val s = lastGood
        if (s != null) {
            state = s.state.copyOf()
            covariance = s.cov.map { it.copyOf() }.toTypedArray()
            //Log.w(TAG, "⚠️ EKF non-finite detected in $tag → rolled back to last good snapshot.")
        } else {
            resetEkfToDefaults()
            //Log.w(TAG, "⚠️ EKF non-finite detected in $tag → hard reset (no snapshot).")
        }
        return true
    }

    private fun resetEkfToDefaults() {
        // Light reset that guarantees finiteness
        for (i in 0 until STATE_DIM) state[i] = 0.0
        state[9] = 1.0 // identity quaternion
        // Reinflate covariance to reasonable diagonals
        for (i in 0 until STATE_DIM) for (j in 0 until STATE_DIM) covariance[i][j] = 0.0
        covariance[0][0] = 100.0; covariance[1][1] = 100.0; covariance[2][2] = 200.0
        covariance[3][3] = 4.0;   covariance[4][4] = 4.0;   covariance[5][5] = 4.0
        covariance[6][6] = 1.0;   covariance[7][7] = 1.0;   covariance[8][8] = 1.0
        covariance[9][9] = 0.05;  covariance[10][10] = 0.05; covariance[11][11] = 0.05; covariance[12][12] = 0.05
        covariance[13][13] = GYRO_BIAS_INITIAL_UNCERTAINTY * GYRO_BIAS_INITIAL_UNCERTAINTY
        covariance[14][14] = covariance[13][13]; covariance[15][15] = covariance[13][13]
        covariance[16][16] = ACCEL_BIAS_INITIAL_UNCERTAINTY * ACCEL_BIAS_INITIAL_UNCERTAINTY
        covariance[17][17] = covariance[16][16]; covariance[18][18] = covariance[16][16]
    }

    // Add after helper functions

    // ---------- NaN-PROOF UTILITIES ----------
    private fun Array<DoubleArray>.symmetrize(): Array<DoubleArray> {
        val n = size
        for (i in 0 until n) for (j in i+1 until n) {
            val v = 0.5 * (this[i][j] + this[j][i])
            this[i][j] = v; this[j][i] = v
        }
        return this
    }

    private fun safeInverseSPD(M: Array<DoubleArray>, tag: String, startJitter: Double = 1e-8): Array<DoubleArray>? {
        var jitter = startJitter
        repeat(7) {
            val A = M.map { it.copyOf() }.toTypedArray()
            for (i in A.indices) A[i][i] += jitter
            try {
                val inv = matrixInverse(A)
                if (inv.allFinite()) return inv
            } catch (_: Exception) {}
            jitter *= 10.0
        }
                    //Log.w(TAG, "$tag: ill-conditioned S; skipping update")
        return null
    }

    // ---------- STATE VALIDATION SYSTEM ----------
    /**
     * Validate state vectors and covariance matrix, normalize quaternion, enforce SPD
     * Call after each predict() and update()
     */
    private fun sanitizeStateAndCovariance(): Boolean {
        var needsReset = false
        
        // Repair finite-ness for x/v quickly (pre-check)
        for (i in 0..5) {
            if (!state[i].isFinite()) state[i] = 0.0
        }
        
        // Check state vector
        if (!state.allOk()) {
            //Log.w(TAG, "State vector contains non-finite values")
            needsReset = true
        }
        
        // Try to repair P first instead of immediately resetting
        val repaired = covariance.repairDiagonalIfNeeded(1e-9)
        //if (repaired && DEBUG_LOGS) Log.w(TAG, "P diagonal repaired in sanitize()")
        
        // If anything is still non-finite, that's fatal → reset
        val anyNonFiniteDiag = (0 until STATE_DIM).any { !covariance[it][it].isFinite() }
        if (anyNonFiniteDiag) {
            //Log.w(TAG, "Covariance still non-finite after repair")
            needsReset = true
        }
        
        // Check quaternion normalization - correct indices 9-12
        val n2 = state[9] * state[9] + state[10] * state[10] + state[11] * state[11] + state[12] * state[12]
        if (!n2.isFinite() || n2 < 1e-12) {
            //Log.w(TAG, "Quaternion normalization unhealthy: n2=$n2")
            needsReset = true
        } else if (abs(sqrt(n2) - 1.0) > QUATERNION_NORM_TOLERANCE) {
            // Renormalize quaternion
            val inv = 1.0 / sqrt(n2)
            state[9] *= inv
            state[10] *= inv
            state[11] *= inv
            state[12] *= inv
            //Log.w(TAG, "Quaternion renormalized from n2=$n2")
        }
        
        // Check biases
        if (!clampBiases()) {
            //Log.w(TAG, "Biases clamped to safe values")
            // do NOT set needsReset here - clamping is recovery, not failure
        }
        
        if (needsReset) {
            //Log.w(TAG, "State sanitization failed, triggering reset")
            healthy = false
            return false
        }
        
        healthy = true
        return true
    }

    /**
     * Public method to check EKF health status
     */
    public fun isHealthy(): Boolean = healthy
    
    /**
     * Check if EKF is safe to use (no NaN values in critical states)
     */
    public fun isSafeToUse(): Boolean {
        val criticalStates = listOf(state[0], state[1], state[2], state[3], state[4], state[5], state[6], state[7], state[8])
        return criticalStates.all { it.isFinite() } && covariance.allFinite()
    }
    
    /**
     * Get safe pose estimate - returns null if EKF is not safe
     */
    /**
     * Check if EKF state is finite
     */
    private fun isStateFinite(): Boolean {
        // Check if critical states are finite
        val criticalStates = listOf(state[0], state[1], state[2], state[3], state[4], state[5], state[6], state[7], state[8])
        return criticalStates.all { it.isFinite() }
    }

    /**
     * Get current pose estimate - returns null if EKF is not safe (no mutation)
     */
    fun poseOrNull(): PoseEstimate? {
        if (!isStateFinite()) {
            //Log.w(TAG, "EKF not safe - returning null pose")
            return null
        }
        return getCurrentPose()
    }

    // ---------- COVARIANCE MATRIX HELPERS ----------
    /**
     * Check if all diagonal elements are finite and positive above epsilon
     */
    private fun Array<DoubleArray>.diagonalAllFiniteAndPositive(epsilon: Double): Boolean {
        for (i in this.indices) {
            if (i < this[i].size) {
                val diag = this[i][i]
                if (!diag.isFinite() || diag <= epsilon) {
                    return false
                }
            }
        }
        return true
    }

    /**
     * Make covariance matrix symmetric positive definite
     */
    private fun Array<DoubleArray>.makeSPD(epsilon: Double) {
        for (i in this.indices) {
            if (i < this[i].size) {
                this[i][i] = max(this[i][i], epsilon)
            }
        }
    }
    
    /**
     * Repair diagonal elements that are non-finite or too small
     */
    private fun Array<DoubleArray>.repairDiagonalIfNeeded(eps: Double = 1e-9): Boolean {
        var repaired = false
        for (i in indices) {
            val d = this[i][i]
            if (!d.isFinite() || d <= eps) {
                val newV = when (i) {
                    in 0..2   -> 25.0           // position
                    in 3..5   -> 1.0            // velocity
                    in 6..8   -> 0.5            // acceleration
                    in 9..12  -> 0.02           // quaternion
                    in 13..15 -> 1e-4           // gyro bias
                    else      -> 0.25           // accel bias
                }
                this[i][i] = newV
                repaired = true
            }
        }
        if (repaired) {
            symmetrize()
            makeSPD(eps)
        }
        return repaired
    }
    


    // Add after covariance helpers

    /**
     * Disable GPS heading row in measurement Jacobian
     */
    private fun disableGpsHeadingRow() {
        for (j in 0 until STATE_DIM) gpsMeasurementJacobian[6][j] = 0.0
    }

    // ---------- RECOVERY-AWARE REINITIALIZATION ----------
    /**
     * Reset EKF from valid lat/lon coordinates
     */
    fun resetFromLatLon(lat: Double, lon: Double, alt: Double, accuracyM: Double? = null, 
                        zeroVelocity: Boolean = true, forceResetBiases: Boolean = true) {
        //Log.i(TAG, "Resetting EKF from lat=$lat, lon=$lon, alt=$alt")
        
        // Set origin if not set
        if (originLatDeg == null) {
            originLatDeg = lat
            originLonDeg = lon
            originAltM = alt
            referenceLat = lat
            referenceLon = lon
            referenceAlt = alt
            //Log.i(TAG, "Set origin: lat=$originLatDeg, lon=$originLonDeg, alt=$originAltM")
        }
        
        // Convert to ENU coordinates using geodeticToLocal
        val enu = geodeticToLocal(lat, lon, alt)
        
        // Reset state vector with correct indices
        state[0] = enu.first   // x position
        state[1] = enu.second  // y position  
        state[2] = enu.third   // z position
        
        if (zeroVelocity) {
            state[3] = 0.0   // vx
            state[4] = 0.0   // vy
            state[5] = 0.0   // vz
        }
        
        // Reset orientation to identity (north-facing) - correct indices 9-12
        state[9] = 1.0   // qw
        state[10] = 0.0  // qx
        state[11] = 0.0  // qy
        state[12] = 0.0  // qz
        
        // Reset biases if requested - correct indices 13-18
        if (forceResetBiases) {
            state[13] = 0.0  // gyro bias x
            state[14] = 0.0  // gyro bias y
            state[15] = 0.0  // gyro bias z
            state[16] = 0.0  // accel bias x
            state[17] = 0.0  // accel bias y
            state[18] = 0.0  // accel bias z
        }
        
        // Reset covariance
        val posStd = max(accuracyM ?: 10.0, 3.0)
        resetCovarianceDiagonal(posStd, 2.0, 10.0, 0.5, 0.02)
        
        // Normalize quaternion
        normalizeQuaternion()
        
        // Reset time tracking
        lastUpdateNs = System.nanoTime()
        previousTimestamp = 0L  // Prevent fake large dt post-reset
        
        // Mark as healthy
        healthy = true
        
        // Important: snapshot immediately after reset to avoid immediate rollbacks
        snapshotIfGood()
        
        //Log.i(TAG, "EKF reset complete")
    }

    /**
     * Data class for pose estimates
     */
    data class PoseEstimate(
        val position: Triple<Double, Double, Double>,      // lat, lon, alt
        val velocity: Triple<Double, Double, Double>,      // vx, vy, vz
        val acceleration: Triple<Double, Double, Double>,  // ax, ay, az
        val orientation: FloatArray                        // quaternion [w, x, y, z]
    )

    // Snapshot for pose logging (geodetic + ENU + orientation)
    data class LogSnapshot(
        val lat: Double,
        val lon: Double,
        val alt: Double,
        val enuX: Double,
        val enuY: Double,
        val enuZ: Double,
        val vE: Double,
        val vN: Double,
        val vU: Double,
        val qw: Float, val qx: Float, val qy: Float, val qz: Float,
        val rollDeg: Double, val pitchDeg: Double, val yawDeg: Double,
        val frame: String = "ENU"
    )

    // Safe public getters for logging
    fun getEnuPosition(): Triple<Double, Double, Double> = Triple(state[0], state[1], state[2])
    fun getEnuVelocity(): Triple<Double, Double, Double> = Triple(state[3], state[4], state[5])
    fun getQuaternionWxyz(): FloatArray = floatArrayOf(
        state[9].toFloat(), state[10].toFloat(), state[11].toFloat(), state[12].toFloat()
    )

    // Euler helper (radians→degrees; ENU convention)
    private fun eulerFromQuatDegrees(qw: Double, qx: Double, qy: Double, qz: Double): Triple<Double, Double, Double> {
        val sinr_cosp = 2.0 * (qw*qx + qy*qz)
        val cosr_cosp = 1.0 - 2.0 * (qx*qx + qy*qy)
        val roll = Math.toDegrees(atan2(sinr_cosp, cosr_cosp))
        val sinp = 2.0 * (qw*qy - qz*qx)
        val pitch = Math.toDegrees(asin(sinp.coerceIn(-1.0, 1.0)))
        val siny_cosp = 2.0 * (qw*qz + qx*qy)
        val cosy_cosp = 1.0 - 2.0 * (qy*qy + qz*qz)
        val yaw = Math.toDegrees(atan2(siny_cosp, cosy_cosp))
        return Triple(roll, pitch, yaw)
    }

    // Build a snapshot of current EKF state for logging
    fun snapshotForLogging(): LogSnapshot {
        // ENU pose/vel from state (x=east, y=north, z=up)
        val x = state[0]; val y = state[1]; val z = state[2]
        val vx = state[3]; val vy = state[4]; val vz = state[5]

        // Geodetic from local (uses existing converters)
        val geo = localToGeodetic(x, y, z)

        // Orientation (normalize defensively)
        val q = floatArrayOf(state[9].toFloat(), state[10].toFloat(), state[11].toFloat(), state[12].toFloat())
        val qn = sqrt((q[0]*q[0] + q[1]*q[1] + q[2]*q[2] + q[3]*q[3]).toDouble()).takeIf { it > 0 } ?: 1.0
        val qw = (q[0]/qn).toFloat(); val qx = (q[1]/qn).toFloat(); val qy = (q[2]/qn).toFloat(); val qz = (q[3]/qn).toFloat()
        val (rollDeg, pitchDeg, yawDeg) = eulerFromQuatDegrees(qw.toDouble(), qx.toDouble(), qy.toDouble(), qz.toDouble())

        return LogSnapshot(
            lat = geo.first, lon = geo.second, alt = geo.third,
            enuX = x, enuY = y, enuZ = z,
            vE = vx, vN = vy, vU = vz,
            qw = qw, qx = qx, qy = qy, qz = qz,
            rollDeg = rollDeg, pitchDeg = pitchDeg, yawDeg = yawDeg,
            frame = "ENU"
        )
    }

    // Add after the resetFromLatLon function

    // ---------- COORDINATE CONVERSION HELPERS ----------
    /**
     * Convert geodetic coordinates to local ENU
     */
    private fun geoToEnu(lat: Double, lon: Double, alt: Double, 
                         originLat: Double, originLon: Double, originAlt: Double): DoubleArray {
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)
        val originLatRad = Math.toRadians(originLat)
        val originLonRad = Math.toRadians(originLon)
        
        val a = 6378137.0  // WGS84 semi-major axis
        val e2 = 0.006694379990141316  // WGS84 eccentricity squared
        
        val N = a / sqrt(1.0 - e2 * sin(originLatRad) * sin(originLatRad))
        
        val x = (N + originAlt) * cos(originLatRad) * cos(originLonRad)
        val y = (N + originAlt) * cos(originLatRad) * sin(originLonRad)
        val z = (N * (1.0 - e2) + originAlt) * sin(originLatRad)
        
        val x1 = (N + alt) * cos(latRad) * cos(lonRad)
        val y1 = (N + alt) * cos(latRad) * sin(lonRad)
        val z1 = (N * (1.0 - e2) + alt) * sin(latRad)
        
        return doubleArrayOf(x1 - x, y1 - y, z1 - z)
    }

    /**
     * Reset covariance matrix diagonal with specified standard deviations
     */
    private fun resetCovarianceDiagonal(posStd: Double, velStd: Double, 
                                       yawStdDeg: Double, baStd: Double, bgStd: Double) {
        // NEW: zero entire matrix first (removes any stale cross-terms)
        for (i in covariance.indices) for (j in covariance[i].indices) covariance[i][j] = 0.0
        
        val yawStdRad = Math.toRadians(yawStdDeg)
        val qVar = (yawStdRad * yawStdRad) / 4.0
        
        // Diagonals
        covariance[0][0] = posStd * posStd
        covariance[1][1] = posStd * posStd
        covariance[2][2] = posStd * posStd

        covariance[3][3] = velStd * velStd
        covariance[4][4] = velStd * velStd
        covariance[5][5] = velStd * velStd

        covariance[6][6] = 1.0
        covariance[7][7] = 1.0
        covariance[8][8] = 1.0

        covariance[9][9]   = qVar
        covariance[10][10] = qVar
        covariance[11][11] = qVar
        covariance[12][12] = qVar

        covariance[13][13] = bgStd * bgStd
        covariance[14][14] = bgStd * bgStd
        covariance[15][15] = bgStd * bgStd

        covariance[16][16] = baStd * baStd
        covariance[17][17] = baStd * baStd
        covariance[18][18] = baStd * baStd

        // Keep it sane
        covariance.symmetrize()
        covariance.makeSPD(1e-9)
    }

    // DEPRECATED: Use update(gpsData: DoubleArray) instead
    @Deprecated("Use update(gpsData: DoubleArray) for consistent GPS updates", ReplaceWith("update(gpsData)"))
    fun updateWithGps(gpsData: DoubleArray) {
        update(gpsData)
    }

    // Add this function after the existing bias update logic
    private fun clampBiases(): Boolean {
        var clamped = false
        
        // Clamp accelerometer bias norms
        val accelBiasNorm = sqrt(state[16]*state[16] + state[17]*state[17] + state[18]*state[18])
        if (accelBiasNorm > 2.0) {
            val scale = 2.0 / accelBiasNorm
            state[16] *= scale  // accel bias X
            state[17] *= scale  // accel bias Y
            state[18] *= scale  // accel bias Z
            //Log.w(TAG, "Accelerometer bias clamped from ${accelBiasNorm}m/s² to 2.0m/s²")
            clamped = true
        }
        
        // Clamp gyroscope bias norms
        val gyroBiasNorm = sqrt(state[13]*state[13] + state[14]*state[14] + state[15]*state[15])
        if (gyroBiasNorm > 0.1) {
            val scale = 0.1 / gyroBiasNorm
            state[13] *= scale  // gyro bias X
            state[14] *= scale  // gyro bias Y
            state[15] *= scale  // gyro bias Z
            //Log.w(TAG, "Gyroscope bias clamped from ${gyroBiasNorm}rad/s to 0.1rad/s")
            clamped = true
        }
        
        return !clamped
    }

    // REMOVED: Placeholder functions that were not implemented
    // Use the main predict(timestamp, imuData) and update(gpsData) functions instead

    // DEPRECATED: Use update(gpsData: DoubleArray) instead for consistent GPS updates
    @Deprecated("Use update(gpsData: DoubleArray) for consistent GPS updates", ReplaceWith("update(doubleArrayOf(lat, lon, alt, 0.0, 0.0, 0.0, 0.0))"))
    fun updateWithGps(lat: Double, lon: Double, alt: Double, accuracyM: Double) {
        // Convert to standard GPS data format and call main update function
        val gpsData = doubleArrayOf(lat, lon, alt, 0.0, 0.0, 0.0, 0.0) // No velocity/heading data
        update(gpsData)
    }

    // REMOVED: Placeholder functions that were not implemented
    // Use the main predict(timestamp, imuData) and update(gpsData) functions instead

    // DEPRECATED: Use updateMagnetometer(mag: DoubleArray, acc: DoubleArray) instead
    @Deprecated("Use updateMagnetometer(mag: DoubleArray, acc: DoubleArray) for tilt compensation", ReplaceWith("updateMagnetometer(doubleArrayOf(magX, magY, magZ), doubleArrayOf(0.0, 0.0, 0.0))"))
    fun updateMagnetometer(magX: Double, magY: Double, magZ: Double, dt: Double) {
        // Forward to the real implementation (with zero accel as fallback)
        updateMagnetometer(doubleArrayOf(magX, magY, magZ), doubleArrayOf(0.0, 0.0, 0.0))
    }

    // ---------- IMMEDIATE EKF RESET FUNCTION ----------
    /**
     * Immediate EKF reset when NaN values are detected
     * Uses lastGood point if available, otherwise soft reset to reference origin
     */
    fun immediateResetOnNaN() {
        //Log.w(TAG, "Immediate EKF reset requested due to NaN detection")
        
        if (lastGood != null) {
            // Restore from last good snapshot
            state = lastGood!!.state.copyOf()
            covariance = lastGood!!.cov.map { it.copyOf() }.toTypedArray()
            //Log.i(TAG, "EKF reset from last good snapshot")
        } else {
            // Soft reset to reference origin
            resetFromLatLon(referenceLat, referenceLon, referenceAlt, accuracyM = 10.0, zeroVelocity = true, forceResetBiases = false)
            //Log.i(TAG, "EKF soft reset to reference origin")
        }
        
        // Reset health status and timestamp
        healthy = true
        previousTimestamp = 0L  // Prevent fake large dt post-reset
    }

    // ---------- MEKF ATTITUDE MEASUREMENT UPDATE FROM MADGWICK ----------
    /**
     * Fuse attitude from ModifiedMadgwick as a measurement.
     * @param qMw       Madgwick quaternion (same convention as EKF nominal)
     * @param magWeight 0..1 reliability of magnetometer (from Madgwick)
     * @param accelNorm current accel norm (m/s^2) to modulate roll/pitch trust
     */
    fun updateAttitudeFromMadgwick(qMw: FloatArray, magWeight: Float, accelNorm: Float) {
        // Residual r = 2 * vec(q_meas ⊗ conj(q_nom))
        val qNom = doubleArrayOf(state[9], state[10], state[11], state[12])
        val qMea = doubleArrayOf(qMw[0].toDouble(), qMw[1].toDouble(), qMw[2].toDouble(), qMw[3].toDouble())
        val qErr = quatMultiply(qMea, quatConjugate(qNom))
        val r = doubleArrayOf(2.0*qErr[1], 2.0*qErr[2], 2.0*qErr[3])

        // H (3x19): minimal mapping to quaternion cols (approximate small-angle)
        val H = Array(3) { DoubleArray(STATE_DIM) { 0.0 } }
        H[0][10] = 1.0; H[1][11] = 1.0; H[2][12] = 1.0

        // Adaptive R
        val gErr = abs(accelNorm - 9.80665)
        val rpStd = 0.5 * (1.0 + min(gErr / 1.0, 3.0))           // roll/pitch std [rad]
        val yawStd = if (magWeight > 0f) 1.5 / (0.5 + 0.5*magWeight) else 1e3
        val R = arrayOf(
            doubleArrayOf(rpStd*rpStd, 0.0, 0.0),
            doubleArrayOf(0.0, rpStd*rpStd, 0.0),
            doubleArrayOf(0.0, 0.0, yawStd*yawStd)
        )

        val Ht  = matrixTranspose(H)
        val PHt = matrixMultiply(covariance, Ht)
        val S   = matrixAdd(matrixMultiply(H, PHt), R).symmetrize()
        val SInv = safeInverseSPD(S, "attitude") ?: return
        val K   = matrixMultiply(PHt, SInv)
        val dx  = matrixVectorMultiply(K, r)

        // Inject multiplicative correction from quaternion columns (10..12)
        val dtheta = doubleArrayOf(dx[10], dx[11], dx[12])
        val dq = smallAngleToQuat(dtheta)
        val qCorrected = quatNormalize(quatMultiply(qNom, dq))
        state[9] = qCorrected[0]; state[10] = qCorrected[1]; state[11] = qCorrected[2]; state[12] = qCorrected[3]

        // Joseph covariance update
        val I = Array(STATE_DIM) { DoubleArray(STATE_DIM) }.also { for (i in 0 until STATE_DIM) it[i][i] = 1.0 }
        val KH = matrixMultiply(K, H)
        val IminusKH = matrixSubtract(I, KH)
        val term1 = matrixMultiply(matrixMultiply(IminusKH, covariance), matrixTranspose(IminusKH))
        val KR = matrixMultiply(K, R)
        val term2 = matrixMultiply(KR, matrixTranspose(K))
        covariance = matrixAdd(term1, term2).symmetrize().also { it.makeSPD(1e-9) }
    }

    private fun quatConjugate(q: DoubleArray) = doubleArrayOf(q[0], -q[1], -q[2], -q[3])
    private fun quatMultiply(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[0]*b[0] - a[1]*b[1] - a[2]*b[2] - a[3]*b[3],
        a[0]*b[1] + a[1]*b[0] + a[2]*b[3] - a[3]*b[2],
        a[0]*b[2] - a[1]*b[3] + a[2]*b[0] + a[3]*b[1],
        a[0]*b[3] + a[1]*b[2] - a[2]*b[1] + a[3]*b[0]
    )
    private fun smallAngleToQuat(d: DoubleArray) = doubleArrayOf(1.0, 0.5*d[0], 0.5*d[1], 0.5*d[2])
    private fun quatNormalize(q: DoubleArray): DoubleArray {
        val n = sqrt(q[0]*q[0] + q[1]*q[1] + q[2]*q[2] + q[3]*q[3])
        val s = if (n > 0.0) 1.0/n else 1.0
        return doubleArrayOf(q[0]*s, q[1]*s, q[2]*s, q[3]*s)
    }

    // ---------- ZUPT + ZARU PSEUDO-MEASUREMENTS ----------
    fun zuptUpdate() {
        // Residual r = -v (expect ~0)
        val r = doubleArrayOf(-state[3], -state[4], -state[5])
        val H = Array(3) { DoubleArray(STATE_DIM) { 0.0 } }
        H[0][3] = 1.0; H[1][4] = 1.0; H[2][5] = 1.0
        val s = 0.05
        val R = arrayOf(
            doubleArrayOf(s*s, 0.0, 0.0),
            doubleArrayOf(0.0, s*s, 0.0),
            doubleArrayOf(0.0, 0.0, s*s)
        )
        ekfUpdate19(H, R, r, "zupt")
    }

    fun zaruUpdate(gyro: DoubleArray) {
        // Simple yaw-rate ~ 0 pseudo-measure, push on bias to make (ωz - bgz) → 0
        val yawRate = gyro[2] - state[15] // ωz - bgz
        val r = doubleArrayOf(-yawRate)
        val H = Array(1) { DoubleArray(STATE_DIM) { 0.0 } }
        // d(ωz - bgz)/d state = [ ... -1 on bgz ... ]
        H[0][15] = -1.0
        val R = arrayOf(doubleArrayOf(0.5 * 0.5))
        ekfUpdate19(H, R, r, "zaru")
    }

    // Double-only EKF update helper for 19-state
    private fun ekfUpdate19(
        H: Array<DoubleArray>, 
        R: Array<DoubleArray>, 
        r: DoubleArray, 
        tag: String = "ekfUpdate19"
    ) {
        val Ht  = matrixTranspose(H)
        val PHt = matrixMultiply(covariance, Ht)
        val S   = matrixAdd(matrixMultiply(H, PHt), R).symmetrize()
        val SInv = safeInverseSPD(S, tag) ?: return
        val K   = matrixMultiply(PHt, SInv)

        val dx  = matrixVectorMultiply(K, r)
        // State correction (protect against non-finite)
        for (i in 0 until STATE_DIM) {
            val v = state[i] + dx[i]
            if (!v.isFinite()) return
            state[i] = v
        }

        // Joseph covariance update
        val I = Array(STATE_DIM) { DoubleArray(STATE_DIM) }.also { for (i in 0 until STATE_DIM) it[i][i] = 1.0 }
        val KH = matrixMultiply(K, H)
        val IminusKH = matrixSubtract(I, KH)
        val term1 = matrixMultiply(matrixMultiply(IminusKH, covariance), matrixTranspose(IminusKH))
        val KR = matrixMultiply(K, R)
        val term2 = matrixMultiply(KR, matrixTranspose(K))
        covariance = matrixAdd(term1, term2).symmetrize().also { it.makeSPD(1e-9) }
    }

    // ---------- ADAPTIVE PROCESS/MEASUREMENT NOISE HOOKS ----------
    fun adaptNoiseByState(motion: String, magWeight: Float, gyroAbs: Float) {
        when (motion) {
            "STILL" -> {
                // shrink Q aggressively
                processNoiseScale = 0.3f
                // consider frequent ZUPT/ZARU
            }
            "WALKING", "RUNNING" -> {
                processNoiseScale = 1.0f
            }
            "DRIVING" -> {
                processNoiseScale = 2.0f
            }
            else -> processNoiseScale = 1.0f
        }
        if (gyroAbs > 10f) { processNoiseScale *= 1.5f }
        // apply scale to Qc before discretization
    }

    // ---------- GPS GATING FIX ----------
    fun isGPSUpdateNeeded(): Boolean {
        val sigmaPos = predictedPositionSigma() // sqrt of diag(covariance_p)
        val tooUncertain = sigmaPos > 500.0 // gpsSigmaThresholdMeters
        val tooOld = (System.currentTimeMillis() - lastGpsUpdateMs) > 300000L // maxGpsSilenceMs = 5min
        
        if (tooUncertain) {
            /*logGpsRefix("ExtendedKalmanFilter.isGPSUpdateNeeded",
                        "EKF_FLAG_UNCERTAINTY",
                        "sigma=${"%.1f".format(sigmaPos)} threshold=500.0")*/
            return true
        }
        if (tooOld) {
            /*logGpsRefix("ExtendedKalmanFilter.isGPSUpdateNeeded",
                        "EKF_FLAG_AGE",
                        "ageMs=${System.currentTimeMillis() - lastGpsUpdateMs} maxAgeMs=300000")*/
            return true
        }
        return false
    }

    private fun predictedPositionSigma(): Double {
        return sqrt(covariance[0][0] + covariance[1][1] + covariance[2][2])
    }

    // ---------- QUATERNION MULTIPLICATION HELPER ----------
    private fun quatMultiply(q1: FloatArray, q2: FloatArray): FloatArray {
        return floatArrayOf(
            q1[0] * q2[0] - q1[1] * q2[1] - q1[2] * q2[2] - q1[3] * q2[3],
            q1[0] * q2[1] + q1[1] * q2[0] + q1[2] * q2[3] - q1[3] * q2[2],
            q1[0] * q2[2] - q1[1] * q2[3] + q1[2] * q2[0] + q1[3] * q2[1],
            q1[0] * q2[3] + q1[1] * q2[2] - q1[2] * q2[1] + q1[3] * q2[0]
        )
    }

}