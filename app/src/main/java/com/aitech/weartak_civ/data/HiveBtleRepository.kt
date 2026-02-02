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
 * Repository for HIVE BLE mesh display state.
 *
 * This repository ONLY stores and exposes mesh state for UI display.
 * All peer management (tracking, deduplication, cleanup) is handled by hive-btle.
 *
 * The app receives peer updates via onMeshUpdated() and stores them directly.
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

    // --- Peer display (read-only from hive-btle) ---------------------------------

    /**
     * Peer info for display. All fields come directly from hive-btle.
     * The app does NOT manage or modify this data.
     */
    data class HivePeerInfo(
        val nodeId: Long,
        val identifier: String,     // BLE address
        val name: String?,
        val callsign: String?,
        val rssi: Int,
        val isConnected: Boolean,
        val lastSeenMs: Long,
        val latitude: Double?,
        val longitude: Double?,
        val altitude: Double?,
        val battery: Int?,
        val heartRate: Int?,
        val isInEmergency: Boolean,
        val hopCount: Int           // 0 = direct, 1+ = relayed via mesh
    ) {
        /** Whether this peer is directly connected via BLE (not relayed) */
        val isDirect: Boolean get() = hopCount == 0

        /** Whether this peer is reached via relay (multi-hop) */
        val isRelayed: Boolean get() = hopCount > 0

        /** Location if available */
        val hasLocation: Boolean get() = latitude != null && longitude != null
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

    /**
     * Set peers directly from hive-btle. No processing, no deduplication.
     * hive-btle handles all peer management.
     */
    fun setPeers(peerList: List<HivePeerInfo>) {
        _peers.value = peerList
        peerCountLiveData.postValue(peerList.size)
    }

    /**
     * Clear all peers (e.g., when mesh stops).
     */
    fun clearPeers() {
        _peers.value = emptyList()
        peerCountLiveData.postValue(0)
    }

    // --- Emergency state (from hive-btle) ----------------------------------------

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

    fun setEmergencyState(state: EmergencyState) {
        _emergencyState.value = state
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

    fun setPendingChat(chat: InboundChat) {
        _pendingChat.value = chat
    }

    fun setLastMeshChat(chat: InboundChat) {
        _lastMeshChat = chat
    }

    fun clearPendingChat() {
        _pendingChat.value = null
    }

    // --- Inbound CannedMessage (for UI overlay) --------------------------------

    data class InboundCannedMessage(
        val messageCode: Int,
        val label: String,
        val sourceNode: Long,
        val targetNode: Long,
        val timestamp: Long
    )

    private val _pendingCannedMessage = MutableStateFlow<InboundCannedMessage?>(null)
    val pendingCannedMessage: StateFlow<InboundCannedMessage?> = _pendingCannedMessage

    fun setPendingCannedMessage(msg: InboundCannedMessage) {
        _pendingCannedMessage.value = msg
    }

    fun clearPendingCannedMessage() {
        _pendingCannedMessage.value = null
    }
}
