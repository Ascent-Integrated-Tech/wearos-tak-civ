/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.data.takserver

import com.aitech.weartak_civ.data.model.TakChannel
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal data class TakChannelGroupSnapshot(
    val payload: ArrayNode,
    val channels: List<TakChannel>,
)

internal fun takChannelGroupsUrl(address: String, sendLatestSa: Boolean): HttpUrl =
    takChannelEndpoint(address, "Marti/api/groups/all")
        .addQueryParameter("useCache", "true")
        .apply {
            if (sendLatestSa) addQueryParameter("sendLatestSA", "true")
        }
        .build()

internal fun takChannelActiveGroupsUrl(address: String, clientUid: String): HttpUrl =
    takChannelEndpoint(address, "Marti/api/groups/active")
        .addQueryParameter("clientUid", clientUid)
        .build()

private fun takChannelEndpoint(address: String, path: String): HttpUrl.Builder = HttpUrl.Builder()
    .scheme("https")
    .host(address)
    .port(8443)
    .addPathSegments(path)

internal object TakChannelJson {
    private val objectMapper = ObjectMapper()

    fun parseSupportResponse(body: String): Boolean {
        val data = parseRoot(body).path("data")
        if (!data.isBoolean) throw TakChannelParseException("Channel support response has no Boolean data field")
        return data.asBoolean()
    }

    fun parseGroupsResponse(body: String): TakChannelGroupSnapshot {
        val data = parseRoot(body).path("data")
        if (!data.isArray) throw TakChannelParseException("Channel response has no data array")
        val payload = (data as ArrayNode).deepCopy()
        val channels = payload
            .mapNotNull(::toChannelOrNull)
            .distinctBy(TakChannel::bitPosition)
        return TakChannelGroupSnapshot(payload, channels)
    }

    fun updateActive(
        payload: ArrayNode,
        channelBitPosition: Int,
        shouldBeActive: Boolean,
    ): TakChannelGroupSnapshot {
        val updated = payload.deepCopy()
        var found = false
        updated.forEach { group ->
            if (group.path("bitpos").asInt(Int.MIN_VALUE) == channelBitPosition) {
                (group as? ObjectNode)?.put("active", shouldBeActive)
                found = true
            }
        }
        if (!found) {
            throw TakChannelParseException("Channel bit position $channelBitPosition was not returned by TAK Server")
        }
        val channels = updated
            .mapNotNull(::toChannelOrNull)
            .distinctBy(TakChannel::bitPosition)
        return TakChannelGroupSnapshot(updated, channels)
    }

    private fun parseRoot(body: String): JsonNode = try {
        objectMapper.readTree(body)
            ?: throw TakChannelParseException("TAK Server returned an empty JSON document")
    } catch (error: TakChannelParseException) {
        throw error
    } catch (error: Exception) {
        throw TakChannelParseException("Unable to parse TAK channel response", error)
    }

    private fun toChannelOrNull(group: JsonNode): TakChannel? {
        val name = group.path("name").asText().trim()
        val bitPositionNode = group.path("bitpos")
        if (!bitPositionNode.isIntegralNumber) return null
        val bitPosition = bitPositionNode.asInt()
        if (name.isEmpty()) return null
        return TakChannel(
            name = name,
            direction = group.path("direction").asText(),
            active = group.path("active").asBoolean(false),
            bitPosition = bitPosition,
        )
    }
}

internal class TakChannelApi(
    private val address: String,
    sslContext: SSLContext,
    trustManager: X509TrustManager,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustManager)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    fun loadGroups(
        sendLatestSa: Boolean,
        checkSupport: Boolean,
    ): TakChannelGroupSnapshot {
        if (checkSupport && !isSupported()) throw TakChannelsUnsupportedException()
        return TakChannelJson.parseGroupsResponse(execute(groupsRequest(sendLatestSa)))
    }

    fun updateChannel(
        channelBitPosition: Int,
        shouldBeActive: Boolean,
        clientUid: String,
    ): TakChannelGroupSnapshot {
        val current = loadGroups(sendLatestSa = false, checkSupport = false)
        val updated = TakChannelJson.updateActive(
            payload = current.payload,
            channelBitPosition = channelBitPosition,
            shouldBeActive = shouldBeActive,
        )
        val request = Request.Builder()
            .url(takChannelActiveGroupsUrl(address, clientUid))
            .put(
                updated.payload.toString()
                    .toRequestBody("application/json".toMediaType())
            )
            .addHeader("Accept", "*/*")
            .build()
        execute(request)
        return updated
    }

    private fun isSupported(): Boolean {
        val request = Request.Builder()
            .url(takChannelEndpoint(address, "Marti/api/groups/groupCacheEnabled").build())
            .get()
            .addHeader("Accept", "*/*")
            .build()
        return TakChannelJson.parseSupportResponse(execute(request))
    }

    private fun groupsRequest(sendLatestSa: Boolean): Request {
        return Request.Builder()
            .url(takChannelGroupsUrl(address, sendLatestSa))
            .get()
            .addHeader("Accept", "*/*")
            .build()
    }

    private fun execute(request: Request): String = client.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw TakChannelHttpException(
                "TAK channel request failed: ${response.code} ${request.method} ${request.url.encodedPath}"
            )
        }
        body
    }

}

internal open class TakChannelApiException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

internal class TakChannelHttpException(message: String) : TakChannelApiException(message)

internal class TakChannelParseException(message: String, cause: Throwable? = null) :
    TakChannelApiException(message, cause)

internal class TakChannelsUnsupportedException :
    TakChannelApiException("TAK Server does not support channels")
