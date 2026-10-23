package com.aitech.weartak_civ.data

import android.app.Application
import androidx.datastore.preferences.core.edit
import com.aitech.weartak_civ.presentation.MainActivity
import com.google.gson.Gson
import com.aitech.weartak_civ.data.InMemoryTakServerSecrets.Companion.server
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PhoneManagedSettingsTest {
    private lateinit var repo: DataRepository
    private val secrets = InMemoryTakServerSecrets()

    @Before fun setup() = runBlocking {
        MainActivity.device_uid = "test-watch"
        MainActivity.versionNumber = "test"
        repo = DataRepository(RuntimeEnvironment.getApplication())
        repo.takServerSecretsOverride = secrets.repository
        repo.legacyDataStore().edit { it.clear() }
        repo.storeEnableDynamicReportingStrategy(true)
        repo.storeConstantReportingInterval(60)
    }

    @Test fun oneTimePayloadDefaultsToUnmanagedAndRetainsCertificateFields() {
        val payload = Gson().fromJson(
            """{"takServerList":[{"name":"TAK","address":"tak.example","port":8089,
                "isEnabled":true,"isP12Cert":true,"p12Cert":"dGVzdC1jZXJ0",
                "p12CertPassword":"test-password"}],"callsign":"ODIN-WEARTAK"}""",
            CompanionAppRepository.SetSettingsPayload::class.java
        )
        assertFalse(payload.phoneManagedSettings)
        assertEquals("ODIN-WEARTAK", payload.callsign)
        assertEquals("dGVzdC1jZXJ0", payload.takServerList.single().p12Cert)
        assertEquals("test-password", payload.takServerList.single().p12CertPassword)
    }

    private suspend fun storeWatchIdentity() {
        repo.storeCallsign("ODIN-WEARTAK")
        repo.storeTeam("Dark Green")
        repo.storeRole("K9")
        repo.storeRoleCategory("CIV")
    }

    private suspend fun assertWatchValues() {
        assertTrue(repo.getEnableDynamicReportingStrategy.first())
        assertEquals(60, repo.getConstantReportingInterval.first())
        assertEquals("ODIN-WEARTAK", repo.getCallsign.first())
        assertEquals("ODIN-WEARTAK", repo.getStandaloneCallsign.first())
        assertEquals("Dark Green", repo.getTeam.first())
        assertEquals("K9", repo.getRole.first())
        assertEquals("CIV", repo.getRoleCategory.first())
    }

    @Test fun standaloneIdentitySurvivesManagedPushesRepositoryRecreationAndDisconnect() = runBlocking {
        storeWatchIdentity()
        val cap = CompanionAppRepository(RuntimeEnvironment.getApplication())
        CompanionAppRepository::class.java.getDeclaredField("repo\$delegate").apply {
            isAccessible = true
            set(cap, lazyOf(repo))
        }
        suspend fun assertIdentity(effective: String, standalone: String) {
            assertEquals(standalone, repo.getStandaloneCallsign.first())
            val recreated = DataRepository(RuntimeEnvironment.getApplication())
            assertEquals(standalone, recreated.getStandaloneCallsign.first())
            val info = org.json.JSONObject(cap.envelopeToJson(cap.buildWatchInfoEnvelope()))
                .getJSONObject("payload")
            assertEquals(effective, info.getString("cs"))
            assertEquals(standalone, info.getString("standaloneCallsign"))
            val settings = org.json.JSONObject(cap.envelopeToJson(cap.buildWatchSettingsEnvelope()))
                .getJSONObject("payload")
            assertEquals(effective, settings.getString("callsign"))
            assertEquals(standalone, settings.getString("standaloneCallsign"))
        }
        assertIdentity("ODIN-WEARTAK", "ODIN-WEARTAK")
        repo.enablePhoneManagedSettings("AA:BB")
        repeat(2) {
            repo.applyPhoneManagedSettings("PHONE-$it", "Cyan", "Team Lead", 15)
            assertIdentity("PHONE-$it", "ODIN-WEARTAK")
        }
        repo.restorePhoneManagedSnapshot()
        assertIdentity("ODIN-WEARTAK", "ODIN-WEARTAK")
        repo.storeCallsign("THOR")
        assertIdentity("THOR", "THOR")
        repo.applyPhoneManagedSettings("PHONE", "Cyan", "Team Lead", 15)
        assertIdentity("PHONE", "THOR")
        repo.clearPhoneManagedSettingsForPeer("AA:BB")
        assertIdentity("THOR", "THOR")
    }

    @Test fun emptyStandaloneCallsignNeverFallsBackToManagedIdentity() = runBlocking {
        repo.storeCallsign("")
        repo.enablePhoneManagedSettings("AA:BB")
        repo.applyPhoneManagedSettings("PHONE", "Cyan", "Team Lead", 15)
        assertEquals("", repo.getStandaloneCallsign.first())
        assertEquals("", repo.snapshotSystem().standaloneCallsign)
        repo.applyPhoneManagedSettings("PHONE-2", "Cyan", "Team Lead", 15)
        assertEquals("", repo.getStandaloneCallsign.first())
        repo.restorePhoneManagedSnapshot()
        assertEquals("", repo.getCallsign.first())
        assertEquals("", repo.getStandaloneCallsign.first())
    }

    @Test fun releaseRestoresEveryPhoneLockedValueSavedBeforeManagement() = runBlocking {
        storeWatchIdentity()
        repo.enablePhoneManagedSettings("AA:BB")
        repo.applyPhoneManagedSettings("PHONE", "Cyan", "Team Lead", 15)
        assertEquals("PHONE", repo.getCallsign.first())
        assertEquals("Cyan", repo.getTeam.first())
        assertEquals("Team Lead", repo.getRole.first())
        assertEquals(15, repo.getConstantReportingInterval.first())
        assertFalse(repo.getEnableDynamicReportingStrategy.first())

        val restored = requireNotNull(repo.clearPhoneManagedSettingsForPeer("aa:bb"))
        assertEquals(true to 60, restored.reporting)
        assertEquals("ODIN-WEARTAK", restored.callsign)
        assertEquals("Dark Green", restored.team)
        assertEquals("Dark Green", restored.teamValue)
        assertEquals("K9", restored.role)
        assertFalse(repo.phoneManagedSettingsEnabled.first())
        assertNull(repo.phoneManagedCompanionAddress.first())
        assertWatchValues()
        assertNull(repo.clearPhoneManagedSettingsForPeer("AA:BB"))
        assertWatchValues()
    }

    @Test fun repeatedPhonePushesKeepOriginalSnapshotAndWrongPeerCannotUnlock() = runBlocking {
        storeWatchIdentity()
        repo.enablePhoneManagedSettings("AA:BB")
        repo.applyPhoneManagedSettings("PHONE", "Cyan", "Team Lead", 15)
        repo.enablePhoneManagedSettings("AA:BB")
        repo.applyPhoneManagedSettings("PHONE-2", "Red", "Medic", 20)
        assertNull(repo.clearPhoneManagedSettingsForPeer("CC:DD"))
        assertTrue(repo.phoneManagedSettingsEnabled.first())
        assertEquals("PHONE-2", repo.getCallsign.first())
        assertEquals(true to 60, repo.clearPhoneManagedSettingsForPeer("AA:BB")?.reporting)
        assertFalse(repo.phoneManagedSettingsEnabled.first())
        assertWatchValues()
    }

    @Test fun sessionTimeoutUnlocksAndSnapshotRestoreKeepsManagementOptIn() = runBlocking {
        storeWatchIdentity()
        repo.enablePhoneManagedSettings("AA:BB")
        repo.applyPhoneManagedSettings("PHONE", "Cyan", "Team Lead", 15)
        assertFalse(repo.phoneManagedSettingsLocked.first())
        repo.setPhoneManagementSessionActive(true)
        assertTrue(repo.phoneManagedSettingsLocked.first())

        repo.setPhoneManagementSessionActive(false)
        assertFalse(repo.phoneManagedSettingsLocked.first())
        assertEquals("PHONE", repo.getCallsign.first())

        assertEquals("ODIN-WEARTAK", repo.restorePhoneManagedSnapshot()?.callsign)
        assertNull(repo.restorePhoneManagedSnapshot())
        assertTrue(repo.phoneManagedSettingsEnabled.first())
        assertEquals("AA:BB", repo.phoneManagedCompanionAddress.first())
        assertWatchValues()

        repo.applyPhoneManagedSettings("PHONE", "Cyan", "Team Lead", 15)
        repo.setPhoneManagementSessionActive(true)
        assertEquals("PHONE", repo.getCallsign.first())
        repo.clearPhoneManagedSettingsForPeer("AA:BB")
        assertFalse(repo.phoneManagedSettingsLocked.first())
        assertWatchValues()
    }

    @Test fun networkSettingsLockCombinesAdminAndLivePhoneManagement() = runBlocking {
        assertFalse(repo.networkSettingsLocked.first())
        repo.storeNetworkPreferencesLocked(true)
        assertTrue(repo.networkSettingsLocked.first())
        repo.enablePhoneManagedSettings("AA:BB")
        repo.setPhoneManagementSessionActive(true)
        assertTrue(repo.networkSettingsLocked.first())
        repo.storeNetworkPreferencesLocked(false)
        assertTrue(repo.networkSettingsLocked.first())
        repo.setPhoneManagementSessionActive(false)
        assertFalse(repo.networkSettingsLocked.first())
        repo.storeNetworkPreferencesLocked(true)
        repo.setPhoneManagementSessionActive(true)
        repo.setPhoneManagementSessionActive(false)
        assertTrue(repo.networkSettingsLocked.first())
        repo.storeNetworkPreferencesLocked(false)
        assertFalse(repo.networkSettingsLocked.first())
    }

    @Test fun unlockedWatchEditsBecomeTheRestoredValuesButLockedWritesDoNot() = runBlocking {
        storeWatchIdentity()
        repo.enablePhoneManagedSettings("AA:BB")
        repo.applyPhoneManagedSettings("PHONE", "Cyan", "Team Lead", 15)

        repo.setPhoneManagementSessionActive(true)
        repo.storeTeam("Red")
        repo.storeConstantReportingInterval(45)
        repo.setPhoneManagementSessionActive(false)
        repo.storeCallsign("THOR")
        repo.storeEnableDynamicReportingStrategy(false)

        repo.clearPhoneManagedSettingsForPeer("AA:BB")
        assertEquals("THOR", repo.getCallsign.first())
        assertFalse(repo.getEnableDynamicReportingStrategy.first())
        assertEquals("Dark Green", repo.getTeam.first())
        assertEquals(60, repo.getConstantReportingInterval.first())
        assertEquals("K9", repo.getRole.first())
    }

    @Test fun legacyReportingOnlySnapshotRestoresReportingAndKeepsIdentity() = runBlocking {
        repo.storeCallsign("PHONE")
        repo.storeEnableDynamicReportingStrategy(false)
        repo.storeConstantReportingInterval(15)
        repo.legacyDataStore().edit { prefs ->
            prefs[androidx.datastore.preferences.core.booleanPreferencesKey("phone_managed_settings_enabled")] = true
            prefs[androidx.datastore.preferences.core.stringPreferencesKey("phone_managed_companion_address")] = "AA:BB"
            prefs[androidx.datastore.preferences.core.booleanPreferencesKey("phone_managed_previous_dynamic_reporting")] = true
            prefs[androidx.datastore.preferences.core.intPreferencesKey("phone_managed_previous_constant_interval")] = 60
        }
        repo.applyPhoneManagedSettings("PHONE", "Cyan", "Team Lead", 20)
        assertEquals(true to 60, repo.clearPhoneManagedSettingsForPeer("AA:BB")?.reporting)
        assertEquals("PHONE", repo.getCallsign.first())
    }

    @Test fun phoneValuesRequireActiveManagement() = runBlocking {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repo.applyPhoneManagedSettings("PHONE", "Cyan", "Team Lead", 15) }
        }
        assertNull(repo.restorePhoneManagedSnapshot())
        Unit
    }

    @Test fun snapshotSurvivesProcessRestart() = runBlocking {
        storeWatchIdentity()
        repo.enablePhoneManagedSettings("AA:BB")
        repo.applyPhoneManagedSettings("PHONE", "Cyan", "Team Lead", 15)
        val restarted = DataRepository(RuntimeEnvironment.getApplication())
        assertEquals("ODIN-WEARTAK", restarted.clearPhoneManagedSettingsForPeer("AA:BB")?.callsign)
        repo = restarted
        assertWatchValues()
    }
    private suspend fun bondWithPhoneServers(vararg phoneServers: String) {
        repo.enablePhoneManagedSettings("AA:BB")
        repo.snapshotTakServersForPhoneManagement()
        repo.storeProvisionedTakServerList(phoneServers.map { server(it) })
        repo.applyPhoneManagedSettings("PHONE", "Cyan", "Team Lead", 15)
    }

    private fun assertOwnServerRestored() {
        val restored = secrets.liveServers().single()
        assertEquals("own", restored.name)
        assertEquals("T1dO", restored.p12Cert)
        assertEquals("own-pass", restored.p12CertPassword)
        assertNull(secrets.snapshot.value)
        assertEquals(listOf("own"), MainActivity.Constants.takServerList.map { it.name })
    }

    @Test fun bondReleaseRestoresWatchServersAndCertificatesAndRefreshesTak() = runBlocking {
        repo.storeTakServerList(listOf(server("own", "T1dO", "own-pass")))
        bondWithPhoneServers("phone")
        assertEquals(listOf("phone"), secrets.liveServers().map { it.name })
        assertEquals("T1dO", secrets.snapshotServers().single().p12Cert)

        repo.snapshotTakServersForPhoneManagement()
        repo.storeProvisionedTakServerList(listOf(server("phone-2")))
        assertNull(repo.clearPhoneManagedSettingsForPeer("CC:DD"))
        assertEquals(listOf("phone-2"), secrets.liveServers().map { it.name })

        assertNotNull(repo.clearPhoneManagedSettingsForPeer("AA:BB"))
        assertOwnServerRestored()
        assertEquals(3, withTimeout(1_000) { repo.takServerConfigurationRefreshes.take(3).toList() }.size)
    }

    @Test fun leaseExpiryRestoresServersKeepsOptInAndHandshakeSnapshotsAgain() = runBlocking {
        repo.storeTakServerList(listOf(server("own", "T1dO", "own-pass")))
        bondWithPhoneServers("phone")
        repo.setPhoneManagementSessionActive(true)
        repo.setPhoneManagementSessionActive(false)

        assertNotNull(repo.restorePhoneManagedSnapshot())
        assertOwnServerRestored()
        assertTrue(repo.phoneManagedSettingsEnabled.first())

        bondWithPhoneServers("phone")
        assertEquals(listOf("phone"), secrets.liveServers().map { it.name })
        repo.clearPhoneManagedSettingsForPeer("AA:BB")
        assertOwnServerRestored()
    }

    @Test fun lingeringServerSnapshotFromFailedHandshakeIsRestored() = runBlocking {
        repo.storeTakServerList(listOf(server("own", "T1dO", "own-pass")))
        repo.snapshotTakServersForPhoneManagement()
        repo.storeProvisionedTakServerList(listOf(server("phone")))
        assertNull(repo.restorePhoneManagedSnapshot())
        assertOwnServerRestored()
    }

    @Test fun unlockedServerEditsSurviveRestoreButLockedWritesDoNot() = runBlocking {
        repo.storeTakServerList(listOf(server("own", "T1dO", "own-pass")))
        bondWithPhoneServers("phone")
        repo.setPhoneManagementSessionActive(true)
        repo.storeTakServerList(listOf(server("locked-edit")))
        assertEquals(listOf("own"), secrets.snapshotServers().map { it.name })

        repo.setPhoneManagementSessionActive(false)
        repo.storeTakServerList(listOf(server("watch-edit")))
        repo.clearPhoneManagedSettingsForPeer("AA:BB")
        assertEquals(listOf("watch-edit"), secrets.liveServers().map { it.name })
    }

    @Test fun legacyManagementWithoutServerSnapshotKeepsCurrentServers() = runBlocking {
        repo.storeTakServerList(listOf(server("phone")))
        repo.legacyDataStore().edit { prefs ->
            prefs[androidx.datastore.preferences.core.booleanPreferencesKey("phone_managed_settings_enabled")] = true
            prefs[androidx.datastore.preferences.core.stringPreferencesKey("phone_managed_companion_address")] = "AA:BB"
            prefs[androidx.datastore.preferences.core.booleanPreferencesKey("phone_managed_previous_dynamic_reporting")] = true
            prefs[androidx.datastore.preferences.core.intPreferencesKey("phone_managed_previous_constant_interval")] = 60
        }
        repo.snapshotTakServersForPhoneManagement()
        assertNull(secrets.snapshot.value)
        assertNotNull(repo.clearPhoneManagedSettingsForPeer("AA:BB"))
        assertEquals(listOf("phone"), secrets.liveServers().map { it.name })
    }

    @Test fun serverSnapshotSurvivesProcessRestart() = runBlocking {
        repo.storeTakServerList(listOf(server("own", "T1dO", "own-pass")))
        bondWithPhoneServers("phone")
        val restarted = DataRepository(RuntimeEnvironment.getApplication())
        restarted.takServerSecretsOverride = secrets.repository
        restarted.snapshotTakServersForPhoneManagement()
        assertNotNull(restarted.clearPhoneManagedSettingsForPeer("AA:BB"))
        assertOwnServerRestored()
    }

    @Test fun processRestartRequiresFreshManagementHandshake() = runBlocking {
        repo.enablePhoneManagedSettings("AA:BB")
        repo.setPhoneManagementSessionActive(true)
        val restarted = DataRepository(RuntimeEnvironment.getApplication())
        assertTrue(restarted.phoneManagedSettingsEnabled.first())
        assertFalse(restarted.phoneManagedSettingsLocked.first())
    }
}
