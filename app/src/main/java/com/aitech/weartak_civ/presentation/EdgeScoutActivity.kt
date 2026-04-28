/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import kotlinx.coroutines.launch
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.EdgeScoutConnectionState
import com.aitech.weartak_civ.data.EdgeScoutDiscoveryTransport
import com.aitech.weartak_civ.data.EdgeScoutDeviceCandidate
import com.aitech.weartak_civ.data.EdgeScoutDroneSnapshot
import com.aitech.weartak_civ.data.EdgeScoutRepository
import com.aitech.weartak_civ.data.EdgeScoutScannerInfo
import com.aitech.weartak_civ.data.looksLikeEdgeScoutName
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.aitech.weartak_civ.service.EdgeScoutService
import kotlinx.coroutines.delay
import java.util.Locale

class EdgeScoutActivity : ComponentActivity() {

    private var pendingPermissionAction: (() -> Unit)? = null

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { granted -> !granted }.keys.toList()
            if (denied.isNotEmpty()) {
                Toast.makeText(
                    this,
                    "Bluetooth permissions are required for Edge Scout",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                pendingPermissionAction?.invoke()
            }
            pendingPermissionAction = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val launchAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
        val launchName = intent.getStringExtra(EXTRA_DEVICE_NAME)
        val autoConnect = intent.getBooleanExtra(EXTRA_AUTO_CONNECT, false)

        if (autoConnect && !launchAddress.isNullOrBlank()) {
            runWithBluetoothPermissions {
                EdgeScoutService.selectAndConnect(this, launchAddress, launchName)
            }
        }

        setContent {
            WearTAKCIVTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = SCREEN_BACKGROUND,
                ) {
                    EdgeScoutRoute(
                        exitActivity = { finish() },
                        runWithBluetoothPermissions = { action ->
                            runWithBluetoothPermissions(action)
                        },
                    )
                }
            }
        }
    }

    private fun runWithBluetoothPermissions(action: () -> Unit) {
        val missing = REQUIRED_BLUETOOTH_PERMISSIONS.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            action()
            return
        }
        pendingPermissionAction = action
        requestPermissionsLauncher.launch(missing.toTypedArray())
    }

    companion object {
        const val EXTRA_DEVICE_ADDRESS = "edge_scout_device_address"
        const val EXTRA_DEVICE_NAME = "edge_scout_device_name"
        const val EXTRA_AUTO_CONNECT = "edge_scout_auto_connect"

        private val REQUIRED_BLUETOOTH_PERMISSIONS = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    }
}

