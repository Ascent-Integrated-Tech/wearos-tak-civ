package com.aitech.weartak_civ.presentation

internal enum class MarkerLayerCollection {
    DROPPED,
    RECEIVED;

    fun layerId(markerType: String): String = "weartak-markers-$name-$markerType"
}
