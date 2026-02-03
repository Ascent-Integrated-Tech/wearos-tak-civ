/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

/*
 * HiveBtleService handles HIVE BLE mesh sync lifecycle using the hive-btle library.
 * This is a power-efficient replacement for DittoService, optimized for WearOS.
 */

package com.aitech.weartak_civ.service

import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.HiveBtleRepository
import com.aitech.weartak_civ.events.HiveCannedMessageEvent
import com.aitech.weartak_civ.events.HiveDocumentEvent
import com.aitech.weartak_civ.events.TakChatEvent
import com.aitech.weartak_civ.hive.CotHiveTranslator
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import android.util.Base64
import uniffi.hive_lite_android.CannedMessageAckEventData
import uniffi.hive_lite_android.CannedMessageEventData
import uniffi.hive_lite_android.CannedMessageType
import uniffi.hive_lite_android.cannedMessageAckEventAddAck
import uniffi.hive_lite_android.cannedMessageAckEventAckCount
import uniffi.hive_lite_android.cannedMessageAckEventHasAcked
import uniffi.hive_lite_android.cannedMessageAckEventMerge
import uniffi.hive_lite_android.cannedMessageMarker
import uniffi.hive_lite_android.cannedMessageShortName
import uniffi.hive_lite_android.createCannedMessageAckEvent
import uniffi.hive_lite_android.createCannedMessageEvent
import uniffi.hive_lite_android.decodeCannedMessageAckEvent
import uniffi.hive_lite_android.decodeCannedMessageEvent
import uniffi.hive_lite_android.encodeCannedMessageAckEvent
import uniffi.hive_lite_android.encodeCannedMessageEvent
import com.revolveteam.hive.HiveBtle
import com.revolveteam.hive.HiveChat
import com.revolveteam.hive.HiveDocument
import com.revolveteam.hive.HiveEventType
import com.revolveteam.hive.HiveLocation
import com.revolveteam.hive.HiveMarker
import com.revolveteam.hive.HiveMeshListener
import com.revolveteam.hive.HivePeer
import uniffi.hive_btle.CannedMessageInfo
import uniffi.hive_btle.DeviceIdentity
import uniffi.hive_btle.MeshGenesis
import uniffi.hive_btle.restoreDeviceIdentity
import uniffi.hive_btle.decodeMeshGenesis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HIVE BLE Mesh Sync Service.
 *
 * Power-efficient replacement for DittoService using the hive-btle library.
 * Targets <5% radio duty cycle for 18+ hour battery life on WearOS devices.
 *
 * Uses HiveBtle for BLE operations (scanning, advertising, GATT) and implements
 * HiveMeshListener to receive mesh events (peer updates, emergencies, document sync).
 */
class HiveBtleService : Service(), HiveMeshListener {

    private lateinit var dataRepository: DataRepository
    private lateinit var hiveBtleRepository: HiveBtleRepository
    private var hiveBtle: HiveBtle? = null
    private var meshId: String = "HIVE"  // Default, updated on init

    private var syncUpdateHandler: Handler? = null
    private var syncUpdateThread: HandlerThread? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Configurable TTL for canned message documents (observed from DataRepository)
    @Volatile
    private var cannedMessageTtlMs: Long = 5 * 60 * 1000L  // Default 5 minutes, updated from settings

