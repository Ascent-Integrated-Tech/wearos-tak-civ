package com.aitech.weartak_civ.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MarkerLayerCollectionTest {
    @Test
    fun separatesLocalAndEchoedMarkerLayers() {
        val type = "a-f-G"
        assertNotEquals(
            MarkerLayerCollection.DROPPED.layerId(type),
            MarkerLayerCollection.RECEIVED.layerId(type)
        )
    }

    @Test
    fun layerIdentityDependsOnCollectionAndTypeNotFirstMarker() {
        assertEquals(
            "weartak-markers-DROPPED-a-f-G",
            MarkerLayerCollection.DROPPED.layerId("a-f-G")
        )
        assertNotEquals(
            MarkerLayerCollection.DROPPED.layerId("a-f-G"),
            MarkerLayerCollection.DROPPED.layerId("a-h-G")
        )
    }
}
