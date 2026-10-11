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
import com.aitech.weartak_civ.cot.CotEmergencyEvent
import com.aitech.weartak_civ.provider.TargetVector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
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
class BloodhoundRepository(
    private val context: Context,
    private val dataStore: DataStore<Preferences> = context.dataStore,
) {
    private val TAG = "WearTAK-" + BloodhoundRepository::class.java.simpleName
    private val emergencyMutex = Mutex()
    private var cancellationsLoaded = false
    private val cancellationTimes = mutableMapOf<String, Long>()
    private val dismissalTimes = mutableMapOf<String, Long>()
    private val alertExpiryJobs = mutableMapOf<SourceCotKey, Job>()

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
        val receivedAt: Long = System.currentTimeMillis(),
        val remoteAlert: RemoteAlert? = null
    )

    data class RemoteAlert(
        val category: String,
        val description: String,
        val senderUid: String?,
        val senderCallsign: String?,
        val staleTime: Long,
        val hasLocation: Boolean,
        val isStale: Boolean = false
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
    private val _remoteAlerts = MutableStateFlow<Map<String, BloodhoundItem>>(emptyMap())
    val remoteAlerts: StateFlow<Map<String, BloodhoundItem>> = _remoteAlerts
    val remoteEmergencyMapItems: StateFlow<List<RemoteEmergencyMapItem>> =
        remoteAlerts.map { alerts -> alerts.values.mapNotNull { it.toRemoteEmergencyMapItemOrNull() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun applyEmergencyEvent(
        event: CotEmergencyEvent,
        server: String,
        sourceServerId: String? = null,
        receivedAt: Long = System.currentTimeMillis(),
        deviceUid: String
    ): Boolean = emergencyMutex.withLock {
        if (event.uid.isBlank() || event.time <= 0 || event.start <= 0 || event.stale <= 0 || event.isFrom(deviceUid)) {
            Log.w(TAG, "Emergency ignored reason=invalid-or-self uid=${event.uid}")
            return@withLock false
        }
        loadEmergencyLedgers()
        if (event.cancelled) {
            val previous = cancellationTimes[event.uid] ?: Long.MIN_VALUE
            if (event.time > previous) {
                val clearsDismissal = dismissalTimes[event.uid]?.let { event.time >= it } == true
                // Persist before publishing removal so restart cannot resurrect a cancelled alert.
                dataStore.edit { prefs ->
                    val json = JSONObject(prefs[ALERT_CANCELLATIONS] ?: "{}")
                    json.put(event.uid, maxOf(json.optLong(event.uid, Long.MIN_VALUE), event.time))
                    prefs[ALERT_CANCELLATIONS] = json.toString()
                    if (clearsDismissal) {
                        val dismissed = JSONObject(prefs[ALERT_DISMISSALS] ?: "{}")
                        dismissed.remove(event.uid)
                        prefs[ALERT_DISMISSALS] = dismissed.toString()
                    }
                }
                cancellationTimes[event.uid] = event.time
                if (clearsDismissal) dismissalTimes.remove(event.uid)
            }
            synchronized(bloodhoundStateLock) {
                bloodhoundStore.allItems.value.filter { (key, item) ->
                    key.cotUid == event.uid && item.bhEvent.remoteAlert != null &&
                        item.bhEvent.mapItem.cotEvent.time <= event.time
                }.keys.forEach { key ->
                    bloodhoundStore.remove(key)
                    alertExpiryJobs.remove(key)?.cancel()
                    if (_activeKey.value == key) setActiveKey(null)
                }
                publishVisibleBloodhounds()
            }
            Log.i(TAG, "Emergency cancelled uid=${event.uid} source=$sourceServerId time=${event.time}")
            return@withLock true
        }
        val now = System.currentTimeMillis()
        val cancelledAt = cancellationTimes[event.uid]
        if (cancelledAt != null && event.time <= cancelledAt) {
            Log.i(TAG, "Emergency ignored reason=cancelled uid=${event.uid} time=${event.time} cancelledAt=$cancelledAt")
            return@withLock false
        }
        dismissalTimes[event.uid]?.let { dismissedAt ->
            // Track suppressed refreshes so a delayed cancellation cannot end the wrong session.
            if (event.time > dismissedAt) persistDismissal(event.uid, event.time)
            Log.i(TAG, "Emergency ignored reason=locally-dismissed uid=${event.uid} time=${event.time}")
            return@withLock false
        }
        synchronized(bloodhoundStateLock) {
            val source = sourceServerId ?: "remote:$server"
            val key = SourceCotKey("emergency:$source", event.uid)
            val previous = bloodhoundStore.item(key)
            if (previous != null && event.time < previous.bhEvent.mapItem.cotEvent.time) {
                Log.i(TAG, "Emergency ignored reason=older uid=${event.uid} source=$source time=${event.time}")
                return@synchronized false
            }
            // A location-less refresh can retain only this source's existing valid location.
            val point = if (event.hasLocation) event.point else
                previous?.takeIf { it.bhEvent.remoteAlert?.hasLocation == true }?.target?.point
            val hasLocation = point != null
            val detail = CotMarkerDetail(
                markerCallsign = event.senderCallsign ?: event.uid,
                creator = null,
                link = CotLink(event.senderUid, null, null, event.senderCallsign, null),
                usericon = null,
                remarks = event.description,
                contact = null
            )
            val marker = CotMarkerEvent(
                "2.0", event.uid, event.type, "h-e", event.time, event.start, event.stale, null,
                point ?: CotPoint(0.0, 0.0, 9999999.0, 9999999.0, 9999999.0), detail
            )
            val e = BloodhoundEvent(
                mapItem = CotMarkerMapItem(marker), server = server, timeIssued = event.time,
                timeCompleted = null, sourceServerId = source, receivedAt = receivedAt,
                remoteAlert = RemoteAlert(
                    event.category ?: event.type, event.description.orEmpty(), event.senderUid,
                    event.senderCallsign, event.stale, hasLocation, isStale = event.stale <= now
                )
            )
            val item = previous?.copy(
                bhEvent = e, target = BloodhoundItem.Target(e.mapItem), origin = BloodhoundItem.Origin(e),
                vector = TargetVector(), compass = BloodhoundItem.Compass()
            ) ?: BloodhoundItem(e)
            val isNew = !_remoteAlerts.value.containsKey(event.uid)
            if (!bloodhoundStore.upsertWithResult(item).applied) {
                Log.i(TAG, "Emergency ignored reason=not-newer uid=${event.uid} source=$source time=${event.time}")
                return@synchronized false
            }
            publishVisibleBloodhounds()
            if (_activeKey.value?.cotUid == event.uid && _activeKey.value != visibleKey(event.uid)) {
                setActiveKey(null)
            }
            _bloodhoundEvent.value = BloodhoundUpdate(e, isNew, false)
            alertExpiryJobs.remove(key)?.cancel()
            alertExpiryJobs[key] = scope.launch {
                delay((event.stale - System.currentTimeMillis()).coerceAtLeast(0))
                synchronized(bloodhoundStateLock) {
                    val current = bloodhoundStore.item(key)
                    if (current?.bhEvent?.remoteAlert?.staleTime?.let { it <= System.currentTimeMillis() } == true) {
                        bloodhoundStore.update(key) { item ->
                            item.copy(bhEvent = item.bhEvent.copy(
                                remoteAlert = item.bhEvent.remoteAlert?.copy(isStale = true)
                            ))
                        }
                        alertExpiryJobs.remove(key)
                        publishVisibleBloodhounds()
                        Log.i(TAG, "Emergency stale retained uid=${event.uid} source=$source")
                    }
                }
            }
            Log.i(TAG, "Emergency active uid=${event.uid} source=$source hasLocation=$hasLocation")
            true
        }
    }

    private inline fun updateBloodhound(
        uid: String,
        crossinline transform: (BloodhoundItem) -> BloodhoundItem
    ) {
        synchronized(bloodhoundStateLock) {
            visibleKey(uid)?.let { key -> bloodhoundStore.update(key) { current -> transform(current) } }
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
        val distKm = if (bhEvent.remoteAlert?.hasLocation == false) null else this.vector.distance
        val needle = this.compass.targetBearingDeg ?: 0.0

//        val eventMs = Utils.toMillis(bhEvent.timeIssued) ?: System.currentTimeMillis()
        val eventMs = bhEvent.remoteAlert?.let { bhEvent.mapItem.cotEvent.time } ?: System.currentTimeMillis()

        return Utils.BloodhoundItem(
            target = this.target.name ?: "unknown",
            uid = this.uid,
            callsign = this.originLabel ?: "Unknown sender",
            timeReceived = eventMs,
            distance = distKm,
            orientation = needle.round(1).toFloat(),
            bloodhounding = isActive,
            acknowledged = this.state.acknowledged,
            alertCategory = bhEvent.remoteAlert?.category,
            isRemoteAlert = bhEvent.remoteAlert != null,
            isStale = bhEvent.remoteAlert?.isStale == true,
            hasLocation = bhEvent.remoteAlert?.hasLocation != false
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
                    compareByDescending<BloodhoundItem> { it.bhEvent.remoteAlert != null }
                        .thenByDescending { if (it.bhEvent.remoteAlert != null) it.bhEvent.mapItem.cotEvent.time else Long.MIN_VALUE }
                        .thenByDescending { sourceKey(it.bhEvent) == activeKey }
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
        if (activeKey?.cotUid == e.uid && visibleKey(e.uid) != activeKey) {
            setActiveKey(null)
        }
        result.applied
    }

    suspend fun storeActiveBloodhoundItems(list: List<BloodhoundItem>) {
        // TODO: Convert BloodHoundMapItem to Json string and store json string.
        dataStore.edit { prefs ->
            prefs[ACTIVE_BLOOD_HOUND] = list.toString()
        }
    }
    val getLastActiveBloodhound: Flow<String> = dataStore.data.map { prefs -> prefs[ACTIVE_BLOOD_HOUND] ?: "" }

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
            val selectedKey = visibleKey(uid)
                ?: return@synchronized ActivationResult.NO_CHANGE to null
            val currentItem = bloodhoundStore.item(selectedKey)
                ?: return@synchronized ActivationResult.NO_CHANGE to null
            if (currentItem.bhEvent.remoteAlert?.hasLocation == false) {
                return@synchronized ActivationResult.NO_CHANGE to currentItem
            }
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
            ?: visibleKey(uid)
            ?: return@synchronized null
        val item = bloodhoundStore.item(key) ?: return@synchronized null
        if (item.bhEvent.remoteAlert != null) {
            if (activeKey == key) setActiveKey(null)
            return@synchronized item
        }
        bloodhoundStore.remove(key)
        publishVisibleBloodhounds()
        if (activeKey == key) setActiveKey(null)
        item
    }

    suspend fun removeFromMenu(uid: String): BloodhoundItem? = emergencyMutex.withLock {
        val selected = synchronized(bloodhoundStateLock) {
            visibleKey(uid)?.let { bloodhoundStore.item(it) }
        } ?: return@withLock null
        if (selected.bhEvent.remoteAlert != null) {
            loadEmergencyLedgers()
            persistDismissal(uid, selected.bhEvent.mapItem.cotEvent.time)
        }
        synchronized(bloodhoundStateLock) {
        val key = visibleKey(uid) ?: return@synchronized null
        val item = bloodhoundStore.item(key) ?: return@synchronized null
        // Local dismissal never emits a sender cancellation or removes ordinary targets.
        val removedKeys = if (item.bhEvent.remoteAlert != null) {
            bloodhoundStore.allItems.value.filter { (candidate, candidateItem) ->
                candidate.cotUid == uid && candidateItem.bhEvent.remoteAlert != null
            }.keys
        } else {
            setOf(key)
        }
        removedKeys.forEach { removedKey ->
            bloodhoundStore.remove(removedKey)
            alertExpiryJobs.remove(removedKey)?.cancel()
        }
        publishVisibleBloodhounds()
        if (_activeKey.value in removedKeys) setActiveKey(null)
        item
        }
    }

    private suspend fun loadEmergencyLedgers() {
        if (cancellationsLoaded) return
        val prefs = dataStore.data.first()
        val cancelled = JSONObject(prefs[ALERT_CANCELLATIONS] ?: "{}")
        cancelled.keys().forEach { uid -> cancellationTimes[uid] = cancelled.getLong(uid) }
        val dismissed = JSONObject(prefs[ALERT_DISMISSALS] ?: "{}")
        dismissed.keys().forEach { uid -> dismissalTimes[uid] = dismissed.getLong(uid) }
        cancellationsLoaded = true
    }

    private suspend fun persistDismissal(uid: String, time: Long) {
        persistDismissals(mapOf(uid to time))
    }

    private suspend fun persistDismissals(times: Map<String, Long>) {
        val latest = times.mapValues { (uid, time) ->
            maxOf(dismissalTimes[uid] ?: Long.MIN_VALUE, time)
        }
        dataStore.edit { prefs ->
            val dismissed = JSONObject(prefs[ALERT_DISMISSALS] ?: "{}")
            latest.forEach { (uid, time) -> dismissed.put(uid, time) }
            prefs[ALERT_DISMISSALS] = dismissed.toString()
        }
        dismissalTimes.putAll(latest)
    }

    suspend fun removeAllFromMenu() = emergencyMutex.withLock {
        val selected = synchronized(bloodhoundStateLock) {
            bloodhoundStore.allItems.value.toMap()
        }
        val alerts = selected.values.filter { it.bhEvent.remoteAlert != null }
            .groupBy { it.uid }
            .mapValues { (_, items) -> items.maxOf { it.bhEvent.mapItem.cotEvent.time } }
        if (alerts.isNotEmpty()) {
            loadEmergencyLedgers()
            persistDismissals(alerts)
        }
        synchronized(bloodhoundStateLock) {
            selected.forEach { (key, item) ->
                if (bloodhoundStore.item(key) == item) {
                    bloodhoundStore.remove(key)
                    alertExpiryJobs.remove(key)?.cancel()
                    if (_activeKey.value == key) setActiveKey(null)
                }
            }
            publishVisibleBloodhounds()
        }
        Log.i(TAG, "Bloodhound Remove All removed snapshot entries=${selected.size} dismissedAlerts=${alerts.size}")
    }

    /** Removes every source copy of [uid], e.g. when the matching map item is deleted. */
    fun removeTarget(uid: String): List<BloodhoundItem> = synchronized(bloodhoundStateLock) {
        val removed = bloodhoundStore.removeUid(uid)
        if (removed.isEmpty()) return@synchronized removed
        alertExpiryJobs.keys.filter { it.cotUid == uid }.toList().forEach {
            alertExpiryJobs.remove(it)?.cancel()
        }
        publishVisibleBloodhounds()
        if (_activeKey.value?.cotUid == uid) setActiveKey(null)
        removed
    }

    fun isUidValid(uid: String) = _bloodhoundMap.value.containsKey(uid)

    fun remarkFromHoundmaster(uid: String) =
        _bloodhoundMap.value[uid]?.containsHoundmasterRemark ?: false

    fun updateBloodhoundCompassNeedle(uid: String, needleDeg: Double) {
        synchronized(bloodhoundStateLock) {
            visibleKey(uid)?.let { key -> bloodhoundStore.update(key) { item ->
                if (item.compass.targetBearingDeg == needleDeg) {
                    item
                } else {
                    item.copy(compass = item.compass.copy(targetBearingDeg = needleDeg))
                }
                }
            }
            publishVisibleBloodhounds()
        }
    }

    fun clearSource(serverId: String): List<BloodhoundItem> = synchronized(bloodhoundStateLock) {
        val activeKey = _activeKey.value
        val activeWasRemoved = activeKey?.sourceId in listOf(serverId, "emergency:$serverId")
        val removed = bloodhoundStore.removeSource(serverId) + bloodhoundStore.removeSource("emergency:$serverId")
        alertExpiryJobs.keys.filter { it.sourceId == "emergency:$serverId" }.toList().forEach {
            alertExpiryJobs.remove(it)?.cancel()
        }
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
        val alerts = bloodhoundStore.allItems.value.values.filter { it.bhEvent.remoteAlert != null }
            .groupBy(BloodhoundItem::uid).mapValues { (_, items) ->
                items.maxWith(compareBy<BloodhoundItem> { it.bhEvent.mapItem.cotEvent.time }.thenBy { it.bhEvent.receivedAt })
            }
        _remoteAlerts.value = alerts
        _bloodhoundMap.value = bloodhoundStore.visibleItems.value.associateBy(BloodhoundItem::uid) + alerts
    }

    private fun visibleKey(uid: String): SourceCotKey? = _bloodhoundMap.value[uid]?.let { sourceKey(it.bhEvent) }

    private fun sourceKey(event: BloodhoundEvent): SourceCotKey = SourceCotKey(
        sourceId = if (event.remoteAlert != null) "emergency:${event.sourceServerId}"
            else event.sourceServerId ?: NON_TAK_BLOODHOUND_SOURCE_ID,
        cotUid = event.uid
    )



    val getEnableBloodhoundService: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[ENABLE] ?: false
    }

    suspend fun storeEnableBloodhoundService(enable: Boolean) {
        dataStore.edit { prefs ->
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
            if (!upsertFromEvent(event) || visibleKey(event.uid) != sourceKey(event)) {
                return@synchronized
            }
            _bloodhoundEvent.value = BloodhoundUpdate(
                event = event,
                isNewUid = isNewUid,
                becameHoundmaster = event.remark == "#houndmaster" && !previouslyFromHoundmaster
            )
        }

    }

    suspend fun applyReceivedMarkerEvent(
        event: CotMarkerEvent,
        server: String,
        sourceServerId: String?,
        receivedAt: Long,
        now: Long = System.currentTimeMillis()
    ) {
        if (event.stale <= now) {
            synchronized(bloodhoundStateLock) {
                val key = SourceCotKey(sourceServerId ?: NON_TAK_BLOODHOUND_SOURCE_ID, event.uid)
                val removed = bloodhoundStore.removeIfNotOlder(key, event.time, receivedAt)
                if (removed != null) {
                    if (_activeKey.value == key) setActiveKey(null)
                    if (_bloodhoundEvent.value?.event?.let { sourceKey(it) == key } == true) {
                        _bloodhoundEvent.value = null
                    }
                    publishVisibleBloodhounds()
                }
            }
        } else {
            val mapItem = CotMarkerMapItem(event)
            updateBloodhoundEvent(BloodhoundEvent(
                mapItem = mapItem, server = server, timeIssued = mapItem.link?.productionTime,
                timeCompleted = null, remark = event.detail.remarks.orEmpty(),
                sourceServerId = sourceServerId, receivedAt = receivedAt
            ))
        }
    }


    companion object {
        private val ENABLE = booleanPreferencesKey("enable_bloodhound_service")
        private val ACTIVE_BLOOD_HOUND = stringPreferencesKey("active_bloodhound")
        private val ALERT_CANCELLATIONS = stringPreferencesKey("remote_emergency_cancellations")
        private val ALERT_DISMISSALS = stringPreferencesKey("remote_emergency_dismissals")
    }
}
