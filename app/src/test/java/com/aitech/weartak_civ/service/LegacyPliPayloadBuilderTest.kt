package com.aitech.weartak_civ.service

import com.aitech.weartak_civ.cot.CotEventTime
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.service.BatteryService.DockType
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LegacyPliPayloadBuilderTest {
    @Test
    fun preservesEsriAndTabletCompatibilityFields() {
        val payloads = LegacyPliPayloadBuilder.build(
            snapshot(),
            LegacyPliPayloadBuilder.Options(
                callsign = "EAGLE",
                role = "Team Member",
                team = "Cyan",
                includeActivityMetrics = true,
                buildEsri = true,
                buildTabletCommand = true,
            ),
        )

        val esriProperties = JSONObject(payloads.esriGeoJson!!)
            .getJSONArray("features").getJSONObject(0).getJSONObject("properties")
        val esriBiometrics = esriProperties.getJSONArray("biometrics").getJSONObject(0)
        assertEquals("watch-1", JSONObject(payloads.esriGeoJson!!).getJSONArray("features").getJSONObject(0).getString("id"))
        assertEquals(72, esriBiometrics.getInt("hr"))
        assertEquals(123, esriBiometrics.getInt("steps"))
        assertEquals(7, esriBiometrics.getInt("floors"))

        val tablet = JSONObject(payloads.tabletCommandJson!!)
        val tabletBiometrics = tablet.getJSONArray("biometrics").getJSONObject(0)
        assertEquals("watch-1", tablet.getString("id"))
        assertEquals(80, tabletBiometrics.getInt("hr"))
        assertEquals(123, tabletBiometrics.getInt("steps"))
        assertNotNull(payloads.tabletCommandJson)
    }

    private fun snapshot() = DataRepository.PliSnapshot(
        loc = DataRepository.LocationSnapshot(0, 41.0, -87.0, 100.0, 4f, 5f, 45.9f, 3.8f),
        time = CotEventTime(0, 0, 0, START, START, STALE),
        physioData = DataRepository.PhysioSnapshot(
            0,
            DataRepository.PhysioData(
                72,
                98.6f,
                30,
                70,
                60,
                .25,
                null,
                DataRepository.DailyMetrics(Instant.EPOCH, 123, 42.4, 6.7, 9.0),
            ),
        ),
        systemData = DataRepository.SystemSnapshot(
            0,
            80,
            "EAGLE",
            "watch-1",
            "1",
            10,
            DataRepository.SystemStates(0, DockType.NONE, false, true, 10, 10),
        ),
    )

    private companion object {
        const val START = "2026-08-19T12:00:00.000Z"
        const val STALE = "2026-08-19T12:00:30.000Z"
    }
}
