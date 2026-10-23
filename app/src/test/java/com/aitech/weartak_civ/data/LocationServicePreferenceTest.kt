package com.aitech.weartak_civ.data

import android.app.Application
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LocationServicePreferenceTest {
    @Test
    fun legacyDisabledPreferenceDefaultsToEnabledUnlessManagedConfigurationDisablesIt() = runBlocking {
        MainActivity.device_uid = "test-watch"
        val repository = DataRepository(RuntimeEnvironment.getApplication())
        repository.storeEnableLocationService(false)

        assertTrue(repository.initializeLocationServiceSetting(managedSetting = null))
        assertTrue(repository.getEnableLocationService.first())

        assertFalse(repository.initializeLocationServiceSetting(managedSetting = false))
        assertFalse(repository.getEnableLocationService.first())
    }
}
