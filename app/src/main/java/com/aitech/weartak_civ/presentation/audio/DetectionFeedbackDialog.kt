/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.presentation.audio

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme.colors
import androidx.wear.compose.material.Text

@Composable
fun DetectionFeedbackDialog(
    promptText: String,
    shouldShow: Boolean,
    confidence: Float,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    if (!shouldShow) return

    val confPercent = kotlin.math.round(confidence * 100f)

    AlertDialog(
        containerColor = colors.background,
        modifier = Modifier.fillMaxSize(),
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Warning,
                modifier = Modifier.size(25.dp),
                contentDescription = null,
                tint = Color.LightGray
            )
        },
        text = {
            Text(
                "$promptText\nScore: ${"%.0f".format(confPercent)}%",
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(0.45f),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Green)
            ) { Text("YES", color = Color.Black) }
        },
        dismissButton = {
            Button(
                onClick = onReject,
                modifier = Modifier.fillMaxWidth(0.45f),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
            ) { Text("NO") }
        }
    )
}
