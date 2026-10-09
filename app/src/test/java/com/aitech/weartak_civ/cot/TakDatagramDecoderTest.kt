package com.aitech.weartak_civ.cot

import com.google.protobuf.CodedOutputStream
import com.aitech.weartak_civ.data.model.GeofenceCot
import com.aitech.weartak_civ.events.TakChatEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class TakDatagramDecoderTest {
    @Test
    fun preservesXmlAndHonorsOffset() {
        val xml = "<event uid='test'></event>"
        val bytes = ("xx" + xml + "xx").toByteArray()
        assertEquals(xml, TakDatagramDecoder.decode(bytes, 2, xml.length))
    }

    @Test
    fun decodesFriendlyEquipmentWithTypedDetailsAndZeroCoordinates() {
        val detail = proto {
            writeByteArray(2, proto { writeString(1, "10.0.0.1:4242:tcp"); writeString(2, "ATAK & K9") })
            writeByteArray(3, proto { writeString(1, "Green"); writeString(2, "K9") })
            writeByteArray(4, proto { writeString(1, "GPS"); writeString(2, "GPS") })
            writeByteArray(5, proto { writeUInt32(1, 75) })
            writeByteArray(6, proto { writeString(1, "Phone"); writeString(2, "ATAK") })
            writeByteArray(7, proto { writeDouble(1, 1.5); writeDouble(2, 90.0) })
            writeString(1, "<remarks>test</remarks>")
        }
        val data = datagram(event("a-f-G-E-V-C", detail))
        val xml = TakDatagramDecoder.decode(data, 0, data.size)!!
        val user = CotMessageParser.parseUserPositionOrNull(xml)!!
        assertEquals("ATAK & K9", user.detail.markerCallsign)
        assertEquals("Green", user.team)
        assertEquals("K9", user.role)
        assertEquals(0.0, user.point.lat, 0.0)
        assertEquals(0.0, user.point.lon, 0.0)
        assertTrue(xml.contains("battery=\"75\""))
        assertTrue(xml.contains("course=\"90.0\""))
    }

    @Test
    fun routesDecodedBinaryAllChatThroughExistingIngestor() = runTest {
        val detail = proto {
            writeString(1, "<__chat senderCallsign='PHONE'><chatgrp uid0='phone-1' uid1='All Chat Rooms'/></__chat><remarks to='All Chat Rooms'>Binary chat &amp; test</remarks>")
        }
        val data = datagram(event("b-t-f", detail))
        var chat: TakChatEvent? = null
        val ingestor = TakServerCotIngestor(
            sink = object : TakServerCotSink {
                override suspend fun onChat(event: TakChatEvent) { chat = event }
                override suspend fun onGeofence(geofence: GeofenceCot) = Unit
                override suspend fun onUser(event: CotUserPositionEvent, envelope: TakServerCotEnvelope) = Unit
                override suspend fun onMarker(event: CotMarkerEvent, envelope: TakServerCotEnvelope) = Unit
            },
            isChatEnabled = { true }, isGeofenceEnabled = { false },
            deviceUid = { "watch-1" }, deviceCallsign = { "WATCH" }, logger = { _, _ -> }
        )
        assertEquals(TakServerCotIngestResult.CHAT, ingestor.ingest(
            TakServerCotEnvelope("multicast:224.10.10.1:17012", "224.10.10.1", 1L,
                TakDatagramDecoder.decode(data, 0, data.size)!!)
        ))
        assertEquals("Binary chat & test", chat!!.parsedRooms!!.single().messageList.single().message)
    }

    @Test
    fun opaqueDetailWinsOverTypedDuplicateAndUnknownFieldsAreSkipped() {
        val detail = proto {
            writeString(1, "<contact callsign='Opaque'/>")
            writeByteArray(2, proto { writeString(2, "Typed") })
        }
        val data = datagram(event("a-f-G-U-C", detail))
        val xml = TakDatagramDecoder.decode(data, 0, data.size)!!
        assertEquals("Opaque", CotMessageParser.parseUserPositionOrNull(xml)!!.detail.markerCallsign)
        assertFalse(xml.contains("Typed"))
    }

    @Test
    fun acceptsControlOnlyMessage() {
        val data = byteArrayOf(0xbf.toByte(), 1, 0xbf.toByte()) +
            proto { writeByteArray(1, proto { writeUInt32(1, 1); writeUInt32(2, 1) }) }
        assertNull(TakDatagramDecoder.decode(data, 0, data.size))
    }

    @Test(expected = IOException::class)
    fun rejectsUnsupportedVersion() {
        TakDatagramDecoder.decode(byteArrayOf(0xbf.toByte(), 2, 0xbf.toByte()), 0, 3)
    }

    @Test(expected = IOException::class)
    fun rejectsTruncatedProtobuf() {
        TakDatagramDecoder.decode(byteArrayOf(0xbf.toByte(), 1, 0xbf.toByte(), 18, 20, 1), 0, 6)
    }

    @Test(expected = IOException::class)
    fun rejectsEntityDeclarationsInOpaqueDetail() {
        val data = datagram(event("b-t-f", proto {
            writeString(1, "<!DOCTYPE detail [<!ENTITY secret SYSTEM 'file:///not-allowed'>]>")
        }))
        TakDatagramDecoder.decode(data, 0, data.size)
    }

    @Test
    fun doesNotTreatDroppedFriendlyMarkerAsContact() {
        assertFalse(CotMessageParser.isUserPositionType("a-f-G"))
        assertFalse(CotMessageParser.isUserPositionType("a-h-G-E-V-C"))
        assertTrue(CotMessageParser.isUserPositionType("a-f-G-U-C"))
        assertTrue(CotMessageParser.isUserPositionType("a-f-G-E-V-C"))
    }

    private fun event(type: String, detail: ByteArray) = proto {
        writeString(1, type)
        writeString(5, "phone-1")
        writeUInt64(6, 4070908800000L)
        writeUInt64(7, 4070908800000L)
        writeUInt64(8, 4070908860000L)
        writeString(9, "m-g")
        writeByteArray(15, detail)
        writeString(100, "unknown future field")
    }

    private fun datagram(event: ByteArray) =
        byteArrayOf(0xbf.toByte(), 1, 0xbf.toByte()) + proto { writeByteArray(2, event) }

    private fun proto(write: CodedOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        CodedOutputStream.newInstance(bytes).apply { write(); flush() }
        return bytes.toByteArray()
    }
}
