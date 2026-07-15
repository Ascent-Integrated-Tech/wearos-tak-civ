/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.presentation

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.manager.NetworkManager
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

private const val DEBUG_SERVER_PORT = 8000

private data class DebugDisplayInfo(
    val versionName: String,
    val versionCode: Int,
    val buildType: String,
    val gitBranch: String,
    val gitCommit: String,
    val transport: String,
    val ssid: String,
    val ipAddress: String,
    val port: Int
) {
    val debugUrl: String
        get() = "http://$ipAddress:$port/debug"

    val canShowQrCode: Boolean
        get() = ipAddress.isNotBlank() &&
                ipAddress != "N/A" &&
                ipAddress != "Unavailable"
}

private data class DebugRow(
    val label: String,
    val value: String
)

@Composable
fun DebugScreen(
    navController: NavController
) {
    DebugScreen(
        dataRepository = dataRepository,
        onExit = {
            navController.popBackStack()
        }
    )
}

@Composable
private fun DebugScreen(
    dataRepository: DataRepository,
    onExit: () -> Unit
) {
    var showQrCode by remember {
        mutableStateOf(false)
    }

//    val networkSnapshot by dataRepository
//        .networkSnapshot
//        .collectAsState()
    val networkSnapshot by NetworkManager
        .getInstance()
        .networkSnapshot
        .collectAsState()

    val wearTakInfo = dataRepository.wearTakInfo

    val debugInfo = DebugDisplayInfo(
        versionName = wearTakInfo.versionNumber,
        versionCode = wearTakInfo.versionCode,
        buildType = wearTakInfo.buildType,
        gitBranch = wearTakInfo.gitBranch,
        gitCommit = wearTakInfo.gitCommit,
        transport = networkSnapshot.type.name,
        ssid = networkSnapshot.ssid ?: "N/A",
        ipAddress = networkSnapshot.ip ?: "N/A",
        port = DEBUG_SERVER_PORT
    )

    fun navigateBack() {
        if (showQrCode) {
            showQrCode = false
        } else {
            onExit()
        }
    }

    BackHandler {
        navigateBack()
    }

    Scaffold {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            IconButton(
                onClick = {
                    navigateBack()
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 2.dp)
                    .size(32.dp)
            ) {
                Text(
                    text = "‹",
                    fontSize = 30.sp,
                    lineHeight = 30.sp,
                    color = MaterialTheme.colors.primary
                )
            }

            if (showQrCode) {
                DebugQrPage(
                    debugInfo = debugInfo,
                    onBack = {
                        showQrCode = false
                    }
                )
            } else {
                DebugInformationPage(
                    debugInfo = debugInfo,
                    onShowQrCode = {
                        showQrCode = true
                    }
                )
            }
        }
    }
}

@Composable
private fun DebugInformationPage(
    debugInfo: DebugDisplayInfo,
    onShowQrCode: () -> Unit
) {
    val rows = listOf(
        DebugRow(
            label = "Version",
            value = debugInfo.versionName
        ),
        DebugRow(
            label = "Version code",
            value = debugInfo.versionCode.toString()
        ),
        DebugRow(
            label = "Build",
            value = debugInfo.buildType
        ),
        DebugRow(
            label = "Git",
            value = "${debugInfo.gitBranch}@${debugInfo.gitCommit}"
        ),
        DebugRow(
            label = "Transport",
            value = debugInfo.transport
        ),
        DebugRow(
            label = "SSID",
            value = debugInfo.ssid
        ),
        DebugRow(
            label = "IP address",
            value = debugInfo.ipAddress
        ),
        DebugRow(
            label = "Port",
            value = debugInfo.port.toString()
        )
    )

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 18.dp,
            end = 18.dp,
            top = 40.dp,
            bottom = 26.dp
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        item {
            Text(
                text = "WearTAK Debug",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.primary
            )
        }

        items(rows) { row ->
            DebugInformationRow(row)
        }

        item {
            Spacer(
                modifier = Modifier.size(5.dp)
            )
        }

        item {
            Button(
                onClick = onShowQrCode,
                enabled = debugInfo.canShowQrCode,
                modifier = Modifier.size(
                    width = 118.dp,
                    height = 42.dp
                ),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.primary
                )
            ) {
                Text(
                    text = if (debugInfo.canShowQrCode) {
                        "Show QR"
                    } else {
                        "No IP"
                    },
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun DebugInformationRow(
    row: DebugRow
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Text(
            text = row.label,
            fontSize = 11.sp,
            lineHeight = 12.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = row.value,
            fontSize = 13.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colors.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun DebugQrPage(
    debugInfo: DebugDisplayInfo,
    onBack: () -> Unit
) {
    val qrBitmap = remember(debugInfo.debugUrl) {
        generateQrCode(
            content = debugInfo.debugUrl,
            sizePx = 500
        )
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 44.dp,
            bottom = 30.dp
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        item {
            Text(
                text = "Scan to open",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }

        item {
            Box(
                modifier = Modifier
                    .size(172.dp)
                    .background(
                        color = Color.White,
                        shape = CircleShape
                    )
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "WearTAK debug server QR code",
                    modifier = Modifier.size(156.dp)
                )
            }
        }

        item {
            Text(
                text = "${debugInfo.ipAddress}:${debugInfo.port}",
                fontSize = 9.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.onSurface.copy(
                    alpha = 0.75f
                )
            )
        }

        item {
            Button(
                onClick = onBack,
                modifier = Modifier.size(
                    width = 90.dp,
                    height = 38.dp
                )
            ) {
                Text(
                    text = "Back",
                    fontSize = 11.sp
                )
            }
        }
    }
}

private fun generateQrCode(
    content: String,
    sizePx: Int
): Bitmap {
    require(content.isNotBlank()) {
        "QR code content cannot be blank"
    }

    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        sizePx,
        sizePx,
        mapOf(
            EncodeHintType.MARGIN to 1
        )
    )

    val pixels = IntArray(
        matrix.width * matrix.height
    )

    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            pixels[y * matrix.width + x] =
                if (matrix[x, y]) {
                    AndroidColor.BLACK
                } else {
                    AndroidColor.WHITE
                }
        }
    }

    return Bitmap.createBitmap(
        pixels,
        matrix.width,
        matrix.height,
        Bitmap.Config.ARGB_8888
    )
}

@Preview(
    device = Devices.WEAR_OS_SMALL_ROUND,
    showBackground = true
)

@Composable
private fun PreviewDebugInformationPage() {
    WearTAKCIVTheme {
        DebugInformationPage(
            debugInfo = DebugDisplayInfo(
                versionName = "5.7.5",
                versionCode = 60,
                buildType = "release",
                gitBranch = "develop",
                gitCommit = "abc1234",
                transport = "WIFI",
                ssid = "Ascent-Lab",
                ipAddress = "192.168.1.84",
                port = DEBUG_SERVER_PORT
            ),
            onShowQrCode = {}
        )
    }
}