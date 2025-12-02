/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.data

import android.annotation.SuppressLint
import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.preferencesDataStore
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.WearTakApplication
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.UUID
import kotlin.math.roundToInt

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "companion_app")
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class CompanionAppRepository(private val context: Context) {
    private val TAG = "WearTAK-CompanAppRepo"

    private val repo by lazy {
        try {
            (context as WearTakApplication).repo
        } catch (_: Throwable) {
            DataRepository(context)
        }
    }

    // --- BLE UUID constants --------------------------------------------------

    val COMPANION_SERVICE_UUID   = java.util.UUID.fromString("0000A11A-0000-1000-8000-00805F9B34FB")
    val TX_TO_COMPANION_UUID     = java.util.UUID.fromString("0000A11B-0000-1000-8000-00805F9B34FB")
    val RX_FROM_COMPANION_UUID   = java.util.UUID.fromString("0000A11C-0000-1000-8000-00805F9B34FB")

    val CLIENT_CHARACTERISTIC_CONFIG_UUID =
        java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // --- BLE connection state -----------------------------------------------

    enum class BleConnectionState {
        NOT_CONNECTED,
        ADVERTISING,
        CONNECTING,
        CONNECTED,
        SERVICES_READY,
        DISCONNECTING,
        DISCONNECTED,
        CONNECTION_FAILED,
        ERROR
    }

    private val _bleConnectionState =
        MutableStateFlow(BleConnectionState.NOT_CONNECTED)
    val bleConnectionState: StateFlow<BleConnectionState> = _bleConnectionState

    val companionAppReady: StateFlow<Boolean> =
        bleConnectionState
            .map { state ->
                    state == BleConnectionState.SERVICES_READY
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = false
            )

    fun updateBleConnectionState(state: BleConnectionState) {
        _bleConnectionState.value = state
    }

    data class Envelope<T>(
        val vers: Int = 2,      // Companion app version control
        val msgType: String,    // Pli, Chat, Marker, Emergency, ReportInt
        val msgUid: String,     // Inherits the UID of the msgType
        val t: String,          // timestamp millis epoch string
        val src: String,        // source (default to wearos)
        val payload: T
    )

    sealed class BleOutboundEvent {
        data class Marker(val snapshot: DataRepository.MarkerSnapshot) : BleOutboundEvent()
        data class Emergency(val snapshot: DataRepository.EmergencyEventSnapshot) : BleOutboundEvent()
        data class Chat(val snapshot: DataRepository.ChatEventSnapshot) : BleOutboundEvent()
        data class Pli(val snapshot: DataRepository.PliSnapshot) : BleOutboundEvent()
        data class ReportInt(val interval: Int) : BleOutboundEvent()
    }

    private val _outboundBleEvents =
        MutableSharedFlow<BleOutboundEvent>(extraBufferCapacity = 64)
    val outboundBleEvents: SharedFlow<BleOutboundEvent> = _outboundBleEvents

    suspend fun enqueueMarkerSnapshotForBle(snapshot: DataRepository.MarkerSnapshot) {
        _outboundBleEvents.emit(BleOutboundEvent.Marker(snapshot))
    }

    suspend fun enqueueEmergencySnapshotForBle(snapshot: DataRepository.EmergencyEventSnapshot) {
        _outboundBleEvents.emit(BleOutboundEvent.Emergency(snapshot))
    }

    suspend fun enqueueChatSnapshotForBle(snapshot: DataRepository.ChatEventSnapshot) {
        _outboundBleEvents.emit(BleOutboundEvent.Chat(snapshot))
    }

    suspend fun enqueuePliSnapshotForBle(snapshot: DataRepository.PliSnapshot) {
        _outboundBleEvents.emit(BleOutboundEvent.Pli(snapshot))
    }

    suspend fun enqueueReportIntForBle(interval: Int) {
        _outboundBleEvents.emit(BleOutboundEvent.ReportInt(interval))
    }

    fun buildPliEnvelopeFromSnapshot(
        snapshot: DataRepository.PliSnapshot,
        repIntSeconds: Int
    ): Envelope<PliPayload> {

        val loc = snapshot.loc ?: DataRepository.LocationSnapshot(
            timeMillis = System.currentTimeMillis(),
            lat = 0.0,
            lon = 0.0,
            hae = 0.0,
            ce = 0F,
            le = 0F,
            course = 0F,
            speed = 0F
        )

        val timeSnap = snapshot.time
        val sys      = snapshot.systemData
        val phys     = snapshot.physioData
        val dm       = phys.data.dailyMetrics

        val payload = PliPayload(
            lat = loc.lat,
            lon = loc.lon,
            hae = loc.hae,
            ce  = loc.ce?.toDouble(),
            le  = loc.le?.toDouble(),

            tStart = timeSnap.startCot,
            tStale = timeSnap.staleCot,

            cs        = sys.callsign,
            hr      = phys.data.heartRateBpm,
            skt             = phys.data.skinTempF?.toInt() ?: 0,
            bat = sys.batteryPercent,

            int = repIntSeconds,
            age    = phys.data.ageYears ?: 0,
            exr    = ((phys.data.exertion ?: 0.0) * 100.0).toInt(), // 0..100

            stps = dm?.steps?.toInt() ?: 0,
            cals = dm?.calories?.roundToInt() ?: 0,
            flrs = dm?.floors?.roundToInt() ?: 0,
            elev = dm?.elevationM?.roundToInt() ?: 0
        )

        val nowIso = Utils.timeNowIsoUtc()

        return Envelope(
            vers = 1,
            msgType      = "pli",
            msgUid        = sys.deviceId,   // stable per-device id; change if you want per-update unique ids
            t           = nowIso,
            src        = "wearos",
            payload       = payload
        )
    }

    fun buildChatEnvelopeFromSnapshot(
        snapshot: DataRepository.ChatEventSnapshot
    ): Envelope<ChatPayload> {

        val timeSnap = snapshot.time
        val sys      = snapshot.system
        val phys     = snapshot.physio

        // For now, cap BLE message length to avoid MTU issues until chunking exists.
        val maxMsgLenForBle = 200
        val bleMsg = if (snapshot.message.length > maxMsgLenForBle) {
            snapshot.message.take(maxMsgLenForBle)
        } else {
            snapshot.message
        }

        val payload = ChatPayload(
            uid        = snapshot.uid,
            rmUid    = snapshot.roomUid,
            rmTitle  = snapshot.roomTitle,
            msg        = bleMsg,
            tStart     = timeSnap.startCot,
            tStale     = timeSnap.staleCot,
            cs         = sys.callsign,
            hr         = phys?.data?.heartRateBpm,
            bat        = sys.batteryPercent
        )

        val nowIso = Utils.timeNowIsoUtc()

        return Envelope(
            vers = 1,
            msgType      = "chat",
            msgUid        = snapshot.uid,
            t     = nowIso,
            src        = "wearos",
            payload       = payload
        )
    }

//    private val _markerSnapshotsForBle =
//        MutableSharedFlow<DataRepository.MarkerSnapshot>(extraBufferCapacity = 64)
//    val markerSnapshotsForBle: SharedFlow<DataRepository.MarkerSnapshot> = _markerSnapshotsForBle

    // In CompanionAppRepository

    // TODO: Make certain fields nullable like exertion, skt, hr if device not worn!!!
    data class PliPayload(
        val lat: Double,        // latitude  (WGS84)
        val lon: Double,        // longitude (WGS84)
        val hae: Double?,       // height above elevation (meters)
        val ce: Double?,        // circular error (horizontal accuracy)
        val le: Double?,        // linear error   (vertical accuracy)
        val tStart: String,     // start time (UTC zulu)
        val tStale: String,     // stale time (UTC zulu)
        val cs: String,         // callsign
        val hr: Int?,           // heart rate (bpm)
        val skt: Int,           // skin temp (degree F)
        val bat: Int?,          // battery (0-100%)
        val int: Int,           // reporting interval (seconds)
        val age: Int,           // user age (years)
        val exr: Int,           // exertion (0-100%)
        val stps: Int,          // steps     (cumulative daily taken)
        val cals: Int,          // calories  (cumulative daily burned)
        val flrs: Int,          // floors    (cumulative daily climbed)
        val elev: Int,          // elevation (cumulative daily climbed meters)
    )

    data class ChatPayload(
        val uid: String,        // chat message id
        val rmUid: String,      // chat room uid
        val rmTitle: String,    // chat room title
        val msg: String,        // truncated for BLE
        val tStart: String,     // start time zulu
        val tStale: String,     // start time zulu
        val cs: String,         // callsign
        val hr: Int?,           // heart rate
        val bat: Int?           // battery
    )

    data class MarkerPayload(
        val uid: String,        // == msg_id
        val type: String,       // Marker type (a-f-G, a-u-G, etc).
        val icn: String?,       // Icon Path in cot details for itak
        val lat: Double,
        val lon: Double,
        val hae: Double?,
        val ce: Double?,
        val le: Double?,
        val tStart: String,
        val tStale: String,
        val cs: String,
        val hr: String,
        val bat: Int?
    )

    data class EmergencyAlertPayload(
        val uid: String,      // the unique id for an alert generated on weartak
        val state: String,    // is this an alert or cancellation of an alert
        val catg: String,     // what kind of an alert is it
        val desc: String,     // details on the alert

        // App crashed because payload was > 512 Bytes, Android GATT MTU.
//        val lat: Double,
//        val lon: Double,
//        val alt: Double?,
//        val ce: Double?,
//        val le: Double?,

        val tStart: String,
        val tStale: String,

        val cs: String,
        val hr: Int?,
        val bat: Int?
    )

    fun buildEmergencyEnvelopeFromSnapshot(
        snapshot: DataRepository.EmergencyEventSnapshot
    ): Envelope<EmergencyAlertPayload> {

        val timeSnap = snapshot.time
        val sys      = snapshot.system
        val phys     = snapshot.physio

        val payload = EmergencyAlertPayload(
            uid     = snapshot.uid,
            state   = snapshot.alertState.name,   // "ALERT" or "CANCEL"
            catg    = snapshot.category,
            desc    = snapshot.description,

            tStart  = timeSnap.startCot,
            tStale  = timeSnap.staleCot,

            cs      = sys.callsign,
            hr      = phys?.data?.heartRateBpm,
            bat     = sys.batteryPercent
        )

        val nowIso = Utils.timeNowIsoUtc()

        return Envelope(
            vers = 1,
            msgType      = "emergency",
            msgUid        = snapshot.uid,   // same UID is used for alert + cancel
            t     = nowIso,
            src        = "wearos",
            payload       = payload
        )
    }

    data class WatchInfoPayload(
        val uid: String,    // Android Device UUID
        val cs: String,     // callsign
        val wtVrs: String,  // WearTAK Version
        val mac: String?,   // Mac address Bluetooth
        val bat: Int?,
        val int: Int        // reporting interval
    )

    data class ReportingIntPayload(
        val int: Int
    )

    suspend fun buildReportIntPayload(interval: Int): Envelope<ReportingIntPayload> {
        val payload = ReportingIntPayload(
            int = interval
        )
        val nowIso = Utils.timeNowIsoUtc()
        return Envelope(
            vers = 1,
            msgType      = "repInt",
            msgUid        = "",   // same UID is used for alert + cancel
            t     = nowIso,
            src        = "wearos",
            payload       = payload
        )
    }

    suspend fun buildWatchInfoPayload(): WatchInfoPayload {
        val sys = repo.snapshotSystem()
        val mac = getLocalBluetoothMacOrNull(context)

        return WatchInfoPayload(
            uid       = sys.deviceId,
            cs       = sys.callsign,
            wtVrs = sys.wearTakVersion,
            mac     = mac,
            bat = sys.batteryPercent,
            int  = sys.reportIntSecs
        )
    }

    data class EnvelopeBase(val msg_type: String)

    suspend fun buildWatchInfoEnvelope(): Envelope<WatchInfoPayload> {
        val nowIso = Utils.timeNowIsoUtc()
        val msgId  = UUID.randomUUID().toString()

        val payload = buildWatchInfoPayload()

        return Envelope(
            vers = 1,
            msgType      = "watch_info",
            msgUid        = msgId,
            t     = nowIso,
            src        = "wearos",
            payload       = payload
        )
    }

    /**
     * Example builder for a marker envelope based on your MarkerSnapshot.
     * Adapt field names to your actual MarkerSnapshot structure.
     */
    suspend fun buildMarkerEnvelopeFromSnapshot(
        snapshot: DataRepository.MarkerSnapshot
    ): Envelope<MarkerPayload> {
        val uid = snapshot.uid                              // WearTAK-generated UID
        val nowIso = System.currentTimeMillis().toString()  // or Utils.timeNowIsoUtc()
        val source = "wearos"                               // eventually can replace with android device uuid

        var hr = (snapshot.physio?.data?.heartRateBpm)
        var hrStr = "\"N/A\""
        if (hr != null) {
            hrStr = hr.toString()
        }

        val payload = MarkerPayload(
            uid       = uid,
            type     = snapshot.markerType.type,
            icn       = snapshot.iconSetPath,
            lat             = snapshot.loc?.lat ?: 0.0,
            lon             = snapshot.loc?.lon ?: 0.0,
            hae             = snapshot.loc?.hae,
            ce              = snapshot.loc?.ce?.toDouble(),
            le              = snapshot.loc?.le?.toDouble(),
            tStart      = snapshot.time.startCot,
            tStale      = snapshot.time.staleCot,
            cs        = snapshot.system.callsign,
            hr      = hrStr,
            bat = snapshot.system.batteryPercent
        )

        return Envelope(
            msgType  = "marker",
            msgUid    = uid,
            t = nowIso,
            src    = source,
            payload   = payload
        )
    }

    private val gson = Gson()

    // --- JSON helper ------------------------------------------------------------
    fun <T> envelopeToJson(envelope: Envelope<T>): String {
        return gson.toJson(envelope)
    }

    @SuppressLint("HardwareIds")
    private fun getLocalBluetoothMacOrNull(ctx: Context): String? {
        // [Unverified] On modern Android/WearOS this will often return a
        // constant like "02:00:00:00:00:00" or null. Treat as best-effort / debug-only.
        return try {
            val manager  = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            val adapter  = manager?.adapter ?: android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            adapter?.address
        } catch (e: SecurityException) {
            // No BLUETOOTH permission or restricted; just return null.
            null
        } catch (t: Throwable) {
            null
        }
    }

    companion object {

    }
}
