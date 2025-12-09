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
                state == BleConnectionState.CONNECTED ||
                        state == BleConnectionState.SERVICES_READY
            }
            .stateIn(
                scope = scope,              // your CoroutineScope
                started = SharingStarted.Eagerly,
                initialValue = false
            )

    fun updateBleConnectionState(state: BleConnectionState) {
        _bleConnectionState.value = state
    }

    data class Envelope<T>(
        val proto_version: Int = 1,
        val msg_type: String,
        val msg_id: String,
        val timestamp: String,
        val source: String,
        val payload: T
    )

    enum class PayloadType {
        MARKER_DROP,
        MARKER_CLEAR,
        EMERGENCY_ALERT
    }

    private val _markerSnapshotsForBle =
        MutableSharedFlow<DataRepository.MarkerSnapshot>(extraBufferCapacity = 64)
    val markerSnapshotsForBle: SharedFlow<DataRepository.MarkerSnapshot> = _markerSnapshotsForBle

    suspend fun enqueueMarkerSnapshotForBle(snapshot: DataRepository.MarkerSnapshot) {
        _markerSnapshotsForBle.emit(snapshot)
    }

    data class MarkerPayload(
        val marker_id: String,       // == msg_id
        val marker_type: String,     // CoT type
        val icon_path: String?,
        val lat: Double,
        val lon: Double,
        val alt: Double?,
        val ce: Double?,
        val le: Double?,
        val time_start: String,
        val time_stale: String,
        val callsign: String,
        val heart_rate: Int?,
        val battery_percent: Int?
    )

    data class EmergencyAlertPayload(
        val category: String,
        val description: String,
        val uuid: String,
        val cancel: Boolean
    )

    data class WatchInfoPayload(
        val deviceId: String,
        val callsign: String,
        val weartakVersion: String,
        val macAddress: String?,
        val batteryPercent: Int?,
    )

    suspend fun buildWatchInfoPayload(): WatchInfoPayload {
        val sys = repo.snapshotSystem()
        val mac = getLocalBluetoothMacOrNull(context)

        return WatchInfoPayload(
            deviceId       = sys.deviceId,
            callsign       = sys.callsign,
            weartakVersion = sys.wearTakVersion,
            macAddress     = mac,
            batteryPercent = sys.batteryPercent
        )
    }

    suspend fun buildWatchInfoEnvelope(): Envelope<WatchInfoPayload> {
        val nowIso = Utils.timeNowIsoUtc()
        val msgId  = UUID.randomUUID().toString()

        val payload = buildWatchInfoPayload()

        return Envelope(
            proto_version = 1,
            msg_type      = "watch_info",
            msg_id        = msgId,
            timestamp     = nowIso,
            source        = "wearos",
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

        val payload = MarkerPayload(
            marker_id       = uid,
            marker_type     = snapshot.markerType.type,
            icon_path       = snapshot.iconSetPath,
            lat             = snapshot.loc?.lat ?: 0.0,
            lon             = snapshot.loc?.lon ?: 0.0,
            alt             = snapshot.loc?.alt,
            ce              = snapshot.loc?.ce?.toDouble(),
            le              = snapshot.loc?.le?.toDouble(),
            time_start      = snapshot.time.startCot,
            time_stale      = snapshot.time.staleCot,
            callsign        = snapshot.system.callsign,
            heart_rate      = snapshot.physio?.data?.heartRateBpm,
            battery_percent = snapshot.system.batteryPercent
        )

        return Envelope(
            msg_type  = "marker",
            msg_id    = uid,
            timestamp = nowIso,
            source    = source,
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