@Composable
private fun EdgeScoutRoute(
    exitActivity: () -> Unit,
    runWithBluetoothPermissions: ((() -> Unit) -> Unit),
) {
    val context = LocalContext.current
    val repo = remember(context.applicationContext) {
        try {
            (context.applicationContext as WearTakApplication).edgeScoutRepo
        } catch (_: Throwable) {
            EdgeScoutRepository(context.applicationContext)
        }
    }

    val connectionState by repo.connectionState.collectAsState()
    val statusMessage by repo.statusMessage.collectAsState()
    val selectedDevice by repo.selectedDevice.collectAsState()
    val discoveredDevices by repo.discoveredDevices.collectAsState()
    val recentDrones by repo.recentDrones.collectAsState()
    val scannerInfo by repo.scannerInfo.collectAsState()
    val lastMessageAtMs by repo.lastMessageAtMs.collectAsState()
    val messageCount by repo.messageCount.collectAsState()
    val activeLogFileName by repo.activeLogFileName.collectAsState()
    val scope = rememberCoroutineScope()

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var autoConnectStarted by remember(selectedDevice?.address) { mutableStateOf(false) }
    var autoScanStarted by remember { mutableStateOf(false) }
    var showTargetMacDialog by remember { mutableStateOf(false) }
    val latestPermissionRunner by rememberUpdatedState(runWithBluetoothPermissions)

    LaunchedEffect(selectedDevice?.address, selectedDevice?.name) {
        val name = selectedDevice?.name
        if (!name.isNullOrBlank() && !looksLikeEdgeScoutName(name)) {
            repo.clearSelectedDevice()
            repo.updateConnectionState(
                EdgeScoutConnectionState.IDLE,
                "Cleared stale non-Edge-Scout selection",
            )
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }

    LaunchedEffect(connectionState, selectedDevice?.address, discoveredDevices.size, autoConnectStarted, autoScanStarted) {
        if (!autoConnectStarted &&
            !selectedDevice?.address.isNullOrBlank() &&
            connectionState == EdgeScoutConnectionState.IDLE
        ) {
            autoConnectStarted = true
            latestPermissionRunner {
                EdgeScoutService.connectSelected(context)
            }
            return@LaunchedEffect
        }

        if (!autoScanStarted &&
            selectedDevice == null &&
            connectionState == EdgeScoutConnectionState.IDLE &&
            discoveredDevices.isEmpty()
        ) {
            autoScanStarted = true
            latestPermissionRunner {
                EdgeScoutService.requestDiscovery(context)
            }
        }
    }

    EdgeScoutScreen(
        context = context,
        exitActivity = exitActivity,
        connectionState = connectionState,
        statusMessage = statusMessage,
        selectedDeviceName = selectedDevice?.name,
        selectedDeviceAddress = selectedDevice?.address,
        discoveredDevices = discoveredDevices,
        recentDrones = recentDrones,
        scannerInfo = scannerInfo,
        lastMessageAtMs = lastMessageAtMs,
        messageCount = messageCount,
        activeLogFileName = activeLogFileName,
        nowMs = nowMs,
        onEditTargetMac = {
            showTargetMacDialog = true
        },
        onScan = {
            latestPermissionRunner {
                EdgeScoutService.requestDiscovery(context)
            }
        },
        onConnectSelected = {
            latestPermissionRunner {
                EdgeScoutService.connectSelected(context)
            }
        },
        onDisconnect = {
            latestPermissionRunner {
                EdgeScoutService.disconnect(context)
            }
        },
        onStop = {
            EdgeScoutService.stop(context)
        },
        onClearSelection = {
            EdgeScoutService.clearSelection(context)
        },
        onSelectDevice = { device ->
            latestPermissionRunner {
                EdgeScoutService.selectAndConnect(context, device.address, device.name)
            }
        },
    )

    if (showTargetMacDialog) {
        TargetMacDialog(
            initialAddress = selectedDevice?.address,
            onDismiss = {
                showTargetMacDialog = false
            },
            onSave = { normalizedAddress ->
                scope.launch {
                    val preservedName = selectedDevice?.name
                        ?.takeIf { selectedDevice?.address.equals(normalizedAddress, ignoreCase = true) }
                    repo.storeSelectedDevice(normalizedAddress, preservedName)
                    repo.updateConnectionState(
                        repo.connectionState.value,
                        "Saved target MAC $normalizedAddress",
                    )
                    showTargetMacDialog = false
                }
            },
        )
    }
}

@Composable
private fun EdgeScoutScreen(
    context: Context,
    exitActivity: () -> Unit,
    connectionState: EdgeScoutConnectionState,
    statusMessage: String?,
    selectedDeviceName: String?,
    selectedDeviceAddress: String?,
    discoveredDevices: List<EdgeScoutDeviceCandidate>,
    recentDrones: List<EdgeScoutDroneSnapshot>,
    scannerInfo: EdgeScoutScannerInfo?,
    lastMessageAtMs: Long?,
    messageCount: Long,
    activeLogFileName: String?,
    nowMs: Long,
    onEditTargetMac: () -> Unit,
    onScan: () -> Unit,
    onConnectSelected: () -> Unit,
    onDisconnect: () -> Unit,
    onStop: () -> Unit,
    onClearSelection: () -> Unit,
    onSelectDevice: (EdgeScoutDeviceCandidate) -> Unit,
) {
    val streamActive = lastMessageAtMs?.let { nowMs - it <= STREAM_ACTIVE_WINDOW_MS } == true
    val ridActive = recentDrones.isNotEmpty()
    val statusLabel = when {
        connectionState == EdgeScoutConnectionState.ERROR &&
            statusMessage?.contains("does not look like an Edge Scout scanner", ignoreCase = true) == true -> {
            "Unsupported Device"
        }

        else -> connectionStateLabel(connectionState, streamActive, ridActive)
    }
    val targetLabel = selectedDeviceName ?: if (selectedDeviceAddress != null) "Manual target" else "None"
    val targetMacLabel = selectedDeviceAddress ?: "None"
    val scannerLabel = scannerInfo?.deviceName ?: scannerInfo?.make ?: "Not identified yet"
    val activityLabel = when {
        ridActive -> "RID traffic active"
        streamActive -> "Receiving scanner data"
        connectionState == EdgeScoutConnectionState.CONNECTED -> "Connected, waiting for data"
        else -> "No live stream"
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentPadding = PaddingValues(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionChip(
                    modifier = Modifier.weight(1f),
                    text = "Back",
                    onClick = exitActivity,
                    backgroundColor = Color(0xFF28323C),
                )
                Text(
                    text = "Edge Scout",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    color = PRIMARY_TEXT,
                )
            }
        }

        item {
            Card(
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(statusCardColor(connectionState, streamActive))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("State: $statusLabel", color = PRIMARY_TEXT)
                    Text(activityLabel, color = PRIMARY_TEXT)
                    Text("Target: $targetLabel", color = SECONDARY_TEXT)
                    Text("Target MAC: $targetMacLabel", color = SECONDARY_TEXT)
                    Text("Scanner: $scannerLabel", color = SECONDARY_TEXT)
                    scannerInfo?.version?.let {
                        Text("Firmware: $it", color = SECONDARY_TEXT)
                    }
                    if (scannerInfo != null) {
                        scannerInfo.serialNumber?.let {
                            Text("Serial: $it", color = SECONDARY_TEXT)
                        }
                    }
                    Text("Lines: $messageCount", color = SECONDARY_TEXT)
                    Text(
                        "Last data: ${
                            formatRelativeTime(
                                context = context,
                                eventTimeMs = lastMessageAtMs,
                                nowMs = nowMs,
                            )
                        }",
                        color = SECONDARY_TEXT,
                    )
                    Text("Log: ${activeLogFileName ?: "Not started"}", color = SECONDARY_TEXT)
                    statusMessage?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = SECONDARY_TEXT)
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionChip(
                    modifier = Modifier.weight(1f),
                    text = "Scan",
                    onClick = onScan,
                    backgroundColor = Color(0xFF1C4B75),
                )
                ActionChip(
                    modifier = Modifier.weight(1f),
                    text = "Connect",
                    onClick = onConnectSelected,
                    backgroundColor = Color(0xFF1D5A3E),
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionChip(
                    modifier = Modifier.weight(1f),
                    text = "Disconnect",
                    onClick = onDisconnect,
                    backgroundColor = Color(0xFF4A2A2A),
                )
                ActionChip(
                    modifier = Modifier.weight(1f),
                    text = "Forget",
                    onClick = onClearSelection,
                    backgroundColor = Color(0xFF5A4315),
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionChip(
                    modifier = Modifier.weight(1f),
                    text = "Set Target",
                    onClick = onEditTargetMac,
                    backgroundColor = Color(0xFF314663),
                )
                ActionChip(
                    modifier = Modifier.weight(1f),
                    text = "Stop Service",
                    onClick = onStop,
                    backgroundColor = Color(0xFF4C2424),
                )
            }
        }

        item {
            Text(
                text = "Targets",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                color = PRIMARY_TEXT,
            )
        }

        item {
            Text(
                text = "Nearby scanners appear here. Saved target info is shown even when discovery misses the device.",
                color = SECONDARY_TEXT,
            )
        }

        if (discoveredDevices.isEmpty()) {
            item {
                Card(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(PANEL_BACKGROUND)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("No scanner candidates yet.", color = PRIMARY_TEXT)
                        Text(
                            statusMessage ?: "Scan again or connect to the saved target MAC.",
                            color = SECONDARY_TEXT,
                        )
                    }
                }
            }
        } else {
            items(discoveredDevices, key = { it.address }) { device ->
                DeviceCandidateCard(device = device, onSelectDevice = onSelectDevice)
            }
        }

        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "RID Contacts",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                color = PRIMARY_TEXT,
            )
        }

        if (recentDrones.isEmpty()) {
            item {
                Text(
                    scannerInfo?.let { "Scanner is connected. Waiting for RID detections." }
                        ?: "No RID data received yet.",
                    color = SECONDARY_TEXT,
                )
            }
        } else {
            items(recentDrones.take(MAX_DRONES_SHOWN), key = { it.uasId }) { drone ->
                DroneSnapshotCard(
                    drone = drone,
                    nowMs = nowMs,
                )
            }
        }
    }
}

