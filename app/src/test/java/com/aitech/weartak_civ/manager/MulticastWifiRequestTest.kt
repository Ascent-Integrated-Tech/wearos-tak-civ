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
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MulticastWifiRequestTest {
    private val manager = mockk<ConnectivityManager>(relaxed = true)
    private val handler = Handler(Looper.getMainLooper())
    private val wifi = RuntimeEnvironment.getApplication().getSystemService(WifiManager::class.java)
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
        assertEquals(0, shadowOf(wifi).activeLockCount)
        requester.close()
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
        assertEquals(MulticastWifiRequest.RuntimeState(false, false), MulticastWifiRequest.runtimeState.value)
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

    @Test
    fun demandDoesNotAcquireAHighPerformanceWifiLock() {
        requester.setEnabled(true)
        assertEquals(0, shadowOf(wifi).activeLockCount)
        assertEquals(MulticastWifiRequest.RuntimeState(true, false), MulticastWifiRequest.runtimeState.value)
        requester.close()
        assertEquals(0, shadowOf(wifi).activeLockCount)
        assertEquals(MulticastWifiRequest.RuntimeState(false, false), MulticastWifiRequest.runtimeState.value)
    }

    @Test
    fun tenMinuteIdleReleaseDoesNotRearmUntilDeliberateTrigger() {
        requester.setEnabled(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(599_999))
        verify(exactly = 0) { manager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        verify(exactly = 1) { manager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
        assertEquals(MulticastWifiRequest.RuntimeState(false, true), MulticastWifiRequest.runtimeState.value)
        requester.setEnabled(true)
        requester.onPeerPacketReceived()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(20))
        verify(exactly = 1) { manager.requestNetwork(any(), any<ConnectivityManager.NetworkCallback>(), handler) }
        requester.setEnabled(true, resetIdle = true)
        verify(exactly = 2) { manager.requestNetwork(any(), any<ConnectivityManager.NetworkCallback>(), handler) }
        requester.close()
    }

    @Test
    fun peerPacketsResetIdleDeadlineButNetworkCallbacksDoNot() {
        val callback = slot<ConnectivityManager.NetworkCallback>()
        every { manager.requestNetwork(any(), capture(callback), handler) } returns Unit
        requester.setEnabled(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(9))
        requester.onPeerPacketReceived()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(9))
        val network = mockk<android.net.Network>(relaxed = true)
        callback.captured.onAvailable(network)
        callback.captured.onLost(network)
        callback.captured.onAvailable(network)
        verify(exactly = 0) { manager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        verify(exactly = 1) { manager.unregisterNetworkCallback(callback.captured) }
        requester.close()
    }

    @Test
    fun unregisterFailureIsSurfacedAndDoesNotLeaveAnIdleTimer() {
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
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(10))
        verify(exactly = 1) { manager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
    }

    @Test fun oldOwnerClosingDoesNotOverwriteReplacementRequestState() {
        requester.setEnabled(true)
        val replacement = MulticastWifiRequest(manager, handler)
        replacement.setEnabled(true)
        requester.close()
        assertEquals(MulticastWifiRequest.RuntimeState(true, false), MulticastWifiRequest.runtimeState.value)
        replacement.close()
    }
}
