package com.aitech.weartak_civ

import com.aitech.weartak_civ.cot.CotMarkerEvent
import com.aitech.weartak_civ.cot.CotUserPositionEvent
import com.aitech.weartak_civ.cot.TakServerCotEnvelope
import com.aitech.weartak_civ.cot.TakServerCotIngestResult
import com.aitech.weartak_civ.cot.TakServerCotIngestor
import com.aitech.weartak_civ.cot.TakServerCotSink
import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.model.GeofenceCot
import com.aitech.weartak_civ.events.TakChatEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket

class WifiMulticastReceiverTest {
    @Test
    fun selfEchoUsesAllLocalAddressesRatherThanEventUid() {
        val addresses = setOf("10.0.1.118", "fe80::1", "127.0.0.1")
        addresses.forEach { assertTrue(WifiMulticastReceiver.isSelfEcho(it, addresses)) }
        assertFalse(WifiMulticastReceiver.isSelfEcho("10.0.1.120", addresses))
        assertFalse(WifiMulticastReceiver.isSelfEcho(null, addresses))
        assertFalse(WifiMulticastReceiver.isSelfEcho("10.0.1.118", emptySet()))
    }

    @Test
    fun ownMarkerDatagramIsNotDeliveredToIngress() = runBlocking {
        val socket = localSocket()
        val ignored = Channel<Unit>(Channel.UNLIMITED)
        var deliveries = 0
        val receiver = WifiMulticastReceiver(
            subscriptions = listOf(WifiMulticastReceiver.Subscription(TakMulticastEndpoints.directCot, socket)),
            localAddresses = setOf(socket.localAddress.hostAddress),
            logPacket = { if (it.startsWith("Ignoring multicast self-echo")) ignored.trySend(Unit) },
            releaseLock = {}
        )
        receiver.use {
            val receiving = launch { receiver.receive { deliveries++ } }
            try {
                DatagramSocket().use { send(it, socket, MARKER_XML) }
                withTimeout(3000) { ignored.receive() }
            } finally {
                withTimeout(3000) { receiving.cancelAndJoin() }
            }
        }
        assertEquals(0, deliveries)
        assertTrue(socket.isClosed)
    }

    @Test
    fun diagnosticsRespectPacketOffsetAndDoNotDumpPayload() {
        val bytes = byteArrayOf(0, 0xbf.toByte(), 1, 0xbf.toByte(), 65, 66, 67)
        val packet = DatagramPacket(bytes, 1, 6, InetAddress.getLoopbackAddress(), 17012)

        val summary = WifiMulticastReceiver.packetSummary(TakMulticastEndpoints.chat, packet)

        assertTrue(summary.contains("group=224.10.10.1:17012"))
        assertTrue(summary.contains("bytes=6"))
        assertTrue(summary.endsWith("prefixHex=[bf 01 bf]"))
    }

    @Test
    fun diagnosticsHandleShortPacketsWithoutReadingStaleBufferBytes() {
        val packet = DatagramPacket(byteArrayOf(60, 65, 66), 1)

        assertTrue(
            WifiMulticastReceiver.packetSummary(TakMulticastEndpoints.chat, packet)
                .endsWith("prefixHex=[3c]")
        )
    }

    @Test
    fun receivesAllEndpointsAndRoutesChatContactsAndDirectMarkersAfterMalformedDatagram() = runBlocking {
        val endpoints = TakMulticastEndpoints.receiveEndpoints("239.2.3.1", 6969)
        val subscriptions = endpoints.map { WifiMulticastReceiver.Subscription(it, localSocket()) }
        var released = false
        val receiver = WifiMulticastReceiver(subscriptions) { released = true }
        val users = mutableListOf<TakServerCotEnvelope>()
        val chats = mutableListOf<TakChatEvent>()
        val markers = mutableListOf<TakServerCotEnvelope>()
        val ingestor = TakServerCotIngestor(
            sink = object : TakServerCotSink {
                override suspend fun onChat(event: TakChatEvent) { chats += event }
                override suspend fun onGeofence(geofence: GeofenceCot) = Unit
                override suspend fun onRoute(route: TakRoute,
                    envelope: TakServerCotEnvelope
                ) = Unit
                override suspend fun onUser(event: CotUserPositionEvent, envelope: TakServerCotEnvelope) {
                    users += envelope
                }
                override suspend fun onMarker(event: CotMarkerEvent, envelope: TakServerCotEnvelope) {
                    markers += envelope
                }
            },
            isChatEnabled = { true },
            isGeofenceEnabled = { false },
            deviceUid = { "watch-1" },
            deviceCallsign = { "WATCH" },
            logger = { _, _ -> }
        )
        val results = Channel<TakServerCotIngestResult>(Channel.UNLIMITED)
        receiver.use {
            val receiving = launch {
                receiver.receive { envelope -> results.send(ingestor.ingest(envelope)) }
            }
            try {
                DatagramSocket().use { sender ->
                    send(sender, subscriptions[0].socket, "<bad/>")
                    send(sender, subscriptions[0].socket, USER_XML)
                    send(sender, subscriptions[1].socket, CHAT_XML)
                    send(sender, subscriptions[2].socket, MARKER_XML)
                }
                val received = withTimeout(5000) { List(4) { results.receive() } }
                assertEquals(
                    setOf(TakServerCotIngestResult.MALFORMED, TakServerCotIngestResult.USER, TakServerCotIngestResult.CHAT, TakServerCotIngestResult.MARKER),
                    received.toSet()
                )
                assertEquals(endpoints[0].sourceId, users.single().sourceServerId)
                assertEquals(USER_XML, users.single().rawXml)
                assertEquals(endpoints[1].sourceId, chats.single().sourceServerId)
                assertEquals("Hello from multicast", chats.single().parsedRooms?.single()?.messageList?.single()?.message)
                assertEquals(TakMulticastEndpoints.directCot.sourceId, markers.single().sourceServerId)
                assertEquals(MARKER_XML, markers.single().rawXml)
            } finally {
                withTimeout(3000) { receiving.cancelAndJoin() }
            }
        }
        assertTrue(released)
        assertTrue(subscriptions.all { it.socket.isClosed })
    }

