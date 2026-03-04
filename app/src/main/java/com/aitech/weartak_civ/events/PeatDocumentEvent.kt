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
 * Carries a fully built CoT XML string plus the collection name for HIVE BLE sync.
 *
 * This mirrors DittoDocumentEvent but targets the PeatBtleService for
 * power-efficient BLE mesh synchronization.
 */
data class PeatDocumentEvent(
    val collection: String,    // "track", "mapitem", "alert", or "chat"
    val cotXml: String,
    val replyToNode: Long = 0,      // For chat replies: originNode of message being replied to
    val replyToTimestamp: Long = 0  // For chat replies: timestamp of message being replied to
)
