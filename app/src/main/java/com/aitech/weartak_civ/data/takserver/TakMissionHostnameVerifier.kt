/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.data.takserver

import android.util.Log
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import okhttp3.OkHttpClient

/**
 * Hostname verifier used specifically for TAK Server Mission/Data Sync REST requests.
 *
 * Standard hostname verification is attempted first. Some TAK Server deployments
 * use certificates whose SAN does not match the hostname used to reach the server.
 * Mission API requests allow that mismatch while certificate trust is still handled
 * by the configured SSLContext/TrustManager.
 */
internal class TakMissionHostnameVerifier(
    private val strictVerifier: HostnameVerifier = OkHttpClient().hostnameVerifier,
) : HostnameVerifier {

    override fun verify(
        hostname: String,
        session: SSLSession,
    ): Boolean {
        if (strictVerifier.verify(hostname, session)) {
            return true
        }

        Log.w(
            TAG,
            "Mission API hostname mismatch allowed for host=$hostname"
        )

        return true
    }

    private companion object {
        const val TAG = "TakMissionHostnameVerifier"
    }
}