package com.aitech.weartak_civ.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.IBinder
import android.util.Log
import com.aitech.weartak_civ.BLOODHOUND_PROXIMITY_INTENSITY_BASIC
import com.aitech.weartak_civ.BLOODHOUND_PROXIMITY_INTENSITY_ESCALATED
import com.aitech.weartak_civ.BLOODHOUND_PROXIMITY_INTENSITY_PERSISTENT
import com.aitech.weartak_civ.DEFAULT_BLOODHOUND_PROXIMITY_INTENSITY
import com.aitech.weartak_civ.DEFAULT_BLOODHOUND_PROXIMITY_RADIUS_METERS
import com.aitech.weartak_civ.DEFAULT_ENABLE_BLOODHOUND_PROXIMITY_VIBRATION
import com.aitech.weartak_civ.FEATURE_ENABLE_BLOODHOUND_PROXIMITY_INTENSITY_SELECTION
import com.aitech.weartak_civ.Utils
import java.util.concurrent.atomic.AtomicBoolean
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.data.BloodhoundRepository.ActivationResult
import com.aitech.weartak_civ.data.BloodhoundRepository.BloodhoundItem
import com.aitech.weartak_civ.data.BloodhoundRepository.VectorToTarget
import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI

class BloodhoundService: Service() {

    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var enableBloodhoundProximityVibration = DEFAULT_ENABLE_BLOODHOUND_PROXIMITY_VIBRATION
    private var bloodhoundProximityRadiusMeters = DEFAULT_BLOODHOUND_PROXIMITY_RADIUS_METERS
    private var bloodhoundProximityIntensity = DEFAULT_BLOODHOUND_PROXIMITY_INTENSITY
    private var proximityAlertActiveUid: String? = null
    private var proximityAlertFiredForActiveUid = false
    private var proximityAlertJob: Job? = null

    private val repo by lazy {
        try {
            (applicationContext as WearTakApplication).repo
        } catch (_: Throwable) {
            DataRepository(applicationContext)
        }
    }

    private val bhRepo by lazy {
        try {
            Log.i(TAG, "")
            (applicationContext as WearTakApplication).bhRepo
        } catch (_: Throwable) {
            BloodhoundRepository(applicationContext)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BloodhoundService onCreate()")
        isRunning.set(true)
        startForegroundServiceCompat(
            BATTERY_MONITOR_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )
        collectBloodhoundFlows()
        collectLocation()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "BloodhoundService onDestroy()")
        cancelActiveProximityAlert()
        isRunning.set(false)
        scope.coroutineContext.cancel()
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "BloodhoundService onStartCommand()")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun collectLocation() {
        scope.launch {
            repo.latestLocation.collect { loc ->
                //Log.i(TAG, "Location Updated")
                if (loc != null) {
                    currLoc = loc
                    val map = bhRepo.bloodhoundMap.value
                    if (map.isNotEmpty()) {
                        updateVectors()
                    }
                }
            }
        }
    }

    private fun collectBloodhoundFlows() {
//        collectBloodMapFlow()
        collectBloodEventFlow()
        collectAzimuthFlow()
        collectProximitySettings()
        collectAckReqFlow()
        collectActivationReqFlow()
        collectInPosReqFlow()
    }

    private fun collectProximitySettings() {
        scope.launch {
            repo.getEnableBloodhoundProximityVibration.collect { enabled ->
                enableBloodhoundProximityVibration = enabled
                if (!enabled) {
                    cancelProximityAlertLoop()
                }
                evaluateActiveTargetProximity()
            }
        }
        scope.launch {
            repo.getBloodhoundProximityRadiusMeters.collect { radiusMeters ->
                bloodhoundProximityRadiusMeters = radiusMeters.coerceAtLeast(0)
                evaluateActiveTargetProximity()
            }
        }
        scope.launch {
            repo.getBloodhoundProximityIntensity.collect { intensity ->
                bloodhoundProximityIntensity = intensity
                cancelProximityAlertLoop()
                evaluateActiveTargetProximity()
            }
        }
    }