    @Test
    fun socketFailureIsSurfacedAndResourcesAreReleased() = runBlocking {
        val socket = localSocket()
        var released = false
        val receiver = WifiMulticastReceiver(
            listOf(WifiMulticastReceiver.Subscription(TakMulticastEndpoints.chat, socket))
        ) { released = true }
        receiver.use {
            supervisorScope {
                val receiving = async { receiver.receive { } }
                socket.close()
                var failed = false
                try {
                    withTimeout(3000) { receiving.await() }
                } catch (_: IOException) {
                    failed = true
                }

                @Test
                fun multicastRouteGeometryUsesSharedRouteIngressAndRetainsActualPeerAddress() = runBlocking {
                    val socket = localSocket()
                    val receiver = WifiMulticastReceiver(
                        listOf(WifiMulticastReceiver.Subscription(TakMulticastEndpoints.directCot, socket))
                    ) {}
                    val routes = Channel<Pair<TakRoute, TakServerCotEnvelope>>(Channel.UNLIMITED)
                    val ingestor = TakServerCotIngestor(
                        sink = object : TakServerCotSink {
                            override suspend fun onChat(event: TakChatEvent) = Unit
                            override suspend fun onGeofence(geofence: GeofenceCot) = Unit
                            override suspend fun onUser(event: CotUserPositionEvent, envelope: TakServerCotEnvelope) = Unit
                            override suspend fun onMarker(event: CotMarkerEvent, envelope: TakServerCotEnvelope) = Unit
                            override suspend fun onRoute(route: TakRoute, envelope: TakServerCotEnvelope) {
                                routes.send(route to envelope)
                            }
                        },
                        isChatEnabled = { false }, isGeofenceEnabled = { false },
                        deviceUid = { "watch" }, deviceCallsign = { "WATCH" }, logger = { _, _ -> }
                    )
                    receiver.use {
                        val receiving = launch { receiver.receive { ingestor.ingest(it) } }
                        try {
                            DatagramSocket().use { sender ->
                                send(sender, socket, "<event uid='route-local' type='b-m-r'><detail>" +
                                    "<contact callsign='Local route'/><link point='41,-87' callsign='Start'/>" +
                                    "<link point='41.01,-87.01' callsign=''/><link point='41.02,-87.02' callsign='Finish'/>" +
                                    "</detail></event>")
                            }
                            val (route, envelope) = withTimeout(3000) { routes.receive() }
                            assertEquals(TakMulticastEndpoints.directCot.sourceId, route.serverId)
                            assertEquals("Local route", route.name)
                            assertEquals(3, route.points.size)
                            assertEquals(41.01, route.points[1].latitude, 0.0)
                            assertEquals(socket.localAddress.hostAddress, envelope.sourcePeerAddress)
                            assertEquals(TakMulticastEndpoints.directCot.address, envelope.sourceServerAddress)
                            assertEquals(null, envelope.sourceSessionId)
                        } finally {
                            withTimeout(3000) { receiving.cancelAndJoin() }
                        }
                    }
                }
                assertTrue(failed)
            }
        }
        assertTrue(released)
        assertTrue(socket.isClosed)
    }

    private fun localSocket() = MulticastSocket(null).apply {
        bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        soTimeout = 100
    }

    private fun send(sender: DatagramSocket, socket: MulticastSocket, xml: String) {
        val bytes = xml.toByteArray(Charsets.UTF_8)
        sender.send(DatagramPacket(bytes, bytes.size, socket.localAddress, socket.localPort))
    }

    private companion object {
        const val USER_XML = "<event uid='user-1' type='a-f-G-U-C' time='2099-05-01T12:00:00Z' stale='2099-05-01T12:05:00Z'><point lat='41' lon='-87' hae='100' ce='4' le='5'/><detail><contact callsign='USER'/><__group name='Cyan' role='Team Member'/></detail></event>"
        const val CHAT_XML = "<event uid='chat-1' type='b-t-f'><detail><__chat senderCallsign='SENDER'><chatgrp uid0='sender-1' uid1='watch-1'/></__chat><remarks to='watch-1'>Hello from multicast</remarks></detail></event>"
        const val MARKER_XML = "<event uid='marker-1' type='a-h-G' time='2099-05-01T12:00:00Z' stale='2099-05-01T12:05:00Z'><point lat='41' lon='-87' hae='100' ce='4' le='5'/><detail><contact callsign='TARGET'/></detail></event>"
    }
}
