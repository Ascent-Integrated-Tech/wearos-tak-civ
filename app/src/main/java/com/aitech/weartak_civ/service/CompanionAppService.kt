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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.data.DataRepository
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
            }
        }
    }

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null

    @Volatile
    private var connectedDevice: BluetoothDevice? = null

    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

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
                    return@collect
                }

                val json = when (event) {
                    is CompanionAppRepository.BleOutboundEvent.Marker -> {
                        val env = capRepo.buildMarkerEnvelopeFromSnapshot(event.snapshot)
                        capRepo.envelopeToJson(env)
                    }

                    is CompanionAppRepository.BleOutboundEvent.Emergency -> {
                        val env = capRepo.buildEmergencyEnvelopeFromSnapshot(event.snapshot)
                        capRepo.envelopeToJson(env)
                    }
                }

                Log.d(TAG, "Sending over BLE: $json")
                sendJsonToCompanion(json)
            }
        }
    }

//    @SuppressLint("MissingPermission")
//    private fun startMarkerBleCollector() {
//        serviceScope.launch {
//            capRepo.markerSnapshotsForBle.collect { snapshot ->
//                if (!capRepo.companionAppReady.value) {
//                    Log.i(TAG, "Companion not ready; dropping marker snapshot")
//                    return@collect
//                }
//
//                // Build envelope + JSON and send
//                val env  = capRepo.buildMarkerEnvelopeFromSnapshot(snapshot)
//                val json = capRepo.envelopeToJson(env)
//
//                Log.d(TAG, "Sending marker over BLE: $json")
//                sendJsonToCompanion(json)
//            }
//        }
//    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "CompanionAppService onStartCommand()")
        // TODO: handle explicit ACTION_* if you use start/stop intents
        return START_NOT_STICKY
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "CompanionAppService onDestroy()")
        stopAdvertising()
        closeGattServer()
        isRunning.set(false)
        // TODO: cleanup resources/listeners/threads here (if any)
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

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Companion device connected: ${device.address}")
                    connectedDevice = device
                    capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.CONNECTED)

                    // Optionally send watch_info immediately
                    serviceScope.launch {
                        val env = capRepo.buildWatchInfoEnvelope()
                        val json = capRepo.envelopeToJson(env)
                        sendJsonToCompanion(json)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Companion device disconnected: ${device.address}")
                    if (connectedDevice == device) {
                        connectedDevice = null
                    }
                    capRepo.updateBleConnectionState(CompanionAppRepository.BleConnectionState.DISCONNECTED)
                }
            }
        }

        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE] )
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

                // TODO: parse JSON properly.
                // Minimal v1: detect request_watch_info substring.
                if (json.contains("\"msg_type\":\"request_watch_info\"")) {
                    serviceScope.launch {
                        val env = capRepo.buildWatchInfoEnvelope()
                        val respJson = capRepo.envelopeToJson(env)
                        sendJsonToCompanion(respJson)
                    }
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
            super.onDescriptorWriteRequest(
                device,
                requestId,
                descriptor,
                preparedWrite,
                responseNeeded,
                offset,
                value
            )

            if (descriptor.uuid == capRepo.CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                Log.d(TAG, "CCCD write from ${device.address}")
                // Could track notification enabled/disabled here if needed.
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
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun closeGattServer() {
        gattServer?.close()
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
        val device = connectedDevice ?: run {
            Log.d(TAG, "No connected companion; dropping JSON: $json")
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
        tx.value = bytes

        val ok = server.notifyCharacteristicChanged(device, tx, false)
        if (!ok) {
            Log.w(TAG, "notifyCharacteristicChanged failed")
        } else {
            Log.d(TAG, "Sent JSON to companion: $json")
        }
    }

    companion object {
        private val TAG = "WearTAK-" + CompanionAppService::class.java.simpleName
        private const val NOTIFICATION_ID = 999  // change per service
        val isRunning: AtomicBoolean = AtomicBoolean(false)
    }
}