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

        initializeHiveBtle()
        startSyncScheduler()

        hiveBtleRepository.updateMeshState(HiveBtleRepository.HiveMeshState.ACTIVE)
        dataRepository.setEndpointConnected(DataRepository.Endpoint.HIVE_BTLE)
        Log.i(TAG, "HiveBtleService started")
    }

    /**
     * Initialize the HiveBtle mesh with configuration from DataRepository.
     * Now uses encrypted mesh with DeviceIdentity and MeshGenesis.
     */
    private fun initializeHiveBtle() {
        try {
            // Get configuration from DataRepository
            meshId = runBlocking { dataRepository.getHiveMeshId.first() }

            // Load or create device identity (persisted across restarts)
            val identity = loadOrCreateIdentity()

            // Load or create mesh genesis (shared across all mesh nodes)
            val genesis = loadOrCreateGenesis(identity)

            Log.d(TAG, "Initializing HiveBtle with encryption: meshId=${genesis.getMeshId()}, callsign=$callsign")

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
     */
    override fun onMeshUpdated(peers: List<HivePeer>) {
        val directCount = peers.count { it.address.isNotEmpty() }
        val relayedCount = peers.count { it.address.isEmpty() }
        Log.d(TAG, "Mesh updated: ${peers.size} peers (direct=$directCount, relayed=$relayedCount)")

        // Update repository with peer info
        val peerInfoList = peers.map { peer ->
            // Determine hop count: 0 for direct BLE connection, 1 for relayed via mesh
            val hopCount = if (peer.address.isNotEmpty()) 0 else 1

            HiveBtleRepository.HivePeerInfo(
                nodeId = peer.nodeId,
                identifier = peer.address,
                name = peer.name,
                rssi = peer.rssi,
                isConnected = peer.isConnected,
                lastSeenMs = peer.lastSeen,
                hopCount = hopCount
            )
        }
        hiveBtleRepository.updatePeers(peerInfoList)
    }

    /**
     * Called when a peer's event state changes in their document.
     * This fires ONCE when the event type changes, not repeatedly.
     */
    override fun onPeerEvent(peer: HivePeer, eventType: HiveEventType) {
        Log.i(TAG, "Peer event from ${peer.displayName()}: $eventType")

        val timestamp = System.currentTimeMillis()
        val senderCallsign = hiveBtleRepository.peers.value
            .find { it.nodeId == peer.nodeId }?.callsign

        ioScope.launch {
            when (eventType) {
                HiveEventType.EMERGENCY -> {
                    Log.w(TAG, "PEER SOS received from ${peer.displayName()}")
                    hiveBtleRepository.startEmergency(peer.nodeId, timestamp)
                    hiveBtleRepository.setPeerEmergency(peer.nodeId, true, timestamp)
                }
                HiveEventType.ACK -> {
                    Log.i(TAG, "ACK received from ${peer.displayName()}")
                    hiveBtleRepository.addEmergencyAck(peer.nodeId)
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
                    // Event cleared - check if emergency was cancelled
                    hiveBtleRepository.setPeerEmergency(peer.nodeId, false)
                }
                else -> {
                    // PING or unknown
                }
            }
        }
    }

    /**
     * Called when a peer connection is established.
     */
    override fun onPeerConnected(peer: HivePeer) {
        Log.i(TAG, "Peer connected: ${peer.displayName()}")
        // Update peer as connected in repository (direct connection = hopCount 0)
        hiveBtleRepository.addOrUpdatePeer(
            HiveBtleRepository.HivePeerInfo(
                nodeId = peer.nodeId,
                identifier = peer.address,
                name = peer.name,
                rssi = peer.rssi,
                isConnected = true,
                lastSeenMs = System.currentTimeMillis(),
                hopCount = 0  // Direct BLE connection
            )
        )
    }

    /**
     * Called when a peer connection is lost.
     * Immediately removes the peer from the repository for instant UI update.
     */
    override fun onPeerDisconnected(peer: HivePeer) {
        Log.i(TAG, "Peer disconnected: ${peer.displayName()} (address=${peer.address})")
        // Use address-based removal since nodeId may have changed after document sync
        hiveBtleRepository.removePeerByIdentifier(peer.address)
    }

    /**
     * Called when a mesh document is synced.
     */
    override fun onDocumentSynced(document: HiveDocument) {
        val nodeIdHex = document.nodeId.toString(16).uppercase()
        val location = document.location()
        val docCallsign = document.callsign()
        val battery = document.batteryPercent()
        val heartRate = document.heartRate()
        val eventType = document.currentEventType()

        if (location != null) {
            Log.i(TAG, "Document synced from $nodeIdHex: " +
                    "callsign=${docCallsign ?: "?"}, " +
                    "location=(${location.latitude}, ${location.longitude}), " +
                    "battery=${battery ?: "?"}%, " +
                    "heartRate=${heartRate ?: "?"}")

            // Update peer info with location
            hiveBtleRepository.updatePeerLocation(
                nodeId = document.nodeId,
                latitude = location.latitude.toDouble(),
                longitude = location.longitude.toDouble(),
                altitude = location.altitude.toDouble(),
                callsign = docCallsign,
                battery = battery,
                heartRate = heartRate
            )
        } else {
            Log.v(TAG, "Document synced from $nodeIdHex (no location)")
        }

        // Check for emergency cancellation: peer was in emergency but now sending NONE
        if (eventType == HiveEventType.NONE) {
            val peerInEmergency = hiveBtleRepository.peers.value.find {
                it.nodeId == document.nodeId && it.isInEmergency
            }
            if (peerInEmergency != null) {
                Log.i(TAG, "Peer $nodeIdHex emergency cancelled (now sending NONE)")
                hiveBtleRepository.setPeerEmergency(document.nodeId, false)
            }
        }

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
    private val CANNED_MESSAGE_TTL_MS = 5 * 60 * 1000L  // 5 minutes

    // Track which messages we've already processed (to avoid duplicate UI updates)
    private val processedCannedMessages = mutableSetOf<Pair<Long, Long>>()

    /**
     * Clean up expired canned message documents to prevent memory growth.
     */
    private fun cleanupExpiredCannedMessages() {
        val now = System.currentTimeMillis()
        val expiredKeys = cannedMessageStoredAt.filter { (_, storedAt) ->
            now - storedAt > CANNED_MESSAGE_TTL_MS
        }.keys.toList()

        if (expiredKeys.isNotEmpty()) {
            expiredKeys.forEach { key ->
                cannedMessageDocs.remove(key)
                cannedMessageStoredAt.remove(key)
                processedCannedMessages.remove(key)
            }
            Log.d(TAG, "[CANNED] Cleaned up ${expiredKeys.size} expired message docs")
        }
    }

    override fun onDecryptedData(peer: HivePeer?, data: ByteArray) {
        if (data.isEmpty()) return

        when (data[0].toUByte()) {
            cannedMessageMarker() -> {
                // CannedMessage (0xAF) - decode using hive-lite ACK event API
                val incomingEvent = decodeCannedMessageAckEvent(data)
                if (incomingEvent == null) {
                    Log.w(TAG, "[DECRYPTED] Failed to decode CannedMessageAckEvent")
                    return
                }

                val messageType = incomingEvent.message
                val sourceNode = incomingEvent.sourceNode.toLong()
                val targetNode = incomingEvent.targetNode.toLong()
                val timestamp = incomingEvent.timestamp.toLong()
                val docKey = Pair(sourceNode, timestamp)

                val label = cannedMessageShortName(messageType)
                val myNodeId = hiveBtle?.nodeId ?: 0L

                // Filter: ignore messages targeted to other nodes
                if (targetNode != 0L && targetNode != myNodeId) {
                    Log.v(TAG, "[CANNED-RX] Ignoring $label for another node")
                    return
                }

                // CRDT merge: combine incoming ACKs with any we have stored
                val storedEvent = cannedMessageDocs[docKey]
                val mergedEvent = if (storedEvent != null) {
                    cannedMessageAckEventMerge(storedEvent, incomingEvent)
                } else {
                    incomingEvent
                }
                cannedMessageDocs[docKey] = mergedEvent
                cannedMessageStoredAt[docKey] = System.currentTimeMillis()

                val ackCount = cannedMessageAckEventAckCount(mergedEvent)
                Log.d(TAG, "[CANNED-RX] $label from ${String.format("%08X", sourceNode)}, ACKs: $ackCount")

                // Update ACK count in ChatService (for UI display)
                ChatService.updateHiveAckCountStatic(sourceNode, timestamp, ackCount.toInt())

                // Check if this is a new message (not just an ACK update)
                val isNewMessage = !processedCannedMessages.contains(docKey)
                if (isNewMessage) {
                    processedCannedMessages.add(docKey)

                    Log.i(TAG, "[CANNED-RX] NEW $label from ${String.format("%08X", sourceNode)}" +
                            if (targetNode != 0L) " to ${String.format("%08X", targetNode)}" else " (broadcast)")

                    // Look up callsign
                    val senderCallsign = hiveBtleRepository.peers.value
                        .find { it.nodeId == sourceNode }?.callsign
                        ?: hiveBtle?.getPeer(sourceNode)?.let { blePeer ->
                            blePeer.name.takeIf { it.isNotEmpty() }
                        }
                        ?: peer?.takeIf { it.nodeId == sourceNode }?.name?.takeIf { it.isNotEmpty() }

                    // Handle based on message type
                    when (messageType) {
                        CannedMessageType.EMERGENCY,
                        CannedMessageType.NEED_MEDIC,
                        CannedMessageType.UNDER_FIRE -> {
                            Log.w(TAG, "[CANNED-RX] EMERGENCY from ${String.format("%08X", sourceNode)}")
                            hiveBtleRepository.startEmergency(sourceNode, timestamp)
                            hiveBtleRepository.setPeerEmergency(sourceNode, true, timestamp)
                        }

                        CannedMessageType.ALL_CLEAR -> {
                            Log.i(TAG, "[CANNED-RX] ALL_CLEAR from ${String.format("%08X", sourceNode)}")
                            hiveBtleRepository.setPeerEmergency(sourceNode, false)
                        }

                        // Status updates - add to chat
                        CannedMessageType.CHECK_IN,
                        CannedMessageType.MOVING,
                        CannedMessageType.HOLDING,
                        CannedMessageType.ON_STATION,
                        CannedMessageType.RETURNING,
                        CannedMessageType.COMPLETE -> {
                            Log.i(TAG, "[CANNED-RX] Status: $label from ${senderCallsign ?: String.format("%08X", sourceNode)}")
                            ChatService.addHiveCannedMessageStatic(sourceNode, label, messageType.ordinal, timestamp, senderCallsign)
                        }

                        // Alerts - add to chat
                        CannedMessageType.ALERT,
                        CannedMessageType.CONTACT -> {
                            Log.w(TAG, "[CANNED-RX] Alert: $label from ${senderCallsign ?: String.format("%08X", sourceNode)}")
                            ChatService.addHiveCannedMessageStatic(sourceNode, label, messageType.ordinal, timestamp, senderCallsign)
                        }

                        // Requests - add to chat
                        CannedMessageType.NEED_EXTRACT,
                        CannedMessageType.NEED_SUPPORT,
                        CannedMessageType.NEED_RESUPPLY -> {
                            Log.w(TAG, "[CANNED-RX] Request: $label from ${senderCallsign ?: String.format("%08X", sourceNode)}")
                            ChatService.addHiveCannedMessageStatic(sourceNode, label, messageType.ordinal, timestamp, senderCallsign)
                        }

                        // ACK types are now handled via embedded ACKs, not as separate messages
                        CannedMessageType.ACK,
                        CannedMessageType.ACK_WILCO,
                        CannedMessageType.ACK_NEGATIVE,
                        CannedMessageType.ACK_SAY_AGAIN -> {
                            // Legacy ACK message format - still process for backwards compat
                            Log.i(TAG, "[CANNED-RX] Legacy ACK type received from ${String.format("%08X", sourceNode)}")
                            hiveBtleRepository.addEmergencyAck(sourceNode)
                        }

                        else -> {
                            Log.d(TAG, "[CANNED-RX] Other message: $label ($messageType)")
                        }
                    }
                }

                // Check for new ACKs in the merged document
                // For emergency messages, track who has ACKed
                if (messageType == CannedMessageType.EMERGENCY ||
                    messageType == CannedMessageType.NEED_MEDIC ||
                    messageType == CannedMessageType.UNDER_FIRE) {
                    for (ack in mergedEvent.acks) {
                        val ackerNodeId = ack.nodeId.toLong()
                        if (ackerNodeId != sourceNode) {
                            Log.i(TAG, "[CANNED-RX] ACK for emergency from ${String.format("%08X", ackerNodeId)}")
                            hiveBtleRepository.addEmergencyAck(ackerNodeId)
                        }
                    }
                }
            }

            else -> {
                // Other document types handled by legacy callbacks (onDocumentSynced, onChatReceived, etc.)
                Log.v(TAG, "[DECRYPTED] Non-CannedMessage data: marker=0x${String.format("%02X", data[0])}, size=${data.size}")
            }
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
            ioScope.launch {
                val location = dataRepository.latestLocation.value
                val battery = dataRepository.latestBatteryPercentage.value

                if (location != null && battery != null) {
                    broadcastTrackUpdate()
                } else {
                    Log.w(TAG, "Missing data for HIVE sync. Skipping this cycle.")
                }

                // Cleanup expired canned message documents
                cleanupExpiredCannedMessages()
            }

            // Schedule next run based on reporting interval
            syncUpdateHandler?.postDelayed(this, CotDispatchService.currReportingIntervalInSecs * 1000L)
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

            // Send sync event with location and vitals
            btle.sendEvent(
                eventType = HiveEventType.NONE,
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
                        findLatestMessageFromNode(evt.targetNode)
                    }
                    if (docKey != null) {
                        val existingDoc = cannedMessageDocs[docKey]
                        if (existingDoc != null) {
                            Log.i(TAG, "[CANNED-TX] Adding ACK to message from ${String.format("%08X", evt.targetNode)} (timestamp=${docKey.second})")

                            // Add our ACK to the document
                            val updatedDoc = cannedMessageAckEventAddAck(
                                existingDoc,
                                myNodeId.toUInt(),
                                timestamp.toULong()
                            )
                            cannedMessageDocs[docKey] = updatedDoc
                            cannedMessageStoredAt[docKey] = System.currentTimeMillis()

                            // Encode and broadcast the updated document
                            val encoded = encodeCannedMessageAckEvent(updatedDoc)
                            btle.broadcastBytes(encoded)
                            hiveBtleRepository.recordDocumentSent(encoded.size)

                            val ackCount = cannedMessageAckEventAckCount(updatedDoc)
                            Log.i(TAG, "[CANNED-TX] Sent ACK update (${encoded.size} bytes), total ACKs: $ackCount")
                            return@launch
                        }
                    }
                    Log.w(TAG, "[CANNED-TX] No existing message found for ACK, sending legacy ACK message")
                }

                // New message or fallback: create fresh CannedMessageAckEvent
                Log.i(TAG, "[CANNED-TX] Sending $label (0x${evt.messageCode.toString(16).uppercase()}) to ${if (evt.targetNode == 0L) "broadcast" else String.format("%08X", evt.targetNode)}")

                val event = createCannedMessageAckEvent(
                    message = messageType,
                    sourceNode = myNodeId.toUInt(),
                    targetNode = evt.targetNode.toUInt(),
                    timestamp = timestamp.toULong(),
                    sequence = 0u
                )

                // Store our own message so we can receive ACKs for it
                val docKey = Pair(myNodeId, timestamp)
                cannedMessageDocs[docKey] = event
                cannedMessageStoredAt[docKey] = System.currentTimeMillis()
                processedCannedMessages.add(docKey)

                // Encode and broadcast
                val encoded = encodeCannedMessageAckEvent(event)
                btle.broadcastBytes(encoded)
                hiveBtleRepository.recordDocumentSent(encoded.size)
                Log.i(TAG, "[CANNED-TX] Sent $label (${encoded.size} bytes) from ${String.format("%08X", myNodeId)}")

            } catch (e: Exception) {
                Log.e(TAG, "[CANNED-TX] Failed to send canned message: ${e.message}", e)
            }
        }
    }

    /**
     * Find the most recent message from a specific node.
     * Used when ACKing a message from a peer.
     */
    private fun findLatestMessageFromNode(nodeId: Long): Pair<Long, Long>? {
        return cannedMessageDocs.keys
            .filter { it.first == nodeId }
            .maxByOrNull { it.second }
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
                val nodeId = btle.nodeId
                hiveBtleRepository.clearEmergency()
                hiveBtleRepository.recordDocumentSent(64)
                Log.i(TAG, "Emergency CANCELLED broadcast sent")
            } else {
                // Send emergency event via HiveBtle
                btle.sendEvent(HiveEventType.EMERGENCY)
                val nodeId = btle.nodeId
                hiveBtleRepository.startEmergency(nodeId, System.currentTimeMillis())
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
        // Don't clear peers - mark them as disconnected to preserve cached callsigns
        // When service restarts, updatePeers() will merge with existing data
        hiveBtleRepository.markAllPeersDisconnected()
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
