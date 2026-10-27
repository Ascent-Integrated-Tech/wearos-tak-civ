package com.aitech.weartak_civ.service

internal object DittoSubscriptions {
    const val REFRESH_MS = 60 * 60 * 1_000L
    const val TRACK_RECENT_MS = 10 * 60 * 1_000L
    const val EVENT_RECENT_MS = 24 * 60 * 60 * 1_000L

    fun query(collection: String, cutoff: Long): String {
        require(collection in setOf("track", "mapitem", "alert"))
        val age = if (collection == "track") TRACK_RECENT_MS else EVENT_RECENT_MS
        // Recent expired revisions include alert cancellations and owned-point tombstones.
        return "SELECT * FROM $collection WHERE o > $cutoff OR b > ${cutoff - age}"
    }
}
