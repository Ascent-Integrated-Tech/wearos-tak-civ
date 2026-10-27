package com.aitech.weartak_civ.cot

import org.junit.Assert.assertEquals
import org.junit.Test

class CotDetailBuilderTest {
    @Test
    fun dittoTrackDetailMatchesLegacyWireValue() {
        val actual = CotDetailBuilder.dittoTrack(
            heartRate = 72,
            battery = 80,
            callsign = "EAGLE",
            role = "Team Member",
            team = "Cyan",
            uid = "watch-1",
            course = 45f,
            speed = 3f,
            dittoA = "peer-key",
            dittoIp = "0.0.0.0",
            dittoVersion = "4.10.2",
            dittoDeviceName = "EAGLE Watch",
            remarks = "MOPP0; HR: 72 bpm @ 12:00:00Z",
        )

        assertEquals(
            "<detail>${CotDetailBuilder.takv()}<contact endpoint='*:-1:stcp' callsign='EAGLE'/><uid Droid='watch-1'/><precisionlocation altsrc='GPS' geopointsrc='GPS'/><__group role='Team Member' name='Cyan'/><status battery='80'/><remarks>MOPP0; HR: 72 bpm @ 12:00:00Z</remarks><biometrics><device><model>WEAROS</model><uid>watch-1</uid><hr>72</hr><bat>80</bat></device></biometrics><track course='45.0' speed='3.0'/><ditto a='peer-key' ip='0.0.0.0' version='4.10.2' deviceName='EAGLE Watch'/></detail>",
            actual,
        )
    }
}
