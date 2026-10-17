package com.aitech.weartak_civ.manager

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MulticastWifiRequestTest {
    private val manager = mockk<ConnectivityManager>(relaxed = true)
    private val handler = Handler(Looper.getMainLooper())
    private val wifi = RuntimeEnvironment.getApplication().getSystemService(WifiManager::class.java)
    private val lock = mockk<WifiManager.WifiLock>(relaxed = true)
    private val requester = MulticastWifiRequest(manager, mockk<WifiManager>().also {
        every { it.createWifiLock(any<Int>(), any<String>()) } returns lock
    }, handler)

    @Test
    fun requestsWifiWithoutRequiringInternetAndDoesNotDuplicateRequests() {
        val request = slot<NetworkRequest>()
        every { manager.requestNetwork(capture(request), any<ConnectivityManager.NetworkCallback>(), handler) } returns Unit

        requester.setEnabled(true)
        requester.setEnabled(true)

        assertTrue(request.captured.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        assertFalse(request.captured.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertFalse(request.captured.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        verify(exactly = 1) { manager.requestNetwork(any(), any<ConnectivityManager.NetworkCallback>(), handler) }
        verify(exactly = 1) { lock.setReferenceCounted(false) }
        verify(exactly = 1) { lock.acquire() }
    }

    @Test
    fun disablingOrClosingReleasesExactlyTheRegisteredCallback() {
        val callback = slot<ConnectivityManager.NetworkCallback>()
        every { manager.requestNetwork(any(), capture(callback), handler) } returns Unit
        every { lock.isHeld } returns true

        requester.setEnabled(false)
        verify(exactly = 0) { manager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
        requester.setEnabled(true)
        requester.setEnabled(false)
        requester.close()

        verify(exactly = 1) { manager.unregisterNetworkCallback(callback.captured) }
        verify(exactly = 1) { lock.release() }
    }

    @Test
    fun canEnableAgainAfterRelease() {
        every { lock.isHeld } returns true
        requester.setEnabled(true)
        requester.close()
        requester.setEnabled(true)
        requester.close()

        verify(exactly = 2) { manager.requestNetwork(any(), any<ConnectivityManager.NetworkCallback>(), handler) }
        verify(exactly = 2) { manager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
        verify(exactly = 2) { lock.acquire() }
        verify(exactly = 2) { lock.release() }
    }

    @Test
    fun failedRequestIsSurfacedAndNotUnregistered() {
        every { lock.isHeld } returns true
        every { manager.requestNetwork(any(), any<ConnectivityManager.NetworkCallback>(), handler) } throws SecurityException("denied")
        var failed = false
        try {
            requester.setEnabled(true)
        } catch (_: SecurityException) {
            failed = true
        }
        assertTrue(failed)
        requester.close()
        verify(exactly = 0) { manager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
        verify(exactly = 1) { lock.release() }
    }

    @Test
    fun actualWifiLockIsHeldUntilMulticastStops() {
        val requester = MulticastWifiRequest(manager, wifi, handler)
        requester.setEnabled(true)
        assertEquals(1, shadowOf(wifi).activeLockCount)
        requester.close()
        assertEquals(0, shadowOf(wifi).activeLockCount)
    }

    @Test
    fun deniedLockDoesNotRequestNetworkAndCanBeRetried() {
        every { lock.acquire() } throws SecurityException("denied")
        var failed = false
        try {
            requester.setEnabled(true)
        } catch (_: SecurityException) {
            failed = true
        }
        assertTrue(failed)
        verify(exactly = 0) { manager.requestNetwork(any(), any<ConnectivityManager.NetworkCallback>(), handler) }
        every { lock.acquire() } returns Unit
        requester.setEnabled(true)
        requester.close()
        verify(exactly = 1) { manager.requestNetwork(any(), any<ConnectivityManager.NetworkCallback>(), handler) }
    }

    @Test
    fun connectionLossKeepsRequestAndLockUntilDisabled() {
        val callback = slot<ConnectivityManager.NetworkCallback>()
        every { lock.isHeld } returns true
        every { manager.requestNetwork(any(), capture(callback), handler) } returns Unit
        requester.setEnabled(true)
        val network = mockk<android.net.Network>(relaxed = true)
        callback.captured.onAvailable(network)
        callback.captured.onLost(network)
        callback.captured.onAvailable(network)
        verify(exactly = 0) { lock.release() }
        verify(exactly = 0) { manager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
        requester.close()
        verify(exactly = 1) { lock.release() }
    }

    @Test
    fun unregisterFailureStillReleasesPowerLockAndIsSurfaced() {
        every { lock.isHeld } returns true
        every { manager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } throws
            IllegalArgumentException("already unregistered")
        requester.setEnabled(true)
        var failed = false
        try {
            requester.close()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
        requester.close()
        verify(exactly = 1) { lock.release() }
    }
}
