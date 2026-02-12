/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.secure.sitx.SitxEncryptedDataStore
import com.aitech.weartak_civ.data.secure.sitx.SitxSecretsRepository
import com.aitech.weartak_civ.data.secure.sitx.TakSession
import com.aitech.weartak_civ.events.NwConnEvent
import com.aitech.weartak_civ.events.NwConnEvent.Companion.WIFI
import com.aitech.weartak_civ.manager.NetworkManager
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.device_uid
import com.aitech.weartak_civ.service.sitx.SitxServiceCommand
import com.aitech.weartak_civ.service.sitx.SitxServiceCommandBus
import com.aitech.weartak_civ.service.sitx.SitxServiceEvent
import com.aitech.weartak_civ.service.sitx.SitxServiceEventBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class SitxTakService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val sitxRepo by lazy {
        try {
            (applicationContext as WearTakApplication).sitxRepo
        } catch (_: Throwable) {
            SitxSecretsRepository(SitxEncryptedDataStore(applicationContext))
        }
    }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val http = OkHttpClient()

    // flowTag -> websocket
    private val sockets: MutableMap<String, WebSocket> = ConcurrentHashMap()

    private var networkManager : NetworkManager? = null
    private var isNetworkConnected = false
    var isWifiConnected = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SitxTakService onCreate()")
        isRunning.set(true)
        startForegroundServiceCompat(
            SITX_TAK_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
        networkManager = NetworkManager.getInstance()
        isNetworkConnected = networkManager?.isNetworkConnected() ?: false
        isWifiConnected = networkManager?.isWifiConnected() ?: false
        startReAuthorizationCollector()
        startSitxCommandBusCollector()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d(TAG, "SitxTakService onStartCommand()")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "SitxTakService onDestroy()")
        isRunning.set(false)
        closeWebsockets()
        stopForeground(STOP_FOREGROUND_DETACH)
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        serviceScope.cancel()
    }

    private fun startSitxCommandBusCollector() {
        serviceScope.launch {
            SitxServiceCommandBus.commands.collectLatest { cmd ->
                Log.i(TAG, "SitxServiceCommandBus command: $cmd")
                when (cmd) {
                    is SitxServiceCommand.BeginAuth -> {
                        beginDeviceAuthorization(
                            baseUrlInput = cmd.baseUrl,
                            clientId = cmd.clientId,
                            callsign = cmd.callsign,
                            deviceName = cmd.deviceName,
                            deviceId = cmd.deviceId
                        )
                    }

                    SitxServiceCommand.PollDeviceToken -> {
                        pollDeviceTokenUntilAuthorized()
                    }

                    SitxServiceCommand.ConnectSockets -> {
                        ensureSessionsAndConnectSockets()
                    }

                    is SitxServiceCommand.SendCot -> {
                        sendCotToAll(cmd.xml)
                    }

                    SitxServiceCommand.Stop -> {
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun closeWebsockets() {
        SitxServiceEventBus.tryEmit(SitxServiceEvent.Disconnected)
        // Close all websockets
        sockets.values.forEach { ws -> runCatching { ws.close(1000, "Closing websockets") } }
        sockets.clear()
    }

    private fun startReAuthorizationCollector() {
        serviceScope.launch {
            sitxRepo.baseUrl
                .combine(sitxRepo.clientId) { baseUrl, clientId -> baseUrl.trim() to (clientId?.trim().orEmpty()) }
                .debounce(300) // helps when baseUrl + clientId are written back-to-back
                .distinctUntilChanged()
                .collectLatest { (baseUrl, clientId) ->
                    Log.i(TAG, "startReAuthorizationCollector: baseUrl=$baseUrl, clientId=$clientId")
                    if (baseUrl.isNotBlank() && clientId.isNotBlank()) {
                        // If these values change, restart device auth flow (or refresh path if token valid)
                        // Currently we don't store baseUrl and clientId locally but that is ok
                        closeWebsockets()
                        Log.d(TAG, "Launching device authorization")
                        beginDeviceAuthorization(
                            baseUrlInput = baseUrl,
                            clientId = clientId,
                            callsign = callsign,
                            deviceName = device_uid,
                            deviceId = device_uid
                        )
                    } else {
                        closeWebsockets()
                    }
                }
        }
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public fun onNwConnEvent(event: NwConnEvent) {
        val currIsConn = event.isConn
        val currTransport = event.transport
        val currIsWifi = (currTransport == WIFI)
        Log.i(TAG, "onNwConnEvent received NwConnEvent (isConn, isWifi)=($currIsConn, $currIsWifi) w/ previous ($isNetworkConnected, $isWifiConnected)")
        if (currIsConn) { // Network is connected
            if (currIsConn != isNetworkConnected) { // Previously network was NOT connected. So now reconnect to Sitx
                Log.d(TAG, "Previously network was NOT connected but now is connected. So reconnecting to Sitx server")
                isNetworkConnected = currIsConn
                isWifiConnected = currIsWifi
                closeWebsockets()
                // TODO Senni: Check if we need to do other things here
                serviceScope.launch {
                    ensureSessionsAndConnectSockets()
                }
            } else { // If underlying transport has changed, then we do need to reconnect
                if (currIsWifi != isWifiConnected) {
                    Log.d(TAG, "Connected to a different transport: prevIsWifi($isWifiConnected), currIsWifi($currIsWifi)")
                    isWifiConnected = currIsWifi
                    closeWebsockets()
                    serviceScope.launch {
                        ensureSessionsAndConnectSockets()
                    }
                }
            }
        } else { // Network is disconnected
            if (currIsConn != isNetworkConnected) { // Previously network was connected. Set required flags. We will reconnect on network available event.
                Log.d(TAG, "Previously network was connected but now is disconnected. So setting required flags")
                isNetworkConnected = currIsConn
                isWifiConnected = false
                closeWebsockets()
            }
        }
    }

    // -------------------------
    // Flow implementation
    // -------------------------

    private suspend fun beginDeviceAuthorization(
        baseUrlInput: String,
        clientId: String,
        callsign: String,
        deviceName: String,
        deviceId: String
    ) {
        val baseUrl = normalizeBaseUrl(baseUrlInput)
        if (baseUrl.isBlank() || clientId.isBlank()) {
            SitxServiceEventBus.tryEmit(SitxServiceEvent.Error("Missing baseUrl or clientId"))
            Log.w(TAG, "Missing baseUrl($baseUrl) or clientId($clientId): empty")
            return
        }

        // If refresh token still valid -> skip Step 1/2/3A and go to 3B->4->5->connect
        val refreshExp = sitxRepo.refreshTokenExpiresAtEpochSec.first()
        val refreshTok = sitxRepo.refreshToken.first()

        if (!refreshTok.isNullOrBlank() && isEpochValid(refreshExp)) {
            SitxServiceEventBus.tryEmit(SitxServiceEvent.Info("Refresh token valid; skipping device grant"))
            Log.i(TAG, "Refresh token valid ($refreshExp (>${nowEpochSec()}: $refreshTok); skipping device grant... Going to connect")
            ensureSessionsAndConnectSockets()
            return
        }

        // STEP 1: POST JSON device authorization code
        val scope = "role:org_user callsign:$callsign device_name:$deviceName device_id:$deviceId"
        val reqBody = json.encodeToString(DeviceAuthCodeRequest.serializer(), DeviceAuthCodeRequest(scope, clientId))

        val url = baseUrl.trimEnd('/') + "/api/v1/device/authorization/code"
        val request = Request.Builder()
            .url(url)
            .post(reqBody.toRequestBody(JSON_MEDIA))
            .addHeader("Accept", JSON_APP)
            .addHeader("Content-Type", JSON_APP)
            .build()

        when (val result = safeHttpCall(request)) {
            is HttpResult.Success -> {
                val bodyStr = result.body
                if (!result.isSuccessful) {
                    SitxServiceEventBus.tryEmit(SitxServiceEvent.Error("Device auth failed w/ code ${result.code}: $url"))
                    Log.e(TAG, "$url failed (${result.code}): $bodyStr ")
                    return
                }
                Log.i(TAG, "$url success: $bodyStr")

                val codeResp = runCatching { json.decodeFromString(DeviceAuthCodeResponse.serializer(), bodyStr) }
                    .getOrElse {
                        SitxServiceEventBus.tryEmit(SitxServiceEvent.Error("Device auth response parse error ($url): ${it.message}"))
                        Log.e(TAG, "$url return body($bodyStr) parse error: ${it.message}")
                        return
                    }

                val now = nowEpochSec()
                val expiresAt = now + codeResp.expiresIn

                // STEP 2: store and tell UI to show user_code + verification_uri_complete
                sitxRepo.setDeviceGrantCache(
                    deviceCode = codeResp.deviceCode,
                    userCode = codeResp.userCode,
                    verificationUriComplete = codeResp.verificationUriComplete,
                    deviceCodeExpiresAtEpochSec = expiresAt,
                    pollIntervalSec = codeResp.interval
                )

                SitxServiceEventBus.tryEmit(SitxServiceEvent.NeedsUserAction(userCode=codeResp.userCode, verificationUriComplete=codeResp.verificationUriComplete))
                Log.i(TAG, "User action (show code=${codeResp.userCode} and URL=${codeResp.verificationUriComplete})")
            }

            is HttpResult.Failure -> {
                val msg = result.exception.message ?: "I/O error"
                SitxServiceEventBus.tryEmit(SitxServiceEvent.Error("Network error: $msg"))
                Log.e(TAG, "Device auth request network error: $msg", result.exception)
            }
        }
    }

    private suspend fun pollDeviceTokenUntilAuthorized() {
        val baseUrl = normalizeBaseUrl(sitxRepo.baseUrl.first())
        val clientId = sitxRepo.clientId.first().orEmpty()
        val deviceCode = sitxRepo.deviceCode.first().orEmpty()

        val expiresAt = sitxRepo.deviceCodeExpiresAtEpochSec.first()
        val baseIntervalSec = sitxRepo.devicePollIntervalSec.first().coerceAtLeast(1L)

        if (baseUrl.isBlank() || clientId.isBlank() || deviceCode.isBlank()) {
            SitxServiceEventBus.tryEmit(SitxServiceEvent.Error("Missing baseUrl/clientId/deviceCode: Reauthorize device"))
            Log.e(TAG, "Missing baseUrl/clientId/deviceCode (baseUrlBlank=${baseUrl.isBlank()}, clientIdBlank=${clientId.isBlank()}, deviceCodeBlank=${deviceCode.isBlank()})")
            return
        }

        var intervalSec = baseIntervalSec
        // Poll until:
        // - authorized (success) -> store tokens -> ensure sessions -> return
        // - device_code expires -> clear cache -> return
        // - non-recoverable error -> return
        while (true) {
            val now = nowEpochSec()
            if (expiresAt != 0L && now >= expiresAt) {
                // TODO Senni: Maybe this is a different state
                SitxServiceEventBus.tryEmit(SitxServiceEvent.Error("Device code expired; restart device authorization"))
                Log.e(TAG, "Device code expired; expiresAt=$expiresAt now=$now. Restart device authorization")
                sitxRepo.clearDeviceGrantCache()
                return
            }

            // STEP 3A: device token request (form)
            val url = baseUrl.trimEnd('/') + "/api/v1/device/authorization/token"

            val form = FormBody.Builder()
                .add("client_id", clientId)
                .add("device_code", deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build()

            val request = Request.Builder()
                .url(url)
                .post(form)
                .addHeader("Accept", JSON_APP)
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .build()

            when (val result = safeHttpCall(request)) {
                is HttpResult.Success -> {
                    val bodyStr = result.body
                    Log.i(TAG, "Device token request code=${result.code} isSuccessful=${result.isSuccessful} body=$bodyStr")

                    if (result.isSuccessful) {
                        val tokenResp = runCatching {
                            json.decodeFromString(DeviceTokenSuccessResponse.serializer(), bodyStr)
                        }.getOrElse {
                            SitxServiceEventBus.tryEmit(SitxServiceEvent.Error("Token parse error: ${it.message}"))
                            Log.e(TAG, "Token parse error: ${it.message}", it)
                            return
                        }

                        // Convert expires_in -> epoch seconds
                        val refreshExpiresAtEpoch = expiresInToEpochSec(tokenResp.expiresIn)

                        sitxRepo.setRefreshToken(tokenResp.refreshToken)
                        sitxRepo.setRefreshTokenExpiresAtEpochSec(refreshExpiresAtEpoch)

                        // Device flow complete
                        sitxRepo.clearDeviceGrantCache()

                        SitxServiceEventBus.tryEmit(SitxServiceEvent.Authorized("Device authorized; building sessions"))
                        Log.i(TAG, "Device authorized; building sessions")
                        ensureSessionsAndConnectSockets()
                        return
                    } else {
                        // If authorization_pending -> stay on UI, wait, and retry
                        val err = runCatching {
                            json.decodeFromString(OAuthErrorResponse.serializer(), bodyStr)
                        }.getOrNull()

                        when (err?.error) {
                            "authorization_pending" -> {
                                // Reset interval back toward server-provided base if you want (optional).
                                intervalSec = baseIntervalSec

                                SitxServiceEventBus.tryEmit(
                                    SitxServiceEvent.Pending(
                                        userCode = sitxRepo.userCode.first().orEmpty(),
                                        verificationUriComplete = sitxRepo.verificationUriComplete.first().orEmpty(),
                                        message = "Authorization is still pending"
                                    )
                                )
                                delay(intervalSec * 1_000L)
                                continue
                            }

                            "slow_down" -> {
                                // Typical behavior: add 5 seconds to interval (common spec behavior)
                                intervalSec = (intervalSec + 5).coerceAtMost(60)

                                SitxServiceEventBus.tryEmit(
                                    SitxServiceEvent.Pending(
                                        userCode = sitxRepo.userCode.first().orEmpty(),
                                        verificationUriComplete = sitxRepo.verificationUriComplete.first().orEmpty(),
                                        message = "Authorization is still pending"
                                    )
                                )

                                delay(intervalSec * 1_000L)
                                continue
                            }

                            else -> {
                                val desc = err?.errorDescription ?: err?.error ?: "unknown error"
                                SitxServiceEventBus.tryEmit(SitxServiceEvent.Error("Token req failed (${result.code}): $desc"))
                                Log.e(TAG, "Token req failed code=${result.code} err=$err body=$bodyStr")
                                return
                            }
                        }
                    }
                }

                is HttpResult.Failure -> {
                    // Network issue: typically keep polling until device_code expires.
                    val msg = result.exception.message ?: "I/O error"
                    SitxServiceEventBus.tryEmit(SitxServiceEvent.Info("Network error: $msg. Retrying..."))
                    Log.e(TAG, "Device token request network error: $msg", result.exception)

                    // Wait for the server-provided interval before retry
                    delay(intervalSec * 1_000L)
                    continue
                }
            }
        }
    }

    private suspend fun ensureSessionsAndConnectSockets() {
        val baseUrl = normalizeBaseUrl(sitxRepo.baseUrl.first())
        val refreshToken = sitxRepo.refreshToken.first()

        if (baseUrl.isBlank()) {
            SitxServiceEventBus.tryEmit(SitxServiceEvent.Error("Missing baseUrl"))
            Log.e(TAG, "Missing baseUrl($baseUrl)")
            return
        }

        // If refresh token empty or expired, we must restart device authorization
        val refreshExp = sitxRepo.refreshTokenExpiresAtEpochSec.first()
        if (refreshToken.isNullOrBlank() || !isEpochValid(refreshExp)) {
            SitxServiceEventBus.tryEmit(SitxServiceEvent.Error("Refresh token empty or expired: restart device authorization"))
            Log.w(TAG, "Refresh token empty(=${refreshToken.isNullOrBlank()}) or expired($refreshExp): restart device authorization")
            return
        }

        // STEP 3B: refresh token
        val refreshed = refreshToken(baseUrl, refreshToken)
        if (refreshed != null) {
            sitxRepo.setRefreshToken(refreshed.refreshToken)
            val expEpoch = refreshed.refreshTokenExpiresAt?.let { parseExpiresAtIsoToEpochSec(it) }
            if (expEpoch != null) sitxRepo.setRefreshTokenExpiresAtEpochSec(expEpoch)
        } else {
            SitxServiceEventBus.tryEmit(SitxServiceEvent.Error("Refresh token request failed"))
            Log.e(TAG, "Refresh token request failed")
            return
        }


        val effectiveRefreshToken = sitxRepo.refreshToken.first().orEmpty()

        // STEP 4: Get TAK servers
        val servers = takServers(baseUrl, effectiveRefreshToken)
        if (servers.isEmpty()) {
            SitxServiceEventBus.tryEmit(SitxServiceEvent.Error("No TAK servers returned"))
            Log.e(TAG, "No TAK servers returned")
            return
        }

        // STEP 5: access token for each server + store sessions
        val sessions = servers.mapNotNull { server ->
            val flowTag = server.flowTag
            val access = accessToken(baseUrl, effectiveRefreshToken, flowTag) ?: return@mapNotNull null
            TakSession(
                flowTag = flowTag,
                endPoint = access.endPoint,
                accessToken = access.accessToken,
                refreshToken = access.refreshToken,
                refreshTokenExpiresAtEpochSec = parseExpiresAtIsoToEpochSec(access.refreshTokenExpiresAt)
            )
        }

        if (sessions.isEmpty()) {
            SitxServiceEventBus.tryEmit(SitxServiceEvent.Error("Access Token Request failed for all servers"))
            Log.e(TAG, "Access Token Request failed for all servers")
            return
        }

        sitxRepo.setTakSessions(sessions)

        // Connect sockets for all sessions
        connectAllSessions(sessions)
        SitxServiceEventBus.tryEmit(SitxServiceEvent.SocketsConnected(sessions.size, "Connected ${sessions.size} WebSocket(s)"))
        Log.i(TAG, "Connected ${sessions.size} WebSocket(s)")
    }

    // -------------------------
    // REST helpers
    // -------------------------

    private fun refreshToken(baseUrl: String, refreshToken: String): RefreshTokenResponse? {
        val url = baseUrl.trimEnd('/') + "/api/v1/refresh/token"
        val request = Request.Builder()
            .url(url)
            .post(ByteArray(0).toRequestBody((EMPTY_MEDIA)))
            .addHeader("Authorization", "Bearer $refreshToken")
            .build()

        when (val result = safeHttpCall(request)) {
            is HttpResult.Success -> {
                val bodyStr = result.body
                if (!result.isSuccessful) {
                    Log.e(TAG, "Refresh token failed (${result.code}): $bodyStr")
                    return null
                }
                return runCatching { json.decodeFromString(RefreshTokenResponse.serializer(), bodyStr) }.getOrNull()
            }
            is HttpResult.Failure -> {
                val msg = result.exception.message ?: "I/O error"
                SitxServiceEventBus.tryEmit(SitxServiceEvent.Error("Network error: $msg"))
                Log.e(TAG, "Refresh token network error: $msg", result.exception)
                return null
            }
        }
    }

    private fun takServers(baseUrl: String, refreshToken: String): List<TakServerItem> {
        val url = baseUrl.trimEnd('/') + "/api/v1/tak_servers"
        Log.d(TAG, "takServers: $url")
        Log.d(TAG, "refreshToken: ${refreshToken.take(12)}...(${refreshToken.length})")

        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Accept", "*/*")
            .addHeader("Authorization", "Bearer $refreshToken")
            .build()

        when (val result = safeHttpCall(request)) {
            is HttpResult.Success -> {
                val bodyStr = result.body
                Log.i(TAG, "takServers (${result.code}): $bodyStr")
                if (!result.isSuccessful) {
                    Log.e(TAG, "GET takServers failed (${result.code}) w/ headers=${result.headers}: $bodyStr")
                    return emptyList()
                }
                return runCatching { json.decodeFromString(ListTakServerSerializer, bodyStr) }
                    .getOrElse {
                        Log.e(TAG, "GET takServers response parse error: ${it.message}")
                        emptyList()
                    }
            }

            is HttpResult.Failure -> {
                val msg = result.exception.message ?: "I/O error"
                SitxServiceEventBus.tryEmit(SitxServiceEvent.Error("Network error: $msg"))
                Log.e(TAG, "Tak servers request network error: $msg", result.exception)
                return emptyList()
            }
        }
    }

    private fun accessToken(baseUrl: String, refreshToken: String, flowTag: String): AccessTokenResponse? {
        val url = baseUrl.trimEnd('/') + "/api/v1/access/token"

        val form = FormBody.Builder()
            .add("grant_type", "access")
            .add("resource_type", "TAKSERVER")
            .add("resource_key", flowTag)
            .build()

        val request = Request.Builder()
            .url(url)
            .post(form)
            .addHeader("Accept", JSON_APP)
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .addHeader("Authorization", "Bearer $refreshToken")
            .build()

        when (val result = safeHttpCall(request)) {
            is HttpResult.Success -> {
                val bodyStr = result.body
                if (!result.isSuccessful) {
                    Log.e(TAG, "Get access token failed for $flowTag (${result.code}): $bodyStr")
                    return null
                }
                val accessTokenResponse = runCatching { json.decodeFromString(AccessTokenResponse.serializer(), bodyStr) }
                    .getOrElse {
                        Log.e(TAG, "Get access token response parse error for $flowTag: ${it.message}")
                        null
                    }
                if (null != accessTokenResponse) {
                    Log.i(TAG, "Get access token success for $flowTag: $accessTokenResponse")
                    runBlocking {
                        sitxRepo.setRefreshToken(accessTokenResponse.refreshToken)
                        val expEpoch =
                            accessTokenResponse.refreshTokenExpiresAt?.let { parseExpiresAtIsoToEpochSec(it) }
                        if (expEpoch != null) sitxRepo.setRefreshTokenExpiresAtEpochSec(expEpoch)
                    }
                }
                return accessTokenResponse
            }

            is HttpResult.Failure -> {
                val msg = result.exception.message ?: "I/O error"
                SitxServiceEventBus.tryEmit(SitxServiceEvent.Error("Network error: $msg"))
                Log.e(TAG, "Access token network error: $msg", result.exception)
                return null
            }
        }
    }

    private fun sendCotToAll(xml: String) {
        //Log.d(TAG, "sendCotToAll: $xml")
        sockets.values.forEach { it.send(xml) }
    }

    // -------------------------
    // WebSocket helpers
    // -------------------------

    private fun connectAllSessions(sessions: List<TakSession>) {
        val desiredTags = sessions.map { it.flowTag }.toSet()

        // Close sockets no longer needed
        val toClose = sockets.keys - desiredTags
        toClose.forEach { tag ->
            sockets.remove(tag)?.close(1000, "Removed")
        }

        // Create sockets missing
        sessions.forEach { s ->
            if (sockets.containsKey(s.flowTag)) return@forEach

            val req = Request.Builder()
                .url(s.endPoint)
                .addHeader("Authorization", "Bearer ${s.accessToken}")
                .build()

            val ws = http.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "WS open flowTag=${s.flowTag}")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WS failure flowTag=${s.flowTag}: ${t.message}")
                    sockets.remove(s.flowTag)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "WS closed flowTag=${s.flowTag} code=$code reason=$reason")
                    sockets.remove(s.flowTag)
                }
            })

            sockets[s.flowTag] = ws
        }
    }

    // -------------------------
    // Time + URL helpers
    // -------------------------

    private sealed class HttpResult {
        data class Success(val code: Int, val isSuccessful: Boolean, val body: String, val headers: okhttp3.Headers) : HttpResult()
        data class Failure(val exception: java.io.IOException) : HttpResult()
    }

    private fun safeHttpCall(request: Request): HttpResult {
        return try {
            http.newCall(request).execute().use { resp: Response ->
                val bodyStr = resp.body?.string().orEmpty()
                HttpResult.Success(resp.code, resp.isSuccessful, bodyStr, resp.headers)
            }
        } catch (e: java.io.IOException) {
            HttpResult.Failure(e)
        } catch (e: CancellationException) {
            // Preserve coroutine cancellation semantics
            throw e
        }
    }
    private fun nowEpochSec(): Long = System.currentTimeMillis() / 1000L

    private fun expiresInToEpochSec(expiresInSeconds: Long): Long = nowEpochSec() + expiresInSeconds

    private fun parseExpiresAtIsoToEpochSec(expiresAtIso: String): Long =
        OffsetDateTime.parse(expiresAtIso).toInstant().epochSecond

    private fun isEpochValid(expiresAtEpochSec: Long, skewSec: Long = 30L): Boolean {
        if (expiresAtEpochSec <= 0L) return false
        return expiresAtEpochSec > (nowEpochSec() + skewSec)
    }

    private fun normalizeBaseUrl(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return ""
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
        return if (withScheme.endsWith("/")) withScheme.dropLast(1) else withScheme
    }

    // -------------------------
    // Serializable models
    // -------------------------

    @Serializable
    data class DeviceAuthCodeRequest(
        val scope: String,
        @SerialName("client_id") val clientId: String
    )

    @Serializable
    data class DeviceAuthCodeResponse(
        @SerialName("device_code") val deviceCode: String,
        @SerialName("user_code") val userCode: String,
        @SerialName("verification_uri") val verificationUri: String,
        @SerialName("verification_uri_complete") val verificationUriComplete: String,
        @SerialName("expires_in") val expiresIn: Long,
        @SerialName("interval") val interval: Long
    )

    @Serializable
    data class DeviceTokenSuccessResponse(
        @SerialName("token_type") val tokenType: String,
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("expires_in") val expiresIn: Long
    )

    @Serializable
    data class OAuthErrorResponse(
        val error: String,
        @SerialName("error_description") val errorDescription: String? = null
    )

    @Serializable
    data class RefreshTokenResponse(
        @SerialName("token_type") val tokenType: String? = null,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("refresh_token_expires_at") val refreshTokenExpiresAt: String? = null,
        @SerialName("sequestered_status") val sequesteredStatus: String? = null
    )

    @Serializable
    data class TakServerItem(
        val id: Long,
        @SerialName("flow_tag") val flowTag: String
    )

    private val ListTakServerSerializer =
        kotlinx.serialization.builtins.ListSerializer(TakServerItem.serializer())

    @Serializable
    data class AccessTokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("token_type") val tokenType: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("refresh_token_expires_at") val refreshTokenExpiresAt: String,
        @SerialName("end_point") val endPoint: String
    )

    companion object {
        private val TAG = "WearTAK-" + SitxTakService::class.java.simpleName
        private const val SITX_TAK_SERVICE_NOTIFICATION_ID = 179

        val isRunning: AtomicBoolean = AtomicBoolean(false)

        private val JSON_APP = "application/json"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_MEDIA = "application/octet-stream".toMediaType()
    }
}