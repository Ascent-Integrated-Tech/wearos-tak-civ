package com.aitech.weartak_civ.data

import android.app.Application
import android.os.Bundle
import androidx.datastore.preferences.core.edit
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.ManagedNetworkToggles
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class NetworkTogglePersistenceTest {
    @Before fun clearPreferences() = runBlocking {
        MainActivity.device_uid = "network-toggle-test"
        DataRepository(RuntimeEnvironment.getApplication()).legacyDataStore().edit { it.clear() }
        Unit
    }

    @Test fun acceptedLocalChoiceIsAtomicAndManagedPolicyDoesNotEraseIt() = runBlocking {
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        repo.storeEnableTakEndpoint(false)
        val local = repo.legacyDataStore().data.first()
        assertEquals(false, local[TAK_ENDPOINT_ENABLED])
        assertEquals(false, local[TAK_ENDPOINT_USER_CHOICE])
        repo.storeManagedNetworkToggles(tak = true)
        assertTrue(repo.getEnableTakEndpoint.first())
        assertEquals(false, repo.legacyDataStore().data.first()[TAK_ENDPOINT_USER_CHOICE])
        repo.storeManagedNetworkToggles()
        assertTrue(repo.getEnableTakEndpoint.first())
        repo.storeEnableTakEndpoint(true)
        val enabled = repo.legacyDataStore().data.first()
        assertEquals(true, enabled[TAK_ENDPOINT_ENABLED])
        assertEquals(true, enabled[TAK_ENDPOINT_USER_CHOICE])
    }

    @Test fun absentManagedFieldsPreserveEveryOtherMasterAndDoNotMarkUserChoice() = runBlocking {
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        repo.storeEnableMulticast(true)
        repo.storeEnableSitxService(true)
        repo.storeEnableDittoService(true)
        repo.storeEnableCompanionAppService(true)
        repo.storeManagedNetworkToggles(tak = false)
        assertFalse(repo.getEnableTakEndpoint.first())
        assertTrue(repo.getEnableMulticast.first())
        assertTrue(repo.getEnableSitxService.first())
        assertTrue(repo.getEnableDittoService.first())
        assertTrue(repo.getEnableCompanionAppService.first())
        assertNull(repo.legacyDataStore().data.first()[TAK_ENDPOINT_USER_CHOICE])
        repo.storeManagedNetworkToggles()
        assertFalse(DataRepository(RuntimeEnvironment.getApplication()).getEnableTakEndpoint.first())
    }

    @Test fun rejectedLocalToggleDoesNotInventExplicitChoice() = runBlocking {
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        repo.storeNetworkPreferencesLocked(true)
        repo.storeEnableTakEndpoint(false)
        assertTrue(repo.getEnableTakEndpoint.first())
        assertNull(repo.legacyDataStore().data.first()[TAK_ENDPOINT_USER_CHOICE])
        repo.storeNetworkPreferencesLocked(false)
        repo.enablePhoneManagedSettings("AA:BB")
        repo.setPhoneManagementSessionActive(true)
        try {
            repo.storeEnableTakEndpoint(false)
            assertTrue(repo.getEnableTakEndpoint.first())
            assertNull(repo.legacyDataStore().data.first()[TAK_ENDPOINT_USER_CHOICE])
        } finally {
            repo.setPhoneManagementSessionActive(false)
            repo.clearPhoneManagedSettingsForPeer("AA:BB")
        }
    }

    @Test fun emptyManagedBundleCannotReproduceUpgradeOffRegressionButSuppliedPolicyWins() = runBlocking {
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        suspend fun apply(bundle: Bundle) {
            val parsed = ManagedNetworkToggles.parse(bundle)
            repo.storeManagedNetworkToggles(parsed.tak, parsed.multicast, parsed.sitx,
                parsed.ditto, parsed.companion)
        }
        apply(Bundle())
        assertTrue(repo.getEnableTakEndpoint.first())
        assertNull(repo.legacyDataStore().data.first()[TAK_ENDPOINT_ENABLED])
        repo.storeEnableTakEndpoint(false)
        apply(Bundle().apply { putString("license_key", "unrelated") })
        assertFalse(repo.getEnableTakEndpoint.first())
        assertEquals(false, repo.legacyDataStore().data.first()[TAK_ENDPOINT_USER_CHOICE])
        repo.storeNetworkPreferencesLocked(true)
        apply(Bundle().apply { putStringArray("select_data_endpoints", arrayOf("TAK")) })
        assertTrue(repo.getEnableTakEndpoint.first())
        assertEquals(false, repo.legacyDataStore().data.first()[TAK_ENDPOINT_USER_CHOICE])
        apply(Bundle().apply { putStringArray("select_data_endpoints", emptyArray()) })
        assertFalse(repo.getEnableTakEndpoint.first())
        apply(Bundle())
        assertFalse(repo.getEnableTakEndpoint.first())
    }

    @Test fun masterDefaultsOnAndNetworkLockRejectsAlternateToggleWriters() = runBlocking {
        MainActivity.device_uid = "network-toggle-test"
        val app = RuntimeEnvironment.getApplication()
        val repo = DataRepository(app)
        assertTrue(repo.getEnableTakEndpoint.first())
        repo.storeEnableTakEndpoint(false)
        assertFalse(DataRepository(app).getEnableTakEndpoint.first())
        val before = listOf(repo.getEnableMulticast.first(), repo.getEnableSitxService.first(),
            repo.getEnableDittoService.first(), repo.getEnableCompanionAppService.first())
        repo.storeNetworkPreferencesLocked(true)
        try {
            repo.storeEnableTakEndpoint(true)
            repo.storeEnableMulticast(!before[0])
            repo.storeEnableSitxService(!before[1])
            repo.storeEnableDittoService(!before[2])
            repo.storeEnableCompanionAppService(!before[3])
            assertFalse(repo.getEnableTakEndpoint.first())
            assertEquals(before, listOf(repo.getEnableMulticast.first(), repo.getEnableSitxService.first(),
                repo.getEnableDittoService.first(), repo.getEnableCompanionAppService.first()))
        } finally {
            repo.storeNetworkPreferencesLocked(false)
            repo.storeEnableTakEndpoint(true)
        }
    }

    @Test fun activePhoneManagementBlocksLocalWritersButNotTrustedManagedConfiguration() = runBlocking {
        MainActivity.device_uid = "network-managed-toggle-test"
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        repo.storeEnableTakEndpoint(true)
        repo.enablePhoneManagedSettings("AA:BB")
        repo.setPhoneManagementSessionActive(true)
        try {
            repo.storeEnableTakEndpoint(false)
            assertTrue(repo.getEnableTakEndpoint.first())
            assertNull(repo.clearPhoneManagedSettingsFromNetworkSettings("AA:BB"))
            assertTrue(repo.phoneManagedSettingsEnabled.first())
            repo.storeNetworkPreferencesLocked(true)
            repo.storeManagedNetworkToggles(false, false, false, false, false)
            assertFalse(repo.getEnableTakEndpoint.first())
            assertFalse(repo.getEnableMulticast.first())
            assertFalse(repo.getEnableSitxService.first())
            assertFalse(repo.getEnableDittoService.first())
            assertFalse(repo.getEnableCompanionAppService.first())
        } finally {
            repo.setPhoneManagementSessionActive(false)
            repo.clearPhoneManagedSettingsForPeer("AA:BB")
            repo.storeNetworkPreferencesLocked(false)
            repo.storeEnableTakEndpoint(true)
        }
    }
}
