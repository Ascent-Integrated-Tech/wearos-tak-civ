/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.data.secure.takserver

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object TakServerSecretsKeys {
    // DataStore file name
    const val DATASTORE_NAME: String = "takserver_secrets"

    // Tink keyset identity + Keystore alias for wrapping key
    const val KEYSET_NAME: String = "takserver_aead_keyset"
    const val KEYSTORE_ALIAS: String = "weartak.takserver.master.v1"

    // AAD binds ciphertext to this domain
    val AAD: ByteArray = "weartak:takserver:list:v1".toByteArray(Charsets.UTF_8)

    // encrypted TAK server list JSON
    val TAK_SERVER_LIST_JSON = stringPreferencesKey("tak_server_list_json")

    // crypto metadata
    val CRYPTO_VERSION = intPreferencesKey("takserver.crypto_version")
}
