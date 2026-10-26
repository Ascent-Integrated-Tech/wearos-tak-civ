package com.aitech.weartak_civ.service

import android.app.Application
import android.net.Network
import com.aitech.weartak_civ.NetworkUtils
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.MulticastSocket
import javax.net.ssl.SSLSocket

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CommandReplyWriteTest {
    private var previousStopping = false
    private var previousEnabled = true
    private var previousMulticast = true
    private var previousNetwork = false
    private lateinit var socket: SSLSocket
    private lateinit var detail: TAKConnectService.TakServerDetail

    @Before fun setup() {
        previousStopping = TAKConnectService.serviceStopping
        previousEnabled = TAKConnectService.takEndpointEnabled
        previousMulticast = MainActivity.enableMulticastOnWifi
        previousNetwork = TAKConnectService.isNetworkConnected
        TAKConnectService.isNetworkConnected = false
        TAKConnectService.serviceStopping = false
        TAKConnectService.takEndpointEnabled = true
        MainActivity.enableMulticastOnWifi = true
        MainActivity.dataRepository = mockk<DataRepository> {
            every { isCompanionConnected } returns MutableStateFlow(false)
        }
        socket = mockk {
            every { isConnected } returns true
            every { isClosed } returns false
            every { close() } just Runs
        }
        detail = TAKConnectService.TakServerDetail("server", true, "test", "tak.invalid",
            8089, false, "", "", null, null)
        detail.runtime.generation = 7
        detail.runtime.currentAttempt = TAKConnectService.ConnectionAttempt(7).apply { socket = this@CommandReplyWriteTest.socket }
        detail.secureSocket = socket
        detail.connectionStatus.set(TAKConnectService.TAK_SERVER_CONNECTED_COT_PORT)
    }

    @After fun cleanup() {
        NetworkUtils.MulticastPublisher.stop()
        TAKConnectService.serviceStopping = previousStopping
        TAKConnectService.takEndpointEnabled = previousEnabled
        MainActivity.enableMulticastOnWifi = previousMulticast
        TAKConnectService.isNetworkConnected = previousNetwork
        unmockkAll()
    }

    @Test fun takTrueRequiresActualWriteAndFlushBeforeReturning() {
        val bytes = ByteArrayOutputStream()
        var flushed = false
        every { socket.outputStream } returns object : OutputStream() {
            override fun write(b: Int) { bytes.write(b) }
            override fun flush() { flushed = true }
        }
        assertTrue(TAKConnectService.writeCommandReply(detail, "<warning/>"))
        assertEquals("<warning/>", bytes.toString("UTF-8"))
        assertTrue(flushed)
    }

    @Test fun failedWriteOrFlushNeverReturnsSuccess() {
        for (failOnFlush in listOf(false, true)) {
            detail.secureSocket = socket
            detail.connectionStatus.set(TAKConnectService.TAK_SERVER_CONNECTED_COT_PORT)
            var wrote = false
            every { socket.outputStream } returns object : OutputStream() {
                override fun write(b: Int) {
                    wrote = true
                    if (!failOnFlush) throw IOException("write failure")
                }
                override fun flush() { throw IOException("flush failure") }
            }
            assertFalse(TAKConnectService.writeCommandReply(detail, "<warning/>"))
            assertTrue(wrote)
        }
    }

    @Test fun unavailableOrStaleTakSessionCannotReportWriteAccepted() {
        detail.runtime.generation++
        assertFalse(TAKConnectService.writeCommandReply(detail, "<warning/>"))
        detail.runtime.generation--
        every { socket.isClosed } returns true
        assertFalse(TAKConnectService.writeCommandReply(detail, "<warning/>"))
        every { socket.isClosed } returns false
        TAKConnectService.takEndpointEnabled = false
        assertFalse(TAKConnectService.writeCommandReply(detail, "<warning/>"))
        verify(exactly = 0) { socket.outputStream }
    }

    @Test fun multicastTrueRequiresDatagramSendAndSendFailurePropagates() {
        mockkConstructor(MulticastSocket::class)
        every { anyConstructed<MulticastSocket>().reuseAddress = any() } just Runs
        every { anyConstructed<MulticastSocket>().timeToLive = any() } just Runs
        every { anyConstructed<MulticastSocket>().bind(any()) } just Runs
        every { anyConstructed<MulticastSocket>().close() } just Runs
        val network = mockk<Network>(relaxed = true)
        assertTrue(NetworkUtils.MulticastPublisher.start(network, "239.2.3.1", 6969))
        var sent = false
        every { anyConstructed<MulticastSocket>().send(any()) } answers {
            val packet = firstArg<DatagramPacket>()
            assertEquals(6969, packet.port)
            assertEquals("239.2.3.1", packet.address.hostAddress)
            sent = true
        }
        assertTrue(NetworkUtils.MulticastPublisher.sendCommandReply("<warning/>", "239.2.3.1", 6969))
        assertTrue(sent)
        every { anyConstructed<MulticastSocket>().send(any()) } throws IOException("send failed")
        assertThrows(IOException::class.java) {
            NetworkUtils.MulticastPublisher.sendCommandReply("<warning/>", "239.2.3.1", 6969)
        }
        NetworkUtils.MulticastPublisher.stop()
        assertFalse(NetworkUtils.MulticastPublisher.sendCommandReply("<warning/>", "239.2.3.1", 6969))
    }
}
