package com.aitech.weartak_civ.service

import android.app.Application
import android.net.Network
import com.aitech.weartak_civ.TakMulticastEndpoints
import com.aitech.weartak_civ.WifiMulticastReceiver
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.every
import io.mockk.mockk
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
                configure(enabled.copy(companionConnected = true), false)
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
