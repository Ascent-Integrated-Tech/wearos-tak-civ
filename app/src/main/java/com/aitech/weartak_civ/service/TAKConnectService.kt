/*
 * Copyright (C) 2024, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service


import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Network
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.aitech.weartak_civ.NetworkUtils
import com.aitech.weartak_civ.WifiMulticastReceiver
import com.aitech.weartak_civ.cot.CotMarkerEvent
import com.aitech.weartak_civ.cot.CotMarkerMapItem
import com.aitech.weartak_civ.cot.CotMessageParser
import com.aitech.weartak_civ.cot.CotStreamFramer
import com.aitech.weartak_civ.cot.TakServerCotEnvelope
import com.aitech.weartak_civ.cot.TakServerCotIngestor
import com.aitech.weartak_civ.cot.TakServerCotSink
import com.aitech.weartak_civ.cot.CotUserPositionEvent
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.DataRepository.TakServerItem
import com.aitech.weartak_civ.data.model.TakChannel
import com.aitech.weartak_civ.data.model.TakChannelStatus
import com.aitech.weartak_civ.data.model.TakServerChannels
import com.aitech.weartak_civ.data.takserver.TakChannelApi
import com.aitech.weartak_civ.data.takserver.TakChannelHostnameVerifier
import com.aitech.weartak_civ.data.takserver.TakDataPackageClient
import com.aitech.weartak_civ.data.takserver.TakDataPackageRequest
import com.aitech.weartak_civ.R
import android.widget.Toast
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import com.fasterxml.jackson.databind.ObjectMapper
import com.aitech.weartak_civ.data.takserver.TakChannelState
import com.aitech.weartak_civ.data.takserver.TakSanHostnameVerifier
import com.aitech.weartak_civ.data.takserver.TakServerEndpointIdentity
import com.aitech.weartak_civ.data.takserver.TakServerId
import com.aitech.weartak_civ.data.takserver.TakChannelsUnsupportedException
import com.aitech.weartak_civ.data.model.GeofenceCot
import com.aitech.weartak_civ.data.GeofenceRepository
import com.aitech.weartak_civ.events.MessageEvent
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_ADDED
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_ADDED_VIA_UI
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_REMOVED
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_TOGGLED
import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.events.NwConnEvent
import com.aitech.weartak_civ.events.NwConnEvent.Companion.WIFI
import com.aitech.weartak_civ.events.TakChatEvent
import com.aitech.weartak_civ.integration.plugin.PluginCotIngress
import com.aitech.weartak_civ.manager.NetworkManager
import com.aitech.weartak_civ.manager.MulticastWifiRequest
import kotlinx.coroutines.flow.StateFlow
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableEsriEndpoint
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableTabletCommandEndpoint
import com.aitech.weartak_civ.presentation.MainActivity.Constants.esriEndpoint
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.tabletCommandApikey
import com.aitech.weartak_civ.presentation.MainActivity.Constants.tabletCommandEndpoint
import com.aitech.weartak_civ.presentation.requestStatusIconsComplicationUpdate
import com.aitech.weartak_civ.service.CotDispatchService.Companion.esriGeojsonMsg
import com.aitech.weartak_civ.service.CotDispatchService.Companion.tabletCommandJsonMsg
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import com.aitech.weartak_civ.data.takserver.TakEnrollment
import com.aitech.weartak_civ.data.takserver.TakEnrollmentStage
import com.aitech.weartak_civ.data.takserver.TakSignedCertificates
import com.aitech.weartak_civ.data.takserver.TakConnectionFailure
import com.aitech.weartak_civ.data.takserver.TakFailureCategory
import com.aitech.weartak_civ.data.takserver.TakEnrollmentException
import kotlinx.coroutines.CancellationException
import com.aitech.weartak_civ.data.model.TakMissionChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.w3c.dom.Document
import org.xml.sax.InputSource
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.IOException
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlSerializer
import java.io.StringReader
import java.io.StringWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.net.SocketTimeoutException
import java.security.*
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import com.aitech.weartak_civ.data.takserver.TakMissionApi
import javax.net.ssl.X509TrustManager
import com.aitech.weartak_civ.data.model.TakMissionStatus
import com.aitech.weartak_civ.data.model.TakMissionState
import com.aitech.weartak_civ.data.model.TakServerMissions
import kotlinx.coroutines.currentCoroutineContext
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.aitech.weartak_civ.data.takserver.TakServerRegistration

class TAKConnectService: Service() {
    var networkManager: NetworkManager? = null
    private var multicastWifiRequest: MulticastWifiRequest? = null

    class ConnectionAttempt(val generation: Long) {
        val sessionId: String = java.util.UUID.randomUUID().toString()
        var job: Job? = null
        var socket: SSLSocket? = null
    }

    class ConnectionRuntime {
        val lock = Any()
        var generation: Long = 0L
        var currentAttempt: ConnectionAttempt? = null
        var connectingSocket: SSLSocket? = null
        var connectJob: Job? = null
        var readerJob: Job? = null
        var terminated: Boolean = false
    }

    data class NameValueEntry(
        var name: String,
        var value: String
    )

    data class TakServerDetail(
        val serverId: String,
        var isEnabled: Boolean,
        var name: String,
        var address: String,
        var port: Int = 8089,
        var isP12Cert: Boolean,
        var username: String,
        var password: String,
        var p12Cert: String?,
        var p12CertPassword: String?,
        var numConnAttempts : Int = 0,
        val connectionStatus : AtomicInteger = AtomicInteger(TAK_SERVER_DISCONNECTED),
        val connectionStatusLiveData: MutableLiveData<String> = MutableLiveData<String>(TAK_SERVER_DISCONNECTED_STRING),
        val keyStore : KeyStore = KeyStore.getInstance("PKCS12"),
        var privateKey: PrivateKey? = null,
        var secureSocket : SSLSocket? = null,
        var channelSslContext: SSLContext? = null,
        var channelHostnameVerifier: HostnameVerifier? = null,
        var channelTrustManager: X509TrustManager? = null,
        val channelMutex: Mutex = Mutex(),
        val runtime: ConnectionRuntime = ConnectionRuntime(),
        var duplicateEndpointSuppressed: Boolean = false,
    )

    override fun onCreate() {
        super.onCreate()
        synchronized(serviceLifecycleLock) {
            activeService?.let { previous ->
                previous.scope.cancel()
                previous.multicastWifiRequest?.close()
                if (EventBus.getDefault().isRegistered(previous)) {
                    EventBus.getDefault().unregister(previous)
                }
            }
            closeTakServerSockets()
            connectionScope.cancel()
            connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            activeService = this
            serviceStopping = false
        }
        Log.w(TAG, "Repo identity = ${System.identityHashCode(dataRepository)}")
        Log.d(TAG, "TAKConnectService onCreate()")
        isRunning.set(true)
        startForegroundServiceCompat(
            notificationId = TAK_CONNECT_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )

        setFromDatastore()
        startTakServerConfigurationCollector()
        startEnableGoefenceServiceCollector()
        startMulticastCollector()

        scope.launch {
            dataRepository.isCompanionConnected
                .collect { connected ->
                    Log.w(TAG, "isCompanionConnected=$connected") // temporary
                    isCompanionAppConnected = connected
                    if (connected) {
                        Log.w(TAG, "closing TAK sockets because companion is ready")
                        closeTakServerSockets()
                        resetTakServerConnectionDetails()
                        return@collect
                }

                if (!isNetworkConnected) return@collect
                if (isAnyTakConnected || isAnyTakConnecting) return@collect
                connectToTakServers()
            }
        }

        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
    }

    private fun startEnableGoefenceServiceCollector() {
        scope.launch {
            dataRepository.geofenceServiceEnabled.collectLatest { enabled ->
                Log.i(TAG, "geofenceServiceEnabled=$enabled")
                if (null != enabled && enabled != geofenceEnabled)
                geofenceEnabled = enabled
            }
        }
    }

    data class MulticastConfig(
        val net: Network?,
        val enable: Boolean?,
        val address: String?,
        val port: Int?
    )

    private fun startMulticastCollector() {
        val wifiRequest = MulticastWifiRequest(
            getSystemService(ConnectivityManager::class.java),
            applicationContext.getSystemService(WifiManager::class.java),
            Handler(Looper.getMainLooper())
        )
        multicastWifiRequest = wifiRequest
        scope.launch(Dispatchers.Main.immediate) {
            try {
                dataRepository.multicastEnabled.collect { enabled ->
                    try {
                        wifiRequest.setEnabled(enabled == true)
                    } catch (error: SecurityException) {
                        Log.e(TAG, "Multicast Wi-Fi request permission denied", error)
                    }
                }
            } finally {
                wifiRequest.close()
            }
        }
        scope.launch {
            collectMulticastConfigurations(combine(NetworkManager.getInstance().wifiNetwork,
                dataRepository.multicastEnabled,
                dataRepository.multicastAddress,
                dataRepository.multicastPort) { net, enable, address, port ->
                MulticastConfig(net, enable, address, port)
            })
        }
    }

    internal suspend fun collectMulticastConfigurations(
        configurations: kotlinx.coroutines.flow.Flow<MulticastConfig>,
        updatePublisher: (MulticastConfig, Boolean) -> Unit = ::updateMulticastPublisher,
        openReceiver: (MulticastConfig) -> WifiMulticastReceiver = { cfg ->
            WifiMulticastReceiver.open(
                applicationContext, requireNotNull(cfg.net), requireNotNull(cfg.address), requireNotNull(cfg.port)
            )
        }
    ) {
        configurations.distinctUntilChanged().collectLatest { cfg ->
            val shouldRun = cfg.enable == true && cfg.net != null &&
                !cfg.address.isNullOrBlank() && cfg.port != null
            Log.i(TAG, "Multicast collector called: $cfg with shouldRun=$shouldRun")
            updatePublisher(cfg, shouldRun)
            if (shouldRun) {
                try {
                    withContext(Dispatchers.IO) {
                        openReceiver(cfg).use { receiver ->
                            val session = java.util.UUID.randomUUID().toString()
                            activeMulticastSource = requireNotNull(cfg.net) to session
                            Log.i(TAG, "Multicast route receiver ready")
                            try {
                                receiver.receive { envelope ->
                                    cotIngestor.ingest(envelope.copy(sourceSessionId = session))
                                }
                            } finally {
                                if (activeMulticastSource?.second == session) activeMulticastSource = null
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: IOException) {
                    Log.e(TAG, "Multicast receive failed", error)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.multicast_receive_failed, Toast.LENGTH_LONG).show()
                    }
                } catch (error: SecurityException) {
                    Log.e(TAG, "Multicast receive permission denied", error)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.multicast_receive_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun updateMulticastPublisher(cfg: MulticastConfig, shouldRun: Boolean) {
        val started = if (shouldRun) {
            NetworkUtils.MulticastPublisher.start(
                requireNotNull(cfg.net), requireNotNull(cfg.address), requireNotNull(cfg.port)
            )
        } else {
            NetworkUtils.MulticastPublisher.stop()
            false
        }
        dataRepository.setMulticastPublisherRunning(started)
        if (started) dataRepository.setEndpointConnected(DataRepository.Endpoint.MULTICAST)
        else dataRepository.setEndpointDisconnected(DataRepository.Endpoint.MULTICAST)
    }

    fun setFromDatastore() {
        runBlocking {
            val takServers: List<TakServerItem> = dataRepository.tryAwaitTakServerList()
                ?: return@runBlocking
            takServerDetails = mutableListOf()
            takServerDetailsById.clear()
            for (takServer in takServers) {
                val detail = takServer.toRuntimeDetail()
                takServerDetails.add(detail)
                takServerDetailsById[detail.serverId] = detail
                Log.d(
                    TAG,
                    "setFromDatastore serverId=${detail.serverId} name=${detail.name} " +
                        "address=${detail.address}:${detail.port} enabled=${detail.isEnabled} " +
                        "isP12=${detail.isP12Cert} hasP12=${!detail.p12Cert.isNullOrBlank()}"
                )
            }
            _takServerRuntimeDetails.value = takServerDetails.toList()
            refreshDuplicateEndpointSuppression()
            enableEsriEndpoint = dataRepository.getEnableEsriEndpoint.first()
            esriEndpoint = dataRepository.getEsriEndpoint.first()

        }
    }

    private fun TakServerItem.toRuntimeDetail(): TakServerDetail = TakServerDetail(
        serverId = requireNotNull(TakServerId.normalizeOrNull(serverId)),
        isEnabled = isEnabled,
        name = name,
        address = address.trim(),
        port = port,
        isP12Cert = isP12Cert,
        username = username,
        password = password,
        p12Cert = p12Cert,
        p12CertPassword = p12CertPassword,
        numConnAttempts = 0
    )

    private fun TakServerDetail.hasSameRuntimeConfiguration(item: TakServerItem): Boolean =
        isEnabled == item.isEnabled &&
            address == item.address.trim() &&
            port == item.port &&
            isP12Cert == item.isP12Cert &&
            username == item.username &&
            password == item.password &&
            p12Cert.orEmpty() == item.p12Cert &&
            p12CertPassword.orEmpty() == item.p12CertPassword

    private fun TakServerDetail.hasChangedCredentialMaterial(item: TakServerItem): Boolean =
        address != item.address.trim() ||
            port != item.port ||
            isP12Cert != item.isP12Cert ||
            username != item.username ||
            password != item.password ||
            p12Cert.orEmpty() != item.p12Cert ||
            p12CertPassword.orEmpty() != item.p12CertPassword

    private fun startTakServerConfigurationCollector() {
        scope.launch {
            dataRepository.takServerConfigurationRefreshes.collect { refresh ->
                try {
                    reloadProvisionedTakServers()
                    refresh.result.complete(Result.success(Unit))
                } catch (error: CancellationException) {
                    refresh.result.complete(Result.failure(error))
                    throw error
                } catch (error: Throwable) {
                    Log.e(TAG, "Unable to refresh provisioned TAK configuration", error)
                    refresh.result.complete(Result.failure(error))
                }
            }
        }
    }

    suspend fun registerServer(serverId: String) {
        reloadProvisionedTakServers(registrationServerId = serverId)

        val detail = getTakServerDetail(serverId)
            ?: error("Saved TAK server is unavailable")

        synchronized(detail.runtime.lock) {
            serverRegistration.bind(detail.serverId, detail)
            detail.numConnAttempts = 0
            getClientCertificateAndConnect(detail)
        }
    }

    private suspend fun reloadProvisionedTakServers(registrationServerId: String? = null) {
        val storedServers = dataRepository.tryAwaitTakServerList()
            ?: error("Persisted TAK server list is unavailable")
        val previousById = takServerDetails.associateBy(TakServerDetail::serverId)
        val retainedIds = mutableSetOf<String>()
        var replacedCount = 0
        val refreshed = storedServers.map { stored ->
            val serverId = requireNotNull(TakServerId.normalizeOrNull(stored.serverId))
            retainedIds += serverId
            val previous = previousById[serverId]
            if (previous != null && previous.hasSameRuntimeConfiguration(stored)) {
                previous.name = stored.name
                dataRepository.takChannels.value
                    .firstOrNull { it.serverId == serverId }
                    ?.let { channels ->
                        dataRepository.updateTakServerChannels(channels.copy(serverName = stored.name))
                    }
                previous
            } else {
                val replacement = stored.toRuntimeDetail()
                if (previous != null) {
                    serverRegistration.replaceRuntime(serverId, previous, replacement)
                    invalidateConnection(previous, terminate = true)
                    if (previous.hasChangedCredentialMaterial(stored)) {
                        context.deleteFile(certificateCacheFileName(previous))
                        if (TakServerEndpointIdentity.key(previous.address, previous.port) !=
                            TakServerEndpointIdentity.key(stored)) {
                            context.deleteFile(
                                certificateCacheFileName(stored.address, stored.port)
                            )
                        }
                    }
                }
                replacedCount++
                replacement
            }
        }

        previousById
            .filterKeys { it !in retainedIds }
            .values
            .forEach { removed -> invalidateConnection(removed, terminate = true) }

        takServerDetails = refreshed.toMutableList()
        takServerDetailsById.clear()
        refreshed.forEach { detail -> takServerDetailsById[detail.serverId] = detail }
        _takServerRuntimeDetails.value = refreshed.toList()
        refreshDuplicateEndpointSuppression()
        setIsAnyNetworkConnected()
        setIsAnyNetworkConnecting()

        Log.i(
            TAG,
            "Provisioned TAK configuration refreshed servers=${refreshed.size} replaced=$replacedCount"
        )

        val companionConnected = isCompanionAppConnected || dataRepository.isCompanionConnected.value
        refreshed
            .filter { detail ->
                detail.serverId != registrationServerId &&
                    (serverRegistration.isPendingRuntime(detail) ||
                    (!companionConnected && isNetworkConnected && detail.isEnabled &&
                        !detail.duplicateEndpointSuppressed &&
                        detail.connectionStatus.get() == TAK_SERVER_DISCONNECTED))
            }
            .forEach(::getClientCertificateAndConnect)
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d(TAG, "TAKConnectService onStartCommand()")
        networkManager = NetworkManager.getInstance()
        isNetworkConnected = networkManager?.isNetworkConnected() ?: false
        isWifiConnected = networkManager?.isWifiConnected() ?: false
        // Connecting to tak server is handled by the endpoint gate logic. If companion app is not connected, connectToTakServers() will fire there.
        if (isNetworkConnected) {
            Log.d(TAG, "TAKConnectService onStartCommand(): net=$isNetworkConnected wifi=$isWifiConnected")
            connectToTakServers()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        throw UnsupportedOperationException("Not yet implemented")
    }

    override fun onDestroy() {
        Log.d(TAG, "TAKConnectService onDestroy()")
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        scope.cancel()
        multicastWifiRequest?.close()
        synchronized(serviceLifecycleLock) {
            if (activeService === this) {
                serviceStopping = true
                isRunning.set(false)
                closeTakServerSockets()
                NetworkUtils.MulticastPublisher.stop()
                connectionScope.cancel()
                activeService = null
            }
        }
        connectJob?.cancel()
        networkManager = null
        stopForeground(STOP_FOREGROUND_DETACH)
        super.onDestroy()
    }

    private fun resetTakServerConnectionDetails() {
        isAnyTakConnected = false; isAnyTakConnectedLiveData.postValue(false)
        isAnyTakConnecting = false; isAnyTakConnectingLiveData.postValue(false)
        requestStatusIconsComplicationUpdate(context)
        for (takServerDetail in takServerDetails) {
            takServerDetail.numConnAttempts = 0
            invalidateConnection(takServerDetail)
        }
        dataRepository.setEndpointDisconnected(DataRepository.Endpoint.TAK_SERVER)
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public fun onNwConnEvent(event: NwConnEvent) {
        val currIsConn = event.isConn
        val currTransport = event.transport
        val currIsWifi = (currTransport == WIFI)
        Log.d(TAG, "onNwConnEvent received NwConnEvent (isConn, isWifi)=($currIsConn, $currIsWifi) w/ previous ($isNetworkConnected, $isWifiConnected)")
        if (currIsConn) { // Network is connected
            if (currIsConn != isNetworkConnected) { // Previously network was NOT connected. So now reconnect to TAK
                Log.d(TAG, "Previously network was NOT connected but now is connected. So reconnecting to TAK server")
                isNetworkConnected = currIsConn
                isWifiConnected = currIsWifi
                resetTakServerConnectionDetails()
                connectToTakServers()
            } else { // If underlying transport has changed, then we do need to reconnect
                if (currIsWifi != isWifiConnected) {
                    Log.d(TAG, "Connected to a different transport: prevIsWifi($isWifiConnected), currIsWifi($currIsWifi)")
                    isWifiConnected = currIsWifi
                    resetTakServerConnectionDetails()
                    connectToTakServers()
                }
            }
        } else { // Network is disconnected
            if (currIsConn != isNetworkConnected) { // Previously network was connected. Set required flags. We will reconnect on network available event.
                Log.d(TAG, "Previously network was connected but now is disconnected. So setting required flags")
                isNetworkConnected = currIsConn
                isWifiConnected = false
                resetTakServerConnectionDetails()
            }
        }
        /*
         * Notify all collectors (multicast handler) that isWifiConnected has changed
         */
        scope.launch {
            dataRepository.isWifiConnected.emit(isWifiConnected)
        }
    }

    // TODO:
    // Item 1: TEST newServerAddedViaUi
    // Item 2: Why last ServerRegistrationDialog is shown??
    // Item 3: Why 3 times when editServer is called
    // Item 4: Why we keep trying for junk connections when sending out data
    // Item 5: All TODOs
    // Item 6: Why GPS changes between 10 and 20s

    private fun onServerAdded(message: String, index: Int) {
        Log.d(TAG, "Received $message message with index($index)")
        runBlocking {
            val takServers: List<TakServerItem> = dataRepository.tryAwaitTakServerList()
                ?: return@runBlocking
            if (takServers.size - 1 != index) {
                // We could do nothing here but if there is a bug we are trying to take care of it
                Log.w(TAG, "SERVER_ADDED index($index) != takServers.size(${takServers.size})-1")
            }
            val takServer = takServers.last()
            val takServerDetail = TakServerDetail(
                serverId = requireNotNull(TakServerId.normalizeOrNull(takServer.serverId)),
                isEnabled = takServer.isEnabled,
                name = takServer.name,
                address = takServer.address.trim(),
                port = takServer.port,
                isP12Cert = takServer.isP12Cert,
                username = takServer.username,
                password = takServer.password,
                p12Cert = takServer.p12Cert,
                p12CertPassword = takServer.p12CertPassword
            )
            if (takServerDetails.size == index) {
                Log.d(TAG, "Adding server at index $index")
                takServerDetails.add(takServerDetail)
            } else {
                Log.d(TAG, "Updating server at index $index")
                val replaced = takServerDetails[index]
                invalidateConnection(replaced, terminate = true)
                takServerDetailsById.remove(replaced.serverId, replaced)
                takServerDetails.set(index, takServerDetail)
            }
            takServerDetailsById[takServerDetail.serverId] = takServerDetail
            refreshDuplicateEndpointSuppression()
            // Delete server name file (if it exists)
            Companion.deleteFile(certificateCacheFileName(takServerDetail))
            if (isNetworkConnected) {
                if (SERVER_ADDED_VIA_UI == message) {
                    Log.d(TAG, "Previous newServerAddedViaUi=$newServerAddedViaUi; newServerAddedViaUiConnectionStatusLiveData=${newServerAddedViaUiConnectionStatusLiveData.value}")
                    newServerAddedViaUi = true
                    newServerAddedViaUiConnectionStatusLiveData.postValue(TAK_SERVER_CONNECTING_STRING)
                }
                setIsAnyNetworkConnected()
                setIsAnyNetworkConnecting()
                getClientCertificateAndConnect(takServerDetail)
            } else {
                Log.w(TAG, "Network is NOT connected. Hence NOT trying to connect to TAK server")
                isAnyTakConnected = false; isAnyTakConnectedLiveData.postValue(false)
                isAnyTakConnecting = false; isAnyTakConnectingLiveData.postValue(false)
                dataRepository.setEndpointDisconnected(DataRepository.Endpoint.TAK_SERVER)

                requestStatusIconsComplicationUpdate(context)

                if (SERVER_ADDED_VIA_UI == message) {
                    newServerAddedViaUi = false
                    newServerAddedViaUiConnectionStatusLiveData.postValue(TAK_SERVER_CONNECTION_FAILED_STRING)
                }
            }
        }
    }

    private fun onServerChanged(index: Int) {
        val oldTakServerDetail = takServerDetails.get(index)
        Log.d(TAG, "Received SERVER_DETAILS_CHANGED message for index($index): ${oldTakServerDetail.address}")
        val oldAddress = oldTakServerDetail.address
        var serverConnDetailsChanged = false
        runBlocking {
            val takServers: List<TakServerItem> = dataRepository.tryAwaitTakServerList()
                ?: return@runBlocking
            val takServer = takServers.get(index)
            if (takServer.address != oldAddress || takServer.port != oldTakServerDetail.port ||
                takServer.isEnabled != oldTakServerDetail.isEnabled || takServer.isP12Cert != oldTakServerDetail.isP12Cert ||
                takServer.username != oldTakServerDetail.username || takServer.password != oldTakServerDetail.password ||
                takServer.p12Cert != oldTakServerDetail.p12Cert || takServer.p12CertPassword != oldTakServerDetail.p12CertPassword) {
                serverConnDetailsChanged = true
            }
            val takServerDetail = takServerDetails[index]
            takServerDetail.isEnabled = takServer.isEnabled; takServerDetail.name = takServer.name
            takServerDetail.address = takServer.address.trim(); takServerDetail.port = takServer.port
            takServerDetail.isP12Cert = takServer.isP12Cert;
            takServerDetail.username = takServer.username; takServerDetail.password = takServer.password
            takServerDetail.p12Cert = takServer.p12Cert; takServerDetail.p12CertPassword = takServer.p12CertPassword
            takServerDetail.numConnAttempts = 0
            dataRepository.takChannels.value
                .firstOrNull { it.serverId == takServerDetail.serverId }
                ?.let { current ->
                    dataRepository.updateTakServerChannels(current.copy(serverName = takServerDetail.name))
                }
            refreshDuplicateEndpointSuppression()
        }
        if (serverConnDetailsChanged) {
            Log.d(TAG, "serverConnDetailsChanged $serverConnDetailsChanged")
            // Delete old server name file
            Companion.deleteFile(certificateCacheFileName(oldTakServerDetail))
            val newTakServerDetail = takServerDetails.get(index)
            // Delete new server name file (if it exists)
            val oldEndpointKey = TakServerEndpointIdentity.key(oldTakServerDetail.address, oldTakServerDetail.port)
            val newEndpointKey = TakServerEndpointIdentity.key(newTakServerDetail.address, newTakServerDetail.port)
            if (oldEndpointKey != newEndpointKey) {
                Companion.deleteFile(certificateCacheFileName(newTakServerDetail))
            }
            invalidateConnection(newTakServerDetail)
            if (isNetworkConnected) {
                setIsAnyNetworkConnected()
                setIsAnyNetworkConnecting()
                if (newTakServerDetail.isEnabled && !newTakServerDetail.duplicateEndpointSuppressed) {
                    getClientCertificateAndConnect(newTakServerDetail)
                }
            } else {
                Log.w(TAG, "Network is NOT connected. Hence NOT trying to connect to TAK server")
                isAnyTakConnected = false; isAnyTakConnectedLiveData.postValue(false)
                isAnyTakConnecting = false; isAnyTakConnectingLiveData.postValue(false)
                dataRepository.setEndpointDisconnected(DataRepository.Endpoint.TAK_SERVER)

                requestStatusIconsComplicationUpdate(context)

            }
        }
    }

    private fun onServerToggled(index: Int) {
        val takServerDetail = takServerDetails.get(index)
        Log.d(TAG, "Received SERVER_TOGGLED message for index($index): ${takServerDetail.address}: Going to set isEnabled to ${!takServerDetail.isEnabled}")
        takServerDetail.numConnAttempts = 0
        takServerDetail.connectionStatus.set(TAK_SERVER_DISCONNECTED)
        takServerDetail.connectionStatusLiveData.postValue(TAK_SERVER_DISCONNECTED_STRING)
        if (takServerDetail.isEnabled) {
            takServerDetail.isEnabled = false
            invalidateConnection(takServerDetail)
            dataRepository.removeTakServerChannels(takServerDetail.serverId)
            setIsAnyNetworkConnected()
            setIsAnyNetworkConnecting()
        } else {
            takServerDetail.isEnabled = true
            if (isNetworkConnected) {
                setIsAnyNetworkConnected()
                setIsAnyNetworkConnecting()
                getClientCertificateAndConnect(takServerDetail)
            } else {
                Log.w(TAG, "Network is NOT connected. Hence NOT trying to connect to TAK server")
                isAnyTakConnected = false; isAnyTakConnectedLiveData.postValue(false)
                isAnyTakConnecting = false; isAnyTakConnectingLiveData.postValue(false)
                dataRepository.setEndpointDisconnected(DataRepository.Endpoint.TAK_SERVER)

                requestStatusIconsComplicationUpdate(context)

            }
        }
    }

    private fun onServerRemoved(index: Int) {
        val takServerDetail = takServerDetails.get(index)
        Log.d(TAG, "Received SERVER_REMOVED message for index($index): ${takServerDetail.address}")
        takServerDetail.numConnAttempts = 0
        invalidateConnection(takServerDetail, terminate = true)
        setIsAnyNetworkConnected()
        setIsAnyNetworkConnecting()
        takServerDetailsById.remove(takServerDetail.serverId, takServerDetail)
        takServerDetails.removeAt(index)
        dataRepository.removeTakServerChannels(takServerDetail.serverId)
        refreshDuplicateEndpointSuppression()
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public fun onMessageEvent(event: MessageEvent) {
        val message = event.message
        val addlIntInfo = event.addlIntInfo // This is the TAK Server index
        try {
            if (SERVER_CHANGED == message) {
                onServerChanged(addlIntInfo)
            } else if ((SERVER_ADDED == message) || (SERVER_ADDED_VIA_UI == message)) {
                onServerAdded(message, addlIntInfo)
            } else if (SERVER_TOGGLED == message) {
                onServerToggled(addlIntInfo)
            } else if (SERVER_REMOVED == message) {
                onServerRemoved(addlIntInfo)
            }
        } catch (e: Exception) {
            Log.w(TAG, "ERROR in MessageEvent ($message) w/ $addlIntInfo: ${e.message}")
            Log.w(TAG, "${e.printStackTrace()}")
        }
    }

    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var connectJob: Job? = null

    fun closeTakServerSockets() {
        for (takServer in takServerDetails) {
            invalidateConnection(takServer)
        }
    }

    fun connectToTakServers() {
        if (dataRepository.isCompanionConnected.value) {
            Log.w(TAG, "TAK Connect Supressed, Companion App is Active")
            return
        }
        isAnyTakConnected = false; isAnyTakConnectedLiveData.postValue(isAnyTakConnected)
        isAnyTakConnecting = false; isAnyTakConnectingLiveData.postValue(isAnyTakConnecting)
        dataRepository.setEndpointDisconnected(DataRepository.Endpoint.TAK_SERVER)

        requestStatusIconsComplicationUpdate(context)

        refreshDuplicateEndpointSuppression()
        for (takServer in takServerDetails) {
            if (takServer.isEnabled && !takServer.duplicateEndpointSuppressed) {
                getClientCertificateAndConnect(takServer)
            }
        }
    }

    private fun refreshDuplicateEndpointSuppression() {
        val seenEndpoints = mutableSetOf<String>()
        takServerDetails.forEach { detail ->
            val isDuplicate = !seenEndpoints.add(
                TakServerEndpointIdentity.key(detail.address, detail.port)
            )
            detail.duplicateEndpointSuppressed = isDuplicate
            if (isDuplicate) {
                invalidateConnection(detail)
            }
        }
    }

    companion object {
        private const val SHA256_ALGORITHM = "SHA-256"
        const val TAK_SERVER_DISCONNECTED = 0
        const val TAK_SERVER_DISCONNECTED_STRING = "DISCONNECTED"

        // The states TAK_SERVER_OBTAINING_CLIENT_CERT, TAK_SERVER_OBTAINED_CLIENT_CERT, TAK_SERVER_CONNECTING_COT_PORT
        // are part of the "CONNECTING" phase as far as the UI is concerned.
        const val TAK_SERVER_OBTAINING_CLIENT_CERT = 1
        const val TAK_SERVER_OBTAINED_CLIENT_CERT = 2
        const val TAK_SERVER_CONNECTING_COT_PORT = 3
        const val TAK_SERVER_CONNECTING_STRING = "CONNECTING"

        const val TAK_SERVER_CONNECTED_COT_PORT = 4
        const val TAK_SERVER_CONNECTED_STRING = "CONNECTED"

        const val TAK_SERVER_CONNECTION_FAILED = 5
        const val TAK_SERVER_CONNECTION_FAILED_STRING = "CONNECTION_FAILED"
        const val TAK_SERVER_DUPLICATE_STRING = "DUPLICATE"

        val isRunning : AtomicBoolean = AtomicBoolean(false)
        private val NUM_CONN_ATTEMPS_AFTER_FAILURE = 3
        private val TAG = "WearTAK-" + TAKConnectService::class.java.simpleName
        const private val TAK_CONNECT_SERVICE_NOTIFICATION_ID = 101
        const private val KEYSTORE_PASS = "atakatak"
        const private val KEY_ALIAS = "myKeyAlias"
        var geofenceEnabled: Boolean = false
        var isNetworkConnected = false
        var isWifiConnected = false
        var isAnyTakConnected : Boolean = false // Overall flag indicating that atleast one of the TAK servers is connected. This includes getting client cert and connecting to COT message port
        val isAnyTakConnectedLiveData = MutableLiveData<Boolean>(false)
        val context: Context = mainActivity.applicationContext

        var isAnyTakConnecting : Boolean = false // Overall flag indicating if connection is being made to any TAK server
        val isAnyTakConnectingLiveData = MutableLiveData<Boolean>(false)

        @Volatile var serviceStopping: Boolean = false
        private val serviceLifecycleLock = Any()
        private var activeService: TAKConnectService? = null
        @Volatile private var connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val channelTraceSequence = AtomicInteger(0)
        private val dataPackageSlot = java.util.concurrent.Semaphore(1)
        @Volatile private var activeMulticastSource: Pair<Network, String>? = null

        private fun receiveDataPackage(offer: TakDataPackageRequest, envelope: TakServerCotEnvelope) {
            if (envelope.sourcePeerAddress != null) {
                receivePeerDataPackage(offer, envelope)
                return
            }
            val detail = takServerDetailsById[envelope.sourceServerId]
            val source = try {
                if (detail == null) throw IOException("Package source is no longer configured")
                synchronized(detail.runtime.lock) {
                    val attempt = requireDataPackageSession(detail, envelope, serviceStopping)
                    attempt to TakDataPackageClient(
                        address = detail.address,
                        sslContext = detail.channelSslContext ?: throw IOException("Package TLS context unavailable"),
                        trustManager = detail.channelTrustManager ?: throw IOException("Package trust manager unavailable"),
                        hostnameVerifier = TakChannelHostnameVerifier()
                    )
                }
            } catch (error: IOException) {
                Log.w(TAG, "Route package source unavailable serverId=${envelope.sourceServerId} " +
                    "enabled=${detail?.isEnabled} status=${detail?.connectionStatus?.get()}", error)
                connectionScope.launch(Dispatchers.Main) {
                    Toast.makeText(context, R.string.route_package_failed, Toast.LENGTH_LONG).show()
                }
                return
            }
            val attempt = source.first
            val client = source.second
            if (!dataPackageSlot.tryAcquire()) {
                connectionScope.launch {
                    reportDataPackageFailure(detail, attempt, offer,
                        IOException("Another route package is being imported; resend after it finishes"))
                }
                return
            }
            val job = connectionScope.launch {
                try {
                    val routes = client.downloadRoutes(offer, detail.serverId)
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    synchronized(detail.runtime.lock) {
                        requireDataPackageSession(detail, envelope, serviceStopping)
                        if (takServerDetailsById[detail.serverId] !== detail) {
                            throw IOException("Package source configuration changed")
                        }
                    }
                    routes.forEach { dataRepository.upsertTakRoute(it) }
                    Log.i(TAG, "Route package imported serverId=${detail.serverId} routes=${routes.size}")
                    val now = System.currentTimeMillis()
                    offer.acknowledgement(MainActivity.device_uid, now, true)?.let {
                        sendData(detail, it, isNotPli = true, currTime = now)
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.route_package_imported, routes.size),
                            Toast.LENGTH_LONG).show()
                    }
                } catch (error: IOException) {
                    reportDataPackageFailure(detail, attempt, offer, error)
                } catch (error: IllegalArgumentException) {
                    reportDataPackageFailure(detail, attempt, offer, error)
                } catch (error: org.xmlpull.v1.XmlPullParserException) {
                    reportDataPackageFailure(detail, attempt, offer, error)
                }
            }
            job.invokeOnCompletion { dataPackageSlot.release() }
        }

        private fun receivePeerDataPackage(offer: TakDataPackageRequest, envelope: TakServerCotEnvelope) {
            val source = activeMulticastSource
            val peer = requireNotNull(envelope.sourcePeerAddress)
            val sourceId = "${envelope.sourceServerId}:peer:$peer"
            connectionScope.launch {
                var acquired = false
                try {
                    if (source == null || source.second != envelope.sourceSessionId || serviceStopping) {
                        throw IOException("Local receive session changed")
                    }
                    val connectivity = context.getSystemService(ConnectivityManager::class.java)
                    val address = java.net.InetAddress.getByAddress(
                        peer.split('.').map { it.toInt().toByte() }.toByteArray())
                    val links = connectivity?.getLinkProperties(source.first)
                    if (links == null || links.linkAddresses.none {
                            android.net.IpPrefix(it.address, it.prefixLength).contains(address)
                        } || links.linkAddresses.any { it.address == address }) {
                        throw IOException("Peer is not a remote device on the receiving Wi-Fi link")
                    }
                    acquired = dataPackageSlot.tryAcquire()
                    if (!acquired) throw IOException("Route package importer is busy")
                    val routes = TakDataPackageClient.peer(offer, peer, source.first).downloadRoutes(offer, sourceId)
                    currentCoroutineContext().ensureActive()
                    if (activeMulticastSource != source || serviceStopping) {
                        throw IOException("Local receive session changed")
                    }
                    routes.forEach { dataRepository.upsertTakRoute(it) }
                    Log.i(TAG, "Local route package imported peer=$peer routes=${routes.size}")
                    // Local transfer acknowledgements require peer-specific routing, not a TAK-server ACK.
                    if (offer.ackUid != null) Log.w(TAG, "Local package ACK routing unsupported peer=$peer")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.route_package_imported, routes.size),
                            Toast.LENGTH_LONG).show()
                    }
                } catch (error: IOException) {
                    val tls = error is javax.net.ssl.SSLException
                    Log.w(TAG, "Local route package failed peer=$peer reason=" +
                        if (tls) "TLS trust/hostname verification failed; no bypass" else "network/source unavailable")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context,
                            if (tls) R.string.route_peer_tls_failed else R.string.route_package_failed,
                            Toast.LENGTH_LONG).show()
                    }
                } catch (error: IllegalArgumentException) {
                    Log.w(TAG, "Local route package failed peer=$peer reason=package validation rejected")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.route_package_failed, Toast.LENGTH_LONG).show()
                    }
                } catch (error: org.xmlpull.v1.XmlPullParserException) {
                    Log.w(TAG, "Local route package failed peer=$peer reason=invalid package XML")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.route_package_failed, Toast.LENGTH_LONG).show()
                    }
                } finally {
                    if (acquired) dataPackageSlot.release()
                }
            }
        }

        private suspend fun reportDataPackageFailure(
            detail: TakServerDetail,
            attempt: ConnectionAttempt,
            offer: TakDataPackageRequest,
            error: Exception
        ) {
            Log.e(TAG, "Route package import failed serverId=${detail.serverId}", error)
            if (isCurrent(detail, attempt) && detail.isEnabled) {
                val now = System.currentTimeMillis()
                offer.acknowledgement(MainActivity.device_uid, now, false)?.let {
                    sendData(detail, it, isNotPli = true, currTime = now)
                }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.route_package_failed, Toast.LENGTH_LONG).show()
            }
        }

        var takServerDetails : MutableList<TakServerDetail> = emptyList<TakServerDetail>().toMutableList()
        private val takServerDetailsById = ConcurrentHashMap<String, TakServerDetail>()
        val serverRegistration = TakServerRegistration()
        fun retryServerRegistration() {
            val serverId = serverRegistration.state.value.serverId ?: return
            val ticket = serverRegistration.prepare(serverId)

            val job = connectionScope.launch {
                serverRegistration.saveAndConnect(
                    serverId,
                    save = { TakServerRegistration.SaveResult.SAVED },
                    connect = { TAK_CONNECT_SERVICE.registerServer(serverId) },
                    ticket = ticket,

                )
            }

            job.invokeOnCompletion { error ->
                if (error != null) {
                    serverRegistration.failPending(ticket)
                }
            }
        }
        private val _takServerRuntimeDetails =
            MutableStateFlow<List<TakServerDetail>>(emptyList())

        val takServerRuntimeDetails: StateFlow<List<TakServerDetail>> =
            _takServerRuntimeDetails
        fun getTakServerDetail(serverId: String): TakServerDetail? =
            TakServerId.normalizeOrNull(serverId)?.let(takServerDetailsById::get)

        fun refreshTakChannels(serverId: String) {
            val traceId = nextChannelTraceId("manual-refresh", serverId)
            val detail = getTakServerDetail(serverId)
            if (detail == null) {
                Log.e(TAG, "Cannot refresh TAK channels trace=$traceId: connected server not found for $serverId")
                return
            }
            Log.i(
                TAG,
                "Channel refresh requested trace=$traceId serverId=${detail.serverId} " +
                    "serverName=${detail.name} address=${detail.address} reason=user-request"
            )
            connectionScope.launch {
                detail.channelMutex.withLock {
                    loadTakChannels(
                        detail = detail,
                        sendLatestSa = true,
                        checkSupport = true,
                        traceId = traceId,
                        reason = "user-request",
                    )
                }
            }
        }

        fun updateTakChannel(
            serverId: String,
            channelBitPosition: Int,
            shouldBeActive: Boolean,
        ) {
            val traceId = nextChannelTraceId("toggle", serverId, channelBitPosition)
            val detail = getTakServerDetail(serverId)
            if (detail == null) {
                Log.e(TAG, "Cannot update TAK channel trace=$traceId: connected server not found for $serverId")
                return
            }
            Log.i(
                TAG,
                "Channel toggle requested trace=$traceId serverId=${detail.serverId} serverName=${detail.name} " +
                    "address=${detail.address} bitpos=$channelBitPosition desiredActive=$shouldBeActive"
            )
            connectionScope.launch {
                detail.channelMutex.withLock {
                    updateTakChannelLocked(
                        detail = detail,
                        channelBitPosition = channelBitPosition,
                        shouldBeActive = shouldBeActive,
                        traceId = traceId,
                    )
                }
            }
        }

        private suspend fun updateTakChannelLocked(
            detail: TakServerDetail,
            channelBitPosition: Int,
            shouldBeActive: Boolean,
            traceId: String,
        ) {
            val previous = dataRepository.takChannels.value
                .firstOrNull { it.serverId == detail.serverId }
            val previousChannel = previous?.channels
                ?.firstOrNull { it.bitPosition == channelBitPosition }
            if (previous == null || previousChannel == null) {
                Log.e(
                    TAG,
                    "Cannot update missing TAK channel trace=$traceId bitpos=$channelBitPosition " +
                        "serverId=${detail.serverId} knownChannels=${previous?.channels.orEmpty().diagnosticSummary()}"
                )
                return
            }

            Log.i(
                TAG,
                "Channel toggle starting trace=$traceId serverId=${detail.serverId} serverName=${detail.name} " +
                    "channel=${previousChannel.name} bitpos=$channelBitPosition " +
                    "previousActive=${previousChannel.active} desiredActive=$shouldBeActive " +
                    "allChannels=${previous.channels.diagnosticSummary()}"
            )

            dataRepository.updateTakServerChannels(
                TakChannelState.beginUpdate(
                    previous = previous,
                    channelBitPosition = channelBitPosition,
                    shouldBeActive = shouldBeActive,
                )
            )

            val api = try {
                channelApi(detail)
            } catch (error: Exception) {
                publishChannelUpdateFailure(detail, previous, traceId, error)
                return
            }

            val confirmed = try {
                api.updateChannel(
                    channelBitPosition = channelBitPosition,
                    shouldBeActive = shouldBeActive,
                    clientUid = MainActivity.device_uid,
                    traceId = traceId,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                publishChannelUpdateFailure(detail, previous, traceId, error)
                return
            }

            val confirmedState = TakServerChannels(
                serverId = detail.serverId,
                serverName = detail.name,
                status = channelStatus(confirmed.channels.isEmpty()),
                channels = confirmed.channels,
            )
            dataRepository.updateTakServerChannels(confirmedState)
            Log.i(
                TAG,
                "Channel toggle API accepted trace=$traceId serverId=${detail.serverId} " +
                    "confirmedChannels=${confirmed.channels.diagnosticSummary()}"
            )

            try {
                val clearSummary = dataRepository.clearSource(detail.serverId)
                Log.i(
                    TAG,
                    "Channel toggle local invalidation complete trace=$traceId ${clearSummary.diagnosticSummary()}"
                )
                val refreshed = api.loadGroups(
                    sendLatestSa = true,
                    checkSupport = false,
                    traceId = traceId,
                )
                dataRepository.updateTakServerChannels(
                    TakServerChannels(
                        serverId = detail.serverId,
                        serverName = detail.name,
                        status = channelStatus(refreshed.channels.isEmpty()),
                        channels = refreshed.channels,
                    )
                )
                Log.i(
                    TAG,
                    "Channel toggle reconciliation complete trace=$traceId serverId=${detail.serverId} " +
                        "refreshedChannels=${refreshed.channels.diagnosticSummary()} sendLatestSa=true"
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                dataRepository.updateTakServerChannels(
                    TakChannelState.refreshFailed(
                        confirmed = confirmedState,
                        message = "Channel updated, but live data refresh failed: ${channelError(error)}",
                    )
                )
                Log.e(
                    TAG,
                    "Channel refresh failed after update trace=$traceId serverId=${detail.serverId}",
                    error
                )
            }
        }

        private fun publishChannelUpdateFailure(
            detail: TakServerDetail,
            previous: TakServerChannels,
            traceId: String,
            error: Exception,
        ) {
            dataRepository.updateTakServerChannels(
                TakChannelState.updateFailed(
                    previous = previous,
                    message = "Channel update failed: ${channelError(error)}",
                )
            )
            Log.e(TAG, "Channel update failed trace=$traceId serverId=${detail.serverId}", error)
        }

        private suspend fun loadTakChannels(
            detail: TakServerDetail,
            sendLatestSa: Boolean,
            checkSupport: Boolean,
            traceId: String,
            reason: String,
        ) {
            val previous = dataRepository.takChannels.value
                .firstOrNull { it.serverId == detail.serverId }
            dataRepository.updateTakServerChannels(
                TakServerChannels(
                    serverId = detail.serverId,
                    serverName = detail.name,
                    status = TakChannelStatus.LOADING,
                    channels = previous?.channels.orEmpty(),
                )
            )
            Log.i(
                TAG,
                "Channel load starting trace=$traceId serverId=${detail.serverId} serverName=${detail.name} " +
                    "address=${detail.address} reason=$reason sendLatestSa=$sendLatestSa " +
                    "checkSupport=$checkSupport previous=${previous?.channels.orEmpty().diagnosticSummary()}"
            )
            try {
                val snapshot = channelApi(detail).loadGroups(
                    sendLatestSa = sendLatestSa,
                    checkSupport = checkSupport,
                    traceId = traceId,
                )
                dataRepository.updateTakServerChannels(
                    TakServerChannels(
                        serverId = detail.serverId,
                        serverName = detail.name,
                        status = channelStatus(snapshot.channels.isEmpty()),
                        channels = snapshot.channels,
                    )
                )
                Log.i(
                    TAG,
                    "Channel load complete trace=$traceId serverId=${detail.serverId} reason=$reason " +
                        "channels=${snapshot.channels.diagnosticSummary()}"
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: TakChannelsUnsupportedException) {
                dataRepository.updateTakServerChannels(
                    TakServerChannels(
                        serverId = detail.serverId,
                        serverName = detail.name,
                        status = TakChannelStatus.UNSUPPORTED,
                        channels = emptyList(),
                    )
                )
                Log.i(
                    TAG,
                    "Channel load unsupported trace=$traceId serverId=${detail.serverId} reason=$reason"
                )
            } catch (error: Exception) {
                dataRepository.updateTakServerChannels(
                    TakServerChannels(
                        serverId = detail.serverId,
                        serverName = detail.name,
                        status = TakChannelStatus.ERROR,
                        channels = previous?.channels.orEmpty(),
                        errorMessage = channelError(error),
                    )
                )
                Log.e(
                    TAG,
                    "Failed to refresh channels trace=$traceId serverId=${detail.serverId} reason=$reason",
                    error
                )
            }
        }



        // Refresh the available Data Sync missions for one TAK Server
        // Each server has its own mission list so the server ID is used to find the right connection
        // The refreshed list is saved in the repository for the rest of the app to access
        private suspend fun refreshAvailableMissions(
            serverId: String
        ) {
            // Find the connection details for the requested server
            // Stop if the server is no longer available
            val detail = getTakServerDetail(serverId) ?: return

            try {
                // Create the mission API client and request the latest mission list
                // This gets all missions the connected client is allowed to see
                val missions = missionApi(detail).getAllMissions()

                // Update the repository with the latest missions
                // Include server information so missions from different servers stay separate
                dataRepository.updateTakMissions(
                    serverId = detail.serverId,
                    serverName = detail.name,
                    serverAddress = detail.address,
                    missions = missions
                )

                // Log how many missions were returned
                Log.d(
                    TAG,
                    "Mission refresh: found ${missions.size} missions"
                )
            } catch (error: CancellationException) {
                // Let coroutine cancellation stop the operation normally
                throw error
            } catch (error: Exception) {
                // Log API or connection failures without stopping other server operations
                Log.e(TAG, "Mission refresh failed for $serverId", error)
            }
        }

        // Load the mission list when the watch connects to a TAK Server
        // Restore previous mission subscriptions after the list is downloaded
        // Start syncing subscribed missions so their content stays updated
        private suspend fun loadTakMissions(
            detail: TakServerDetail
        ) {
            // Tell the repository that this server's missions are loading
            // This allows the app to track the loading state
            dataRepository.updateTakMissionLoading(
                serverId = detail.serverId,
                serverName = detail.name,
                serverAddress = detail.address
            )

            Log.i(
                TAG,
                "Mission load starting serverId=${detail.serverId} " +
                        "serverName=${detail.name} address=${detail.address}"
            )

            try {
                // Log the server and client details used during mission requests
                // Useful when checking which device is requesting mission access
                Log.d(
                    TAG,
                    "MISSION AUTH " +
                            "server=${detail.address} " +
                            "username=${detail.username} " +
                            "clientUid=${MainActivity.device_uid}"
                )

                // Request the available Data Sync missions from the server
                // The response is converted into mission models by the API layer
                val missions = missionApi(detail).getAllMissions()

                // Save the mission models under the correct TAK Server
                // This makes the mission list available to other parts of the app
                dataRepository.updateTakMissions(
                    serverId = detail.serverId,
                    serverName = detail.name,
                    serverAddress = detail.address,
                    missions = missions
                )

                // Restore any mission subscriptions saved from an earlier connection
                // This lets the watch remember which missions were joined previously
                dataRepository.restorePersistedMissionState(
                    serverId = detail.serverId
                )

                // Start checking subscribed missions for new changes and CoT content
                syncSubscribedMissions(detail.serverId)

                Log.i(
                    TAG,
                    "Mission load complete serverId=${detail.serverId} " +
                            "missions=${missions.map { it.name }}"
                )

            } catch (error: CancellationException) {
                // Preserve normal cancellation behavior
                throw error
            } catch (error: Exception) {
                // Store the failure so the mission state can show what went wrong
                dataRepository.updateTakMissionError(
                    serverId = detail.serverId,
                    serverName = detail.name,
                    serverAddress = detail.address,
                    message = error.message ?: error.javaClass.simpleName
                )

                Log.e(
                    TAG,
                    "Mission load failed serverId=${detail.serverId}",
                    error
                )
            }
        }


        // Join a Data Sync mission on a specific TAK Server
        // The watch must subscribe before treating the mission as joined locally
        // After subscribing, the mission is synced to retrieve its available content
        fun subscribeToTakMission(
            serverId: String,
            missionName: String
        ) {
            // Find the TAK Server that owns the mission
            // Stop if the server connection details are missing
            val detail = getTakServerDetail(serverId) ?: run {
                Log.e(TAG, "TAK Server not found for serverId=$serverId")
                return
            }

            // Run the subscription request in the connection coroutine scope
            // This avoids blocking the caller while the network request runs
            connectionScope.launch {
                try {
                    Log.i(
                        TAG,
                        "Mission subscribe starting serverId=$serverId mission=$missionName"
                    )

                    // Send a subscription request to the TAK Server
                    // The device UID identifies which client is joining the mission
                    missionApi(detail).subscribeToMission(
                        missionName = missionName,
                        clientUid = MainActivity.device_uid
                    )

                    // Mark the mission as subscribed in the current repository state
                    // Other parts of the app can use this to determine joined missions
                    dataRepository.setMissionSubscribed(
                        serverId = serverId,
                        missionName = missionName,
                        subscribed = true
                    )

                    // Save the subscription so it can be restored after reconnecting
                    // Keep the subscription tied to its original TAK Server
                    dataRepository.persistMissionSubscribed(
                        serverId = serverId,
                        missionName = missionName
                    )

                    Log.i(
                        TAG,
                        "Mission subscribe complete serverId=$serverId mission=$missionName"
                    )

                    // Immediately check the joined mission for content
                    // This allows routes and other CoT events to be retrieved after joining
                    syncTakMission(
                        serverId = serverId,
                        missionName = missionName
                    )

                } catch (error: CancellationException) {
                    // Allow cancellation to stop the coroutine
                    throw error
                } catch (error: Exception) {
                    // Save the error without marking a failed subscription as successful
                    dataRepository.setMissionSyncError(
                        serverId = serverId,
                        missionName = missionName,
                        message = error.message ?: error.javaClass.simpleName
                    )

                    Log.e(
                        TAG,
                        "Mission subscribe failed serverId=$serverId mission=$missionName",
                        error
                    )
                }
            }
        }

        // Leave a Data Sync mission on a specific TAK Server
        // Remove locally stored routes associated with the mission
        // Clear the saved subscription so the mission is not automatically restored
        fun unsubscribeFromTakMission(
            serverId: String,
            missionName: String
        ) {
            // Find the TAK Server that owns the mission
            val detail = getTakServerDetail(serverId) ?: run {
                Log.e(TAG, "TAK Server not found for serverId=$serverId")
                return
            }

            // Run the unsubscribe request without blocking the caller
            connectionScope.launch {
                try {
                    // Tell the TAK Server that this device is leaving the mission
                    // The client UID identifies which subscription should be removed
                    missionApi(detail).unsubscribeFromMission(
                        missionName = missionName,
                        clientUid = MainActivity.device_uid
                    )

                    // Remove routes associated with the mission from local storage
                    // Include the server ID so routes on other servers are not affected
                    dataRepository.removeTakRoutesForMission(
                        serverId = serverId,
                        missionName = missionName
                    )

                    // Update the local mission state to show that the device has left
                    dataRepository.setMissionSubscribed(
                        serverId = serverId,
                        missionName = missionName,
                        subscribed = false
                    )

                    // Remove the saved subscription from persistent storage
                    // This prevents the mission from being restored as joined later
                    dataRepository.persistMissionUnsubscribed(
                        serverId = serverId,
                        missionName = missionName
                    )

                    Log.i(
                        TAG,
                        "Mission unsubscribe complete serverId=$serverId mission=$missionName"
                    )

                } catch (error: CancellationException) {
                    // Allow cancellation to stop the coroutine
                    throw error
                } catch (error: Exception) {
                    // Record the error if the server does not complete the unsubscribe request
                    dataRepository.setMissionSyncError(
                        serverId = serverId,
                        missionName = missionName,
                        message = error.message ?: error.javaClass.simpleName
                    )

                    Log.e(
                        TAG,
                        "Mission unsubscribe failed serverId=$serverId mission=$missionName",
                        error
                    )
                }
            }
        }


        // Keep subscribed missions updated while the server connection is active
        // Check for newly available missions and updates to existing missions
        // Each server is handled separately to prevent mixing mission content
        private suspend fun syncSubscribedMissions(
            serverId: String
        ) {
            // Continue syncing until the coroutine is cancelled
            // Stopping the connection coroutine also stops this polling loop
            while (currentCoroutineContext().isActive) {

                // Refresh the available mission list from this TAK Server
                // This allows newly created missions to appear in the repository
                refreshAvailableMissions(serverId)

                // Find missions currently marked as subscribed on this server
                // Unsubscribed missions do not need their content downloaded
                val subscribedMissions =
                    dataRepository.getSubscribedMissions(serverId)

                // Check each subscribed mission for updated content
                // The mission name identifies which Data Sync mission to request
                subscribedMissions.forEach { state ->
                    syncTakMission(
                        serverId = serverId,
                        missionName = state.mission.name
                    )
                }

                // Wait 20 seconds before starting the next check
                // This prevents the app from constantly sending mission API requests
                delay(20_000L)
            }
        }


        // Sync the content of one subscribed Data Sync mission
        // Check the server for changes since previous syncs
        // Download CoT when new changes exist or the mission has no stored routes
        // Send downloaded events through the existing CoT processing system
        private suspend fun syncTakMission(
            serverId: String,
            missionName: String
        ) {
            // Find the server connection used for this mission
            // Stop if the server details are unavailable
            val detail = getTakServerDetail(serverId) ?: run {
                Log.e(TAG, "TAK Server not found for serverId=$serverId")
                return
            }

            // Find the mission in the repository under the correct server
            // Mission names alone are not enough because different servers may use the same name
            // Stop if the requested mission is missing from the repository
            val state = dataRepository.takMissions.value
                .firstOrNull { it.serverId == serverId }
                ?.missions
                ?.firstOrNull { it.mission.name == missionName }
                ?: return

            // Only sync missions that the watch has joined
            // This prevents downloading content for missions that are not subscribed
            if (!state.subscribed) return

            // Mark the mission as syncing before making network requests
            // The repository can use this state to show that an update is in progress
            dataRepository.setMissionSyncing(
                serverId = serverId,
                missionName = missionName
            )

            try {
                // Create the mission API client for the matching TAK Server
                // The client uses the server's existing TLS configuration
                val api = missionApi(detail)

                // Request the mission change history from the TAK Server
                // Changes can indicate that mission content was added or updated
                val changes = api.getMissionChanges(missionName)
                val creatorByRouteUid = changes
                    .filter {
                        it.type == "ADD_CONTENT" &&
                                !it.contentUid.isNullOrBlank() &&
                                !it.creatorUid.isNullOrBlank()
                    }
                    .associate {
                        it.contentUid!! to it.creatorUid!!
                    }
                val routeSenders = changes
                    .filter { it.type == "ADD_CONTENT" }
                    .mapNotNull { change ->
                        val routeUid = change.contentUid
                        val senderUid = change.creatorUid

                        if (routeUid != null && senderUid != null) {
                            routeUid to senderUid
                        } else {
                            null
                        }
                    }
                    .toMap()

                // Compare the returned changes against previously processed changes
                // Each change has a key based on its type, content UID, and timestamp
                // Only changes without a matching saved key are treated as new
                val newChanges = changes.filter {
                    it.changeKey() !in state.processedChangeKeys
                }

                // Log the total number of changes and how many are new
                Log.i(
                    TAG,
                    "Mission sync serverId=$serverId mission=$missionName " +
                            "changes=${changes.size} new=${newChanges.size}"
                )

                // Log the routes currently stored in the repository
                // Useful for checking whether downloaded routes were saved correctly
                // Also shows whether each route has a mission name associated with it
                Log.d(
                    TAG,
                    "Stored routes for mission=$missionName: " +
                            dataRepository.takRoutes.value.map {
                                "${it.name} uid=${it.uid} mission=${it.missionName}"
                            }
                )

                // Decide whether the mission's CoT content needs to be downloaded
                // Download if no route is stored for this server and mission
                // Also download if the server reports any unprocessed changes
                // Otherwise skip downloading the same mission content again
                if (
                    dataRepository.takRoutes.value.none {
                        it.serverId == serverId && it.missionName == missionName
                    } || newChanges.isNotEmpty()
                ) {

                    // Request the current CoT content from the Data Sync mission
                    // The response can contain multiple CoT events in one XML payload
                    val cot = api.getMissionCot(missionName)

                    // Log the downloaded payload size for debugging
                    Log.i(
                        TAG,
                        "Mission content downloaded serverId=$serverId " +
                                "mission=$missionName bytes=${cot.toByteArray().size}"
                    )

                    // Separate the mission XML into individual CoT event messages
                    // Each event needs to be processed on its own by the CoT ingestor
                    val events = extractMissionCotEvents(cot)

                    // Log how many CoT events were extracted from the mission
                    Log.d(
                        TAG,
                        "Mission CoT events found=${events.size}"
                    )

                    // Process every CoT event downloaded from the mission
                    // This reuses the same parser used for live TAK Server messages
                    for (eventXml in events) {

                        // Wrap the event with information about its source server
                        // Include the server address and the time the event was received
                        // This helps downstream handlers keep data separated by server
                        val envelope = TakServerCotEnvelope(
                            sourceServerId = serverId,
                            sourceServerAddress = detail.address,
                            receivedAt = System.currentTimeMillis(),
                            rawXml = eventXml
                        )

                        // Log the extracted XML to help inspect the CoT structure
                        Log.d("MISSION_TEST", "Extracted event XML=$eventXml")

                        // Pass the event to the existing CoT ingestor
                        // Route events are identified by their b-m-r event type
                        // TakRouteCotParser converts valid route XML into a TakRoute
                        // The route is then passed to the onRoute handler
                        val result = cotIngestor.ingest(envelope)

                        // Log whether the event was processed as a route or another CoT type
                        // Malformed or unsupported events are also reported here
                        Log.d(
                            TAG,
                            "Mission CoT processed mission=$missionName " +
                                    "result=$result"
                        )
                    }

                } else {
                    // Skip the CoT download when a route is already stored
                    // and the mission has no newly reported changes
                    Log.d(
                        TAG,
                        "Mission sync skipped: route already loaded, no new changes"
                    )
                }



                // Attach mission creator information to matching routes
                dataRepository.takRoutes.value
                    .filter { route ->
                        route.serverId == serverId &&
                                creatorByRouteUid.containsKey(route.uid) &&
                                (route.missionName == null || route.missionName == missionName)
                    }
                    .forEach { route ->
                        val creatorUid = creatorByRouteUid[route.uid] ?: return@forEach

                        if (route.sender != creatorUid || route.missionName != missionName) {
                            dataRepository.upsertTakRoute(
                                route.copy(
                                    sender = creatorUid,
                                    missionName = missionName
                                )
                            )

                            Log.d(
                                TAG,
                                "Route sender updated: route=${route.name}, creatorUid=$creatorUid"
                            )
                        }
                    }

                // Add newly processed change keys to the existing set
                // These keys are used during the next sync to identify new changes
                val processedKeys =
                    state.processedChangeKeys +
                            newChanges.map { it.changeKey() }

                // Record the time when this sync finished
                val syncTime = System.currentTimeMillis()

                // Mark the mission sync as complete in the repository
                // Save the latest sync time and processed change keys in memory
                dataRepository.setMissionSyncComplete(
                    serverId = serverId,
                    missionName = missionName,
                    processedChangeKeys = processedKeys,
                    syncTime = syncTime
                )

                // Save the sync progress for future sessions
                // This helps the app recognize changes that were already processed
                // The progress is stored separately for each server and mission
                dataRepository.persistMissionSyncProgress(
                    serverId = serverId,
                    missionName = missionName,
                    lastSyncTime = syncTime,
                    processedChangeKeys = processedKeys
                )

            } catch (error: CancellationException) {
                // Let coroutine cancellation stop the sync normally
                throw error

            } catch (error: Exception) {
                // Record the failure so the repository knows this sync did not complete
                // Keep the error associated with the correct server and mission
                dataRepository.setMissionSyncError(
                    serverId = serverId,
                    missionName = missionName,
                    message = error.message ?: error.javaClass.simpleName
                )

                Log.e(
                    TAG,
                    "Mission sync failed serverId=$serverId mission=$missionName",
                    error
                )
            }
        }



        // Split downloaded Data Sync XML into separate CoT event messages
        // A mission response can contain multiple event elements
        // Each event is turned into its own XML string for the normal CoT ingestor
        private fun extractMissionCotEvents(xml: String): List<String> {

            // Store the individual CoT XML messages found in the response
            val events = mutableListOf<String>()

            // Create an XML pull parser to read the response one tag at a time
            // This allows individual events to be found inside a larger XML document
            val parser = XmlPullParserFactory.newInstance()
                .newPullParser()

            // Disable namespace processing and provide the XML response as input
            parser.setFeature(
                XmlPullParser.FEATURE_PROCESS_NAMESPACES,
                false
            )
            parser.setInput(StringReader(xml))

            // Start reading from the first XML parser event
            var eventType = parser.eventType

            // Continue reading until the end of the XML document
            while (eventType != XmlPullParser.END_DOCUMENT) {

                // Look for the start of a CoT event element
                // Each event element will be extracted as a separate XML message
                if (
                    eventType == XmlPullParser.START_TAG &&
                    parser.name == "event"
                ) {

                    // Create a new string writer to hold this CoT event
                    // Create an XML serializer to rebuild the event as valid XML
                    val writer = StringWriter()
                    val serializer = XmlPullParserFactory.newInstance()
                        .newSerializer()

                    serializer.setOutput(writer)
                    serializer.startDocument("UTF-8", null)

                    // Track how deeply nested the XML tags are
                    // The event is complete when the depth returns to zero
                    // This keeps nested tags inside the correct CoT event
                    var depth = 0

                    do {
                        when (parser.eventType) {

                            // Copy each opening XML tag into the new event
                            XmlPullParser.START_TAG -> {
                                serializer.startTag(
                                    parser.namespace,
                                    parser.name
                                )

                                // Copy all attributes from the original tag
                                // This preserves fields such as type, UID, coordinates, and callsign
                                for (i in 0 until parser.attributeCount) {
                                    serializer.attribute(
                                        parser.getAttributeNamespace(i),
                                        parser.getAttributeName(i),
                                        parser.getAttributeValue(i)
                                    )
                                }

                                // Increase depth when entering a new XML element
                                depth++
                            }

                            // Copy the closing XML tag into the new event
                            XmlPullParser.END_TAG -> {
                                serializer.endTag(
                                    parser.namespace,
                                    parser.name
                                )

                                // Decrease depth when leaving an XML element
                                depth--
                            }

                            // Preserve text stored inside XML elements
                            XmlPullParser.TEXT -> {
                                serializer.text(parser.text)
                            }

                            // Preserve any CDATA sections inside the event
                            XmlPullParser.CDSECT -> {
                                serializer.cdsect(parser.text)
                            }
                        }

                        // Move to the next parser event while still inside this CoT event
                        if (depth > 0) {
                            parser.next()
                        }

                    } while (depth > 0)

                    // Finish rebuilding this CoT event as its own XML document
                    serializer.endDocument()

                    // Add the completed XML string to the list of extracted events
                    events.add(writer.toString())
                }

                // Continue looking for more CoT events in the mission response
                eventType = parser.next()
            }

            // Return all extracted events for individual processing
            return events
        }


        // Create a key that identifies one mission change
        // Combines the change type, affected content UID, and timestamp
        // Used during syncing to avoid treating previously processed changes as new
        private fun TakMissionChange.changeKey(): String {

            // Replace missing optional values with empty strings
            // Join the fields into one string for comparison against saved keys
            return listOf(
                type,
                contentUid.orEmpty(),
                timestamp.orEmpty()
            ).joinToString("|")
        }


        private fun channelApi(detail: TakServerDetail): TakChannelApi {
            check(detail.isEnabled && detail.connectionStatus.get() == TAK_SERVER_CONNECTED_COT_PORT) {
                "TAK Server Xis not connected"
            }
            val sslContext = checkNotNull(detail.channelSslContext) {
                "TAK Server channel TLS context is unavailable"
            }
            val trustManager = checkNotNull(detail.channelTrustManager) {
                "TAK Server channel trust manager is unavailable"
            }
            return TakChannelApi(
                address = detail.address,
                sslContext = sslContext,
                trustManager = trustManager,
                serverId = detail.serverId,
                serverName = detail.name,
                hostnameVerifier = checkNotNull(detail.channelHostnameVerifier) {
                    "TAK Server channel hostname verifier is unavailable"
                },
            )
        }


        // Create the REST API client used for Data Sync mission requests
        // Use the same server connection details and TLS settings already configured
        // Each client is associated with one TAK Server
        private fun missionApi(detail: TakServerDetail): TakMissionApi {

            // Make sure the server is enabled and its CoT connection is active
            // Mission requests should only be made through a connected server
            check(
                detail.isEnabled &&
                        detail.connectionStatus.get() == TAK_SERVER_CONNECTED_COT_PORT
            ) {
                "TAK Server is not connected"
            }

            // Get the SSL context configured for this server
            // This contains the TLS configuration needed for secure connections
            // Stop if the required context is unavailable
            val sslContext = checkNotNull(detail.channelSslContext) {
                "TAK Server API TLS context is unavailable"
            }

            // Get the trust manager used to validate the server certificate
            // Stop if the trust manager has not been configured
            val trustManager = checkNotNull(detail.channelTrustManager) {
                "TAK Server API trust manager is unavailable"
            }

            // Create the mission API client using the server address and TLS settings
            // Server identity is also included to keep requests tied to the correct server
            return TakMissionApi(
                address = detail.address,
                sslContext = sslContext,
                trustManager = trustManager,
                serverId = detail.serverId,
                serverName = detail.name
            )
        }


        // Start loading Data Sync missions after a TAK Server connects
        // Launch the loading process in the background without blocking connection setup
        // The loading function also restores subscriptions and begins mission syncing
        private fun loadTakMissionsAfterConnect(
            detail: TakServerDetail
        ) {
            // Run the mission loading process in the connection coroutine scope
            connectionScope.launch {
                loadTakMissions(detail)
            }
        }


        private fun channelStatus(empty: Boolean): TakChannelStatus =
            if (empty) TakChannelStatus.EMPTY else TakChannelStatus.READY

        private fun channelError(error: Exception): String =
            error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName

        private fun loadTakChannelsAfterConnect(detail: TakServerDetail) {
            val traceId = nextChannelTraceId("post-connect-load", detail.serverId)
            connectionScope.launch {
                detail.channelMutex.withLock {
                    loadTakChannels(
                        detail = detail,
                        sendLatestSa = false,
                        checkSupport = true,
                        traceId = traceId,
                        reason = "post-connect",
                    )
                }
            }
        }



        private suspend fun reconcileServerChannelChange(serverId: String) {
            val traceId = nextChannelTraceId("server-notice", serverId)
            val detail = getTakServerDetail(serverId)
            if (detail == null) {
                Log.e(TAG, "Cannot reconcile server channel notice trace=$traceId: serverId=$serverId not found")
                return
            }
            detail.channelMutex.withLock {
                Log.i(
                    TAG,
                    "Server channel-change notice reconciliation starting trace=$traceId " +
                        "serverId=$serverId serverName=${detail.name} address=${detail.address}"
                )
                try {
                    val clearSummary = dataRepository.clearSource(serverId)
                    Log.i(
                        TAG,
                        "Server channel-change notice local invalidation complete trace=$traceId " +
                            clearSummary.diagnosticSummary()
                    )
                } catch (error: Exception) {
                    Log.e(TAG, "Unable to invalidate live data trace=$traceId serverId=$serverId", error)
                    return@withLock
                }
                loadTakChannels(
                    detail = detail,
                    sendLatestSa = true,
                    checkSupport = false,
                    traceId = traceId,
                    reason = "inbound-t-x-g-c",
                )
            }
        }

        private fun nextChannelTraceId(
            operation: String,
            serverId: String,
            bitPosition: Int? = null,
        ): String = buildString {
            append(operation)
            append('-')
            append(serverId.take(8))
            bitPosition?.let {
                append("-bit")
                append(it)
            }
            append('-')
            append(channelTraceSequence.incrementAndGet())
        }

        private fun List<TakChannel>.diagnosticSummary(): String = joinToString(
            prefix = "[",
            postfix = "]",
        ) { channel ->
            "${channel.name}{bitpos=${channel.bitPosition},direction=${channel.direction}," +
                "active=${channel.active},updating=${channel.updating}}"
        }

        private fun cotPayloadFingerprint(rawXml: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(rawXml.toByteArray(Charsets.UTF_8))
                .take(8)
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

        var newServerAddedViaUi = false
        val newServerAddedViaUiConnectionStatusLiveData: MutableLiveData<String> = MutableLiveData<String>(TAK_SERVER_CONNECTING_STRING)

        private val bhRepo by lazy {
            try {
                (context as WearTakApplication).bhRepo
            } catch (_: Throwable) {
                BloodhoundRepository(context)
            }
        }

        private val cotIngestor by lazy {
            TakServerCotIngestor(
                sink = object : TakServerCotSink {
                    override suspend fun onChat(event: TakChatEvent) {
                        Log.d(
                            TAG,
                            "Inbound chat stamped sourceServerId=${event.sourceServerId} " +
                                "receivedAt=${event.receivedAt} roomUids=${event.parsedRooms.orEmpty().map { it.uid }} " +
                                "channelOwnership=unavailable"
                        )
                        EventBus.getDefault().post(event)
                    }

                    override suspend fun onGeofence(geofence: GeofenceCot) {
                        Log.d(
                            TAG,
                            "Inbound geofence stamped uid=${geofence.uid} sourceServerId=${geofence.sourceServerId} " +
                                "eventTime=${geofence.eventTime} receivedAt=${geofence.receivedAt} " +
                                "channelOwnership=unavailable"
                        )
                        GeofenceRepository.sendGeofenceCot(geofence)
                    }

                    override suspend fun onUser(
                        event: CotUserPositionEvent,
                        envelope: TakServerCotEnvelope
                    ) {
                        val shouldTrack = dataRepository.applyReceivedUserEvent(
                            event = event,
                            source = envelope.sourceServerAddress,
                            sourceServerId = envelope.sourceServerId,
                            receivedAt = envelope.receivedAt
                        )
                        Log.d(
                            TAG,
                                "Inbound user delivered to map state uid=${event.uid} " +
                                "sourceServerId=${envelope.sourceServerId} sourceAddress=${envelope.sourceServerAddress} " +
                                "eventTime=${event.time} nonStale=$shouldTrack channelOwnership=unavailable"
                        )
                        if (!shouldTrack) {
                            Log.i(
                                TAG,
                                "Processed stale received user uid=${event.uid} " +
                                    "sourceServerId=${envelope.sourceServerId}"
                            )
                        }
                    }

                    override suspend fun onMarker(
                        event: CotMarkerEvent,
                        envelope: TakServerCotEnvelope
                    ) {
                        val shouldTrack = dataRepository.applyReceivedMarkerEvent(
                            event = event,
                            source = envelope.sourceServerAddress,
                            sourceServerId = envelope.sourceServerId,
                            receivedAt = envelope.receivedAt
                        )
                        Log.d(
                            TAG,
                                "Inbound marker delivered to map state uid=${event.uid} " +
                                "sourceServerId=${envelope.sourceServerId} sourceAddress=${envelope.sourceServerAddress} " +
                                "eventTime=${event.time} nonStale=$shouldTrack channelOwnership=unavailable"
                        )
                        if (!shouldTrack) {
                            Log.i(
                                TAG,
                                "Processed stale received marker uid=${event.uid} " +
                                    "sourceServerId=${envelope.sourceServerId}"
                            )
                            return
                        }

                        val mapItem = CotMarkerMapItem(event)
                        bhRepo.updateBloodhoundEvent(
                            BloodhoundRepository.BloodhoundEvent(
                                mapItem = mapItem,
                                server = envelope.sourceServerAddress,
                                timeIssued = mapItem.link?.productionTime,
                                timeCompleted = null,
                                remark = event.detail.remarks ?: "",
                                sourceServerId = envelope.sourceServerId,
                                receivedAt = envelope.receivedAt
                            )
                        )
                        Log.d(
                            TAG,
                            "Inbound marker delivered to Bloodhound state uid=${event.uid} " +
                                "sourceServerId=${envelope.sourceServerId} eventTime=${event.time}"
                        )
                    }


                    // Handle routes received from incoming TAK CoT messages
                    // Routes may come through the normal CoT connection or mission sync
                    // The CoT ingestor has already identified and parsed the route
                    override suspend fun onRoute(
                        route: TakRoute,
                        envelope: TakServerCotEnvelope
                    ) {

                        // Add the parsed route to the repository
                        // Update the existing route if its identity is already stored
                        // The route includes its UID, name, points, and source server
                        dataRepository.upsertTakRoute(route)

                        // Log the route details after it has been stored
                        // The point count helps verify that route coordinates were extracted
                        // Server and mission fields help identify where the route came from
                        Log.d(
                            TAG,
                            "Route received: uid=${route.uid}, " +
                                    "name=${route.name}, " +
                                    "points=${route.points.size}, " +
                                    "serverId=${route.serverId}, " +
                                    "mission=${route.missionName}"
                        )
                    }


                    override suspend fun onEmergency(
                        event: com.aitech.weartak_civ.cot.CotEmergencyEvent,
                        envelope: TakServerCotEnvelope
                    ) {
                        bhRepo.applyEmergencyEvent(
                            event, envelope.sourceServerAddress, envelope.sourceServerId,
                            envelope.receivedAt, MainActivity.device_uid
                        )
                    }

                    override suspend fun onChannelChanged(envelope: TakServerCotEnvelope) {
                        Log.i(
                            TAG,
                            "Inbound t-x-g-c channel-change CoT sourceServerId=${envelope.sourceServerId} " +
                                "sourceAddress=${envelope.sourceServerAddress} receivedAt=${envelope.receivedAt}"
                        )
                        reconcileServerChannelChange(envelope.sourceServerId)
                    }
                },
                isChatEnabled = { dataRepository.getEnableChatService.first() },
                isGeofenceEnabled = { geofenceEnabled },
                deviceUid = { MainActivity.device_uid },
                deviceCallsign = { MainActivity.callsign },
                chatCommandHandler = ChatCommandRuntime.create(context, dataRepository),
                dataPackageHandler = { offer, envelope -> receiveDataPackage(offer, envelope) }
            )
        }

        suspend fun handleInboundCot(rawXml: String, source: String) {
            val envelope = TakServerCotEnvelope(
                sourceServerId = source,
                sourceServerAddress = source,
                receivedAt = System.currentTimeMillis(),
                rawXml = rawXml
            )

            val result = cotIngestor.ingest(envelope)

            Log.d(TAG, "Plugin CoT ingested source=$source result=$result")
        }

        private val TAK_CONNECT_SERVICE = TAKConnectService()
        public fun getInstance() : TAKConnectService {
            return TAK_CONNECT_SERVICE
        }

        private fun isCurrent(detail: TakServerDetail, attempt: ConnectionAttempt): Boolean {
            synchronized(attemptRuntime(detail).lock) {
                return detail.runtime.currentAttempt === attempt &&
                        detail.runtime.generation == attempt.generation &&
                        !detail.runtime.terminated &&
                        !serviceStopping
            }
        }

        private fun publishRegistrationResult(
            detail: TakServerDetail,
            attempt: ConnectionAttempt,
            status: TakServerRegistration.Status,
            failure: TakConnectionFailure? = null
        ) {
            synchronized(detail.runtime.lock) {
                if (isCurrent(detail, attempt)) {
                    serverRegistration.publish(detail.serverId, detail, status, failure)
                }
            }
        }

        private fun attemptRuntime(detail: TakServerDetail): ConnectionRuntime = detail.runtime

        private fun beginConnectionAttempt(detail: TakServerDetail): ConnectionAttempt? {
            val oldSockets: MutableList<SSLSocket> = mutableListOf()
            val attempt: ConnectionAttempt
            synchronized(detail.runtime.lock) {
                if (serviceStopping || detail.runtime.terminated ||
                    !detail.isEnabled || !isNetworkConnected ||
                    detail.duplicateEndpointSuppressed) {
                    return null
                }
                detail.runtime.connectJob?.cancel()
                detail.runtime.readerJob?.cancel()
                detail.runtime.connectingSocket?.let { oldSockets.add(it) }
                detail.secureSocket?.let { oldSockets.add(it) }
                detail.runtime.connectingSocket = null
                detail.secureSocket = null
                detail.runtime.generation++
                attempt = ConnectionAttempt(detail.runtime.generation)
                detail.runtime.currentAttempt = attempt
                detail.runtime.connectJob = null
                detail.runtime.readerJob = null
                detail.connectionStatus.set(TAK_SERVER_DISCONNECTED)
                detail.connectionStatusLiveData.postValue(TAK_SERVER_DISCONNECTED_STRING)
            }
            oldSockets.distinct().forEach { runCatching { it.close() } }
            return attempt
        }

        private fun invalidateConnection(detail: TakServerDetail, terminate: Boolean = false) {
            val sockets: MutableList<SSLSocket> = mutableListOf()
            synchronized(detail.runtime.lock) {
                serverRegistration.publish(
                    detail.serverId, detail, TakServerRegistration.Status.FAILED,
                    serverRegistration.unavailable()
                )
                detail.runtime.generation++
                if (terminate) detail.runtime.terminated = true
                detail.runtime.connectJob?.cancel()
                detail.runtime.readerJob?.cancel()
                detail.runtime.connectingSocket?.let { sockets.add(it) }
                detail.secureSocket?.let { sockets.add(it) }
                detail.runtime.connectingSocket = null
                detail.secureSocket = null
                detail.runtime.currentAttempt = null
                detail.runtime.connectJob = null
                detail.runtime.readerJob = null
                detail.channelSslContext = null
                detail.channelTrustManager = null
                detail.connectionStatus.set(TAK_SERVER_DISCONNECTED)
                detail.connectionStatusLiveData.postValue(TAK_SERVER_DISCONNECTED_STRING)
            }
            sockets.distinct().forEach { runCatching { it.close() } }
        }

        private fun readTextFileOrNull(f: java.io.File): String? =
            try { f.readText() } catch (_: Exception) { null }

        // does this tak server connection have a side loaded cert in  /sdcard/Android/data/com.aitech.weartak_civ/files/certs
        private fun maybeLoadP12FromDisk(t: TakServerDetail): Boolean {
            val certDir = java.io.File(context.getExternalFilesDir(null), "certs")
            val binP12 = java.io.File(certDir, "${t.address}.p12")
            val b64P12 = java.io.File(certDir, "${t.address}.p12.b64")
            val pwdFile = java.io.File(certDir, "${t.address}.pwd")
            Log.i(
                TAG,
                "P12 sideload check: certDir=${certDir.absolutePath}, " +
                        "bin=${binP12.absolutePath} exists=${binP12.exists()} readable=${binP12.canRead()}, " +
                        "b64=${b64P12.absolutePath} exists=${b64P12.exists()}, " +
                        "pwd=${pwdFile.absolutePath} exists=${pwdFile.exists()}"
            )

            // don't load p12 if it doesn't exist
            if (!binP12.exists() && !b64P12.exists()) return false

            // fallback to "atakatak" if no password file provided.
            val pwd = readTextFileOrNull(pwdFile)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "atakatak"

            return try {
                t.keyStore.load(null, null) // init empty

                if (binP12.exists()) {
                    java.io.FileInputStream(binP12).use {
                        t.keyStore.load(it, pwd.toCharArray())
                    }
                } else {
                    val b64 = readTextFileOrNull(b64P12)?.replace("\\s+".toRegex(), "") ?: return false
                    val bytes = java.util.Base64.getDecoder().decode(b64)
                    java.io.ByteArrayInputStream(bytes).use {
                        t.keyStore.load(it, pwd.toCharArray())
                    }
                }

                // We’re now using a file-based P12; mark password for KeyManagerFactory.init in connectTAKServer
                t.isP12Cert = true                 // drive P12 path semantics later (password handling)
                t.p12CertPassword = pwd

                // Optional but very helpful during bring-up:
                dumpKeyStoreAliases(t.keyStore, "$TAG/${t.address}")

                true
            } catch (e: Exception) {
                Log.e(TAG, "maybeLoadP12FromDisk failed for ${t.address}: ${e.message}", e)
                false
            }
        }

        private fun setIsAnyNetworkConnecting() {
            var anyTakConnecting = false
            for (takServerDetail in takServerDetails) {
                if (takServerDetail.isEnabled) {
                    val connectionStatus = takServerDetail.connectionStatus.get()
                    if ((TAK_SERVER_OBTAINING_CLIENT_CERT == connectionStatus) ||
                        (TAK_SERVER_OBTAINED_CLIENT_CERT == connectionStatus) ||
                        (TAK_SERVER_CONNECTING_COT_PORT == connectionStatus)) {
                        anyTakConnecting = true
                        break
                    }
                }
            }
            isAnyTakConnecting = anyTakConnecting
            isAnyTakConnectingLiveData.postValue(anyTakConnecting)
            requestStatusIconsComplicationUpdate(context)

            //Log.d(TAG, "setIsAnyNetworkConnecting: $isAnyTakConnecting")
        }

        private fun escapeDnValue(v: String): String {
            // minimal but practical escaping for DN values
            return v
                .replace("\\", "\\\\")
                .replace(",", "\\,")
                .replace("+", "\\+")
                .replace("\"", "\\\"")
                .replace("<", "\\<")
                .replace(">", "\\>")
                .replace(";", "\\;")
                .replace("=", "\\=")
                .let { s ->
                    // escape leading/trailing spaces
                    var out = s
                    if (out.startsWith(" ")) out = "\\$out"
                    if (out.endsWith(" ")) out = out.dropLast(1) + "\\ "
                    out
                }
        }

        private fun generateBase64CSR(nameValueList: MutableList<NameValueEntry>, takServerDetail: TakServerDetail): String? {
            val cnName = takServerDetail.username
            var base64CSR: String? = null
            try {
                // Add the BouncyCastle security provider
                Security.addProvider(BouncyCastleProvider())

                // Generate a KeyPair (RSA 4096).
                val keyPairGen = KeyPairGenerator.getInstance("RSA", "BC")
                keyPairGen.initialize(4096)
                val keyPair = keyPairGen.generateKeyPair()
                takServerDetail.privateKey = keyPair.private

                // Build the X.500 Name (subject) for the CSR
                //    e.g., "C=US, ST=NY, L=New York, O=MyOrg, CN=example.com"
                // TAK Server expects exactly those name value fields input as well as CN=$cnName
                // TODO: Also send version information as separate POST message I think??
                var subjectStr = "CN=${escapeDnValue(cnName)}"
                if (nameValueList.size == 0) {
                    Log.w(TAG, "generateBase64CSR: size of nameValueList is 0")
                } else {
                    for (nameValueEntry in nameValueList) {
                        subjectStr = "$subjectStr, ${nameValueEntry.name}=${escapeDnValue(nameValueEntry.value)}"
                    }
                }
                Log.d(TAG, "subjectStr =($subjectStr)")
                val subject = X500Name(subjectStr)

                // 4. Create the CSR builder
                val p10Builder = JcaPKCS10CertificationRequestBuilder(subject, keyPair.public)

                // (No challenge password set)

                // Create a ContentSigner for "SHA256withRSA" using the private key
                val csBuilder = JcaContentSignerBuilder("SHA256withRSA")
                //csBuilder.setProvider("BC")
                val signer = csBuilder.build(takServerDetail.privateKey)

                // Build the PKCS#10 CSR
                val csr = p10Builder.build(signer)

                base64CSR = Base64.getEncoder().encodeToString(csr.encoded)
            } catch (e: Exception) {
                Log.e(TAG, "ERROR in generateBase64CSR: ${e.message}", e)
            }
            return base64CSR
        }

        fun fetchAndParseCertificateConfig(takServerDetail: TakServerDetail): MutableList<NameValueEntry>? {
            val takServer = takServerDetail.address
            val username = takServerDetail.username
            val password = takServerDetail.password
            val nameValueList : MutableList<NameValueEntry> = mutableListOf()

            val url = "https://$takServer:8446/Marti/api/tls/config"
            val client = OkHttpClient()

            // Basic Authentication
            val credential = Credentials.basic(username, password)

            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", credential)
                    .get()
                    .build()

                Log.d(TAG, "fetchAndParseCertificateConfig: GET $url")
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "ERROR fetch certification config response returned unexpected HTTP code: ${response.code} w/ body: ${response.body?.string()}")
                        return nameValueList
                    }

                    val responseBody = response.body?.string()
                    if (null == responseBody) {
                        Log.e(TAG, "ERROR fetch certification config response is null")
                        return nameValueList
                    } else {
                        Log.d(TAG, "fetchAndParseCertificateConfig received response: $responseBody")
                    }

                    // Parse XML response
                    val document = parseXml(responseBody)
                    val nameEntries = document.getElementsByTagName("nameEntry")
                    for (i in 0 until nameEntries.length) {
                        val element = nameEntries.item(i) as? org.w3c.dom.Element
                        val name = element?.getAttribute("name")
                        val value = element?.getAttribute("value")
                        if ((null !=name) && (null != value)) {
                                nameValueList.add(NameValueEntry(name, value))
                            }
                    }

                    Log.d(TAG, "fetchAndParseCertificateConfig: Received nameEntry Pairs: $nameValueList")
                    return nameValueList
                }
            } catch (e: Exception) {
                Log.e(TAG, "ERROR in fetchAndParseCertificateConfig: ${e.message}", e)
                return null
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "ERROR in fetchAndParseCertificateConfig: ${e.message}", e)
                return null
            }
            return nameValueList
        }

        fun parseXml(xml: String): Document {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val inputSource = InputSource(StringReader(xml))
            return builder.parse(inputSource)
        }

        // Returns null on ERROR else response body as String
        private fun postCsrAndGetResponse(takServer: String, username: String, password: String, base64CSR: String): String? {
            try {
                var versionName: String? = null
                try {
                    val pInfo: PackageInfo = MainActivity.mainActivity.getPackageManager()
                        .getPackageInfo(MainActivity.mainActivity.getPackageName(), 0)
                    versionName = pInfo.versionName
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "postCsrAndGetResponse: Cannot get versionName")
                }
                var versionInfo = ""
                if (null != versionName) {
                    versionInfo = "+v$versionName"
                }
                val urlString = "https://$takServer:8446/Marti/api/tls/signClient/v2?clientUid=${MainActivity.device_uid}&version=WearTAK-Civ$versionInfo"

                Log.d(TAG, "postCsrAndGetResponse CSR request: ($urlString)")

                // Build OkHttpClient
                val client = OkHttpClient()

                // Basic Auth header
                val credential = Credentials.basic(username, password)

                // Prepare the request body (text/plain)
                val body: RequestBody =
                    RequestBody.create("text/plain; charset=utf-8".toMediaTypeOrNull(), base64CSR)

                // Build the request
                val request: Request = Request.Builder()
                    .url(urlString)
                    .header("Authorization", credential)
                    .header("Content-Type", "text/plain")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "ERROR CSR response returned unexpected HTTP code: ${response.code} w/ body: ${response.body?.string()}")
                        return null
                    }
                    val responseBodyString = response.body?.string()
                    Log.v(TAG, "postCsrAndGetResponse: obtained response body $responseBodyString")
                    // Return the response body as string
                    return responseBodyString
                }
            } catch (e: Exception) {
                Log.e(TAG, "postCsrAndGetResponse ERROR: ${e.message}", e)
            }
            return null
        }

        fun postCsrAndParse(takServerDetail: TakServerDetail, base64CSR: String): TakSignedCertificates? {
            val takServer = takServerDetail.address
            val username = takServerDetail.username
            val password = takServerDetail.password

            var result: TakSignedCertificates? = null

            val csrResponse = postCsrAndGetResponse(takServer, username, password, base64CSR)
            if (null != csrResponse) {
                try {
                    // Servers may return any number of caN entries (e.g. TAK Ops returns ca0..ca2).
                    result = TakEnrollment.parseSignResponse(csrResponse)
                    Log.i(TAG, "postCsrAndParse: signed certificate with ${result.authorities.size} CA(s) " +
                        result.authorities.joinToString { it.first })
                } catch (e: Exception) {
                    Log.e(TAG, "postCsrAndParse: could not parse response", e)
                }
            }
            return result
        }
        fun isCertExpiringSoon(expiryDate: Date): Boolean {
            val now = Date()
            val diffInMillis = expiryDate.time - now.time
            val diffInDays = TimeUnit.MILLISECONDS.toDays(diffInMillis)
            return diffInDays < 3
        }

        fun deleteFile(fileName: String) {
            try {
                if (MainActivity.mainActivity.deleteFile(fileName)) {
                    Log.d(TAG, "Successfully removed $fileName")
                } else {
                    Log.w(TAG, "Could not remove $fileName")
                }
            } catch(e: Exception) {
                Log.e(TAG, "ERROR in deleteFile: ${e.message}", e)
            }
        }

        private fun certificateCacheFileName(takServerDetail: TakServerDetail): String =
            certificateCacheFileName(takServerDetail.address, takServerDetail.port)

        private fun certificateCacheFileName(address: String, port: Int): String {
            val endpoint = TakServerEndpointIdentity.key(address, port)
            val digest = MessageDigest.getInstance(SHA256_ALGORITHM).digest(endpoint.toByteArray())
            val encoded = android.util.Base64.encodeToString(
                digest,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            ).trimEnd('=')
            return "tak_${encoded}.jks"
        }

        fun loadKeyStore(keyStore : KeyStore, fileName: String, password: String): Boolean {
            return runBlocking {
                try {
                    MainActivity.mainActivity.openFileInput(fileName).use { fis ->
                        keyStore.load(fis, password.toCharArray())
                    }
                    val chain = keyStore.getCertificateChain(KEY_ALIAS)

                    if (chain != null && chain.size > 0) {
                        // The first certificate in the chain is the user's certificate (signedCert)
                        val signedCert = chain[0] as X509Certificate

                        val signedCertExpiry : Date = signedCert.getNotAfter()
                        if (isCertExpiringSoon(signedCertExpiry)) {
                            Log.w(TAG, "Certificate is expiring soon ($signedCertExpiry). Getting new certificate")
                            return@runBlocking false
                        } else {
                            Log.v(TAG, "Certificate is NOT expiring soon ($signedCertExpiry)")
                        }
                    } else {
                        Log.e(TAG, "No certificate chain found for alias: $KEY_ALIAS")
                        return@runBlocking false
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "loadKeyStore failed w/ exception: ${e.message}", e)
                    return@runBlocking false
                }
                return@runBlocking true
            }
        }

        fun setKeystore(takServerDetail: TakServerDetail, signed: TakSignedCertificates): Boolean {
            val cacheFileName = certificateCacheFileName(takServerDetail)
            var retVal = true

            try {
                Log.i(TAG, "signedCert expiry date: ${signed.signedCert.notAfter}")
                signed.authorities.forEach { (name, cert) -> Log.v(TAG, "$name expiry date: ${cert.notAfter}") }

                // Keystore password to protect the .p12
                val keystorePassword = KEYSTORE_PASS.toCharArray()
                // Leaf first, then every CA in server order (ca0 intermediate ... root), each also stored as trusted.
                signed.install(takServerDetail.keyStore, requireNotNull(takServerDetail.privateKey) { "Missing CSR private key" },
                    KEY_ALIAS, keystorePassword)
                runBlocking {
                    try {
                        MainActivity.mainActivity.openFileOutput(cacheFileName, Context.MODE_PRIVATE).use { fos ->
                            takServerDetail.keyStore.store(fos, keystorePassword)
                            Log.i(TAG, "Keystore stored successfully in $cacheFileName")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Not able to store keystore file as $cacheFileName. Error: ${e.message}")
                        retVal = true // Really no need for this but....
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ERROR in setKeystore: ${e.message}", e)
                retVal = false
            }
            return retVal
        }

        fun base64ToInputStream(encodedString: String?): InputStream? {
            var inputStream: InputStream? = null
            if (null == encodedString) {
                return inputStream
            }
            try {
                // Decode the Base64 string into a byte array
                val decodedBytes = Base64.getDecoder().decode(encodedString)

                // Create and return an InputStream from the byte array
                inputStream = ByteArrayInputStream(decodedBytes)
            } catch (e: Exception) {
                Log.d(TAG, "ERROR in base64ToInputStream: ${e.message}", e)
            }
            return inputStream
        }

        private fun checkIssuerEqualsSubject(cert1: X509Certificate, cert2: X509Certificate): Boolean {
            return cert1.issuerX500Principal == cert2.subjectX500Principal
        }

        fun dumpKeyStoreAliases(ks: KeyStore, tag: String = TAG) {
            try {
                val aliases: Enumeration<String> = ks.aliases()
                if (!aliases.hasMoreElements()) {
                    Log.d(tag, "(keystore is empty)")
                    return
                }
                while (aliases.hasMoreElements()) {
                    val alias = aliases.nextElement()
                    val isKey = ks.isKeyEntry(alias)
                    val isTrustedCert = ks.isCertificateEntry(alias)
                    val type = when {
                        isKey -> "keyEntry"
                        isTrustedCert -> "trustedCert"
                        else -> "unknown"
                    }
                    Log.d(tag, "alias=\"$alias\" type=$type")

                    if (isKey) {
                        // For key entries you can inspect the certificate chain
                        val chain = ks.getCertificateChain(alias)
                        Log.d(tag, "  chain length=${chain?.size ?: 0}")
                        chain?.forEachIndexed { idx, c ->
                            (c as? X509Certificate)?.let { x ->
                                logX509("$tag/$alias[$idx]", x)
                            }
                        }
                    } else {
                        // For trusted cert entries there’s a single certificate
                        (ks.getCertificate(alias) as? X509Certificate)?.let { x ->
                            logX509("$tag/$alias", x)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to dump keystore aliases: ${e.message}", e)
            }
        }

        private fun logX509(tag: String, cert: X509Certificate) {
            Log.d(tag, "  Subject: ${cert.subjectX500Principal.name}")
            Log.d(tag, "  Issuer : ${cert.issuerX500Principal.name}")
            Log.d(tag, "  Valid  : notBefore=${cert.notBefore}  notAfter=${cert.notAfter}")
            Log.d(tag, "  SHA256 : ${sha256(cert.encoded)}")
            // Uncomment if you also want SANs (may be null)
            // val sans = try { cert.subjectAlternativeNames?.joinToString { it[1].toString() } ?: "(none)" } catch (_: Exception) { "(n/a)" }
            // Log.d(tag, "  SANs   : $sans")
        }

        private fun sha256(bytes: ByteArray): String {
            val md = MessageDigest.getInstance(SHA256_ALGORITHM).digest(bytes)
            // Android Base64
            return android.util.Base64.encodeToString(md, android.util.Base64.NO_WRAP)
            // If not on Android, use:
            // return java.util.Base64.getEncoder().encodeToString(md)
        }

    fun getClientCertificateAndConnect(takServerDetail: TakServerDetail) {
            if (takServerDetail.duplicateEndpointSuppressed) {
                Log.w(TAG, "TAK connection suppressed for duplicate endpoint=${takServerDetail.address}:${takServerDetail.port}")
                serverRegistration.publish(
                    takServerDetail.serverId, takServerDetail, TakServerRegistration.Status.DUPLICATE
                )
                return
            }
            if (isCompanionAppConnected || dataRepository.isCompanionConnected.value ||
                serviceStopping || takServerDetail.runtime.terminated || !takServerDetail.isEnabled ||
                !connectionScope.isActive) {
                Log.w(TAG, "TAK connection unavailable: companion, service or server gate")
                serverRegistration.publish(
                    takServerDetail.serverId, takServerDetail, TakServerRegistration.Status.FAILED,
                    serverRegistration.unavailable()
                )
                return
            }
            Log.d(TAG, "getClientCertificateAndConnect called for server(${takServerDetail.address}) w/ numConnAttempts(${takServerDetail.numConnAttempts})")
            takServerDetail.connectionStatus.set(TAK_SERVER_DISCONNECTED)
            takServerDetail.connectionStatusLiveData.postValue(TAK_SERVER_DISCONNECTED_STRING)
            if (isNetworkConnected) {
                if (takServerDetail.numConnAttempts > NUM_CONN_ATTEMPS_AFTER_FAILURE) {
                    serverRegistration.publish(
                        takServerDetail.serverId, takServerDetail, TakServerRegistration.Status.FAILED,
                        serverRegistration.unavailable()
                    )
                    return
                }
                val attempt = beginConnectionAttempt(takServerDetail)
                if (attempt == null) {
                    serverRegistration.publish(
                        takServerDetail.serverId, takServerDetail, TakServerRegistration.Status.FAILED,
                        serverRegistration.unavailable()
                    )
                    return
                }
                takServerDetail.numConnAttempts++
                val job = connectionScope.launch {
                    try {
                        isAnyTakConnecting = true; isAnyTakConnectingLiveData.postValue(true)
                        takServerDetail.connectionStatus.set(TAK_SERVER_OBTAINING_CLIENT_CERT)
                        takServerDetail.connectionStatusLiveData.postValue(TAK_SERVER_CONNECTING_STRING)
                        requestStatusIconsComplicationUpdate(context)

                        // see if we have one locally
                        if (!takServerDetail.isP12Cert || takServerDetail.p12Cert.isNullOrBlank()) {
                            Log.i(TAG, "Not P12 for ${takServerDetail.address} ... checking if p12 locally loaded ...")
                            if (maybeLoadP12FromDisk(takServerDetail)) {
                                Log.i(TAG, "Loaded PKCS12 from disk for ${takServerDetail.address}; short-circuiting to connect")
                                takServerDetail.connectionStatus.set(TAK_SERVER_OBTAINED_CLIENT_CERT)
                                takServerDetail.connectionStatusLiveData.postValue(TAK_SERVER_CONNECTING_STRING)
                                connectTAKServer(takServerDetail, attempt)
                                return@launch            // <<< IMPORTANT
                            } else {
                                Log.w(TAG, "Didn't find a P12 for ${takServerDetail.address} in certs/")
                            }
                        }

                        if (takServerDetail.isP12Cert && !takServerDetail.p12Cert.isNullOrBlank()) {
                            Log.d(TAG, "isP12Cert true")
                            //takServerDetail.keyStore = KeyStore.getInstance("PKCS12")
                            val inputStream = base64ToInputStream(takServerDetail.p12Cert)
                            if (null != inputStream) {
                                try {
                                    takServerDetail.keyStore.load(inputStream, takServerDetail.p12CertPassword?.toCharArray() ?: "".toCharArray())
                                    takServerDetail.connectionStatus.set(TAK_SERVER_OBTAINED_CLIENT_CERT)
                                    takServerDetail.connectionStatusLiveData.postValue(TAK_SERVER_CONNECTING_STRING)
                                    connectTAKServer(takServerDetail, attempt)
                                } catch (e: Exception) {
                                    Log.e(TAG, "ERROR getClientCertificateAndConnect: Cannot load p12Cert for takServer ${takServerDetail.address}: ${e.message}", e)
                                    takServerDetail.connectionStatus.set(TAK_SERVER_CONNECTION_FAILED)
                                    takServerDetail.connectionStatusLiveData.postValue(TAK_SERVER_CONNECTION_FAILED_STRING)
                                    setIsAnyNetworkConnected()
                                    setIsAnyNetworkConnecting()
                                    if (newServerAddedViaUi) {
                                        newServerAddedViaUi = false
                                        newServerAddedViaUiConnectionStatusLiveData.postValue(TAK_SERVER_CONNECTION_FAILED_STRING)
                                    }
                                }
                            } else {
                                Log.e(TAG, "ERROR getClientCertificateAndConnect: Cannot parse p12Cert for takServer ${takServerDetail.address}")
                                takServerDetail.connectionStatus.set(TAK_SERVER_CONNECTION_FAILED)
                                takServerDetail.connectionStatusLiveData.postValue(TAK_SERVER_CONNECTION_FAILED_STRING)
                                setIsAnyNetworkConnected()
                                setIsAnyNetworkConnecting()
                                if (newServerAddedViaUi) {
                                    newServerAddedViaUi = false
                                    newServerAddedViaUiConnectionStatusLiveData.postValue(TAK_SERVER_CONNECTION_FAILED_STRING)
                                }
                            }
                        } else {
                            if (takServerDetail.numConnAttempts == NUM_CONN_ATTEMPS_AFTER_FAILURE) {
                                // This is to address a bug found when connecting to tak.weartak.com where
                                // certificate was valid but we got the error below and we could not connect
                                // to the server till that server needed to be removed (which removes the .jks file)
                                // and server details added again.
                                // The error found was:
                                // ERROR in connectTAKServer: Error configuring certificate: ssl=0xe737db58:  (Explain with AI)
                                // error:10000112:SSL routines:OPENSSL_internal:CERTIFICATE_AND_PRIVATE_KEY_MISMATCH (external/boringssl/src/ssl/ssl_cert.cc:281 0xb261e757:0x00000000)
                                // javax.net.ssl.SSLHandshakeException: Error configuring certificate: ssl=0xe737db58:
                                // error:10000112:SSL routines:OPENSSL_internal:CERTIFICATE_AND_PRIVATE_KEY_MISMATCH (external/boringssl/src/ssl/ssl_cert.cc:281 0xb261e757:0x00000000)
                                //    at com.android.org.conscrypt.SSLUtils.toSSLHandshakeException(SSLUtils.java:356)
                                //    at com.android.org.conscrypt.ConscryptEngine.convertException(ConscryptEngine.java:1134)
                                //    at com.android.org.conscrypt.ConscryptEngine.unwrap(ConscryptEngine.java:919)
                                //    at com.android.org.conscrypt.ConscryptEngine.unwrap(ConscryptEngine.java:747)
                                //    at com.android.org.conscrypt.ConscryptEngine.unwrap(ConscryptEngine.java:712)
                                //    at com.android.org.conscrypt.ConscryptEngineSocket$SSLInputStream.processDataFromSocket(ConscryptEngineSocket.java:896)
                                //    at com.android.org.conscrypt.ConscryptEngineSocket$SSLInputStream.-$$Nest$mprocessDataFromSocket(Unknown Source:0)
                                //    at com.android.org.conscrypt.ConscryptEngineSocket.doHandshake(ConscryptEngineSocket.java:236)
                                //    at com.android.org.conscrypt.ConscryptEngineSocket.startHandshake(ConscryptEngineSocket.java:218)
                                //    at com.aitech.weartak_civ.service.TAKConnectService$Companion.connectTAKServer(TAKConnectService.kt:1186)
                                //    at com.aitech.weartak_civ.service.TAKConnectService$Companion$getClientCertificateAndConnect$1.invokeSuspend(TAKConnectService.kt:1043)
                                //    at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:33)
                                //    at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:104)
                                //    at kotlinx.coroutines.internal.LimitedDispatcher$Worker.run(LimitedDispatcher.kt:111)
                                //    at kotlinx.coroutines.scheduling.TaskImpl.run(Tasks.kt:99)
                                //    at kotlinx.coroutines.scheduling.CoroutineScheduler.runSafely(CoroutineScheduler.kt:584)
                                //    at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.executeTask(CoroutineScheduler.kt:811)
                                //    at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.runWorker(CoroutineScheduler.kt:715)
                                //    at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.run(CoroutineScheduler.kt:702)
                                //    Caused by: javax.net.ssl.SSLException: Error configuring certificate: ssl=0xe737db58:
                                //    error:10000112:SSL routines:OPENSSL_internal:CERTIFICATE_AND_PRIVATE_KEY_MISMATCH (external/boringssl/src/ssl/ssl_cert.cc:281 0xb261e757:0x00000000)
                                //    at com.android.org.conscrypt.NativeCrypto.setLocalCertsAndPrivateKey(Native Method)
                                //    at com.android.org.conscrypt.NativeSsl.setCertificate(NativeSsl.java:267)
                                //    at com.android.org.conscrypt.NativeSsl.chooseClientCertificate(NativeSsl.java:230)
                                //    at com.android.org.conscrypt.ConscryptEngine.clientCertificateRequested(ConscryptEngine.java:1654)
                                //    at com.android.org.conscrypt.NativeCrypto.ENGINE_SSL_read_direct(Native Method)
                                //    at com.android.org.conscrypt.NativeSsl.readDirectByteBuffer(NativeSsl.java:568)
                                //    at com.android.org.conscrypt.ConscryptEngine.readPlaintextDataDirect(ConscryptEngine.java:1095)
                                //    at com.android.org.conscrypt.ConscryptEngine.readPlaintextData(ConscryptEngine.java:1079)
                                //    at com.android.org.conscrypt.ConscryptEngine.unwrap(ConscryptEngine.java:876)
                                //    ... 16 more                                                                                                      ... 16 more
                                deleteFile(certificateCacheFileName(takServerDetail))
                            }
                            if (loadKeyStore(takServerDetail.keyStore, certificateCacheFileName(takServerDetail), KEYSTORE_PASS)) {
                                Log.d(TAG, "Successfully loaded keyStore for ${takServerDetail.address}")
                                takServerDetail.connectionStatus.set(TAK_SERVER_OBTAINED_CLIENT_CERT)
                                takServerDetail.connectionStatusLiveData.postValue(TAK_SERVER_CONNECTING_STRING)
                                    connectTAKServer(takServerDetail, attempt)
                            } else {
                                deleteFile(certificateCacheFileName(takServerDetail))
                                Log.i(TAG, "Cannot load keyStore for ${takServerDetail.address}. Going to generate CSR")
                                val nameValueList = fetchAndParseCertificateConfig(takServerDetail)
                                var signClientResponse: TakSignedCertificates? = null
                                var base64CSR: String? = null
                                if (null != nameValueList) {
                                   base64CSR = generateBase64CSR(nameValueList, takServerDetail)
                                }
                                if (null != base64CSR) {
                                    //Log.d(TAG, "base64CSR NOT null. Going to call postCsrAndParse")
                                    signClientResponse = postCsrAndParse(takServerDetail, base64CSR)
                                    if (null != signClientResponse) {
                                        //Log.d(TAG, "signClientResponse NOT null. Going to call setKeystore")
                                        if (setKeystore(takServerDetail, signClientResponse)) {
                                            Log.d(TAG, "setKeystore success: calling connectTAKServer")
                                            takServerDetail.connectionStatus.set(TAK_SERVER_OBTAINED_CLIENT_CERT)
                                            takServerDetail.connectionStatusLiveData.postValue(TAK_SERVER_CONNECTING_STRING)
                                            connectTAKServer(takServerDetail, attempt)
                                        } else {
                                            deleteFile(certificateCacheFileName(takServerDetail))
                                            Log.e(TAG, "ERROR in setKeystore: Cannot connect to TAK Server")
                                            takServerDetail.connectionStatus.set(TAK_SERVER_CONNECTION_FAILED)
                                            takServerDetail.connectionStatusLiveData.postValue(TAK_SERVER_CONNECTION_FAILED_STRING)
                                            setIsAnyNetworkConnected()
                                            setIsAnyNetworkConnecting()
                                            if (newServerAddedViaUi) {
                                                newServerAddedViaUi = false
                                                newServerAddedViaUiConnectionStatusLiveData.postValue(TAK_SERVER_CONNECTION_FAILED_STRING)
                                            }
                                        }
                                    } else {
                                        Log.e(TAG, "ERROR postCsrAndParse returned NULL: Cannot connect to TAK Server")
                                        takServerDetail.connectionStatus.set(TAK_SERVER_CONNECTION_FAILED)
                                        takServerDetail.connectionStatusLiveData.postValue(TAK_SERVER_CONNECTION_FAILED_STRING)
                                        setIsAnyNetworkConnected()
                                        setIsAnyNetworkConnecting()
                                        if (newServerAddedViaUi) {
                                            newServerAddedViaUi = false
                                            newServerAddedViaUiConnectionStatusLiveData.postValue(TAK_SERVER_CONNECTION_FAILED_STRING)
                                        }
                                    }
                                } else {
                                    Log.e(TAG, "ERROR fetchAndParseCertificateConfig or generateBase64CSR returned NULL. Cannot connect to TAK Server")
                                    takServerDetail.connectionStatus.set(TAK_SERVER_CONNECTION_FAILED)
                                    takServerDetail.connectionStatusLiveData.postValue(TAK_SERVER_CONNECTION_FAILED_STRING)
                                    setIsAnyNetworkConnected()
                                    setIsAnyNetworkConnecting()
                                    if (newServerAddedViaUi) {
                                        newServerAddedViaUi = false
                                        newServerAddedViaUiConnectionStatusLiveData.postValue(TAK_SERVER_CONNECTION_FAILED_STRING)
                                    }
                                }
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (isCurrent(takServerDetail, attempt)) {
                            val failure = TakConnectionFailure.from(TakEnrollmentStage.CERTIFICATE, error)
                            Log.e(TAG, "TAK enrollment failed: ${failure.displayMessage()}")
                            takServerDetail.connectionStatus.set(TAK_SERVER_CONNECTION_FAILED)
                            takServerDetail.connectionStatusLiveData.postValue(TAK_SERVER_CONNECTION_FAILED_STRING)
                            setIsAnyNetworkConnected()
                            setIsAnyNetworkConnecting()
                            publishRegistrationResult(
                                takServerDetail, attempt,
                                TakServerRegistration.Status.FAILED, failure
                            )
                        }
                    } finally {
                        val failure = if (takServerDetail.connectionStatus.get() == TAK_SERVER_CONNECTION_FAILED) {
                            TakConnectionFailure(TakEnrollmentStage.CERTIFICATE, TakFailureCategory.CERTIFICATE)
                        } else serverRegistration.unavailable()
                        publishRegistrationResult(
                            takServerDetail, attempt, TakServerRegistration.Status.FAILED, failure
                        )
                    }
                }

                synchronized(takServerDetail.runtime.lock) {
                    if (takServerDetail.runtime.currentAttempt === attempt) {
                        attempt.job = job
                        takServerDetail.runtime.connectJob = job
                    } else {
                        job.cancel()
                    }
                }
                job.invokeOnCompletion {
                    synchronized(takServerDetail.runtime.lock) {
                        if (takServerDetail.runtime.currentAttempt === attempt &&
                            takServerDetail.runtime.connectJob === job) {
                            takServerDetail.runtime.connectJob = null
                            serverRegistration.publish(
                                takServerDetail.serverId, takServerDetail,
                                TakServerRegistration.Status.FAILED, serverRegistration.unavailable()
                            )
                        }
                    }
                }
            } else {
                Log.d(TAG, "Network is NOT connected. Hence not trying to connect to TAK server")
                serverRegistration.publish(
                    takServerDetail.serverId, takServerDetail, TakServerRegistration.Status.FAILED,
                    serverRegistration.unavailable()
                )
                if (newServerAddedViaUi) {
                    newServerAddedViaUi = false
                    newServerAddedViaUiConnectionStatusLiveData.postValue(TAK_SERVER_CONNECTION_FAILED_STRING)
                }
            }
        }

        fun firstKeyAlias(ks: KeyStore): String? {
            val e = ks.aliases()
            while (e.hasMoreElements()) {
                val a = e.nextElement()
                if (ks.isKeyEntry(a)) return a
            }
            return null
        }

        fun findRootInChain(ks: KeyStore): X509Certificate? {
            val alias = firstKeyAlias(ks)
            Log.d(TAG, "findRootInChain for alias=$alias")
            val chain = ks.getCertificateChain(alias)
                ?.map { it as X509Certificate }
            if (null == chain) return null

            // Prefer a self-signed cert (subject == issuer AND signature verifies with its own key)
            chain.firstOrNull { isSelfSigned(it) }?.let { return it }

            // Fallback: pick the cert whose issuer DN is not the subject DN of any other (top of chain)
            val subjects = chain.map { it.subjectX500Principal.name }.toSet()
            val candidate = chain.firstOrNull { it.issuerX500Principal.name !in subjects }
            return candidate
        }

        private fun isSelfSigned(cert: X509Certificate): Boolean = try {
            if (cert.subjectX500Principal != cert.issuerX500Principal) {
                Log.d(TAG, "isSelfSigned=false")
                false
            } else {
                cert.verify(cert.publicKey);
                Log.d(TAG, "isSelfSigned=true")
                true
            }
        } catch (_: Exception) {
            Log.d(TAG, "isSelfSigned=false")
            false
        }

        var isCompanionAppConnected = false
        
        fun connectTAKServer(
            takServerDetail: TakServerDetail,
            attempt: ConnectionAttempt
        ) {
            if (isCompanionAppConnected) {
                Log.w(TAG, "connectTAKServer CompanionAppIsConnected back out early")
                return
            }
            if (!isCurrent(takServerDetail, attempt)) return
            val takServer = takServerDetail.address
            val port = takServerDetail.port
            Log.i(TAG,"connectTAKServer called for takServer ($takServer)")
            if (takServerDetail.connectionStatus.getAndSet(TAK_SERVER_CONNECTING_COT_PORT) == TAK_SERVER_OBTAINED_CLIENT_CERT) {
                takServerDetail.connectionStatusLiveData.postValue(TAK_SERVER_CONNECTING_STRING)
                try {
                    Log.d(TAG, "Aliases for server $takServer")
                    dumpKeyStoreAliases(takServerDetail.keyStore, TAG)
                    // Initialize the KeyManagerFactory with the client certificate
                    val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                    if (takServerDetail.isP12Cert) {
                        keyManagerFactory.init(takServerDetail.keyStore, takServerDetail.p12CertPassword?.toCharArray()?:"".toCharArray())
                    } else {
                        keyManagerFactory.init(takServerDetail.keyStore, KEYSTORE_PASS.toCharArray())
                    }

                    // Initialize SSL Context with TrustManager
                    val root = findRootInChain(takServerDetail.keyStore)
                    Log.d(TAG, "TAK trust root present=${root != null}")
                    val trustKs = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                        load(null, null)
                        setCertificateEntry("root", root)
                    }
                    val trustManagerFactory = if (null != root) {
                        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                            .apply { init(trustKs) }
                    } else {
                        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                            .apply { init (takServerDetail.keyStore) }

                    }
                    /*
                    val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                    trustManagerFactory.init(takServerDetail.keyStore)
                     */
                    val trustManager = trustManagerFactory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()

                    val sslContext = SSLContext.getInstance("TLSv1.3")
                    if (null != trustManager) {
                        sslContext.init(keyManagerFactory.keyManagers, arrayOf(trustManager), null)
                    } else {
                        Log.e(TAG, "trustManager is null")
                        throw Exception("X509TrustManager is NULL. Cannot connect to ${takServerDetail.name}.")
                    }

                    Log.d(TAG, "Going to createSocket and startHandshake")
                    // Create the socket and connect
                    val socketFactory = sslContext.socketFactory
                    val socket = socketFactory.createSocket(takServer, port) as SSLSocket
                    synchronized(takServerDetail.runtime.lock) {
                        if (!isCurrent(takServerDetail, attempt)) {
                            runCatching { socket.close() }
                            return
                        }
                        attempt.socket = socket
                        takServerDetail.runtime.connectingSocket = socket
                    }
                    Log.d(TAG, "Starting secureSocket handshake")
                    socket.startHandshake()
                    synchronized(takServerDetail.runtime.lock) {
                        if (!isCurrent(takServerDetail, attempt) ||
                            !takServerDetail.isEnabled || !isNetworkConnected) {
                            runCatching { socket.close() }
                            return
                        }
                        takServerDetail.runtime.connectingSocket = null
                        takServerDetail.secureSocket = socket
                        attempt.socket = socket
                    }
                    takServerDetail.channelSslContext = sslContext
                    takServerDetail.channelTrustManager = trustManager
                    isAnyTakConnected = true
                    isAnyTakConnectedLiveData.postValue(isAnyTakConnected)
                    dataRepository.setEndpointConnected(DataRepository.Endpoint.TAK_SERVER)
                    requestStatusIconsComplicationUpdate(context)

                    takServerDetail.connectionStatus.set(TAK_SERVER_CONNECTED_COT_PORT)
                    takServerDetail.connectionStatusLiveData.postValue(TAK_SERVER_CONNECTED_STRING)
                    publishRegistrationResult(
                        takServerDetail, attempt, TakServerRegistration.Status.CONNECTED
                    )
                    // Reset counters for connection attempts
                    takServerDetail.numConnAttempts = 0
                    setIsAnyNetworkConnecting()
                    Log.d(TAG, "Successfully connected to $takServer on port $port")
                    if (newServerAddedViaUi) {
                        newServerAddedViaUi = false
                        newServerAddedViaUiConnectionStatusLiveData.postValue(TAK_SERVER_CONNECTED_STRING)
                    }
                    loadTakChannelsAfterConnect(takServerDetail)
                    loadTakMissionsAfterConnect(takServerDetail)
                    startCotReader(takServerDetail, socket, attempt)
                } catch (e: Exception) {
                    runCatching { attempt.socket?.close() }
                    synchronized(takServerDetail.runtime.lock) {
                        if (takServerDetail.runtime.currentAttempt === attempt) {
                            takServerDetail.runtime.connectingSocket = null
                            takServerDetail.secureSocket = null
                        }
                    }
                    val currentAttempt = isCurrent(takServerDetail, attempt)
                    if (currentAttempt) {
                        //sendTakConnectionStatusEvent(CONNECTION_FAILURE)
                        takServerDetail.connectionStatus.set(TAK_SERVER_CONNECTION_FAILED)
                        takServerDetail.connectionStatusLiveData.postValue(TAK_SERVER_CONNECTION_FAILED_STRING)
                        publishRegistrationResult(
                            takServerDetail, attempt, TakServerRegistration.Status.FAILED,
                            TakConnectionFailure.from(TakEnrollmentStage.CONNECTION, e)
                        )
                        setIsAnyNetworkConnected()
                        setIsAnyNetworkConnecting()
                        Log.e(TAG, "ERROR in connectTAKServer: ${e.message}", e)
                        if (newServerAddedViaUi) {
                            newServerAddedViaUi = false
                            newServerAddedViaUiConnectionStatusLiveData.postValue(TAK_SERVER_CONNECTION_FAILED_STRING)
                        } else {
                            getClientCertificateAndConnect(takServerDetail)
                        }
                    } else {
                        Log.d(TAG, "Ignoring stale TAK connection failure for ${takServerDetail.address}")
                    }
                }
            } else {
                // TODO: do we need to check that it is also NOT TAK_SERVER_CONNECTED_COT_PORT
                if (takServerDetail.connectionStatus.get() != TAK_SERVER_OBTAINING_CLIENT_CERT) {
                    Log.d(TAG, "Going to call getClientCertificateAndConnect for takServer($takServer)")
                    if (!newServerAddedViaUi) {
                        getClientCertificateAndConnect(takServerDetail)
                        newServerAddedViaUi = false
                        newServerAddedViaUiConnectionStatusLiveData.postValue(TAK_SERVER_CONNECTION_FAILED_STRING)
                    }
                }
            }
        }

        fun setIsAnyNetworkConnected() {
            var anyTakConnected = false
            for (takServerDetail in takServerDetails) {
                if (takServerDetail.isEnabled && (takServerDetail.connectionStatus.get() == TAK_SERVER_CONNECTED_COT_PORT)) {
                    anyTakConnected = true
                    break
                }
            }
            //Log.d(TAG, "setIsAnyNetworkConnected: $anyTakConnected")
            isAnyTakConnected = anyTakConnected
            isAnyTakConnectedLiveData.postValue(isAnyTakConnected)
            if (anyTakConnected) {
                dataRepository.setEndpointConnected(DataRepository.Endpoint.TAK_SERVER)
            } else {
                dataRepository.setEndpointDisconnected(DataRepository.Endpoint.TAK_SERVER)
            }
        }

        fun sendData(takServerDetail: TakServerDetail, msg: String, isNotPli: Boolean, currTime: Long) {
            //Log.d(TAG, "sendData called")
            val socket: SSLSocket
            val attempt: ConnectionAttempt
            synchronized(takServerDetail.runtime.lock) {
                socket = takServerDetail.secureSocket ?: return
                attempt = takServerDetail.runtime.currentAttempt ?: return
                if (takServerDetail.runtime.generation != attempt.generation ||
                    socket.isClosed || !socket.isConnected) return
            }
            connectionScope.launch {
                try {
                    val outputStream = socket.getOutputStream()
                    outputStream.write(msg.toByteArray(charset("UTF-8")))
                    outputStream.flush()
                    if (!isNotPli) {
                        Log.i(TAG, "Outbound PLI write succeeded to ${takServerDetail.address}")
                    }
                } catch (e: IOException) {
                    handleSocketFailure(takServerDetail, attempt, socket, e)
                } catch (e: java.lang.Exception) {
                    handleSocketFailure(takServerDetail, attempt, socket, e)
                }
            }
        }

        private fun handleSocketFailure(
            detail: TakServerDetail,
            attempt: ConnectionAttempt,
            socket: SSLSocket,
            error: Exception
        ) {
            Log.e(TAG, "Error sending COT message to server(${detail.address}): ${error.message}", error)
            var shouldReconnect = false
            synchronized(detail.runtime.lock) {
                if (detail.runtime.currentAttempt === attempt &&
                    detail.runtime.generation == attempt.generation &&
                    detail.secureSocket === socket) {
                    detail.secureSocket = null
                    detail.connectionStatus.set(TAK_SERVER_DISCONNECTED)
                    detail.connectionStatusLiveData.postValue(TAK_SERVER_DISCONNECTED_STRING)
                    shouldReconnect = !serviceStopping && detail.isEnabled && isNetworkConnected
                }
            }
            runCatching { socket.close() }
            if (shouldReconnect) getClientCertificateAndConnect(detail)
        }

        private fun startCotReader(
            takServerDetail: TakServerDetail,
            socket: SSLSocket,
            attempt: ConnectionAttempt
        ) {
            val lastRxMs = AtomicLong(System.currentTimeMillis())
            val keepaliveJob = startKeepaliveWatchdog(takServerDetail, socket, attempt, lastRxMs)
            // start a background COT‐reader on the same socket
            val job = connectionScope.launch {
                try {
                    BufferedReader(InputStreamReader(socket.inputStream)).use { reader ->
                        val framer = CotStreamFramer { reason ->
                            Log.w(
                                TAG,
                                "Discarding CoT stream data reason=$reason serverId=${takServerDetail.serverId}"
                            )
                        }

                        while (true) {
                            val line = try {
                                reader.readLine()
                            } catch (e: SocketTimeoutException) {
                                // Timeout just re-checks enabled state and cancellation.
                                continue
                            } ?: break
                            lastRxMs.set(System.currentTimeMillis())

                            for (rawXml in framer.append(line)) {
                                val envelope = TakServerCotEnvelope(
                                    sourceServerId = takServerDetail.serverId,
                                    sourceServerAddress = takServerDetail.address,
                                    receivedAt = System.currentTimeMillis(),
                                    rawXml = rawXml,
                                    sourceSessionId = attempt.sessionId
                                )
                                val diagnostics = CotMessageParser.ingressDiagnostics(rawXml)
                                val activeChannels = dataRepository.takChannels.value
                                    .firstOrNull { it.serverId == takServerDetail.serverId }
                                    ?.channels
                                    .orEmpty()
                                    .filter { it.active }
                                Log.d(
                                    TAG,
                                    "Inbound CoT received serverId=${takServerDetail.serverId} " +
                                        "serverName=${takServerDetail.name} sourceAddress=${takServerDetail.address} " +
                                        "uid=${diagnostics.uid} type=${diagnostics.type} " +
                                        "eventTime=${diagnostics.eventTime} receivedAt=${envelope.receivedAt} " +
                                        "team=${diagnostics.teamName} role=${diagnostics.teamRole} " +
                                        "flowTags=${diagnostics.flowTagNames} " +
                                        "explicitChannelStamps=${diagnostics.explicitChannelStamps} " +
                                        "activeChannelsAtReceipt=${activeChannels.diagnosticSummary()} " +
                                        "channelOwnership=${if (diagnostics.explicitChannelStamps.isEmpty()) "unavailable" else "explicit-stamp-present"} " +
                                        "payloadSha256Prefix=${cotPayloadFingerprint(rawXml)} " +
                                        "payloadBytes=${rawXml.toByteArray(Charsets.UTF_8).size}"
                                )
                                val result = cotIngestor.ingest(envelope)
                                Log.d(
                                    TAG,
                                    "Inbound CoT routed serverId=${takServerDetail.serverId} uid=${diagnostics.uid} " +
                                        "type=${diagnostics.type} result=$result"
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "COT listener loop failed", e)
                } finally {
                    keepaliveJob?.cancel()
                    runCatching { socket.close() }
                    var shouldReconnect = false
                    synchronized(takServerDetail.runtime.lock) {
                        if (takServerDetail.runtime.currentAttempt === attempt &&
                            takServerDetail.runtime.generation == attempt.generation &&
                            takServerDetail.secureSocket === socket &&
                            !takServerDetail.runtime.terminated &&
                            takServerDetail.isEnabled &&
                            isNetworkConnected &&
                            !serviceStopping) {
                            takServerDetail.secureSocket = null
                            takServerDetail.connectionStatus.set(TAK_SERVER_DISCONNECTED)
                            takServerDetail.connectionStatusLiveData.postValue(TAK_SERVER_DISCONNECTED_STRING)
                            shouldReconnect = true
                        }
                        if (takServerDetail.runtime.currentAttempt === attempt) {
                            takServerDetail.runtime.readerJob = null
                        }
                    }
                    if (shouldReconnect) getClientCertificateAndConnect(takServerDetail)
                }
            }
            synchronized(takServerDetail.runtime.lock) {
                if (takServerDetail.runtime.currentAttempt === attempt) {
                    takServerDetail.runtime.readerJob = job
                } else {
                    job.cancel()
                    keepaliveJob?.cancel()
                }
            }
        }

        private fun startKeepaliveWatchdog(
            takServerDetail: TakServerDetail,
            socket: SSLSocket,
            attempt: ConnectionAttempt,
            lastRxMs: AtomicLong
        ): Job? {
            val keepalive = TakServerKeepalive(TakServerKeepalive.configuredSilenceSeconds(context))
            if (!keepalive.enabled) {
                Log.i(TAG, "TAK keepalive disabled by managed config serverId=${takServerDetail.serverId}")
                return null
            }
            return connectionScope.launch {
                var lastPingMs = 0L
                while (isActive) {
                    delay(TakServerKeepalive.CHECK_INTERVAL_MS)
                    if (!isCurrent(takServerDetail, attempt) || socket.isClosed) break
                    val now = System.currentTimeMillis()
                    when (keepalive.next(now, lastRxMs.get(), lastPingMs)) {
                        TakServerKeepalive.Action.PING -> {
                            lastPingMs = now
                            Log.d(TAG, "TAK keepalive ping serverId=${takServerDetail.serverId} " +
                                "silenceMs=${now - lastRxMs.get()}")
                            sendData(takServerDetail, TakServerKeepalive.pingCot(MainActivity.device_uid, now), true, now)
                        }
                        TakServerKeepalive.Action.RECONNECT -> {
                            Log.w(TAG, "TAK keepalive timeout serverId=${takServerDetail.serverId} " +
                                "silenceMs=${now - lastRxMs.get()}; resetting connection")
                            // Closing unblocks the reader, whose finally block schedules the reconnect.
                            runCatching { socket.close() }
                            break
                        }
                        TakServerKeepalive.Action.NONE -> Unit
                    }
                }
            }
        }
        fun replaceQuotedStringAfterSubstring(input: String, substring: String, replacement: String): String {
            val regex = Regex("$substring='(.*?)'")
            return input.replace(regex, "$substring='$replacement'")
        }

        fun getQuotedStringAfterSubstring(text: String, substring: String): String? {
            val startIndex = text.indexOf("$substring='")
            if (startIndex == -1) {
                return null
            }

            val valueStartIndex = startIndex + "$substring='".length
            val endIndex = text.indexOf("'", valueStartIndex)
            if (endIndex == -1) {
                return null
            }

            return text.substring(valueStartIndex, endIndex)
        }

        fun sendEsriData(msg: String) {
            val client = OkHttpClient()
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val body = RequestBody.create(mediaType, msg)
            val request = Request.Builder()
                .url(esriEndpoint)
                .post(body)
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(
                            TAG,
                            "ERROR sendEsriData returned unexpected HTTP code: ${response.code} w/ body: ${response.body?.string()} and response: $response"
                        )
                    } else {
                        Log.d(TAG, "sendEsriData Response Code: ${response.code} w/ Body: ${response.body?.string()}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ERROR in sendEsriData: ${e.message}", e)
            }
        }

        fun sendTabletCommandData(msg: String) {
            if (tabletCommandEndpoint.isEmpty() || tabletCommandApikey.isEmpty()) {
                return
            }
            try {
                val client = OkHttpClient()
                val mediaType = "application/json".toMediaTypeOrNull()
                val body = RequestBody.create(mediaType, msg)
                val request = Request.Builder()
                .url(tabletCommandEndpoint)
                .method("POST", body)
                .addHeader("apikey", tabletCommandApikey)
                .addHeader("Content-Type", "application/json")
                .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "ERROR sendTabletCommandData returned unexpected HTTP code: ${response.code} w/ body: ${response.body?.string()} and response: $response")
                    }
                    //else {
                    //    Log.d(TAG, "sendTabletCommandData Response Code: ${response.code} w/ Body: ${response.body?.string()}")
                    //}
                }
            } catch (e: Exception) {
                Log.e(TAG, "ERROR in sendTabletCommandData: ${e.message}", e)
            }
        }

        fun sendDataTAKServers(msg: String, isNotPli: Boolean) {
            if (isLicenseValid() && isNetworkConnected) {
                val currTime = System.currentTimeMillis()
                for (takServerDetail in takServerDetails) {
                    if (TAK_SERVER_OBTAINING_CLIENT_CERT == takServerDetail.connectionStatus.get()) {
                        Log.i(TAG, "Connecting to TAK server ${takServerDetail.address}. Hence not sending this message: $msg")
                        continue
                    }
                    if (takServerDetail.isEnabled && !takServerDetail.duplicateEndpointSuppressed) {
                        if (isNotPli) {
                            // Send immediately
                            sendData(takServerDetail, msg, isNotPli, currTime)
                            // TODO: We need to add for ESRI and TabletCommand
                        } else {
                            sendData(takServerDetail, msg, isNotPli, currTime)
                            if (enableEsriEndpoint && !esriEndpoint.isNullOrEmpty() && !esriGeojsonMsg.isNullOrEmpty()) {
                                sendEsriData(esriGeojsonMsg)
                            }
                            if (enableTabletCommandEndpoint && !tabletCommandEndpoint.isNullOrEmpty() && !tabletCommandApikey.isNullOrEmpty() && !tabletCommandJsonMsg.isNullOrEmpty()) {
                                sendTabletCommandData(tabletCommandJsonMsg)
                            }
                        }
                    }
                }
            } else {
                Log.d(TAG,"License Invalid or network NOT connected: NOT sending any data")
            }
        }
    }
}
