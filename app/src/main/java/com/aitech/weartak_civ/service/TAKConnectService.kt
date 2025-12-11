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
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.aitech.weartak_civ.NetworkUtils
import com.aitech.weartak_civ.data.DataRepository.TakServerItem
import com.aitech.weartak_civ.events.MessageEvent
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_ADDED
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_ADDED_VIA_UI
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_CHANGED
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_REMOVED
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_TOGGLED
import com.aitech.weartak_civ.events.NwConnEvent
import com.aitech.weartak_civ.events.NwConnEvent.Companion.WIFI
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivity
import com.aitech.weartak_civ.manager.NetworkManager
import com.aitech.weartak_civ.service.CotDispatchService.Companion.esriGeojsonMsg
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import java.security.*
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import javax.xml.parsers.DocumentBuilderFactory
import com.aitech.weartak_civ.Utils.CotUtils
import com.aitech.weartak_civ.Utils.CotUtils.Bloodhound.parseMarkerCotOrNull
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.events.TakChatEvent
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableEsriEndpoint
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableTabletCommandEndpoint
import com.aitech.weartak_civ.presentation.MainActivity.Constants.esriEndpoint
import com.aitech.weartak_civ.presentation.MainActivity.Constants.tabletCommandApikey
import com.aitech.weartak_civ.presentation.MainActivity.Constants.tabletCommandEndpoint
import com.aitech.weartak_civ.service.CotDispatchService.Companion.tabletCommandJsonMsg
import com.aitech.weartak_civ.presentation.requestStatusIconsComplicationUpdate
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import java.net.SocketTimeoutException
import javax.net.ssl.TrustManagerFactory

