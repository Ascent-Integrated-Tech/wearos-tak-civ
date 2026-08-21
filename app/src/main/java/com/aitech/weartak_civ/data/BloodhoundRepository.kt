/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.cot.ChatRoom
import com.aitech.weartak_civ.cot.CotLink
import com.aitech.weartak_civ.cot.CotMarkerDetail
import com.aitech.weartak_civ.cot.CotMarkerEvent
import com.aitech.weartak_civ.cot.CotMarkerMapItem
import com.aitech.weartak_civ.cot.CotPoint
import com.aitech.weartak_civ.provider.TargetVector
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

    data class BloodhoundItem(
        val bhEvent: BloodhoundEvent,

        // fields you want to update/react to in UI and service:
        val vector: TargetVector = TargetVector(),
        val state: State = State(),
        val target: Target = Target(bhEvent.mapItem),
        val origin: Origin = Origin(bhEvent),
        val compass: Compass = Compass()
    ) {
        val server = bhEvent.server
        val uid = bhEvent.mapItem.uid
        val originLabel: String?
            get() = bhEvent.issuer?.parentCallsign
                ?: bhEvent.mapItem.creator?.callsign

        val containsHoundmasterRemark: Boolean
            get() = bhEvent.remark.equals("#houndmaster")


        data class Target(
            val mapItem: CotMarkerMapItem
        ) {
            val name: String? = mapItem.cotEvent.detail.markerCallsign
            val point: CotPoint = mapItem.point
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
            val chatRoom: ChatRoom? = null
        }
    }

    enum class ActivationResult {
        FIRST_ACK,
        SWITCH_ONLY,
        NO_CHANGE
    }

    /*
     *  Backend uses this to map devices back
     */
    data class BloodhoundEvent(
        val mapItem: CotMarkerMapItem,                   // to identify the event, calc trajectory,
        val uid: String = mapItem.uid,                   // unique identifier of the event
        val server: String,                              // so we can reply via chat to the proper socket  //TODO: Instead of passing the entire details, we can pass a uuid for a tak server detail map...
        var issuer: CotLink? = mapItem.link,              // so we send the chat to the right person
        var timeIssued: Long?,                            // tell user on UI when it was sent.
        val timeCompleted: Long?,                         // tell the issuer how long it took.
        val remark: String = ""
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
        val distKm = this.vector.distance ?: 0.0
        val needle = this.compass.targetBearingDeg ?: 0.0

//        val eventMs = Utils.toMillis(bhEvent.timeIssued) ?: System.currentTimeMillis()
        val eventMs = System.currentTimeMillis()

        return Utils.BloodhoundItem(
            target = this.target.name ?: "unknown",
            uid = this.uid,
            callsign = this.originLabel ?: "Unknown sender",
            timeReceived = eventMs,
            distance = distKm,
            orientation = needle.round(1).toFloat(),
            bloodhounding = isActive,
            acknowledged = this.state.acknowledged
        )
    }
    fun addMarkerTarget(
        uid: String,
        title: String,
        markerType: String,
        lat: Double,
        lon: Double,
        time: Long
    ) {
        val point = CotPoint(
            lat = lat,
            lon = lon,
            hae = 0.0,
            ce = 999999.0,
            le = 999999.0
        )

        val detail = CotMarkerDetail(
            markerCallsign = title,
            link = null,
            creator = null,
            usericon = null,
            remarks = null,
            contact = null
        )

        val cotEvent = CotMarkerEvent(
            version = "2.0",
            uid = uid,
            type = markerType,
            how = "h-g-i-g-o",
            time = time,
            start = time,
            stale = time + 300_000,
            access = null,
            point = point,
            detail = detail
        )

        val mapItem = CotMarkerMapItem(cotEvent)

        upsertFromEvent(
            BloodhoundEvent(
                mapItem = mapItem,
                server = "local",
                timeIssued = time,
                timeCompleted = null
            )
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

    val activeBhItemMap: StateFlow<BloodhoundItem?> =
        combine(activeUid, _bloodhoundMap) { uid, map ->
            uid?.let { map[it] }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    fun Double.round(decimals: Int): Double {
        val factor = 10.0.pow(decimals)
        return (this * factor).roundToInt() / factor
    }

    // ---- API ----
    fun updateBloodhoundItemVector(uid: String, vector: TargetVector) {
        updateBloodhound(uid) { it.copy(vector = vector) }
    }

    fun upsertFromEvent(e: BloodhoundEvent) {
        _bloodhoundMap.update { prev ->
            val curr = prev[e.uid]
            val updated = if (curr == null) {
                BloodhoundItem(e)
            } else {
                // Preserve state; refresh event payload
                curr.copy(bhEvent = e, target = BloodhoundItem.Target(e.mapItem), origin = BloodhoundItem.Origin(e))
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

    fun activate(uid: String): Pair<ActivationResult, BloodhoundItem?> {
        val currentItem = _bloodhoundMap.value[uid] ?: return ActivationResult.NO_CHANGE to null
        val currentActiveUid = _activeUid.value
        val result = when {
            currentItem.state.acknowledged -> ActivationResult.SWITCH_ONLY
            else -> ActivationResult.FIRST_ACK
        }

        _bloodhoundMap.update { prev ->
            prev.mapValues { (key, value) ->
                if (key == uid) {
                    value.copy(state = value.state.copy(active = true, acknowledged = true))
                } else {
                    value.copy(state = value.state.copy(active = false))
                }
            }
        }
        _activeUid.value = uid

        val updatedItem = _bloodhoundMap.value[uid] ?: currentItem
        val normalizedResult = if (currentActiveUid == uid && currentItem.state.acknowledged) {
            ActivationResult.NO_CHANGE
        } else {
            result
        }
        return normalizedResult to updatedItem
    }

    fun setInPos(uid: String): BloodhoundItem? {
        val item = _bloodhoundMap.value[uid] ?: return null
        val wasActive = (_activeUid.value == uid)
        _bloodhoundMap.update { prev -> prev - uid }
        if (wasActive) _activeUid.value = null
        return item
    }

    fun isUidValid(uid: String) = _bloodhoundMap.value.containsKey(uid)

    fun remarkFromHoundmaster(uid: String) = _bloodhoundMap.value[uid]?.containsHoundmasterRemark ?: false

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


    companion object {
        private val ENABLE = booleanPreferencesKey("enable_bloodhound_service")
        private val ACTIVE_BLOOD_HOUND = stringPreferencesKey("active_bloodhound")
    }
}
