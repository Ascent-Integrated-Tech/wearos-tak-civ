/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
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
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.edgeScoutDataStore: DataStore<Preferences> by preferencesDataStore(name = "edge_scout")

enum class EdgeScoutConnectionState {
    IDLE,
    SCANNING,
    BONDING,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR,
}

enum class EdgeScoutDiscoveryTransport {
    CLASSIC,
    BLE,
    CLASSIC_AND_BLE,
}

data class EdgeScoutStoredDevice(
    val address: String,
    val name: String?,
)

data class EdgeScoutDeviceCandidate(
    val address: String,
    val name: String?,
    val bondState: Int,
    val isLikelyMatch: Boolean,
    val isSelected: Boolean,
    val lastSeenAtMs: Long,
    val transport: EdgeScoutDiscoveryTransport = EdgeScoutDiscoveryTransport.CLASSIC,
    val supportsSerialConnection: Boolean = false,
    val rssi: Int? = null,
    val detail: String? = null,
)

data class EdgeScoutRidObservation(
    val uasId: String?,
    val rssi: Int? = null,
    val recvMethod: Int? = null,
    val ch: Int? = null,
    val remoteId: String? = null,
    val serialNumber: String? = null,
    val caaRegId: String? = null,
    val utmId: String? = null,
    val sessionId: String? = null,
    val uasType: Int? = null,
    val uasLat: Double? = null,
    val uasLon: Double? = null,
    val uasHae: Double? = null,
    val uasHag: Double? = null,
    val uasHat: Double? = null,
    val uasHSpeed: Double? = null,
    val uasVSpeed: Double? = null,
    val uasHeading: Double? = null,
    val uasHorizontalError: Double? = null,
    val uasVerticalError: Double? = null,
    val uasBaroPressure: Double? = null,
    val uasBaroPressureAcc: Double? = null,
    val uasHSpeedError: Double? = null,
    val uasVSpeedError: Double? = null,
    val opStatus: Int? = null,
    val opLat: Double? = null,
    val opLon: Double? = null,
    val opHae: Double? = null,
    val opId: String? = null,
    val description: String? = null,
    @SerializedName("_logged_at") val loggedAtSeconds: Double? = null,
)

data class EdgeScoutScannerInfo(
    val manufacturer: String? = null,
    val make: String? = null,
    val version: String? = null,
    val serialNumber: String? = null,
    val deviceName: String? = null,
    val capabilities: Int? = null,
    val batteryLevel: Double? = null,
    val lastSeenAtMs: Long,
)

data class EdgeScoutDroneSnapshot(
    val uasId: String,
    val firstSeenAtMs: Long,
    val lastSeenAtMs: Long,
    val rssi: Int? = null,
    val recvMethod: Int? = null,
    val ch: Int? = null,
    val remoteId: String? = null,
    val serialNumber: String? = null,
    val caaRegId: String? = null,
    val utmId: String? = null,
    val sessionId: String? = null,
    val uasType: Int? = null,
    val uasLat: Double? = null,
    val uasLon: Double? = null,
    val uasHae: Double? = null,
    val uasHag: Double? = null,
    val uasHat: Double? = null,
    val uasHSpeed: Double? = null,
    val uasVSpeed: Double? = null,
    val uasHeading: Double? = null,
    val uasHorizontalError: Double? = null,
    val uasVerticalError: Double? = null,
    val uasBaroPressure: Double? = null,
    val uasBaroPressureAcc: Double? = null,
    val uasHSpeedError: Double? = null,
    val uasVSpeedError: Double? = null,
    val opStatus: Int? = null,
    val opLat: Double? = null,
    val opLon: Double? = null,
    val opHae: Double? = null,
    val opId: String? = null,
    val description: String? = null,
    val lastRawJson: String? = null,
) {
    fun displayName(): String = remoteId ?: serialNumber ?: uasId
}

data class EdgeScoutMergeResult(
    val snapshot: EdgeScoutDroneSnapshot,
    val isFirstObservation: Boolean,
)

class EdgeScoutRepository(private val context: Context) {

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mergeLock = Any()

    private val _connectionState = MutableStateFlow(EdgeScoutConnectionState.IDLE)
    val connectionState: StateFlow<EdgeScoutConnectionState> = _connectionState

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage

