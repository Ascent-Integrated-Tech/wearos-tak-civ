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
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.aitech.weartak_civ.NetworkUtils
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
import com.aitech.weartak_civ.data.takserver.TakChannelState
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
import com.aitech.weartak_civ.events.NwConnEvent
import com.aitech.weartak_civ.events.NwConnEvent.Companion.WIFI
import com.aitech.weartak_civ.events.TakChatEvent
import com.aitech.weartak_civ.manager.NetworkManager
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
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
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
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringReader
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
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

class TAKConnectService: Service() {
    var networkManager: NetworkManager? = null

    class ConnectionAttempt(val generation: Long) {
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

    class SignClientResponse {
        var signedCert: String? = null
        var ca0: String? = null
        var ca1: String? = null // getters/setters not strictly needed for basic usage
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
        var channelTrustManager: X509TrustManager? = null,
        val channelMutex: Mutex = Mutex(),
        val runtime: ConnectionRuntime = ConnectionRuntime(),
        var duplicateEndpointSuppressed: Boolean = false,
    )

    override fun onCreate() {
        super.onCreate()
        serviceStopping = false
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
        scope.launch {
            combine(NetworkManager.getInstance().wifiNetwork,
                dataRepository.multicastEnabled,
                dataRepository.multicastAddress,
                dataRepository.multicastPort) { net, enable, address, port ->
                MulticastConfig(net, enable, address, port)
            }.distinctUntilChanged().collectLatest{ cfg ->
                val shouldRun = cfg.enable != null && cfg.enable && cfg.net != null &&
                        !cfg.address.isNullOrBlank() && cfg.port != null
                Log.i(TAG, "Multicast collector called: $cfg with shouldRun=$shouldRun")
                if (shouldRun) {
                    val started = NetworkUtils.MulticastPublisher.start(network = cfg.net, address = cfg.address, port = cfg.port)
                    if (started) {
                        dataRepository.setMulticastPublisherRunning(true)
                        dataRepository.setEndpointConnected(DataRepository.Endpoint.MULTICAST)
                    } else {
                        dataRepository.setMulticastPublisherRunning(false)
                        dataRepository.setEndpointDisconnected(DataRepository.Endpoint.MULTICAST)
                    }
                } else {
                    NetworkUtils.MulticastPublisher.stop()
                    dataRepository.setMulticastPublisherRunning(false)
                    dataRepository.setEndpointDisconnected(DataRepository.Endpoint.MULTICAST)
                }
            }
        }
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

    private suspend fun reloadProvisionedTakServers() {
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
                if (previous != null) {
                    invalidateConnection(previous, terminate = true)
                    if (previous.hasChangedCredentialMaterial(stored)) {
                        applicationContext.deleteFile(certificateCacheFileName(previous))
                        if (TakServerEndpointIdentity.key(previous.address, previous.port) !=
                            TakServerEndpointIdentity.key(stored)) {
                            applicationContext.deleteFile(
                                certificateCacheFileName(stored.address, stored.port)
                            )
                        }
                    }
                }
                replacedCount++
                stored.toRuntimeDetail()
            }
        }

        previousById
            .filterKeys { it !in retainedIds }
            .values
            .forEach { removed -> invalidateConnection(removed, terminate = true) }

        takServerDetails = refreshed.toMutableList()
        takServerDetailsById.clear()
        refreshed.forEach { detail -> takServerDetailsById[detail.serverId] = detail }
        refreshDuplicateEndpointSuppression()
        setIsAnyNetworkConnected()
        setIsAnyNetworkConnecting()

        Log.i(
            TAG,
            "Provisioned TAK configuration refreshed servers=${refreshed.size} replaced=$replacedCount"
        )

        val companionConnected = isCompanionAppConnected || dataRepository.isCompanionConnected.value
        if (!companionConnected && isNetworkConnected) {
            refreshed
                .filter { detail ->
                    detail.isEnabled &&
                        !detail.duplicateEndpointSuppressed &&
                        detail.connectionStatus.get() == TAK_SERVER_DISCONNECTED
                }
                .forEach(::getClientCertificateAndConnect)
        }
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
        serviceStopping = true
        isRunning.set(false)
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        closeTakServerSockets()
        NetworkUtils.MulticastPublisher.stop()
        scope.cancel()
        connectionScope.cancel()
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
        private val connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val channelTraceSequence = AtomicInteger(0)

