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
import androidx.lifecycle.MutableLiveData
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

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Repository for HIVE BLE mesh synchronization state.
 *
 * Manages:
 * - BLE mesh connection state
 * - Discovered and connected peers
 * - Emergency/ACK event tracking
 * - Outbound document queue
 *
 * Settings (mesh ID, power profile, encryption) are stored in DataRepository.
 */
class HiveBtleRepository(private val context: Context) {

    companion object {
        private const val TAG = "WearTAK-HiveBtleRepo"
    }

    // --- Mesh connection state ---------------------------------------------------

    enum class HiveMeshState {
        NOT_INITIALIZED,
        INITIALIZING,
        IDLE,               // Initialized but not scanning/advertising
        SCANNING,           // Actively scanning for peers
        ADVERTISING,        // Advertising our presence
        ACTIVE,             // Both scanning and advertising
        SYNCING,            // Actively syncing with peers
        ERROR
    }

    private val _meshState = MutableStateFlow(HiveMeshState.NOT_INITIALIZED)
    val meshState: StateFlow<HiveMeshState> = _meshState

    // LiveData version for better WearOS deep sleep compatibility
    val meshStateLiveData = MutableLiveData(HiveMeshState.NOT_INITIALIZED)

    val isActive: StateFlow<Boolean> = meshState
        .map { state ->
            state == HiveMeshState.ACTIVE ||
            state == HiveMeshState.SCANNING ||
            state == HiveMeshState.ADVERTISING ||
            state == HiveMeshState.SYNCING
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    fun updateMeshState(state: HiveMeshState) {
        _meshState.value = state
        meshStateLiveData.postValue(state)
    }

    // --- Peer tracking -----------------------------------------------------------

    data class HivePeerLocation(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double
    )

    data class HivePeerInfo(
        val nodeId: Long,
        val identifier: String,     // BLE address (empty for relayed peers)
        val name: String?,
        val callsign: String? = null,
        val rssi: Int,
        val isConnected: Boolean,
        val lastSeenMs: Long,
        val location: HivePeerLocation? = null,
        val battery: Int? = null,
        val heartRate: Int? = null,
        val isInEmergency: Boolean = false,
        val emergencyTimestamp: Long? = null,
        val hopCount: Int = 0       // 0 = direct, 1+ = relayed via mesh
    ) {
        /** Whether this peer is directly connected via BLE (not relayed) */
        val isDirect: Boolean get() = hopCount == 0

        /** Whether this peer is reached via relay (multi-hop) */
        val isRelayed: Boolean get() = hopCount > 0
    }

    private val _peers = MutableStateFlow<List<HivePeerInfo>>(emptyList())
    val peers: StateFlow<List<HivePeerInfo>> = _peers

    // LiveData version for better WearOS deep sleep compatibility
    val peerCountLiveData = MutableLiveData(0)

    val connectedPeers: StateFlow<List<HivePeerInfo>> = _peers
        .map { list -> list.filter { it.isConnected } }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    val peerCount: StateFlow<Int> = _peers
        .map { it.size }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    val connectedCount: StateFlow<Int> = connectedPeers
        .map { it.size }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    val peersInEmergency: StateFlow<List<HivePeerInfo>> = _peers
        .map { list -> list.filter { it.isInEmergency } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val emergencyPeerCount: StateFlow<Int> = peersInEmergency
        .map { it.size }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    private fun updatePeerCountLiveData() {
        peerCountLiveData.postValue(_peers.value.size)
    }

    fun updatePeers(peerList: List<HivePeerInfo>) {
        // Merge new peer info with existing data to preserve callsign/location/battery/emergency
        val existingMap = _peers.value.associateBy { it.nodeId }
        val merged = peerList.map { newPeer ->
            val existing = existingMap[newPeer.nodeId]
            if (existing != null) {
                // Preserve existing callsign, location, battery, heartRate, emergency state if not in new data
                newPeer.copy(
                    callsign = newPeer.callsign ?: existing.callsign,
                    location = newPeer.location ?: existing.location,
                    battery = newPeer.battery ?: existing.battery,
                    heartRate = newPeer.heartRate ?: existing.heartRate,
                    isInEmergency = existing.isInEmergency,
                    emergencyTimestamp = existing.emergencyTimestamp
                )
            } else {
                newPeer
            }
        }
        _peers.value = merged
        updatePeerCountLiveData()
    }

    fun addOrUpdatePeer(peer: HivePeerInfo) {
        val current = _peers.value.toMutableList()
        val index = current.indexOfFirst { it.nodeId == peer.nodeId }
        if (index >= 0) {
            // Preserve emergency state when updating
            val existing = current[index]
            current[index] = peer.copy(
                isInEmergency = existing.isInEmergency,
                emergencyTimestamp = existing.emergencyTimestamp
            )
        } else {
            current.add(peer)
        }
        _peers.value = current
        updatePeerCountLiveData()
    }

    fun removePeer(nodeId: Long) {
        _peers.value = _peers.value.filter { it.nodeId != nodeId }
        updatePeerCountLiveData()
    }

    /**
     * Remove peer by BLE address/identifier.
     * More reliable than nodeId when the peer was re-keyed after document sync.
     */
    fun removePeerByIdentifier(identifier: String) {
        _peers.value = _peers.value.filter { it.identifier != identifier }
        updatePeerCountLiveData()
    }

    fun clearPeers() {
        _peers.value = emptyList()
        updatePeerCountLiveData()
    }

    /**
     * Mark all peers as disconnected without clearing cached data (callsign, location, etc.).
     * Used when service stops to preserve data for the next session.
     */
    fun markAllPeersDisconnected() {
        _peers.value = _peers.value.map { peer ->
            peer.copy(isConnected = false)
        }
        // Don't update peer count - we're keeping the peers, just marking disconnected
    }

    /**
     * Set a peer's emergency state.
     * Called when receiving EMERGENCY event from mesh.
     */
    fun setPeerEmergency(nodeId: Long, isInEmergency: Boolean, timestamp: Long? = null) {
        _peers.value = _peers.value.map { peer ->
            if (peer.nodeId == nodeId) {
                peer.copy(
                    isInEmergency = isInEmergency,
                    emergencyTimestamp = if (isInEmergency) timestamp else null
                )
            } else {
                peer
            }
        }
    }

    /**
     * Clear all peer emergency states.
     */
    fun clearAllPeerEmergencies() {
        _peers.value = _peers.value.map { peer ->
            peer.copy(isInEmergency = false, emergencyTimestamp = null)
        }
    }

    /**
     * Update peer location from received HIVE document.
     * Only updates existing peers - does NOT create new entries.
     * Peer creation happens via onMeshUpdated/updatePeers.
     */
    fun updatePeerLocation(
        nodeId: Long,
        latitude: Double,
        longitude: Double,
        altitude: Double,
        callsign: String? = null,
        battery: Int? = null,
        heartRate: Int? = null
    ) {
        val location = HivePeerLocation(latitude, longitude, altitude)
        val current = _peers.value.toMutableList()
        val index = current.indexOfFirst { it.nodeId == nodeId }

        if (index >= 0) {
            // Update existing peer
            current[index] = current[index].copy(
                location = location,
                callsign = callsign ?: current[index].callsign,
                battery = battery ?: current[index].battery,
                heartRate = heartRate ?: current[index].heartRate,
                lastSeenMs = System.currentTimeMillis()
            )
            _peers.value = current
        }
        // Don't create new peers here - let onMeshUpdated handle peer creation
        // This prevents duplicate entries when document nodeId differs from BLE nodeId
    }

    // --- Emergency/ACK tracking --------------------------------------------------

    data class EmergencyState(
        val isActive: Boolean = false,
        val sourceNodeId: Long? = null,
        val timestamp: Long = 0L,
        val ackedByNodes: Set<Long> = emptySet()
    )

    private val _emergencyState = MutableStateFlow(EmergencyState())
    val emergencyState: StateFlow<EmergencyState> = _emergencyState

    val isEmergencyActive: StateFlow<Boolean> = emergencyState
        .map { it.isActive }
        .stateIn(scope, SharingStarted.Eagerly, false)

    fun updateEmergencyState(state: EmergencyState) {
        _emergencyState.value = state
    }

    fun startEmergency(sourceNodeId: Long, timestamp: Long) {
        _emergencyState.value = EmergencyState(
            isActive = true,
            sourceNodeId = sourceNodeId,
            timestamp = timestamp,
            ackedByNodes = emptySet()
        )
    }

    fun addEmergencyAck(nodeId: Long) {
        val current = _emergencyState.value
        if (current.isActive) {
            _emergencyState.value = current.copy(
                ackedByNodes = current.ackedByNodes + nodeId
            )
        }
    }

    fun clearEmergency() {
        _emergencyState.value = EmergencyState()
    }

    // --- Sync statistics ---------------------------------------------------------

    data class SyncStats(
        val documentsSent: Long = 0,
        val documentsReceived: Long = 0,
        val bytesSent: Long = 0,
        val bytesReceived: Long = 0,
        val lastSyncMs: Long = 0
    )

    private val _syncStats = MutableStateFlow(SyncStats())
    val syncStats: StateFlow<SyncStats> = _syncStats

    fun recordDocumentSent(bytes: Int) {
        val current = _syncStats.value
        _syncStats.value = current.copy(
            documentsSent = current.documentsSent + 1,
            bytesSent = current.bytesSent + bytes,
            lastSyncMs = System.currentTimeMillis()
        )
    }

    fun recordDocumentReceived(bytes: Int) {
        val current = _syncStats.value
        _syncStats.value = current.copy(
            documentsReceived = current.documentsReceived + 1,
            bytesReceived = current.bytesReceived + bytes,
            lastSyncMs = System.currentTimeMillis()
        )
    }

    fun resetStats() {
        _syncStats.value = SyncStats()
    }

    // --- Outbound events (for service to process) --------------------------------

    sealed class HiveOutboundEvent {
        data class Document(val collection: String, val cotXml: String) : HiveOutboundEvent()
        data class Emergency(val timestamp: Long) : HiveOutboundEvent()
        data class Ack(val timestamp: Long) : HiveOutboundEvent()
    }

    private val _outboundEvents = MutableSharedFlow<HiveOutboundEvent>(extraBufferCapacity = 64)
    val outboundEvents: SharedFlow<HiveOutboundEvent> = _outboundEvents

    suspend fun enqueueDocument(collection: String, cotXml: String) {
        _outboundEvents.emit(HiveOutboundEvent.Document(collection, cotXml))
    }

    suspend fun enqueueEmergency(timestamp: Long) {
        _outboundEvents.emit(HiveOutboundEvent.Emergency(timestamp))
    }

    suspend fun enqueueAck(timestamp: Long) {
        _outboundEvents.emit(HiveOutboundEvent.Ack(timestamp))
    }

    // --- Inbound chat messages (for UI overlay) --------------------------------

    data class InboundChat(
        val sender: String,
        val message: String,
        val timestamp: Long,
        val originNodeId: Long
    )

    private val _pendingChat = MutableStateFlow<InboundChat?>(null)
    val pendingChat: StateFlow<InboundChat?> = _pendingChat

    // Last received mesh chat - for ACK replyTo without popup
    private var _lastMeshChat: InboundChat? = null
    val lastMeshChat: InboundChat? get() = _lastMeshChat

    /**
     * Set a pending chat message for UI display.
     * Called by HiveBtleService when a chat is received.
     */
    fun setPendingChat(chat: InboundChat) {
        _pendingChat.value = chat
    }

    /**
     * Record the last mesh chat received (for ACK replyTo).
     * Does NOT trigger popup - just stores for reference.
     */
    fun setLastMeshChat(chat: InboundChat) {
        _lastMeshChat = chat
    }

    /**
     * Clear the pending chat (user dismissed or ACK'd).
     */
    fun clearPendingChat() {
        _pendingChat.value = null
    }
}
