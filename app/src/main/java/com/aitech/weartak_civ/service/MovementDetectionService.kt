package com.aitech.weartak_civ.service

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.hardware.*
import android.location.Location
import android.os.*
import android.os.Build
import android.os.Process
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.compose.ui.graphics.vector.DefaultTrimPathEnd
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aitech.weartak_civ.algorithm.ExtendedKalmanFilter
import com.aitech.weartak_civ.algorithm.ModifiedMadgwickFilter
import com.google.android.gms.location.*
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.math.max
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.Date
import java.util.Locale

// Updated MovementState enum to match Google's Activity Recognition types
enum class MovementState {
    STILL, WALKING, RUNNING, CYCLING, DRIVING, UNKNOWN, ON_FOOT, TILTING
}

// ---------- NEW DATA STRUCTURES AND STATE ----------
/**
 * Represents a valid lat/lon/alt coordinate with timestamp
 */
data class LatLonAlt(val lat: Double, val lon: Double, val alt: Double, val tMs: Long)

// ---------- RECOVERY SYSTEM STATE ----------
@Volatile private var lastGoodPredicted: LatLonAlt? = null
@Volatile private var lastGoodGps: LatLonAlt? = null
@Volatile private var recovering: Boolean = false
@Volatile private var republishedFallback: Boolean = false
@Volatile private var freezeUntilMs: Long = 0L

// ---------- GOOGLE ACTIVITY RECOGNITION INTEGRATION ----------
// Removed duplicate top-level variables - now only class fields

// Activity-based EKF parameter tuning
data class ActivityBasedEKFParams(
    val processNoiseStd: Double,
    val measurementNoiseStd: Double,
    val gpsUpdateIntervalMs: Long,
    val velocityDampingFactor: Float,
    val driftThresholdMeters: Float
)

class MovementDetectionService : Service(), SensorEventListener {
    
    companion object {
        private const val TAG = "MovementDetectionService"
        private const val GPS_TAG = "GPS_REFIX"
        private const val GPS_REFIX_TAG = "GPS_REFIX"
        private const val MOVEMENT_DETECTION_SERVICE_NOTIFICATION_ID = 1001
        private const val GPS_TIME_THRESHOLD_MS = 600000L // 10 minutes
        private const val GPS_RESET_TIME_THRESHOLD = 300000L // 5 minutes

        private const val GPS_FORBIDDEN_MODE_CHECK_INTERVAL_MS = 10_000L
        
        // Activity Recognition broadcast constants
        private const val ACTION_ACTIVITY_BROADCAST = "com.aitech.weartak_civ.ACTIVITY_BROADCAST"
        private const val EXTRA_ACTIVITY_TYPE = "activity_type"
        private const val EXTRA_ACTIVITY_CONFIDENCE = "activity_confidence"

        // Service state for other components to access
        @Volatile
        var isRunning: AtomicBoolean = AtomicBoolean(false)
        
        // Location coordinates for other services to access
        @Volatile
        var lastPredictedLocation: Location? = null
            private set
            
        @Volatile
        var lastPredictedLatLonAlt: LatLonAlt? = null
            private set
    }

    // Service state variables
    private var sensorManager: SensorManager? = null
    private var ekf: ExtendedKalmanFilter? = null
    private var currentActivity: MovementState = MovementState.UNKNOWN
    private var activityConfidence: Int = 0
    private var lastActivityUpdateTime: Long = 0L
    private var lastActivityChangeTime: Long = 0L
    private var useGoogleActivityRecognition: Boolean = true
    
    // GPS and location services
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var isLocationUpdatesActive: Boolean = false
    private var hasGpsFix: Boolean = false  // Track if we have received GPS fix
    private var gpsRefixJob: Job? = null  // Periodic GPS refix job
    
    // Activity change hysteresis to prevent flapping
    private var activityChangeHysteresisMs: Long = 5000L // 5 seconds
    private var consecutiveActivityHits: Int = 0
    private var requiredConsecutiveHits: Int = 2
    private var candidateActivity: MovementState? = null
    private var candidateHits = 0
    
    // Safety fallback: revert to UNKNOWN if no AR updates received
    private var lastArUpdateTime: Long = 0L
    // Seed to avoid printing epoch-based seconds before first AR broadcast
    private var serviceStartMs: Long = 0L

    // --- movement txt logging (service-only) ---
    private var movementWriter: BufferedWriter? = null
    private var movementLogFile: File? = null
    private var movementLogDayStamp: String? = null
    private var lastGoogleAccuracyM: Double? = null

    // timestamps in nanoseconds from sensor events
    private var accTsNs: Long = 0L
    private var gyrTsNs: Long = 0L
    private var magTsNs: Long = 0L


    // GPS request rate limiting to prevent excessive location service calls
    private var lastGpsRequestTime = System.currentTimeMillis() // Initialize to current time to prevent immediate GPS request
    private val gpsRequestMinInterval = 10000L // 10 seconds minimum between GPS requests

    // IMU data buffering to avoid multiple predict() calls
    private var latestAccelData: FloatArray? = null
    private var latestGyroData: FloatArray? = null
    private var latestMagData: FloatArray? = null
    private var latestPressureData: Float? = null
    private var lastImuUpdateTime = System.currentTimeMillis() // Initialize to current time to prevent immediate IMU update
    private val imuUpdateInterval = 50L // 50ms for 20Hz IMU updates
    private var latestImuTimestampNs: Long = 0L  // Sensor timestamp for EKF predict


