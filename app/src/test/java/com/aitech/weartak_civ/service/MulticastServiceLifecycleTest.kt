package com.aitech.weartak_civ.service

import android.app.Application
import android.net.Network
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import com.aitech.weartak_civ.TakMulticastEndpoints
import com.aitech.weartak_civ.WifiMulticastReceiver
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.manager.MulticastWifiRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MulticastServiceLifecycleTest {
    @Test fun wifiDemandRequiresValidEnabledEndpointWithoutCompanionAndRearmsOnConfigurationChange() = runBlocking {
        MainActivity.mainActivity = mockk {
            every { applicationContext } returns RuntimeEnvironment.getApplication()
        }
        val manager = mockk<ConnectivityManager>(relaxed = true)
        val handler = Handler(Looper.getMainLooper())
        val requester = MulticastWifiRequest(manager, handler)
        val configs = Channel<TAKConnectService.MulticastConfig>(Channel.UNLIMITED)
        val requested = Channel<Unit>(Channel.UNLIMITED)
        val released = Channel<Unit>(Channel.UNLIMITED)
        every { manager.requestNetwork(any(), any<ConnectivityManager.NetworkCallback>(), handler) } answers {
            requested.trySend(Unit)
            Unit
        }
        every { manager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } answers {
            released.trySend(Unit)
            Unit
        }
        val service = TAKConnectService()
        val collector = launch { service.collectMulticastWifiRequests(configs.receiveAsFlow(), requester) }
        val valid = TAKConnectService.MulticastConfig(null, true, "239.2.3.1", 6969)
        try {
            listOf(valid.copy(enable = false), valid.copy(watchTransportSuppressed = true),
                valid.copy(address = ""), valid.copy(address = "192.0.2.1"),
                valid.copy(port = 0), valid.copy(port = 65536), valid.copy(port = null)
            ).forEach { configs.send(it) }
            configs.send(valid)
            withTimeout(3_000) { requested.receive() }
            verify(exactly = 1) {
                manager.requestNetwork(any(), any<ConnectivityManager.NetworkCallback>(), handler)
            }
            configs.send(valid.copy(watchTransportSuppressed = true))
            withTimeout(3_000) { released.receive() }
            configs.send(valid.copy(port = 7000))
            withTimeout(3_000) { requested.receive() }
            configs.send(valid.copy(enable = false))
            withTimeout(3_000) { released.receive() }
        } finally {
            withTimeout(3_000) { collector.cancelAndJoin() }
        }
        verify(exactly = 2) {
            manager.requestNetwork(any(), any<ConnectivityManager.NetworkCallback>(), handler)
        }
    }

    @Test fun serviceCollectorDeduplicatesAndClosesOnWifiLossReplacementDisableAndShutdown() = runBlocking {
        MainActivity.mainActivity = mockk {
            every { applicationContext } returns RuntimeEnvironment.getApplication()
        }
        val service = TAKConnectService()
        val configs = Channel<TAKConnectService.MulticastConfig>(Channel.UNLIMITED)
        val processed = Channel<Boolean>(Channel.UNLIMITED)
        val opened = Channel<MulticastSocket>(Channel.UNLIMITED)
        val live = AtomicInteger()
        val totalOpened = AtomicInteger()
        val totalClosed = AtomicInteger()
        val wifi = mockk<Network>(name = "wifi")
        val otherWifi = mockk<Network>(name = "other-wifi")
        val enabled = TAKConnectService.MulticastConfig(wifi, true, "239.2.3.1", 6969)
        val collector = launch {
            service.collectMulticastConfigurations(
                configs.receiveAsFlow(),
                updatePublisher = { _, running -> processed.trySend(running) },
                openReceiver = {
                    assertEquals(1, live.incrementAndGet())
                    totalOpened.incrementAndGet()
                    val socket = MulticastSocket(null).apply {
                        bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                        soTimeout = 50
                    }
                    opened.trySend(socket)
                    WifiMulticastReceiver(
                        listOf(WifiMulticastReceiver.Subscription(TakMulticastEndpoints.directCot, socket))
                    ) {
                        live.decrementAndGet()
                        totalClosed.incrementAndGet()
                    }
                }
            )
        }
        suspend fun configure(config: TAKConnectService.MulticastConfig, running: Boolean) {
            configs.send(config)
            assertEquals(running, withTimeout(3000) { processed.receive() })
        }
        try {
            configure(enabled.copy(enable = false), false)
            configure(enabled.copy(address = ""), false)
            configure(enabled.copy(address = "192.0.2.1"), false)
            configure(enabled.copy(port = 65536), false)
            assertEquals(0, totalOpened.get())
            configure(enabled, true)
            val first = withTimeout(3000) { opened.receive() }
            configs.send(enabled)
            configure(enabled.copy(net = null), false)
            assertTrue(first.isClosed)
            assertEquals(1, totalOpened.get())
            configure(enabled.copy(net = otherWifi), true)
            val second = withTimeout(3000) { opened.receive() }
            configure(enabled, true)
            val third = withTimeout(3000) { opened.receive() }
            assertTrue(second.isClosed)
            repeat(2) {
                configure(enabled.copy(watchTransportSuppressed = true), false)
                assertTrue(if (it == 0) third.isClosed else live.get() == 0)
                configure(enabled, true)
                withTimeout(3000) { opened.receive() }
            }
            configure(enabled.copy(enable = false), false)
            assertTrue(third.isClosed)
            configure(enabled, true)
            val last = withTimeout(3000) { opened.receive() }
            withTimeout(3000) { collector.cancelAndJoin() }
            assertTrue(last.isClosed)
            assertEquals(0, live.get())
            assertEquals(6, totalOpened.get())
            assertEquals(totalOpened.get(), totalClosed.get())
        } finally {
            withTimeout(3000) { collector.cancelAndJoin() }
        }
    }
}