@Composable
private fun TargetMacDialog(
    initialAddress: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(initialAddress) {
        mutableStateOf(initialAddress.orEmpty())
    }
    var showValidationError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PANEL_BACKGROUND,
        title = {
            Text(
                text = "Target MAC",
                color = PRIMARY_TEXT,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Enter the Classic Bluetooth MAC for direct Edge Scout connect.",
                    color = SECONDARY_TEXT,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = normalizeBluetoothMacAddressInput(it)
                        showValidationError = false
                    },
                    singleLine = true,
                    label = { androidx.compose.material3.Text("MAC address") },
                    placeholder = { androidx.compose.material3.Text("00:4B:12:BF:AB:62") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val normalizedValue = normalizeBluetoothMacAddress(value)
                            if (isValidBluetoothMacAddress(normalizedValue)) {
                                onSave(normalizedValue)
                            } else {
                                showValidationError = true
                            }
                        },
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = PRIMARY_TEXT,
                        unfocusedTextColor = PRIMARY_TEXT,
                        focusedLabelColor = SECONDARY_TEXT,
                        unfocusedLabelColor = SECONDARY_TEXT,
                        focusedPlaceholderColor = SECONDARY_TEXT,
                        unfocusedPlaceholderColor = SECONDARY_TEXT,
                        focusedBorderColor = Color(0xFF6EA8DC),
                        unfocusedBorderColor = Color(0xFF4A6078),
                        cursorColor = PRIMARY_TEXT,
                    ),
                )
                if (showValidationError) {
                    Text(
                        text = "Use MAC format 00:11:22:33:44:55.",
                        color = Color(0xFFFFB4AB),
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    val normalizedValue = normalizeBluetoothMacAddress(value)
                    if (isValidBluetoothMacAddress(normalizedValue)) {
                        onSave(normalizedValue)
                    } else {
                        showValidationError = true
                    }
                },
            ) {
                androidx.compose.material3.Text(
                    text = "Save",
                    color = PRIMARY_TEXT,
                )
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(
                onClick = onDismiss,
            ) {
                androidx.compose.material3.Text(
                    text = "Cancel",
                    color = PRIMARY_TEXT,
                )
            }
        },
    )
}

