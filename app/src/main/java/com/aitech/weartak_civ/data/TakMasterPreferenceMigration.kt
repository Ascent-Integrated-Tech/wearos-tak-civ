package com.aitech.weartak_civ.data

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.aitech.weartak_civ.DEFAULT_ENABLE_TAK_ENDPOINT

internal val TAK_ENDPOINT_ENABLED = booleanPreferencesKey("enable_tak_endpoint")
// Presence records an accepted local choice; managed configuration never writes this key.
internal val TAK_ENDPOINT_USER_CHOICE = booleanPreferencesKey("tak_endpoint_user_choice")
internal val TAK_MASTER_DEFAULT_MIGRATED = booleanPreferencesKey("tak_master_default_migrated")

internal class TakMasterPreferenceMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) =
        currentData[TAK_MASTER_DEFAULT_MIGRATED] != true

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply {
            // Legacy false has no provenance: recover the default, regardless of server count.
            this[TAK_ENDPOINT_ENABLED] =
                currentData[TAK_ENDPOINT_USER_CHOICE] ?: DEFAULT_ENABLE_TAK_ENDPOINT
            this[TAK_MASTER_DEFAULT_MIGRATED] = true
        }

    override suspend fun cleanUp() = Unit
}
