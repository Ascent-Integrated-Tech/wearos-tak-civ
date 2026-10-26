package com.aitech.weartak_civ.manager

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.slot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class TakNetworkMonitorTest {
    private val cell = mockk<Network>(name = "cell")
    private val wifi = mockk<Network>(name = "wifi")
    private val manager = mockk<ConnectivityManager>(relaxed = true)
    private var default: Network? = cell
    private val context = mockk<Context> {
        every { getSystemService(ConnectivityManager::class.java) } returns manager
    }

    private fun caps(wifi: Boolean, internet: Boolean = true, validated: Boolean = true, vpn: Boolean = false) =
        mockk<NetworkCapabilities> {
            every { hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns wifi
            every { hasTransport(NetworkCapabilities.TRANSPORT_VPN) } returns vpn
            every { hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns internet
            every { hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns validated
        }

    private fun start(): TakNetworkPreference {
        every { manager.activeNetwork } answers { default }
        every { manager.allNetworks } returns arrayOf(cell)
        every { manager.getNetworkCapabilities(cell) } returns caps(false)
        return TakNetworkPreference(context).also { it.start() }
    }

    @Test fun validatedWifiWinsAndUnrelatedCellCallbacksCannotDislodgeIt() {
        val monitor = start()
        monitor.onCapabilitiesChanged(wifi, caps(true))
        assertEquals(wifi, monitor.network.value)
        monitor.onCapabilitiesChanged(cell, caps(false))
        assertEquals(wifi, monitor.network.value)
        monitor.onLost(cell)
        assertEquals(wifi, monitor.network.value)
        monitor.stop()
        monitor.stop()
        verify(exactly = 1) { manager.unregisterNetworkCallback(monitor) }
    }

    @Test fun captiveOrLocalOnlyWifiDoesNotDisplaceLteAndValidationLossFallsBack() {
        val monitor = start()
        monitor.onCapabilitiesChanged(wifi, caps(true, validated = false))
        assertEquals(cell, monitor.network.value)
        monitor.onCapabilitiesChanged(wifi, caps(true, internet = false))
        assertEquals(cell, monitor.network.value)
        monitor.onCapabilitiesChanged(wifi, caps(true))
        assertEquals(wifi, monitor.network.value)
        monitor.onCapabilitiesChanged(wifi, caps(true, validated = false))
        assertEquals(cell, monitor.network.value)
        monitor.onCapabilitiesChanged(wifi, caps(true))
        monitor.onLost(wifi)
        assertEquals(cell, monitor.network.value)
        default = null
        monitor.onLost(cell)
        assertNull(monitor.network.value)
        monitor.stop()
        monitor.onCapabilitiesChanged(wifi, caps(true))
        assertNull(monitor.network.value)
    }

    @Test fun wifiRemainsPreferredWhenItLosesOnlyDefaultStatus() {
        val callback = slot<ConnectivityManager.NetworkCallback>()
        every { manager.registerDefaultNetworkCallback(capture(callback), any()) } returns Unit
        val monitor = start()
        monitor.onCapabilitiesChanged(wifi, caps(true))
        default = cell
        callback.captured.onLost(wifi)
        callback.captured.onCapabilitiesChanged(cell, caps(false))
        assertEquals(wifi, monitor.network.value)
        monitor.stop()
    }

    @Test fun transportPreferenceDoesNotBypassDefaultVpn() {
        val monitor = start()
        monitor.onCapabilitiesChanged(wifi, caps(true))
        val vpn = mockk<Network>()
        default = vpn
        every { manager.getNetworkCapabilities(vpn) } returns caps(false, vpn = true)
        monitor.onCapabilitiesChanged(vpn, caps(false, vpn = true))
        assertEquals(vpn, monitor.network.value)
        monitor.onCapabilitiesChanged(wifi, caps(true))
        assertEquals(vpn, monitor.network.value)
        monitor.stop()
    }
}
