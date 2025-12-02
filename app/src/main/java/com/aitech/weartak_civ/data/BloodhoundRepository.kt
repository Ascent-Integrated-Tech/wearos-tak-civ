package com.aitech.weartak_civ.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.Utils.CotUtils.Point
import com.aitech.weartak_civ.Utils.CotUtils.MarkerMapItem
import com.aitech.weartak_civ.Utils.CotUtils.Link
import com.aitech.weartak_civ.WearTakApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlin.math.pow
import kotlin.math.roundToInt

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bloodhound")
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
class BloodhoundRepository(private val context: Context) {
    private val TAG = "WearTAK-" + BloodhoundRepository::class.java.simpleName
    private val repo by lazy {
        try {
            (context as WearTakApplication).repo
        } catch (_: Throwable) {
            DataRepository(context)
        }
    }
    data class VectorToTarget(
        val distance: Double? = null,
        val bearing: Double? = null
    )

    data class BloodhoundItem(
        val bhEvent: BloodhoundEvent,

        // fields you want to update/react to in UI and service:
        val vector: VectorToTarget = VectorToTarget(),
        val state: State = State(),
        val target: Target = Target(bhEvent.mapItem),
        val origin: Origin = Origin(bhEvent),
        val compass: Compass = Compass()
    ) {
        val server = bhEvent.server
        val uid = bhEvent.mapItem.uid

        data class Target(
            val mapItem: Utils.CotUtils.MarkerMapItem
        ) {
            val name: String? = mapItem.cotEvent.detail.markerCallsign
            val point: Point = mapItem.point
            val type: String = mapItem.type
        }

        data class State(
            var acknowledged: Boolean = false,
            var active: Boolean = false,
            var inPos: Boolean = false,
        )

        data class Compass(
            var targetBearingDeg: Double? = null,
        )

        data class Origin(
            val bhEvent: BloodhoundEvent
        ) {
            val server = bhEvent.server
            val issuerUid = bhEvent.issuer?.uid
            val chatRoomUid = issuerUid
            val chatRoom: Utils.ChatRoom? = null
        }
    }

    /*
     *  Backend uses this to map devices back
     */
    data class BloodhoundEvent(
        val mapItem: MarkerMapItem,                      // to identify the event, calc trajectory,
        val uid: String = mapItem.uid,                   // unique identifier of the event
        val server: String,                              // so we can reply via chat to the proper socket  //TODO: Instead of passing the entire details, we can pass a uuid for a tak server detail map...
        var issuer: Link? = mapItem.link,                 // so we send the chat to the right person
        var timeIssued: Long?,                            // tell user on UI when it was sent.
        val timeCompleted: Long?,                         // tell the issuer how long it took.
    )

    /*
     * The UI needs a List of all the current Bloodhounds Items that are
     *    selectable for navigation. These Bloodhound Items can be active or
     *    inactive, acknowledged or unacknowledged.
     */
    // ---- State ----
    private val _bloodhoundMap = MutableStateFlow<Map<String, BloodhoundItem>>(emptyMap())
    val bloodhoundMap: StateFlow<Map<String, BloodhoundItem>> = _bloodhoundMap

    private inline fun updateBloodhound(
        uid: String,
        crossinline transform: (BloodhoundItem) -> BloodhoundItem
    ) {
        _bloodhoundMap.update { prev ->
            val curr = prev[uid] ?: return@update prev
            prev + (uid to transform(curr))
        }
    }

    // So we can filter da list of hounds
    private fun BloodhoundItem.receivedAt(): Long {
        return this.origin.bhEvent.timeIssued // comes from cot event time
            ?: this.target.mapItem.cotEvent.time
    }

    // Detail screen activates a uid, downstream updates UI list and active
    private val _activeUid = MutableStateFlow<String?>(null)
    val activeUid: StateFlow<String?> = _activeUid

