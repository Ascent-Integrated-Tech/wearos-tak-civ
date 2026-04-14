package com.aitech.weartak_civ.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.data.BloodhoundRepository.ActivationResult
import com.aitech.weartak_civ.data.BloodhoundRepository.BloodhoundItem
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.provider.LocationVectorCalculator
import com.aitech.weartak_civ.provider.TargetVector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlin.math.PI

class BloodhoundService: Service() {

    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob())
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
                if (loc != null && bhRepo.bloodhoundMap.value.isNotEmpty()) {
                    updateVectors()
                }
            }
        }
    }

    private fun collectBloodhoundFlows() {
//        collectBloodMapFlow()
        collectBloodEventFlow()
        collectAzimuthFlow()
        collectAckReqFlow()
        collectActivationReqFlow()
        collectInPosReqFlow()
    }

    private fun collectAzimuthFlow() {
        scope.launch {
            repo.azimuthTrueDegSmoothed.collect { azimuth ->
                val map = bhRepo.bloodhoundMap.value
                map.values.forEach { item ->
                    if (azimuth != null) {
                        updateCompassAngle(item.uid, item.vector.bearing, azimuth)
                    }
                }
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

    private fun updateCompassAngle(uid: String, targetBearing: Double?, azimuthTrueDeg: Double) {
        val needleAngle = LocationVectorCalculator.needleAngle(targetBearing, azimuthTrueDeg) ?: return
        bhRepo.updateBloodhoundCompassNeedle(uid, needleAngle)
    }

    private var previousVectors = mutableMapOf<String, TargetVector>()

    private fun updateVectors() {
        scope.launch {
            val map = bhRepo.bloodhoundMap.value
            val currentLoc = repo.latestLocation.value ?: return@launch
            val currentAzimuth = repo.azimuthTrueDegSmoothed.value

            map.values.forEach { item ->
                val vector = LocationVectorCalculator.calculate(
                    currentLoc.latitude,
                    currentLoc.longitude,
                    item.target.point.lat,
                    item.target.point.lon
                )
                bhRepo.updateBloodhoundItemVector(uid = item.uid, vector)
                if (currentAzimuth != null) {
                    updateCompassAngle(item.uid, vector.bearing, currentAzimuth)
                }
            }

            // Save current vectors for next comparison
            previousVectors = map.values.associateBy({ it.uid }, { it.vector }).toMutableMap()
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
//        private const val EARTH_RADIUS_M = 6_371_000.0 // meters

    }

}
