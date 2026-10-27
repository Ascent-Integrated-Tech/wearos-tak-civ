package com.aitech.weartak_civ.service

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DittoNetworkMonitorTest {
    @Test fun callbacksSupportLocalWifiCellInternetAndLosingOneOfSeveralNetworks() {
        val context = mockk<Context>()
        val connectivity = mockk<ConnectivityManager>(relaxed = true)
        val wifi = mockk<Network>()
        val cell = mockk<Network>()
        every { context.getSystemService(ConnectivityManager::class.java) } returns connectivity
        every { connectivity.allNetworks } returns emptyArray()
        val states = mutableListOf<Pair<Boolean, Boolean>>()
        val monitor = DittoNetworkMonitor(context) { w, i -> states += w to i }
        monitor.start()
        assertEquals(false to false, states.last())
        monitor.onCapabilitiesChanged(wifi, caps(wifi = true))
        assertEquals(true to false, states.last())
        monitor.onCapabilitiesChanged(cell, caps(internet = true, validated = false))
        assertEquals(true to false, states.last())
        monitor.onCapabilitiesChanged(cell, caps(internet = true))
        assertEquals(true to true, states.last())
        monitor.onLost(wifi)
        assertEquals(false to true, states.last())
        monitor.onLost(cell)
        assertEquals(false to false, states.last())
        monitor.close()
        monitor.close()
        val count = states.size
        monitor.onCapabilitiesChanged(wifi, NetworkCapabilities())
        assertEquals(count, states.size)
        verify(exactly = 1) { connectivity.registerNetworkCallback(any<NetworkRequest>(), monitor) }
        verify(exactly = 1) { connectivity.unregisterNetworkCallback(monitor) }
    }

    private fun caps(wifi: Boolean = false, internet: Boolean = false, validated: Boolean = internet) =
        mockk<NetworkCapabilities>(relaxed = true) {
            every { hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns wifi
            every { hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns internet
            every { hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns validated
        }
}
