package com.aitech.weartak_civ.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.service.TAKConnectService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

        // fields you want to update/react to in UI:
        val vector: VectorToTarget = VectorToTarget(),
        val state: State = State(),

        // precomputed, but kept in constructor so copy stays ergonomic if needed later
        val target: Target = Target(bhEvent.mapItem),
        val origin: Origin = Origin(bhEvent),
        val compass: Compass = Compass()
    ) {
        val server = bhEvent.server
        val uid = bhEvent.mapItem.uid

        data class Target(
            val mapItem: MarkerMapItem
        ) {
            val name: String = mapItem.cotEvent.detail.markerCallsign
            val point: Point = mapItem.point
            val type: String = mapItem.type
        }

        data class State(
            var acknowledged: Boolean = false,
            var active: Boolean = false,
            var inPos: Boolean = false,
        )

        data class Compass(
            var needleAngleDeg: Double? = null   // <-- for UI rendering
        )

        data class Origin(
            val bhEvent: BloodhoundEvent
        ) {
            val server = bhEvent.server
            val issuerUid = bhEvent.issuer.uid
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
        val server: TAKConnectService.TakServerDetail,   // so we can reply via chat to the proper socket  //TODO: Instead of passing the entire details, we can pass a uuid for a tak server detail map...
        var issuer: Link = mapItem.link,                 // so we send the chat to the right person
        var timeIssued: Long?,                            // tell user on UI when it was sent.
        val timeCompleted: Long?,                         // tell the issuer how long it took.
    )

    data class MarkerCotEvent(
        val version: String,
        val uid: String,
        val type: String,
        val how: String,
        val time: Long,
        val start: Long,
        val stale: Long,
        val access: String?,
        val point: Point,
        val detail: MarkerDetail,
    )

    data class MarkerMapItem(
        val cotEvent: MarkerCotEvent
    ) {
        val uid: String = cotEvent.uid
        val type: String = cotEvent.type
        val callsign: String = cotEvent.detail.markerCallsign
        val point: Point = cotEvent.point
        val creator: Creator = cotEvent.detail.creator   // who created this map item / marker?
        val link: Link = cotEvent.detail.link            // who sent us this map item / marker?
    }

    data class Point(
        val lat: Double,
        val lon: Double,
        val hae: Double,
        val ce: Double,
        val le: Double
    )

    data class MarkerDetail(
        val markerCallsign: String,
        val link: Link,
        val creator: Creator,
    )

    data class Link(
        var uid: String?,
        var productionTime: Long?,
        var type: String?,
        var parentCallsign: String?,
        var relation: String?
    )

    data class Creator(
        var uid: String?,
        var callsign: String?,
        var time: Long?,
        var type: String?,
    )

    /*
     * The UI needs a List of all the current Bloodhounds Items that are
     *    selectable for navigation. These Bloodhound Items can be active or
     *    inactive, acknowledged or unacknowledged.
     */
    // ---- State ----
    private val _bloodhoundMap = MutableStateFlow<Map<String, BloodhoundItem>>(emptyMap())
    val bloodhoundMap: StateFlow<Map<String, BloodhoundItem>> = _bloodhoundMap

    // Derived: UI consumes a flat list of items
    val selectableBloodhounds: StateFlow<List<BloodhoundItem>> =
        _bloodhoundMap
            .map { it.values.toList() }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private inline fun updateBloodhound(
        uid: String,
        crossinline transform: (BloodhoundItem) -> BloodhoundItem
    ) {
        _bloodhoundMap.update { prev ->
            val curr = prev[uid] ?: return@update prev
            prev + (uid to transform(curr))
        }
    }

    // ---------- Helper: choose a "received" timestamp ----------
    private fun BloodhoundItem.receivedAt(): Long {
        // Adjust if you prefer a different source of truth
        return this.origin.bhEvent.timeIssued
            ?: this.target.mapItem.cotEvent.time
    }

    // ---------- Sorted domain list for UI (most recent first) ----------
    val selectableBloodhoundsSorted: StateFlow<List<BloodhoundItem>> =
        _bloodhoundMap
            .map { map -> map.values.sortedByDescending { it.receivedAt() } }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

// ---------- Active UID already exists ----------
    private val _activeUid = MutableStateFlow<String?>(null)
    val activeUid: StateFlow<String?> = _activeUid

    private val _selectedUid = MutableStateFlow<String?>(null)
    val selectedUid: StateFlow<String?> = _selectedUid

    // ---------- UI adapter: List<Utils.BloodhoundItem> ----------
    // "active first, then by received time desc", and adapt to Utils.BloodhoundItem
    val listBloodhoundsUi: StateFlow<List<Utils.BloodhoundItem>> =
        combine(_bloodhoundMap, _activeUid) { map, active ->
            map.values
                .sortedWith(
                    compareByDescending<BloodhoundItem> { it.uid == active }
                        .thenByDescending { it.receivedAt() }
                )
                .map { it.toUi(isActive = (it.uid == active)) }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    // Convenience to expose the active UI item (null if none)
// replace your previous activeBloodhoundUi with this:
    val activeBloodhoundUi: StateFlow<Utils.BloodhoundItem?> =
        combine(activeUid, _bloodhoundMap) { uid, map ->
            uid?.let { map[it] }?.toUi(isActive = true)
        }.stateIn(scope, SharingStarted.Eagerly, null)

    // --- Mapper: domain -> UI model (reuse everywhere)
    private fun BloodhoundItem.toUi(isActive: Boolean): Utils.BloodhoundItem {
        val distKm = this.vector.distance
        val distKmRounded = distKm?.round(2) ?: 0.0
        val needle = (this.compass.needleAngleDeg ?: 0.0).toFloat()

        return Utils.BloodhoundItem(
            target        = this.target.name,
            uid           = this.uid,
            callsign      = this.target.name,
            timeReceived  = this.bhEvent.timeIssued ?: 0,
            distance      = distKmRounded,
            orientation   = needle,
            bloodhounding = isActive
        )
    }

    fun Double.round(decimals: Int): Double {
        val factor = 10.0.pow(decimals)
        return (this * factor).roundToInt() / factor
    }

//    // ---------- Convenience: active BloodHoundItem (derived) ----------
//    val activeBloodhound: StateFlow<BloodhoundItem?> =
//        combine(_activeUid, _bloodhoundMap) { uid, map -> uid?.let { map[it] } }
//            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    // ---- API ----
    fun addOrReplaceBloodhound(item: BloodhoundItem) {
        _bloodhoundMap.update { prev -> prev + (item.uid to item) }
    }

    fun removeBloodhound(uid: String) {
        _bloodhoundMap.update { prev -> prev - uid }
    }

    fun upsertAll(items: Iterable<BloodhoundItem>) {
        _bloodhoundMap.update { prev -> prev + items.associateBy { it.uid } }
    }
    val getLastActiveBloodhound: Flow<String> = context.dataStore.data.map { prefs -> prefs[ACTIVE_BLOOD_HOUND] ?: "" }

    fun updateBloodhoundItemVector(uid: String, vector: VectorToTarget) {
        updateBloodhound(uid) { it.copy(vector = vector) }
    }

    fun updateAckState(uid: String, acknowledged: Boolean) {
        updateBloodhound(uid) { it.copy(state = it.state.copy(acknowledged = acknowledged)) }
    }

    suspend fun storeActiveBloodhoundItem(active: BloodhoundItem) {
        // TODO: Convert BloodHoundMapItem to Json string and store json string.
        context.dataStore.edit { prefs ->
            prefs[ACTIVE_BLOOD_HOUND] = active.toString()
        }
    }

//    private val _activeBloodhound =  MutableStateFlow<BloodHoundItem?>(null)
//    val activeBloodhound: StateFlow<BloodHoundItem?> = _activeBloodhound

    private val _requestActivateBloodhound =  MutableStateFlow<String>("")
    val requestActivateBloodhound: StateFlow<String> = _requestActivateBloodhound

    fun requestActivateBloodhound(uid: String) {
        if (isUidValid(uid)) {
            _requestActivateBloodhound.update { uid }
        }
    }

    private val _requestAcknowledgement = MutableStateFlow<String>("")
    val requestAcknowledgement: StateFlow<String> = _requestAcknowledgement     // Collected by the ChatService to send out a chat message to the issuer
    fun requestAcknowledgement(uid: String) {
        if (isUidValid(uid)) {
            _requestAcknowledgement.update { uid }
        }
    }

    private val _requestInPosition = MutableStateFlow<String>("")
    val requestInPosition: StateFlow<String> = _requestInPosition  // Collected by the ChatService to send out a chat message to the issuer
    fun requestInPosition(uid: String) {
        _requestInPosition.update { uid }
    }

    fun setAcknowledged(uid: String, acknowledged: Boolean) {
        updateBloodhound(uid) { it.copy(state = it.state.copy(acknowledged = acknowledged)) }
    }

    fun setActive(uid: String, active: Boolean) {
        updateBloodhound(uid) { it.copy(state = it.state.copy(active = active)) }
    }

    fun setInPos(uid: String, inPos: Boolean) {
        updateBloodhound(uid) { it.copy(state = it.state.copy(inPos = inPos)) }
    }

    fun isUidValid(uid: String) = _bloodhoundMap.value.containsKey(uid)

    sealed interface BloodhoundCommand {
        data class InPosition(val uid: String): BloodhoundCommand
    }

    fun setActiveBloodhound(uid: String): Boolean {
        if (!isUidValid(uid)) return false

        val currentActive = _activeUid.value
        if (currentActive != null && currentActive != uid) {
            Log.i(TAG, "Clearing previous active Bloodhound: $currentActive")
            _activeUid.value = null
        }

        _activeUid.value = uid
        Log.i(TAG, "Active Bloodhound set: $uid")
        return true
    }

    fun clearActiveBloodhound() {
        val prev = _activeUid.value
        if (prev != null) {
            Log.i(TAG, "Active Bloodhound cleared: $prev")
            _activeUid.value = null
        }
    }

    fun updateBloodhoundCompassNeedle(uid: String, needleDeg: Double) {
        _bloodhoundMap.update { currentMap ->
            val item = currentMap[uid] ?: return@update currentMap  // nothing to change

            // If angle is unchanged, avoid emitting a new map
            if (item.compass.needleAngleDeg == needleDeg) return@update currentMap

            val updated = HashMap(currentMap)
            updated[uid] = item.copy(
                compass = item.compass.copy(needleAngleDeg = needleDeg)
            )
            updated
        }
    }

    private val _pendingBloodHounds = MutableStateFlow<List<BloodhoundItem>?>(null)
    val pendingBloodhounds: StateFlow<List<BloodhoundItem>?> = _pendingBloodHounds

    val getEnableBloodhoundService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE] ?: false
    }

    private val _bloodhoundEvent = MutableStateFlow<BloodhoundEvent?>(null)
    val bloodhoundEvent: StateFlow<BloodhoundEvent?> = _bloodhoundEvent

    suspend fun updateBloodhoundEvent(event: BloodhoundEvent) {
        _bloodhoundEvent.emit(event)
    }

    suspend fun storeEnableBloodhoundService(enable: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE] = enable
        }
    }

    // ===== Compass section START =====

    // --- Manual override: prefer GPS Course over Ground as heading instead of magnetometer?
    private val _useGpsCogAsHeading = MutableStateFlow(false)
    val useGpsCogAsHeading: StateFlow<Boolean> = _useGpsCogAsHeading
    fun setUseGpsCogAsHeading(enabled: Boolean) { _useGpsCogAsHeading.value = enabled }

    // --- Magnetometer TRUE azimuth (degrees). Origin: MovementDetectionService should pass TRUE (declination applied upstream)
    private val _magTrueDeg = MutableStateFlow<Double?>(null)
    val magTrueDeg: StateFlow<Double?> = _magTrueDeg
    fun updateMagAzimuthTrue(angleTrueDeg: Double?) {
        _magTrueDeg.value = angleTrueDeg?.let(::normalize0to360)
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
        combine(useGpsFinal, gpsCogTrueDeg, magTrueDeg) { useGps, gps, mag ->
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