@Composable
private fun DeviceCandidateCard(
    device: EdgeScoutDeviceCandidate,
    onSelectDevice: (EdgeScoutDeviceCandidate) -> Unit,
) {
    val name = device.name ?: "Unnamed device"
    val stateLabel = when {
        device.isSelected -> "Saved target"
        device.supportsSerialConnection -> "Ready to connect"
        else -> "Visible only"
    }

    Card(
        onClick = { onSelectDevice(device) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    when {
                        device.isSelected -> Color(0xFF234A6B)
                        device.isLikelyMatch -> PANEL_BACKGROUND
                        else -> Color(0xFF3E2C19)
                    },
                )
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(name, color = PRIMARY_TEXT)
            Text(stateLabel, color = SECONDARY_TEXT)
            Text(device.address, color = SECONDARY_TEXT)
            Text(
                buildString {
                    append(transportLabel(device.transport))
                    append(" • ")
                    append(if (device.supportsSerialConnection) bondStateLabel(device.bondState) else "BLE only")
                    device.rssi?.let {
                        append(" • RSSI ")
                        append(it)
                    }
                },
                color = SECONDARY_TEXT,
            )
            Text(device.detail ?: "Tap to pair or connect", color = SECONDARY_TEXT)
        }
    }
}

@Composable
private fun DroneSnapshotCard(
    drone: EdgeScoutDroneSnapshot,
    nowMs: Long,
) {
    Card(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(PANEL_BACKGROUND)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(drone.displayName(), fontWeight = FontWeight.Bold, color = PRIMARY_TEXT)
            Text("UAS ID: ${drone.uasId}", color = SECONDARY_TEXT)
            Text(
                buildString {
                    append("RSSI ${drone.rssi ?: "?"}")
                    append(" • ${recvMethodLabel(drone.recvMethod)}")
                    drone.uasType?.let { append(" • ${uasTypeLabel(it)}") }
                },
                color = SECONDARY_TEXT,
            )
            Text(
                buildString {
                    append("UAS: ")
                    append(drone.uasLat?.formatCoord() ?: "?")
                    append(", ")
                    append(drone.uasLon?.formatCoord() ?: "?")
                },
                color = SECONDARY_TEXT,
            )
            Text(
                buildString {
                    append("Operator: ")
                    append(drone.opLat?.formatCoord() ?: "?")
                    append(", ")
                    append(drone.opLon?.formatCoord() ?: "?")
                },
                color = SECONDARY_TEXT,
            )
            Text("Updated ${formatRelativeTime(drone.lastSeenAtMs, nowMs)}", color = SECONDARY_TEXT)
        }
    }
}

