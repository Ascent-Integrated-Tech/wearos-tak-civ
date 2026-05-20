/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.ipc

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Binder
import android.util.Log
import com.aitech.weartak_civ.BuildConfig
import com.aitech.weartak_civ.events.DittoDocumentEvent
import com.aitech.weartak_civ.service.TAKConnectService.Companion.sendDataTAKServers
import com.weartak.ipcservice.IWearTakAppCotListener
import org.greenrobot.eventbus.EventBus

class WearTakCotIpcService : Service() {
    private val binder = object : IWearTakAppCotListener.Stub() {
        override fun receiveCotFromPlugin(cotXml: String) {
            handleIncomingPayload(AtosIpcRoute.LOCAL_INGEST_REQUEST, cotXml)
        }

        override fun broadcastCot(cotXml: String) {
            handleIncomingPayload(AtosIpcRoute.BROADCAST_REQUEST, cotXml)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(AtosIpcConstants.LOG_TAG, "WearTakCotIpcService onCreate()")
    }

    override fun onBind(intent: Intent?): IBinder? {
        val callingUid = Binder.getCallingUid()
        val callerPackages = packageManager.getPackagesForUid(callingUid)?.toList().orEmpty()
        val callerLabel = if (callerPackages.isEmpty()) {
            packageManager.getNameForUid(callingUid) ?: "unknown"
        } else {
            callerPackages.joinToString(",")
        }

        if (!isCallerAllowed(callerPackages)) {
            Log.w(
                AtosIpcConstants.LOG_TAG,
                "Rejected ATOS bind action=${intent?.action} callerUid=$callingUid callerPackages=$callerLabel"
            )
            return null
        }

        Log.i(
            AtosIpcConstants.LOG_TAG,
            "WearTakCotIpcService onBind(action=${intent?.action}) callerUid=$callingUid callerPackages=$callerLabel"
        )
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(AtosIpcConstants.LOG_TAG, "WearTakCotIpcService onUnbind(action=${intent?.action})")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(AtosIpcConstants.LOG_TAG, "WearTakCotIpcService onDestroy()")
        super.onDestroy()
    }

    private fun isCallerAllowed(callerPackages: List<String>): Boolean {
        if (callerPackages.any { it == AtosIpcConstants.EXPECTED_ATOS_PACKAGE }) {
            return true
        }

        if (BuildConfig.DEBUG) {
            if (callerPackages.any { it == packageName }) {
                return true
            }
            if (callerPackages.any { it.endsWith(".test") }) {
                return true
            }
        }

        return false
    }

    private fun handleIncomingPayload(route: AtosIpcRoute, cotXml: String) {
        val size = cotXml.toByteArray(Charsets.UTF_8).size
        val callingUid = Binder.getCallingUid()
        val callerPackages = packageManager.getPackagesForUid(callingUid)?.toList().orEmpty()
        val callerLabel = if (callerPackages.isEmpty()) {
            packageManager.getNameForUid(callingUid) ?: "unknown"
        } else {
            callerPackages.joinToString(",")
        }
        logIncomingPayloadPreview(route, size, callingUid, callerLabel, cotXml)
        logIncomingPayloadFull(route, callingUid, callerLabel, cotXml)
        val result = try {
            AtosCotParser.parse(cotXml)
        } catch (t: Throwable) {
            AtosCotParseResult.Failure("Parser threw ${t::class.java.simpleName}: ${t.message ?: "unknown"}")
        }

        when (result) {
            is AtosCotParseResult.Failure -> {
                Log.w(
                    AtosIpcConstants.LOG_TAG,
                    "Rejected ATOS CoT route=$route callerUid=$callingUid callerPackages=$callerLabel reason=\"${result.reason}\" size=$size"
                )
            }
            is AtosCotParseResult.Success -> {
                logPacket(route, callerUid = callingUid, callerPackages = callerLabel, packet = result.packet)
                val takXml = AtosCotSerializer.toCotXml(result.packet)
                if (takXml.isBlank()) {
                    Log.w(
                        AtosIpcConstants.LOG_TAG,
                        "ATOS CoT serialization failed route=$route uid=${result.packet.uid}"
                    )
                } else {
                    EventBus.getDefault().post(DittoDocumentEvent("mapitem", takXml))
                    sendDataTAKServers(takXml, true)
                    Log.i(
                        AtosIpcConstants.LOG_TAG,
                        "Forwarded ATOS CoT to TAK route=$route uid=${result.packet.uid} size=${takXml.toByteArray(Charsets.UTF_8).size}"
                    )
                }
            }
        }
    }

    private fun logIncomingPayloadPreview(
        route: AtosIpcRoute,
        size: Int,
        callingUid: Int,
        callerLabel: String,
        cotXml: String,
    ) {
        val bytes = cotXml.toByteArray(Charsets.UTF_8)
        val previewBytes = bytes.take(32).joinToString(" ") { byte -> "%02x".format(byte) }
        val previewText = cotXml.take(120)
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        Log.d(
            AtosIpcConstants.LOG_TAG,
            "ATOS payload preview route=$route callerUid=$callingUid callerPackages=$callerLabel size=$size firstBytes=$previewBytes textPrefix=\"$previewText\""
        )
    }

    private fun logIncomingPayloadFull(
        route: AtosIpcRoute,
        callingUid: Int,
        callerLabel: String,
        cotXml: String,
    ) {
        Log.d(
            AtosIpcConstants.LOG_TAG,
            "ATOS payload full route=$route callerUid=$callingUid callerPackages=$callerLabel xml=${cotXml.replace("\n", "\\n").replace("\r", "\\r")}"
        )
    }

    private fun logPacket(route: AtosIpcRoute, callerUid: Int, callerPackages: String, packet: AtosCotPacket) {
        val point = packet.point
        val contact = packet.contact
        val track = packet.track
        val atos = packet.atosDetail
        val link = packet.link
        val customAttrCount = atos?.attributes?.size ?: 0

        val summary = buildString {
            append("Received ATOS CoT route=").append(route)
            append(" uid=").append(packet.uid ?: "null")
            append(" type=").append(packet.type ?: "null")
            append(" callsign=").append(contact?.callsign ?: "null")
            append(" tagUid=").append(atos?.tagUid ?: "null")
            append(" tagType=").append(atos?.tagType ?: "null")
            append(" color=").append(atos?.color ?: "null")
            append(" commandlink=").append(atos?.commandLink ?: "null")
            append(" tempC=").append(atos?.tempC ?: "null")
            append(" voltage=").append(atos?.voltage ?: "null")
            append(" lat=").append(point?.lat ?: "null")
            append(" lon=").append(point?.lon ?: "null")
            append(" hae=").append(point?.hae ?: "null")
            append(" speed=").append(track?.speed ?: "null")
            append(" course=").append(track?.course ?: "null")
            append(" vspeed=").append(track?.vspeed ?: "null")
            append(" slope=").append(track?.slope ?: "null")
            append(" alarm=").append(atos?.alarm ?: "null")
            append(" manifest=").append(atos?.manifest ?: "null")
            append(" archive=").append(packet.hasArchive)
            append(" attrs=").append(customAttrCount)
            append(" size=").append(packet.rawXmlLength)
            append(" callerUid=").append(callerUid)
            append(" callerPackages=").append(callerPackages)
        }

        Log.i(AtosIpcConstants.LOG_TAG, summary)

        packet.warnings.forEach { warning ->
            Log.w(AtosIpcConstants.LOG_TAG, "ATOS CoT warning route=$route warning=\"$warning\" uid=${packet.uid}")
        }

        if (atos == null) {
            Log.i(AtosIpcConstants.LOG_TAG, "ATOS detail block absent route=$route uid=${packet.uid}")
        } else {
            val expectedCotType = AtosReferenceTables.tagTypeToCotType[atos.tagType]
            val colorArgb = atos.color?.let { AtosReferenceTables.teamColorToArgb[it] }
            Log.d(
                AtosIpcConstants.LOG_TAG,
                "ATOS detail route=$route tagType=${atos.tagType} expectedCotType=${expectedCotType ?: "unknown"} colorArgb=${colorArgb?.toString(16) ?: "unknown"} customAttrCount=$customAttrCount"
            )
            atos.attributes.forEach { (key, value) ->
                Log.d(AtosIpcConstants.LOG_TAG, "ATOS attribute route=$route key=$key value=$value uid=${packet.uid}")
            }
        }

        if (link != null) {
            Log.d(
                AtosIpcConstants.LOG_TAG,
                "ATOS link route=$route relation=${link.relation ?: "null"} uid=${link.uid ?: "null"} type=${link.type ?: "null"} parentCallsign=${link.parentCallsign ?: "null"}"
            )
        }
    }
}
