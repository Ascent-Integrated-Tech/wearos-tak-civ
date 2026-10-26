package com.aitech.weartak_civ.data.takserver

import android.app.Application
import android.net.Network
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.net.InetAddress
import javax.net.SocketFactory
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class TakHttpRoutingTest {
    @Test fun sessionBindsBothDnsAndSocketsWithoutChangingTlsOrHostnameVerification() {
        val network = mockk<Network>()
        val sockets = mockk<SocketFactory>()
        val address = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))
        every { network.socketFactory } returns sockets
        every { network.getAllByName("tak.example.invalid") } returns arrayOf(address)
        val session = TakHttpSession(network) { true }
        val original = OkHttpClient()
        val routed = session.route(original)
        assertSame(sockets, routed.socketFactory)
        assertEquals(listOf(address), routed.dns.lookup("tak.example.invalid"))
        assertSame(original.sslSocketFactory, routed.sslSocketFactory)
        assertSame(original.hostnameVerifier, routed.hostnameVerifier)
        assertSame(routed.connectionPool, session.route(original).connectionPool)
        assertSame(routed.dns, session.route(original).dns)
        verify { network.getAllByName("tak.example.invalid") }
        session.cancel()
        assertThrows(IOException::class.java) { session.route(original) }
    }

    @Test fun unboundSessionPreservesOriginalClient() {
        val client = OkHttpClient()
        assertSame(client, TakHttpSession { true }.route(client))
    }
}
