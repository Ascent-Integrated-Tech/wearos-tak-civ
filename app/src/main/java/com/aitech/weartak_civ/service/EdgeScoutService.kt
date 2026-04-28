/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
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
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.EdgeScoutConnectionState
import com.aitech.weartak_civ.data.EdgeScoutDiscoveryTransport
import com.aitech.weartak_civ.data.EdgeScoutDeviceCandidate
import com.aitech.weartak_civ.data.EdgeScoutRepository
import com.aitech.weartak_civ.data.EdgeScoutRidObservation
import com.aitech.weartak_civ.data.EdgeScoutScannerInfo
import com.aitech.weartak_civ.data.looksLikeEdgeScoutName
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class EdgeScoutService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val gson = Gson()

    private val edgeScoutRepo by lazy {
        try {
            (applicationContext as WearTakApplication).edgeScoutRepo
        } catch (_: Throwable) {
            EdgeScoutRepository(applicationContext)
        }
    }

    private val dataRepository by lazy {
        try {
            (applicationContext as WearTakApplication).repo
        } catch (_: Throwable) {
            DataRepository(applicationContext)
        }
    }

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var socket: BluetoothSocket? = null
    private var readerJob: Job? = null
    private var reconnectJob: Job? = null
    private var bleScanTimeoutJob: Job? = null
    private var classicScanTimeoutJob: Job? = null
    private var bondTimeoutJob: Job? = null
    private var pendingConnectAddress: String? = null
    private var currentDevice: BluetoothDevice? = null
    private var currentLogUri: android.net.Uri? = null
    private var manualDisconnectRequested = false
    private var receiverRegistered = false

    private val candidateMap = ConcurrentHashMap<String, EdgeScoutDeviceCandidate>()
    private val alertedUasIds = ConcurrentHashMap.newKeySet<String>()
    private var classicDevicesSeen = 0
    private var bleAdvertisementsSeen = 0
    private var classicScanFinished = false
    private var bleScanFinished = false

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        startForegroundServiceCompat(NOTIFICATION_ID, importance = NotificationManager.IMPORTANCE_LOW)
        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        edgeScoutRepo.updateConnectionState(EdgeScoutConnectionState.IDLE, "Edge Scout idle")
        registerBluetoothReceiver()
        serviceScope.launch {
            validateStoredSelection()
            refreshSelectedDeviceCandidate()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SCAN -> {
                manualDisconnectRequested = false
                serviceScope.launch { startDiscovery() }
            }

            ACTION_CONNECT_SELECTED -> {
                manualDisconnectRequested = false
                serviceScope.launch { connectSelectedDevice() }
            }

            ACTION_SELECT_AND_CONNECT -> {
                manualDisconnectRequested = false
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                val name = intent.getStringExtra(EXTRA_DEVICE_NAME)
                serviceScope.launch { selectAndConnect(address, name) }
            }

            ACTION_DISCONNECT -> {
                manualDisconnectRequested = true
                disconnectSocket("Disconnected")
            }

            ACTION_STOP -> {
                manualDisconnectRequested = true
                stopSelf()
            }

            ACTION_CLEAR_SELECTION -> {
                manualDisconnectRequested = true
                serviceScope.launch { clearSelectedDeviceSelection() }
            }

            else -> Unit
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        manualDisconnectRequested = true
        reconnectJob?.cancel()
        readerJob?.cancel()
        bondTimeoutJob?.cancel()
        cancelDiscovery()
        stopBleScan()
        disconnectSocket("Edge Scout stopped")
        unregisterBluetoothReceiver()
        edgeScoutRepo.updateConnectionState(EdgeScoutConnectionState.IDLE, "Edge Scout stopped")
        serviceJob.cancel()
        isRunning.set(false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun selectAndConnect(address: String?, name: String?) {
        if (address.isNullOrBlank()) {
            edgeScoutRepo.updateConnectionState(
                EdgeScoutConnectionState.ERROR,
                "No Edge Scout device selected",
            )
            return
        }
        Log.i(TAG, "Direct select/connect requested mac=$address name=${name ?: "<unknown>"}")
        val knownCandidate = candidateMap[address]
        if (knownCandidate != null &&
            knownCandidate.transport == EdgeScoutDiscoveryTransport.BLE &&
            !knownCandidate.supportsSerialConnection
        ) {
            edgeScoutRepo.updateConnectionState(
                EdgeScoutConnectionState.ERROR,
                "${knownCandidate.name ?: address} is visible over BLE, but no Classic serial channel was advertised",
            )
            return
        }
        edgeScoutRepo.storeSelectedDevice(address, name)
        connectToAddress(address)
    }

    private suspend fun connectSelectedDevice() {
        val selected = edgeScoutRepo.selectedDevice.value
        if (selected == null) {
            startDiscovery()
            return
        }
        connectToAddress(selected.address)
    }

    private suspend fun validateStoredSelection() {
        val selected = edgeScoutRepo.selectedDevice.value ?: return
        val bondedDevice = try {
            bluetoothAdapter?.bondedDevices.orEmpty().firstOrNull { it.address == selected.address }
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to inspect bonded devices for stored selection", t)
            null
        }

        val shouldClear = when {
            bondedDevice != null -> {
                !bondedDevice.toCandidate(
                    selectedAddress = selected.address,
                    lastSeenAtMs = System.currentTimeMillis(),
                ).isLikelyMatch
            }

            !selected.name.isNullOrBlank() -> !looksLikeEdgeScoutName(selected.name)
            else -> false
        }

        if (shouldClear) {
            clearSelectedDeviceSelection("Cleared stale non-Edge-Scout selection")
        }
    }

    private suspend fun clearSelectedDeviceSelection(message: String = "Device selection cleared") {
        pendingConnectAddress = null
        edgeScoutRepo.clearSelectedDevice()
        candidateMap.entries.removeIf { !it.value.isLikelyMatch }
        refreshSelectedDeviceCandidate()
        val nextState = when (edgeScoutRepo.connectionState.value) {
            EdgeScoutConnectionState.CONNECTED -> EdgeScoutConnectionState.CONNECTED
            EdgeScoutConnectionState.SCANNING -> EdgeScoutConnectionState.SCANNING
            else -> EdgeScoutConnectionState.IDLE
        }
        edgeScoutRepo.updateConnectionState(nextState, message)
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectToAddress(address: String) {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            edgeScoutRepo.updateConnectionState(
                EdgeScoutConnectionState.ERROR,
                "Bluetooth adapter unavailable",
            )
            return
        }
        if (!adapter.isEnabled) {
            edgeScoutRepo.updateConnectionState(
                EdgeScoutConnectionState.ERROR,
                "Bluetooth is disabled",
            )
            return
        }

        val device = try {
            adapter.getRemoteDevice(address)
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to load device for $address", t)
            edgeScoutRepo.updateConnectionState(
                EdgeScoutConnectionState.ERROR,
                "Unable to load selected device",
            )
            return
        }

        pendingConnectAddress = address
        currentDevice = device
        refreshSelectedDeviceCandidate()
        cancelDiscovery()
        Log.i(
            TAG,
            "Resolved remote device mac=${device.address} name=${device.name ?: "<unknown>"} bond=${bondStateLabel(device.bondState)} uuids=${classicUuidSummary(device)}",
        )

        val selected = edgeScoutRepo.selectedDevice.value
        val candidate = device.toCandidate(
            selectedAddress = edgeScoutRepo.selectedDevice.value?.address,
            lastSeenAtMs = System.currentTimeMillis(),
        )
        val isManualTargetMac = selected?.address == address && selected.name.isNullOrBlank()
        if (!candidate.isLikelyMatch && !isManualTargetMac) {
            Log.w(
                TAG,
                "Rejecting direct connect mac=${device.address} name=${candidate.name ?: "<unknown>"} because candidate is not likely Edge Scout",
            )
            pendingConnectAddress = null
            currentDevice = null
            edgeScoutRepo.clearSelectedDevice()
            edgeScoutRepo.updateConnectionState(
                EdgeScoutConnectionState.ERROR,
                "${safeDeviceLabel(device)} does not look like an Edge Scout scanner",
            )
            return
        }
        if (isManualTargetMac && !candidate.isLikelyMatch) {
            Log.i(
                TAG,
                "Proceeding with manual target MAC ${device.address} despite missing Edge Scout metadata",
            )
        }

        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            Log.i(TAG, "Device mac=${device.address} not bonded; attempting createBond()")
            edgeScoutRepo.updateConnectionState(
                EdgeScoutConnectionState.BONDING,
                "Pairing with ${safeDeviceLabel(device)}",
            )
            val bondStarted = try {
                device.createBond()
            } catch (t: Throwable) {
                Log.e(TAG, "createBond failed for ${device.address}", t)
                false
            }
            Log.i(TAG, "createBond result mac=${device.address} started=$bondStarted bond=${bondStateLabel(device.bondState)}")
            if (!bondStarted && device.bondState != BluetoothDevice.BOND_BONDED) {
                edgeScoutRepo.updateConnectionState(
                    EdgeScoutConnectionState.ERROR,
                    "Unable to start pairing",
                )
            } else {
                scheduleBondTimeout(device)
            }
            return
        }

        connectSocket(device)
    }

    @SuppressLint("MissingPermission")
    private fun connectSocket(device: BluetoothDevice) {
        reconnectJob?.cancel()
        readerJob?.cancel()
        bondTimeoutJob?.cancel()
        closeSocketQuietly()

        serviceScope.launch {
            Log.i(
                TAG,
                "Opening RFCOMM socket mac=${device.address} name=${device.name ?: "<unknown>"} uuid=$SPP_UUID bond=${bondStateLabel(device.bondState)}",
            )
            edgeScoutRepo.updateConnectionState(
                EdgeScoutConnectionState.CONNECTING,
                "Connecting to ${safeDeviceLabel(device)}",
            )

            val adapter = bluetoothAdapter
            if (adapter == null) {
                edgeScoutRepo.updateConnectionState(
                    EdgeScoutConnectionState.ERROR,
                    "Bluetooth adapter unavailable",
                )
                return@launch
            }

            try {
                adapter.cancelDiscovery()
            } catch (t: Throwable) {
                Log.w(TAG, "cancelDiscovery before connect failed", t)
            }

            try {
                val newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                Log.i(TAG, "RFCOMM socket created mac=${device.address}; calling connect()")
                newSocket.connect()
                socket = newSocket
                currentDevice = device
                currentLogUri = null
                edgeScoutRepo.setActiveLogFileName(null)
                Log.i(TAG, "RFCOMM connect succeeded mac=${device.address}")
                edgeScoutRepo.updateConnectionState(
                    EdgeScoutConnectionState.CONNECTED,
                    "Connected to ${safeDeviceLabel(device)}",
                )
                refreshSelectedDeviceCandidate()
                startReaderLoop(device, newSocket)
            } catch (t: Throwable) {
                Log.e(TAG, "RFCOMM connect failed for ${device.address}", t)
                edgeScoutRepo.updateConnectionState(
                    EdgeScoutConnectionState.ERROR,
                    "Unable to open Edge Scout serial connection",
                )
                scheduleReconnect()
            }
        }
    }

    private fun startReaderLoop(device: BluetoothDevice, socket: BluetoothSocket) {
        readerJob?.cancel()
        readerJob = serviceScope.launch {
            try {
                BufferedReader(InputStreamReader(socket.inputStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        handleIncomingLine(device, line)
                    }
                }
                Log.w(TAG, "RFCOMM stream ended for ${device.address}")
            } catch (t: Throwable) {
                if (!manualDisconnectRequested) {
                    Log.e(TAG, "Reader loop failed for ${device.address}", t)
                }
            } finally {
                if (!manualDisconnectRequested) {
                    edgeScoutRepo.updateConnectionState(
                        EdgeScoutConnectionState.DISCONNECTED,
                        "Disconnected from ${safeDeviceLabel(device)}",
                    )
                    scheduleReconnect()
                }
                closeSocketQuietly()
            }
        }
    }

    private suspend fun handleIncomingLine(device: BluetoothDevice, line: String) {
        val receivedAtMs = System.currentTimeMillis()
        val observation = try {
            gson.fromJson(line, EdgeScoutRidObservation::class.java)
        } catch (t: JsonSyntaxException) {
            Log.w(TAG, "Ignoring malformed Edge Scout JSON: $line", t)
            return
        }

        val seenAtMs = observation.loggedAtSeconds
            ?.times(1000.0)
            ?.toLong()
            ?: receivedAtMs

        appendLogLine(line)
        edgeScoutRepo.recordIngress(seenAtMs)

        val uasId = observation.uasId?.takeIf { it.isNotBlank() }
        if (uasId == null) {
            handleScannerInfoLine(device, line, seenAtMs)
            return
        }
        edgeScoutRepo.updateConnectionState(
            EdgeScoutConnectionState.CONNECTED,
            "Streaming from ${safeDeviceLabel(device)}",
        )

        val result = edgeScoutRepo.recordObservation(
            observation = observation.copy(uasId = uasId),
            rawJson = line,
            seenAtMs = seenAtMs,
        )

        if (result.isFirstObservation && alertedUasIds.add(result.snapshot.uasId)) {
            dataRepository.addPhysioAndAutoGenAlertRequest(
                uid = "edge-scout-${sanitizeUasId(result.snapshot.uasId)}",
                category = "CAT_UAS_RID",
                description = "RID detected: ${result.snapshot.displayName()}",
                priority = 2,
                sendSms = false,
                timeMillis = seenAtMs,
            )
        }
    }

    private fun handleScannerInfoLine(device: BluetoothDevice, line: String, seenAtMs: Long) {
        val scannerInfo = try {
            gson.fromJson(line, EdgeScoutScannerInfoPayload::class.java)
        } catch (t: JsonSyntaxException) {
            Log.w(TAG, "Ignoring Edge Scout JSON without uasId: $line", t)
            return
        }

        if (!scannerInfo.hasDisplayData()) {
            Log.w(TAG, "Ignoring Edge Scout JSON without uasId: $line")
            return
        }

        edgeScoutRepo.updateScannerInfo(
            EdgeScoutScannerInfo(
                manufacturer = scannerInfo.manufacturer,
                make = scannerInfo.make,
                version = scannerInfo.version,
                serialNumber = scannerInfo.serialNumber,
                deviceName = scannerInfo.deviceName,
                capabilities = scannerInfo.capabilities,
                batteryLevel = scannerInfo.batteryLevel,
                lastSeenAtMs = seenAtMs,
            ),
        )
        val label = scannerInfo.deviceName ?: scannerInfo.make ?: safeDeviceLabel(device)
        edgeScoutRepo.updateConnectionState(
            EdgeScoutConnectionState.CONNECTED,
            "Connected to $label",
        )
        Log.i(TAG, "Scanner info update: $line")
    }

    private fun scheduleReconnect() {
        if (manualDisconnectRequested) return
        val address = pendingConnectAddress ?: edgeScoutRepo.selectedDevice.value?.address ?: return
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            delay(RECONNECT_DELAY_MS)
            if (!manualDisconnectRequested) {
                connectToAddress(address)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            edgeScoutRepo.updateConnectionState(
                EdgeScoutConnectionState.ERROR,
                "Bluetooth adapter unavailable",
            )
            return
        }
        if (!adapter.isEnabled) {
            edgeScoutRepo.updateConnectionState(
                EdgeScoutConnectionState.ERROR,
                "Bluetooth is disabled",
            )
            return
        }

        cancelDiscovery()
        stopBleScan()
        candidateMap.clear()
        refreshSelectedDeviceCandidate()
        classicDevicesSeen = 0
        bleAdvertisementsSeen = 0
        classicScanFinished = false
        bleScanFinished = false
        edgeScoutRepo.updateConnectionState(
            EdgeScoutConnectionState.SCANNING,
            "Scanning for Edge Scout over Classic and BLE",
        )
        Log.i(
            TAG,
            "Starting Edge Scout scan selected=${edgeScoutRepo.selectedDevice.value?.address ?: "none"}",
        )

        startBleScan()

        val started = try {
            adapter.startDiscovery()
        } catch (t: Throwable) {
            Log.e(TAG, "startDiscovery failed", t)
            false
        }

        if (!started && bleScanFinished) {
            Log.w(TAG, "Classic discovery and BLE scan both failed to start")
            edgeScoutRepo.updateConnectionState(
                EdgeScoutConnectionState.ERROR,
                "Bluetooth Classic and BLE scans failed to start",
            )
        } else if (!started) {
            Log.w(TAG, "Classic discovery failed to start; BLE scan continues")
            classicScanFinished = true
            maybeCompleteScan()
        } else {
            Log.i(TAG, "Classic discovery started")
            classicScanTimeoutJob?.cancel()
            classicScanTimeoutJob = serviceScope.launch {
                delay(SCAN_WINDOW_MS)
                try {
                    bluetoothAdapter?.cancelDiscovery()
                } catch (t: Throwable) {
                    Log.w(TAG, "Classic scan timeout cancel failed", t)
                } finally {
                    classicScanFinished = true
                    maybeCompleteScan()
                }
            }
        }
    }

    private fun cancelDiscovery() {
        classicScanTimeoutJob?.cancel()
        classicScanTimeoutJob = null
        try {
            bluetoothAdapter?.cancelDiscovery()
        } catch (t: Throwable) {
            Log.w(TAG, "cancelDiscovery failed", t)
        }
    }

    private fun startBleScan() {
        val scanner = bluetoothLeScanner ?: bluetoothAdapter?.bluetoothLeScanner
        bluetoothLeScanner = scanner
        if (scanner == null) {
            bleScanFinished = true
            maybeCompleteScan()
            return
        }

        try {
            scanner.startScan(
                null,
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(),
                bleScanCallback,
            )
            Log.i(TAG, "BLE scan started")
            bleScanTimeoutJob?.cancel()
            bleScanTimeoutJob = serviceScope.launch {
                delay(SCAN_WINDOW_MS)
                stopBleScan()
                maybeCompleteScan()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "BLE scan failed to start", t)
            bleScanFinished = true
            maybeCompleteScan()
        }
    }

    private fun stopBleScan() {
        bleScanTimeoutJob?.cancel()
        bleScanTimeoutJob = null
        try {
            bluetoothLeScanner?.stopScan(bleScanCallback)
        } catch (t: Throwable) {
            Log.w(TAG, "stop BLE scan failed", t)
        } finally {
            bleScanFinished = true
        }
    }

    private fun maybeCompleteScan() {
        if (edgeScoutRepo.connectionState.value != EdgeScoutConnectionState.SCANNING) return
        if (!classicScanFinished || !bleScanFinished) return
        val likelyMatchCount = candidateMap.values.count { it.isLikelyMatch }
        val message = if (candidateMap.isEmpty()) {
            "No devices captured. Classic seen: $classicDevicesSeen, BLE adverts: $bleAdvertisementsSeen"
        } else if (likelyMatchCount == 0) {
            "No likely Edge Scout matches. Devices shown: ${candidateMap.size}, Classic seen: $classicDevicesSeen, BLE adverts: $bleAdvertisementsSeen"
        } else {
            "Scan complete. Likely matches: $likelyMatchCount, devices shown: ${candidateMap.size}, Classic seen: $classicDevicesSeen, BLE adverts: $bleAdvertisementsSeen"
        }
        Log.i(
            TAG,
            "Scan complete classicSeen=$classicDevicesSeen bleSeen=$bleAdvertisementsSeen shown=${candidateMap.size} likely=$likelyMatchCount",
        )
        edgeScoutRepo.updateConnectionState(EdgeScoutConnectionState.IDLE, message)
    }

    @SuppressLint("MissingPermission")
    private fun refreshSelectedDeviceCandidate() {
        val selectedAddress = edgeScoutRepo.selectedDevice.value?.address
        if (selectedAddress.isNullOrBlank()) {
            edgeScoutRepo.replaceDiscoveredDevices(candidateMap.values.toList())
            return
        }

        val bondedDevice = try {
            bluetoothAdapter?.bondedDevices.orEmpty().firstOrNull { it.address == selectedAddress }
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to read bonded devices", t)
            null
        }

        if (bondedDevice != null) {
            val candidate = bondedDevice.toCandidate(
                selectedAddress = selectedAddress,
                lastSeenAtMs = System.currentTimeMillis(),
            )
            if (candidate.isLikelyMatch) {
                candidateMap[bondedDevice.address] = candidate
            }
        }
        edgeScoutRepo.replaceDiscoveredDevices(candidateMap.values.toList())
    }

    private fun appendCandidate(device: BluetoothDevice, rssi: Short? = null) {
        classicDevicesSeen += 1
        val selectedAddress = edgeScoutRepo.selectedDevice.value?.address
        val candidate = device.toCandidate(
            selectedAddress = selectedAddress,
            lastSeenAtMs = System.currentTimeMillis(),
        )
        Log.i(
            TAG,
            "Classic hit mac=${candidate.address} name=${candidate.name ?: "<unknown>"} bond=${bondStateLabel(candidate.bondState)} rssi=${rssi ?: "n/a"} serial=${candidate.supportsSerialConnection} likely=${candidate.isLikelyMatch} uuids=${classicUuidSummary(device)}",
        )
        upsertCandidate(candidate)
        edgeScoutRepo.replaceDiscoveredDevices(candidateMap.values.toList())
    }

    private fun appendBleCandidate(result: ScanResult) {
        bleAdvertisementsSeen += 1
        val device = result.device ?: return
        val selectedAddress = edgeScoutRepo.selectedDevice.value?.address
        val advertisedName = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() } ?: device.name
        val isSelected = device.address == selectedAddress
        val likely = looksLikeEdgeScoutName(advertisedName)
        val serviceSummary = result.scanRecord?.serviceUuids
            ?.take(6)
            ?.joinToString(",") { it.uuid.toString() }
            ?.takeIf { it.isNotBlank() }
        Log.i(
            TAG,
            "BLE hit mac=${device.address} name=${advertisedName ?: "<unknown>"} rssi=${result.rssi} likely=$likely selected=$isSelected services=${serviceSummary ?: "<none>"}",
        )
        if (!likely && !isSelected) {
            return
        }
        val candidate = EdgeScoutDeviceCandidate(
            address = device.address,
            name = advertisedName,
            bondState = device.bondState,
            isLikelyMatch = likely,
            isSelected = isSelected,
            lastSeenAtMs = System.currentTimeMillis(),
            transport = EdgeScoutDiscoveryTransport.BLE,
            supportsSerialConnection = false,
            rssi = result.rssi,
            detail = buildString {
                append("BLE advertisement")
                if (serviceSummary != null) {
                    append(" • svc ")
                    append(serviceSummary.take(36))
                }
            },
        )
        upsertCandidate(candidate)
        edgeScoutRepo.replaceDiscoveredDevices(candidateMap.values.toList())
    }

    private fun upsertCandidate(candidate: EdgeScoutDeviceCandidate) {
        val existing = candidateMap[candidate.address]
        candidateMap[candidate.address] = if (existing == null) {
            candidate
        } else {
            existing.copy(
                name = candidate.name ?: existing.name,
                bondState = maxOf(existing.bondState, candidate.bondState),
                isLikelyMatch = existing.isLikelyMatch || candidate.isLikelyMatch,
                isSelected = existing.isSelected || candidate.isSelected,
                lastSeenAtMs = maxOf(existing.lastSeenAtMs, candidate.lastSeenAtMs),
                transport = mergeTransport(existing.transport, candidate.transport),
                supportsSerialConnection = existing.supportsSerialConnection || candidate.supportsSerialConnection,
                rssi = candidate.rssi ?: existing.rssi,
                detail = candidate.detail ?: existing.detail,
            )
        }
    }

    private fun disconnectSocket(message: String) {
        reconnectJob?.cancel()
        readerJob?.cancel()
        bondTimeoutJob?.cancel()
        closeSocketQuietly()
        edgeScoutRepo.updateConnectionState(EdgeScoutConnectionState.DISCONNECTED, message)
    }

    private fun scheduleBondTimeout(device: BluetoothDevice) {
        bondTimeoutJob?.cancel()
        bondTimeoutJob = serviceScope.launch {
            delay(BOND_TIMEOUT_MS)
            val selectedAddress = pendingConnectAddress
            if (selectedAddress != device.address) return@launch
            val currentBondState = device.bondState
            if (currentBondState == BluetoothDevice.BOND_BONDED) return@launch
            Log.w(
                TAG,
                "Pairing timeout mac=${device.address} bond=${bondStateLabel(currentBondState)}",
            )
            edgeScoutRepo.updateConnectionState(
                EdgeScoutConnectionState.ERROR,
                "Pairing timed out or was not accepted",
            )
        }
    }

    private fun closeSocketQuietly() {
        val oldSocket = socket
        socket = null
        try {
            oldSocket?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "close socket failed", t)
        }
    }

    private fun registerBluetoothReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            this,
            bluetoothReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun unregisterBluetoothReceiver() {
        if (!receiverRegistered) return
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (t: Throwable) {
            Log.w(TAG, "unregister bluetooth receiver failed", t)
        } finally {
            receiverRegistered = false
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
                        ?: return
                    val rssi = if (intent.hasExtra(BluetoothDevice.EXTRA_RSSI)) {
                        intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                            .takeUnless { it == Short.MIN_VALUE }
                    } else {
                        null
                    }
                    appendCandidate(device, rssi)
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.i(TAG, "Classic discovery finished classicSeen=$classicDevicesSeen")
                    classicScanFinished = true
                    maybeCompleteScan()
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
                        ?: return
                    appendCandidate(device)

                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val prevBondState = intent.getIntExtra(
                        BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                        BluetoothDevice.BOND_NONE,
                    )
                    Log.i(
                        TAG,
                        "Bond state changed mac=${device.address} from=${bondStateLabel(prevBondState)} to=${bondStateLabel(bondState)}",
                    )

                    if (device.address != pendingConnectAddress) return

                    when {
                        bondState == BluetoothDevice.BOND_BONDED -> {
                            bondTimeoutJob?.cancel()
                            connectSocket(device)
                        }
                        bondState == BluetoothDevice.BOND_NONE && prevBondState == BluetoothDevice.BOND_BONDING -> {
                            bondTimeoutJob?.cancel()
                            edgeScoutRepo.updateConnectionState(
                                EdgeScoutConnectionState.ERROR,
                                "Pairing failed or was cancelled",
                            )
                        }
                    }
                }
            }
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            appendBleCandidate(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::appendBleCandidate)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with code=$errorCode")
            bleScanFinished = true
            maybeCompleteScan()
        }
    }

    private fun appendLogLine(line: String) {
        try {
            val uri = currentLogUri ?: createLogFile().also { created ->
                currentLogUri = created
            } ?: return
            contentResolver.openFileDescriptor(uri, "wa")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).bufferedWriter().use { writer ->
                    writer.appendLine(line)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to append Edge Scout log line", t)
        }
    }

    private fun createLogFile(): android.net.Uri? {
        val fileName = "edge_scout_${timestampFileName()}.jsonl"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/x-ndjson")
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/counter-uas",
            )
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            edgeScoutRepo.setActiveLogFileName(fileName)
        }
        return uri
    }

    private fun timestampFileName(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    @SuppressLint("MissingPermission")
    private fun safeDeviceLabel(device: BluetoothDevice?): String {
        if (device == null) return "Edge Scout"
        return device.name?.takeIf { it.isNotBlank() } ?: device.address
    }

    companion object {
        private const val TAG = "WearTAK-EdgeScoutService"
        private const val NOTIFICATION_ID = 2127
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val SCAN_WINDOW_MS = 12_000L
        private const val BOND_TIMEOUT_MS = 20_000L

        private const val ACTION_SCAN = "com.aitech.weartak_civ.action.EDGE_SCOUT_SCAN"
        private const val ACTION_CONNECT_SELECTED = "com.aitech.weartak_civ.action.EDGE_SCOUT_CONNECT_SELECTED"
        private const val ACTION_SELECT_AND_CONNECT = "com.aitech.weartak_civ.action.EDGE_SCOUT_SELECT_AND_CONNECT"
        private const val ACTION_DISCONNECT = "com.aitech.weartak_civ.action.EDGE_SCOUT_DISCONNECT"
        private const val ACTION_STOP = "com.aitech.weartak_civ.action.EDGE_SCOUT_STOP"
        private const val ACTION_CLEAR_SELECTION = "com.aitech.weartak_civ.action.EDGE_SCOUT_CLEAR_SELECTION"
        private const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
        private const val EXTRA_DEVICE_NAME = "extra_device_name"

        private val SPP_UUID: UUID = EDGE_SCOUT_SPP_UUID

        val isRunning = AtomicBoolean(false)

        fun requestDiscovery(context: Context) {
            context.startEdgeScoutService(
                Intent(context, EdgeScoutService::class.java).setAction(ACTION_SCAN),
            )
        }

        fun connectSelected(context: Context) {
            context.startEdgeScoutService(
                Intent(context, EdgeScoutService::class.java).setAction(ACTION_CONNECT_SELECTED),
            )
        }

        fun selectAndConnect(context: Context, address: String, name: String?) {
            context.startEdgeScoutService(
                Intent(context, EdgeScoutService::class.java)
                    .setAction(ACTION_SELECT_AND_CONNECT)
                    .putExtra(EXTRA_DEVICE_ADDRESS, address)
                    .putExtra(EXTRA_DEVICE_NAME, name),
            )
        }

        fun disconnect(context: Context) {
            context.startService(
                Intent(context, EdgeScoutService::class.java).setAction(ACTION_DISCONNECT),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, EdgeScoutService::class.java).setAction(ACTION_STOP),
            )
        }

        fun clearSelection(context: Context) {
            context.startEdgeScoutService(
                Intent(context, EdgeScoutService::class.java).setAction(ACTION_CLEAR_SELECTION),
            )
        }

        private fun Context.startEdgeScoutService(intent: Intent) {
            ContextCompat.startForegroundService(this, intent)
        }
    }
}

