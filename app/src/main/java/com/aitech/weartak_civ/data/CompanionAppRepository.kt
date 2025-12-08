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
import android.location.Location
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.preferencesDataStore
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.data.DataRepository.DropRequest
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "companion_app")
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class CompanionAppRepository(private val context: Context) {
    private val TAG = "WearTAK-CompanAppRepo"

    val COMPANION_SERVICE_UUID   = java.util.UUID.fromString("0000A11A-0000-1000-8000-00805F9B34FB")
    val TX_TO_COMPANION_UUID     = java.util.UUID.fromString("0000A11B-0000-1000-8000-00805F9B34FB")
    val RX_FROM_COMPANION_UUID   = java.util.UUID.fromString("0000A11C-0000-1000-8000-00805F9B34FB")

    val CLIENT_CHARACTERISTIC_CONFIG_UUID =
        java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

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

    suspend fun sendCot(snapshot: DataRepository.MarkerSnapshot) {

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

    enum class PayloadType {
        MARKER_DROP,
        MARKER_CLEAR,
        EMERGENCY_ALERT
    }

    data class EmergencyAlert(
        val category: String,
        val description: String,
        val uuid: String,
        val cancel: Boolean
    )

    data class MarkerCotEventCompApp(
        val uid: String,
        val type: String,
        val time: String,
        val start: String,
        val stale: String,
        val detail: String,
    )

    data class WatchInfoPayload(
        val device_id: String,
        val callsign: String,
        val weartak_version: String,
        val os_version: String,
        val mac_address: String?,
        val battery_percent: Int?,
        val is_charging: Boolean?
    )

    data class MarkerPayload(
        val marker_id: String,
        val marker_type: String,
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

    data class Envelope<T>(
        val proto_version: Int = 1,
        val msg_type: String,
        val msg_id: String,
        val timestamp: String,
        val source: String,
        val payload: T
    )

    class CompanionAppRepository(
        private val dataRepository: DataRepository
    ) {
        suspend fun buildWatchInfoEnvelope(): Envelope<WatchInfoPayload> {
            val nowIso = Utils.timeNowIsoUtc()
            val msgId = UUID.randomUUID().toString()

            val payload = WatchInfoPayload(
                device_id = MainActivity.device_uid,
                callsign = dataRepository.getCallsign.first(),
                weartak_version = MainActivity.versionNumber,
                os_version = "",
                mac_address = "",
                battery_percent = dataRepository.latestBatteryPercentage.value,
                is_charging = dataRepository.chargingState.value
            )

            return Envelope(
                msg_type = "watch_info",
                msg_id = msgId,
                timestamp = nowIso,
                source = "wearos",
                payload = payload
            )
        }

        suspend fun buildMarkerEnvelope(req: DropRequest, loc: Location): Envelope<MarkerPayload> {
            val uid = UUID.randomUUID().toString()
            val nowIso = Utils.timeNowIsoUtc()
            val staleIso = Utils.oneYearFromNowIsoUtc()

            val payload = MarkerPayload(
                marker_id = uid,
                marker_type = req.markerType.type,
                icon_path = req.markerType.icon,
                lat = loc.latitude,
                lon = loc.longitude,
                alt = loc.altitude,
                ce = loc.accuracy.toDouble(),
                le = loc.verticalAccuracyMeters.toDouble(),
                time_start = nowIso,
                time_stale = staleIso,
                callsign = dataRepository.getCallsign.first(),
                heart_rate = dataRepository.latestHeartRate.value,
                battery_percent = dataRepository.latestBatteryPercentage.value
            )

            return Envelope(
                msg_type = "marker",
                msg_id = uid,
                timestamp = nowIso,
                source = "wearos",
                payload = payload
            )
        }
    }

    companion object {

    }
}
