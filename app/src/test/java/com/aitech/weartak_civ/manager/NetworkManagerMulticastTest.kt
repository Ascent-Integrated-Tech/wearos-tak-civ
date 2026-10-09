package com.aitech.weartak_civ.manager

import android.app.Application
import android.net.Network
import android.net.NetworkCapabilities
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class NetworkManagerMulticastTest {
    private val monitor = MulticastWifiNetworkMonitor()
    private val radio = mockk<Network>(name = "radio")
    private val hotspot = mockk<Network>(name = "hotspot")

    @Test
    fun acceptsRadioWifiWithoutInternetOrValidation() {
        monitor.onAvailable(radio)
        monitor.onCapabilitiesChanged(radio, wifiCapabilities())
        assertEquals(radio, monitor.wifiNetwork.value)
    }

    @Test
    fun retainsWifiAcrossCapabilityUpdatesAndAdditionalNetworks() {
        monitor.onAvailable(radio)
        monitor.onAvailable(hotspot)
        monitor.onCapabilitiesChanged(
            radio, wifiCapabilities(internet = true)
        )
        monitor.onCapabilitiesChanged(radio, wifiCapabilities())
        monitor.onLost(hotspot)
        assertEquals(radio, monitor.wifiNetwork.value)
    }

    @Test
    fun switchesOnlyWhenSelectedWifiIsLost() {
        monitor.onAvailable(radio)
        monitor.onAvailable(hotspot)
        monitor.onLost(radio)
        assertEquals(hotspot, monitor.wifiNetwork.value)
        monitor.onLost(hotspot)
        assertNull(monitor.wifiNetwork.value)
    }

    @Test
    fun unrelatedCellularCallbacksCannotClearRadioWifi() {
        monitor.onAvailable(radio)
        val cellular = mockk<Network>(name = "cellular")
        val cellularCapabilities = mockk<NetworkCapabilities> {
            every { hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns false
        }
        monitor.onCapabilitiesChanged(
            cellular, cellularCapabilities
        )
        monitor.onLost(cellular)
        assertEquals(radio, monitor.wifiNetwork.value)
    }

    @Test
    fun clearReleasesSelectedWifi() {
        monitor.onAvailable(radio)
        monitor.clear()
        assertNull(monitor.wifiNetwork.value)
    }

    private fun wifiCapabilities(internet: Boolean = false) = mockk<NetworkCapabilities> {
        every { hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true
        every { hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns internet
        every { hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns false
    }
}
