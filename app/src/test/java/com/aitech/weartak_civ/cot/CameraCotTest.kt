package com.aitech.weartak_civ.cot

import org.junit.Assert.*
import org.junit.Test

class CameraCotTest {
    private fun xml(type: String, detail: String = "") = """
        <event uid="sensor" type="$type" time="2026-10-24T12:00:00Z" start="2026-10-24T12:00:00Z" stale="2026-10-24T12:15:00Z">
          <point lat="41" lon="-87" hae="100" ce="10" le="10"/><detail>$detail</detail>
        </event>
    """.trimIndent()

    @Test fun exactVideoTypeAndDirectVideoDetailAreCameraEvidence() {
        assertTrue(CotMessageParser.parseMarkerOrNull(xml("b-i-v"))!!.detail.isCamera)
        assertTrue(CompanionGatewaySession.supported(xml("b-i-v")))
        assertTrue(CotMessageParser.parseMarkerOrNull(xml("b-m-p-s-p-i", "<__video/>"))!!.detail.isCamera)
        assertTrue(CotMessageParser.parseUserPositionOrNull(xml("a-f-G-U-C", "<__video/>"))!!.detail.isCamera)
        assertTrue(CotMessageParser.parseMarkerOrNull(xml("a-n-G",
            "<__video url='unvalidated-source-not-fetched'/>"))!!.detail.isCamera)
    }

    @Test fun sensorsSpiNamesUrlsAndEmbeddedXmlDoNotImplyCamera() {
        for (type in listOf("b-m-p-s-p-i", "a-f-G", "a-n-A-C-F")) {
            for (detail in listOf("", "<contact callsign='Camera Video'/>",
                "<remarks>rtsp://camera.invalid/feed</remarks>",
                "<remarks>&lt;__video/&gt;</remarks>",
                "<remarks><![CDATA[<__video/>]]></remarks>",
                "<extension><__video/></extension>", "<video/>")) {
                assertFalse("$type $detail", CotMessageParser.parseMarkerOrNull(xml(type, detail))!!.detail.isCamera)
            }
        }
        for (type in listOf("b-i-v-extra", "b-i-v2", "t-x-takp-v", "b-i")) {
            assertNull(CotMessageParser.parseMarkerOrNull(xml(type)))
            assertFalse(CotMessageParser.isCamera(xml(type)))
        }
        assertFalse(CotMessageParser.isCamera("<not-event type='b-i-v'><detail><__video/></detail></not-event>"))
    }

    @Test fun cameraAdmissionStillRequiresValidPointAndTimestamps() {
        assertNull(CotMessageParser.parseMarkerOrNull(xml("b-i-v").replace("lat=\"41\"", "lat=\"91\"")))
        assertNull(CotMessageParser.parseMarkerOrNull(xml("b-i-v").replace("2026-10-24T12:00:00Z", "invalid")))
    }
}
