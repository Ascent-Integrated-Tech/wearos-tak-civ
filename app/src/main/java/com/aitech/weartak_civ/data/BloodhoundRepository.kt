package com.aitech.weartak_civ.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.service.TAKConnectService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bloodhound")
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class BloodhoundRepository(private val context: Context) {
    private val TAG = "WearTAK-" + BloodhoundRepository::class.java.simpleName

    data class Vector(
        var distance: Double?,
        var bearing: Double?
    )

    data class BloodHoundItem(
        val bhEvent: BloodhoundEvent
    ) {
        val server = bhEvent.server       // which tak server came from


        val uid = bhEvent.mapItem.uid

        val vector: Vector = Vector(null, null)
        val target: Target = Target(bhEvent.mapItem)
        val state: State = State()              //
        val origin: Origin = Origin(bhEvent)    // who issued this bloodhound from what server?

        data class Target(
            val mapItem: MarkerMapItem
        ) {
            val name: String = mapItem.cotEvent.detail.markerCallsign
            val point: Point = mapItem.point
            val type: String = mapItem.type
        }
        data class State(
            var acknowledged: Boolean = false,  // user acknowledged via RGR button in UI
            var active: Boolean = false,        // user selects the active one in UI
            var inPos: Boolean = false,        // user pressed nPOS button in UI (delete request)
        )

        data class Origin(
            val bhEvent: BloodhoundEvent
        ) {
            val server = bhEvent.server
            val issuerUid = bhEvent.issuer.uid
            val chatRoomUid = issuerUid // TODO: Is this true check ChatService I forgot.
            val chatRoom: Utils.ChatRoom? = null // TODO: get chatroom using uid
        }
    }

    /*
     *  Backend uses this to map devices back
     */
    data class BloodhoundEvent(
        val mapItem: MarkerMapItem,                      // to identify the event calc traj,
        val server: TAKConnectService.TakServerDetail,   // so we can reply via chat to the proper socket  //TODO: Instead of passing the entire details, we can pass a uuid for a tak server detail map...
        val issuer: Link = mapItem.link,                 // so we send the chat to the right person
        val timeIssued: Long,                            // tell user on UI when it was sent.
        val timeCompleted: Long,                         // tell the issuer how long it took.
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
        var uid: String,
        var production_time: Long,
        var type: String,
        var parent_callsign: String,
        var relation: String?
    )

    data class Creator(
        var uid: String,
        var callsign: String,
        var time: String,
        var type: String,
    )

    /*
     * The UI needs a List of all the current Bloodhounds Items that are
     *    selectable for navigation. These Bloodhound Items can be active or
     *    inactive, acknowledged or unacknowledged.
     */
    private val _selectableBloodhounds = MutableStateFlow<List<BloodHoundItem>?>(null)
    val selectableBloodhounds: StateFlow<List<BloodHoundItem>?> = _selectableBloodhounds

    val getLastActiveBloodhound: Flow<String> = context.dataStore.data.map { prefs -> prefs[ACTIVE_BLOOD_HOUND] ?: "" }

    suspend fun storeActiveBloodhoundItem(active: BloodHoundItem) {
        // TODO: Convert BloodHoundMapItem to Json string and store json string.
        context.dataStore.edit { prefs ->
            prefs[ACTIVE_BLOOD_HOUND] = active.toString()
        }
    }

    private val _activeBloodhound =  MutableStateFlow<BloodHoundItem>(
        value = TODO()
    )

    val activeBloodhound: StateFlow<BloodHoundItem> = _activeBloodhound

    private val _pendingBloodHounds = MutableStateFlow<List<BloodHoundItem>?>(null)
    val pendingBloodhounds: StateFlow<List<BloodHoundItem>?> = _pendingBloodHounds

    val getEnableBloodhoundService: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ENABLE] ?: false
    }

    suspend fun storeEnableBloodhoundService(enable: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ENABLE] = enable
        }
    }

    companion object {
        private val ENABLE = booleanPreferencesKey("enable_bloodhound_service")
        private val ACTIVE_BLOOD_HOUND = stringPreferencesKey("active_bloodhound")
    }
}