    private val _discoveredDevices = MutableStateFlow<List<EdgeScoutDeviceCandidate>>(emptyList())
    val discoveredDevices: StateFlow<List<EdgeScoutDeviceCandidate>> = _discoveredDevices

    private val _recentDrones = MutableStateFlow<List<EdgeScoutDroneSnapshot>>(emptyList())
    val recentDrones: StateFlow<List<EdgeScoutDroneSnapshot>> = _recentDrones

    private val _scannerInfo = MutableStateFlow<EdgeScoutScannerInfo?>(null)
    val scannerInfo: StateFlow<EdgeScoutScannerInfo?> = _scannerInfo

    private val _lastMessageAtMs = MutableStateFlow<Long?>(null)
    val lastMessageAtMs: StateFlow<Long?> = _lastMessageAtMs

    private val _messageCount = MutableStateFlow(0L)
    val messageCount: StateFlow<Long> = _messageCount

    private val _activeLogFileName = MutableStateFlow<String?>(null)
    val activeLogFileName: StateFlow<String?> = _activeLogFileName

    private val selectedAddressFlow = context.edgeScoutDataStore.data.map { prefs ->
        prefs[KEY_SELECTED_ADDRESS]
    }

    private val selectedNameFlow = context.edgeScoutDataStore.data.map { prefs ->
        prefs[KEY_SELECTED_NAME]
    }

    val selectedDevice: StateFlow<EdgeScoutStoredDevice?> = combine(
        selectedAddressFlow,
        selectedNameFlow,
    ) { address, name ->
        address?.let { EdgeScoutStoredDevice(address = it, name = name) }
    }.stateIn(
        scope = repoScope,
        started = SharingStarted.Eagerly,
        initialValue = null,
    )

    suspend fun storeSelectedDevice(address: String, name: String?) {
        context.edgeScoutDataStore.edit { prefs ->
            prefs[KEY_SELECTED_ADDRESS] = address
            if (name.isNullOrBlank()) {
                prefs.remove(KEY_SELECTED_NAME)
            } else {
                prefs[KEY_SELECTED_NAME] = name
            }
        }
        _discoveredDevices.value = _discoveredDevices.value
            .map { it.copy(isSelected = it.address == address) }
            .sortedCandidates()
    }

    suspend fun clearSelectedDevice() {
        context.edgeScoutDataStore.edit { prefs ->
            prefs.remove(KEY_SELECTED_ADDRESS)
            prefs.remove(KEY_SELECTED_NAME)
        }
        _discoveredDevices.value = _discoveredDevices.value
            .map { it.copy(isSelected = false) }
            .sortedCandidates()
    }

    fun replaceDiscoveredDevices(devices: List<EdgeScoutDeviceCandidate>) {
        _discoveredDevices.value = devices.sortedCandidates()
    }

    fun upsertDiscoveredDevice(candidate: EdgeScoutDeviceCandidate) {
        val updated = _discoveredDevices.value
            .filterNot { it.address == candidate.address } + candidate
        _discoveredDevices.value = updated.sortedCandidates()
    }

    fun clearDiscoveredDevices() {
        _discoveredDevices.value = emptyList()
    }

    fun updateConnectionState(state: EdgeScoutConnectionState, message: String? = null) {
        _connectionState.value = state
        _statusMessage.value = message
    }

    fun setActiveLogFileName(fileName: String?) {
        _activeLogFileName.value = fileName
    }

    fun recordIngress(seenAtMs: Long) {
        _lastMessageAtMs.value = seenAtMs
        _messageCount.value = _messageCount.value + 1L
    }

    fun updateScannerInfo(info: EdgeScoutScannerInfo) {
        val current = _scannerInfo.value
        _scannerInfo.value = EdgeScoutScannerInfo(
            manufacturer = info.manufacturer ?: current?.manufacturer,
            make = info.make ?: current?.make,
            version = info.version ?: current?.version,
            serialNumber = info.serialNumber ?: current?.serialNumber,
            deviceName = info.deviceName ?: current?.deviceName,
            capabilities = info.capabilities ?: current?.capabilities,
            batteryLevel = info.batteryLevel ?: current?.batteryLevel,
            lastSeenAtMs = info.lastSeenAtMs,
        )
    }

