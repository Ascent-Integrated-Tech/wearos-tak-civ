/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.data.secure.migration

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aitech.weartak_civ.data.secure.takserver.TakServerSecretsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val TAK_SERVER_LIST_KEY = stringPreferencesKey("tak_server_list")

class TakServerMigration(
    private val legacyDataStore: DataStore<Preferences>,
    private val secretsRepository: TakServerSecretsRepository
) {
    suspend fun runIfNeeded() {
        val encrypted = secretsRepository.takServerListJson.first()
        if (!encrypted.isNullOrBlank()) {
            return
        }

        val legacy = legacyDataStore.data.map { it[TAK_SERVER_LIST_KEY] }.first()
        if (!legacy.isNullOrBlank()) {
            secretsRepository.setTakServerListJson(legacy)
            legacyDataStore.edit { it.remove(TAK_SERVER_LIST_KEY) }
        }
    }
}
