/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

/*
 * PeatBtleService handles Peat BLE mesh sync lifecycle using the peat-btle library.
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
import com.aitech.weartak_civ.data.PeatBtleRepository
import com.aitech.weartak_civ.events.PeatCannedMessageEvent
import com.aitech.weartak_civ.events.PeatDocumentEvent
import com.aitech.weartak_civ.events.TakChatEvent
import com.aitech.weartak_civ.peat.CannedMessageCodec
import com.aitech.weartak_civ.peat.CotPeatTranslator
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import android.util.Base64
// uniffi.peat_lite_android imports removed — using local CannedMessageCodec instead
import com.defenseunicorns.peat.PeatBtle
import com.defenseunicorns.peat.PeatChat
import com.defenseunicorns.peat.PeatDocument
import com.defenseunicorns.peat.PeatEventType
import com.defenseunicorns.peat.PeatLocation
import com.defenseunicorns.peat.PeatMarker
import com.defenseunicorns.peat.PeatMeshListener
import com.defenseunicorns.peat.PeatPeer
import uniffi.peat_btle.DeviceIdentity
import uniffi.peat_btle.MeshGenesis
import uniffi.peat_btle.restoreDeviceIdentity
import uniffi.peat_btle.decodeMeshGenesis
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
 * Peat BLE Mesh Sync Service.
 *
 * Power-efficient replacement for DittoService using the peat-btle library.
 * Targets <5% radio duty cycle for 18+ hour battery life on WearOS devices.
 *
 * Uses PeatBtle for BLE operations (scanning, advertising, GATT) and implements
 * PeatMeshListener to receive mesh events (peer updates, emergencies, document sync).
 */
class PeatBtleService : Service(), PeatMeshListener {

    private lateinit var dataRepository: DataRepository
    private lateinit var peatBtleRepository: PeatBtleRepository
    private var peatBtle: PeatBtle? = null
    private var meshId: String = "PEAT"  // Default, updated on init

    private var syncUpdateHandler: Handler? = null
    private var syncUpdateThread: HandlerThread? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Configurable TTL for canned message documents (observed from DataRepository)
    @Volatile
    private var cannedMessageTtlMs: Long = 5 * 60 * 1000L  // Default 5 minutes, updated from settings


    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PeatBtleService onCreate()")
        isRunning.set(true)

        startForegroundServiceCompat(
            notificationId = PEAT_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )

