/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.data.secure.migration

import com.aitech.weartak_civ.data.secure.crypto.CryptoConfig
import com.aitech.weartak_civ.data.secure.sitx.SitxEncryptedDataStore
import com.aitech.weartak_civ.data.secure.sitx.SitxSecretsKeys
import kotlinx.coroutines.flow.first

class SitxMigration(private val store: SitxEncryptedDataStore) {

    suspend fun runIfNeeded() {
        val storedVersion = store.plaintextInt(SitxSecretsKeys.CRYPTO_VERSION, default = 0).first()
        if (storedVersion > CryptoConfig.CURRENT_CRYPTO_VERSION) {
            store.clearAllSecrets(keepBaseUrl = true)
            return
        }

        // Conservative probe: if crypto_version is set but decryptable secrets are all null, clear.
        val anyEncrypted =
            store.encryptedString(SitxSecretsKeys.CLIENT_ID).first()
                ?: store.encryptedString(SitxSecretsKeys.REFRESH_TOKEN).first()
                ?: store.encryptedString(SitxSecretsKeys.TAK_SESSIONS_JSON).first()

        if (storedVersion != 0 && anyEncrypted == null) {
            store.clearAllSecrets(keepBaseUrl = true)
        }
    }
}
