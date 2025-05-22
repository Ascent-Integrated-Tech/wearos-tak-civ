package com.aitech.weartak_civ.events

/**
 * Fired by TAKConnectService whenever a COT XML chat message
 * for this device (or “All Chat Rooms”) is received.
 */

data class TakChatEvent(
    val rawXml: String
)