    // Broadcast receiver for activity updates
    private val activityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_ACTIVITY_BROADCAST) {
                val activityType = intent.getIntExtra(EXTRA_ACTIVITY_TYPE, DetectedActivity.UNKNOWN)
                val confidence = intent.getIntExtra(EXTRA_ACTIVITY_CONFIDENCE, 0)
                
                //Log.d(TAG, "📡 Received AR broadcast: activityType=$activityType, confidence=$confidence%")
                
                // Update last AR update time
                lastArUpdateTime = System.currentTimeMillis()
                
                // Convert Google Activity Recognition to our MovementState
                val movementState = when (activityType) {
                    DetectedActivity.STILL -> MovementState.STILL
                    DetectedActivity.ON_FOOT -> MovementState.ON_FOOT
                    DetectedActivity.WALKING -> MovementState.WALKING
                    DetectedActivity.RUNNING -> MovementState.RUNNING
                    DetectedActivity.ON_BICYCLE -> MovementState.CYCLING
                    DetectedActivity.IN_VEHICLE -> MovementState.DRIVING
                    DetectedActivity.TILTING -> MovementState.TILTING
                    else -> MovementState.UNKNOWN
                }

                updateMovementStateFromGoogle(movementState, confidence)
                
                //Log.d(TAG, "🔄 Converted AR activity $activityType to MovementState: $movementState")
                
                // Apply hysteresis to prevent activity flapping
                /*if (shouldUpdateActivity(movementState, confidence)) {
                    //Log.d(TAG, "✅ Hysteresis passed, updating activity state to: $movementState")
                    updateMovementStateFromGoogle(movementState, confidence)
                } else {
                    //Log.d(TAG, "⏸️ Hysteresis blocked activity change to: $movementState (current: $currentActivity)")
                }*/
            } else {
                return
            }
        }
    }
    
    /**
     * Update movement state from Google Activity Recognition
     */
    private fun updateMovementStateFromGoogle(activity: MovementState, confidence: Int) {
        //Log.d(TAG, "🔄 Updating movement state: $currentActivity → $activity (confidence: $confidence%)")
        
        // Update activity state
        currentActivity = activity
        activityConfidence = confidence
        lastActivityUpdateTime = System.currentTimeMillis()
        lastActivityChangeTime = lastActivityUpdateTime   // <- commit time
        candidateActivity = null
        candidateHits = 0
        
        // Apply activity-based EKF parameters
        applyActivityBasedEKFParams(activity, confidence)
        
        // Update GPS update intervals based on activity
        //updateGPSUpdateIntervals(activity)
        
        //Log.d(TAG, "✅ Movement state updated to: $activity (confidence: $confidence%)")
    }
    

    /*private fun shouldUpdateActivity(newActivity: MovementState, confidence: Int): Boolean {
        if (confidence < 70) return false
        val now = System.currentTimeMillis()
        if (newActivity == currentActivity) { 
            candidateActivity = null; 
            candidateHits = 0; 
            return false 
        }
        if (now - lastActivityChangeTime < activityChangeHysteresisMs) return false
        if (candidateActivity == newActivity) candidateHits++ else { 
            candidateActivity = newActivity; 
            candidateHits = 1 
        }
        return candidateHits >= requiredConsecutiveHits
    }*/

    private fun isEkfPredictionAllowed(activity: MovementState = currentActivity): Boolean {
        return when (activity) {
            MovementState.STILL,
            MovementState.ON_FOOT,
            MovementState.WALKING,
            MovementState.TILTING -> true
            else -> false // RUNNING, ON_BICYCLE, IN_VEHICLE, UNKNOWN
        }
    }

    private fun checkGpsFixNeededWhenEkfForbidden() {
        val now = System.currentTimeMillis()
        val lastFixMs = lastGoodGps?.tMs ?: 0L

        // Only nudge if we haven't seen a GPS fix in the last 10s (and respect request rate limiter)
        if (now - lastFixMs >= GPS_FORBIDDEN_MODE_CHECK_INTERVAL_MS) {
            requestGpsFix("EKF_FORBIDDEN_10S")
        }
    }

    /**
     * Check if we should fall back to UNKNOWN due to no recent AR updates
     */
    /*private fun checkArFallback() {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastArUpdate = currentTime - lastArUpdateTime
        
        if (timeSinceLastArUpdate > arFallbackTimeoutMs && currentActivity != MovementState.UNKNOWN) {
            //Log.w(TAG, "⚠️ No AR updates for ${timeSinceLastArUpdate/1000}s, falling back to UNKNOWN")
            updateMovementStateFromGoogle(MovementState.STILL, 0)
        } else if (timeSinceLastArUpdate > 100000) { // Log every 10 seconds
            //Log.d(TAG, "⏰ Last AR update: ${timeSinceLastArUpdate/1000}s ago (fallback threshold: ${arFallbackTimeoutMs/1000}s)")
        }
        
        // If we still don't have a location and AR is not working, try fallback
        if (lastPredictedLocation == null && timeSinceLastArUpdate > 30000) { // 30 seconds
            //Log.d(TAG, "🔄 No location available and AR not working, attempting fallback")
            provideFallbackLocation()
        }
    }*/
    
    /**
     * Manual method to set location for testing/debugging
     * This can be called from other services or for testing purposes
     */
    /*fun setTestLocation(latitude: Double, longitude: Double, altitude: Double = 0.0) {
        try {
            val testLocation = Location("test").apply {
                this.latitude = latitude
                this.longitude = longitude
                this.altitude = altitude
                this.accuracy = 5.0f // Assume good accuracy for test
                this.time = System.currentTimeMillis()
            }
            
            //Log.d(TAG, "🧪 Setting test location: $latitude, $longitude, $altitude")
            onLocationUpdate(testLocation)
            
        } catch (e: Exception) {
            //Log.e(TAG, "❌ Error setting test location: ${e.message}")
        }
    }*/
    
    /**
     * Get current location status for debugging
     */
    /*fun getLocationStatus(): String {
        return "Location Status: " +
               "lastPredictedLocation=${lastPredictedLocation != null}, " +
               "lastGoodGps=${lastGoodGps != null}, " +
               "currentActivity=$currentActivity, " +
               "activityConfidence=$activityConfidence%, " +
               "lastArUpdate=${(System.currentTimeMillis() - lastArUpdateTime)/1000}s ago"
    }*/
    
    // ---------- ACTIVITY-BASED EKF PARAMETER TUNING ----------
    
    /**
     * Get EKF parameters optimized for each activity type
     */
    private fun getEKFParamsForActivity(activity: MovementState, confidence: Int): ActivityBasedEKFParams {
        // Base parameters for each activity type
        val baseParams = when (activity) {
            MovementState.STILL -> ActivityBasedEKFParams(
                processNoiseStd = 0.1,        // Very low - device is stationary
                measurementNoiseStd = 0.1,     // High GPS trust when stationary
                gpsUpdateIntervalMs = 300000L, // 5 minutes - no need for frequent updates
                velocityDampingFactor = 0.1f,  // Aggressive velocity damping
                driftThresholdMeters = 500.0f    // Low drift tolerance
            )

            MovementState.ON_FOOT -> ActivityBasedEKFParams(
                processNoiseStd = 0.5,        // Very low - device is stationary
                measurementNoiseStd = 0.1,     // High GPS trust when stationary
                gpsUpdateIntervalMs = 120000L, // 5 minutes - no need for frequent updates
                velocityDampingFactor = 0.3f,  // Aggressive velocity damping
                driftThresholdMeters = 500.0f    // Low drift tolerance
            )

            MovementState.TILTING -> ActivityBasedEKFParams(
                processNoiseStd = 0.5,        // Very low - device is stationary
                measurementNoiseStd = 0.1,     // High GPS trust when stationary
                gpsUpdateIntervalMs = 120000L, // 5 minutes - no need for frequent updates
                velocityDampingFactor = 0.3f,  // Aggressive velocity damping
                driftThresholdMeters = 500.0f    // Low drift tolerance
            )

            MovementState.WALKING -> ActivityBasedEKFParams(
                processNoiseStd = 0.5,        // Low - predictable movement
                measurementNoiseStd = 0.2,     // Good GPS trust
                gpsUpdateIntervalMs = 120000L, // 2 minutes
                velocityDampingFactor = 0.3f,  // Moderate damping
                driftThresholdMeters = 500.0f    // Moderate drift tolerance
            )
            
            MovementState.RUNNING -> ActivityBasedEKFParams(
                processNoiseStd = 1.0,        // Moderate - more dynamic movement
                measurementNoiseStd = 0.4,     // Reduced GPS trust due to movement
                gpsUpdateIntervalMs = 60000L,  // 1 minute
                velocityDampingFactor = 0.5f,  // Light damping
                driftThresholdMeters = 500.0f    // Higher drift tolerance
            )
            
            MovementState.CYCLING -> ActivityBasedEKFParams(
                processNoiseStd = 0.8,        // High - vehicle-like movement
                measurementNoiseStd = 0.6,     // Lower GPS trust
                gpsUpdateIntervalMs = 30000L,  // 30 seconds
                velocityDampingFactor = 0.7f,  // Minimal damping
                driftThresholdMeters = 500.0f   // High drift tolerance
            )
            
            MovementState.DRIVING -> ActivityBasedEKFParams(
                processNoiseStd = 5.0,        // Very high - unpredictable vehicle movement
                measurementNoiseStd = 1.0,     // Low GPS trust
                gpsUpdateIntervalMs = 15000L,  // 15 seconds
                velocityDampingFactor = 0.9f,  // No damping
                driftThresholdMeters = 500.0f   // Very high drift tolerance
            )
            
            MovementState.UNKNOWN -> ActivityBasedEKFParams(
                processNoiseStd = 1.0,        // Default moderate values
                measurementNoiseStd = 0.3,     // Default GPS trust
                gpsUpdateIntervalMs = 60000L,  // Default 1 minute
                velocityDampingFactor = 0.5f,  // Default damping
                driftThresholdMeters = 500.0f   // Default drift tolerance
            )
        }
        
        // Confidence scaling kept for noise only; distance refix stays fixed per activity.
        val confidenceFactor = when {
            confidence >= 80 -> 1.0
            confidence >= 60 -> 1.2
            else -> 1.5
        }
        return ActivityBasedEKFParams(
            processNoiseStd = baseParams.processNoiseStd * confidenceFactor,
            measurementNoiseStd = baseParams.measurementNoiseStd * confidenceFactor,
            gpsUpdateIntervalMs = baseParams.gpsUpdateIntervalMs,
            velocityDampingFactor = baseParams.velocityDampingFactor,
            driftThresholdMeters = baseParams.driftThresholdMeters
        )
    }
    
    /**
     * Apply activity-based EKF parameters
     */
    private fun applyActivityBasedEKFParams(activity: MovementState, confidence: Int) {
        val params = getEKFParamsForActivity(activity, confidence)

        // Apply activity-tuned noise to EKF (without re-instantiating)
        ekf?.setNoiseParameters(
            processNoiseStd = params.processNoiseStd,
            measurementNoiseStd = params.measurementNoiseStd
        )
        
        gpsUpdateInterval = params.gpsUpdateIntervalMs
        velocityDampingFactor = params.velocityDampingFactor
        driftThresholdMeters = params.driftThresholdMeters  // fixed 500 m per activity (tunable later)
    }
    
    /**
     * Initialize Google Activity Recognition
     */
    private fun initializeActivityRecognition() {
        try {
            //Log.d(TAG, "🔍 Initializing Google Activity Recognition...")
            
            // Check for ACTIVITY_RECOGNITION permission (required on Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) 
                    != PackageManager.PERMISSION_GRANTED) {
                    return
                }
            }
            
            // Start the GoogleActivityRecognition service with foreground service support on O+
            val serviceIntent = Intent(this, GoogleActivityRecognition::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, serviceIntent)
                        } else {
                startService(serviceIntent)
            }
            
                                } catch (e: Exception) {
        }
        
        // If activity recognition failed, try to provide fallback location
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && 
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) 
            != PackageManager.PERMISSION_GRANTED) {
            //Log.d(TAG, "🔄 Attempting to provide fallback location due to missing AR permission")
            provideFallbackLocation()
        }
    }
    
    /**
     * Update GPS update intervals based on activity type
     */

    // ---- EKF profile model and catalog (uses only knobs already exposed by EKF) ----
    /*private data class EkfProfile(
        val mode: String,                 // feeds ekf.adaptNoiseByState(...)
        val measurementNoiseStd: Double,  // feeds ekf.setNoiseParameters(..., R_std)
        val gpsHeadingMinSpeed: Double,   // m/s; heading gate threshold
        val velocityDamping: Double,      // 0..1; feeds ekf.dampenVelocity()
        val zuptRepeats: Int,             // call ekf.zuptUpdate() N times
        val zaru: Boolean,                // if true, call ekf.zaruUpdate()
        val magWeight: Float,             // 0..1; hint for adaptNoiseByState()
        val distanceRefixMeters: Float    // distance-based refix threshold for this activity
    )*/

    /*private object ActivityProfiles {
        // ON_FOOT treated like stationary
        val STILL      = EkfProfile("STILL",    0.28, Double.POSITIVE_INFINITY, 0.05, 4, true,  0.75f, 500f)
        val ON_FOOT    = EkfProfile("WALKING",    0.28, 1.20,                      0.90, 0, false,  0.65f, 500f)
        val WALKING    = EkfProfile("WALKING",  0.35, 1.00,                      0.90, 0, false, 0.55f, 500f)
        val RUNNING    = EkfProfile("RUNNING",  0.40, 1.50,                      1.00, 0, false, 0.45f, 500f)
        val ON_BICYCLE = EkfProfile("RUNNING",  0.43, 2.00,                      1.00, 0, false, 0.35f, 500f)
        val IN_VEHICLE = EkfProfile("DRIVING",  0.48, 3.00,                      1.00, 0, false, 0.15f, 500f)
        val TILTING    = EkfProfile("WALKING",  0.40, 1.00,                      0.90, 0, false,  0.40f, 500f)
        val UNKNOWN    = EkfProfile("WALKING",  0.38, 1.00,                      0.90, 0, true, 0.50f, 500f)

        fun forType(type: Int): EkfProfile = when (type) {
            com.google.android.gms.location.DetectedActivity.STILL       -> STILL
            com.google.android.gms.location.DetectedActivity.ON_FOOT     -> ON_FOOT
            com.google.android.gms.location.DetectedActivity.WALKING     -> WALKING
            com.google.android.gms.location.DetectedActivity.RUNNING     -> RUNNING
            com.google.android.gms.location.DetectedActivity.ON_BICYCLE  -> ON_BICYCLE
            com.google.android.gms.location.DetectedActivity.IN_VEHICLE  -> IN_VEHICLE
            com.google.android.gms.location.DetectedActivity.TILTING     -> TILTING
            com.google.android.gms.location.DetectedActivity.UNKNOWN     -> UNKNOWN
            else                                                         -> UNKNOWN
        }
    }

    // ---- Profile helper functions ----
    private fun currentSpeedMps(): Double {
        lastPredictedLocation?.speed?.let { if (it.isFinite() && it > 0f) return it.toDouble() }
        ekfPoseOrNull()?.velocity?.let { return kotlin.math.hypot(it.first, it.second) }
        return 0.0
    }

    private fun lastGyroAbs(): Float =
        latestGyroData?.let { sqrt(it[0]*it[0] + it[1]*it[1] + it[2]*it[2]) } ?: 0f

    /** Apply a profile to the EKF on the main thread. */
    private fun applyActivityProfileToEkf(
        ekf: ExtendedKalmanFilter,
        activityType: Int,
        speedMps: Double,
        gyroAbs: Float
    ) {
        val p = ActivityProfiles.forType(activityType)

        // (a) Process noise scaling + mag hint
        ekf.adaptNoiseByState(p.mode, p.magWeight, gyroAbs)

        // (b) Measurement trust (R): only measurement noise; process noise left to adaptNoiseByState
        ekf.setNoiseParameters(processNoiseStd = 0.0, measurementNoiseStd = p.measurementNoiseStd)

        // (c) Heading gating threshold if supported
        ekf.setGpsHeadingMinSpeed(p.gpsHeadingMinSpeed)

        // (d) Extra damping inside EKF (avoid duplicate damping in publisher; see below)
        if (p.velocityDamping < 1.0) ekf.dampenVelocity(p.velocityDamping)

        // (e) Stillness pinning
        repeat(p.zuptRepeats) { ekf.zuptUpdate() }
        if (p.zaru) ekf.zaruUpdate(doubleArrayOf(0.0, 0.0, 0.0))

        // (f) Service-owned distance refix threshold
        driftThresholdMeters = p.distanceRefixMeters
        //Log.d(TAG, "📏 driftThresholdMeters set from profile: ${"%.0f".format(driftThresholdMeters)} m")
    }*/


    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER      -> processAccelerometerData(event)
            Sensor.TYPE_GYROSCOPE          -> processGyroscopeData(event)
            Sensor.TYPE_MAGNETIC_FIELD     -> processMagnetometerData(event)
            Sensor.TYPE_LINEAR_ACCELERATION-> { /* optional */ }
            else -> return
        }
        // Attempt a fused EKF predict once we have all three sensors buffered
        tryUpdateEKFWithBufferedData()
    }


    private fun processAccelerometerData(event: SensorEvent) {
        val x = event.values[0] ; val y = event.values[1] ; val z = event.values[2]
        latestAccelData = floatArrayOf(x, y, z)
        accTsNs = event.timestamp
        latestImuTimestampNs = max(latestImuTimestampNs, event.timestamp)
    }
    
    private fun processGyroscopeData(event: SensorEvent) {
        val x = event.values[0] ; val y = event.values[1] ; val z = event.values[2]
        latestGyroData = floatArrayOf(x, y, z)
        gyrTsNs = event.timestamp
        latestImuTimestampNs = max(latestImuTimestampNs, event.timestamp)
    }
    
    private fun processMagnetometerData(event: SensorEvent) {
        val x = event.values[0] ; val y = event.values[1] ; val z = event.values[2]
        latestMagData = floatArrayOf(x, y, z)
        magTsNs = event.timestamp
        latestImuTimestampNs = max(latestImuTimestampNs, event.timestamp)
    }

    private fun processPressureData(event: SensorEvent) {
        val pressure = event.values[0]

        //Log.v(TAG, "🌡️ Pressure: ${String.format("%.1f", pressure)} hPa")
        logToFile("🌡️ Pressure: ${String.format("%.1f", pressure)} hPa")

        latestPressureData = pressure
    }

    // EKF pose TXT logging (.txt; one line = key=value pairs)
    private var poseTxtWriter: BufferedWriter? = null
    private var poseTxtDayStamp: String? = null

    private fun poseLogDayStamp(): String =
        SimpleDateFormat("dd_MM_yy", Locale.US).format(Date())

    private fun ensurePoseTxtOpen() {
        val current = poseLogDayStamp()
        if (poseTxtWriter != null && poseTxtDayStamp == current) return
        try { poseTxtWriter?.close() } catch (_: Throwable) {}

        val dir = applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir
        val file = File(dir, "ekf_pose_log_${current}.txt")
        val fresh = !file.exists()
        file.parentFile?.mkdirs()
        poseTxtWriter = BufferedWriter(FileWriter(file, /*append=*/true))
        poseTxtDayStamp = current

        if (fresh) {
            poseTxtWriter?.apply {
                write("# fields: ts_ms ts_ns source frame lat lon alt enu_x enu_y enu_z vel_e vel_n vel_u qw qx qy qz roll_deg pitch_deg yaw_deg mag_weight"); newLine()
                write("# note: frame=ENU (x=east, y=north, z=up)"); newLine()
                flush()
            }
        }
    }

    // Safe formatter for doubles/floats
    private fun fmt(d: Double, digits: Int = 6): String =
        if (d.isFinite()) String.format(Locale.US, "% .${"%d".format(digits)}f".replace(" ", ""), d) else "NaN"

    
    /**
     * Update EKF with buffered IMU data (single predict call per update)
     * Only runs after we have received a GPS fix to anchor the coordinate system
     */

    private fun tryUpdateEKFWithBufferedData() {
        // 0) Require GPS anchor before IMU predictions
        if (!hasGpsFix) {
            //Log.d(TAG, "🚫 BLOCKED: No GPS fix yet (hasGpsFix=$hasGpsFix), EKF IMU updates disabled")
            return
        }

        // NEW: If EKF predict is *not* allowed, do a flat 10s GPS check and bail.
        if (!isEkfPredictionAllowed()) {
            checkGpsFixNeededWhenEkfForbidden()  // polls GPS every 10s (see constant)
            return
        }

        val nowMs = System.currentTimeMillis()
        if (nowMs - lastImuUpdateTime < imuUpdateInterval) return

        // 1) Ensure we have all three sensors buffered
        val a = latestAccelData
        val g = latestGyroData
        val m = latestMagData
        if (a == null || g == null || m == null) {
            //Log.v(TAG, "⏳ Waiting for complete IMU data: accel=${a!=null}, gyro=${g!=null}, mag=${m!=null}")
            return
        }

        try {
            // 2) Build IMU packet in body frame: [ax, ay, az, wx, wy, wz, mx, my, mz]
            val imuData = doubleArrayOf(
                a[0].toDouble(), a[1].toDouble(), a[2].toDouble(),
                g[0].toDouble(), g[1].toDouble(), g[2].toDouble(),
                m[0].toDouble(), m[1].toDouble(), m[2].toDouble()
            )

            // 3) Choose a synchronized timestamp (ns) & guard skew
            val accTs = accTsNs
            val gyrTs = gyrTsNs
            val magTs = magTsNs
            if (accTs == 0L || gyrTs == 0L || magTs == 0L) return

            val tsNs = maxOf(accTs, gyrTs, magTs)
            val minTs = minOf(accTs, gyrTs, magTs)
            val skewNs = tsNs - minTs
            val maxSkewNs = 25_000_000L // 25 ms; relax if you register sensors at very different rates
            if (skewNs > maxSkewNs) {
                //Log.v(TAG, "⏳ IMU skew ${(skewNs/1e6)}ms > ${(maxSkewNs/1e6)}ms; waiting to align")
                return
            }

            // 4) EKF predict (rotates to ENU internally; subtracts gravity inside EKF)
            ekf?.predict(tsNs, imuData)

            publishFromEkf("predicted-imu")
            lastImuUpdateTime = nowMs

            checkGpsFixNeeded()
        } catch (e: Exception) {
            //Log.e(TAG, "❌ Error updating EKF with IMU data: ${e.message}", e)
        }
    }


    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onCreate() {
        super.onCreate()
        // Initialize AR "last update" clock to now so early logs don't show 1970-based seconds
        serviceStartMs = System.currentTimeMillis()
        lastArUpdateTime = serviceStartMs
        
        //Log.d(TAG, "🚀 MovementDetectionService onCreate() - Initializing...")
        
        // Initialize sensor manager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        
        // Register sensors for IMU data
        try {
            //Log.d(TAG, "🔍 Attempting to register sensors...")
            
            val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val linearAccelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            val magnetometer = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            val pressure = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)
            
            //Log.d(TAG, "🔍 Sensor availability check: accel=${accelerometer != null}, gyro=${gyroscope != null}, mag=${magnetometer != null}, pressure=${pressure != null}")
            
            if (accelerometer != null) {
                val success = sensorManager?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL) ?: false
                if (success) {
                    //Log.d(TAG, "✅ Accelerometer sensor registered successfully (GAME rate ~50Hz)")
                } else {
                    //Log.w(TAG, "⚠️ Failed to register accelerometer sensor")
                }
            } else {
                //Log.w(TAG, "⚠️ Accelerometer sensor not available")
            }

            if (linearAccelerometer != null) {
                val success = sensorManager?.registerListener(this, linearAccelerometer, SensorManager.SENSOR_DELAY_NORMAL) ?: false
                if (success) {
                    //Log.d(TAG, "✅Linear Accelerometer sensor registered successfully (GAME rate ~50Hz)")
                } else {
                    //Log.w(TAG, "⚠️ Failed to register Linear accelerometer sensor")
                }
            } else {
                //Log.w(TAG, "⚠️Linear Accelerometer sensor not available")
            }
            
            if (gyroscope != null) {
                val success = sensorManager?.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_NORMAL) ?: false
                if (success) {
                    //Log.d(TAG, "✅ Gyroscope sensor registered successfully (GAME rate ~50Hz)")
                } else {
                    //Log.w(TAG, "⚠️ Failed to register gyroscope sensor")
                }
            } else {
                //Log.w(TAG, "⚠️ Gyroscope sensor not available")
            }
            
            if (magnetometer != null) {
                val success = sensorManager?.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_NORMAL) ?: false
                if (success) {
                    //Log.d(TAG, "🧲 Magnetometer sensor registered successfully (UI rate ~15Hz)")
                } else {
                    //Log.w(TAG, "⚠️ Failed to register magnetometer sensor")
                }
            } else {
                //Log.w(TAG, "⚠️ Magnetometer sensor not available")
            }
            
            if (pressure != null) {
                val success = sensorManager?.registerListener(this, pressure, SensorManager.SENSOR_DELAY_NORMAL) ?: false
                if (success) {
                    //Log.d(TAG, "✅ Pressure sensor registered successfully")
            } else {
                    //Log.w(TAG, "⚠️ Failed to register pressure sensor")
            }
            } else {
                //Log.w(TAG, "⚠️ Pressure sensor not available")
            }
            
        } catch (e: Exception) {
            //Log.e(TAG, "❌ Error registering sensors: ${e.message}", e)
        }
        
        // Initialize EKF
        try {
            // Initialize with default position (0,0,0) - will be updated when GPS is available
            val initialPosition = Triple(0.0, 0.0, 0.0)
            ekf = ExtendedKalmanFilter(
                initialPosition = initialPosition,
                processNoiseStd = 0.1,
                measurementNoiseStd = 0.1
            )
            //Log.d(TAG, "✅ EKF initialized successfully with initial position: $initialPosition")
            //Log.i(TAG, "🚫 EKF IMU updates disabled until GPS fix received")
            } catch (e: Exception) {
            //Log.e(TAG, "❌ Failed to initialize EKF: ${e.message}")
        }
        
        // Initialize activity recognition
        initializeActivityRecognition()
        
        // Initialize location services
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    onLocationUpdate(location)
                }
            }
        }
        
        // Register BroadcastReceiver for activity updates
        val filter = IntentFilter(ACTION_ACTIVITY_BROADCAST)

        registerReceiver(activityReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        requestGpsFix("INITIAL_STARTUP")
        
        // One-shot high-accuracy anchor when (re)starting GPS
        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { it?.let(::onLocationUpdate) }
            //Log.d(TAG, "🎯 One-shot high-accuracy GPS request sent")
        } catch (e: Exception) {
            //Log.w(TAG, "⚠️ Failed to request one-shot GPS: ${e.message}")
        }

        // Initialize movement log
        ensureMovementLogOpen()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        //Log.d(TAG, "🚀 MovementDetectionService onStartCommand() - Service starting...")
        isRunning.set(true)
        //Log.d(TAG, "✅ Service state: isRunning=${isRunning.get()}")
        
        // Start periodic GPS refix
        startPeriodicRefix()
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Cancel periodic GPS refix job
        gpsRefixJob?.cancel()
        gpsRefixJob = null
        //Log.d(TAG, "🛑 MovementDetectionService onDestroy() - Service stopping...")
        isRunning.set(false)
        
        // Unregister broadcast receiver
        try {
            unregisterReceiver(activityReceiver)
            //Log.d(TAG, "✅ Activity receiver unregistered successfully")
        } catch (e: Exception) {
            //Log.w(TAG, "⚠️ Error unregistering activity receiver: ${e.message}")
        }
        
        // Unregister sensors
        try {
            sensorManager?.unregisterListener(this)
            //Log.d(TAG, "✅ Sensors unregistered successfully")
        } catch (e: Exception) {
            //Log.w(TAG, "⚠️ Error unregistering sensors: ${e.message}")
        }
        
        // Remove location updates
        try {
            locationCallback?.let { callback ->
                fusedLocationClient.removeLocationUpdates(callback)
                isLocationUpdatesActive = false
                //Log.d(TAG, "✅ Location updates removed successfully")
            }
        } catch (e: Exception) {
            //Log.w(TAG, "⚠️ Error removing location updates: ${e.message}")
        }
        
        //Log.d(TAG, "✅ Service state: isRunning=${isRunning.get()}")
        
        // Close movement log writer
        try { movementWriter?.close() } catch (_: Throwable) {}
        movementWriter = null
        movementLogFile = null
        movementLogDayStamp = null
    }
    
    /**
     * Log current service status for debugging
     */

    override fun onBind(intent: Intent?): IBinder? = null

    // Placeholder variables and functions that would be implemented in the full service
    private var gpsUpdateInterval: Long = 60000L
    private var velocityDampingFactor: Float = 0.5f
    private var driftThresholdMeters: Float = 1000.0f
    
    // ---------- GPS UPDATE WITH SPEED DECOMPOSITION ----------
    
    /**
     * Update EKF with GPS location data, properly decomposing speed into velocity components
     */
    fun updateEKFWithGPS(location: Location) {
        try {
            // Validate location quality
            if (!location.hasAccuracy() || location.accuracy > 500.0f) {
                //Log.w(TAG, "⚠️ GPS accuracy too poor: ${location.accuracy}m, skipping update")
            return
        }
        
            // Guard: speed & bearing
            val speedMps = if (location.hasSpeed()) location.speed.toDouble() else 0.0
            val hasBear = location.hasBearing()
            val bearingDeg = if (hasBear) location.bearing.toDouble() else Double.NaN
            
            // Bearing quality gate (API 26+ has accuracy)
            val bearingOk = hasBear &&
                !bearingDeg.isNaN() &&
                speedMps >= 0.5 && // below this, bearing is unreliable
                (Build.VERSION.SDK_INT < 26 ||
                 !location.hasBearingAccuracy() ||
                 location.bearingAccuracyDegrees <= 30f)
            
            // Normalize & decompose (ENU: +x east, +y north)
            val bearingRad = if (bearingOk) Math.toRadians(((bearingDeg % 360) + 360) % 360) else Double.NaN
            val (vx, vy) = if (bearingOk) {
                speedMps * kotlin.math.sin(bearingRad) to  // East
                speedMps * kotlin.math.cos(bearingRad)     // North
            } else 0.0 to 0.0
            
            // Zero out vx, vy when speed is very low to avoid injecting jitter at standstill
            val finalVx = if (speedMps < 0.3) 0.0 else vx
            val finalVy = if (speedMps < 0.3) 0.0 else vy
            
            // Create GPS data array for EKF update
            val gpsData = doubleArrayOf(
                location.latitude,
                location.longitude,
                location.altitude,
                finalVx,            // East velocity
                finalVy,            // North velocity
                0.0,                // vz not available; keep 0 or infer from alt diff if you support it
                if (bearingOk) bearingRad else 0.0  // No NaN - EKF gates heading internally when speed is low
            )
            
            // Update EKF with GPS data
            ekf?.update(gpsData)

            // New: EKF pose TXT logging after GPS update
            ekf?.let { e ->
                val ekfSnap = e.snapshotForLogging()
                val ahrsSnap = try { 
                    null as com.aitech.weartak_civ.algorithm.ModifiedMadgwickFilter.AhrsLog?
                } catch (_: Throwable) { null }
                val gpsTsNs = System.currentTimeMillis() * 1_000_000L
            }
            
            // Publish EKF estimate after GPS correction
            publishFromEkf("predicted-after-gps")
            
                        } catch (e: Exception) {
        }
    }

    /**
     * Handle GPS location updates (called from location callback)
     */
    fun onLocationUpdate(location: Location) {
        // Save Google accuracy radius and write a GPS line
        lastGoogleAccuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null
        //logGpsTxt(location)
        
        // Handle first GPS fix - anchor EKF and enable IMU predictions
        if (!hasGpsFix) {
            hasGpsFix = true
            //Log.i(TAG, "🎉 FIRST GPS fix: anchoring EKF & enabling IMU predictions")

            // Anchor EKF to the first GPS fix before any predict/update happens
            ekf?.resetFromLatLon(
                location.latitude,
                location.longitude,
                location.altitude,
                accuracyM = location.accuracy.toDouble(),
                zeroVelocity = true,
                forceResetBiases = false
            )

            // Keep existing immediate publish of the raw GPS to companions:
            val initialGpsLocation = Location("initial-gps").apply {
                latitude = location.latitude
                longitude = location.longitude
                altitude = location.altitude
                time = System.currentTimeMillis()
                accuracy = location.accuracy
                if (location.hasSpeed()) speed = location.speed
                if (location.hasBearing()) bearing = location.bearing
            }
            
            // Update companion object locations with raw GPS coordinates
            lastPredictedLocation = initialGpsLocation
            lastPredictedLatLonAlt = LatLonAlt(location.latitude, location.longitude, location.altitude, System.currentTimeMillis())
            
        } else {
            // Subsequent GPS updates
            val ageMs = System.currentTimeMillis() - (lastGoodGps?.tMs ?: System.currentTimeMillis())
            //Log.d(TAG, "📡 GPS update: age=${ageMs}ms, speed=${location.speed} m/s, acc=${location.accuracy} m")
        }
        
        // Update EKF with GPS data
        updateEKFWithGPS(location)
        
        // Update last good GPS location for reference
        lastGoodGps = LatLonAlt(location.latitude, location.longitude, location.altitude, System.currentTimeMillis())
        
        // Initialize location if this is our first GPS update
        initializeLocationIfNeeded()
        
        //Log.d(TAG, "✅ GPS location processed, EKF estimate published")
    }
    
    // ---------- EKF-BASED LOCATION PUBLISHING ----------
    
    /**
     * Get damped velocity components from EKF state
     * Applies the current velocityDampingFactor to reduce drift
     */
    private fun getDampedVelocities(): Triple<Double, Double, Double>? {
        val est = ekfPoseOrNull() ?: return null
        val (rawVx, rawVy, rawVz) = est.velocity
        
        // Apply activity-based velocity damping
        val vx = rawVx * velocityDampingFactor
        val vy = rawVy * velocityDampingFactor
        val vz = rawVz * velocityDampingFactor
        
        return Triple(vx, vy, vz)
    }
    
    /**
     * Publish location estimate from EKF state
     * This provides continuous location estimates even between GPS updates
     * Only publishes after we have received a GPS fix
     */
    private fun publishFromEkf(source: String) {
        // Only publish after GPS fix - strict GPS-first gating
        if (!hasGpsFix) {
            //Log.v(TAG, "⏳ Waiting for GPS fix before publishing EKF estimate ($source)")
            return
        }
        
        try {
            val est = ekfPoseOrNull()
            if (est == null) {
                //Log.w(TAG, "EKF not safe; attempting fallback publish")
                lastGoodGps?.let {
                    //Log.w(TAG, "⚠️ Publishing fallback lastGoodGps due to unsafe EKF state")
                    val fallbackLocation = Location("fallback-gps").apply {
                        latitude = it.lat
                        longitude = it.lon
                        altitude = it.alt
                        time = it.tMs
                        accuracy = 50.0f
                    }
                    onLocationUpdate(fallbackLocation)
                }
                return
            }

        val now = System.currentTimeMillis()

        // Extract predicted position and validate before publishing
        val (lat, lon, alt) = est.position
        if (!isValidLat(lat) || !isValidLon(lon) || !isValidAlt(alt)) {
            //Log.w(TAG, "⚠️ EKF predicted geo invalid: lat=$lat lon=$lon alt=$alt — NOT updating companion")
            return
        }

        // Use damped velocity for reported speed (drift control), but compute heading from pose (raw) via helper.
        val (dvx, dvy, dvz) = getDampedVelocities() ?: est.velocity
        val speedMps = hypot(dvx, dvy).toFloat()

        // Heading: prefer course over ground; fallback to quaternion yaw when slow/still
        val headingDeg = headingDegreesFromPose(est)

        // Build a Location reflecting the *predicted* state (use EKF sigma as accuracy)
        val sigma = (ekf?.getHorizontalPositionStdMeters() ?: 10.0).coerceIn(3.0, 100.0)
        val loc = Location(source).apply {
            latitude = lat
            longitude = lon
            altitude = alt
            time = now
            accuracy = sigma.toFloat()
            if (speedMps.isFinite() && speedMps >= 0.1f) speed = speedMps
            if (headingDeg.isFinite()) bearing = headingDeg.toFloat()
        }
            
            // Update companion object locations
            lastPredictedLocation = loc
            lastPredictedLatLonAlt = LatLonAlt(lat, lon, alt, now)

            /*Log.d(TAG, "📡 Published EKF estimate ($source): " +
                    "lat=${String.format("%.6f", lat)}, " +
                    "lon=${String.format("%.6f", lon)}, " +
                    "alt=${String.format("%.1f", alt)}m, " +
                    "speed=${String.format("%.2f", speedMps)}m/s, " +
                    "bearing=${String.format("%.1f", headingDeg)}°")*/
            
        } catch (e: Exception) {
            //Log.e(TAG, "❌ Error publishing EKF estimate ($source): ${e.message}")
        }
    }
    
    /**
     * Check if EKF has a valid state for publishing location estimates
     */
    private fun isEKFReady(): Boolean {
        return try {
            val pose = ekf?.getCurrentPose()
            pose != null && 
            pose.position.first != 0.0 && 
            pose.position.second != 0.0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Safety: initialize location if EKF becomes valid and we don't have a location yet
     * Only initializes after GPS fix to ensure real-world coordinates
     */
    private fun initializeLocationIfNeeded() {
        if (!hasGpsFix) {
            //Log.d(TAG, "⏳ Waiting for GPS fix before initializing location")
            return
        }
        
        if (lastPredictedLocation == null && isEKFReady()) {
            //Log.d(TAG, "🔧 Initializing location from EKF state (after GPS fix)")
            publishFromEkf("predicted-initial")
        } else if (lastPredictedLocation == null) {
            //Log.d(TAG, "⏳ EKF not ready yet, cannot initialize location")
        }
    }
    
    /**
     * Request a GPS fix and publish current EKF estimate immediately
     * This ensures dependents always have a non-null location
     */
    private fun requestGpsFix() = requestGpsFix("unspecified")

    private fun requestGpsFix(reason: String) {
        //Log.d("GPS_REFIX", "requestGpsFix reason=$reason")

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastGpsRequestTime < gpsRequestMinInterval) {
            //Log.d(GPS_TAG, "GPS_REFIX_REQUEST reason=$reason status=suppressed")
            return
        }
        lastGpsRequestTime = currentTime
        ////Log.d(GPS_TAG, "GPS_REFIX_REQUEST reason=$reason status=begin")

        // Only publish EKF estimate if we have GPS fix to prevent pre-fix estimates
        if (hasGpsFix) publishFromEkf("predicted-before-gps") else //Log.d(TAG, "⏳ No GPS fix yet, skipping EKF estimate publish")

        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                //Log.d(GPS_TAG, "GPS_REFIX_REQUEST reason=$reason status=no-permission")
                return
            }

            // 1) Try one-shot: this does NOT keep the GPS running.
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        ////Log.d(GPS_TAG, "GPS_REFIX_REQUEST reason=$reason status=one-shot-fix acc=${loc.accuracy}")
                        onLocationUpdate(loc) // process like normal
                    } else {
                        //Log.d(GPS_TAG, "GPS_REFIX_REQUEST reason=$reason status=one-shot-null → fallback")
                        // 2) Fallback: start updates with a self-unregistering callback.
                        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000L)
                            .setWaitForAccurateLocation(false)
                            .setMinUpdateIntervalMillis(5000L)
                            .setMaxUpdateDelayMillis(10000L)
                            .build()

                        val oneShotCallback = object : LocationCallback() {
                            override fun onLocationResult(result: LocationResult) {
                                val fix = result.lastLocation
                                if (fix != null) {
                                    //Log.d(GPS_TAG, "GPS_REFIX_REQUEST reason=$reason status=fix-received acc=${fix.accuracy}")
                                    onLocationUpdate(fix)
                                } else {
                                    //Log.d(GPS_TAG, "GPS_REFIX_REQUEST reason=$reason status=fix-null")
                                }
                                // RELEASE resource immediately
                                fusedLocationClient.removeLocationUpdates(this)
                                isLocationUpdatesActive = false
                                //Log.d(GPS_TAG, "GPS_REFIX_REQUEST reason=$reason status=updates-stopped")
                            }
                        }

                        fusedLocationClient.requestLocationUpdates(
                            locationRequest,
                            oneShotCallback,
                            Looper.getMainLooper()
                        )
                        isLocationUpdatesActive = true
                        //Log.d(GPS_TAG, "GPS_REFIX_REQUEST reason=$reason status=updates-started-one-shot")
                    }
                }
                .addOnFailureListener {
                    //Log.d(GPS_TAG, "GPS_REFIX_REQUEST reason=$reason status=one-shot-failed ${it.message}")
                }

        } catch (e: Exception) {
            //Log.d(GPS_TAG, "GPS_REFIX_REQUEST reason=$reason status=error ${e.message}")
        }
    }


    /**
     * Calculate distance between two lat/lon points using Haversine formula
     */
    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0  // Earth's radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat/2) * sin(dLat/2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon/2) * sin(dLon/2)
        return 2 * R * atan2(sqrt(a), sqrt(1-a))
    }

    // --- EKF pose accessor shim (isolates API drift) ---
    private fun ekfPoseOrNull(): ExtendedKalmanFilter.PoseEstimate? =
        try { ekf?.let { e ->
            // Prefer poseOrNull() if your EKF exposes it; fall back to getCurrentPose()
            val pose = try { e.poseOrNull() } catch (_: Throwable) { null }
            pose ?: e.getCurrentPose()
        } } catch (_: Throwable) { null }

    // --- Predicted pose validation helpers ---
    private fun isValidLat(lat: Double) = lat.isFinite() && kotlin.math.abs(lat) <= 90.0
    private fun isValidLon(lon: Double) = lon.isFinite() && kotlin.math.abs(lon) <= 180.0
    private fun isValidAlt(alt: Double) = alt.isFinite() && alt > -1000.0 && alt < 12000.0 // generous sane bounds
    private fun normDeg(d: Double) = ((d % 360.0) + 360.0) % 360.0

    // --- Orientation frame and quaternion order constants ---
    private enum class Frame { ENU, NED }
    private enum class QuatOrder { WXYZ, XYZW }

    // Set these to match your EKF output:
    private val ORIENT_FRAME = Frame.ENU
    private val QUAT_ORDER = QuatOrder.WXYZ

    private fun quatToYawDeg(q: FloatArray): Double {
        val (w, x, y, z) = when (QUAT_ORDER) {
            QuatOrder.WXYZ -> listOf(q[0], q[1], q[2], q[3])
            QuatOrder.XYZW -> listOf(q[3], q[0], q[1], q[2])
        }.map { it.toDouble() }
        // Standard yaw from quaternion
        val yawRad = atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y*y + z*z))
        return Math.toDegrees(yawRad)
    }

    private fun yawToBearingDeg(yawDeg: Double, frame: Frame): Double = when (frame) {
        Frame.ENU -> normDeg(90.0 - yawDeg)    // ENU yaw 0°=East -> bearing 0°=North
        Frame.NED -> normDeg(yawDeg)           // NED yaw 0°=North already matches bearing
    }

    // Compute heading in degrees (0°=North, 90°=East). Prefer CoG from ENU velocity; fallback to yaw (quaternion).
    private fun headingDegreesFromPose(p: ExtendedKalmanFilter.PoseEstimate): Double {
        val (vx, vy, _) = p.velocity
        val speed = hypot(vx, vy)

        // Moving? Use course over ground from ENU velocity.
        if (speed >= 0.5) {
            val brad = atan2(vx, vy) // atan2(East, North)
            return normDeg(Math.toDegrees(brad))
        }

        // Fallback: yaw from quaternion orientation if available: [w, x, y, z]
        val q = try { p.orientation } catch (t: Throwable) { null }
        if (q != null && q.size >= 4) {
            val yawDeg = quatToYawDeg(q)
            return yawToBearingDeg(yawDeg, ORIENT_FRAME)
        }

        // No velocity and no quaternion → no heading
        return Double.NaN
    }

    /**
     * Start periodic GPS refix every 5 minutes
     */
    private fun startPeriodicRefix() {
        gpsRefixJob?.cancel()
        gpsRefixJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                // Dynamic interval: before first fix → 10s, after → 5min
                val intervalMs = if (!hasGpsFix) 10_000L else 5 * 60 * 1000L
                delay(intervalMs)

                val pred = lastPredictedLatLonAlt
                val gps = lastGoodGps

                if (!hasGpsFix) {
                    // Hunt aggressively until we get the first fix
                    requestGpsFix("INITIAL_FIX_HUNT")
                } else {
                    // Drift-gated 5-minute refix
                    if (pred != null && gps != null) {
                        val drift = distanceMeters(pred.lat, pred.lon, gps.lat, gps.lon)
                        if (drift > 555.0) {
                            requestGpsFix("PERIODIC_DRIFT_GATE")
                        }
                    } else {
                        requestGpsFix("PERIODIC_INITIAL_ANCHOR")
                    }
                }
            }
        }
    }


    /**
     * Check if we need to request a GPS fix based on time or drift thresholds
     */
    private fun checkGpsFixNeeded() {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastGps = lastGoodGps?.let { currentTime - it.tMs } ?: Long.MAX_VALUE
        
        // Check time threshold
        if (timeSinceLastGps > gpsUpdateInterval) {
            //Log.d(TAG, "⏰ GPS update overdue: ${timeSinceLastGps/1000}s > ${gpsUpdateInterval/1000}s")
            //Log.d(TAG, "🕐 Calling requestGpsFix() from checkGpsFixNeeded() - TIME THRESHOLD")
            //logRefixTxt(reason = "TIME_THRESHOLD", details = "service_decided_gps_refix")
            requestGpsFix("TIME_THRESHOLD")
            return
        }

        // Drift gate: request refix if predicted has drifted too far from last GPS
        val pred = lastPredictedLatLonAlt
        val gps = lastGoodGps
        if (pred != null && gps != null) {
            val drift = distanceMeters(pred.lat, pred.lon, gps.lat, gps.lon)
            if (drift > driftThresholdMeters) {
                //Log.d(TAG, "🚨 Drift ${"%.1f".format(drift)}m > ${driftThresholdMeters}m → requestGpsFix()")
                //Log.d(TAG, "🧭 Calling requestGpsFix() from checkGpsFixNeeded() - DRIFT THRESHOLD")
                val driftMsg = "DRIFT_THRESHOLD drift=${"%.1f".format(drift)}m>limit=${"%.0f".format(driftThresholdMeters)}m"
                //logRefixTxt(reason = "DRIFT_THRESHOLD", details = driftMsg)
                requestGpsFix(driftMsg)
                return
            }
        }

        // Optional: if EKF exposes 1σ horizontal position std, gate on that too
        ekf?.getHorizontalPositionStdMeters()?.let { sigma ->
            if (sigma.isFinite() && sigma > driftThresholdMeters / 2) {
                //Log.d(TAG, "📈 Uncertainty ${"%.1f".format(sigma)}m exceeds half drift threshold → requestGpsFix()")
                //Log.d(TAG, "📊 Calling requestGpsFix() from checkGpsFixNeeded() - UNCERTAINTY THRESHOLD")
                //logRefixTxt(reason = "UNCERTAINTY_THRESHOLD", details = "service_decided_gps_refix")
                requestGpsFix("UNCERTAINTY_THRESHOLD")
                return
            }
        }
        
        // Use EKF's own trigger for GPS refix
        if (ekf?.isGPSUpdateNeeded() == true) {
            //Log.d(TAG, "📈 EKF requests GPS update → requestGpsFix()")
            //Log.d(TAG, "🧠 Calling requestGpsFix() from checkGpsFixNeeded() - EKF TRIGGER")
            //logRefixTxt(reason = "EKF_FLAG", details = "service_decided_gps_refix")
            requestGpsFix("EKF_TRIGGER")
            return
        }
    }
    
    /**
     * Fallback: provide basic location estimate when activity recognition is not available
     * This ensures other services can still get location data
     */
    private fun provideFallbackLocation() {
        try {
            // If we have a last good GPS location, use that as fallback
            lastGoodGps?.let { gps ->
                val fallbackLocation = Location("fallback-gps").apply {
                    latitude = gps.lat
                    longitude = gps.lon
                    altitude = gps.alt
                    time = System.currentTimeMillis()
                    accuracy = 250.0f // Assume lower accuracy for fallback
                }
                
                lastPredictedLocation = fallbackLocation
                lastPredictedLatLonAlt = LatLonAlt(gps.lat, gps.lon, gps.alt, System.currentTimeMillis())
                
                //Log.d(TAG, "🔄 Fallback location provided from last GPS: ${gps.lat}, ${gps.lon}")
            } ?: run {
                //Log.w(TAG, "⚠️ No fallback location available - no GPS data yet")
                }
            } catch (e: Exception) {
            //Log.e(TAG, "❌ Error providing fallback location: ${e.message}")
        }
    }
    
    private fun logToFile(message: String) {
        //Log.d(TAG, message)
    }

    // region Movement .txt logging helpers
    private fun movementDayStamp(): String =
        SimpleDateFormat("dd_MM_yy", Locale.US).format(Date())

    private fun ensureMovementLogOpen() {
        val current = movementDayStamp()
        if (movementWriter != null && movementLogDayStamp == current) return
        try { movementWriter?.close() } catch (_: Throwable) {}
        val dir = applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir
        val file = File(dir, "movement_detection_service_${current}.txt")
        try {
            file.parentFile?.mkdirs()
            movementWriter = BufferedWriter(FileWriter(file, /*append=*/true))
            movementLogFile = file
            movementLogDayStamp = current
            writeMovementLine("=== movement log started ${Date()} ===")
        } catch (_: Throwable) {
            movementWriter = null
            movementLogFile = null
        }
    }

    private fun writeMovementLine(line: String) {
        try {
            ensureMovementLogOpen()
            movementWriter?.let {
                it.write(line)
                it.newLine()
                it.flush()
            }
        } catch (_: Throwable) {
            // best-effort, never crash
        }
    }

    private fun fmt(d: Double?): String =
        if (d == null || !d.isFinite()) "nan" else String.format(Locale.US, "%.6f", d)

    private fun quatYawDeg(qw: Double, qx: Double, qy: Double, qz: Double): Double {
        val yaw = kotlin.math.atan2(
            2.0 * (qw * qz + qx * qy),
            1.0 - 2.0 * (qy * qy + qz * qz)
        )
        return Math.toDegrees(yaw)
    }

    private fun logPredictTxt(ekf: ExtendedKalmanFilter) {
        // Use a pure read; do not mutate EKF here
        val pose = ekf.getCurrentPose() // lat/lon/alt computed inside EKF from ENU
        val lat = pose.position.first
        val lon = pose.position.second
        val alt = pose.position.third
        val x = ekf.getCurrentPose().let { // avoid extra accessor in case peekPose alloc-free changes
            // ENU x/y/z and v from state are not exposed; we only have lat/lon/alt.
            // If you expose ENU in the service elsewhere, replace "nan" with real values.
            Triple(Double.NaN, Double.NaN, Double.NaN)
        }
        val v = pose.velocity
        val q = pose.orientation
        val yawDeg = quatYawDeg(q[0].toDouble(), q[1].toDouble(), q[2].toDouble(), q[3].toDouble())
        writeMovementLine(
            "[${System.currentTimeMillis()}] PREDICT " +
            "pred_lat=${fmt(lat)} pred_lon=${fmt(lon)} pred_alt=${fmt(alt)} " +
            "ENU(x=${fmt(Double.NaN)},y=${fmt(Double.NaN)},z=${fmt(Double.NaN)}) " +
            "V(vx=${fmt(v.first)},vy=${fmt(v.second)},vz=${fmt(v.third)}) " +
            "yaw_deg=${fmt(yawDeg)}"
        )
    }
}