class TAKConnectService: Service() {
    var networkManager: NetworkManager? = null

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
    )

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "TAKConnectService onCreate()")
        isRunning.set(true)
        startForegroundServiceCompat(
            notificationId = TAK_CONNECT_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )

        setFromDatastore()
        startMulticastCollector()
        collectSocketEnablement()

        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }

    }

    private fun startMulticastCollector() {
        CoroutineScope(Dispatchers.IO).launch {
            NetworkManager.getInstance().wifiNetwork.collect { net ->
                if (net != null) NetworkUtils.MulticastPublisher.start(net) else NetworkUtils.MulticastPublisher.stop()
            }
        }
    }

    fun setFromDatastore() {
        runBlocking {
            val takServers : List<TakServerItem> = dataRepository.getTakServerList.first()
            takServerDetails = mutableListOf()
            for (takServer in takServers) {
                takServerDetails.add(TakServerDetail(takServer.isEnabled, takServer.name, takServer.address.trim(), takServer.port,
                    takServer.isP12Cert, takServer.username, takServer.password,
                    takServer.p12Cert, takServer.p12CertPassword, numConnAttempts = 0))
                Log.d(TAG, "setFromDatastore takServer(${takServer.name}) isEnabled(${takServer.isEnabled}): address(${takServer.address.trim()}), port(${takServer.port}), isP12Cert(${takServer.isP12Cert}), un(${takServer.username}), paswd(${takServer.password}),")
                Log.d(TAG, "                 p12Password(${takServer.p12CertPassword}), p12Cert(${takServer.p12Cert})")
            }
            enableEsriEndpoint = dataRepository.getEnableEsriEndpoint.first()
            esriEndpoint = dataRepository.getEsriEndpoint.first()

        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d(TAG, "TAKConnectService onStartCommand()")
        networkManager = NetworkManager.getInstance()
        isNetworkConnected = networkManager?.isNetworkConnected() ?: false
        isWifiConnected = networkManager?.isWifiConnected() ?: false
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
        super.onDestroy()
        Log.d(TAG, "TAKConnectService onDestroy()")
        isRunning.set(false)
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        networkManager = null
        closeTakServerSockets()
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun resetTakServerConnectionDetails() {
        isAnyTakConnected = false; isAnyTakConnectedLiveData.postValue(false)
        isAnyTakConnecting = false; isAnyTakConnectingLiveData.postValue(false)
        requestStatusIconsComplicationUpdate(context)
        for (takServerDetail in takServerDetails) {
            takServerDetail.numConnAttempts = 0
            takServerDetail.secureSocket = null
            takServerDetail.connectionStatus.set(TAK_SERVER_DISCONNECTED)
            takServerDetail.connectionStatusLiveData.postValue(TAK_SERVER_DISCONNECTED_STRING)
        }
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
        CoroutineScope(Dispatchers.IO).launch {
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
            val takServers: List<TakServerItem> = dataRepository.getTakServerList.first()
            if (takServers.size - 1 != index) {
                // We could do nothing here but if there is a bug we are trying to take care of it
                Log.w(TAG, "SERVER_ADDED index($index) != takServers.size(${takServers.size})-1")
            }
            val takServer = takServers.last()
            val takServerDetail = TakServerDetail(takServer.isEnabled, takServer.name, takServer.address.trim(), takServer.port,
                takServer.isP12Cert, takServer.username, takServer.password, takServer.p12Cert, takServer.p12CertPassword)
            if (takServerDetails.size == index) {
                Log.d(TAG, "Adding server at index $index")
                takServerDetails.add(takServerDetail)
            } else {
                Log.d(TAG, "Updating server at index $index")
                takServerDetails.set(index, takServerDetail)
            }
            // Delete server name file (if it exists)
            Companion.deleteFile("${takServerDetail.address}.jks")
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
            val takServers: List<TakServerItem> = dataRepository.getTakServerList.first()
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
        }
        if (serverConnDetailsChanged) {
            Log.d(TAG, "serverConnDetailsChanged $serverConnDetailsChanged")
            // Delete old server name file
            Companion.deleteFile("$oldAddress.jks")
            val newTakServerDetail = takServerDetails.get(index)
            // Delete new server name file (if it exists)
            val newAddress = newTakServerDetail.address
            if (oldAddress != newAddress) {
                Companion.deleteFile("$newAddress.jks")
            }
            newTakServerDetail.secureSocket?.close()
            newTakServerDetail.connectionStatus.set(TAK_SERVER_DISCONNECTED)
            newTakServerDetail.connectionStatusLiveData.postValue(TAK_SERVER_DISCONNECTED_STRING)
            if (isNetworkConnected) {
                setIsAnyNetworkConnected()
                setIsAnyNetworkConnecting()
                if (newTakServerDetail.isEnabled) {
                    getClientCertificateAndConnect(newTakServerDetail)
                }
            } else {
                Log.w(TAG, "Network is NOT connected. Hence NOT trying to connect to TAK server")
                isAnyTakConnected = false; isAnyTakConnectedLiveData.postValue(false)
                isAnyTakConnecting = false; isAnyTakConnectingLiveData.postValue(false)
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
            if ((TAK_SERVER_CONNECTED_COT_PORT == takServerDetail.connectionStatus.get()) && isNetworkConnected) {
                if ((null != takServerDetail.secureSocket) && ((takServerDetail.secureSocket?.isClosed ?: true) == false)) {
                    Log.d(TAG, "Closing socket")
                    takServerDetail.secureSocket?.close()
                }
            }
            takServerDetail.secureSocket = null
            takServerDetail.isEnabled = false
            setIsAnyNetworkConnected()
            setIsAnyNetworkConnecting()
        } else {
            takServerDetail.secureSocket = null
            takServerDetail.isEnabled = true
            if (isNetworkConnected) {
                setIsAnyNetworkConnected()
                setIsAnyNetworkConnecting()
                getClientCertificateAndConnect(takServerDetail)
            } else {
                Log.w(TAG, "Network is NOT connected. Hence NOT trying to connect to TAK server")
                isAnyTakConnected = false; isAnyTakConnectedLiveData.postValue(false)
                isAnyTakConnecting = false; isAnyTakConnectingLiveData.postValue(false)
                requestStatusIconsComplicationUpdate(context)

            }
        }
    }

    private fun onServerRemoved(index: Int) {
        val takServerDetail = takServerDetails.get(index)
        Log.d(TAG, "Received SERVER_REMOVED message for index($index): ${takServerDetail.address}")
        takServerDetail.numConnAttempts = 0
        if (takServerDetail.isEnabled) {
            if ((TAK_SERVER_CONNECTED_COT_PORT == takServerDetail.connectionStatus.get()) && isNetworkConnected) {
                if ((null != takServerDetail.secureSocket) && ((takServerDetail.secureSocket?.isClosed ?: true) == false)) {
                    Log.d(TAG, "Closing socket")
                    takServerDetail.secureSocket?.close()
                }
            }
            takServerDetail.secureSocket = null
            takServerDetail.connectionStatus.set(TAK_SERVER_DISCONNECTED)
            takServerDetail.connectionStatusLiveData.postValue(TAK_SERVER_DISCONNECTED_STRING)
            setIsAnyNetworkConnected()
            setIsAnyNetworkConnecting()
        }
        takServerDetails.removeAt(index)
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

    private fun collectSocketEnablement() {
        scope.launch {
            dataRepository.enableTakSockets
                .filterNotNull()
                .collect { enable ->
                    if (enable) {
                        Log.v(TAG, "Collected socket ENABLED!")
                        connectToTakServers()
                    } else {
                        Log.v(TAG, "Collected socket DISABLED!")
                        closeTakServerSockets()
                    }
            }
        }
    }

    fun closeTakServerSockets() {
        for (takServer in takServerDetails) {
            if (takServer.isEnabled) {
                val secureSocket = takServer.secureSocket
                if ((null != secureSocket) && ((secureSocket?.isClosed ?: true) == false)) {
                    // TODO: check logic above
                    runBlocking( Dispatchers.IO) {
                        secureSocket?.close()
                    }
                    takServer.secureSocket = null
                }
            }
        }
    }

    fun connectToTakServers() {
        isAnyTakConnected = false; isAnyTakConnectedLiveData.postValue(isAnyTakConnected)
        isAnyTakConnecting = false; isAnyTakConnectingLiveData.postValue(isAnyTakConnecting)
        requestStatusIconsComplicationUpdate(context)

        for (takServer in takServerDetails) {
            if (takServer.isEnabled) {
                getClientCertificateAndConnect(takServer)
            }
        }
    }

    companion object {
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

        val isRunning : AtomicBoolean = AtomicBoolean(false)
        private val NUM_CONN_ATTEMPS_AFTER_FAILURE = 3
        private val TAG = "WearTAK-" + TAKConnectService::class.java.simpleName
        const private val TAK_CONNECT_SERVICE_NOTIFICATION_ID = 101
        const private val KEYSTORE_PASS = "atakatak"
        const private val KEY_ALIAS = "myKeyAlias"
        var isNetworkConnected = false
        var isWifiConnected = false
        var isAnyTakConnected : Boolean = false // Overall flag indicating that atleast one of the TAK servers is connected. This includes getting client cert and connecting to COT message port
        val isAnyTakConnectedLiveData = MutableLiveData<Boolean>(false)
        val context: Context = mainActivity.applicationContext

        var isAnyTakConnecting : Boolean = false // Overall flag indicating if connection is being made to any TAK server
        val isAnyTakConnectingLiveData = MutableLiveData<Boolean>(false)

        var takServerDetails : MutableList<TakServerDetail> = emptyList<TakServerDetail>().toMutableList()

        var newServerAddedViaUi = false
        val newServerAddedViaUiConnectionStatusLiveData: MutableLiveData<String> = MutableLiveData<String>(TAK_SERVER_CONNECTING_STRING)

        private val bhRepo by lazy {
            try {
                (context as WearTakApplication).bhRepo
            } catch (_: Throwable) {
                BloodhoundRepository(context)
            }
        }

        private val TAK_CONNECT_SERVICE = TAKConnectService()
        public fun getInstance() : TAKConnectService {
            return TAK_CONNECT_SERVICE
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
                var subjectStr = "CN=$cnName"
                if (nameValueList.size == 0) {
                    Log.w(TAG, "generateBase64CSR: size of nameValueList is 0")
                } else {
                    for (nameValueEntry in nameValueList) {
                        subjectStr = "$subjectStr, ${nameValueEntry.name}=${nameValueEntry.value}"
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
            val takServer = takServerDetail.address
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
                        MainActivity.mainActivity.openFileOutput("$takServer.jks", Context.MODE_PRIVATE).use { fos ->
                            takServerDetail.keyStore.store(fos, keystorePassword)
                            Log.i(TAG, "Keystore stored successfully in $takServer.jks")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Not able to store keystore file as $takServer.jks. Error: ${e.message}")
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
            val md = MessageDigest.getInstance("SHA-256").digest(bytes)
            // Android Base64
            return android.util.Base64.encodeToString(md, android.util.Base64.NO_WRAP)
            // If not on Android, use:
            // return java.util.Base64.getEncoder().encodeToString(md)
        }

        fun getClientCertificateAndConnect(takServerDetail: TakServerDetail) {
            Log.d(TAG, "getClientCertificateAndConnect called for server(${takServerDetail.address}) w/ numConnAttempts(${takServerDetail.numConnAttempts})")
            takServerDetail.connectionStatus.set(TAK_SERVER_DISCONNECTED)
            takServerDetail.connectionStatusLiveData.postValue(TAK_SERVER_DISCONNECTED_STRING)
            if (isNetworkConnected) {
                if (takServerDetail.numConnAttempts > NUM_CONN_ATTEMPS_AFTER_FAILURE) {
                    return
                }
                takServerDetail.numConnAttempts++
                CoroutineScope(Dispatchers.IO).launch {
                    val secureSocket = takServerDetail.secureSocket
                    if ((null != secureSocket) && ((secureSocket?.isClosed ?: true) == false)) {
                        // TODO: check logic above
                        Log.d(TAG, "Closing socket for takServer ${takServerDetail.address}")
                        secureSocket?.close()
                    }
                    isAnyTakConnecting = true; isAnyTakConnectingLiveData.postValue(true)
                    takServerDetail.connectionStatus.set(TAK_SERVER_OBTAINING_CLIENT_CERT)
                    takServerDetail.connectionStatusLiveData.postValue(TAK_SERVER_CONNECTING_STRING)
                    requestStatusIconsComplicationUpdate(context)


                    if (takServerDetail.isP12Cert) {
                        Log.d(TAG, "isP12Cert true")
                        //takServerDetail.keyStore = KeyStore.getInstance("PKCS12")
                        val inputStream = base64ToInputStream(takServerDetail.p12Cert)
                        if (null != inputStream) {
                            try {
                                takServerDetail.keyStore.load(inputStream, takServerDetail.p12CertPassword?.toCharArray() ?: "".toCharArray())
                                takServerDetail.connectionStatus.set(TAK_SERVER_OBTAINED_CLIENT_CERT)
                                takServerDetail.connectionStatusLiveData.postValue(TAK_SERVER_CONNECTING_STRING)
                                connectTAKServer(takServerDetail)
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
                        if (loadKeyStore(takServerDetail.keyStore, "${takServerDetail.address}.jks", KEYSTORE_PASS)) {
                            Log.d(TAG, "Successfully loaded keyStore for ${takServerDetail.address}")
                            takServerDetail.connectionStatus.set(TAK_SERVER_OBTAINED_CLIENT_CERT)
                            takServerDetail.connectionStatusLiveData.postValue(TAK_SERVER_CONNECTING_STRING)
                            connectTAKServer(takServerDetail)
                        } else {
                            deleteFile("${takServerDetail.address}.jks")
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
                                        connectTAKServer(takServerDetail)
                                    } else {
                                        deleteFile("${takServerDetail.address}.jks")
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
        
        fun connectTAKServer(takServerDetail: TakServerDetail) {
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
                    Log.d(TAG, "root=${root?.toString() ?: "null"}")
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
                    takServerDetail.secureSocket = socketFactory.createSocket(takServer, port) as SSLSocket
                    Log.d(TAG, "Starting secureSocket handshake")
                    takServerDetail.secureSocket?.startHandshake()
                    isAnyTakConnected = true
                    isAnyTakConnectedLiveData.postValue(isAnyTakConnected)
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
                    // start a background COT‐reader on the same socket
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val reader = BufferedReader(InputStreamReader(takServerDetail.secureSocket!!.inputStream))
                            val buffer = StringBuilder()

                            while (true) {
                                // Gate: do not read unless chat is enabled
                                if (!dataRepository.getEnableChatService.first()) {
                                    delay(750)    // light sleep; re-check flag soon
                                    continue
                                }

                                val line = try {
                                    reader.readLine()
                                } catch (e: SocketTimeoutException) {
                                    // timeout just to re-check enabled/cancellation
                                    continue
                                } ?: break

                                buffer.append(line)

                                // parse one <event> ... </event>
                                val bufStr = buffer.toString()
                                val endIdx = bufStr.indexOf("</event>")
                                if (endIdx >= 0) {
                                    val rawXml = bufStr.substring(0, endIdx + "</event>".length)
                                    buffer.delete(0, endIdx + "</event>".length)

                                    if (rawXml.contains("chat", ignoreCase = true)) Log.d(TAG, "Received chat:\n$rawXml")
                                    if (CotUtils.isChatMessage(rawXml)
                                        && CotUtils.isChatForMe(rawXml, MainActivity.device_uid)) {
                                        Log.d(TAG, "Received Chat for me:\n$rawXml")
                                        EventBus.getDefault().post(TakChatEvent(rawXml))
                                    }
                                    val markerCotEvent: CotUtils.MarkerCotEvent? = parseMarkerCotOrNull(rawXml)

                                    if (markerCotEvent != null) {
                                        Log.i(TAG, "We read a marker message!: \n $markerCotEvent")
//                                        Log.i(TAG, "XML XML XML: $rawXml")
                                        val mapItem = CotUtils.MarkerMapItem(markerCotEvent)
                                        val bhEvent = BloodhoundRepository.BloodhoundEvent(
                                            mapItem = mapItem,
                                            server = takServerDetail.address,
                                            timeIssued = mapItem.link?.productionTime,
                                            timeCompleted = null
                                        )
                                        bhRepo.updateBloodhoundEvent(bhEvent)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "COT listener loop failed", e)
                        }
                    }

                } catch (e: Exception) {
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
        }

        fun sendData(takServerDetail: TakServerDetail, msg: String, isNotPli: Boolean, currTime: Long) {
            //Log.d(TAG, "sendData called")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    if ((null != takServerDetail.secureSocket) && (takServerDetail.secureSocket?.isConnected == true)) {
                        val outputStream = takServerDetail.secureSocket!!.getOutputStream()
                        outputStream.write(msg.toByteArray(charset("UTF-8")))
                        outputStream.flush()
                        //Log.d(TAG, "Sent data (notPli=$isNotPli) to ${takServerDetail.address}: $msg")
                    } else {
                        if (takServerDetail.numConnAttempts <= NUM_CONN_ATTEMPS_AFTER_FAILURE) {
                            Log.i(TAG, "ERROR sendDataTAKServers to server(${takServerDetail.address}): secureSocket=${takServerDetail.secureSocket}; isConnected: ${takServerDetail.secureSocket?.isConnected ?: false}. Reconnecting....")
                            takServerDetail.secureSocket?.close()
                            takServerDetail.connectionStatus.set(TAK_SERVER_DISCONNECTED)
                            takServerDetail.connectionStatusLiveData.postValue(TAK_SERVER_DISCONNECTED_STRING)
                            setIsAnyNetworkConnected()
                            setIsAnyNetworkConnecting()
                            getClientCertificateAndConnect(takServerDetail)
                        }
                    }
                } catch (e: IOException) {
                    if (takServerDetail.numConnAttempts <= NUM_CONN_ATTEMPS_AFTER_FAILURE) {
                        Log.e(TAG, "Error sending COT message to server(${takServerDetail.address}): ${e.message}", e)
                        takServerDetail.secureSocket?.close()
                        takServerDetail.connectionStatus.set(TAK_SERVER_DISCONNECTED)
                        takServerDetail.connectionStatusLiveData.postValue(TAK_SERVER_DISCONNECTED_STRING)
                        setIsAnyNetworkConnected()
                        setIsAnyNetworkConnecting()
                        getClientCertificateAndConnect(takServerDetail)
                    }
                } catch (e: java.lang.Exception) {
                    if (takServerDetail.numConnAttempts <= NUM_CONN_ATTEMPS_AFTER_FAILURE) {
                        Log.e(TAG, "Error sending COT message to server(${takServerDetail.address}): ${e.message}", e)
                        takServerDetail.secureSocket?.close()
                        takServerDetail.connectionStatus.set(TAK_SERVER_DISCONNECTED)
                        takServerDetail.connectionStatusLiveData.postValue(TAK_SERVER_DISCONNECTED_STRING)
                        setIsAnyNetworkConnected()
                        setIsAnyNetworkConnecting()
                        getClientCertificateAndConnect(takServerDetail)
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
                    if (takServerDetail.isEnabled) {
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