private fun sanitizeUasId(value: String): String =
    value.replace(":", "-").replace(" ", "_")

private data class EdgeScoutScannerInfoPayload(
    val manufacturer: String? = null,
    val make: String? = null,
    val version: String? = null,
    val serialNumber: String? = null,
    val deviceName: String? = null,
    val capabilities: Int? = null,
    val batteryLevel: Double? = null,
)

private fun EdgeScoutScannerInfoPayload.hasDisplayData(): Boolean =
    !manufacturer.isNullOrBlank() ||
        !make.isNullOrBlank() ||
        !version.isNullOrBlank() ||
        !serialNumber.isNullOrBlank() ||
        !deviceName.isNullOrBlank() ||
        capabilities != null ||
        batteryLevel != null

private val EDGE_SCOUT_SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

@SuppressLint("MissingPermission")
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
private fun BluetoothDevice.toCandidate(
    selectedAddress: String?,
    lastSeenAtMs: Long,
): EdgeScoutDeviceCandidate {
    val label = name?.takeIf { it.isNotBlank() }
    val uuids = try {
        uuids?.mapNotNull { it.uuid } ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }
        val likely = isLikelyEdgeScout(label, uuids)
        return EdgeScoutDeviceCandidate(
            address = address,
            name = label,
            bondState = bondState,
            isLikelyMatch = likely,
            isSelected = address == selectedAddress,
            lastSeenAtMs = lastSeenAtMs,
            transport = EdgeScoutDiscoveryTransport.CLASSIC,
        supportsSerialConnection = uuids.contains(EDGE_SCOUT_SPP_UUID) || likely,
        detail = if (uuids.contains(EDGE_SCOUT_SPP_UUID)) "Classic RFCOMM serial" else "Classic Bluetooth discovery",
        )
    }

private fun isLikelyEdgeScout(name: String?, uuids: List<UUID>): Boolean {
    return looksLikeEdgeScoutName(name) ||
        uuids.contains(EDGE_SCOUT_SPP_UUID)
}

private fun mergeTransport(
    left: EdgeScoutDiscoveryTransport,
    right: EdgeScoutDiscoveryTransport,
): EdgeScoutDiscoveryTransport = when {
    left == right -> left
    else -> EdgeScoutDiscoveryTransport.CLASSIC_AND_BLE
}

@SuppressLint("MissingPermission")
private fun classicUuidSummary(device: BluetoothDevice): String {
    val uuids = try {
        device.uuids?.mapNotNull { it.uuid } ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }
    if (uuids.isEmpty()) return "<none>"
    return uuids.joinToString(",") { it.toString() }
}

private fun bondStateLabel(state: Int): String = when (state) {
    BluetoothDevice.BOND_NONE -> "none"
    BluetoothDevice.BOND_BONDING -> "bonding"
    BluetoothDevice.BOND_BONDED -> "bonded"
    else -> state.toString()
}
