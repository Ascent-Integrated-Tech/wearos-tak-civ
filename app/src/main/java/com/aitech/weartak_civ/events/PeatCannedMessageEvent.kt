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
 * Event to send a CannedMessage via HIVE BLE mesh.
 * Uses the peat-lite CannedMessageCode format for efficient tactical messaging.
 *
 * @param messageCode The CannedMessageCode value (e.g., 0x00 for ACK, 0x20 for EMERGENCY)
 * @param targetNode Target node ID (0 for broadcast to all peers)
 * @param originalTimestamp For ACK messages, the timestamp of the original message being ACK'd.
 *                          This enables proper CRDT lookup to add ACK to the existing document.
 */
data class PeatCannedMessageEvent(
    val messageCode: Int,
    val targetNode: Long = 0L,
    val originalTimestamp: Long = 0L
)