    fun recordObservation(
        observation: EdgeScoutRidObservation,
        rawJson: String,
        seenAtMs: Long,
    ): EdgeScoutMergeResult = synchronized(mergeLock) {
        val existing = _recentDrones.value.firstOrNull { it.uasId == observation.uasId }
        val snapshot = mergeEdgeScoutObservation(existing, observation, rawJson, seenAtMs)
        val updated = _recentDrones.value
            .filterNot { it.uasId == observation.uasId } + snapshot

        _recentDrones.value = updated.sortedByDescending { it.lastSeenAtMs }

        EdgeScoutMergeResult(
            snapshot = snapshot,
            isFirstObservation = existing == null,
        )
    }

    companion object {
        private val KEY_SELECTED_ADDRESS = stringPreferencesKey("selected_device_address")
        private val KEY_SELECTED_NAME = stringPreferencesKey("selected_device_name")
    }
}

private fun List<EdgeScoutDeviceCandidate>.sortedCandidates(): List<EdgeScoutDeviceCandidate> =
    sortedWith(
        compareByDescending<EdgeScoutDeviceCandidate> { it.isSelected }
            .thenByDescending { it.supportsSerialConnection }
            .thenByDescending { it.isLikelyMatch }
            .thenByDescending { it.bondState }
            .thenByDescending { it.lastSeenAtMs }
            .thenBy { it.name ?: "" }
            .thenBy { it.address }
    )

fun looksLikeEdgeScoutName(name: String?): Boolean {
    val normalizedName = name?.trim()?.lowercase().orEmpty()
    return normalizedName.contains("edge") ||
        normalizedName.contains("scout") ||
        normalizedName.contains("esp32")
}

fun mergeEdgeScoutObservation(
    existing: EdgeScoutDroneSnapshot?,
    observation: EdgeScoutRidObservation,
    rawJson: String,
    seenAtMs: Long,
): EdgeScoutDroneSnapshot {
    val uasId = requireNotNull(observation.uasId)
    val current = existing ?: EdgeScoutDroneSnapshot(
        uasId = uasId,
        firstSeenAtMs = seenAtMs,
        lastSeenAtMs = seenAtMs,
    )

    return current.copy(
        lastSeenAtMs = seenAtMs,
        rssi = observation.rssi ?: current.rssi,
        recvMethod = observation.recvMethod ?: current.recvMethod,
        ch = observation.ch ?: current.ch,
        remoteId = observation.remoteId ?: current.remoteId,
        serialNumber = observation.serialNumber ?: current.serialNumber,
        caaRegId = observation.caaRegId ?: current.caaRegId,
        utmId = observation.utmId ?: current.utmId,
        sessionId = observation.sessionId ?: current.sessionId,
        uasType = observation.uasType ?: current.uasType,
        uasLat = observation.uasLat ?: current.uasLat,
        uasLon = observation.uasLon ?: current.uasLon,
        uasHae = observation.uasHae ?: current.uasHae,
        uasHag = observation.uasHag ?: current.uasHag,
        uasHat = observation.uasHat ?: current.uasHat,
        uasHSpeed = observation.uasHSpeed ?: current.uasHSpeed,
        uasVSpeed = observation.uasVSpeed ?: current.uasVSpeed,
        uasHeading = observation.uasHeading ?: current.uasHeading,
        uasHorizontalError = observation.uasHorizontalError ?: current.uasHorizontalError,
        uasVerticalError = observation.uasVerticalError ?: current.uasVerticalError,
        uasBaroPressure = observation.uasBaroPressure ?: current.uasBaroPressure,
        uasBaroPressureAcc = observation.uasBaroPressureAcc ?: current.uasBaroPressureAcc,
        uasHSpeedError = observation.uasHSpeedError ?: current.uasHSpeedError,
        uasVSpeedError = observation.uasVSpeedError ?: current.uasVSpeedError,
        opStatus = observation.opStatus ?: current.opStatus,
        opLat = observation.opLat ?: current.opLat,
        opLon = observation.opLon ?: current.opLon,
        opHae = observation.opHae ?: current.opHae,
        opId = observation.opId ?: current.opId,
        description = observation.description ?: current.description,
        lastRawJson = rawJson,
    )
}
