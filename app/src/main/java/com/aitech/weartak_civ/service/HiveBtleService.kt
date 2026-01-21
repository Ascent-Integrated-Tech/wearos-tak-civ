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
import com.aitech.weartak_civ.events.HiveDocumentEvent
import com.aitech.weartak_civ.events.TakChatEvent
import com.aitech.weartak_civ.hive.CotHiveTranslator
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import android.util.Base64
import com.revolveteam.hive.DeviceIdentity
import com.revolveteam.hive.HiveBtle
import com.revolveteam.hive.HiveChat
import com.revolveteam.hive.HiveDocument
import com.revolveteam.hive.HiveEventType
import com.revolveteam.hive.HiveLocation
import com.revolveteam.hive.HiveMarker
import com.revolveteam.hive.HiveMeshListener
import com.revolveteam.hive.HivePeer
import com.revolveteam.hive.MembershipPolicy
import com.revolveteam.hive.MeshGenesis
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

            Log.d(TAG, "Initializing HiveBtle with encryption: meshId=${genesis.meshId}, callsign=$callsign")

            // Create encrypted HiveBtle instance
            // IMPORTANT: Pass genesis.meshId for BLE advertising!
            hiveBtle = HiveBtle(
                context = applicationContext,
                meshId = genesis.meshId,  // Use genesis meshId for advertising
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
                val identity = DeviceIdentity.fromPrivateKey(privateKey)
                Log.d(TAG, "Loaded existing DeviceIdentity: nodeId=${String.format("%08X", identity.nodeId)}")
                identity
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
        val encoded = Base64.encodeToString(identity.privateKey, Base64.NO_WRAP)
        runBlocking { dataRepository.storeHiveDeviceIdentity(encoded) }
        Log.i(TAG, "Generated and stored new DeviceIdentity: nodeId=${String.format("%08X", identity.nodeId)}")
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
        val genesis = MeshGenesis.decode(bytes)
        if (genesis != null) {
            Log.i(TAG, "Using SHARED WEARTAK genesis: meshId=${genesis.meshId}")
            meshId = genesis.meshId
            return genesis
        }

        // Fallback if hardcoded genesis fails (should not happen)
        Log.e(TAG, "Failed to decode SHARED_GENESIS_BASE64! Creating new genesis.")
        return createAndStoreGenesis(identity)
    }

    private fun createAndStoreGenesis(identity: DeviceIdentity): MeshGenesis {
        // Create genesis with OPEN policy - WEARTAK mesh for testing
        val genesis = MeshGenesis.create(
            meshName = "WEARTAK",
            identity = identity,
            policy = MembershipPolicy.OPEN
        )
        val encoded = Base64.encodeToString(genesis.encode(), Base64.NO_WRAP)
        runBlocking { dataRepository.storeHiveMeshGenesis(encoded) }

        // Log the genesis for sharing with ATAK Plugin team
        Log.i(TAG, "=== SHAREABLE GENESIS (copy this for ATAK Plugin) ===")
        Log.i(TAG, "GENESIS_BASE64: $encoded")
        Log.i(TAG, "MESH_ID: ${genesis.meshId}")
        Log.i(TAG, "=== END GENESIS ===")

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
     * Called when a peer sends an event (Emergency, ACK, etc.).
     */
    override fun onPeerEvent(peer: HivePeer, eventType: HiveEventType) {
        Log.i(TAG, "Peer event from ${peer.displayName()}: $eventType")

        ioScope.launch {
            when (eventType) {
                HiveEventType.EMERGENCY -> {
                    Log.w(TAG, "PEER SOS received from ${peer.displayName()}")
                    val timestamp = System.currentTimeMillis()
                    hiveBtleRepository.startEmergency(peer.nodeId, timestamp)
                    hiveBtleRepository.setPeerEmergency(peer.nodeId, true, timestamp)
                    // UI observes peersInEmergency StateFlow for flash/indicator
                }
                HiveEventType.ACK -> {
                    Log.i(TAG, "ACK received from ${peer.displayName()}")
                    hiveBtleRepository.addEmergencyAck(peer.nodeId)
                }
                else -> {
                    // Regular sync (NONE, PING, etc.), no special handling
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
