package com.aitech.weartak_civ.service

import android.app.Application
import android.os.ParcelUuid
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CompanionAdvertisingDataTest {
    private val uuid = UUID.fromString("0000A11A-0000-1000-8000-00805F9B34FB")

    @Test fun versionAndAsciiCallsignHaveStableWireFormat() {
        assertArrayEquals(byteArrayOf(1) + "WATCH".toByteArray(Charsets.UTF_8),
            CompanionAdvertisingData.encodeCallsign("WATCH"))
        assertArrayEquals(byteArrayOf(1), CompanionAdvertisingData.encodeCallsign(""))
    }

    @Test fun truncatesAsciiAtTwelveBytes() {
        assertEquals("ABCDEFGHIJKL", decode("ABCDEFGHIJKLMNOPQRSTUVWXYZ"))
        assertEquals("ABCDEFGHIJKL", decode("ABCDEFGHIJKL"))
    }

    @Test fun truncatesAtWholeUtf8CodePointsNotUtf16Characters() {
        assertEquals("\u00e9".repeat(6), decode("\u00e9".repeat(7)))
        assertEquals("\u4e2d".repeat(4), decode("\u4e2d".repeat(5)))
        assertEquals("\uD83D\uDE80".repeat(3), decode("\uD83D\uDE80".repeat(4)))
        assertEquals("ABCDEFGHIJK", decode("ABCDEFGHIJK\u00e9"))
        assertEquals("ABCDEFGHIJ", decode("ABCDEFGHIJ\uD83D\uDE80"))
        assertEquals("ABCDEFGHI\u4e2d", decode("ABCDEFGHI\u4e2dZ"))
    }

    @Test fun bothLegacyPacketsFitEvenWithFull128BitUuidAndMaximumCallsign() {
        for (serviceUuid in listOf(uuid, UUID.fromString("12345678-1234-5678-9abc-def012345678"))) {
            val primary = CompanionAdvertisingData.primary(serviceUuid)
            assertEquals(listOf(ParcelUuid(serviceUuid)), primary.serviceUuids)
            assertFalse(primary.includeDeviceName)
            assertFalse(primary.includeTxPowerLevel)
            assertTrue(primary.serviceData.isEmpty())
            assertEquals(0, primary.manufacturerSpecificData.size())
            val primaryWorstCaseBytes = 3 + 2 + 16
            assertTrue(primaryWorstCaseBytes <= 31)

            val payload = CompanionAdvertisingData.encodeCallsign("A".repeat(1000))
            val response = CompanionAdvertisingData.scanResponse(serviceUuid, payload)
            assertFalse(response.includeDeviceName)
            assertFalse(response.includeTxPowerLevel)
            assertTrue(response.serviceUuids.isNullOrEmpty())
            assertEquals(0, response.manufacturerSpecificData.size())
            assertEquals(setOf(ParcelUuid(serviceUuid)), response.serviceData.keys)
            assertArrayEquals(payload, response.serviceData[ParcelUuid(serviceUuid)])
            assertEquals(31, 2 + 16 + response.serviceData.getValue(ParcelUuid(serviceUuid)).size)
        }
    }

    private fun decode(callsign: String): String {
        val payload = CompanionAdvertisingData.encodeCallsign(callsign)
        assertEquals(1.toByte(), payload[0])
        assertTrue(payload.size <= 13)
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload, 1, payload.size - 1)).toString()
    }
}
