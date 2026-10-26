package com.aitech.weartak_civ.data

import android.app.Application
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TakMasterPreferenceMigrationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val serverList = stringPreferencesKey("tak_server_list")
    private val enabledServer = """[{"isEnabled":true,"name":"Saved server","address":"tak.example.invalid"}]"""

    private fun upgrade(seed: Preferences, expected: Boolean) = runBlocking {
        val file = File(temporaryFolder.root, "data.preferences_pb")
        val oldJob = SupervisorJob()
        val oldStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + oldJob), produceFile = { file }
        )
        oldStore.updateData { seed }
        oldJob.cancelAndJoin()
        repeat(2) {
            val job = SupervisorJob()
            val migrated = PreferenceDataStoreFactory.create(
                migrations = listOf(TakMasterPreferenceMigration()),
                scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file }
            )
            try {
                val prefs = migrated.data.first()
                assertEquals(expected, prefs[TAK_ENDPOINT_ENABLED])
                assertEquals(seed[TAK_ENDPOINT_USER_CHOICE], prefs[TAK_ENDPOINT_USER_CHOICE])
                assertEquals(seed[serverList], prefs[serverList])
                assertTrue(prefs[TAK_MASTER_DEFAULT_MIGRATED] == true)
            } finally {
                job.cancelAndJoin()
            }
        }
    }

    @Test fun freshInstallDefaultsOnWithoutInventingUserChoice() =
        upgrade(emptyPreferences(), true)

    @Test fun enabledServerUpgradeDefaultsOnAndPreservesServerData() =
        upgrade(preferencesOf(serverList to enabledServer), true)

    @Test fun explicitOffUpgradeStaysOff() =
        upgrade(preferencesOf(TAK_ENDPOINT_ENABLED to false, TAK_ENDPOINT_USER_CHOICE to false), false)

    @Test fun noServerUpgradeDefaultsOn() =
        upgrade(preferencesOf(serverList to "[]"), true)

    @Test fun unmarkedLegacyFalseAutoWriteRecoversOnWithEnabledServer() =
        upgrade(preferencesOf(TAK_ENDPOINT_ENABLED to false, serverList to enabledServer), true)

    @Test fun unmarkedLegacyFalseWithoutServersAlsoRecoversOn() =
        upgrade(preferencesOf(TAK_ENDPOINT_ENABLED to false, serverList to "[]"), true)

    @Test fun explicitOnUpgradeStaysOn() =
        upgrade(preferencesOf(TAK_ENDPOINT_ENABLED to true, TAK_ENDPOINT_USER_CHOICE to true), true)

    @Test fun completedMigrationDoesNotReenableManagedOffOnNextProcessStart() =
        upgrade(preferencesOf(TAK_ENDPOINT_ENABLED to false, TAK_MASTER_DEFAULT_MIGRATED to true), false)

    @Test fun laterUpgradePreservesManagedOnWithoutErasingExplicitLocalOff() =
        upgrade(preferencesOf(TAK_ENDPOINT_ENABLED to true, TAK_ENDPOINT_USER_CHOICE to false,
            TAK_MASTER_DEFAULT_MIGRATED to true), true)

    @Test fun laterUpgradePreservesManagedOffWithoutErasingExplicitLocalOn() =
        upgrade(preferencesOf(TAK_ENDPOINT_ENABLED to false, TAK_ENDPOINT_USER_CHOICE to true,
            TAK_MASTER_DEFAULT_MIGRATED to true), false)
}
