/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.weartak.weartak

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import android.util.Log
import com.weartak.weartak.presentation.MainActivity
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import android.net.Network
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket

object NetworkUtils {
    private const val TAG = "WearTAK-NetworkUtils"

    // tak.X.ops.ascentitech.com is not in default truststore, so we need to trust it. To do this, we need to obtain the trusted cert from the keystore once we have connected to the tak server.
    private fun createCustomOkHttpClient(keyStore: KeyStore): OkHttpClient {
        // Initialize a TrustManagerFactory with the custom Keystore
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(keyStore)
        val trustManagers = trustManagerFactory.trustManagers
        val trustManager = trustManagers.first { it is X509TrustManager } as X509TrustManager

        val sslContext = SSLContext.getInstance("TLSv1.3")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
        val sslSocketFactory = sslContext.socketFactory

        return OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustManager)
            .build()
    }

    suspend fun fetchJsonFromServer(url: String, keyStore: KeyStore): String? {
        return withContext(Dispatchers.IO) {
            try {
                val client = createCustomOkHttpClient(keyStore)
                val xHardwareId = MainActivity.device_uid
                val request = Request.Builder()
                    .url(url)
                    .addHeader("X-Hardware-Id", xHardwareId)
                    .addHeader("X-API-KEY", "yFaPWEcH3Q4HtB2gA9qjOHOXiQFCrxshFvZebMdn3ZvCKmkOgOON6Geh3E0LXQxmuqns92Gd1xcR87TaA74Xg")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Unexpected response: ${response.code}")
                        return@withContext null
                    }
                    response.body?.string()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching JSON from server", e)
                null
            }
        }
    }

    fun buildJsonUrl(takServerIp: String, departmentIdentifier: String, jsonType: String): String {
        val url = "https://$takServerIp:18281/json/$departmentIdentifier/$jsonType.json"
        Log.v(TAG, "URL: $url")
        return url
    }

    object MulticastPublisher {
        private const val TAG = "WearTAK-MulticastPub"

        @Volatile private var running = false
        @Volatile private var currentNetwork: Network? = null
        private var socket: MulticastSocket? = null
        private var group: InetAddress? = null

        private var address: String = DEFAULT_MULTICAST_ADDRESS
        private var port: Int = DEFAULT_MULTICAST_PORT

        /**
         * Call when Wi-Fi becomes available. We resolve the Wi-Fi Network and bind the socket to it.
         */
        fun start(network: Network, address: String, port: Int) {
            Log.i(TAG, "MulticastPublisher starting → $address:$port")
            MulticastPublisher.port = port
            synchronized(this) {
                // No-op if already bound to this same Network and socket is alive
                if (running && (currentNetwork == network) && (socket != null) && !socket!!.isClosed && (address == MulticastPublisher.address)) return

                // If running but on a different (or stale) Network, rebuild
                stopLocked()

                try {
                    val ms = java.net.MulticastSocket(null).apply {
                        reuseAddress = true
                        try { timeToLive = 1 } catch (_: Throwable) {}  // keep multicast on LAN
                        bind(null) // ephemeral source port
                    }

                    try {
                        network.bindSocket(ms)  // ensure packets egress over this Wi-Fi network
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "bindSocket failed: ${e.message}", e)
                        ms.close()
                        return
                    }

                    socket = ms
                    group = java.net.InetAddress.getByName(address)
                    currentNetwork = network
                    running = true
                    Log.i(TAG, "MulticastPublisher started → $address:${MulticastPublisher.port}")
                } catch (e: Exception) {
                    Log.e(TAG, "start() error: ${e.message}", e)
                    stopLocked() // clean up partial state
                }
            }
        }

        fun stop() {
            synchronized(this) {
                stopLocked()
            }
        }

        private fun stopLocked() {
            if (!running && socket == null && group == null && currentNetwork == null) return
            try {
                socket?.close()
            } catch (_: Throwable) { /* ignore */ }
            socket = null
            group = null
            currentNetwork = null
            running = false
            Log.i(TAG, "MulticastPublisher stopped")
        }

        /**
         * Non-blocking send. Safe to call from any thread.
         * PLI CoT is small; if you ever send big payloads, consider chunking.
         */
        fun send(cotXml: String) {
            //Log.i(TAG, "Checking socket")
            val ms = socket ?: return
            //Log.i(TAG, "Checking group")
            val grp = group ?: return
            //Log.i(TAG, "Checking multicast publisher running status")
            if (!running) return
            //Log.i(TAG, "Checking if multicast is enabled: ${MainActivity.enableMulticastOnWifi}")
            if (!MainActivity.enableMulticastOnWifi) return

            val bytes = cotXml.toByteArray(Charsets.UTF_8)
            // Keep messages small; UDP can fragment.
            if (bytes.size > 1300) {
                Log.w(TAG, "CoT payload ${bytes.size}B may fragment over UDP.")
            }
            try {
                ms.send(DatagramPacket(bytes, bytes.size, grp, port))
                //Log.i(TAG, "Sent Datagram to the multicast socket! $cotXml")
            } catch (e: Exception) {
                Log.e(TAG, "Multicast send() error: ${e.message}", e)
            }
        }
    }
}