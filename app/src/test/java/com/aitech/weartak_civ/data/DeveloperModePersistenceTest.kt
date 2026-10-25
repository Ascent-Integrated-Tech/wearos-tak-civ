package com.aitech.weartak_civ.data

import android.app.Application
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
class DeveloperModePersistenceTest {
    @Test fun localUiUnlockSurvivesReloadAndExplicitDisableWithoutChangingNetworkLock() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        com.aitech.weartak_civ.presentation.MainActivity.device_uid = "diagnostics-test"
        val repository = DataRepository(context)
        val previousMode = repository.getEnableWearTakDevMode.first()
        val previousLock = repository.getNetworkPreferencesLocked.first()
        try {
            repository.storeNetworkPreferencesLocked(true)
            repository.storeEnableWearTakDevMode(true)
            assertTrue(DataRepository(context).getEnableWearTakDevMode.first())
            assertTrue(repository.networkSettingsLocked.first())
            repository.storeEnableWearTakDevMode(false)
            assertFalse(DataRepository(context).getEnableWearTakDevMode.first())
            assertTrue(repository.getNetworkPreferencesLocked.first())
            assertTrue(repository.networkSettingsLocked.first())
        } finally {
            repository.storeEnableWearTakDevMode(previousMode)
            repository.storeNetworkPreferencesLocked(previousLock)
        }
    }
}
