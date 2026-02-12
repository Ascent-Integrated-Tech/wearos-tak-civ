/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
*/
/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 */
package com.aitech.weartak_civ.data.secure.sitx

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class TakSession(
    val flowTag: String,
    val endPoint: String,
    val accessToken: String,
    val refreshToken: String,
    val refreshTokenExpiresAtEpochSec: Long
)

class SitxSecretsRepository(private val store: SitxEncryptedDataStore) {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    // Base URL is allowed plaintext
    val baseUrl: Flow<String> = store.plaintextString(SitxSecretsKeys.BASE_URL, default = "ascent.parteamconnect.com")

    // Encrypted
    val clientId: Flow<String?> = store.encryptedString(SitxSecretsKeys.CLIENT_ID)

    val deviceCode: Flow<String?> = store.encryptedString(SitxSecretsKeys.DEVICE_CODE)
    val userCode: Flow<String?> = store.encryptedString(SitxSecretsKeys.USER_CODE)
    val verificationUriComplete: Flow<String?> = store.encryptedString(SitxSecretsKeys.VERIFICATION_URI_COMPLETE)

    val refreshToken: Flow<String?> = store.encryptedString(SitxSecretsKeys.REFRESH_TOKEN)
    val refreshTokenExpiresAtEpochSec: Flow<Long> =
        store.plaintextLong(SitxSecretsKeys.REFRESH_TOKEN_EXPIRES_AT_EPOCH_SEC, default = 0L)

    val deviceCodeExpiresAtEpochSec: Flow<Long> =
        store.plaintextLong(SitxSecretsKeys.DEVICE_CODE_EXPIRES_AT_EPOCH_SEC, default = 0L)

    val devicePollIntervalSec: Flow<Long> =
        store.plaintextLong(SitxSecretsKeys.DEVICE_POLL_INTERVAL_SEC, default = 5L)

    val takSessions: Flow<List<TakSession>> =
        store.encryptedString(SitxSecretsKeys.TAK_SESSIONS_JSON).map { enc ->
            if (enc.isNullOrBlank()) emptyList()
            else runCatching { json.decodeFromString(ListSerializer(TakSession.serializer()), enc) }
                .getOrElse { emptyList() }
        }

    // WS health map (plaintext JSON object)
    // flowTag -> epochSec
    val wsHealth: Flow<Map<String, Long>> =
        store.plaintextString(SitxSecretsKeys.WS_HEALTH_JSON, default = "{}").map { raw ->
            runCatching {
                val obj = json.decodeFromString(JsonObject.serializer(), raw)
                obj.mapValues { (_, v) -> v.jsonPrimitive.longOrNull ?: 0L }.filterValues { it > 0L }
            }.getOrElse { emptyMap() }
        }

    val deviceUid: Flow<String?> = store.encryptedString(SitxSecretsKeys.DEVICE_UID)

    // -------- setters --------

    suspend fun setBaseUrl(url: String) =
        store.setPlaintextString(SitxSecretsKeys.BASE_URL, url)

    suspend fun setClientId(clientId: String?) =
        store.setEncryptedString(SitxSecretsKeys.CLIENT_ID, clientId)

    suspend fun setDeviceGrantCache(
        deviceCode: String?,
        userCode: String?,
        verificationUriComplete: String?,
        deviceCodeExpiresAtEpochSec: Long,
        pollIntervalSec: Long
    ) {
        store.setEncryptedString(SitxSecretsKeys.DEVICE_CODE, deviceCode)
        store.setEncryptedString(SitxSecretsKeys.USER_CODE, userCode)
        store.setEncryptedString(SitxSecretsKeys.VERIFICATION_URI_COMPLETE, verificationUriComplete)
        store.setPlaintextLong(SitxSecretsKeys.DEVICE_CODE_EXPIRES_AT_EPOCH_SEC, deviceCodeExpiresAtEpochSec)
        store.setPlaintextLong(SitxSecretsKeys.DEVICE_POLL_INTERVAL_SEC, pollIntervalSec)
    }

    suspend fun clearDeviceGrantCache() {
        store.setEncryptedString(SitxSecretsKeys.DEVICE_CODE, null)
        store.setEncryptedString(SitxSecretsKeys.USER_CODE, null)
        store.setEncryptedString(SitxSecretsKeys.VERIFICATION_URI_COMPLETE, null)
        store.setPlaintextLong(SitxSecretsKeys.DEVICE_CODE_EXPIRES_AT_EPOCH_SEC, 0L)
        store.setPlaintextLong(SitxSecretsKeys.DEVICE_POLL_INTERVAL_SEC, 5L)
    }

    suspend fun setRefreshToken(token: String?) =
        store.setEncryptedString(SitxSecretsKeys.REFRESH_TOKEN, token)

    suspend fun setRefreshTokenExpiresAtEpochSec(epochSec: Long) =
        store.setPlaintextLong(SitxSecretsKeys.REFRESH_TOKEN_EXPIRES_AT_EPOCH_SEC, epochSec)

    suspend fun setTakSessions(list: List<TakSession>) {
        val encoded = json.encodeToString(ListSerializer(TakSession.serializer()), list)
        store.setEncryptedString(SitxSecretsKeys.TAK_SESSIONS_JSON, encoded)
    }

    suspend fun updateWsHealth(flowTag: String, lastConnectedEpochSec: Long) {
        val current = store.plaintextString(SitxSecretsKeys.WS_HEALTH_JSON, default = "{}")
        // DataStore doesn't give a sync get; this repo is used from service scope.
        // Keep this function "fire-and-forget" by writing a merged map string that the service computes.
        // Preferred: service calls setWsHealthMap(...) (below).
        // This method is kept for convenience but should be used carefully.
        // -> Not used in service; see setWsHealthMap().
    }

    suspend fun setWsHealthMap(map: Map<String, Long>) {
        val obj = JsonObject(map.mapValues { JsonPrimitive(it.value) })
        store.setPlaintextString(SitxSecretsKeys.WS_HEALTH_JSON, obj.toString())
    }

    suspend fun setDeviceUid(deviceUid: String?) =
        store.setEncryptedString(SitxSecretsKeys.DEVICE_UID, deviceUid)

    suspend fun clearSecrets(keepBaseUrl: Boolean = true) =
        store.clearAllSecrets(keepBaseUrl = keepBaseUrl)
}