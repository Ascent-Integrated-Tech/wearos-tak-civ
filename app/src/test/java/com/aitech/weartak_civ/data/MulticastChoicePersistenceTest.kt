package com.aitech.weartak_civ.data

import android.app.Application
import android.os.Bundle
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.aitech.weartak_civ.DEFAULT_ENABLE_MULTICAST_ON_WIFI
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.ManagedNetworkToggles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MulticastChoicePersistenceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val enabled = booleanPreferencesKey("enable_multicast")
    private val choice = booleanPreferencesKey("multicast_user_choice")
    private lateinit var repo: DataRepository

    @Before fun setup() = runBlocking {
        MainActivity.device_uid = "multicast-choice-test"
        repo = DataRepository(RuntimeEnvironment.getApplication())
        repo.legacyDataStore().edit { it.clear() }
        Unit
    }

    private suspend fun assertRestartPreserves(snapshot: Preferences) {
        val file = File(temporaryFolder.root, "data.preferences_pb")
        repeat(3) { restart ->
            val job = SupervisorJob()
            val store = PreferenceDataStoreFactory.create(
                migrations = listOf(TakMasterPreferenceMigration()),
                scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file }
            )
            try {
                if (restart == 0) store.updateData { snapshot }
                val actual = store.data.first()
                assertEquals(snapshot[enabled], actual[enabled])
                assertEquals(snapshot[choice], actual[choice])
                assertEquals(snapshot[enabled] ?: true,
                    actual[enabled] ?: DEFAULT_ENABLE_MULTICAST_ON_WIFI)
            } finally {
                job.cancelAndJoin()
            }
        }
    }

    @Test fun unsetDefaultsOnWithoutInventingLegacyProvenance() = runBlocking {
        assertTrue(DEFAULT_ENABLE_MULTICAST_ON_WIFI)
        assertTrue(repo.getEnableMulticast.first())
        assertNull(repo.legacyDataStore().data.first()[choice])
        assertRestartPreserves(repo.legacyDataStore().data.first())
    }

    @Test fun legacyOffSurvivesUpgradeAndRepeatedRestartsUnmarked() = runBlocking {
        repo.legacyDataStore().edit { it[enabled] = false }
        assertFalse(repo.getEnableMulticast.first())
        assertNull(repo.legacyDataStore().data.first()[choice])
        assertRestartPreserves(repo.legacyDataStore().data.first())
    }

    @Test fun legacyOnSurvivesUpgradeAndRepeatedRestartsUnmarked() = runBlocking {
        repo.legacyDataStore().edit { it[enabled] = true }
        assertTrue(repo.getEnableMulticast.first())
        assertNull(repo.legacyDataStore().data.first()[choice])
        assertRestartPreserves(repo.legacyDataStore().data.first())
    }

    @Test fun acceptedOffIsAtomicAndSurvivesRestart() = runBlocking {
        repo.storeEnableMulticast(false)
        val snapshot = repo.legacyDataStore().data.first()
        assertEquals(false, snapshot[enabled])
        assertEquals(false, snapshot[choice])
        assertRestartPreserves(snapshot)
    }

    @Test fun acceptedOnIsAtomicAndSurvivesRestart() = runBlocking {
        repo.storeEnableMulticast(true)
        val snapshot = repo.legacyDataStore().data.first()
        assertEquals(true, snapshot[enabled])
        assertEquals(true, snapshot[choice])
        assertRestartPreserves(snapshot)
    }

    @Test fun explicitPolicyWinsWithoutErasingOppositeLocalChoiceAndAbsentIsNoop() = runBlocking {
        for (local in listOf(false, true)) {
            repo.storeEnableMulticast(local)
            val policy = ManagedNetworkToggles.parse(Bundle().apply {
                putBoolean("multicast_service_enabled", !local)
            })
            repo.storeManagedNetworkToggles(multicast = policy.multicast)
            assertEquals(!local, repo.getEnableMulticast.first())
            assertEquals(local, repo.legacyDataStore().data.first()[choice])
            repo.storeManagedNetworkToggles(multicast = ManagedNetworkToggles.parse(Bundle()).multicast)
            assertEquals(!local, repo.getEnableMulticast.first())
            assertRestartPreserves(repo.legacyDataStore().data.first())
        }
    }

    @Test fun lockedAndPhoneManagedRejectionsDoNotMarkButTrustedPolicyStillApplies() = runBlocking {
        repo.storeNetworkPreferencesLocked(true)
        repo.storeEnableMulticast(false)
        assertTrue(repo.getEnableMulticast.first())
        assertNull(repo.legacyDataStore().data.first()[choice])
        repo.storeManagedNetworkToggles(multicast = false)
        assertFalse(repo.getEnableMulticast.first())
        assertNull(repo.legacyDataStore().data.first()[choice])
        repo.storeNetworkPreferencesLocked(false)
        repo.enablePhoneManagedSettings("AA:BB")
        repo.setPhoneManagementSessionActive(true)
        try {
            repo.storeEnableMulticast(true)
            assertFalse(repo.getEnableMulticast.first())
            assertNull(repo.legacyDataStore().data.first()[choice])
            repo.storeManagedNetworkToggles(multicast = true)
            assertTrue(repo.getEnableMulticast.first())
        } finally {
            repo.setPhoneManagementSessionActive(false)
            repo.clearPhoneManagedSettingsForPeer("AA:BB")
        }
    }
}