private fun connectionStateLabel(
    state: EdgeScoutConnectionState,
    streamActive: Boolean,
    ridActive: Boolean,
): String = when {
    state == EdgeScoutConnectionState.CONNECTED && ridActive -> "RID Active"
    state == EdgeScoutConnectionState.CONNECTED && streamActive -> "Connected"
    else -> when (state) {
        EdgeScoutConnectionState.IDLE -> "Idle"
        EdgeScoutConnectionState.SCANNING -> "Scanning"
        EdgeScoutConnectionState.BONDING -> "Pairing"
        EdgeScoutConnectionState.CONNECTING -> "Connecting"
        EdgeScoutConnectionState.CONNECTED -> "Connected"
        EdgeScoutConnectionState.DISCONNECTED -> "Disconnected"
        EdgeScoutConnectionState.ERROR -> "Error"
    }
}

private fun formatRelativeTime(
    context: Context,
    eventTimeMs: Long?,
    nowMs: Long,
): String {
    if (eventTimeMs == null) return "Never"
    return DateUtils.getRelativeTimeSpanString(
        eventTimeMs,
        nowMs,
        DateUtils.SECOND_IN_MILLIS,
    ).toString()
}

private fun formatRelativeTime(
    eventTimeMs: Long,
    nowMs: Long,
): String =
    DateUtils.getRelativeTimeSpanString(
        eventTimeMs,
        nowMs,
        DateUtils.SECOND_IN_MILLIS,
    ).toString()

private fun bondStateLabel(bondState: Int): String = when (bondState) {
    android.bluetooth.BluetoothDevice.BOND_BONDED -> "Bonded"
    android.bluetooth.BluetoothDevice.BOND_BONDING -> "Bonding"
    else -> "Not bonded"
}

