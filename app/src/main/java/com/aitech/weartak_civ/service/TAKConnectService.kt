/*
 * Copyright (C) 2024, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import com.aitech.weartak_civ.events.MessageEvent
import com.aitech.weartak_civ.events.MessageEvent.Companion.SERVER_DETAILS_CHANGED
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.events.TakServerConnEvent
import com.aitech.weartak_civ.events.TakServerConnEvent.Companion.CONNECTING
import com.aitech.weartak_civ.events.TakServerConnEvent.Companion.CONNECTION_FAILURE
import com.aitech.weartak_civ.events.TakServerConnEvent.Companion.CONNECTION_SUCCESS
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enableTAKConnectService
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
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.*
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager


class TAKConnectService: Service() {
    class SignClientResponse {
        var signedCert: String? = null
        var ca0: String? = null
        var ca1: String? = null // getters/setters not strictly needed for basic usage
    }


    override fun onCreate() {
        Log.d(TAG, "TAKConnectService onCreate")
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
        return super.onCreate()
    }

    fun setFromDatastore() {
        runBlocking {
            takServer = dataRepository.getAddress.first()
            username = dataRepository.getUsername.first()
            password = dataRepository.getPassword.first()
            try {
                port = dataRepository.getPort.first().toInt()
            } catch (e: Exception) {
                Log.w(TAG, "Port ($port) is not a number. Setting to default 8089", e)
                port = 8089
            }
            Log.d(TAG, "setFromDatastore takServer ($takServer), username ($username), password ($password), port ($port)")
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        startForegroundService()
        setFromDatastore()
        getClientCertificateAndConnect(takServer, username, password, port)
        return START_NOT_STICKY
    }

    private fun startForegroundService() {
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        } else {
            "TAK Connect Service"
        }

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
        val notification = notificationBuilder.setOngoing(true)
            .setSmallIcon(R.drawable.splash_icon)
            .setContentText("WearTAK-CIV TAK Connect service running")
            .setPriority(Context.BIND_WAIVE_PRIORITY)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        startForeground(TAK_CONNECT_SERVICE_NOTIFICATION_ID, notification)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(): String {
        val channelId = "TAK Connect Service"
        val channelName = "TAK Connect Background Service"
        val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
        chan.lightColor = Color.GRAY
        chan.importance = NotificationManager.IMPORTANCE_NONE
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service.createNotificationChannel(chan)
        return channelId
    }

    override fun onBind(intent: Intent): IBinder? {
        throw UnsupportedOperationException("Not yet implemented")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        CoroutineScope(Dispatchers.IO).launch {
            if ((null != secureSocket) && ((secureSocket?.isClosed ?: true) == false)) {
                // TODO: check logic above
                secureSocket?.close()
                secureSocket = null
            }
            Log.d(TAG, "TAK Connect Service Destroyed")
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public fun onMessageEvent(event: MessageEvent) {
        if (SERVER_DETAILS_CHANGED == event.message) {
            Log.d(TAG, "Received SERVER_DETAILS_CHANGED message")
            EventBus.getDefault().post(TakServerConnEvent(CONNECTING))
            numConnFailureEventSentAfterConnecting = 0
            setFromDatastore()
            getClientCertificateAndConnect(takServer, username, password, port)
        }
    }

    companion object {
        private val NUM_CONN_FAILURE_TOASTS = 2
        private val NUM_CONN_ATTEMPS_AFTER_FAILURE = 4
        private var numConnFailureEventSentAfterConnecting = 0
        private val TAG = "WearTAK-" + TAKConnectService::class.java.simpleName
        const private val TAK_CONNECT_SERVICE_NOTIFICATION_ID = 101
        private var secureSocket : SSLSocket? = null
        const private val TAK_CERT_PASSWORD = "atakatak"
        var isConnected : Boolean = false // Overall isConnected flag. This includes getting client cert and connecting to COT message port
        val isConnectedLiveData = MutableLiveData<Boolean>(false)
        val obtainingClientCert = AtomicBoolean(false)
        val obtainedClientCert  = AtomicBoolean(false)
        val isConnectingToCotMsgPort : AtomicBoolean = AtomicBoolean(false) // This is only for the process of connecting to COT message port

        var privateKey: PrivateKey? = null
        val keyStore : KeyStore = KeyStore.getInstance("PKCS12")

        var username : String = ""
        var password : String = ""
        var takServer : String = ""
        var port : Int = 8089
        val organization = "TAK"
        val organizationUnit = "TAK"

        private fun generateBase64CSR(org: String, orgUnit: String, cnName: String): String? {
            var base64CSR: String? = null
            try {
                // Add the BouncyCastle security provider
                Security.addProvider(BouncyCastleProvider())

                // Generate a KeyPair (RSA 4096).
                val keyPairGen = KeyPairGenerator.getInstance("RSA", "BC")
                keyPairGen.initialize(4096)
                val keyPair = keyPairGen.generateKeyPair()
                privateKey = keyPair.private

                // Build the X.500 Name (subject) for the CSR
                //    e.g., "C=US, ST=NY, L=New York, O=MyOrg, CN=example.com"
                // TAK Server expects exactly the 3 fields: O, OU and CN.
                // Anything more and less and getting singed cert fails
                // TODO: get O and OU using HTTP GET https://tak.test.ops.ascentitech.com:8446/Marti/api/tls/config
                // TODO: and parse <?xml version="1.0" encoding="UTF-8" standalone="yes"?><ns2:certificateConfig xmlns="http://bbn.com/marti/xml/config" xmlns:ns2="com.bbn.marti.config"><nameEntries><nameEntry name="O" value="TAK"/><nameEntry name="OU" value="TAK"/></nameEntries></ns2:certificateConfig>
                // TODO: Also send version information as separate POST message I think??
                val subject = X500Name("O=$org, OU=$orgUnit, CN=$cnName")

                // 4. Create the CSR builder
                val p10Builder = JcaPKCS10CertificationRequestBuilder(subject, keyPair.public)

                // (No challenge password set)

                // Create a ContentSigner for "SHA256withRSA" using the private key
                val csBuilder = JcaContentSignerBuilder("SHA256withRSA")
                //csBuilder.setProvider("BC")
                val signer = csBuilder.build(privateKey)

                // Build the PKCS#10 CSR
                val csr = p10Builder.build(signer)

                base64CSR = Base64.getEncoder().encodeToString(csr.encoded)
            } catch (e: Exception) {
                Log.d(TAG, "ERROR in generateBase64CSR: ${e.message}", e)
            }
            return base64CSR
        }

        // TODO: check for network connectivity
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
                        Log.e(TAG, "ERROR CSR response returned unexpected HTTP code: ${response.code} w/ body: ${response.body?.string()}"
                        )
                        return null
                    }
                    val responseBodyString = response.body?.string()
                    Log.d(TAG, "postCsrAndGetResponse: obtained response body $responseBodyString")
                    // Return the response body as string
                    return responseBodyString
                }
            } catch (e: Exception) {
                Log.e(TAG, "postCsrAndGetResponse ERROR: ${e.message}", e)
            }
            return null
        }

        fun postCsrAndParse(takServer: String, username: String, password: String, base64CSR: String): SignClientResponse? {
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

        fun setKeystore(signClientResponse: SignClientResponse): Boolean {
            var retVal = true
            try {
                // Convert each base64 string to X509Certificate
                val signedCert = parseBase64ToX509(signClientResponse.signedCert ?: "")
                val ca0Cert = parseBase64ToX509(signClientResponse.ca0 ?: "")
                val ca1Cert = parseBase64ToX509(signClientResponse.ca1 ?: "")

                // Build the chain: leaf first, then CA(s).
                // NOTE: ca0cert is the intermediate CA cert and ca1Cert is the root CA cert. This is how the TAK server returns the CA certs
                val chain = arrayOf<Certificate>(signedCert, ca0Cert, ca1Cert)

                // Create an empty PKCS#12 keystore
                // Initialize with null parameters => empty store
                keyStore.load(null, null)

                //p12.setCertificateEntry("signedCert", signedCert);
                // Store the CA certs as "trusted" entries
                keyStore.setCertificateEntry("ca0", ca0Cert)
                keyStore.setCertificateEntry("ca1", ca1Cert)

                // Keystore password to protect the .p12
                val keystorePassword = TAK_CERT_PASSWORD.toCharArray()
                keyStore.setKeyEntry("myKeyAlias", privateKey, keystorePassword, chain)

            } catch (e: java.lang.Exception) {
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

        private fun checkIssuerEqualsSubject(cert1: X509Certificate, cert2: X509Certificate): Boolean {
            return cert1.issuerX500Principal == cert2.subjectX500Principal
        }

        private fun sendConnectionFailureEvent() {
            if (numConnFailureEventSentAfterConnecting < NUM_CONN_FAILURE_TOASTS) {
                EventBus.getDefault().post(TakServerConnEvent(CONNECTION_FAILURE))
            }
        }

        fun getClientCertificateAndConnect(takServer: String, username: String, password: String, port: Int) {
            isConnected = false
            isConnectedLiveData.postValue(isConnected)
            //isConnecting.set(true)
            obtainedClientCert.set(false)
            obtainingClientCert.set(true)
            CoroutineScope(Dispatchers.IO).launch {
                if ((null != secureSocket) && ((secureSocket?.isClosed ?: true) == false)) {
                    // TODO: check logic above
                    Log.d(TAG, "Closing socket")
                    secureSocket?.close()
                }
                if (numConnFailureEventSentAfterConnecting < NUM_CONN_ATTEMPS_AFTER_FAILURE) {
                    val base64CSR = generateBase64CSR(
                        organization, organizationUnit,
                        username
                    )
                    var signClientResponse: SignClientResponse? = null
                    if (null != base64CSR) {
                        //Log.d(TAG, "base64CSR NOT null. Going to call postCsrAndParse")
                        signClientResponse =
                            postCsrAndParse(takServer, username, password, base64CSR)
                        if (null != signClientResponse) {
                            //Log.d(TAG, "signClientResponse NOT null. Going to call setKeystore")
                            if (setKeystore(signClientResponse)) {
                                Log.d(TAG, "setKeystore success: calling connectTAKServer")
                                obtainedClientCert.set(true)
                                obtainingClientCert.set(false)
                                connectTAKServer(takServer, username, password, port)
                            } else {
                                Log.e(TAG, "ERROR in setKeystore: Cannot connect to TAK Server")
                                obtainingClientCert.set(false)
                                sendConnectionFailureEvent()
                            }
                        } else {
                            Log.e(
                                TAG,
                                "ERROR postCsrAndParse returned NULL: Cannot connect to TAK Server"
                            )
                            obtainingClientCert.set(false)
                            sendConnectionFailureEvent()
                        }
                    } else {
                        Log.e(
                            TAG,
                            "ERROR generateBase64CSR returned NULL. Cannot connect to TAK Server"
                        )
                        obtainingClientCert.set(false)
                        sendConnectionFailureEvent()
                    }
                }
                numConnFailureEventSentAfterConnecting++
            }
        }

        fun connectTAKServer(takServer: String, username: String, password: String, port: Int) {
            isConnected = false
            isConnectedLiveData.postValue(isConnected)
            Log.d(TAG,"connectTAKServer called with obtainedClientCert = ${obtainedClientCert.get()}, obtainingClientCert = ${obtainedClientCert.get()} & isConnecting = ${isConnectingToCotMsgPort.get()}")
            if (obtainedClientCert.get()) {
                if (isConnectingToCotMsgPort.getAndSet(true) == false) {
                    try {
                        // Initialize the KeyManagerFactory with the client certificate
                        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                        keyManagerFactory.init(keyStore, TAK_CERT_PASSWORD.toCharArray())

                        // Initialize SSL Context with TrustManager
                        val sslContext = SSLContext.getInstance("TLS")
                        sslContext.init(
                            keyManagerFactory.keyManagers, arrayOf<TrustManager>(object :
                                X509TrustManager {
                                override fun checkClientTrusted(
                                    certs: Array<X509Certificate>,
                                    authType: String
                                ) {
                                }

                                override fun checkServerTrusted(
                                    certs: Array<X509Certificate>,
                                    authType: String
                                ) {
                                }

                                override fun getAcceptedIssuers(): Array<X509Certificate> {
                                    return emptyArray()
                                }
                            }),
                            null
                        )

                        // Create the socket and connect
                        val socketFactory = sslContext.socketFactory
                        secureSocket = socketFactory.createSocket(takServer, port) as SSLSocket
                        secureSocket?.startHandshake()
                        isConnected = true
                        isConnectedLiveData.postValue(isConnected)
                        EventBus.getDefault().post(TakServerConnEvent(CONNECTION_SUCCESS))
                        Log.d(TAG, "Successfully connected to $takServer on port $port")
                    } catch (e: Exception) {
                        isConnected = false
                        isConnectedLiveData.postValue(isConnected)
                        sendConnectionFailureEvent()
                        Log.e(TAG, "ERROR in connectTAKServer: ${e.message}", e)
                    }
                }
                isConnectingToCotMsgPort.set(false)
            } else {
                Log.d(TAG, "obtainingClientCert = ${obtainingClientCert.get()}")
                if (!obtainingClientCert.get()) {
                    Log.d(TAG, "Going to obtain client cert and then connect as obtainedClientCert and obtainingClientCert are false")
                    getClientCertificateAndConnect(takServer, username, password, port)
                }
            }
        }

        fun sendDataTAKServer(msg: String) {
            if (isConnectingToCotMsgPort.get()) {
                Log.i(TAG, "Connecting to TAK server. Hence not sending this message: $msg")
                return
            }
            CoroutineScope(Dispatchers.IO).launch {
                if (isLicenseValid() && enableTAKConnectService) {
                    // TODO: Remove in the next release. Keeping this for release 0.33
                    Log.d(TAG,"License Valid and enableTAKConnectService true. Sending data: $msg")
                    try {
                        if ((null != secureSocket) && (secureSocket?.isConnected == true)) {
                            val outputStream = secureSocket!!.getOutputStream()
                            outputStream.write(msg.toByteArray(charset("UTF-8")))
                            outputStream.flush()
                        } else {
                            Log.i(TAG, "ERROR sendDataTAKServer: secureSocket=$secureSocket; isConnected: ${secureSocket?.isConnected ?: false}. Reconnecting....")
                            secureSocket?.close()
                            connectTAKServer(takServer, username, password, port)
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "Error sending COT message: " + e.message, e)
                        secureSocket?.close()
                        connectTAKServer(takServer, username, password, port)
                    } catch (e: java.lang.Exception) {
                        Log.e(TAG, "Error sending COT message: " + e.message, e)
                        secureSocket?.close()
                        connectTAKServer(takServer, username, password, port)
                    }
                } else {
                    Log.d(TAG,"License Invalid or enableTAKConnectService false: NOT sending any data")
                }
            }
        }
    }
}