    private fun collectAzimuthFlow() {
        scope.launch {
            bhRepo.azimuthTrueDegSmoothed.collect { azimuth ->
//                Log.i(TAG, "New Smooth Azimuth")
                val map = bhRepo.bloodhoundMap.value
                map.values.forEach { item ->
                    val vector = calculateVectorKm(item)
                    bhRepo.updateBloodhoundItemVector(uid = item.uid, vector)
                    if (azimuth != null) {
                        calculateCompassAngle(azimuth, item)
                    }
                }
                evaluateActiveTargetProximity()
            }
        }
    }

    private fun collectBloodEventFlow() {
        scope.launch {
            bhRepo.bloodhoundEvent
                .filterNotNull()
                .collect { event ->
                    //Log.i(TAG, "Bloodhound Event updated:\n$event")
                    val isNewBloodhound = !bhRepo.isUidValid(event.uid)
                    val currRemarkFromHoundmaster = event.remark.equals("#houndmaster")
                    val previousRemarkFromHoundmaster = if (isNewBloodhound) false else bhRepo.remarkFromHoundmaster(event.uid)
                    bhRepo.upsertFromEvent(event)
                    if (isNewBloodhound) {
                        repo.incrementBloodhoundUnreadCount(event.uid)
                    } else {
                        if (currRemarkFromHoundmaster && !previousRemarkFromHoundmaster) {
                            repo.updateHasUnreadBloodhounds(true)
                        }
                        //Log.i(TAG, "Bloodhound event refreshed without unread increment uid=${event.uid}")
                    }
                    updateVectors()
                }
        }
    }

//    private fun collectBloodMapFlow() {
//        scope.launch {
//            bhRepo.bloodhoundMap.collect { map ->
////                Log.i(TAG, "Bloodhound Map changed: \n $map")
//            }
//        }
//    }

    private fun collectAckReqFlow() {
        scope.launch {
            bhRepo.requestAcknowledgement
                .filter { uid -> uid.isNotBlank()}
                .collect { uid ->
                    Log.i(TAG, "Request for Ack Received...")
                    if (uid.isNotBlank()) {
                        Log.i(TAG, "Requesting for Activation.")
                        bhRepo.requestActivateBloodhound(uid)
                    }
            }
        }
    }

    private fun collectActivationReqFlow() {
        scope.launch {
            bhRepo.requestActivateBloodhound.collect { uid ->
                if (bhRepo.isUidValid(uid)) {
                    val (result, item) = bhRepo.activate(uid)
                    when (result) {
                        ActivationResult.FIRST_ACK,
                        ActivationResult.SWITCH_ONLY -> {
                            resetProximityAlertState(item?.uid)
                            evaluateActiveTargetProximity()
                            sendBloodhoundReply(item, "Roger: Bloodhounding to")
                            //openIssuerChat(item)
                            repo.sendBloodhoundUiCommand(DataRepository.BloodhoundUiCommand.CloseBloodhoundScreen)
                        }
                        //ActivationResult.SWITCH_ONLY -> {
                        //    repo.sendBloodhoundUiCommand(DataRepository.BloodhoundUiCommand.CloseBloodhoundScreen)
                        //}
                        ActivationResult.NO_CHANGE -> Unit
                    }
                }
            }
        }
    }

    private fun collectInPosReqFlow() {
        scope.launch {
            bhRepo.requestInPosition.collect { uid ->
                if (uid.isNotBlank()) {
                    val item = bhRepo.setInPos(uid)
                    resetProximityAlertState()
                    //repo.removeReceivedMarkerByUid(uid)
                    sendBloodhoundReply(item, "In Position at")
                    //openIssuerChat(item)
                    repo.sendBloodhoundUiCommand(DataRepository.BloodhoundUiCommand.CloseBloodhoundScreen)
                }
            }
        }
    }

    private suspend fun sendBloodhoundReply(item: BloodhoundItem?, messagePrefix: String) {
        val issuerUid = item?.origin?.issuerUid
        val chatTitle = item?.originLabel ?: "Unknown sender"
        val targetTitle = item?.target?.name
        if (issuerUid.isNullOrBlank() || chatTitle.isNullOrBlank() || targetTitle.isNullOrBlank()) {
            Log.w(TAG, "Skipping bloodhound auto-reply due to missing issuer metadata: uid=$issuerUid, title=$chatTitle")
            return
        }
        val message = "$messagePrefix $targetTitle"
        repo.requestAutomatedChatSend(issuerUid, chatTitle, message, System.currentTimeMillis())
    }

