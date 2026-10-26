package com.aitech.weartak_civ

import org.junit.Assert.assertEquals
import org.junit.Test

class TakMulticastEndpointsTest {
    @Test fun demandAndReceiverEndpointValidationAgreeOnIpv4MulticastAndPorts() {
        assertEquals(true, TakMulticastEndpoints.isValidConfiguredEndpoint("239.2.3.1", 6969))
        for ((address, port) in listOf(null to 6969, "" to 6969, "192.0.2.1" to 6969,
            "ff02::1" to 6969, "239.2.3.1" to 0, "239.2.3.1" to 65536, "239.2.3.1" to null)) {
            assertEquals(false, TakMulticastEndpoints.isValidConfiguredEndpoint(address, port))
        }
    }

    @Test
    fun advertisesUdpChatOnlyInMulticastPositionCopy() {
        val xml = "<event uid='watch-1' type='a-f-G-U-C'><detail><contact callsign='WATCH' endpoint=\"*:-1:stcp\"/></detail></event>"
        val multicastXml = TakMulticastEndpoints.prepareOutbound(xml)
        assertEquals(
            xml.replace("endpoint=\"*:-1:stcp\"", "endpoint='224.10.10.1:17012:udp'"),
            multicastXml
        )
        assertEquals(true, xml.contains("*:-1:stcp"))
    }

    @Test
    fun leavesChatAndDroppedMarkerContactsUnchanged() {
        for (type in listOf("b-t-f", "a-f-G", "b-a-o")) {
            val xml = "<event type='$type'><detail><contact endpoint='*:-1:stcp'/></detail></event>"
            assertEquals(xml, TakMulticastEndpoints.prepareOutbound(xml))
        }
    }

    @Test
    fun subscribesToPositionGeoChatAndDirectCot() {
        assertEquals(
            listOf(
                MulticastEndpoint("239.2.3.1", 6969),
                MulticastEndpoint("224.10.10.1", 17012),
                MulticastEndpoint("224.10.10.1", 6969)
            ),
            TakMulticastEndpoints.receiveEndpoints(DEFAULT_MULTICAST_ADDRESS, DEFAULT_MULTICAST_PORT)
        )
    }

    @Test
    fun keepsCustomPositionEndpointWithoutChangingChat() {
        assertEquals(
            listOf(MulticastEndpoint("239.5.6.7", 7000), TakMulticastEndpoints.chat, TakMulticastEndpoints.directCot),
            TakMulticastEndpoints.receiveEndpoints("239.5.6.7", 7000)
        )
    }

    @Test
    fun doesNotSubscribeTwiceToTheSameEndpoint() {
        assertEquals(
            listOf(TakMulticastEndpoints.chat, TakMulticastEndpoints.directCot),
            TakMulticastEndpoints.receiveEndpoints("224.10.10.1", 17012)
        )
        assertEquals(
            listOf(TakMulticastEndpoints.directCot, TakMulticastEndpoints.chat),
            TakMulticastEndpoints.receiveEndpoints("224.10.10.1", 6969)
        )
    }

    @Test
    fun sendsGeoChatToChatGroupAndOtherCotToConfiguredGroup() {
        val chatXml = "<event type='b-t-f'><detail><__chat/><remarks>Hello</remarks></detail></event>"
        assertEquals(TakMulticastEndpoints.chat, TakMulticastEndpoints.outbound(chatXml, "239.5.6.7", 7000))
        for (type in listOf("a-f-G-U-C", "b-m-p-s-p-i", "b-a-o")) {
            assertEquals(
                MulticastEndpoint("239.5.6.7", 7000),
                TakMulticastEndpoints.outbound("<event type='$type'><detail/></event>", "239.5.6.7", 7000)
            )
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidPorts() {
        TakMulticastEndpoints.receiveEndpoints("239.2.3.1", 65536)
    }
}
