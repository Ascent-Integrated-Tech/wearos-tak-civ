/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Intent
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.data.DataRepository
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import com.aitech.weartak_civ.data.CompanionAppRepository.BleConnectionState.DISCONNECTED
import com.aitech.weartak_civ.data.CompanionAppRepository.BleConnectionState.CONNECTION_FAILED
import com.aitech.weartak_civ.data.CompanionAppRepository.BleConnectionState.NOT_CONNECTED
import com.aitech.weartak_civ.data.CompanionAppRepository.BleConnectionState.ERROR
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import kotlinx.coroutines.flow.map


/**
 * CompanionAppService:
 * - Foreground start via Utils.startForegroundServiceCompat
 * - START_NOT_STICKY
 * - Standardized logging TAG
 * - Companion isRunning flag
 *
 * Rename class + adjust NOTIFICATION_ID. Add your work loop (HandlerThread/Coroutine) if needed.
 */

class CompanionAppService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val gson = Gson()

    @Volatile private var activeAddr: String? = null
    private fun isActive(device: BluetoothDevice): Boolean =
        activeAddr != null && device.address == activeAddr

    private val capRepo by lazy {
        try {
            (applicationContext as WearTakApplication).capRepo
        } catch (_: Throwable) {
            CompanionAppRepository(applicationContext)
        }
    }

    private val repo by lazy {
        try {
            (applicationContext as WearTakApplication).repo
        } catch (_: Throwable) {
            DataRepository(applicationContext)
        }
    }

    @SuppressLint("MissingPermission")
    private fun collectBleState() {
        serviceScope.launch {
            capRepo.bleConnectionState
                .map { it == CompanionAppRepository.BleConnectionState.SERVICES_READY }
                .collect { ready ->
                    Log.i(TAG, "bleReady: $ready (state=${capRepo.bleConnectionState.value})")

                    if (ready) repo.setCompanionAppConnected()
                    else repo.setCompanionAppDisconnected()
                }
        }

        // keep your advertising logic separate so it doesn't also spam endpoint updates
        serviceScope.launch {
            capRepo.bleConnectionState.collect { state ->
                if (state in listOf(DISCONNECTED, ERROR, CONNECTION_FAILED, NOT_CONNECTED)) {
                    if (connectedDevice == null) startAdvertising()
                }
            }
        }
    }

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null

    private val pendingJson = ArrayDeque<String>(MAX_PENDING_JSON)
    private val pendingLock = Any()

    @Volatile private var gattServiceAdded: Boolean = false
    @Volatile private var handshakeWatchdogArmed: Boolean = false
    private val watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val HANDSHAKE_TIMEOUT_MS = 4_000L

    @SuppressLint("MissingPermission")
    private fun armHandshakeWatchdog(reason: String) {
        if (handshakeWatchdogArmed) return
        handshakeWatchdogArmed = true
        Log.d(TAG, "Handshake watchdog armed ($reason)")

        watchdogHandler.postDelayed(@RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT]) {
            handshakeWatchdogArmed = false

            // If we never reached SERVICES_READY, force a reset
            val stuck = (connectedDevice != null && !notificationsEnabled)
            if (stuck) {
                Log.w(TAG, "Handshake stuck in CONNECTED (no CCCD). Forcing disconnect/reset.")
                forceBleReset("handshake_timeout")
            }
        }, HANDSHAKE_TIMEOUT_MS)
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    private fun forceBleReset(why: String) {
        Log.w(TAG, "forceBleReset: $why")

        // Drop state
        capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.DISCONNECTING)
        connectedDevice = null
        notificationsEnabled = false
        negotiatedMtu = DEFAULT_ATT_MTU
        helloSentForConnection = false
        clearPendingQueue()
        disarmHandshakeWatchdog()

        // Tear down + rebuild server
        closeGattServer()
        initBle()

        // Resume advertising (if available)
        startAdvertising()

        capRepo.updateBleConnectionState(NOT_CONNECTED)
    }

    private fun disarmHandshakeWatchdog() {
        handshakeWatchdogArmed = false
        watchdogHandler.removeCallbacksAndMessages(null)
    }

    private fun enqueueJson(json: String) {
        synchronized(pendingLock) {
            while (pendingJson.size >= MAX_PENDING_JSON) {
                pendingJson.removeFirst()
            }
            pendingJson.addLast(json)
        }
    }

    private fun drainPending(): List<String> {
        synchronized(pendingLock) {
            val out = pendingJson.toList()
            pendingJson.clear()
            return out
        }
    }

    private fun clearPendingQueue() {
        synchronized(pendingLock) { pendingJson.clear() }
    }

    private fun bleSendReady(): Boolean {
        return (connectedDevice != null &&
                gattServer != null &&
                txCharacteristic != null &&
                notificationsEnabled &&
                capRepo.companionAppReady.value)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun flushPendingIfReady() {
        if (!bleSendReady()) return
        val batch = drainPending()
        for (j in batch) {
            sendJsonToCompanion(j) // will still enforce MTU payload cap
        }
    }

    @Volatile
    private var connectedDevice: BluetoothDevice? = null
    @Volatile private var negotiatedMtu: Int = DEFAULT_ATT_MTU
    @Volatile private var notificationsEnabled: Boolean = false
    @Volatile private var helloSentForConnection: Boolean = false
    @Volatile private var takSocketsClosedForBle = false

    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    private fun maxNotifyPayloadBytes(): Int {
        // ATT payload max for notifications = MTU - 3
        val mtu = negotiatedMtu.coerceAtLeast(DEFAULT_ATT_MTU)
        val attPayload = (mtu - 3).coerceAtLeast(0)
        return minOf(TARGET_JSON_BYTES, attPayload)
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    override fun onCreate() {
        super.onCreate()
        Log.w(TAG, "Repo identity = ${System.identityHashCode(repo)}")
        Log.d(TAG, "CompanionAppService onCreate()")
        isRunning.set(true)

        // 1) HARD RESET — this instance must never inherit BLE state
        connectedDevice = null
        negotiatedMtu = DEFAULT_ATT_MTU
        notificationsEnabled = false
        helloSentForConnection = false
        clearPendingQueue()

        capRepo.updateBleConnectionState(NOT_CONNECTED)

        // 2) Foreground FIRST (keeps process alive during BLE init)
        startForegroundServiceCompat(
            NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )

        // 3) Collect state *before* BLE init so transitions are not missed
        collectBleState()

        // 4) Initialize BLE server + GATT service
        initBle()

        // 5) Start outbound BLE collector
        startBleOutboundCollector()

        // 6) Finish setup async
        serviceScope.launch {
            val sys = repo.snapshotSystem()
            updateBluetoothNameFromSnapshot(sys)

            // IMPORTANT:
            // Only advertise if we are NOT already connected.
            // If we ARE already connected, the handshake watchdog
            // will force a clean reconnect if CCCD never arrives.
            if (connectedDevice == null) {
                startAdvertising()
            } else {
                Log.w(TAG, "Service started while already connected; waiting for handshake")
                armHandshakeWatchdog("onCreate_already_connected")
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun updateBluetoothNameFromSnapshot(sys: DataRepository.SystemSnapshot) {
        // Don't set Bluetooth name if HiveBtleService is running - it handles the name
        if (HiveBtleService.isRunning.get()) {
            Log.d(TAG, "Skipping BT name update - HiveBtleService handles it")
            return
        }

        val adapter = bluetoothAdapter ?: return
        // Use callsign directly (no WT- prefix) for consistency with HiveBtleService
        val desiredName = sys.callsign

        if (adapter.name != desiredName) {
            try {
                adapter.name = desiredName
                Log.d(TAG, "Set BT adapter name to $desiredName")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to set BT adapter name", t)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleOutboundCollector() {
        serviceScope.launch {
            capRepo.outboundBleEvents.collect { event ->
                if (!capRepo.companionAppReady.value) {
                    Log.i(TAG, "Companion not ready; queueing BLE event $event")
                    // build json as normal below and enqueueJson(json)
                    // but we need json first, so don't return here
                }

                val json = when (event) {
                    is CompanionAppRepository.BleOutboundEvent.Pli -> {
                        Log.i(TAG, "PLI - BLE Outbound Collector")
                        val env = capRepo.buildPliEnvelopeFromSnapshot(snapshot = event.snapshot, repIntSeconds = repo.almightyReportingIntervalSecs.value)
                        capRepo.envelopeToJson(env)
                    }

                    is CompanionAppRepository.BleOutboundEvent.Marker -> {
                        Log.i(TAG, "MARKER - BLE Outbound Collector")
                        val env = capRepo.buildMarkerEnvelopeFromSnapshot(event.snapshot)
                        capRepo.envelopeToJson(env)
                    }

                    is CompanionAppRepository.BleOutboundEvent.Emergency -> {
                        Log.i(TAG, "EMERGENCY - BLE Outbound Collector")
                        val env = capRepo.buildEmergencyEnvelopeFromSnapshot(event.snapshot)
                        capRepo.envelopeToJson(env)
                    }

                    is CompanionAppRepository.BleOutboundEvent.Chat -> {
                        Log.i(TAG, "CHAT - BLE Outbound Collector uid=${event.snapshot.uid}")
                        val env = capRepo.buildChatEnvelopeFromSnapshot(snapshot = event.snapshot)
                        capRepo.envelopeToJson(env)
                    }

                    is CompanionAppRepository.BleOutboundEvent.ReportInt -> {
                        Log.i(TAG, "REPORTING INT - BLE Outbound Collector")
                        val env = capRepo.buildReportIntPayload(event.interval)
                        capRepo.envelopeToJson(env)
                    }

                }

                if (!capRepo.companionAppReady.value || !notificationsEnabled) {
                    Log.i(TAG, "Companion not ready; queueing BLE event $event")
                    enqueueJson(json)
                    return@collect
                }
                flushPendingIfReady()
                sendJsonToCompanion(json)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "CompanionAppService onStartCommand()")
        // TODO: handle explicit ACTION_* if you use start/stop intents
        return START_NOT_STICKY
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
    override fun onDestroy() {
        Log.d(TAG, "CompanionAppService onDestroy()")

        // 1) Update app-level state FIRST (avoid stale SERVICES_READY in repo)
        capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.DISCONNECTING)
        notificationsEnabled = false
        negotiatedMtu = DEFAULT_ATT_MTU
        clearPendingQueue()

        // 2) Stop outbound work
        serviceJob.cancel()

        // 3) Stop advertising + close the server + force disconnect
        stopAdvertising()
        closeGattServer() // should cancel connection + close server

        // 4) Finalize state
        capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.NOT_CONNECTED)
        repo.setCompanionAppDisconnected()
        isRunning.set(false)

        // 5) Foreground teardown
        stopForeground(STOP_FOREGROUND_DETACH)
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        throw UnsupportedOperationException("Not yet implemented")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun initBle() {
        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null || bluetoothAdapter?.isEnabled != true) {
            Log.w(TAG, "Bluetooth not available or disabled")
            capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.ERROR)
            return
        }

        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        gattServer = bluetoothManager?.openGattServer(this, gattServerCallback)

        if (gattServer == null) {
            Log.w(TAG, "Failed to open GATT server")
            capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.ERROR)
            return
        }

        // Build GATT service and characteristics using constants from capRepo
        val service = BluetoothGattService(
            capRepo.COMPANION_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        txCharacteristic = BluetoothGattCharacteristic(
            capRepo.TX_TO_COMPANION_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            val cccd = BluetoothGattDescriptor(
                capRepo.CLIENT_CHARACTERISTIC_CONFIG_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            addDescriptor(cccd)
        }

        rxCharacteristic = BluetoothGattCharacteristic(
            capRepo.RX_FROM_COMPANION_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        service.addCharacteristic(txCharacteristic)
        service.addCharacteristic(rxCharacteristic)

        gattServiceAdded = false
        val added = gattServer?.addService(service) ?: false
        if (!added) {
            Log.w(TAG, "Failed to add companion GATT service")
            capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.ERROR)
        } else {
            gattServiceAdded = true
            Log.d(TAG, "Companion GATT service added")
        }
    }


    // --- Advertising ---------------------------------------------------------

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun startAdvertising() {
        // DISABLED: CompanionAppService advertising is disabled when using HIVE mesh.
        // HiveBtleService handles all BLE advertising to prevent duplicates in scanners.
        // The companion app can still receive connections via GATT server without advertising.
        Log.d(TAG, "startAdvertising(): DISABLED - HiveBtleService handles advertising")
        return

        // Original code below is unreachable but kept for reference
        @Suppress("UNREACHABLE_CODE")
        if (connectedDevice != null) return

        if (advertiser == null) {
            advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        }

        if (advertising) {
            Log.d(TAG, "startAdvertising(): already advertising; skipping")
            capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.ADVERTISING)
            return
        }

        val adv = advertiser ?: run {
            Log.w(TAG, "No BLE advertiser available")
            return
        }

        capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.ADVERTISING)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(capRepo.COMPANION_SERVICE_UUID))
            .build()

        adv.startAdvertising(settings, data, advertiseCallback)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun stopAdvertising() {
        if (!advertising) return
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } finally {
            advertising = false
        }
    }

    @Volatile private var advertising: Boolean = false

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "Companion BLE advertising started")
            advertising = true
        }

        override fun onStartFailure(errorCode: Int) {
            Log.d(TAG, "onStartFailure($errorCode): advertising=$advertising")

            // ADVERTISE_FAILED_ALREADY_STARTED == 3
            if (errorCode == 3) {
                Log.w(TAG, "Advertising already started; keeping ADVERTISING state")
                advertising = true
                capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.ADVERTISING)
                return
            }

            advertising = false
            capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.ERROR)
        }
    }

    private fun resetNegotiatedMtu() {
        negotiatedMtu = DEFAULT_ATT_MTU
        notificationsEnabled = false
        helloSentForConnection = false
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)

            Log.d(TAG, "onConnectionStateChange: dev=${device.address} status=$status newState=$newState")


            if (status != BluetoothGatt.GATT_SUCCESS && newState != BluetoothProfile.STATE_CONNECTED) {
                // e.g. 133 or other error – treat as failure, reset and re-advertise Log.w(TAG, "Connection error status=$status, resetting BLE state")
                connectedDevice = null
                resetNegotiatedMtu()
                capRepo.updateBleConnectionState(
                    CompanionAppRepository.BleConnectionState.CONNECTION_FAILED
                )
                restartAdvertisingSafely()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    val current = activeAddr
                    if (current != null && current != device.address) {
                        Log.w(TAG, "Rejecting ${device.address}; already active=$current")
                        gattServer?.cancelConnection(device)
                        return
                    }

                    activeAddr = device.address
                    connectedDevice = device

                    // reset per-session flags
                    negotiatedMtu = DEFAULT_ATT_MTU
                    notificationsEnabled = false
                    helloSentForConnection = false

                    stopAdvertising()
                    capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.CONNECTED)
                    armHandshakeWatchdog("connected ${device.address}")
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (!isActive(device)) {
                        Log.w(TAG, "Ignoring DISCONNECTED from stale device=${device.address} active=$activeAddr")
                        return
                    }


                    Log.d(TAG, "Active device disconnected: ${device.address}")
                    activeAddr = null
                    connectedDevice = null

                    // reset session flags
                    negotiatedMtu = DEFAULT_ATT_MTU
                    notificationsEnabled = false
                    helloSentForConnection = false

                    capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.DISCONNECTED)
                    startAdvertising()
                    disarmHandshakeWatchdog()
                }
            }
        }

        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
        private fun restartAdvertisingSafely() {
            try {
                stopAdvertising()
            } catch (t: Throwable) {
                Log.w(TAG, "stopAdvertising failed in restartAdvertisingSafely", t)
            }
            startAdvertising()
        }

        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            super.onCharacteristicWriteRequest(
                device,
                requestId,
                characteristic,
                preparedWrite,
                responseNeeded,
                offset,
                value
            )

            if (characteristic.uuid == capRepo.RX_FROM_COMPANION_UUID) {
                val json = value.toString(Charsets.UTF_8)
                Log.d(TAG, "RX from companion: $json")

                // Minimal but robust: parse just msg_type, regardless of spacing/formatting
                try {
                    val base = gson.fromJson(
                        json,
                        CompanionAppRepository.EnvelopeBase::class.java
                    )
                    if (base.msg_type == "request_watch_info") {
                        serviceScope.launch {
                            val env = capRepo.buildWatchInfoEnvelope()
                            val respJson = capRepo.envelopeToJson(env)
                            sendJsonToCompanion(respJson)
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to parse RX JSON: $json", t)
                    // If parsing fails, just ignore; we can extend this later for other msg_types.
                }

                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        null
                    )
                }
            } else {
                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        offset,
                        null
                    )
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val server = gattServer

            fun respond(status: Int) {
                if (responseNeeded && server != null) {
                    server.sendResponse(device, requestId, status, offset, null)
                }
            }

            // Only handle CCCD
            if (descriptor.uuid != capRepo.CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                respond(BluetoothGatt.GATT_FAILURE)
                return
            }

            // Only accept from active device
            if (device != connectedDevice) {
                Log.i(TAG, "CCCD write ignored: device ${device.address} != connected ${connectedDevice?.address}")
                respond(BluetoothGatt.GATT_FAILURE)
                return
            }

            // Only CCCD on TX characteristic
            if (descriptor.characteristic.uuid != capRepo.TX_TO_COMPANION_UUID) {
                Log.i(TAG, "CCCD write ignored: not TX characteristic (${descriptor.characteristic.uuid})")
                respond(BluetoothGatt.GATT_FAILURE)
                return
            }

            // Persist descriptor value (helps some stacks)
            descriptor.value = value

            val enableNoti = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            val disableNoti = value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)

            Log.d(TAG, "CCCD write from ${device.address}, enable=$enableNoti disable=$disableNoti")

            if (enableNoti) {
                notificationsEnabled = true
                disarmHandshakeWatchdog()
                capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.SERVICES_READY)

                serviceScope.launch {
                    if (!helloSentForConnection) {
                        helloSentForConnection = true
                        val env = capRepo.buildWatchInfoEnvelope()
                        val json = capRepo.envelopeToJson(env)
                        sendJsonToCompanion(json)
                    }
                    flushPendingIfReady()
                }

                respond(BluetoothGatt.GATT_SUCCESS)
                return
            }

            if (disableNoti) {
                notificationsEnabled = false
                capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.CONNECTED)
                respond(BluetoothGatt.GATT_SUCCESS)
                return
            }

            respond(BluetoothGatt.GATT_FAILURE)
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            if (!isActive(device)) {
                Log.w(TAG, "Ignoring MTU from stale device=${device.address} active=$activeAddr")
                return
            }
            negotiatedMtu = mtu
            Log.d(TAG, "MTU now $mtu for ${device.address}")
            serviceScope.launch { flushPendingIfReady() }
        }
    }
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun closeGattServer() {
        val server = gattServer
        val dev = connectedDevice

        // Drop local flags immediately
        connectedDevice = null
        notificationsEnabled = false
        negotiatedMtu = DEFAULT_ATT_MTU

        if (server != null && dev != null) {
            try {
                Log.d(TAG, "Cancelling GATT connection to ${dev.address}")
                server.cancelConnection(dev)
            } catch (t: Throwable) {
                Log.w(TAG, "cancelConnection failed", t)
            }
        }

        try {
            Log.d(TAG, "Closing GATT server")
            server?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "gattServer.close() failed", t)
        } finally {
            gattServer = null
        }
    }

    // --- Public send API: JSON -> Notify ------------------------------------

    /**
     * Public entrypoint to send a JSON envelope over the TX characteristic.
     * You can call this from CotDispatchService (via some reference) once you have
     * a MarkerSnapshot -> Envelope -> JSON.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendJsonToCompanion(json: String) {
        if (!capRepo.companionAppReady.value) {
            Log.d(TAG, "Companion not ready (state=${capRepo.bleConnectionState.value}); dropping JSON")
            return
        }
        if (!notificationsEnabled) {
            Log.d(TAG, "Notifications not enabled; dropping/queueing JSON")
            return
        }

        val device = connectedDevice ?: run {
            Log.d(TAG, "Not connected companion; dropping JSON: $json")
            return
        }
        val tx = txCharacteristic ?: run {
            Log.w(TAG, "TX characteristic not ready")
            return
        }
        val server = gattServer ?: run {
            Log.w(TAG, "Gatt server not ready")
            return
        }

        val bytes = json.toByteArray(Charsets.UTF_8)
        val maxPayload = maxNotifyPayloadBytes()

        if (bytes.size > maxPayload) {
            Log.w(TAG, "BLE JSON too long (${bytes.size} > $maxPayload, mtu=$negotiatedMtu); dropping/queueing")
            // either return OR queue (see section 9)
            return
        }

        tx.value = bytes

        try {
            val ok = server.notifyCharacteristicChanged(device, tx, false)
            if (!ok) {
                Log.w(TAG, "notifyCharacteristicChanged returned false, will keep connection but drop this msg")
            } else {
                Log.d(TAG, "Sent JSON to companion: $json")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "notifyCharacteristicChanged threw", t)
            // [Inference] optional: treat as connection failure and reset:
            // capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.ERROR)
            // restartAdvertisingSafely()
        }
    }

    companion object {
        private val TAG = "WearTAK-" + CompanionAppService::class.java.simpleName
        private const val NOTIFICATION_ID = 999  // change per service
        val isRunning: AtomicBoolean = AtomicBoolean(false)
        private const val DEFAULT_ATT_MTU = 23
        private const val TARGET_JSON_BYTES = 512
        private const val MAX_PENDING_JSON = 64
    }
}