package com.aitech.weartak_civ.data

import android.app.Application
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class NetworkTogglePersistenceTest {
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
