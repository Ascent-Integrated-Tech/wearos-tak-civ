package com.aitech.weartak_civ.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtosCotParserTest {
    @Test
    fun validIcdSampleParsesSuccessfully() {
        val xml = """
            <?xml version="1.0" encoding="utf-8" standalone="yes"?>
            <event version="2.0" uid="atos-plugin-4" type="a-f-G-U-C-I-I"
                time="2021-10-25T15:52:40.61Z"
                start="2021-10-25T15:52:40.61Z"
                stale="2021-10-25T15:57:40.61Z"
                how="m-g">
                <point lat="35.7985556" lon="-78.6577434"
                    hae="110.750658846955"
                    ce="9999999" le="9999999" />
                <detail>
                    <contact callsign="hodor" />
                    <track course="22" speed="0.025" vspeed="1.0" />
                    <__atos tag_type="Vehicle" color="Maroon" tag_uid="4"
                        commandlink="CL-12345" temp_c="5" voltage="3.6" alarm="0"
                        manifest="Default Manifest">
                        <attributes Remark="remark" Frequency="frq"
                            Special_Equipment="equp" Vehicle_Type="tacoma"
                            Driver_Callsign="hodor" Team_Frequency="420.69" />
                    </__atos>
                    <link relation="p-p" uid="android-device" type="a-f-g" parent_callsign="PAX 1"/>
                    <archive />
                </detail>
            </event>
        """.trimIndent()

        val result = AtosCotParser.parse(xml)
        val packet = (result as AtosCotParseResult.Success).packet

        assertEquals("atos-plugin-4", packet.uid)
        assertEquals("a-f-G-U-C-I-I", packet.type)
        assertEquals("hodor", packet.contact?.callsign)
        assertEquals(35.7985556, packet.point?.lat ?: Double.NaN, 0.0)
        assertEquals(-78.6577434, packet.point?.lon ?: Double.NaN, 0.0)
        assertEquals(110.750658846955, packet.point?.hae ?: Double.NaN, 0.0)
        assertEquals(22.0, packet.track?.course ?: Double.NaN, 0.0)
        assertEquals(0.025, packet.track?.speed ?: Double.NaN, 0.0)
        assertEquals(1.0, packet.track?.vspeed ?: Double.NaN, 0.0)
        assertEquals(null, packet.track?.slope)
        assertEquals("Vehicle", packet.atosDetail?.tagType)
        assertEquals("Maroon", packet.atosDetail?.color)
        assertEquals(4, packet.atosDetail?.tagUid)
        assertEquals("CL-12345", packet.atosDetail?.commandLink)
        assertEquals(5, packet.atosDetail?.tempC)
        assertEquals(3.6, packet.atosDetail?.voltage ?: Double.NaN, 0.0)
        assertEquals(0, packet.atosDetail?.alarm)
        assertEquals("Default Manifest", packet.atosDetail?.manifest)
        assertEquals("tacoma", packet.atosDetail?.attributes?.get("Vehicle_Type"))
        assertEquals("hodor", packet.atosDetail?.attributes?.get("Driver_Callsign"))
        assertEquals("android-device", packet.link?.uid)
        assertEquals("a-f-g", packet.link?.type)
        assertEquals("p-p", packet.link?.relation)
        assertEquals("PAX 1", packet.link?.parentCallsign)
        assertEquals("hodor", packet.contact?.callsign)
        assertTrue(packet.hasArchive)
        assertTrue(packet.warnings.isEmpty())
    }

    @Test
    fun missingAtosDetailStillParsesGenericCot() {
        val xml = """
            <event version="2.0" uid="generic-1" type="a-f-G"
                time="2021-10-25T15:52:40.61Z"
                start="2021-10-25T15:52:40.61Z"
                stale="2021-10-25T15:57:40.61Z"
                how="m-g">
                <point lat="35.0" lon="-78.0" hae="9999999" ce="10" le="10" />
                <detail>
                    <contact callsign="Raptor" />
                    <track course="22" speed="0.025" vspeed="1.0" />
                    <link relation="p-p" uid="android-device" type="a-f-g"/>
                    <archive />
                </detail>
            </event>
        """.trimIndent()

        val result = AtosCotParser.parse(xml)
        val packet = (result as AtosCotParseResult.Success).packet

        assertEquals("generic-1", packet.uid)
        assertEquals("Raptor", packet.contact?.callsign)
        assertEquals(null, packet.atosDetail)
        assertTrue(packet.hasArchive)
    }

    @Test
    fun missingPointOnAtosPacketBecomesWarning() {
        val xml = """
            <event version="2.0" uid="atos-no-point" type="a-f-G-U-C-I-I"
                time="2021-10-25T15:52:40.61Z"
                start="2021-10-25T15:52:40.61Z"
                stale="2021-10-25T15:57:40.61Z"
                how="m-g">
                <detail>
                    <contact callsign="hodor" />
                    <__atos tag_type="Vehicle" color="Maroon" tag_uid="4"
                        commandlink="CL-12345" temp_c="5" voltage="3.6" alarm="0">
                        <attributes Vehicle_Type="tacoma" />
                    </__atos>
                    <archive />
                </detail>
            </event>
        """.trimIndent()

        val result = AtosCotParser.parse(xml)
        val packet = (result as AtosCotParseResult.Success).packet

        assertEquals("atos-no-point", packet.uid)
        assertTrue(packet.warnings.any { it.contains("Point element missing") })
    }

    @Test
    fun malformedXmlReturnsFailure() {
        val result = AtosCotParser.parse("<event><detail>")
        assertTrue(result is AtosCotParseResult.Failure)
    }

    @Test
    fun blankXmlReturnsFailure() {
        val result = AtosCotParser.parse("   ")
        assertTrue(result is AtosCotParseResult.Failure)
    }

    @Test
    fun oversizedXmlReturnsFailure() {
        val payload = buildString {
            append("<event version=\"2.0\" uid=\"big\" type=\"a-f-G\" time=\"2021-10-25T15:52:40.61Z\" start=\"2021-10-25T15:52:40.61Z\" stale=\"2021-10-25T15:57:40.61Z\" how=\"m-g\">")
            append("<point lat=\"35.0\" lon=\"-78.0\" hae=\"1\" ce=\"1\" le=\"1\" />")
            append("<detail><contact callsign=\"big\" /><__atos tag_type=\"Vehicle\" color=\"Maroon\" tag_uid=\"4\" commandlink=\"x\" temp_c=\"5\" voltage=\"3.6\" alarm=\"0\"><attributes ")
            append("Remark=\"")
            append("x".repeat(70 * 1024))
            append("\" /></__atos></detail></event>")
        }

        val result = AtosCotParser.parse(payload)
        assertTrue(result is AtosCotParseResult.Failure)
    }

    @Test
    fun invalidNumericFieldDoesNotCrash() {
        val xml = """
            <event version="2.0" uid="bad-num" type="a-f-G-U-C-I-I"
                time="2021-10-25T15:52:40.61Z"
                start="2021-10-25T15:52:40.61Z"
                stale="2021-10-25T15:57:40.61Z"
                how="m-g">
                <point lat="not-a-number" lon="-78.6577434"
                    hae="110.750658846955"
                    ce="9999999" le="9999999" />
                <detail>
                    <contact callsign="hodor" />
                    <track course="22" speed="0.025" vspeed="1.0" />
                    <__atos tag_type="Vehicle" color="Maroon" tag_uid="4"
                        commandlink="CL-12345" temp_c="5" voltage="3.6" alarm="0">
                        <attributes Vehicle_Type="tacoma" />
                    </__atos>
                </detail>
            </event>
        """.trimIndent()

        val result = AtosCotParser.parse(xml)
        val packet = (result as AtosCotParseResult.Success).packet

        assertEquals(null, packet.point?.lat)
        assertEquals(-78.6577434, packet.point?.lon ?: Double.NaN, 0.0)
        assertTrue(packet.warnings.isNotEmpty())
    }
}
