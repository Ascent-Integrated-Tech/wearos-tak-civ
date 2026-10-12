package com.aitech.weartak_civ.data.takserver

import com.aitech.weartak_civ.data.model.TakMission
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import com.aitech.weartak_civ.data.model.TakMissionChange
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log

class TakMissionApi(
    private val address: String,
    private val sslContext: SSLContext,
    private val trustManager: X509TrustManager,
    private val serverId: String,
    private val serverName: String,

    private val client: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustManager)
        .hostnameVerifier(TakMissionHostnameVerifier())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    private val objectMapper = ObjectMapper()



    private fun takMissionsEndpoint(
        address: String,
        path: String
    ): HttpUrl.Builder =
        HttpUrl.Builder()
            .scheme("https")
            .host(address)
            .port(8443)
            .addPathSegments(path)

    internal fun takMissionsURL(address: String): HttpUrl =
        takMissionsEndpoint(address, "Marti/api/missions")
            .build()


    internal fun takMissionURL(
        address: String,
        missionName: String
    ): HttpUrl =
        takMissionsEndpoint(address, "Marti/api/missions")
            .addPathSegment(missionName)
            .build()

    suspend fun getAllMissions(): List<TakMission> {
        val request = Request.Builder()
            .url(takMissionsURL(address))
            .get()
            .addHeader("Accept", "*/*")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    "Failed to get missions: HTTP ${response.code}"
                )
            }

            val body = response.body?.string()
                ?: throw IOException("Mission response body was empty")

            Log.d(
                "TakMissionApi",
                "getAllMissions response=$body"
            )
            val responseJson = objectMapper.readTree(body)
            val missions = responseJson.path("data")

            if (!missions.isArray) {
                throw IOException("Mission response data was not an array")
            }

            return missions.map { mission ->
                objectMapper.treeToValue(
                    mission,
                    TakMission::class.java
                )
            }
        }
    }
    internal fun takMissionSubscriptionURL(
        address: String,
        missionName: String,
        clientUid: String
    ): HttpUrl =
        takMissionsEndpoint(address, "Marti/api/missions")
            .addPathSegment(missionName)
            .addPathSegment("subscription")
            .addQueryParameter("uid", clientUid)
            .build()

    internal fun takMissionChangesURL(
        address: String,
        missionName: String
    ): HttpUrl =
        takMissionsEndpoint(address, "Marti/api/missions")
            .addPathSegment(missionName)
            .addPathSegment("changes")
            .build()

    internal fun takMissionCotURL(
        address: String,
        missionName: String
    ): HttpUrl =
        takMissionsEndpoint(address, "Marti/api/missions")
            .addPathSegment(missionName)
            .addPathSegment("cot")
            .build()

    internal fun takMissionContentsURL(
        address: String,
        missionName: String
    ): HttpUrl =
        takMissionsEndpoint(address, "Marti/api/missions")
            .addPathSegment(missionName)
            .addPathSegment("contents")
            .build()

    suspend fun getOneMission(
missionName: String
    ): TakMission? {
        val request = Request.Builder()
            .url(takMissionURL(address, missionName))
            .get()
            .addHeader("Accept", "*/*")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 404) {
                return null
            }

            if (!response.isSuccessful) {
                throw IOException(
                    "Failed to get mission $missionName: HTTP ${response.code}"
                )
            }

            val body = response.body?.string()
                ?: throw IOException("Mission response body was empty")

            val responseJson = objectMapper.readTree(body)
            val missions = responseJson.path("data")

            if (!missions.isArray || missions.isEmpty) {
                return null
            }

            return objectMapper.treeToValue(
                missions.first(),
                TakMission::class.java
            )


        }
    }

    suspend fun subscribeToMission(
        missionName: String,
        clientUid: String
    ) {
        val request = Request.Builder()
            .url(takMissionSubscriptionURL(address, missionName, clientUid))
            .put("".toRequestBody())
            .addHeader("Accept", "*/*")
            .build()

        execute(
            request = request,
            operation = "subscribe-mission"
        )
    }

    suspend fun unsubscribeFromMission(
        missionName: String,
        clientUid: String
    ) {
        val request = Request.Builder()
            .url(
                takMissionSubscriptionURL(
                    address,
                    missionName,
                    clientUid
                )
            )
            .delete()
            .addHeader("Accept", "*/*")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    "Failed to unsubscribe from mission $missionName: HTTP ${response.code}"
                )
            }
        }
    }


    suspend fun getMissionChanges(
        missionName: String
    ): List<TakMissionChange> {

        val request = Request.Builder()
            .url(takMissionChangesURL(address, missionName))
            .get()
            .addHeader("Accept", "*/*")
            .build()

        val body = execute(
            request = request,
            operation = "get-mission-changes"
        )

        val responseJson = objectMapper.readTree(body)
        val changes = responseJson.path("data")

        if (!changes.isArray) {
            throw TakMissionParseException(
                "Mission changes response data was not an array"
            )
        }

        return changes.map { change ->
            objectMapper.treeToValue(
                change,
                TakMissionChange::class.java
            )
        }
    }
    suspend fun getMissionCot(
        missionName: String
    ): String {

        val request = Request.Builder()
            .url(takMissionCotURL(address, missionName))
            .get()
            .addHeader("Accept", "*/*")
            .build()

        return execute(
            request = request,
            operation = "get-mission-cot"
        )
    }

    suspend fun addMissionContent(
        missionName: String,
        uids: List<String>
    ) {
        val payload = objectMapper.createObjectNode().apply {
            putArray("uids").apply {
                uids.forEach { add(it) }
            }
        }

        val request = Request.Builder()
            .url(takMissionContentsURL(address, missionName))
            .put(
                payload.toString()
                    .toRequestBody("application/json".toMediaType())
            )
            .addHeader("Accept", "*/*")
            .build()

        execute(
            request = request,
            operation = "add-mission-content"
        )
    }

    private fun execute(
        request: Request,
        operation: String
    ): String {
        try {
            return client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    throw TakMissionHttpException(
                        "TAK mission request failed: " +
                                "${response.code} ${request.method} ${request.url.encodedPath}"
                    )
                }

                body
            }
        } catch (error: Exception) {
            throw error
        }
    }

}
internal open class TakMissionApiException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

internal class TakMissionHttpException(
    message: String
) : TakMissionApiException(message)

internal class TakMissionParseException(
    message: String,
    cause: Throwable? = null
) : TakMissionApiException(message, cause)