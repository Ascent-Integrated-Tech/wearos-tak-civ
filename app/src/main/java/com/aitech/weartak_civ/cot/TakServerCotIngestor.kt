/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.cot

import android.util.Log
import com.aitech.weartak_civ.data.model.GeofenceCot
import com.aitech.weartak_civ.data.parsers.GeofenceCotParser
import com.aitech.weartak_civ.events.TakChatEvent
import kotlinx.coroutines.CancellationException
import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.parsers.TakRouteCotParser

data class TakServerCotEnvelope(
    val sourceServerId: String,
    val sourceServerAddress: String,
    val receivedAt: Long,
    val rawXml: String
)

interface TakServerCotSink {
    suspend fun onChat(event: TakChatEvent)
    suspend fun onGeofence(geofence: GeofenceCot)
    suspend fun onUser(event: CotUserPositionEvent, envelope: TakServerCotEnvelope)
    suspend fun onMarker(event: CotMarkerEvent, envelope: TakServerCotEnvelope)
    suspend fun onChannelChanged(envelope: TakServerCotEnvelope) = Unit
    suspend fun onRoute(route: TakRoute, envelope: TakServerCotEnvelope)
}

enum class TakServerCotIngestResult {
    CHAT,
    GEOFENCE,
    USER,
    MARKER,
    ROUTE,
    CHANNEL_CHANGED,
    UNSUPPORTED,
    MALFORMED
}

class TakServerCotIngestor(
    private val sink: TakServerCotSink,
    private val isChatEnabled: suspend () -> Boolean,
    private val isGeofenceEnabled: () -> Boolean,
    private val deviceUid: () -> String,
    private val deviceCallsign: () -> String,
    private val logger: (String, Throwable?) -> Unit = { message, error ->
        if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
    }
) {
    suspend fun ingest(envelope: TakServerCotEnvelope): TakServerCotIngestResult {
        return try {
            require(envelope.sourceServerId.isNotBlank()) { "TAK server ID cannot be blank" }
            ingestMessage(envelope)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger(
                "Skipping malformed CoT from serverId=${envelope.sourceServerId}",
                error
            )
            TakServerCotIngestResult.MALFORMED
        }
    }

    private suspend fun ingestMessage(envelope: TakServerCotEnvelope): TakServerCotIngestResult {
        val rawXml = envelope.rawXml.trim()
        if (!rawXml.contains("<event") || !rawXml.contains("</event>")) {
            logger("Skipping malformed CoT from serverId=${envelope.sourceServerId}", null)
            return TakServerCotIngestResult.MALFORMED
        }

        if (isChatEnabled() &&
            CotMessageParser.isChatMessage(rawXml) &&
            CotMessageParser.isChatForRecipient(rawXml, deviceUid(), deviceCallsign())) {
            val parsedRooms = CotMessageParser.parseChatRooms(
                rawXml = rawXml,
                myUid = deviceUid(),
                myCallsign = deviceCallsign(),
                sourceServerId = envelope.sourceServerId,
                receivedAt = envelope.receivedAt
            )
            if (parsedRooms.isEmpty()) {
                logger("Skipping malformed TAK chat from serverId=${envelope.sourceServerId}", null)
                return TakServerCotIngestResult.MALFORMED
            }
            sink.onChat(
                TakChatEvent(
                    rawXml = rawXml,
                    sourceServerId = envelope.sourceServerId,
                    receivedAt = envelope.receivedAt,
                    parsedRooms = parsedRooms
                )
            )
            return TakServerCotIngestResult.CHAT
        }

        if (isGeofenceEnabled() && GeofenceCotParser.isRadioSilenceRectangleGeofence(rawXml)) {
            val geofence = GeofenceCotParser.parse(rawXml)
            if (geofence == null) {
                logger("Skipping malformed TAK geofence from serverId=${envelope.sourceServerId}", null)
                return TakServerCotIngestResult.MALFORMED
            }
            sink.onGeofence(
                geofence.copy(
                    sourceServerId = envelope.sourceServerId,
                    receivedAt = envelope.receivedAt
                )
            )
            return TakServerCotIngestResult.GEOFENCE
        }

//        if (CotMessageParser.eventTypeOrNull(rawXml) == CHANNEL_CHANGE_EVENT_TYPE) {
//            val route = TakRouteCotParser.parse(
//                rawXml,
//                envelope.sourceServerId
//            )
//
//            if (route == null) {
//                logger(
//                    "Skipping malformed route CoT from serverId=${envelope.sourceServerId}",
//                    null
//                )
//                return TakServerCotIngestResult.MALFORMED
//            }
//            sink.onChannelChanged(envelope)
//            return TakServerCotIngestResult.CHANNEL_CHANGED
//        }

        if (CotMessageParser.eventTypeOrNull(rawXml) == CHANNEL_CHANGE_EVENT_TYPE) {
            sink.onChannelChanged(envelope)
            return TakServerCotIngestResult.CHANNEL_CHANGED
        }

        if (CotMessageParser.eventTypeOrNull(rawXml) == ROUTE_EVENT_TYPE) {
            val route = TakRouteCotParser.parse(
                rawXml,
                envelope.sourceServerId
            )

            if (route == null) {
                logger(
                    "Skipping malformed route CoT from serverId=${envelope.sourceServerId}",
                    null
                )
                return TakServerCotIngestResult.MALFORMED
            }

            sink.onRoute(route, envelope)
            return TakServerCotIngestResult.ROUTE
        }


        CotMessageParser.parseUserPositionOrNull(rawXml)?.let { event ->
            if (!event.hasValidRequiredFields()) {
                logger("Skipping malformed TAK user CoT from serverId=${envelope.sourceServerId}", null)
                return TakServerCotIngestResult.MALFORMED
            }
            if (event.uid != deviceUid()) {
                sink.onUser(event, envelope)
            }
            return TakServerCotIngestResult.USER
        }

        CotMessageParser.parseMarkerOrNull(rawXml)?.let { event ->
            if (!event.hasValidRequiredFields()) {
                logger("Skipping malformed TAK marker CoT from serverId=${envelope.sourceServerId}", null)
                return TakServerCotIngestResult.MALFORMED
            }
            sink.onMarker(event, envelope)
            return TakServerCotIngestResult.MARKER
        }

        val eventType = CotMessageParser.eventTypeOrNull(rawXml)
        if (eventType != null &&
            (CotMessageParser.isUserPositionType(eventType) || CotMessageParser.isMarkerType(eventType))) {
            logger("Skipping malformed supported CoT from serverId=${envelope.sourceServerId}", null)
            return TakServerCotIngestResult.MALFORMED
        }

        logger("Skipping unsupported CoT from serverId=${envelope.sourceServerId}", null)
        return TakServerCotIngestResult.UNSUPPORTED
    }

    private fun CotUserPositionEvent.hasValidRequiredFields(): Boolean =
        uid.isNotBlank() && time > 0L && start > 0L && stale > 0L && point.isFinite()

    private fun CotMarkerEvent.hasValidRequiredFields(): Boolean =
        uid.isNotBlank() && time > 0L && start > 0L && stale > 0L && point.isFinite()

    private fun CotPoint.isFinite(): Boolean =
        lat.isFinite() && lon.isFinite() && hae.isFinite() && ce.isFinite() && le.isFinite()

    private companion object {
        const val TAG = "WearTAK-TakServerCotIngestor"
        const val CHANNEL_CHANGE_EVENT_TYPE = "t-x-g-c"
        const val ROUTE_EVENT_TYPE = "b-m-r"
    }
}
