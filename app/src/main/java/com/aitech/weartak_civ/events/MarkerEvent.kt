package com.aitech.weartak_civ.events


class MarkerEvent(val message: String, val addlIntInfo: Int) {

    companion object {
        val HOSTILE = "Hostile"
        val FRIENDLY = "Friendly"
        val UNKNOWN = "Unknown"
        val NEUTRAL = "Neutral"

    }
}