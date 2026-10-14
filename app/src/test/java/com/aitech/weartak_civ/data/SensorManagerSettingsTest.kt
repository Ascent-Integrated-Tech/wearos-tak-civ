package com.aitech.weartak_civ.data

import android.app.Application
import androidx.datastore.preferences.core.edit
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
class SensorManagerSettingsTest {
    @Test
    fun defaultsOffAndPersistsBothToggleStates() = runBlocking {
        MainActivity.device_uid = "test-watch"
        MainActivity.versionNumber = "test"
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        repo.legacyDataStore().edit { it.clear() }
        assertFalse(repo.getEnableSensorManager.first())
        repo.storeEnableSensorManager(true)
        assertTrue(repo.getEnableSensorManager.first())
        repo.storeEnableSensorManager(false)
        assertFalse(repo.getEnableSensorManager.first())
    }
}
