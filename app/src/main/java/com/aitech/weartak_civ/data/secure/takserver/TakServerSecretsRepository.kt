/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.data.secure.takserver

import kotlinx.coroutines.flow.Flow
import com.aitech.weartak_civ.data.secure.crypto.EncryptedSerializer.DecryptResult

class TakServerSecretsRepository(private val store: TakServerEncryptedDataStore) {

    val takServerListJson: Flow<String?> = store.encryptedString(TakServerSecretsKeys.TAK_SERVER_LIST_JSON)
    val takServerListRead: Flow<DecryptResult?> =
        store.encryptedStringResult(TakServerSecretsKeys.TAK_SERVER_LIST_JSON)

    suspend fun setTakServerListJson(json: String?) =
        store.setEncryptedString(TakServerSecretsKeys.TAK_SERVER_LIST_JSON, json)

    val phoneManagedSnapshotRead: Flow<DecryptResult?> =
        store.encryptedStringResult(TakServerSecretsKeys.PHONE_MANAGED_TAK_SERVER_SNAPSHOT_JSON)

    suspend fun setPhoneManagedSnapshotJson(json: String?) =
        store.setEncryptedString(TakServerSecretsKeys.PHONE_MANAGED_TAK_SERVER_SNAPSHOT_JSON, json)
}