    // Track peers in emergency state with timestamp (like ATAK's _activeEmergencies map)
    // Maps nodeId -> timestamp when emergency was detected
    // Used to avoid clearing based on stale document data
    private val peersInEmergency = mutableMapOf<Long, Long>()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "HiveBtleService onCreate()")
        isRunning.set(true)

        startForegroundServiceCompat(
            notificationId = HIVE_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )

        val app = applicationContext as WearTakApplication
        dataRepository = app.repo
        hiveBtleRepository = app.hiveBtleRepo

        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }

        // Observe canned message TTL setting for dynamic updates
        observeCannedMessageTtl()

        // Start scheduler FIRST so handler is available for retry logic
        startSyncScheduler()
        initializeHiveBtle()

        hiveBtleRepository.updateMeshState(HiveBtleRepository.HiveMeshState.ACTIVE)
        dataRepository.setEndpointConnected(DataRepository.Endpoint.HIVE_BTLE)
        Log.i(TAG, "HiveBtleService started")
    }

    /**
     * Initialize the HiveBtle mesh with configuration from DataRepository.
     * Now uses encrypted mesh with DeviceIdentity and MeshGenesis.
     *
     * IMPORTANT: This waits for the callsign to be set before starting hive.
     * The callsign identifies this node to other peers in the mesh.
     */
    private fun initializeHiveBtle() {
        try {
            // Ensure callsign is loaded from DataRepository before starting
            // This prevents starting with a default/placeholder callsign
            val storedCallsign = runBlocking { dataRepository.getCallsign.first() }
            if (storedCallsign.isBlank() || storedCallsign == "WEAROS-0") {
                Log.w(TAG, "Callsign not yet set (current: '$storedCallsign'), delaying hive initialization")
                hiveBtleRepository.updateMeshState(HiveBtleRepository.HiveMeshState.INITIALIZING)
                // Retry after delay
                syncUpdateHandler?.postDelayed({ initializeHiveBtle() }, 1000)
                return
            }

            // Get configuration from DataRepository
            meshId = runBlocking { dataRepository.getHiveMeshId.first() }

            // Set Bluetooth adapter name to callsign so it appears in BLE advertisements
            // This makes the device identifiable in nRF Connect and other BLE scanners
            try {
                val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val bluetoothAdapter = bluetoothManager.adapter
                if (bluetoothAdapter != null && bluetoothAdapter.name != storedCallsign) {
                    val success = bluetoothAdapter.setName(storedCallsign)
                    if (success) {
                        Log.i(TAG, "Set Bluetooth adapter name to callsign: $storedCallsign")
                    } else {
                        Log.w(TAG, "Failed to set Bluetooth adapter name to: $storedCallsign")
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Missing permission to set Bluetooth name: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "Error setting Bluetooth name: ${e.message}")
            }

            // Load or create device identity (persisted across restarts)
            val identity = loadOrCreateIdentity()

            // Load or create mesh genesis (shared across all mesh nodes)
            val genesis = loadOrCreateGenesis(identity)

            Log.d(TAG, "Initializing HiveBtle with encryption: meshId=${genesis.getMeshId()}, callsign=$storedCallsign")

            // Create encrypted HiveBtle instance
            // IMPORTANT: Pass genesis.getMeshId() for BLE advertising!
            hiveBtle = HiveBtle(
                context = applicationContext,
                meshId = genesis.getMeshId(),  // Use genesis meshId for advertising
                identity = identity,
                genesis = genesis
            )

            // Initialize BLE adapter
            hiveBtle?.init()

            // Start the mesh with this service as the listener
            hiveBtle?.startMesh(this)

            // Verify encryption is active
            if (hiveBtle?.isEncryptionEnabled() == true) {
                Log.i(TAG, "HiveBtle mesh initialized with ENCRYPTION ENABLED")
            } else {
                Log.w(TAG, "HiveBtle mesh initialized but encryption NOT enabled")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize HiveBtle: ${e.message}", e)
            hiveBtleRepository.updateMeshState(HiveBtleRepository.HiveMeshState.ERROR)
        }
    }

    /**
     * Load persisted DeviceIdentity or generate a new one.
     * Identity is an Ed25519 keypair that persists across app restarts.
     */
    private fun loadOrCreateIdentity(): DeviceIdentity {
        val storedIdentity = runBlocking { dataRepository.getHiveDeviceIdentity.first() }

        return if (storedIdentity != null) {
            try {
                // Restore from persisted 32-byte private key
                val privateKey = Base64.decode(storedIdentity, Base64.NO_WRAP)
                val identity = restoreDeviceIdentity(privateKey)
                if (identity != null) {
                    Log.d(TAG, "Loaded existing DeviceIdentity: nodeId=${String.format("%08X", identity.getNodeId().toLong())}")
                    identity
                } else {
                    Log.w(TAG, "Failed to restore stored identity, generating new one")
                    generateAndStoreIdentity()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore stored identity: ${e.message}, generating new one")
                generateAndStoreIdentity()
            }
        } else {
            // First run - generate new identity
            Log.d(TAG, "No stored identity, generating new DeviceIdentity")
            generateAndStoreIdentity()
        }
    }

    private fun generateAndStoreIdentity(): DeviceIdentity {
        val identity = DeviceIdentity.generate()
        // Store the 32-byte private key (Base64 encoded)
        val encoded = Base64.encodeToString(identity.getPrivateKey(), Base64.NO_WRAP)
        runBlocking { dataRepository.storeHiveDeviceIdentity(encoded) }
        Log.i(TAG, "Generated and stored new DeviceIdentity: nodeId=${String.format("%08X", identity.getNodeId().toLong())}")
        return identity
    }

    /**
     * Load the shared hardcoded MeshGenesis for WEARTAK mesh.
     * All nodes must use the same genesis for encrypted communication.
     *
     * This genesis is shared with the ATAK Plugin team for interoperability testing.
     */
    private fun loadOrCreateGenesis(identity: DeviceIdentity): MeshGenesis {
        // Use the hardcoded shared genesis for WEARTAK mesh
        val bytes = Base64.decode(SHARED_GENESIS_BASE64, Base64.NO_WRAP)
        val genesis = decodeMeshGenesis(bytes)
        if (genesis != null) {
            Log.i(TAG, "Using SHARED WEARTAK genesis: meshId=${genesis.getMeshId()}")
            meshId = genesis.getMeshId()
            return genesis
        }

        // Fallback if hardcoded genesis fails (should not happen)
        Log.e(TAG, "Failed to decode SHARED_GENESIS_BASE64! Creating new genesis.")
        return createAndStoreGenesis(identity)
    }

    private fun createAndStoreGenesis(identity: DeviceIdentity): MeshGenesis {
        // Create genesis for WEARTAK mesh
        val genesis = MeshGenesis.create("WEARTAK", identity)
        val encoded = Base64.encodeToString(genesis.encode(), Base64.NO_WRAP)
        runBlocking { dataRepository.storeHiveMeshGenesis(encoded) }

        // Log the genesis for sharing with ATAK Plugin team
        Log.i(TAG, "=== SHAREABLE GENESIS (copy this for ATAK Plugin) ===")
        Log.i(TAG, "GENESIS_BASE64: $encoded")
        Log.i(TAG, "MESH_ID: ${genesis.getMeshId()}")
        Log.i(TAG, "=== END GENESIS ===")

        meshId = genesis.getMeshId()
        return genesis
    }

    // ==================== HiveMeshListener Implementation ====================

    /**
     * Called when the mesh state changes (peers added/removed/updated).
     *
     * All peer management is handled by hive-btle. We just convert HivePeer
     * to HivePeerInfo for UI display. Callsign, location, battery, emergency
     * state all come from peer.lastDocument which hive-btle manages.
     */
    override fun onMeshUpdated(peers: List<HivePeer>) {
        val directCount = peers.count { it.address.isNotEmpty() }
        val relayedCount = peers.count { it.address.isEmpty() }
        Log.d(TAG, "Mesh updated: ${peers.size} peers (direct=$directCount, relayed=$relayedCount)")

        // Convert to HivePeerInfo - extract ALL data from hive-btle's HivePeer
        // No management, just display. hive-btle handles deduplication, cleanup, etc.
        val peerInfoList = peers.map { peer ->
            // Get data from peer's last synced document (managed by hive-btle)
            val doc = peer.lastDocument
            val location = doc?.location()
            val eventType = peer.currentEventType()

            // Check emergency from both document AND our tracked state
            // onPeerEvent() handles add/remove from peersInEmergency map
            // Document eventType used as primary, tracked map as fallback for reliability
            //
            // Clear tracking if document shows non-emergency AND document is newer than when we tracked
            val trackedTimestamp = peersInEmergency[peer.nodeId]
            if (trackedTimestamp != null && eventType != HiveEventType.EMERGENCY && peer.lastSeen > trackedTimestamp) {
                peersInEmergency.remove(peer.nodeId)
                Log.i(TAG, "Cleared emergency for ${peer.displayName()} - newer document shows $eventType")
            }

            val isEmergency = eventType == HiveEventType.EMERGENCY || peersInEmergency.containsKey(peer.nodeId)

            HiveBtleRepository.HivePeerInfo(
                nodeId = peer.nodeId,
                identifier = peer.address,
                name = peer.name,
                callsign = doc?.callsign(),
                rssi = peer.rssi,
                isConnected = peer.isConnected,
                lastSeenMs = peer.lastSeen,
                latitude = location?.latitude?.toDouble(),
                longitude = location?.longitude?.toDouble(),
                altitude = location?.altitude?.toDouble(),
                battery = doc?.batteryPercent(),
                heartRate = doc?.heartRate(),
                isInEmergency = isEmergency,
                hopCount = if (peer.address.isNotEmpty()) 0 else 1
            )
        }

        // Log peers for debugging
        if (peerInfoList.isNotEmpty()) {
            Log.v(TAG, "Updating ${peerInfoList.size} peers: ${peerInfoList.map {
                "${it.nodeId.toString(16)}(${it.callsign ?: it.name ?: "?"})"
            }}")
        }

        // Just set peers - no merging, no deduplication. hive-btle handles all that.
        hiveBtleRepository.setPeers(peerInfoList)
    }

    /**
     * Called when a peer's event state changes in their document.
     * This fires ONCE when the event type changes, not repeatedly.
     *
     * Peer emergency state is extracted from peer.currentEventType() in onMeshUpdated.
     * This callback handles app-level notifications (canned messages to chat UI).
     */
    override fun onPeerEvent(peer: HivePeer, eventType: HiveEventType) {
        Log.i(TAG, "Peer event from ${peer.displayName()}: $eventType")

        val timestamp = System.currentTimeMillis()
        // Get callsign from peer's document (managed by hive-btle)
        val senderCallsign = peer.lastDocument?.callsign()

        ioScope.launch {
            when (eventType) {
                HiveEventType.EMERGENCY -> {
                    Log.w(TAG, "PEER SOS received from ${peer.displayName()}")
                    // Track this peer as in emergency with timestamp (survives document sync cycles)
                    peersInEmergency[peer.nodeId] = timestamp
                    // Update global emergency state for UI
                    hiveBtleRepository.setEmergencyState(
                        HiveBtleRepository.EmergencyState(
                            isActive = true,
                            sourceNodeId = peer.nodeId,
                            timestamp = timestamp
                        )
                    )
                }
                HiveEventType.ACK -> {
                    Log.i(TAG, "ACK received from ${peer.displayName()}")
                    // Add ACK to global emergency state
                    val current = hiveBtleRepository.emergencyState.value
                    if (current.isActive) {
                        hiveBtleRepository.setEmergencyState(
                            current.copy(ackedByNodes = current.ackedByNodes + peer.nodeId)
                        )
                    }
                }
                HiveEventType.MOVING -> {
                    Log.i(TAG, "MOVING from ${peer.displayName()}")
                    ChatService.addHiveCannedMessageStatic(peer.nodeId, "MOVING", 0x11, timestamp, senderCallsign)
                }
                HiveEventType.IN_POSITION -> {
                    Log.i(TAG, "IN POSITION from ${peer.displayName()}")
                    ChatService.addHiveCannedMessageStatic(peer.nodeId, "IN POSITION", 0x13, timestamp, senderCallsign)
                }
                HiveEventType.NEED_ASSIST -> {
                    Log.w(TAG, "NEED ASSIST from ${peer.displayName()}")
                    ChatService.addHiveCannedMessageStatic(peer.nodeId, "NEED ASSIST", 0x31, timestamp, senderCallsign)
                }
                HiveEventType.NONE -> {
                    // Event cleared - peer is no longer in emergency
                    Log.d(TAG, "Event cleared for ${peer.displayName()}")
                    val wasInEmergency = peersInEmergency.remove(peer.nodeId) != null
                    if (wasInEmergency) {
                        Log.i(TAG, "SOS CANCELLED by peer ${peer.displayName()}")
                        // Clear global emergency state if this was the source
                        val current = hiveBtleRepository.emergencyState.value
                        if (current.sourceNodeId == peer.nodeId) {
                            hiveBtleRepository.setEmergencyState(HiveBtleRepository.EmergencyState())
                        }
                    }
                }
                else -> {
                    // PING or unknown
                }
            }
        }
    }

    /**
     * Called when a peer connection is established.
     * Just log - peer state is managed by hive-btle and updated via onMeshUpdated.
     */
    override fun onPeerConnected(peer: HivePeer) {
        Log.i(TAG, "Peer connected: ${peer.displayName()} (${peer.address})")
        // No peer management - hive-btle handles this and notifies via onMeshUpdated
    }

    /**
     * Called when a peer connection is lost.
     * Just log - peer state is managed by hive-btle and updated via onMeshUpdated.
     */
    override fun onPeerDisconnected(peer: HivePeer) {
        Log.i(TAG, "Peer disconnected: ${peer.displayName()} (${peer.address})")
        // No peer management - hive-btle handles this and notifies via onMeshUpdated
    }

    /**
     * Called when a mesh document is synced.
     *
     * Just log and record stats. Peer data (callsign, location, etc.) is stored
     * in peer.lastDocument by hive-btle and extracted in onMeshUpdated.
     */
    override fun onDocumentSynced(document: HiveDocument) {
        val nodeIdHex = document.nodeId.toString(16).uppercase()
        val location = document.location()
        val docCallsign = document.callsign()

        if (location != null) {
            Log.i(TAG, "Document synced from $nodeIdHex: " +
                    "callsign=${docCallsign ?: "?"}, " +
                    "location=(${location.latitude}, ${location.longitude}), " +
                    "battery=${document.batteryPercent() ?: "?"}%")
        } else {
            Log.v(TAG, "Document synced from $nodeIdHex (no location)")
        }

        // Just record stats - peer data is managed by hive-btle
        hiveBtleRepository.recordDocumentReceived(64)
    }

    /**
     * Called when a map marker is synced from a peer.
     * Converts HiveMarker back to CoT format and publishes to EventBus for TAK display.
     */
    override fun onMarkerSynced(peer: HivePeer, marker: HiveMarker) {
        val nodeIdHex = peer.nodeId.toString(16).uppercase()
        Log.i(TAG, "Marker synced from $nodeIdHex: uid=${marker.uid}, type=${marker.type}, callsign=${marker.callsign}")

        ioScope.launch {
            try {
                // Convert HiveMarker to CoT XML for local display
                val cotXml = CotHiveTranslator.compactMarkerToCotXml(
                    CotHiveTranslator.CompactMarker(
                        uid = marker.uid,
                        type = marker.type,
                        lat = marker.lat,
                        lon = marker.lon,
                        hae = marker.hae,
                        callsign = marker.callsign,
                        time = marker.time
                    )
                )

                // Publish to EventBus for UI/TAK to receive
                EventBus.getDefault().post(HiveDocumentEvent("mapitem_rx", cotXml))
                hiveBtleRepository.recordDocumentReceived(84)

                Log.d(TAG, "Marker converted to CoT and published: ${marker.uid}")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to process received marker: ${e.message}", e)
            }
        }
    }

    /**
     * Called when a chat message is received from a mesh peer.
     * Converts to CoT XML and posts TakChatEvent for existing chat UI.
     */
    override fun onChatReceived(chat: HiveChat, fromPeer: HivePeer) {
        val nodeIdHex = chat.originNode.toString(16).uppercase()
        Log.i(TAG, "[CHAT-RX] From $nodeIdHex (${chat.sender}): ${chat.message}")

        // Filter out ACKs that aren't meant for us (i.e., ACKs for other peers' messages)
        val myNodeId = hiveBtle?.nodeId ?: 0L
        if (chat.message.equals("ACK", ignoreCase = true) && chat.replyToNode != 0L && chat.replyToNode != myNodeId) {
            Log.v(TAG, "[CHAT-RX] Ignoring ACK for another peer (replyTo: ${String.format("%08X", chat.replyToNode)})")
            return
        }

        // Build chat CoT XML for existing chat system
        val chatCotXml = buildMeshChatCotXml(chat)

        // Post to EventBus - ChatService will handle display in existing UI
        EventBus.getDefault().post(TakChatEvent(chatCotXml))

        // Store for ACK replyTo (no popup)
        hiveBtleRepository.setLastMeshChat(HiveBtleRepository.InboundChat(
            sender = chat.sender,
            message = chat.message,
            timestamp = chat.timestamp,
            originNodeId = chat.originNode
        ))

        hiveBtleRepository.recordDocumentReceived(chat.message.length + 30)
        Log.d(TAG, "[CHAT-RX] Posted TakChatEvent for mesh chat from ${chat.sender}")
    }

    /**
     * Called when raw decrypted data is received from a peer.
     * This is the hive-lite interface - app handles message parsing directly.
     */
    // Canned message document storage: (sourceNode, timestamp) -> latest document with ACKs
    // Key is (sourceNode, timestamp) since that uniquely identifies a message for CRDT merge
    private val cannedMessageDocs = mutableMapOf<Pair<Long, Long>, CannedMessageAckEventData>()
    private val cannedMessageStoredAt = mutableMapOf<Pair<Long, Long>, Long>()  // When doc was stored

    // Track which messages we've already processed (to avoid duplicate UI updates)
    private val processedCannedMessages = mutableSetOf<Pair<Long, Long>>()

    /**
     * Observe canned message TTL setting for dynamic updates without restart.
     */
    private fun observeCannedMessageTtl() {
        ioScope.launch {
            dataRepository.getHiveCannedMessageTtlSeconds.collect { ttlSeconds ->
                val newTtlMs = ttlSeconds * 1000L
                if (newTtlMs != cannedMessageTtlMs) {
                    Log.i(TAG, "[CONFIG] Canned message TTL changed: ${cannedMessageTtlMs/1000}s -> ${ttlSeconds}s")
                    cannedMessageTtlMs = newTtlMs
                }
            }
        }
    }

    /**
     * Clean up expired canned message documents to prevent memory growth.
     */
    private fun cleanupExpiredCannedMessages() {
        val now = System.currentTimeMillis()
        val expiredKeys = cannedMessageStoredAt.filter { (_, storedAt) ->
            now - storedAt > cannedMessageTtlMs
        }.keys.toList()

        if (expiredKeys.isNotEmpty()) {
            expiredKeys.forEach { key ->
                cannedMessageDocs.remove(key)
                cannedMessageStoredAt.remove(key)
                processedCannedMessages.remove(key)
            }
            Log.d(TAG, "[CANNED] Cleaned up ${expiredKeys.size} expired message docs (TTL: ${cannedMessageTtlMs/1000}s)")
        }
    }

    /**
     * Poll for CannedMessages that arrived via delta sync (0xB2/0xC0 documents).
     * Delta-synced documents are stored in Rust and need to be polled since they
     * don't come through onDecryptedData callback.
     */
    private fun pollCannedMessagesFromDeltaSync() {
        val btle = hiveBtle ?: return

        try {
            val cannedMessages = btle.getAllCannedMessages()
            Log.d(TAG, "[DELTA-POLL] Polling: found ${cannedMessages.size} canned messages in Rust")
            if (cannedMessages.isEmpty()) return

            val myNodeId = btle.nodeId

            for (msgInfo in cannedMessages) {
                val docKey = Pair(msgInfo.sourceNode.toLong(), msgInfo.timestamp.toLong())

                // Decode the CannedMessageAckEvent from bytes
                val event = decodeCannedMessageAckEvent(msgInfo.encodedBytes)
                if (event == null) {
                    Log.w(TAG, "[DELTA-POLL] Failed to decode CannedMessageAckEvent")
                    continue
                }

                val messageType = event.message
                val sourceNode = event.sourceNode.toLong()
                val targetNode = event.targetNode.toLong()
                val timestamp = event.timestamp.toLong()
                val label = cannedMessageShortName(messageType)

                // Filter: ignore messages targeted to other nodes
                if (targetNode != 0L && targetNode != myNodeId) {
                    Log.v(TAG, "[DELTA-POLL] Ignoring $label for another node")
                    processedCannedMessages.add(docKey)  // Mark processed to avoid re-checking
                    continue
                }

                // Store/merge the document and check if ACK count changed
                val storedEvent = cannedMessageDocs[docKey]
                val previousAckCount = storedEvent?.let { cannedMessageAckEventAckCount(it) } ?: 0u
                val mergedEvent = if (storedEvent != null) {
                    cannedMessageAckEventMerge(storedEvent, event)
                } else {
                    event
                }
                cannedMessageDocs[docKey] = mergedEvent
                cannedMessageStoredAt[docKey] = System.currentTimeMillis()

                val newAckCount = cannedMessageAckEventAckCount(mergedEvent)
                val isNewMessage = !processedCannedMessages.contains(docKey)
                val ackCountChanged = newAckCount != previousAckCount

                // Update UI if ACK count changed (even for already-processed messages)
                if (ackCountChanged && !isNewMessage) {
                    Log.i(TAG, "[DELTA-POLL] ACK count updated for ${String.format("%08X", sourceNode)}:$timestamp: $previousAckCount -> $newAckCount")
                    ChatService.updateHiveAckCountStatic(sourceNode, timestamp, newAckCount.toInt())
                    continue  // Skip new message handling since already processed
                }

                // Skip if already processed and ACK count didn't change
                if (!isNewMessage) {
                    continue
                }

                Log.i(TAG, "[DELTA-POLL] NEW $label from ${String.format("%08X", sourceNode)}" +
                        if (targetNode != 0L) " to ${String.format("%08X", targetNode)}" else " (broadcast)" +
                        ", ACKs: $newAckCount")

                // Look up callsign
                val senderCallsign = hiveBtleRepository.peers.value
                    .find { it.nodeId == sourceNode }?.callsign
                    ?: btle.getPeer(sourceNode)?.let { blePeer ->
                        blePeer.name.takeIf { it.isNotEmpty() }
                    }

                // Check if this message type requires ChatService
                val requiresChatService = when (messageType) {
                    CannedMessageType.CHECK_IN,
                    CannedMessageType.MOVING,
                    CannedMessageType.HOLDING,
                    CannedMessageType.ON_STATION,
                    CannedMessageType.RETURNING,
                    CannedMessageType.COMPLETE,
                    CannedMessageType.ALERT,
                    CannedMessageType.CONTACT,
                    CannedMessageType.NEED_EXTRACT,
                    CannedMessageType.NEED_SUPPORT,
                    CannedMessageType.NEED_RESUPPLY -> true
                    else -> false
                }

                // If ChatService is required but not running, skip this message for now
                // It will be retried on the next poll when ChatService is available
                if (requiresChatService && !ChatService.isRunning.get()) {
                    Log.d(TAG, "[DELTA-POLL] Deferring $label - ChatService not running yet")
                    continue
                }

                // Mark as processed now that we're going to handle it
                processedCannedMessages.add(docKey)

                // Handle based on message type
                when (messageType) {
                    CannedMessageType.EMERGENCY,
                    CannedMessageType.NEED_MEDIC,
                    CannedMessageType.UNDER_FIRE -> {
                        Log.w(TAG, "[DELTA-POLL] EMERGENCY from ${String.format("%08X", sourceNode)}")
                        hiveBtleRepository.setEmergencyState(
                            HiveBtleRepository.EmergencyState(
                                isActive = true,
                                sourceNodeId = sourceNode,
                                timestamp = timestamp
                            )
                        )
                    }

                    CannedMessageType.ALL_CLEAR -> {
                        Log.i(TAG, "[DELTA-POLL] ALL_CLEAR from ${String.format("%08X", sourceNode)}")
                        val current = hiveBtleRepository.emergencyState.value
                        if (current.sourceNodeId == sourceNode) {
                            hiveBtleRepository.setEmergencyState(HiveBtleRepository.EmergencyState())
                        }
                    }

                    // Status updates - add to chat
                    CannedMessageType.CHECK_IN,
                    CannedMessageType.MOVING,
                    CannedMessageType.HOLDING,
                    CannedMessageType.ON_STATION,
                    CannedMessageType.RETURNING,
                    CannedMessageType.COMPLETE -> {
                        Log.i(TAG, "[DELTA-POLL] Status: $label from ${senderCallsign ?: String.format("%08X", sourceNode)}")
                        ChatService.addHiveCannedMessageStatic(sourceNode, label, messageType.ordinal, timestamp, senderCallsign)
                    }

                    // Alerts - add to chat
                    CannedMessageType.ALERT,
                    CannedMessageType.CONTACT -> {
                        Log.w(TAG, "[DELTA-POLL] Alert: $label from ${senderCallsign ?: String.format("%08X", sourceNode)}")
                        ChatService.addHiveCannedMessageStatic(sourceNode, label, messageType.ordinal, timestamp, senderCallsign)
                    }

                    // Requests - add to chat
                    CannedMessageType.NEED_EXTRACT,
                    CannedMessageType.NEED_SUPPORT,
                    CannedMessageType.NEED_RESUPPLY -> {
                        Log.w(TAG, "[DELTA-POLL] Request: $label from ${senderCallsign ?: String.format("%08X", sourceNode)}")
                        ChatService.addHiveCannedMessageStatic(sourceNode, label, messageType.ordinal, timestamp, senderCallsign)
                    }

                    // ACK types
                    CannedMessageType.ACK,
                    CannedMessageType.ACK_WILCO,
                    CannedMessageType.ACK_NEGATIVE,
                    CannedMessageType.ACK_SAY_AGAIN -> {
                        Log.i(TAG, "[DELTA-POLL] ACK from ${String.format("%08X", sourceNode)}")
                        val current = hiveBtleRepository.emergencyState.value
                        if (current.isActive) {
                            hiveBtleRepository.setEmergencyState(
                                current.copy(ackedByNodes = current.ackedByNodes + sourceNode)
                            )
                        }
                    }

                    else -> {
                        Log.d(TAG, "[DELTA-POLL] Other message: $label ($messageType)")
                    }
                }

                // Update ACK count in ChatService (only if ChatService is running)
                if (ChatService.isRunning.get()) {
                    ChatService.updateHiveAckCountStatic(sourceNode, timestamp, newAckCount.toInt())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[DELTA-POLL] Error polling canned messages: ${e.message}", e)
        }
    }

    override fun onDecryptedData(peer: HivePeer?, data: ByteArray) {
        // CannedMessages now flow through delta sync only (0xB2/0xC0 documents)
        // No longer handling raw 0xAF broadcasts here - use pollCannedMessagesFromDeltaSync()
        if (data.isNotEmpty()) {
            Log.v(TAG, "[DECRYPTED] Received ${data.size} bytes, marker=0x${String.format("%02X", data[0])}")
        }
    }

    /**
     * Build CoT XML for an incoming mesh chat message.
     * Uses "All Chat Rooms" as the destination so it appears in the main chat view.
     */
    private fun buildMeshChatCotXml(chat: HiveChat): String {
        val now = System.currentTimeMillis()
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        dateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val timeStr = dateFormat.format(java.util.Date(now))

        val uid = java.util.UUID.randomUUID().toString()
        val senderUid = "HIVE-${chat.originNode.toString(16).uppercase()}"
        val dstUid = "All Chat Rooms"
        val dstChatroom = "All Chat Rooms"

        // Prefix message with mesh name (sender shown separately in chat UI)
        val displayMessage = "[$meshId] ${chat.message}"

        return """
        <?xml version='1.0' encoding='UTF-8' standalone='yes'?>
        <event version='2.0' uid='GeoChat.$senderUid.$dstUid.$uid' type='b-t-f' time='$timeStr' start='$timeStr' stale='$timeStr' how='h-g-i-g-o' access='Undefined'>
            <point lat='0.0' lon='0.0' hae='0.0' ce='9999999.0' le='9999999.0' />
            <detail>
                <__chat parent='RootContactGroup' groupOwner='false' messageId='$uid' chatroom='$dstChatroom' id='$dstUid' senderCallsign='${chat.sender}'>
                    <chatgrp uid0='$senderUid' uid1='$dstUid' id='$dstUid'/>
                </__chat>
                <link uid='$senderUid' type='a-f-G-U-C' relation='p-p'/>
                <remarks source='$senderUid' to='$dstUid' time='$timeStr'>$displayMessage</remarks>
            </detail>
        </event>
        """.trimIndent()
    }

    // ==================== Sync Scheduler ====================

    /**
     * Periodic sync update - broadcasts current state to mesh.
     */
    private val syncUpdateRunnable = object : Runnable {
        override fun run() {
            Log.d(TAG, "[SYNC] Sync scheduler tick (interval=${CotDispatchService.currReportingIntervalInSecs}s)")
            ioScope.launch {
                val location = dataRepository.latestLocation.value
                val battery = dataRepository.latestBatteryPercentage.value

                if (location != null && battery != null) {
                    broadcastTrackUpdate()
                } else {
                    Log.w(TAG, "Missing data for HIVE sync. Skipping this cycle.")
                }

                // Poll for CannedMessages that arrived via delta sync
                pollCannedMessagesFromDeltaSync()

                // Cleanup expired canned message documents
                cleanupExpiredCannedMessages()
            }

            // Schedule next run - use shorter interval for responsive delta sync polling
            // (reporting interval is for CoT, but delta sync should be faster)
            val pollIntervalMs = minOf(CotDispatchService.currReportingIntervalInSecs * 1000L, 10_000L)
            syncUpdateHandler?.postDelayed(this, pollIntervalMs)
        }
    }

    private fun startSyncScheduler() {
        syncUpdateThread = HandlerThread("HiveSyncThread")
        syncUpdateThread?.start()
        syncUpdateHandler = syncUpdateThread?.looper?.let { Handler(it) }

        // Schedule first run
        syncUpdateHandler?.postDelayed(syncUpdateRunnable, SYNC_INTERVAL_MS)
    }

    private fun stopSyncScheduler() {
        syncUpdateHandler?.removeCallbacks(syncUpdateRunnable)
        syncUpdateThread?.quitSafely()
        syncUpdateHandler = null
        syncUpdateThread = null
    }

    /**
     * Broadcast current track (position/vitals) to mesh.
     */
    private fun broadcastTrackUpdate() {
        val btle = hiveBtle ?: return

        try {
            val location = dataRepository.latestLocation.value ?: return
            val heartRate = dataRepository.latestHeartRate.value
            val battery = dataRepository.latestBatteryPercentage.value ?: 100

            // Create HiveLocation from Android location
            val hiveLocation = HiveLocation(
                latitude = location.latitude.toFloat(),
                longitude = location.longitude.toFloat(),
                altitude = location.altitude.toFloat()
            )

            // Preserve emergency state in track updates - if THIS watch initiated an SOS,
            // send EMERGENCY to keep peers showing the SOS; otherwise send NONE for normal tracking
            val emergencyState = hiveBtleRepository.emergencyState.value
            val localNodeId = btle.nodeId
            val isLocalEmergencyActive = emergencyState.isActive && emergencyState.sourceNodeId == localNodeId
            val eventType = if (isLocalEmergencyActive) {
                HiveEventType.EMERGENCY
            } else {
                HiveEventType.NONE
            }

            // Send sync event with location and vitals
            btle.sendEvent(
                eventType = eventType,
                location = hiveLocation,
                callsign = callsign,
                battery = battery,
                heartRate = heartRate
            )

            hiveBtleRepository.recordDocumentSent(64) // Approximate size
            Log.i(TAG, "Track update broadcast: lat=${hiveLocation.latitude}, lon=${hiveLocation.longitude}, callsign=$callsign")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast track update: ${e.message}", e)
        }
    }

    // ==================== EventBus Handlers ====================

    /**
     * Handle HiveDocumentEvent from CotDispatchService.
     * Converts CoT XML to HiveDocument format and broadcasts to mesh.
     */
    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onHiveDoc(evt: HiveDocumentEvent) {
        ioScope.launch {
            try {
                Log.v(TAG, "Received HiveDocumentEvent: collection=${evt.collection}")

                when (evt.collection) {
                    "alert" -> handleAlertDocument(evt.cotXml)
                    "mapitem" -> handleMapItemDocument(evt.cotXml)
                    "track" -> handleTrackDocument(evt.cotXml)
                    "chat" -> handleOutboundChatDocument(evt.cotXml, evt.replyToNode, evt.replyToTimestamp)
                    else -> Log.w(TAG, "Unknown collection: ${evt.collection}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to process HiveDocumentEvent: ${e.message}", e)
            }
        }
    }

    /**
     * Handle HiveCannedMessageEvent - sends tactical canned messages via mesh.
     * Uses hive-lite CannedMessageAckEvent format for proper CRDT ACK tracking.
     */
    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onHiveCannedMessage(evt: HiveCannedMessageEvent) {
        val btle = hiveBtle ?: run {
            Log.w(TAG, "[CANNED] HiveBtle not initialized, ignoring canned message")
            return
        }

        ioScope.launch {
            try {
                // Convert message code to CannedMessageType
                val messageType = uniffi.hive_lite_android.cannedMessageFromCode(evt.messageCode.toUByte())
                    ?: run {
                        Log.w(TAG, "[CANNED-TX] Unknown message code: ${evt.messageCode}")
                        return@launch
                    }

                val label = cannedMessageShortName(messageType)
                val myNodeId = btle.nodeId
                val timestamp = System.currentTimeMillis()

                // Check if this is an ACK response to an existing message
                val isAckResponse = messageType == CannedMessageType.ACK ||
                        messageType == CannedMessageType.ACK_WILCO ||
                        messageType == CannedMessageType.ACK_NEGATIVE ||
                        messageType == CannedMessageType.ACK_SAY_AGAIN

                if (isAckResponse && evt.targetNode != 0L) {
                    // ACK to a specific message - use originalTimestamp if provided, otherwise find latest
                    val docKey = if (evt.originalTimestamp != 0L) {
                        Pair(evt.targetNode, evt.originalTimestamp)
                    } else {
                        // Try to find in Kotlin cache first, then in Rust
                        findLatestMessageFromNode(evt.targetNode)
                            ?: findLatestMessageFromRust(evt.targetNode)
                    }
                    if (docKey != null) {
                        Log.i(TAG, "[CANNED-TX] Adding ACK to message from ${String.format("%08X", evt.targetNode)} (timestamp=${docKey.second})")

                        // Update the Rust-side document for delta sync propagation
                        val rustUpdated = btle.ackCannedMessage(
                            docKey.first.toUInt(),
                            docKey.second.toULong(),
                            myNodeId.toUInt(),
                            timestamp.toULong()
                        )
                        if (rustUpdated) {
                            Log.i(TAG, "[CANNED-TX] ACK added to Rust document (will propagate via delta sync)")
                        } else {
                            Log.w(TAG, "[CANNED-TX] Failed to add ACK to Rust document")
                        }

                        // Also update Kotlin-side cache if present
                        val existingDoc = cannedMessageDocs[docKey]
                        if (existingDoc != null) {
                            val updatedDoc = cannedMessageAckEventAddAck(
                                existingDoc,
                                myNodeId.toUInt(),
                                timestamp.toULong()
                            )
                            cannedMessageDocs[docKey] = updatedDoc
                            cannedMessageStoredAt[docKey] = System.currentTimeMillis()

                            val ackCount = cannedMessageAckEventAckCount(updatedDoc)
                            Log.i(TAG, "[CANNED-TX] ACK recorded locally, total ACKs: $ackCount")

                            // Update UI with new ACK count
                            ChatService.updateHiveAckCountStatic(docKey.first, docKey.second, ackCount.toInt())
                        }
                        return@launch
                    }
                    Log.w(TAG, "[CANNED-TX] No existing message found for ACK")
                    return@launch
                }

                // New message: create fresh CannedMessageAckEvent and store in Rust for delta sync
                Log.i(TAG, "[CANNED-TX] Creating $label (0x${evt.messageCode.toString(16).uppercase()}) to ${if (evt.targetNode == 0L) "broadcast" else String.format("%08X", evt.targetNode)}")

                val event = createCannedMessageAckEvent(
                    message = messageType,
                    sourceNode = myNodeId.toUInt(),
                    targetNode = evt.targetNode.toUInt(),
                    timestamp = timestamp.toULong(),
                    sequence = 0u
                )

                // Store in Kotlin cache
                val docKey = Pair(myNodeId, timestamp)
                cannedMessageDocs[docKey] = event
                cannedMessageStoredAt[docKey] = System.currentTimeMillis()
                processedCannedMessages.add(docKey)

                // Store in Rust for delta sync propagation
                val encoded = encodeCannedMessageAckEvent(event)
                val stored = btle.storeCannedMessageDocument(encoded)
                if (stored) {
                    Log.i(TAG, "[CANNED-TX] $label stored in Rust (will propagate via delta sync)")
                } else {
                    Log.w(TAG, "[CANNED-TX] Failed to store $label in Rust")
                }

            } catch (e: Exception) {
                Log.e(TAG, "[CANNED-TX] Failed to send canned message: ${e.message}", e)
            }
        }
    }

    /**
     * Find the most recent message from a specific node in Kotlin cache.
     * Used when ACKing a message from a peer.
     */
    private fun findLatestMessageFromNode(nodeId: Long): Pair<Long, Long>? {
        return cannedMessageDocs.keys
            .filter { it.first == nodeId }
            .maxByOrNull { it.second }
    }

    /**
     * Find the most recent message from a specific node in Rust storage.
     * Used when the message came via delta sync and isn't in Kotlin cache.
     */
    private fun findLatestMessageFromRust(nodeId: Long): Pair<Long, Long>? {
        val btle = hiveBtle ?: return null
        return btle.getAllCannedMessages()
            .filter { it.sourceNode.toLong() == nodeId }
            .maxByOrNull { it.timestamp.toLong() }
            ?.let { Pair(it.sourceNode.toLong(), it.timestamp.toLong()) }
    }

    /**
     * Send an ACK in response to receiving a CannedMessage from a peer.
     * Uses the new CRDT ACK approach which embeds the ACK in the message document itself.
     */
    private fun sendCannedMessageAck(targetNode: Long, originalTimestamp: Long) {
        val btle = hiveBtle ?: run {
            Log.w(TAG, "[ACK] HiveBtle not initialized, cannot send ACK")
            return
        }

        ioScope.launch {
            try {
                val myNodeId = btle.nodeId
                val docKey = Pair(targetNode, originalTimestamp)
                val existingDoc = cannedMessageDocs[docKey]

                if (existingDoc != null) {
                    Log.i(TAG, "[ACK-TX] Adding ACK to message ${String.format("%08X", targetNode)}:$originalTimestamp")

                    // Add our ACK to the document
                    val updatedDoc = cannedMessageAckEventAddAck(
                        existingDoc,
                        myNodeId.toUInt(),
                        System.currentTimeMillis().toULong()
                    )
                    cannedMessageDocs[docKey] = updatedDoc
                    cannedMessageStoredAt[docKey] = System.currentTimeMillis()

                    // Encode and broadcast the updated document
                    val encoded = encodeCannedMessageAckEvent(updatedDoc)
                    btle.broadcastBytes(encoded)
                    hiveBtleRepository.recordDocumentSent(encoded.size)

                    val ackCount = cannedMessageAckEventAckCount(updatedDoc)
                    Log.i(TAG, "[ACK-TX] Sent ACK (${encoded.size} bytes), total ACKs: $ackCount")
                } else {
                    Log.w(TAG, "[ACK-TX] No stored document for ${String.format("%08X", targetNode)}:$originalTimestamp, using legacy ACK")
                    // Fallback: send via HiveBtle sendEvent (legacy path)
                    btle.sendEvent(HiveEventType.ACK)
                }
            } catch (e: Exception) {
                Log.e(TAG, "[ACK-TX] Failed to send ACK: ${e.message}", e)
            }
        }
    }

    /**
     * Handle emergency alert - uses HiveBtle emergency broadcast.
     * Detects cancellation via type='b-a-o-can' or cancel='true' attribute.
     */
    private fun handleAlertDocument(cotXml: String) {
        val btle = hiveBtle ?: return

        try {
            // Check if this is a cancellation (type='b-a-o-can' or cancel='true')
            val isCancellation = cotXml.contains("type='b-a-o-can'") ||
                                 cotXml.contains("cancel='true'") ||
                                 cotXml.contains("cancel=\"true\"")

            if (isCancellation) {
                // Send NONE to clear emergency state on peers
                btle.sendEvent(HiveEventType.NONE)
                hiveBtleRepository.setEmergencyState(HiveBtleRepository.EmergencyState())
                hiveBtleRepository.recordDocumentSent(64)
                Log.i(TAG, "Emergency CANCELLED broadcast sent")
            } else {
                // Send emergency event via HiveBtle
                btle.sendEvent(HiveEventType.EMERGENCY)
                val nodeId = btle.nodeId
                hiveBtleRepository.setEmergencyState(
                    HiveBtleRepository.EmergencyState(
                        isActive = true,
                        sourceNodeId = nodeId,
                        timestamp = System.currentTimeMillis()
                    )
                )
                hiveBtleRepository.recordDocumentSent(64)
                Log.i(TAG, "Emergency broadcast sent")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send emergency: ${e.message}", e)
        }
    }

    /**
     * Handle map item (marker) - encode and broadcast to mesh peers.
     */
    private fun handleMapItemDocument(cotXml: String) {
        val btle = hiveBtle ?: return

        try {
            // Parse CoT and create compact marker for BLE transmission
            val cotEvent = CotHiveTranslator.parseCotXml(cotXml)
            if (cotEvent != null) {
                // Convert CoT event to HiveMarker and send via mesh
                val hiveMarker = HiveMarker(
                    uid = cotEvent.uid,
                    type = cotEvent.type,
                    lat = cotEvent.lat.toFloat(),
                    lon = cotEvent.lon.toFloat(),
                    hae = cotEvent.hae.toFloat(),
                    callsign = cotEvent.callsign ?: callsign,
                    time = cotEvent.time
                )

                btle.sendMarker(hiveMarker)
                hiveBtleRepository.recordDocumentSent(84)  // ~84 bytes typical marker size
                Log.i(TAG, "MapItem marker sent: uid=${cotEvent.uid}, type=${cotEvent.type}, callsign=${hiveMarker.callsign}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send mapitem marker: ${e.message}", e)
        }
    }

    /**
     * Handle track update from external source.
     */
    private fun handleTrackDocument(cotXml: String) {
        // Track documents are handled by the periodic broadcastTrackUpdate()
        Log.v(TAG, "Track document received - handled by periodic sync")
    }

    /**
     * Handle outbound chat message - send to mesh peers.
     * Parses CoT XML to extract message and broadcasts via HiveBtle.
     *
     * @param cotXml The CoT XML containing the message
     * @param replyToNode If non-zero, originNode of the message being replied to
     * @param replyToTimestamp If non-zero, timestamp of the message being replied to
     */
    private fun handleOutboundChatDocument(cotXml: String, replyToNode: Long = 0, replyToTimestamp: Long = 0) {
        val btle = hiveBtle ?: return

        try {
            // Extract message from <remarks> element
            val remarksPattern = "<remarks[^>]*>([^<]*)</remarks>".toRegex()
            val remarksMatch = remarksPattern.find(cotXml)
            val message = remarksMatch?.groupValues?.get(1) ?: return

            // Skip if message is empty or is an incoming mesh message (already prefixed with mesh name)
            if (message.isBlank() || message.startsWith("[$meshId]")) {
                Log.v(TAG, "[CHAT-TX] Skipping: empty or mesh echo")
                return
            }

            // Create HiveChat with threading support
            val chat = HiveChat(
                sender = callsign.take(HiveChat.MAX_SENDER_LENGTH),
                message = message.take(HiveChat.MAX_MESSAGE_LENGTH),
                timestamp = System.currentTimeMillis(),
                originNode = btle.nodeId,
                replyToNode = replyToNode,
                replyToTimestamp = replyToTimestamp
            )

            // Send via mesh
            btle.sendChat(chat)
            hiveBtleRepository.recordDocumentSent(message.length + 42)  // +12 bytes for replyTo fields

            if (replyToNode != 0L) {
                Log.i(TAG, "[CHAT-TX] Sent reply to mesh: $message (replyTo: ${String.format("%08X", replyToNode)}:$replyToTimestamp)")
            } else {
                Log.i(TAG, "[CHAT-TX] Sent to mesh: $message")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send chat to mesh: ${e.message}", e)
        }
    }

    // ==================== Lifecycle ====================

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "HiveBtleService onDestroy()")
        isRunning.set(false)

        stopForeground(STOP_FOREGROUND_DETACH)
        stopSyncScheduler()

        try {
            hiveBtle?.stopMesh()
            hiveBtle?.shutdown()
            hiveBtle = null
            Log.d(TAG, "HiveBtle stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping HiveBtle: ${e.message}", e)
        }

        hiveBtleRepository.updateMeshState(HiveBtleRepository.HiveMeshState.NOT_INITIALIZED)
        // Clear peers - hive-btle manages peer state, we just display it
        hiveBtleRepository.clearPeers()
        dataRepository.setEndpointDisconnected(DataRepository.Endpoint.HIVE_BTLE)

        ioScope.cancel()

        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "HiveBtleService onStartCommand()")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Not a bound service
    }

    companion object {
        private val TAG = "WearTAK-" + HiveBtleService::class.java.simpleName

        /**
         * Shared genesis for WEARTAK encrypted mesh.
         * All nodes (WearOS, ATAK Plugin) must use this same genesis.
         *
         * MESH_ID: 29C916FA
         * Generated: 2026-01-19
         */
        private const val SHARED_GENESIS_BASE64: String =
            "BwBXRUFSVEFL4O7thA03dXXBNkT+gG22aTRGICECcX5RHtOgIdLBrb7tU7LTxkFLCLP+De21IALSXAbi6ZR/c3VXW9lKWacbM0YqfK9n5JXqob7/stIM63nBMLzJiFTGl9E6wcF8Gz0gUerY2JsBAAAA"
        private const val HIVE_SERVICE_NOTIFICATION_ID = 3334
        private const val SYNC_INTERVAL_MS = 5_000L // 5 seconds initial tick

        val isRunning: AtomicBoolean = AtomicBoolean(false)
    }
}
