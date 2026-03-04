/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.presentation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.CardDefaults
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.PeatBtleRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme

class PeatMeshActivity : ComponentActivity() {
    companion object {
        val TAG = "WearTAK-PeatMeshActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            WearTAKCIVTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val context = LocalContext.current
                    val peatBtleRepo = (applicationContext as WearTakApplication).peatBtleRepo
                    PeatMeshApp(
                        exitActivity = { finishAndRemoveTask() },
                        context = context,
                        peatBtleRepo = peatBtleRepo
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "PeatMeshActivity onDestroy()")
    }
}

@Composable
fun PeatMeshApp(
    exitActivity: () -> Unit,
    context: Context,
    peatBtleRepo: PeatBtleRepository
) {
    val navController = rememberSwipeDismissableNavController()
    val peers by peatBtleRepo.connectedPeers.collectAsState()
    val meshState by peatBtleRepo.meshState.collectAsState()
    val highPriorityMode by peatBtleRepo.highPriorityMode.collectAsState()

    SwipeDismissableNavHost(
        navController = navController,
        startDestination = "peer_list_screen"
    ) {
        composable("peer_list_screen") {
            PeatMeshScreen(
                exitActivity = exitActivity,
                context = context,
                peers = peers,
                meshState = meshState,
                highPriorityMode = highPriorityMode,
                onHighPriorityToggle = { enabled ->
                    peatBtleRepo.setHighPriorityMode(enabled, if (enabled) "user" else null)
                }
            )
        }
    }
}

@Composable
fun PeatMeshScreen(
    exitActivity: () -> Unit,
    context: Context,
    peers: List<PeatBtleRepository.PeatPeerInfo>,
    meshState: PeatBtleRepository.PeatMeshState,
    highPriorityMode: Boolean,
    onHighPriorityToggle: (Boolean) -> Unit
) {
    // Only show peers that are connected or in reconnect grace period
    val visiblePeers = peers.filter { it.isConnected || it.isReconnecting }

    var showConfirmDialog by remember { mutableStateOf(false) }

    // Confirmation dialog for enabling high-priority mode
    if (showConfirmDialog) {
        HighPriorityConfirmDialog(
            onConfirm = {
                onHighPriorityToggle(true)
                showConfirmDialog = false
            },
            onDismiss = {
                showConfirmDialog = false
            }
        )
    }

    WearTakCivScreenDimensions(
        columnContent = {
            // High-priority sync toggle button
            HighPrioritySyncButton(
                enabled = highPriorityMode,
                onClick = {
                    if (highPriorityMode) {
                        // Disable immediately without confirmation
                        onHighPriorityToggle(false)
                    } else {
                        // Show confirmation dialog before enabling
                        showConfirmDialog = true
                    }
                }
            )
            Spacer(modifier = Modifier.height(6.dp))

            // Show mesh state
            val stateText = when (meshState) {
                PeatBtleRepository.PeatMeshState.ACTIVE -> "Active"
                PeatBtleRepository.PeatMeshState.SCANNING -> "Scanning"
                PeatBtleRepository.PeatMeshState.ADVERTISING -> "Advertising"
                PeatBtleRepository.PeatMeshState.SYNCING -> "Syncing"
                PeatBtleRepository.PeatMeshState.ERROR -> "Error"
                else -> "Idle"
            }
            Text(
                text = "Status: $stateText",
                fontSize = 12.sp,
                color = colors.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))

            if (visiblePeers.isEmpty()) {
                Text(
                    text = "No peers discovered",
                    fontSize = 14.sp,
                    color = colors.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.height(180.dp)  // Reduced to make room for button
                ) {
                    itemsIndexed(visiblePeers) { index, peer ->
                        PeerCard(peer = peer)
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
        },
        rowContent = {
            BackButton(onClick = {
                exitActivity()
                context.startActivity(Intent(context, MainActivity::class.java))
            })
        },
        text = "Mesh Peers (${visiblePeers.size})"
    )
}

/**
 * High-priority sync mode toggle button.
 * Orange/yellow when enabled, gray when disabled.
 */
@Composable
fun HighPrioritySyncButton(
    enabled: Boolean,
    onClick: () -> Unit
) {
    val buttonColor = if (enabled) Color(0xFFFF9800) else colors.surface
    val textColor = if (enabled) Color.Black else colors.onSurface
    val borderColor = if (enabled) Color(0xFFFF9800) else colors.onSurfaceVariant

    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .padding(horizontal = 16.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = buttonColor
        )
    ) {
        Text(
            text = if (enabled) "PRIORITY SYNC: ON" else "PRIORITY SYNC: OFF",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}

/**
 * Confirmation dialog for enabling high-priority mode.
 * Warns about battery impact.
 */
@Composable
fun HighPriorityConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Enable Priority Sync?",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = "Priority sync improves peer communication but uses more battery (~40-50% faster drain).\n\nAuto-disables after 1 hour.",
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("ENABLE", color = Color(0xFFFF9800))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL")
            }
        },
        containerColor = Color(0xFF1E1E1E)
    )
}

@Composable
fun PeerCard(peer: PeatBtleRepository.PeatPeerInfo) {
    val displayName = peer.callsign ?: peer.name ?: "Node-${peer.nodeId.toString(16).uppercase().takeLast(4)}"

    // Format RSSI (only show for direct peers)
    val rssiText = if (peer.hopCount == 0) "${peer.rssi}dBm" else "--"

    // Format battery
    val batteryText = peer.battery?.let { "${it}%" } ?: "--"

    // Format hop count
    val hopText = if (peer.hopCount > 0) "${peer.hopCount}-hop" else null

    // SOS flash animation
    val infiniteTransition = rememberInfiniteTransition(label = "peer_sos")
    val sosFlashAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(350),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sos_alpha"
    )

    // Connection/emergency status color
    val statusColor = when {
        peer.isInEmergency -> Color.Red.copy(alpha = sosFlashAlpha)  // Red flashing for SOS
        peer.isConnected -> Color(0xFF4CAF50)  // Green for connected
        peer.isReconnecting -> Color(0xFF9E9E9E)  // Grey during reconnect grace period
        else -> Color(0xFF9E9E9E)  // Gray fallback
    }

    Card(
        onClick = { },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        backgroundPainter = CardDefaults.cardBackgroundPainter(
            startBackgroundColor = colors.surface,
            endBackgroundColor = colors.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    // SOS badge when peer in emergency
                    if (peer.isInEmergency) {
                        Text(
                            text = "SOS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Red.copy(alpha = sosFlashAlpha),
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                    // Connection status dot
                    Canvas(modifier = Modifier.size(8.dp)) {
                        drawCircle(color = statusColor)
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row {
                    Text(
                        text = rssiText,
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = batteryText,
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariant
                    )
                    // Show hop count for relayed peers
                    if (hopText != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = hopText,
                            fontSize = 12.sp,
                            color = Color(0xFF2196F3)  // Blue for relay indicator
                        )
                    }
                }
            }
        }
    }
}
