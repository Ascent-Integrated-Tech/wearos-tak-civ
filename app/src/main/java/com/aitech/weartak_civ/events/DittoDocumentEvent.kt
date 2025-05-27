package com.aitech.weartak_civ.events

/**
 * Carries a fully built CoT XML string plus the Ditto collection name.
 */
data class DittoDocumentEvent(
    val collection: String,    // "mapitem" or "alerts" (future: "tracks")
    val cotXml: String
)