package com.aitech.weartak_civ.data

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import com.aitech.weartak_civ.presentation.MainActivity
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MoppSettingsTest {
    @Before fun initializeDevice(): Unit = runBlocking {
        MainActivity.device_uid = "test-watch"
        DataRepository(RuntimeEnvironment.getApplication()).legacyDataStore().edit {
            it.remove(stringPreferencesKey("mopp_status"))
            it.remove(booleanPreferencesKey("enable_mopp"))
            it.remove(booleanPreferencesKey("enable_data_sync"))
        }
    }

    @Test fun noneIsDefaultAndDistinctFromZeroAndInvalidPersistedValues() = runBlocking {
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        assertEquals(MoppStatus.NONE, repo.getMoppStatus.first())
        assertFalse(repo.getEnableMopp.first())
        assertEquals(listOf("none", "0", "1", "2", "3", "4"), MoppStatus.entries.map { it.key })
        repo.legacyDataStore().edit { it[stringPreferencesKey("mopp_status")] = "invalid" }
        assertEquals(MoppStatus.NONE, repo.getMoppStatus.first())
        assertNotEquals(MoppStatus.NONE, MoppStatus.LEVEL_0)
    }

    @Test fun everyLevelPersistsAndDisablingDoesNotClearStatus() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val repo = DataRepository(app)
        MoppStatus.entries.forEach {
            repo.storeEnableMopp(true)
            repo.storeMoppStatus(it)
            repo.storeEnableMopp(false)
            assertEquals(it, DataRepository(app).getMoppStatus.first())
        }
    }

    @Test fun dataSyncDefaultsOnAndPausePreservesSubscriptionsAndProgress() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val repo = DataRepository(app)
        assertTrue(repo.getEnableDataSync.first())
        repo.persistMissionSubscribed("server", "mission")
        repo.persistMissionSyncProgress("server", "mission", 1234L, setOf("change"))
        val saved = repo.persistedMissionSubscriptions.first()
        repo.storeEnableDataSync(false)
        assertFalse(DataRepository(app).getEnableDataSync.first())
        assertEquals(saved, repo.persistedMissionSubscriptions.first())
        repo.storeEnableDataSync(true)
        assertEquals(saved, DataRepository(app).persistedMissionSubscriptions.first())
    }
}