    private suspend fun openIssuerChat(item: BloodhoundItem?) {
        val issuerUid = item?.origin?.issuerUid
        if (issuerUid.isNullOrBlank()) {
            Log.w(TAG, "Skipping bloodhound chat open due to missing issuer uid")
            return
        }
        repo.sendBloodhoundUiCommand(DataRepository.BloodhoundUiCommand.OpenChatRoom(issuerUid))
    }

    private fun calculateCompassAngle(azimuthTrueDeg: Double, item: BloodhoundItem) {
        val bearing = item.vector.bearing ?: return  // if we don’t have a bearing yet, skip
        val needleAngle = normalize0to360(bearing - azimuthTrueDeg)
        bhRepo.updateBloodhoundCompassNeedle(item.uid, needleAngle)
    }

    private fun normalize0to360(deg: Double): Double =
        ((deg % 360.0) + 360.0) % 360.0

    private fun cancelProximityAlertLoop() {
        proximityAlertJob?.cancel()
        proximityAlertJob = null
        Utils.cancelWatchVibration()
    }

    private fun cancelActiveProximityAlert() {
        cancelProximityAlertLoop()
        proximityAlertActiveUid = null
        proximityAlertFiredForActiveUid = false
    }

    private fun resetProximityAlertState(activeUid: String? = null) {
        cancelProximityAlertLoop()
        proximityAlertActiveUid = activeUid
        proximityAlertFiredForActiveUid = false
    }

    private fun activeBloodhoundIntensity(): String {
        return if (FEATURE_ENABLE_BLOODHOUND_PROXIMITY_INTENSITY_SELECTION) {
            bloodhoundProximityIntensity
        } else {
            BLOODHOUND_PROXIMITY_INTENSITY_BASIC
        }
    }

    private fun startProximityAlert() {
        val activeUid = proximityAlertActiveUid ?: return
        proximityAlertFiredForActiveUid = true
        cancelProximityAlertLoop()

        val burstDurationMs = (3 * 150L) + (2 * 120L)
        val gapBetweenBurstsMs = 1500L

        proximityAlertJob = scope.launch {
            when (activeBloodhoundIntensity()) {
                BLOODHOUND_PROXIMITY_INTENSITY_ESCALATED -> {
                    repeat(3) { index ->
                        Utils.vibrateWatch(vibDurationMs = 150L, numberOfVibes = 3, timeBtwnVibesMs = 120L)
                        if (index < 2) {
                            delay(burstDurationMs + gapBetweenBurstsMs)
                        }
                    }
                }
                BLOODHOUND_PROXIMITY_INTENSITY_PERSISTENT -> {
                    while (isActive && proximityAlertActiveUid == activeUid) {
                        Utils.vibrateWatch(vibDurationMs = 150L, numberOfVibes = 3, timeBtwnVibesMs = 120L)
                        delay(burstDurationMs + gapBetweenBurstsMs)
                    }
                }
                else -> {
                    Utils.vibrateWatch(vibDurationMs = 150L, numberOfVibes = 3, timeBtwnVibesMs = 120L)
                }
            }
        }
    }

    private fun evaluateActiveTargetProximity() {
        if (currLoc == null) return

        val activeUid = bhRepo.activeUid.value ?: run {
            resetProximityAlertState()
            return
        }
        val activeItem = bhRepo.bloodhoundMap.value[activeUid] ?: run {
            resetProximityAlertState()
            return
        }

        if (proximityAlertActiveUid != activeUid) {
            resetProximityAlertState(activeUid)
        }
        if (!enableBloodhoundProximityVibration || proximityAlertFiredForActiveUid) return

        val distanceMeters = activeItem.vector.distance?.times(1000.0) ?: return
        if (distanceMeters <= bloodhoundProximityRadiusMeters.toDouble()) {
            startProximityAlert()
            Log.i(
                TAG,
                "Bloodhound proximity alert fired uid=$activeUid distanceMeters=$distanceMeters radiusMeters=$bloodhoundProximityRadiusMeters intensity=${activeBloodhoundIntensity()}"
            )
        }
    }