    // --- UI Adapter function
    //  - backend domain item -> frontend UI model item (Utils.BloodhoundItem)
    private fun BloodhoundItem.toUi(isActive: Boolean): Utils.BloodhoundItem {
        val distKm = this.vector.distance
        val distKmRounded = distKm?.round(2) ?: 0.0
        val needle = this.compass.targetBearingDeg ?: 0.0

//        val eventMs = Utils.toMillis(bhEvent.timeIssued) ?: System.currentTimeMillis()
        val eventMs = System.currentTimeMillis()

        return Utils.BloodhoundItem(
            target = this.target.name ?: "unknown",
            uid = this.uid,
            callsign = this.bhEvent.issuer?.parentCallsign ?: "unknown",
            timeReceived = eventMs,
            distance = distKmRounded,
            orientation = needle.round(1).toFloat(),
            bloodhounding = isActive
        )
    }

    // UI adapter, list to populate chips in the bloodhounds screen
    val listBhItemsUi: StateFlow<List<Utils.BloodhoundItem>> =
        combine(_bloodhoundMap, _activeUid) { map, active ->
            map.values
                .sortedWith(
                    compareByDescending<BloodhoundItem> { it.uid == active }
                        .thenByDescending { it.receivedAt() }
                )
                .map { it.toUi(isActive = (it.uid == active)) }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    // UI adapter, BloodhoundItem used by Main Screen button and Complication
    val activeBhItemUi: StateFlow<Utils.BloodhoundItem?> =
        combine(activeUid, _bloodhoundMap) { uid, map ->
            uid?.let { map[it] }?.toUi(isActive = true)
        }.stateIn(scope, SharingStarted.Eagerly, null)

    fun Double.round(decimals: Int): Double {
        val factor = 10.0.pow(decimals)
        return (this * factor).roundToInt() / factor
    }

    // ---- API ----
    fun updateBloodhoundItemVector(uid: String, vector: VectorToTarget) {
        updateBloodhound(uid) { it.copy(vector = vector) }
    }

    fun upsertFromEvent(e: BloodhoundEvent) {
        _bloodhoundMap.update { prev ->
            val curr = prev[e.uid]
            val updated = if (curr == null) {
                BloodhoundItem(e)
            } else {
                // Preserve state; refresh event payload
                curr.copy(bhEvent = e)
            }
            prev + (e.uid to updated)
        }
    }

    suspend fun storeActiveBloodhoundItems(list: List<BloodhoundItem>) {
        // TODO: Convert BloodHoundMapItem to Json string and store json string.
        context.dataStore.edit { prefs ->
            prefs[ACTIVE_BLOOD_HOUND] = list.toString()
        }
    }
    val getLastActiveBloodhound: Flow<String> = context.dataStore.data.map { prefs -> prefs[ACTIVE_BLOOD_HOUND] ?: "" }

    private val _requestActivateBloodhound = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val requestActivateBloodhound = _requestActivateBloodhound.asSharedFlow()

    private val _requestAcknowledgement = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val requestAcknowledgement = _requestAcknowledgement.asSharedFlow()

    private val _requestInPosition = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val requestInPosition = _requestInPosition.asSharedFlow()

    fun requestInPosition(uid: String) {
        _requestInPosition.tryEmit(uid)
    }

    fun requestActivateBloodhound(uid: String) {
        if (isUidValid(uid)) _requestActivateBloodhound.tryEmit(uid)
    }

    fun setActive(uid: String, active: Boolean) {
        if (!isUidValid(uid)) return
        _bloodhoundMap.update { prev ->
            prev.mapValues { (k, v) ->
                val makeActive = active && (k == uid)
                v.copy(state = v.state.copy(active = makeActive))
            }
        }
        _activeUid.value = if (active) uid else null
    }

    fun setInPos(uid: String) {
        // (emit any side-effects first, if needed)
        val wasActive = (_activeUid.value == uid)
        _bloodhoundMap.update { prev -> prev - uid }
        if (wasActive) _activeUid.value = null
    }

    fun isUidValid(uid: String) = _bloodhoundMap.value.containsKey(uid)

    fun updateBloodhoundCompassNeedle(uid: String, needleDeg: Double) {
        _bloodhoundMap.update { currentMap ->
            val item = currentMap[uid] ?: return@update currentMap  // nothing to change
            // If angle is unchanged, avoid emitting a new map
            if (item.compass.targetBearingDeg == needleDeg) return@update currentMap

            val updated = HashMap(currentMap)
            updated[uid] = item.copy(
                compass = item.compass.copy(targetBearingDeg = needleDeg)
            )
            updated
        }
    }

    val getEnableBloodhoundService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE] ?: false
    }

    suspend fun storeEnableBloodhoundService(enable: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE] = enable
        }
    }

    private val _bloodhoundEvent = MutableStateFlow<BloodhoundEvent?>(null)
    val bloodhoundEvent: StateFlow<BloodhoundEvent?> = _bloodhoundEvent

    suspend fun updateBloodhoundEvent(event: BloodhoundEvent) {
        _bloodhoundEvent.emit(event)
    }

    // ===== Compass section START =====

    // --- Manual override: prefer GPS Course over Ground as heading instead of magnetometer?
    private val _useGpsCogAsHeading = MutableStateFlow(false)
    val useGpsCogAsHeading: StateFlow<Boolean> = _useGpsCogAsHeading
    fun setUseGpsCogAsHeading(enabled: Boolean) { _useGpsCogAsHeading.value = enabled }

    // --- Magnetometer TRUE azimuth (degrees). Origin: MovementDetectionService should pass TRUE (declination applied upstream)
    private val _magAzTrueDeg = MutableStateFlow<Double?>(null)
    val magAzTrueDeg: StateFlow<Double?> = _magAzTrueDeg
    fun updateMagAzimuthTrue(angleTrueDeg: Double?) {
        _magAzTrueDeg.value = angleTrueDeg?.let(::normalize0to360)
    }

    // --- Auto rule: when moving with valid COG, prefer GPS
    val autoUseGps: StateFlow<Boolean> =
        repo.latestLocation
            .map { loc ->
                loc != null && loc.hasSpeed() && loc.speed > 1.5f && loc.hasBearing()
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)

    // --- GPS Course-Over-Ground heading (assumed TRUE degrees on Android GNSS; normalize just in case)
    val gpsCogTrueDeg: StateFlow<Double?> =
        repo.latestLocation
            .map { loc ->
                if (loc != null && loc.hasBearing() && loc.hasSpeed() && loc.speed > 1.5f)
                    normalize0to360(loc.bearing.toDouble())
                else null
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    // --- Final selection: manual override takes precedence; otherwise follow autoUseGps
    private val useGpsFinal: StateFlow<Boolean> =
        combine(useGpsCogAsHeading, autoUseGps) { manual, auto ->
            if (manual) true else auto
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)

    // --- Fused azimuth (TRUE degrees)
    val azimuthTrueDeg: StateFlow<Double?> =
        combine(useGpsFinal, gpsCogTrueDeg, magAzTrueDeg) { useGps, gps, mag ->
            val chosen = if (useGps) (gps ?: mag) else (mag ?: gps)
            chosen?.let(::normalize0to360)
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    // --- Optional: smoothed azimuth for UI
    val azimuthTrueDegSmoothed: StateFlow<Double?> =
        azimuthTrueDeg
            .scan<Double?, Double?>(null) { prev, curr ->
                when {
                    prev == null -> curr
                    curr == null -> null
                    else -> smoothAngle(prev, curr, alpha = 0.15)
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    // --- Helper: wrap angle to [0, 360)
    private fun normalize0to360(deg: Double): Double =
        ((deg % 360.0) + 360.0) % 360.0

    // --- Helper: smooth shortest-arc angle transition
    private fun smoothAngle(prevDeg: Double, newDeg: Double, alpha: Double): Double {
        var delta = newDeg - prevDeg
        if (delta > 180) delta -= 360.0
        if (delta < -180) delta += 360.0
        return normalize0to360(prevDeg + alpha * delta)
    }
    // ===== Compass section END =====

    companion object {
        private val ENABLE = booleanPreferencesKey("enable_bloodhound_service")
        private val ACTIVE_BLOOD_HOUND = stringPreferencesKey("active_bloodhound")
    }
}