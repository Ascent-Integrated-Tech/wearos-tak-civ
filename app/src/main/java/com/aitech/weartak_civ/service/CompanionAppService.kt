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

    private fun collectBleState() {
        serviceScope.launch {
            capRepo.bleConnectionState.collect { state ->
                Log.i(TAG, "bleState: $state")
                if (state == CompanionAppRepository.BleConnectionState.SERVICES_READY) {
                    if (!takSocketsClosedForBle) {
                        repo.closeTakSockets()
                        takSocketsClosedForBle = true
                    }
                    repo.setEndpointConnected(DataRepository.Endpoint.COMPANION_APP)

                } else if (
                    state == CompanionAppRepository.BleConnectionState.DISCONNECTED ||
                    state == CompanionAppRepository.BleConnectionState.ERROR ||
                    state == CompanionAppRepository.BleConnectionState.CONNECTION_FAILED ||
                    state == CompanionAppRepository.BleConnectionState.NOT_CONNECTED
                ) {
                    if (takSocketsClosedForBle) {
                        repo.openTakSockets()
                        takSocketsClosedForBle = false
                    }
                    repo.setEndpointDisconnected(DataRepository.Endpoint.COMPANION_APP)
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
        Log.d(TAG, "CompanionAppService onCreate()")
        isRunning.set(true)

        startForegroundServiceCompat(
            NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )

        initBle()
        startBleOutboundCollector()

        serviceScope.launch {
            val sys = repo.snapshotSystem()
            updateBluetoothNameFromSnapshot(sys)
            startAdvertising()
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun updateBluetoothNameFromSnapshot(sys: DataRepository.SystemSnapshot) {
        val adapter = bluetoothAdapter ?: return
        val desiredName = "WT-${sys.callsign}"          // or just sys.callsign

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
                    Log.i(TAG, "Companion not ready; dropping BLE event $event")
                    // TODO: Queue it??
                    return@collect
                }

                val json = when (event) {
                    is CompanionAppRepository.BleOutboundEvent.Pli -> {
                        Log.i(TAG, "PLI - BLE Outbound Collector")
                        val env = capRepo.buildPliEnvelopeFromSnapshot(event.snapshot)
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
        super.onDestroy()
        Log.d(TAG, "CompanionAppService onDestroy()")
        serviceJob.cancel()
        stopAdvertising()
        closeGattServer()
        repo.setEndpointDisconnected(DataRepository.Endpoint.COMPANION_APP)
        isRunning.set(false)
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    override fun onBind(intent: Intent): IBinder? {
        throw UnsupportedOperationException("Not yet implemented")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun initBle() {
        collectBleState()
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

        val added = gattServer?.addService(service) ?: false
        if (!added) {
            Log.w(TAG, "Failed to add companion GATT service")
            capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.ERROR)
        } else {
            Log.d(TAG, "Companion GATT service added")
        }
    }


    // --- Advertising ---------------------------------------------------------

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun startAdvertising() {
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
            .setIncludeDeviceName(true)  // <-- was false
            .addServiceUuid(ParcelUuid(capRepo.COMPANION_SERVICE_UUID))
            .build()

        adv.startAdvertising(settings, data, advertiseCallback)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun stopAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "Companion BLE advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "Companion BLE advertising failed: $errorCode")
            capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.ERROR)
        }
    }

    private fun resetNegotiatedMtu() {
        negotiatedMtu = DEFAULT_ATT_MTU
        notificationsEnabled = false
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
                    Log.d(TAG, "Companion device connected: ${device.address}")
                    if (connectedDevice != null && device.address != connectedDevice?.address) {
                        Log.w(TAG, "Rejecting extra client ${device.address} (already connected=${connectedDevice?.address})")
                        // [Unverified] cancelConnection exists and works reliably on your target OS
                        gattServer?.cancelConnection(device)
                        return
                    }
                    resetNegotiatedMtu()
                    stopAdvertising()
                    connectedDevice = device
                    capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.CONNECTED)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Companion device disconnected: ${device.address}")
                    if (connectedDevice == device) {
                        connectedDevice = null
                    }
                    resetNegotiatedMtu()
                    capRepo.updateBleConnectionState(
                        CompanionAppRepository.BleConnectionState.DISCONNECTED
                    )
                    // Go back to advertising so the host can reconnect
                    restartAdvertisingSafely()
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
                capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.SERVICES_READY)

                // Send initial watch_info AFTER notifications are enabled
                serviceScope.launch {
                    val env = capRepo.buildWatchInfoEnvelope()
                    val json = capRepo.envelopeToJson(env)
                    sendJsonToCompanion(json)
                    // TODO: flushPendingIfReady() // section 9
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

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.d(TAG, "onMtuChanged dev=${device.address} mtu=$mtu")
            if (device == connectedDevice) {
                negotiatedMtu = mtu
                // TODO: flushPendingIfReady() // you’ll add this in section 9
            }
        }
    }
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun closeGattServer() {
        connectedDevice?.let { dev ->
            try {
                Log.d(TAG, "Cancelling GATT connection to ${dev.address}")
                gattServer?.cancelConnection(dev)
            } catch (t: Throwable) {
                Log.w(TAG, "cancelConnection failed", t)
            }
        }

        try {
            Log.d(TAG, "Closing GATT server")
            gattServer?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "gattServer.close() failed", t)
        }

        gattServer = null
        connectedDevice = null
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