        var takServerDetails : MutableList<TakServerDetail> = emptyList<TakServerDetail>().toMutableList()
        private val takServerDetailsById = ConcurrentHashMap<String, TakServerDetail>()

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
            )
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
                deviceCallsign = { MainActivity.callsign }
            )
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

        fun postCsrAndParse(takServerDetail: TakServerDetail, base64CSR: String): SignClientResponse? {
            val takServer = takServerDetail.address
            val username = takServerDetail.username
            val password = takServerDetail.password

            var result: SignClientResponse? = null

            val csrResponse = postCsrAndGetResponse(takServer, username, password, base64CSR)
            if (null != csrResponse) {
                try {
                    val mapper = ObjectMapper()
                    result = mapper.readValue(csrResponse, SignClientResponse::class.java)
                    println("signedCert: " + result.signedCert)
                    println("ca0: " + result.ca0)
                    println("ca1: " + result.ca1)
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

        fun setKeystore(takServerDetail: TakServerDetail, signClientResponse: SignClientResponse): Boolean {
            val cacheFileName = certificateCacheFileName(takServerDetail)
            var retVal = true

            try {
                // Convert each base64 string to X509Certificate
                val signedCert = parseBase64ToX509(signClientResponse.signedCert ?: "")
                //val expiryDate: Date = signedCert.getNotAfter()
                val ca0Cert = parseBase64ToX509(signClientResponse.ca0 ?: "")
                val ca1Cert = parseBase64ToX509(signClientResponse.ca1 ?: "")
                Log.i(TAG, "signedCert expiry date: ${signedCert.getNotAfter()}")
                Log.v(TAG, "ca0Cert expiry date: ${ca0Cert.getNotAfter()}")
                Log.v(TAG, "ca1Cert expiry date: ${ca1Cert.getNotAfter()}")

                // Build the chain: leaf first, then CA(s).
                // NOTE: ca0cert is the intermediate CA cert and ca1Cert is the root CA cert. This is how the TAK server returns the CA certs
                val chain = arrayOf<Certificate>(signedCert, ca0Cert, ca1Cert)

                // Create an empty PKCS#12 keystore
                // Initialize with null parameters => empty store
                takServerDetail.keyStore.load(null, null)

                //p12.setCertificateEntry("signedCert", signedCert);
                // Store the CA certs as "trusted" entries
                takServerDetail.keyStore.setCertificateEntry("ca0", ca0Cert)
                takServerDetail.keyStore.setCertificateEntry("ca1", ca1Cert)

                // Keystore password to protect the .p12
                val keystorePassword = KEYSTORE_PASS.toCharArray()
                takServerDetail.keyStore.setKeyEntry(KEY_ALIAS, takServerDetail.privateKey, keystorePassword, chain)
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

        @Throws(java.lang.Exception::class)
        private fun parseBase64ToX509(base64Cert: String): X509Certificate {
            // Remove newlines if present
            val normalized = base64Cert.replace("\\s+".toRegex(), "")
            val derBytes = Base64.getDecoder().decode(normalized)

            val cf = CertificateFactory.getInstance("X.509")
            ByteArrayInputStream(derBytes).use { `in` ->
                return cf.generateCertificate(`in`) as X509Certificate
            }
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
                return
            }
            if (isCompanionAppConnected) {
                Log.w(TAG, "TAK reconnect suppressed (cert): Companion App is active")
                return
            }
            Log.d(TAG, "getClientCertificateAndConnect called for server(${takServerDetail.address}) w/ numConnAttempts(${takServerDetail.numConnAttempts})")
            takServerDetail.connectionStatus.set(TAK_SERVER_DISCONNECTED)
            takServerDetail.connectionStatusLiveData.postValue(TAK_SERVER_DISCONNECTED_STRING)
            if (isNetworkConnected) {
                if (takServerDetail.numConnAttempts > NUM_CONN_ATTEMPS_AFTER_FAILURE) {
                    return
                }
                val attempt = beginConnectionAttempt(takServerDetail) ?: return
                takServerDetail.numConnAttempts++
                val job = connectionScope.launch {
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
                            var signClientResponse: SignClientResponse? = null
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
                        }
                    }
                }
            } else {
                Log.d(TAG, "Network is NOT connected. Hence not trying to connect to TAK server")
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
                    // Reset counters for connection attempts
                    takServerDetail.numConnAttempts = 0
                    setIsAnyNetworkConnecting()
                    Log.d(TAG, "Successfully connected to $takServer on port $port")
                    if (newServerAddedViaUi) {
                        newServerAddedViaUi = false
                        newServerAddedViaUiConnectionStatusLiveData.postValue(TAK_SERVER_CONNECTED_STRING)
                    }
                    loadTakChannelsAfterConnect(takServerDetail)
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

                            for (rawXml in framer.append(line)) {
                                val envelope = TakServerCotEnvelope(
                                    sourceServerId = takServerDetail.serverId,
                                    sourceServerAddress = takServerDetail.address,
                                    receivedAt = System.currentTimeMillis(),
                                    rawXml = rawXml
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
