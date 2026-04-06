/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.events

/**
 * Emitted when a chat message is received from the Peat BLE mesh.
 * The UI should display this as an overlay with ACK button.
 */
data class PeatChatReceivedEvent(
    val sender: String,         // Sender's callsign
    val message: String,        // Message text
    val timestamp: Long,        // When message was sent (epoch ms)
    val originNodeId: Long      // Sender's Peat node ID
)
