/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.data.secure.takserver

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.aitech.weartak_civ.data.secure.crypto.AppKeyManager
import com.aitech.weartak_civ.data.secure.crypto.CryptoConfig
import com.aitech.weartak_civ.data.secure.crypto.EncryptedSerializer
import com.aitech.weartak_civ.data.secure.crypto.EncryptedSerializer.DecryptResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.takServerSecretsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = TakServerSecretsKeys.DATASTORE_NAME
)

class TakServerEncryptedDataStore(context: Context) {
    private val appContext = context.applicationContext
    private val ds = appContext.takServerSecretsDataStore

    private val aead = AppKeyManager(appContext).getAead(
        keysetName = TakServerSecretsKeys.KEYSET_NAME,
        keystoreAlias = TakServerSecretsKeys.KEYSTORE_ALIAS
    )

    fun encryptedString(key: Preferences.Key<String>): Flow<String?> =
        ds.data.map { prefs ->
            val enc = prefs[key] ?: return@map null
            when (val res = EncryptedSerializer.decryptFromBase64(aead, enc, TakServerSecretsKeys.AAD)) {
                is DecryptResult.Success -> res.plaintext
                is DecryptResult.Failure -> null
            }
        }

    suspend fun setEncryptedString(key: Preferences.Key<String>, value: String?) {
        ds.edit { prefs ->
            if (value == null) {
                prefs.remove(key)
            } else {
                val enc = EncryptedSerializer.encryptToBase64(aead, value, TakServerSecretsKeys.AAD)
                prefs[key] = enc
                prefs[TakServerSecretsKeys.CRYPTO_VERSION] = CryptoConfig.CURRENT_CRYPTO_VERSION
            }
        }
    }
}
