package com.aitech.weartak_civ.manager

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MulticastWifiRequestTest {
    private val manager = mockk<ConnectivityManager>(relaxed = true)
    private val handler = Handler(Looper.getMainLooper())
    private val requester = MulticastWifiRequest(manager, handler)

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
    }

    @Test
    fun disablingOrClosingReleasesExactlyTheRegisteredCallback() {
        val callback = slot<ConnectivityManager.NetworkCallback>()
        every { manager.requestNetwork(any(), capture(callback), handler) } returns Unit

        requester.setEnabled(false)
        verify(exactly = 0) { manager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
        requester.setEnabled(true)
        requester.setEnabled(false)
        requester.close()

        verify(exactly = 1) { manager.unregisterNetworkCallback(callback.captured) }
    }

    @Test
    fun canEnableAgainAfterRelease() {
        requester.setEnabled(true)
        requester.close()
        requester.setEnabled(true)
        requester.close()

        verify(exactly = 2) { manager.requestNetwork(any(), any<ConnectivityManager.NetworkCallback>(), handler) }
        verify(exactly = 2) { manager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
    }

    @Test
    fun failedRequestIsSurfacedAndNotUnregistered() {
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
    }
}