        val app = applicationContext as WearTakApplication
        dataRepository = app.repo
        peatBtleRepository = app.peatBtleRepo

        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }

        // Observe canned message TTL setting for dynamic updates
        observeCannedMessageTtl()

        // Start scheduler FIRST so handler is available for retry logic
        startSyncScheduler()
        initializePeatBtle()

        peatBtleRepository.updateMeshState(PeatBtleRepository.PeatMeshState.ACTIVE)
        dataRepository.setEndpointConnected(DataRepository.Endpoint.PEAT_BTLE)
        Log.i(TAG, "PeatBtleService started")
    }

    /**
     * Initialize the PeatBtle mesh with configuration from DataRepository.
     * Now uses encrypted mesh with DeviceIdentity and MeshGenesis.
     *
     * IMPORTANT: This waits for the callsign to be set before starting peat.
     * The callsign identifies this node to other peers in the mesh.
     */
    private fun initializePeatBtle() {
        try {
            // Ensure callsign is loaded from DataRepository before starting
            // This prevents starting with a default/placeholder callsign
            val storedCallsign = runBlocking { dataRepository.getCallsign.first() }
            if (storedCallsign.isBlank() || storedCallsign == "WEAROS-0") {
                Log.w(TAG, "Callsign not yet set (current: '$storedCallsign'), delaying peat initialization")
                peatBtleRepository.updateMeshState(PeatBtleRepository.PeatMeshState.INITIALIZING)
                // Retry after delay
                syncUpdateHandler?.postDelayed({ initializePeatBtle() }, 1000)
                return
            }

            // Get configuration from DataRepository
            meshId = runBlocking { dataRepository.getPeatMeshId.first() }

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

            Log.d(TAG, "Initializing PeatBtle with encryption: meshId=${genesis.getMeshId()}, callsign=$storedCallsign")

            // Create encrypted PeatBtle instance
            // IMPORTANT: Pass genesis.getMeshId() for BLE advertising!
            peatBtle = PeatBtle(
                context = applicationContext,
                meshId = genesis.getMeshId(),  // Use genesis meshId for advertising
                identity = identity,
                genesis = genesis
            )

            // Initialize BLE adapter
            peatBtle?.init()

            // Start the mesh with this service as the listener
            peatBtle?.startMesh(this)

            // Verify encryption is active
            if (peatBtle?.isEncryptionEnabled() == true) {
                Log.i(TAG, "PeatBtle mesh initialized with ENCRYPTION ENABLED")
            } else {
                Log.w(TAG, "PeatBtle mesh initialized but encryption NOT enabled")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PeatBtle: ${e.message}", e)
            peatBtleRepository.updateMeshState(PeatBtleRepository.PeatMeshState.ERROR)
        }
    }

    /**
     * Load persisted DeviceIdentity or generate a new one.
     * Identity is an Ed25519 keypair that persists across app restarts.
     */
    private fun loadOrCreateIdentity(): DeviceIdentity {
        val storedIdentity = runBlocking { dataRepository.getPeatDeviceIdentity.first() }

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
        runBlocking { dataRepository.storePeatDeviceIdentity(encoded) }
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
        runBlocking { dataRepository.storePeatMeshGenesis(encoded) }

        // Log the genesis for sharing with ATAK Plugin team
        Log.i(TAG, "=== SHAREABLE GENESIS (copy this for ATAK Plugin) ===")
        Log.i(TAG, "GENESIS_BASE64: $encoded")
        Log.i(TAG, "MESH_ID: ${genesis.getMeshId()}")
        Log.i(TAG, "=== END GENESIS ===")

        meshId = genesis.getMeshId()
        return genesis
    }

    // ==================== PeatMeshListener Implementation ====================

    /**
     * Called when the mesh state changes (peers added/removed/updated).
     *
     * All peer management is handled by peat-btle. We just convert PeatPeer
     * to PeatPeerInfo for UI display. Callsign, location, battery, emergency
     * state all come from peer.lastDocument which peat-btle manages.
     */
    override fun onMeshUpdated(peers: List<PeatPeer>) {
        val directCount = peers.count { it.address.isNotEmpty() }
        val relayedCount = peers.count { it.address.isEmpty() }
        Log.d(TAG, "Mesh updated: ${peers.size} peers (direct=$directCount, relayed=$relayedCount)")

        // Convert to PeatPeerInfo - extract ALL data from peat-btle's PeatPeer
        // No management, just display. peat-btle handles deduplication, cleanup, etc.
        val peerInfoList = peers.map { peer ->
            // Get data from peer's last synced document (managed by peat-btle)
            val doc = peer.lastDocument
            val location = doc?.location()
            val eventType = peer.currentEventType()

            PeatBtleRepository.PeatPeerInfo(
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
                isInEmergency = eventType == PeatEventType.EMERGENCY,
                hopCount = if (peer.address.isNotEmpty()) 0 else 1
            )
        }

        // Log peers for debugging
        if (peerInfoList.isNotEmpty()) {
            Log.v(TAG, "Updating ${peerInfoList.size} peers: ${peerInfoList.map {
                "${it.nodeId.toString(16)}(${it.callsign ?: it.name ?: "?"})"
            }}")
        }

        // Just set peers - no merging, no deduplication. peat-btle handles all that.
        peatBtleRepository.setPeers(peerInfoList)
    }

    /**
     * Called when a peer's event state changes in their document.
     * This fires ONCE when the event type changes, not repeatedly.
     *
     * Peer emergency state is extracted from peer.currentEventType() in onMeshUpdated.
     * This callback handles app-level notifications (canned messages to chat UI).
     */
    override fun onPeerEvent(peer: PeatPeer, eventType: PeatEventType) {
        Log.i(TAG, "Peer event from ${peer.displayName()}: $eventType")

        val timestamp = System.currentTimeMillis()
        // Get callsign from peer's document (managed by peat-btle)
        val senderCallsign = peer.lastDocument?.callsign()

        ioScope.launch {
            when (eventType) {
                PeatEventType.EMERGENCY -> {
                    Log.w(TAG, "PEER SOS received from ${peer.displayName()}")
                    // Update global emergency state for UI
                    peatBtleRepository.setEmergencyState(
                        PeatBtleRepository.EmergencyState(
                            isActive = true,
                            sourceNodeId = peer.nodeId,
                            timestamp = timestamp
                        )
                    )
                }
                PeatEventType.ACK -> {
                    Log.i(TAG, "ACK received from ${peer.displayName()}")
                    // Add ACK to global emergency state
                    val current = peatBtleRepository.emergencyState.value
                    if (current.isActive) {
                        peatBtleRepository.setEmergencyState(
                            current.copy(ackedByNodes = current.ackedByNodes + peer.nodeId)
                        )
                    }
                }
                PeatEventType.MOVING -> {
                    Log.i(TAG, "MOVING from ${peer.displayName()}")
                    ChatService.addPeatCannedMessageStatic(peer.nodeId, "MOVING", 0x11, timestamp, senderCallsign)
                }
                PeatEventType.IN_POSITION -> {
                    Log.i(TAG, "IN POSITION from ${peer.displayName()}")
                    ChatService.addPeatCannedMessageStatic(peer.nodeId, "IN POSITION", 0x13, timestamp, senderCallsign)
                }
                PeatEventType.NEED_ASSIST -> {
                    Log.w(TAG, "NEED ASSIST from ${peer.displayName()}")
                    ChatService.addPeatCannedMessageStatic(peer.nodeId, "NEED ASSIST", 0x31, timestamp, senderCallsign)
                }
                PeatEventType.NONE -> {
                    // Event cleared - peer is no longer in emergency
                    // Emergency state per-peer is handled by onMeshUpdated extracting from peer.currentEventType()
                    Log.d(TAG, "Event cleared for ${peer.displayName()}")
                }
                else -> {
                    // PING or unknown
                }
            }
        }
    }

    /**
     * Called when a peer connection is established.
     * Just log - peer state is managed by peat-btle and updated via onMeshUpdated.
     */
    override fun onPeerConnected(peer: PeatPeer) {
        Log.i(TAG, "Peer connected: ${peer.displayName()} (${peer.address})")
        // No peer management - peat-btle handles this and notifies via onMeshUpdated
    }

    /**
     * Called when a peer connection is lost.
     * Just log - peer state is managed by peat-btle and updated via onMeshUpdated.
     */
    override fun onPeerDisconnected(peer: PeatPeer) {
        Log.i(TAG, "Peer disconnected: ${peer.displayName()} (${peer.address})")
        // No peer management - peat-btle handles this and notifies via onMeshUpdated
    }

    /**
     * Called when a mesh document is synced.
     *
     * Just log and record stats. Peer data (callsign, location, etc.) is stored
     * in peer.lastDocument by peat-btle and extracted in onMeshUpdated.
     */
    override fun onDocumentSynced(document: PeatDocument) {
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

        // Just record stats - peer data is managed by peat-btle
        peatBtleRepository.recordDocumentReceived(64)
    }

    /**
     * Called when a map marker is synced from a peer.
     * Converts PeatMarker back to CoT format and publishes to EventBus for TAK display.
     */
    override fun onMarkerSynced(peer: PeatPeer, marker: PeatMarker) {
        val nodeIdHex = peer.nodeId.toString(16).uppercase()
        Log.i(TAG, "Marker synced from $nodeIdHex: uid=${marker.uid}, type=${marker.type}, callsign=${marker.callsign}")

        ioScope.launch {
            try {
                // Convert PeatMarker to CoT XML for local display
                val cotXml = CotPeatTranslator.compactMarkerToCotXml(
                    CotPeatTranslator.CompactMarker(
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
                EventBus.getDefault().post(PeatDocumentEvent("mapitem_rx", cotXml))
                peatBtleRepository.recordDocumentReceived(84)

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
    override fun onChatReceived(chat: PeatChat, fromPeer: PeatPeer) {
        val nodeIdHex = chat.originNode.toString(16).uppercase()
        Log.i(TAG, "[CHAT-RX] From $nodeIdHex (${chat.sender}): ${chat.message}")

        // Filter out ACKs that aren't meant for us (i.e., ACKs for other peers' messages)
        val myNodeId = peatBtle?.nodeId ?: 0L
        if (chat.message.equals("ACK", ignoreCase = true) && chat.replyToNode != 0L && chat.replyToNode != myNodeId) {
            Log.v(TAG, "[CHAT-RX] Ignoring ACK for another peer (replyTo: ${String.format("%08X", chat.replyToNode)})")
            return
        }

        // Build chat CoT XML for existing chat system
        val chatCotXml = buildMeshChatCotXml(chat)

        // Post to EventBus - ChatService will handle display in existing UI
        EventBus.getDefault().post(TakChatEvent(chatCotXml))

        // Store for ACK replyTo (no popup)
        peatBtleRepository.setLastMeshChat(PeatBtleRepository.InboundChat(
            sender = chat.sender,
            message = chat.message,
            timestamp = chat.timestamp,
            originNodeId = chat.originNode
        ))

        peatBtleRepository.recordDocumentReceived(chat.message.length + 30)
        Log.d(TAG, "[CHAT-RX] Posted TakChatEvent for mesh chat from ${chat.sender}")
    }

    /**
     * Called when raw decrypted data is received from a peer.
     * This is the peat-lite interface - app handles message parsing directly.
     */
    // Track received canned message timestamps for dedup and TTL cleanup
    private val cannedMessageStoredAt = mutableMapOf<Pair<Long, Long>, Long>()

    /**
     * Observe canned message TTL setting for dynamic updates without restart.
     */
    private fun observeCannedMessageTtl() {
        ioScope.launch {
            dataRepository.getPeatCannedMessageTtlSeconds.collect { ttlSeconds ->
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
                cannedMessageStoredAt.remove(key)
            }
            Log.d(TAG, "[CANNED] Cleaned up ${expiredKeys.size} expired message entries (TTL: ${cannedMessageTtlMs/1000}s)")
        }
    }

    /**
     * Poll for CannedMessages from delta sync.
     * Queries the Rust mesh document registry for all stored CannedMessages
     * and dispatches new ones to the chat UI.
     */
    private fun pollCannedMessagesFromDeltaSync() {
        val mesh = peatBtle?.mesh ?: return
        val messages = mesh.getAllCannedMessages()
        val docCount = mesh.appDocumentCount()
        if (docCount > 0u || messages.isNotEmpty()) {
            Log.d(TAG, "[CANNED-POLL] appDocumentCount=$docCount, getAllCannedMessages=${messages.size}")
        }
        val myNodeId = peatBtle?.nodeId ?: 0L
        for (msg in messages) {
            val key = Pair(msg.sourceNode.toLong(), msg.timestamp.toLong())
            // Skip messages from self — don't echo our own alerts back as chat
            if (msg.sourceNode.toLong() == myNodeId) continue
            // Only process if we haven't seen this message before
            if (!cannedMessageStoredAt.containsKey(key)) {
                cannedMessageStoredAt[key] = System.currentTimeMillis()
                val senderCallsign = peatBtle?.getCachedCallsign(msg.sourceNode.toLong())
                Log.i(TAG, "[CANNED-RX] ${msg.messageName} from ${String.format("%08X", msg.sourceNode.toLong())} (acks=${msg.ackCount})")
                ChatService.addPeatCannedMessageStatic(
                    msg.sourceNode.toLong(),
                    msg.messageName,
                    msg.messageCode.toInt(),
                    msg.timestamp.toLong(),
                    senderCallsign
                )
            }
        }
    }

    override fun onDecryptedData(peer: PeatPeer?, data: ByteArray) {
        if (data.isEmpty()) return
        Log.v(TAG, "[DECRYPTED] Received ${data.size} bytes, marker=0x${String.format("%02X", data[0])}")

        // Handle 0xAF CannedMessage broadcasts (immediate display only)
        // Document storage happens via Rust delta sync — don't duplicate-store here
        if (data[0] == CannedMessageCodec.MARKER) {
            val event = CannedMessageCodec.decode(data) ?: return
            // Skip messages from self — don't echo our own messages back as chat
            val myNodeId = peatBtle?.nodeId ?: 0L
            if (event.sourceNode == myNodeId) return
            val key = Pair(event.sourceNode, event.timestamp)
            if (!cannedMessageStoredAt.containsKey(key)) {
                cannedMessageStoredAt[key] = System.currentTimeMillis()
                val label = com.aitech.weartak_civ.peat.CannedMessageType.fromCode(event.messageCode)?.label
                    ?: "Unknown (0x${String.format("%02X", event.messageCode)})"
                val senderCallsign = peer?.lastDocument?.callsign()
                Log.i(TAG, "[CANNED-RX] $label from ${String.format("%08X", event.sourceNode)}")
                ChatService.addPeatCannedMessageStatic(
                    event.sourceNode, label, event.messageCode, event.timestamp, senderCallsign
                )
            }
        }
    }

    /**
     * Build CoT XML for an incoming mesh chat message.
     * Uses "All Chat Rooms" as the destination so it appears in the main chat view.
     */
    private fun buildMeshChatCotXml(chat: PeatChat): String {
        val now = System.currentTimeMillis()
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        dateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val timeStr = dateFormat.format(java.util.Date(now))

        val uid = java.util.UUID.randomUUID().toString()
        val senderUid = "PEAT-${chat.originNode.toString(16).uppercase()}"
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
            Log.d(TAG, "[SYNC] Sync scheduler tick (interval=${SYNC_INTERVAL_MS / 1000}s)")
            ioScope.launch {
                val location = dataRepository.latestLocation.value
                val battery = dataRepository.latestBatteryPercentage.value

                if (location != null && battery != null) {
                    broadcastTrackUpdate()
                } else {
                    Log.w(TAG, "Missing data for Peat sync. Skipping this cycle.")
                }

                // Poll for CannedMessages that arrived via delta sync
                pollCannedMessagesFromDeltaSync()

                // Cleanup expired canned message documents
                cleanupExpiredCannedMessages()
            }

            // Schedule next run - use shorter interval for responsive delta sync polling
            // (reporting interval is for CoT, but delta sync should be faster)
            syncUpdateHandler?.postDelayed(this, SYNC_INTERVAL_MS)
        }
    }

    private fun startSyncScheduler() {
        syncUpdateThread = HandlerThread("PeatSyncThread")
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
        val btle = peatBtle ?: return

        try {
            val location = dataRepository.latestLocation.value ?: return
            val heartRate = dataRepository.latestHeartRate.value
            val battery = dataRepository.latestBatteryPercentage.value ?: 100

            // Create PeatLocation from Android location
            val peatLocation = PeatLocation(
                latitude = location.latitude.toFloat(),
                longitude = location.longitude.toFloat(),
                altitude = location.altitude.toFloat()
            )

            // Send sync event with location and vitals
            // Preserve EMERGENCY state + description during position updates
            val isInEmergency = peatBtleRepository.emergencyState.value.isActive
            val eventType = if (isInEmergency) PeatEventType.EMERGENCY else PeatEventType.NONE
            val eventDesc = if (isInEmergency) (btle.localPeripheral?.lastEvent?.description ?: "") else ""
            btle.sendEvent(
                eventType = eventType,
                location = peatLocation,
                callsign = callsign,
                battery = battery,
                heartRate = heartRate,
                eventDescription = eventDesc
            )

            peatBtleRepository.recordDocumentSent(64) // Approximate size
            Log.i(TAG, "Track update broadcast: lat=${peatLocation.latitude}, lon=${peatLocation.longitude}, callsign=$callsign")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast track update: ${e.message}", e)
        }
    }

    // ==================== EventBus Handlers ====================

    /**
     * Handle PeatDocumentEvent from CotDispatchService.
     * Converts CoT XML to PeatDocument format and broadcasts to mesh.
     */
    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onPeatDoc(evt: PeatDocumentEvent) {
        ioScope.launch {
            try {
                Log.v(TAG, "Received PeatDocumentEvent: collection=${evt.collection}")

                when (evt.collection) {
                    "alert" -> handleAlertDocument(evt.cotXml)
                    "mapitem" -> handleMapItemDocument(evt.cotXml)
                    "track" -> handleTrackDocument(evt.cotXml)
                    "chat" -> handleOutboundChatDocument(evt.cotXml, evt.replyToNode, evt.replyToTimestamp)
                    else -> Log.w(TAG, "Unknown collection: ${evt.collection}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to process PeatDocumentEvent: ${e.message}", e)
            }
        }
    }

    /**
     * Handle PeatCannedMessageEvent - stores canned message in mesh document registry
     * for reliable delta sync delivery to all peers.
     */
    @Subscribe(threadMode = ThreadMode.ASYNC)
    fun onPeatCannedMessage(evt: PeatCannedMessageEvent) {
        Log.i(TAG, "[CANNED-EVT] Received PeatCannedMessageEvent: code=0x${evt.messageCode.toString(16)}, target=${String.format("%08X", evt.targetNode)}, origTs=${evt.originalTimestamp}")
        val btle = peatBtle ?: run {
            Log.w(TAG, "[CANNED] PeatBtle not initialized, ignoring canned message")
            return
        }

        ioScope.launch {
            try {
                val mesh = btle.mesh ?: run {
                    Log.w(TAG, "[CANNED-TX] Mesh not available")
                    return@launch
                }
                val myNodeId = btle.nodeId
                val timestamp = System.currentTimeMillis()

                // ACK messages (0x00-0x03) with a target and originalTimestamp → ack existing document
                val isAck = evt.messageCode <= 0x03 && evt.targetNode != 0L && evt.originalTimestamp != 0L
                Log.i(TAG, "[CANNED-TX] isAck=$isAck (code=${evt.messageCode}, target=${evt.targetNode}, origTs=${evt.originalTimestamp})")
                if (isAck) {
                    val ackCount = mesh.ackCannedMessage(
                        evt.targetNode.toUInt(),
                        evt.originalTimestamp.toULong(),
                        myNodeId.toUInt(),
                        timestamp.toULong()
                    )
                    Log.i(TAG, "[CANNED-TX] ACK added to message from ${String.format("%08X", evt.targetNode)}, total ACKs: $ackCount")
                } else {
                    val label = com.aitech.weartak_civ.peat.CannedMessageType.fromCode(evt.messageCode)?.label
                        ?: "Code 0x${evt.messageCode.toString(16).uppercase()}"

                    Log.i(TAG, "[CANNED-TX] Storing $label (0x${evt.messageCode.toString(16).uppercase()}) to ${if (evt.targetNode == 0L) "broadcast" else String.format("%08X", evt.targetNode)}")

                    val stored = mesh.storeCannedMessage(
                        evt.messageCode.toUByte(),
                        myNodeId.toUInt(),
                        evt.targetNode.toUInt(),
                        timestamp.toULong()
                    )

                    Log.i(TAG, "[CANNED-TX] $label stored in mesh (stored=$stored), will sync via delta")
                }

            } catch (e: Exception) {
                Log.e(TAG, "[CANNED-TX] Failed to store canned message: ${e.message}", e)
            }
        }
    }

    /**
     * Find the most recent message from a specific node in local cache.
     */
    private fun findLatestMessageFromNode(nodeId: Long): Pair<Long, Long>? {
        return cannedMessageStoredAt.keys
            .filter { it.first == nodeId }
            .maxByOrNull { it.second }
    }

    /**
     * Send an ACK in response to receiving a CannedMessage from a peer.
     * Uses mesh document registry — ACK is stored and synced via delta.
     */
    private fun sendCannedMessageAck(targetNode: Long, originalTimestamp: Long) {
        val btle = peatBtle ?: run {
            Log.w(TAG, "[ACK] PeatBtle not initialized, cannot send ACK")
            return
        }

        ioScope.launch {
            try {
                val mesh = btle.mesh ?: run {
                    Log.w(TAG, "[ACK] Mesh not available")
                    return@launch
                }
                val ackCount = mesh.ackCannedMessage(
                    targetNode.toUInt(),
                    originalTimestamp.toULong(),
                    btle.nodeId.toUInt(),
                    System.currentTimeMillis().toULong()
                )
                Log.i(TAG, "[ACK-TX] ACKed message from ${String.format("%08X", targetNode)}, total ACKs: $ackCount")
            } catch (e: Exception) {
                Log.e(TAG, "[ACK-TX] Failed to ACK: ${e.message}", e)
            }
        }
    }

    /**
     * Handle emergency alert - uses PeatBtle emergency broadcast.
     * Detects cancellation via type='b-a-o-can' or cancel='true' attribute.
     */
    private fun handleAlertDocument(cotXml: String) {
        val btle = peatBtle ?: return

        try {
            // Check if this is a cancellation (type='b-a-o-can' or cancel='true')
            val isCancellation = cotXml.contains("type='b-a-o-can'") ||
                                 cotXml.contains("cancel='true'") ||
                                 cotXml.contains("cancel=\"true\"")

            if (isCancellation) {
                // Send NONE to clear emergency state on peers
                btle.sendEvent(PeatEventType.NONE)
                peatBtleRepository.setEmergencyState(PeatBtleRepository.EmergencyState())
                peatBtleRepository.recordDocumentSent(64)
                Log.i(TAG, "Emergency CANCELLED broadcast sent")
            } else {
                // Extract emergency type from CoT XML (e.g., "Injury", "Gunshot")
                val emergencyType = Regex("emergency\\s+type=['\"]([^'\"]+)['\"]")
                    .find(cotXml)?.groupValues?.get(1) ?: ""
                // Send emergency event with description via PeatBtle
                btle.sendEvent(PeatEventType.EMERGENCY, eventDescription = emergencyType)
                val nodeId = btle.nodeId
                peatBtleRepository.setEmergencyState(
                    PeatBtleRepository.EmergencyState(
                        isActive = true,
                        sourceNodeId = nodeId,
                        timestamp = System.currentTimeMillis()
                    )
                )
                peatBtleRepository.recordDocumentSent(64)
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
        val btle = peatBtle ?: return

        try {
            // Parse CoT and create compact marker for BLE transmission
            val cotEvent = CotPeatTranslator.parseCotXml(cotXml)
            if (cotEvent != null) {
                // Convert CoT event to PeatMarker and send via mesh
                val peatMarker = PeatMarker(
                    uid = cotEvent.uid,
                    type = cotEvent.type,
                    lat = cotEvent.lat.toFloat(),
                    lon = cotEvent.lon.toFloat(),
                    hae = cotEvent.hae.toFloat(),
                    callsign = cotEvent.callsign ?: callsign,
                    time = cotEvent.time
                )

                btle.sendMarker(peatMarker)
                peatBtleRepository.recordDocumentSent(84)  // ~84 bytes typical marker size
                Log.i(TAG, "MapItem marker sent: uid=${cotEvent.uid}, type=${cotEvent.type}, callsign=${peatMarker.callsign}")
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
     * Parses CoT XML to extract message and broadcasts via PeatBtle.
     *
     * @param cotXml The CoT XML containing the message
     * @param replyToNode If non-zero, originNode of the message being replied to
     * @param replyToTimestamp If non-zero, timestamp of the message being replied to
     */
    private fun handleOutboundChatDocument(cotXml: String, replyToNode: Long = 0, replyToTimestamp: Long = 0) {
        val btle = peatBtle ?: return

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

            // Create PeatChat with threading support
            val chat = PeatChat(
                sender = callsign.take(PeatChat.MAX_SENDER_LENGTH),
                message = message.take(PeatChat.MAX_MESSAGE_LENGTH),
                timestamp = System.currentTimeMillis(),
                originNode = btle.nodeId,
                replyToNode = replyToNode,
                replyToTimestamp = replyToTimestamp
            )

            // Send via mesh (encode and broadcast raw bytes)
            btle.broadcastBytes(PeatChat.encode(chat))
            peatBtleRepository.recordDocumentSent(message.length + 42)  // +12 bytes for replyTo fields

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
        Log.d(TAG, "PeatBtleService onDestroy()")
        isRunning.set(false)

        stopForeground(STOP_FOREGROUND_DETACH)
        stopSyncScheduler()

        try {
            peatBtle?.stopMesh()
            peatBtle?.shutdown()
            peatBtle = null
            Log.d(TAG, "PeatBtle stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping PeatBtle: ${e.message}", e)
        }

        peatBtleRepository.updateMeshState(PeatBtleRepository.PeatMeshState.NOT_INITIALIZED)
        // Clear peers - peat-btle manages peer state, we just display it
        peatBtleRepository.clearPeers()
        dataRepository.setEndpointDisconnected(DataRepository.Endpoint.PEAT_BTLE)

        ioScope.cancel()

        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "PeatBtleService onStartCommand()")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Not a bound service
    }

    companion object {
        private val TAG = "WearTAK-" + PeatBtleService::class.java.simpleName

        /**
         * Shared genesis for WEARTAK encrypted mesh.
         * All nodes (WearOS, ATAK Plugin) must use this same genesis.
         *
         * MESH_ID: 29C916FA
         * Generated: 2026-01-19
         */
        private const val SHARED_GENESIS_BASE64: String =
            "BwBXRUFSVEFL4O7thA03dXXBNkT+gG22aTRGICECcX5RHtOgIdLBrb7tU7LTxkFLCLP+De21IALSXAbi6ZR/c3VXW9lKWacbM0YqfK9n5JXqob7/stIM63nBMLzJiFTGl9E6wcF8Gz0gUerY2JsBAAAA"
        private const val PEAT_SERVICE_NOTIFICATION_ID = 3334
        private const val SYNC_INTERVAL_MS = 5_000L // 5 seconds initial tick

        val isRunning: AtomicBoolean = AtomicBoolean(false)
    }
}
