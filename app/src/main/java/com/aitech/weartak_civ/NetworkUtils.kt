/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import android.util.Log
import com.aitech.weartak_civ.presentation.MainActivity
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager


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
}