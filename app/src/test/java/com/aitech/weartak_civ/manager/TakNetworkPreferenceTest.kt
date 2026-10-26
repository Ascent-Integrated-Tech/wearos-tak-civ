package com.aitech.weartak_civ.manager

import org.junit.Assert.*
import org.junit.Test

class TakNetworkPreferenceTest {
    @Test fun wifiWinsOverDefaultCellRegardlessOfCallbackOrder() {
        assertEquals("wifi", preferredTakNetwork(listOf("wifi"), "cell", "cell"))
        assertEquals("wifi", preferredTakNetwork(listOf("wifi"), "cell", "wifi"))
    }

    @Test fun keepSelectedWifiUntilItIsLostOrUnvalidated() {
        assertEquals("wifi1", preferredTakNetwork(listOf("wifi2", "wifi1"), "wifi2", "wifi1"))
        assertEquals("wifi2", preferredTakNetwork(listOf("wifi2"), "cell", "wifi1"))
        assertEquals("cell", preferredTakNetwork(emptyList(), "cell", "wifi1"))
    }

    @Test fun defaultWifiIsPreferredOnInitialSelectionAndAbsentInternetDoesNotInventRoute() {
        assertEquals("wifi2", preferredTakNetwork(listOf("wifi1", "wifi2"), "wifi2", null))
        assertNull(preferredTakNetwork<String>(emptyList(), null, "lost"))
    }
}
