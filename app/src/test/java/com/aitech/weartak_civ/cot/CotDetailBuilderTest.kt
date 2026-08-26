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
            takvOs = "WEAROS",
            takvVersion = "WearTAK-14",
            takvDevice = "Samsung Watch",
            takvPlatform = "ATAK-CIV",
            dittoA = "peer-key",
            dittoIp = "0.0.0.0",
            dittoVersion = "4.10.2",
            dittoDeviceName = "EAGLE Watch",
        )

        assertEquals(
            "<detail><takv os='WEAROS' version='WearTAK-14' device='Samsung Watch' platform='ATAK-CIV'/><contact endpoint='*:-1:stcp' callsign='EAGLE'/><uid Droid='watch-1'/><precisionlocation altsrc='GPS' geopointsrc='GPS'/><__group role='Team Member' name='Cyan'/><status battery='80'/><remarks>HR: 72 bpm; Batt: 80%</remarks><biometrics><device><model>WEAROS</model><uid>watch-1</uid><hr>72</hr><bat>80</bat></device></biometrics><track course='45.0' speed='3.0'/><ditto a='peer-key' ip='0.0.0.0' version='4.10.2' deviceName='EAGLE Watch'/></detail>",
            actual,
        )
    }
}
