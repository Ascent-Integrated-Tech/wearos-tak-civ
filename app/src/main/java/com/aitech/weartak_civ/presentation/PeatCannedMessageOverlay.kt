/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.data.PeatBtleRepository

/**
 * Full-screen overlay that displays incoming HIVE mesh CannedMessages.
 * Shows message type, sender, and ACK button.
 *
 * @param message The CannedMessage to display, or null if no overlay should be shown
 * @param onAck Called when user taps the ACK button
 * @param onDismiss Called when user taps outside the message area
 */
@Composable
fun PeatCannedMessageOverlay(
    message: PeatBtleRepository.InboundCannedMessage?,
    onAck: () -> Unit,
    onDismiss: () -> Unit
) {
    if (message == null) return

    // Determine color based on message type
    val (headerText, headerColor) = when {
        message.messageCode in 0x20..0x2F -> "EMERGENCY" to Color(0xFFFF5252) // Red
        message.messageCode in 0x30..0x3F -> "ALERT" to Color(0xFFFF9800) // Orange
        message.messageCode in 0x40..0x4F -> "REQUEST" to Color(0xFFFFEB3B) // Yellow
        else -> "STATUS" to Color(0xFF2196F3) // Blue
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .clickable { onDismiss() }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(16.dp)
                .background(
                    color = Color(0xFF1A1A1A),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(16.dp)
                .clickable { /* Prevent dismiss when tapping message area */ },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Message type indicator
            Text(
                text = headerText,
                color = headerColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Message label (e.g., "Check In", "Moving", etc.)
            Text(
                text = message.label,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Sender node ID
            Text(
                text = "from ${String.format("%08X", message.sourceNode)}",
                color = Color(0xFF9E9E9E),
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ACK button
            Button(
                onClick = onAck,
                modifier = Modifier.size(width = 80.dp, height = 36.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = Color(0xFF4CAF50)
                )
            ) {
                Text(
                    text = "ACK",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
    }
}
