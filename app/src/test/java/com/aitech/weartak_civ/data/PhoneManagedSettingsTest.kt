package com.aitech.weartak_civ.data

import android.app.Application
import androidx.datastore.preferences.core.edit
import com.aitech.weartak_civ.presentation.MainActivity
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

    @Before fun setup() = runBlocking {
        MainActivity.device_uid = "test-watch"
        MainActivity.versionNumber = "test"
        repo = DataRepository(RuntimeEnvironment.getApplication())
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

    @Test fun optingOutUnlocksWithoutRevertingProvisionedIdentityOrReporting() = runBlocking {
        assertFalse(repo.phoneManagedSettingsEnabled.first())
        repo.enablePhoneManagedSettings("AA:BB")
        repo.storeEnableDynamicReportingStrategy(false)
        repo.storeConstantReportingInterval(15)
        repo.storeCallsign("ODIN-WEARTAK")
        repo.storeTeam("Dark Green")
        repo.storeRole("K9")
        assertEquals(false to 15, repo.clearPhoneManagedSettingsForPeer("aa:bb", false))
        assertFalse(repo.phoneManagedSettingsEnabled.first())
        assertNull(repo.phoneManagedCompanionAddress.first())
        assertEquals(15, repo.getConstantReportingInterval.first())
        assertFalse(repo.getEnableDynamicReportingStrategy.first())
        assertEquals("ODIN-WEARTAK", repo.getCallsign.first())
        assertEquals("Dark Green", repo.getTeam.first())
        assertEquals("K9", repo.getRole.first())
        assertNull(repo.clearPhoneManagedSettingsForPeer("AA:BB"))
        assertEquals(15, repo.getConstantReportingInterval.first())
    }

    @Test fun unpairingStillRestoresPreviousReportingAndWrongPeerCannotUnlock() = runBlocking {
        repo.enablePhoneManagedSettings("AA:BB")
        repo.storeEnableDynamicReportingStrategy(false)
        repo.storeConstantReportingInterval(15)
        repo.enablePhoneManagedSettings("AA:BB")
        assertNull(repo.clearPhoneManagedSettingsForPeer("CC:DD", false))
        assertTrue(repo.phoneManagedSettingsEnabled.first())
        assertEquals(true to 60, repo.clearPhoneManagedSettingsForPeer("AA:BB"))
        assertFalse(repo.phoneManagedSettingsEnabled.first())
        assertEquals(60, repo.getConstantReportingInterval.first())
        assertTrue(repo.getEnableDynamicReportingStrategy.first())
    }

    @Test fun sessionTimeoutUnlocksWithoutClearingManagementOrProvisionedValues() = runBlocking {
        repo.enablePhoneManagedSettings("AA:BB")
        repo.storeCallsign("PHONE")
        repo.storeConstantReportingInterval(15)
        repo.storeEnableDynamicReportingStrategy(false)
        assertFalse(repo.phoneManagedSettingsLocked.first())
        repo.setPhoneManagementSessionActive(true)
        assertTrue(repo.phoneManagedSettingsLocked.first())

        repo.setPhoneManagementSessionActive(false)
        assertFalse(repo.phoneManagedSettingsLocked.first())
        assertTrue(repo.phoneManagedSettingsEnabled.first())
        assertEquals("AA:BB", repo.phoneManagedCompanionAddress.first())
        assertEquals("PHONE", repo.getCallsign.first())
        assertEquals(15, repo.getConstantReportingInterval.first())
        assertFalse(repo.getEnableDynamicReportingStrategy.first())

        repo.setPhoneManagementSessionActive(true)
        assertTrue(repo.phoneManagedSettingsLocked.first())
        repo.clearPhoneManagedSettingsForPeer("AA:BB", false)
        assertFalse(repo.phoneManagedSettingsLocked.first())
    }

    @Test fun processRestartRequiresFreshManagementHandshake() = runBlocking {
        repo.enablePhoneManagedSettings("AA:BB")
        repo.setPhoneManagementSessionActive(true)
        val restarted = DataRepository(RuntimeEnvironment.getApplication())
        assertTrue(restarted.phoneManagedSettingsEnabled.first())
        assertFalse(restarted.phoneManagedSettingsLocked.first())
    }
}
