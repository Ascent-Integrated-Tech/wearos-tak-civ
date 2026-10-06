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
import android.util.Log
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
import kotlinx.coroutines.flow.stateIn
import kotlin.math.pow
import kotlin.math.roundToInt

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bloodhound")
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private const val NON_TAK_BLOODHOUND_SOURCE_ID = "non-tak"
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
        val sourceServerId = bhEvent.sourceServerId
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
        val remark: String = "",
        val sourceServerId: String? = null,
        val receivedAt: Long = System.currentTimeMillis()
    )

    data class BloodhoundUpdate(
        val event: BloodhoundEvent,
        val isNewUid: Boolean,
        val becameHoundmaster: Boolean
    )

    /*
     * The UI needs a List of all the current Bloodhounds Items that are
     *    selectable for navigation. These Bloodhound Items can be active or
     *    inactive, acknowledged or unacknowledged.
     */
    // ---- State ----
    private val bloodhoundStateLock = Any()
    private val bloodhoundStore = SourceAwareCotStore(
        sourceIdOf = { item: BloodhoundItem -> sourceKey(item.bhEvent).sourceId },
        cotUidOf = BloodhoundItem::uid,
        eventTimeOf = { item: BloodhoundItem -> item.bhEvent.mapItem.cotEvent.time },
        receivedAtOf = { item: BloodhoundItem -> item.bhEvent.receivedAt }
    )
    private val _bloodhoundMap = MutableStateFlow<Map<String, BloodhoundItem>>(emptyMap())
    val bloodhoundMap: StateFlow<Map<String, BloodhoundItem>> = _bloodhoundMap

    private inline fun updateBloodhound(
        uid: String,
        crossinline transform: (BloodhoundItem) -> BloodhoundItem
    ) {
        synchronized(bloodhoundStateLock) {
            bloodhoundStore.updateVisible(uid) { current -> transform(current) }
            publishVisibleBloodhounds()
        }
    }

    // So we can filter da list of hounds
    private fun BloodhoundItem.receivedAt(): Long {
        return bhEvent.receivedAt
    }

    // Detail screen activates a uid, downstream updates UI list and active
    private val _activeKey = MutableStateFlow<SourceCotKey?>(null)
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
        combine(bloodhoundMap, _activeKey) { map, activeKey ->
            map.values
                .sortedWith(
                    compareByDescending<BloodhoundItem> {
                        sourceKey(it.bhEvent) == activeKey
                    }
                        .thenByDescending { it.receivedAt() }
                )
                .map { item ->
                    item.toUi(isActive = sourceKey(item.bhEvent) == activeKey)
                }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    // UI adapter, BloodhoundItem used by Main Screen button and Complication
    val activeBhItemUi: StateFlow<Utils.BloodhoundItem?> =
        combine(_activeKey, bloodhoundStore.allItems) { key, items ->
            key?.let(items::get)?.toUi(isActive = true)
        }.stateIn(scope, SharingStarted.Eagerly, null)

    val activeBhItemMap: StateFlow<BloodhoundItem?> =
        combine(_activeKey, bloodhoundStore.allItems) { key, items ->
            key?.let(items::get)
        }.stateIn(scope, SharingStarted.Eagerly, null)

    fun Double.round(decimals: Int): Double {
        val factor = 10.0.pow(decimals)
        return (this * factor).roundToInt() / factor
    }

    // ---- API ----
    fun updateBloodhoundItemVector(uid: String, vector: TargetVector) {
        updateBloodhound(uid) { it.copy(vector = vector) }
    }

    fun upsertFromEvent(e: BloodhoundEvent) = synchronized(bloodhoundStateLock) {
        val key = sourceKey(e)
        val current = bloodhoundStore.item(key)
        val updated = if (current == null) {
            BloodhoundItem(e)
        } else {
            // Preserve state; refresh event payload for this source-owned copy.
            current.copy(
                bhEvent = e,
                target = BloodhoundItem.Target(e.mapItem),
                origin = BloodhoundItem.Origin(e)
            )
        }
        val result = bloodhoundStore.upsertWithResult(updated)
        publishVisibleBloodhounds()
        Log.d(
            TAG,
            "Map state bloodhound upsert uid=${e.uid} sourceServerId=${e.sourceServerId} " +
                "eventTime=${e.mapItem.cotEvent.time} receivedAt=${e.receivedAt} " +
                "applied=${result.applied} visibleSourceId=${bloodhoundStore.visibleKey(e.uid)?.sourceId} " +
                "channelOwnership=unavailable"
        )

        val activeKey = _activeKey.value
        if (activeKey?.cotUid == e.uid && bloodhoundStore.visibleKey(e.uid) != activeKey) {
            setActiveKey(null)
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

    fun activate(uid: String): Pair<ActivationResult, BloodhoundItem?> =
        synchronized(bloodhoundStateLock) {
            val selectedKey = bloodhoundStore.visibleKey(uid)
                ?: return@synchronized ActivationResult.NO_CHANGE to null
            val currentItem = bloodhoundStore.item(selectedKey)
                ?: return@synchronized ActivationResult.NO_CHANGE to null
            val currentActiveKey = _activeKey.value
            val result = when {
                currentItem.state.acknowledged -> ActivationResult.SWITCH_ONLY
                else -> ActivationResult.FIRST_ACK
            }

            bloodhoundStore.transformAll { key, value ->
                if (key == selectedKey) {
                    value.copy(state = value.state.copy(active = true, acknowledged = true))
                } else {
                    value.copy(state = value.state.copy(active = false))
                }
            }
            publishVisibleBloodhounds()
            setActiveKey(selectedKey)

            val updatedItem = bloodhoundStore.item(selectedKey) ?: currentItem
            val normalizedResult = if (
                currentActiveKey == selectedKey && currentItem.state.acknowledged
            ) {
                ActivationResult.NO_CHANGE
            } else {
                result
            }
            normalizedResult to updatedItem
        }

    fun setInPos(uid: String): BloodhoundItem? = synchronized(bloodhoundStateLock) {
        val activeKey = _activeKey.value
        val key = activeKey?.takeIf { it.cotUid == uid }
            ?: bloodhoundStore.visibleKey(uid)
            ?: return@synchronized null
        val item = bloodhoundStore.remove(key) ?: return@synchronized null
        publishVisibleBloodhounds()
        if (activeKey == key) setActiveKey(null)
        item
    }

    fun removeFromMenu(uid: String): BloodhoundItem? = synchronized(bloodhoundStateLock) {
        val key = bloodhoundStore.visibleKey(uid) ?: return@synchronized null
        val item = bloodhoundStore.remove(key) ?: return@synchronized null
        publishVisibleBloodhounds()
        if (_activeKey.value == key) setActiveKey(null)
        item
    }

    fun isUidValid(uid: String) = bloodhoundStore.visibleItem(uid) != null

    fun remarkFromHoundmaster(uid: String) =
        bloodhoundStore.visibleItem(uid)?.containsHoundmasterRemark ?: false

    fun updateBloodhoundCompassNeedle(uid: String, needleDeg: Double) {
        synchronized(bloodhoundStateLock) {
            bloodhoundStore.updateVisible(uid) { item ->
                if (item.compass.targetBearingDeg == needleDeg) {
                    item
                } else {
                    item.copy(compass = item.compass.copy(targetBearingDeg = needleDeg))
                }
            }
            publishVisibleBloodhounds()
        }
    }

    fun clearSource(serverId: String): List<BloodhoundItem> = synchronized(bloodhoundStateLock) {
        val activeKey = _activeKey.value
        val activeWasRemoved = activeKey?.sourceId == serverId
        val removed = bloodhoundStore.removeSource(serverId)
        publishVisibleBloodhounds()
        if (_bloodhoundEvent.value?.event?.sourceServerId == serverId) {
            _bloodhoundEvent.value = null
        }
        if (activeWasRemoved) {
            setActiveKey(null)
        }
        removed
    }

    private fun setActiveKey(key: SourceCotKey?) {
        _activeKey.value = key
        _activeUid.value = key?.cotUid
    }

    private fun publishVisibleBloodhounds() {
        _bloodhoundMap.value = bloodhoundStore.visibleItems.value.associateBy(BloodhoundItem::uid)
    }

    private fun sourceKey(event: BloodhoundEvent): SourceCotKey = SourceCotKey(
        sourceId = event.sourceServerId ?: NON_TAK_BLOODHOUND_SOURCE_ID,
        cotUid = event.uid
    )



    val getEnableBloodhoundService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE] ?: false
    }

    suspend fun storeEnableBloodhoundService(enable: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE] = enable
        }
    }

    private val _bloodhoundEvent = MutableStateFlow<BloodhoundUpdate?>(null)
    val bloodhoundEvent: StateFlow<BloodhoundUpdate?> = _bloodhoundEvent

    suspend fun updateBloodhoundEvent(event: BloodhoundEvent) {
        synchronized(bloodhoundStateLock) {
            val isNewUid = !isUidValid(event.uid)
            val previouslyFromHoundmaster = if (isNewUid) {
                false
            } else {
                remarkFromHoundmaster(event.uid)
            }
            upsertFromEvent(event)
            _bloodhoundEvent.value = BloodhoundUpdate(
                event = event,
                isNewUid = isNewUid,
                becameHoundmaster = event.remark == "#houndmaster" && !previouslyFromHoundmaster
            )
        }
    }


    companion object {
        private val ENABLE = booleanPreferencesKey("enable_bloodhound_service")
        private val ACTIVE_BLOOD_HOUND = stringPreferencesKey("active_bloodhound")
    }
}
