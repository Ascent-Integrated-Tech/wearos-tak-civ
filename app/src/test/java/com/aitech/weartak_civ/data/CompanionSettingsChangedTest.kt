package com.aitech.weartak_civ.data

import android.app.Application
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CompanionSettingsChangedTest {
    private val initial = CompanionAppRepository.WearTakSettingsSnapshot(
        timeMillis = 1,
        takServerList = emptyList(),
        reportIntSecs = 60,
        callsign = "TEST-WATCH",
        team = "Cyan",
        role = "Medic",
        phoneManagedSettings = false,
        standaloneCallsign = "TEST-WATCH"
    )

    @Test fun initialDeltaIncludesIdentityAndExistingSettings() {
        val payload = deltas(initial).single()
        assertEquals(setOf("callsign", "standaloneCallsign", "team", "role", "reportIntSecs", "takServerList"), payload.fieldNames())
        assertEquals("TEST-WATCH", payload.getString("standaloneCallsign"))
        assertEquals("TEST-WATCH", payload.getString("callsign"))
        assertEquals("Cyan", payload.getString("team"))
        assertEquals("Medic", payload.getString("role"))
        assertEquals(60, payload.getInt("reportIntSecs"))
        assertEquals(0, payload.getJSONArray("takServerList").length())
    }

    @Test fun teamOnlyChangeIsNotDropped() {
        assertOnlyChange("team", "Red", initial.copy(team = "Red"))
    }

    @Test fun roleOnlyChangeIsNotDropped() {
        assertOnlyChange("role", "Team Lead", initial.copy(role = "Team Lead"))
    }

    @Test fun callsignOnlyChangeStillOmitsUnchangedFields() {
        assertOnlyChange("callsign", "TEST-UPDATED", initial.copy(callsign = "TEST-UPDATED"))
    }

    @Test fun standaloneOnlyChangeIsEmittedWithoutReplacingEffectiveCallsign() {
        assertOnlyChange("standaloneCallsign", "ODIN-WEARTAK",
            initial.copy(standaloneCallsign = "ODIN-WEARTAK"))
        assertOnlyChange("standaloneCallsign", "", initial.copy(standaloneCallsign = ""))
    }

    @Test fun combinedIdentityChangePreservesEscapedValuesAndEnvelopeVersion() {
        val changed = initial.copy(callsign = "TEST \"A\" & B", team = "Blue\\Green", role = "Role\nTwo")
        val payload = deltas(initial, changed).last()
        assertEquals(setOf("callsign", "team", "role"), payload.fieldNames())
        assertEquals(changed.callsign, payload.getString("callsign"))
        assertEquals(changed.team, payload.getString("team"))
        assertEquals(changed.role, payload.getString("role"))
    }

    @Test fun unchangedTimestampAndManagementOnlyChangesDoNotEmitDeltas() {
        assertEquals(1, deltas(initial, initial, initial.copy(timeMillis = 2),
            initial.copy(timeMillis = 3, phoneManagedSettings = true)).size)
    }

    @Test fun intervalAndServerChangesKeepExistingSparseShape() {
        val servers = listOf(DataRepository.TakServerItem(
            isEnabled = false, name = "Test", address = "tak.example", port = 8089,
            isP12Cert = false, username = "", password = "", p12Cert = "", p12CertPassword = ""
        ))
        val payloads = deltas(initial, initial.copy(reportIntSecs = 30),
            initial.copy(reportIntSecs = 30, takServerList = servers))
        assertEquals(setOf("reportIntSecs"), payloads[1].fieldNames())
        assertEquals(30, payloads[1].getInt("reportIntSecs"))
        assertEquals(setOf("takServerList"), payloads[2].fieldNames())
        assertEquals("Test", payloads[2].getJSONArray("takServerList").getJSONObject(0).getString("name"))
    }

    @Test fun repositoryTeamAndRoleFlowsReachSerializedDeltas() = runBlocking {
        val repo = mockk<DataRepository>()
        val team = MutableStateFlow(initial.team)
        val role = MutableStateFlow(initial.role)
        every { repo.getTakServerListReady } returns MutableStateFlow(emptyList())
        every { repo.getConstantReportingInterval } returns MutableStateFlow(60)
        every { repo.getCallsign } returns MutableStateFlow(initial.callsign)
        val standalone = MutableStateFlow(initial.standaloneCallsign)
        every { repo.getStandaloneCallsign } returns standalone
        every { repo.getTeam } returns team
        every { repo.getRole } returns role
        every { repo.phoneManagedSettingsEnabled } returns MutableStateFlow(false)
        val cap = CompanionAppRepository(RuntimeEnvironment.getApplication())
        CompanionAppRepository::class.java.getDeclaredField("repo\$delegate").apply {
            isAccessible = true
            set(cap, lazyOf(repo))
        }
        val received = Channel<JSONObject>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            cap.settingsChangedDeltasFlow().collect { delta ->
                received.send(JSONObject(cap.envelopeToJson(cap.buildSettingsChangedEnvelope(delta)))
                    .getJSONObject("payload"))
            }
        }
        try {
            withTimeout(5_000) {
                assertEquals("Cyan", received.receive().getString("team"))
                team.value = "Red"
                val teamDelta = received.receive()
                assertEquals(setOf("team"), teamDelta.fieldNames())
                assertEquals("Red", teamDelta.getString("team"))
                role.value = "Team Lead"
                val roleDelta = received.receive()
                assertEquals(setOf("role"), roleDelta.fieldNames())
                assertEquals("Team Lead", roleDelta.getString("role"))
                standalone.value = "ODIN-WEARTAK"
                val standaloneDelta = received.receive()
                assertEquals(setOf("standaloneCallsign"), standaloneDelta.fieldNames())
                assertEquals("ODIN-WEARTAK", standaloneDelta.getString("standaloneCallsign"))
            }
        } finally {
            collector.cancel()
            received.close()
        }
    }

    private fun assertOnlyChange(key: String, value: String, changed: CompanionAppRepository.WearTakSettingsSnapshot) {
        val payloads = deltas(initial, changed)
        assertEquals(2, payloads.size)
        val payload = payloads.last()
        assertEquals(setOf(key), payload.fieldNames())
        assertEquals(value, payload.getString(key))
    }

    private fun deltas(vararg snapshots: CompanionAppRepository.WearTakSettingsSnapshot): List<JSONObject> = runBlocking {
        val cap = spyk(CompanionAppRepository(RuntimeEnvironment.getApplication()))
        every { cap.wearTakSettingsFlow() } returns flowOf(*snapshots)
        cap.settingsChangedDeltasFlow().toList().map { delta ->
            val envelope = JSONObject(cap.envelopeToJson(cap.buildSettingsChangedEnvelope(delta)))
            assertEquals(1, envelope.getInt("vers"))
            assertEquals("settings_changed", envelope.getString("msgType"))
            assertEquals("wearos", envelope.getString("src"))
            envelope.getJSONObject("payload")
        }
    }

    private fun JSONObject.fieldNames(): Set<String> = keys().asSequence().toSet()
}
