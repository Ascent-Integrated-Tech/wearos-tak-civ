/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.weartak.weartak.events

/**
 * Fired by TAKConnectService whenever a COT XML chat message
 * for this device (or “All Chat Rooms”) is received.
 */

data class TakChatEvent(
    val rawXml: String
)