package com.aitech.weartak_civ.service

import android.app.Application
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import live.ditto.transports.DittoTransportConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DittoTransportPolicyTest {
    @Test fun samsungPrivateDirectFeatureDoesNotEnableUnsupportedAwareAndBleIsUntouched() {
        val features = mockk<PackageManager>()
        every { features.hasSystemFeature(any()) } answers { firstArg<String>() == "sem.hardware.wifi.direct" }
        val config = DittoTransportConfig().apply { enableAllPeerToPeer() }
        DittoTransportPolicy.apply(config, features, false, false, emptySet())
        assertFalse(config.peerToPeer.wifiAware.enabled)
        assertFalse(config.peerToPeer.lan.enabled)
        assertTrue(config.peerToPeer.bluetoothLe.enabled)
        assertEquals(setOf("BLE"), DittoTransportPolicy.enabled(config))
    }

    @Test fun wifiInternetAndPublicFeatureChangesGateOnlyTheirOwnTransports() {
        val features = mockk<PackageManager>()
        every { features.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) } returns true
        val config = DittoTransportConfig().apply {
            peerToPeer.bluetoothLe.enabled = false
            connect.retryIntervalMs = 12_345
        }
        val cloud = setOf("wss://example.invalid/sync")
        DittoTransportPolicy.apply(config, features, true, false, cloud)
        assertEquals(setOf("LAN", "Wi-Fi Aware"), DittoTransportPolicy.enabled(config))
        DittoTransportPolicy.apply(config, features, false, true, cloud)
        assertEquals(setOf("Wi-Fi Aware", "WebSocket"), DittoTransportPolicy.enabled(config))
        assertEquals(cloud, config.connect.websocketUrls)
        assertEquals(12_345, config.connect.retryIntervalMs)
        DittoTransportPolicy.apply(config, features, true, true, emptySet())
        assertTrue(config.connect.websocketUrls.isEmpty())
        assertFalse(config.peerToPeer.bluetoothLe.enabled)
    }
}
