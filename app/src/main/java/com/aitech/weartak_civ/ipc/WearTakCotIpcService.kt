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
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Binder
import android.util.Log
import com.aitech.weartak_civ.BuildConfig
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.DittoDocumentEvent
import com.weartak.ipcservice.IWearTakAppCotListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus

class WearTakCotIpcService : Service() {
    companion object {
        fun ensureStarted(context: Context) {
            val intent = Intent(context, WearTakCotIpcService::class.java).apply {
                action = AtosIpcConstants.ACTION_START
            }
            try {
                context.startService(intent)
                Log.i(AtosIpcConstants.LOG_TAG, "WearTakCotIpcService ensureStarted() requested")
            } catch (t: Throwable) {
                Log.w(
                    AtosIpcConstants.LOG_TAG,
                    "WearTakCotIpcService ensureStarted() failed: ${t::class.java.simpleName}: ${t.message ?: "unknown"}"
                )
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dataRepo by lazy {
        try {
            (applicationContext as WearTakApplication).repo
        } catch (_: Throwable) {
            DataRepository(applicationContext)
        }
    }

    private val binder = object : IWearTakAppCotListener.Stub() {
        override fun receiveCotFromPlugin(cotXml: String) {
            Log.i(
                AtosIpcConstants.LOG_TAG,
                "ATOS_IPC received route=LOCAL_INGEST_REQUEST size=${cotXml.utf8Size()}"
            )
            handleIncomingPayload(AtosIpcRoute.LOCAL_INGEST_REQUEST, cotXml)
        }

        override fun broadcastCot(cotXml: String) {
            Log.i(
                AtosIpcConstants.LOG_TAG,
                "ATOS_IPC received route=BROADCAST_REQUEST size=${cotXml.utf8Size()}"
            )
            handleIncomingPayload(AtosIpcRoute.BROADCAST_REQUEST, cotXml)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(AtosIpcConstants.LOG_TAG, "WearTakCotIpcService onCreate()")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.i(
            AtosIpcConstants.LOG_TAG,
            "WearTakCotIpcService onStartCommand(action=$action) startId=$startId flags=$flags"
        )
        return START_STICKY
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

    private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

    private fun handleIncomingPayload(route: AtosIpcRoute, cotXml: String) {
        val size = cotXml.utf8Size()
        val callingUid = Binder.getCallingUid()
        val callerPackages = packageManager.getPackagesForUid(callingUid)?.toList().orEmpty()
        val callerLabel = if (callerPackages.isEmpty()) {
            packageManager.getNameForUid(callingUid) ?: "unknown"
        } else {
            callerPackages.joinToString(",")
        }

        val result = try {
            AtosCotParser.parse(cotXml)
        } catch (t: Throwable) {
            AtosCotParseResult.Failure("Parser threw ${t::class.java.simpleName}: ${t.message ?: "unknown"}")
        }

        when (result) {
            is AtosCotParseResult.Failure -> {
                Log.w(
                    AtosIpcConstants.LOG_TAG,
                    "ATOS_IPC rejected route=$route caller=$callerLabel size=$size reason=\"${result.reason}\""
                )
            }

            is AtosCotParseResult.Success -> {
                val packet = result.packet

                Log.i(
                    AtosIpcConstants.LOG_TAG,
                    "ATOS_IPC accepted route=$route uid=${packet.uid} callsign=${packet.contact?.callsign} caller=$callerLabel"
                )

                logPacket(route, callerUid = callingUid, callerPackages = callerLabel, packet = packet)

                serviceScope.launch {
                    val applied = dataRepo.applyReceivedAtosTagEvent(packet)
                    Log.d(
                        AtosIpcConstants.LOG_TAG,
                        "ATOS_IPC map.apply route=$route uid=${packet.uid} applied=$applied"
                    )
                }

                val takXml = AtosCotSerializer.toCotXml(packet)

                if (takXml.isBlank()) {
                    Log.w(
                        AtosIpcConstants.LOG_TAG,
                        "ATOS_IPC serialize.failed route=$route uid=${packet.uid}"
                    )
                    return
                }

                Log.d(
                    AtosIpcConstants.LOG_TAG,
                    "ATOS_IPC serialize.ok route=$route uid=${packet.uid} takSize=${takXml.utf8Size()}"
                )

                if (Log.isLoggable(AtosIpcConstants.LOG_TAG, Log.VERBOSE)) {
                    Log.v(
                        AtosIpcConstants.LOG_TAG,
                        "ATOS_IPC incoming.xml route=$route uid=${packet.uid} size=$size xml=$cotXml"
                    )
                    Log.v(
                        AtosIpcConstants.LOG_TAG,
                        "ATOS_IPC outgoing.xml route=$route uid=${packet.uid} size=${takXml.utf8Size()} xml=$takXml"
                    )
                }

                if (route == AtosIpcRoute.BROADCAST_REQUEST) {
                    dataRepo.broadcastCot(takXml)
                } else {
                    Log.d(
                        AtosIpcConstants.LOG_TAG,
                        "ATOS_IPC broadcast.skip route=$route uid=${packet.uid} reason=local_ingest"
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

        val verboseSummary = buildString {
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
        Log.v(AtosIpcConstants.LOG_TAG, verboseSummary)

        packet.warnings.forEach { warning ->
            Log.w(AtosIpcConstants.LOG_TAG, "ATOS CoT warning route=$route warning=\"$warning\" uid=${packet.uid}")
        }

        if (atos == null) {
            Log.i(AtosIpcConstants.LOG_TAG, "ATOS detail block absent route=$route uid=${packet.uid}")
        } else {
            val expectedCotType = AtosReferenceTables.resolveTagTypeToCotType(atos.tagType)
            val colorArgb = atos.color?.let { AtosReferenceTables.teamColorToArgb[it] }
            Log.v(
                AtosIpcConstants.LOG_TAG,
                "ATOS detail route=$route tagType=${atos.tagType} expectedCotType=${expectedCotType ?: "unknown"} colorArgb=${colorArgb?.toString(16) ?: "unknown"} customAttrCount=$customAttrCount"
            )
            atos.attributes.forEach { (key, value) ->
                Log.v(AtosIpcConstants.LOG_TAG, "ATOS attribute route=$route key=$key value=$value uid=${packet.uid}")
            }
        }

        if (link != null) {
            Log.v(
                AtosIpcConstants.LOG_TAG,
                "ATOS link route=$route relation=${link.relation ?: "null"} uid=${link.uid ?: "null"} type=${link.type ?: "null"} parentCallsign=${link.parentCallsign ?: "null"}"
            )
        }
    }
}