    private var currLoc: Location? = null
    // …
    private fun getCurrPoint(): Utils.CotUtils.Point =
        currLoc?.let { loc ->
            Utils.CotUtils.Point(
                lat = loc.latitude,
                lon = loc.longitude,
                hae = loc.altitude,
                ce = loc.accuracy.toDouble(),
                le = if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters.toDouble() else 0.0
            )
        } ?: Utils.CotUtils.Point(
            lat = 41.92288763956643,
            lon = -87.6454244503331,
            hae = 1700.0, ce = 0.0, le = 0.0
        )

    private fun calculateVectorKm(item: BloodhoundItem): VectorToTarget {
        val curr = getCurrPoint()
        val tgt = item.target.point

        val lat1 = Math.toRadians(curr.lat)
        val lat2 = Math.toRadians(tgt.lat)
        val deltaLat = Math.toRadians(tgt.lat - curr.lat)
        val deltaLon = Math.toRadians(tgt.lon - curr.lon)

        // --- Haversine distance ---
        val sinLat = kotlin.math.sin(deltaLat / 2)
        val sinLon = kotlin.math.sin(deltaLon / 2)
        val a = sinLat * sinLat +
                kotlin.math.cos(lat1) * kotlin.math.cos(lat2) *
                sinLon * sinLon
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        val distanceKm = (EARTH_RADIUS_M * c) / 1000.0

        // --- Bearing (azimuth) ---
        val y = kotlin.math.sin(deltaLon) * kotlin.math.cos(lat2)
        val x = kotlin.math.cos(lat1) * kotlin.math.sin(lat2) -
                kotlin.math.sin(lat1) * kotlin.math.cos(lat2) * kotlin.math.cos(deltaLon)
        val bearingDeg = (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0

        return VectorToTarget(distance = distanceKm, bearing = bearingDeg)
    }

    private var previousVectors = mutableMapOf<String, VectorToTarget>()

    private fun updateVectors() {
        scope.launch {
            val map = bhRepo.bloodhoundMap.value

            map.values.forEach { item ->
                val vector = calculateVectorKm(item)
                bhRepo.updateBloodhoundItemVector(uid = item.uid, vector)
            }

            // Save current vectors for next comparison
            previousVectors = map.values.associateBy({ it.uid }, { it.vector }).toMutableMap()
            evaluateActiveTargetProximity()
        }
    }

    private fun logBloodhoundTable() {
        fun fmtBool(b: Boolean) = if (b) "Y" else "N"

        val header = """
        |────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
        | UID                 | CALLSIGN            | TARGET (lat,lon)         | VECTOR (d,b)        | PREV (d,b)       | ACTIVE | INPOS |
        |────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
    """.trimMargin()
        val map = bhRepo.bloodhoundMap.value
        val body = buildString {
            map.values.forEach { item ->
                val uid = item.uid.padEnd(20).take(20)
                val name = item.target.name?.padEnd(20)?.take(20)

                val lat = String.format("%.5f", item.target.point.lat)
                val lon = String.format("%.5f", item.target.point.lon)
                val target = "($lat,$lon)".padEnd(24)

                val dist = item.vector.distance?.let { String.format("%.1f", it) } ?: "null"
                val bear = item.vector.bearing?.let { String.format("%.1f", it) } ?: "null"
                val vector = "(${dist.padEnd(4)},${bear.padEnd(4)})".padEnd(20)

                val prev = previousVectors[item.uid]
                val prevDist = prev?.distance?.let { String.format("%.1f", it) } ?: "-"
                val prevBear = prev?.bearing?.let { String.format("%.1f", it) } ?: "-"
                val prevVec = "(${prevDist.padEnd(4)},${prevBear.padEnd(4)})".padEnd(20)

                val active = fmtBool(item.state.active).padEnd(6)
                val inPos = fmtBool(item.state.inPos).padEnd(5)

                appendLine("| $uid | $name | $target | $vector | $prevVec | $active | $inPos |")
            }
        }

        Log.i(TAG, "$header\n$body────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────")
    }

    private fun toDegrees(rads: Double): Double {
        val angle = (((180.0/ PI) * rads) + 180.0)
        return angle
    }

    companion object {
        val isRunning: AtomicBoolean = AtomicBoolean(false)
        private val TAG = "WearTAK-${BloodhoundService::class.java.simpleName}"
        private val BATTERY_MONITOR_SERVICE_NOTIFICATION_ID = 848
        private const val EARTH_RADIUS_M = 6_371_000.0 // meters
    }

}
