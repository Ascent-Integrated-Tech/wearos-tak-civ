package com.aitech.weartak_civ.service

internal object DittoTrackExpiry {
    fun expire(document: Map<String, Any?>, nowMillis: Long): Map<String, Any?> =
        document + mapOf(
            "b" to nowMillis,
            "n" to nowMillis,
            "o" to nowMillis,
        )
}
