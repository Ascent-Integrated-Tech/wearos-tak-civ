package com.aitech.weartak_civ.presentation

internal enum class MarkerLayerCollection {
    DROPPED,
    RECEIVED,
    USERS;

    fun layerId(markerType: String): String = "weartak-markers-$name-$markerType"
}