@Composable
private fun ActionChip(
    modifier: Modifier = Modifier,
    text: String,
    onClick: () -> Unit,
    backgroundColor: Color = Color(0xFF2A2A2A),
) {
    Chip(
        modifier = modifier,
        label = {
            Text(
                text = text,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                maxLines = 1,
                color = PRIMARY_TEXT,
            )
        },
        onClick = onClick,
        colors = ChipDefaults.chipColors(
            backgroundColor = backgroundColor,
            contentColor = PRIMARY_TEXT,
            secondaryContentColor = SECONDARY_TEXT,
        ),
    )
}

private fun statusCardColor(
    connectionState: EdgeScoutConnectionState,
    streamActive: Boolean,
): Color = when {
    streamActive -> Color(0xFF153826)
    connectionState == EdgeScoutConnectionState.CONNECTED -> Color(0xFF16344D)
    connectionState == EdgeScoutConnectionState.SCANNING -> Color(0xFF5A4315)
    connectionState == EdgeScoutConnectionState.ERROR -> Color(0xFF4A2222)
    connectionState == EdgeScoutConnectionState.BONDING -> Color(0xFF3E2F57)
    connectionState == EdgeScoutConnectionState.CONNECTING -> Color(0xFF1F3A5F)
    else -> PANEL_BACKGROUND
}

private fun transportLabel(transport: EdgeScoutDiscoveryTransport): String = when (transport) {
    EdgeScoutDiscoveryTransport.CLASSIC -> "Classic"
    EdgeScoutDiscoveryTransport.BLE -> "BLE"
    EdgeScoutDiscoveryTransport.CLASSIC_AND_BLE -> "Classic + BLE"
}

private fun recvMethodLabel(value: Int?): String = when (value) {
    1 -> "Wi-Fi 2.4 beacon"
    2 -> "Wi-Fi 2.4 NAN"
    4 -> "Wi-Fi 5 beacon"
    8 -> "Wi-Fi 5 NAN"
    16 -> "BT4"
    32 -> "BT5"
    else -> "Unknown"
}

private fun uasTypeLabel(value: Int): String = when (value) {
    1 -> "Aeroplane"
    2 -> "Multirotor"
    3 -> "Gyroplane"
    4 -> "Hybrid"
    5 -> "Ornithopter"
    6 -> "Glider"
    7 -> "Kite"
    8 -> "Free balloon"
    9 -> "Captive balloon"
    10 -> "Airship"
    11 -> "Parachute"
    12 -> "Rocket"
    13 -> "Tethered"
    14 -> "Ground obstacle"
    15 -> "Other"
    else -> "Unknown"
}

private fun Double.formatCoord(): String =
    String.format(Locale.US, "%.5f", this)

private fun normalizeBluetoothMacAddressInput(value: String): String =
    value.trim()
        .uppercase(Locale.US)
        .filter { it in '0'..'9' || it in 'A'..'F' || it == ':' || it == '-' }
        .take(MAX_MAC_ADDRESS_INPUT_LENGTH)

private fun normalizeBluetoothMacAddress(value: String): String {
    val hexOnly = value.trim()
        .uppercase(Locale.US)
        .filter { it in '0'..'9' || it in 'A'..'F' }
    if (hexOnly.length == MAC_ADDRESS_HEX_LENGTH) {
        return hexOnly.chunked(2).joinToString(":")
    }
    return value.trim().replace('-', ':').uppercase(Locale.US)
}

private fun isValidBluetoothMacAddress(value: String): Boolean =
    BLUETOOTH_MAC_ADDRESS_REGEX.matches(value)

private const val STREAM_ACTIVE_WINDOW_MS = 10_000L
private const val MAX_DRONES_SHOWN = 8
private const val MAX_MAC_ADDRESS_INPUT_LENGTH = 17
private const val MAC_ADDRESS_HEX_LENGTH = 12
private val SCREEN_BACKGROUND = Color(0xFF081018)
private val PANEL_BACKGROUND = Color(0xFF15202B)
private val PRIMARY_TEXT = Color(0xFFF4F7FB)
private val SECONDARY_TEXT = Color(0xFFB8C7D9)
private val BLUETOOTH_MAC_ADDRESS_REGEX = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
