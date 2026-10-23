
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
import com.aitech.weartak_civ.command.ChatCommandParser
import com.aitech.weartak_civ.command.ChatCommandRequest
import com.aitech.weartak_civ.data.model.GeofenceCot
import com.aitech.weartak_civ.data.parsers.GeofenceCotParser
import com.aitech.weartak_civ.events.TakChatEvent
import kotlinx.coroutines.CancellationException
import com.aitech.weartak_civ.data.model.TakRoute
import com.aitech.weartak_civ.data.parsers.TakRouteCotParser
import com.aitech.weartak_civ.data.takserver.TakDataPackageRequest

// Holds information about a CoT message received from a TAK Server
// Keeps the original message along with the server it came from
// This helps keep incoming data from different TAK Servers separate
data class TakServerCotEnvelope(

    // ID of the TAK Server that sent the message
    val sourceServerId: String,

    // Address of the TAK Server that sent the message
    val sourceServerAddress: String,

    // Time when the message was received
    val receivedAt: Long,

    // Original CoT message in XML format
    val rawXml: String,
    val sourceSessionId: String? = null,
    val sourcePeerAddress: String? = null
)


// Defines how different types of incoming CoT messages get passed to the app
// Each function handles a specific type of TAK information
// The ingestor calls the matching function after identifying the message type
interface TakServerCotSink {

    // Pass a received chat message to the chat handler
    suspend fun onChat(event: TakChatEvent)

    // Pass a received geofence to the geofence handler
    suspend fun onGeofence(geofence: GeofenceCot)

    // Pass a user location update along with its source server
    suspend fun onUser(event: CotUserPositionEvent, envelope: TakServerCotEnvelope)

    // Pass a map marker along with its source server
    suspend fun onMarker(event: CotMarkerEvent, envelope: TakServerCotEnvelope)

    // Pass an emergency event to the emergency handler
    // Does nothing by default unless a handler overrides it
    suspend fun onEmergency(event: CotEmergencyEvent, envelope: TakServerCotEnvelope) = Unit

    // Notify the app when a channel change message is received
    // Does nothing by default unless a handler overrides it
    suspend fun onChannelChanged(envelope: TakServerCotEnvelope) = Unit

    // Give another handler a chance to process the incoming CoT first
    // Returning true means the message was already handled
    // Returning false allows normal message processing to continue
    suspend fun onInboundCot(envelope: TakServerCotEnvelope): Boolean = false

    // Pass a parsed route to the route handler
    // Includes the route points and the server the route came from
    suspend fun onRoute(route: TakRoute, envelope: TakServerCotEnvelope)
}


// Lists the possible results after processing an incoming CoT message
// Each result identifies the type of message that was handled
// MALFORMED means the message could not be processed correctly
// UNSUPPORTED means the message type is not currently handled
enum class TakServerCotIngestResult {
    CHAT,
    COMMAND,
    GEOFENCE,
    USER,
    MARKER,
    EMERGENCY,
    ROUTE,
    DATA_PACKAGE,
    CHANNEL_CHANGED,
    CONFIGURATION,
    SUPPRESSED,
    UNSUPPORTED,
    MALFORMED
}


// Receives incoming CoT messages and figures out what type of data they contain
// CoT stands for Cursor on Target and uses XML to share TAK information
// Handles chats, geofences, user locations, markers, emergencies, and routes
// Sends parsed information to the correct handler through TakServerCotSink
class TakServerCotIngestor(

    // Handler that receives the parsed CoT information
    private val sink: TakServerCotSink,

    // Checks whether chat processing is enabled
    private val isChatEnabled: suspend () -> Boolean,

    // Checks whether geofence processing is enabled
    private val isGeofenceEnabled: () -> Boolean,

    // Gets the UID of the current watch
    private val deviceUid: () -> String,

    // Gets the callsign of the current watch
    private val deviceCallsign: () -> String,
    private val chatCommandHandler: (suspend (ChatCommandRequest) -> Boolean)? = null,
    private val dataPackageHandler: (suspend (TakDataPackageRequest, TakServerCotEnvelope) -> Unit)? = null,

    private val isInboundAllowed: () -> Boolean = { true },
    // Logs problems found while processing incoming CoT messages
    private val logger: (String, Throwable?) -> Unit = { message, error ->
        if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
    }
) {

    // Main entry point for processing an incoming CoT message
    // Checks that the message has a valid source server ID
    // Passes the message to the parser and returns the result
    // Handles invalid messages without crashing the processing flow
    suspend fun ingest(envelope: TakServerCotEnvelope): TakServerCotIngestResult {
        if (!isInboundAllowed()) {
            logger("Suppressing direct CoT while bonded companion is connected source=${envelope.sourceServerId}", null)
            return TakServerCotIngestResult.SUPPRESSED
        }
        return try {

            // Make sure the message came from a server with a valid ID
            // This is needed to keep data associated with the correct server
            require(envelope.sourceServerId.isNotBlank()) {
                "TAK server ID cannot be blank"
            }

            // Process the message and determine its CoT type
            ingestMessage(envelope)

        } catch (error: CancellationException) {

            // Allow coroutine cancellation to continue normally
            // Cancellation should not be treated as a malformed message
            throw error

        } catch (error: Exception) {

            // Log the error and skip the message if processing fails
            // Return MALFORMED instead of stopping other incoming messages
            logger(
                "Skipping malformed CoT from serverId=${envelope.sourceServerId}",
                error
            )

            TakServerCotIngestResult.MALFORMED
        }
    }


    // Checks the incoming CoT message and determines how to handle it
    // Each message type has its own parsing and validation steps
    // Once a message is handled, the matching result is returned
    private suspend fun ingestMessage(envelope: TakServerCotEnvelope): TakServerCotIngestResult {

        // Remove extra whitespace from the beginning and end of the XML
        val rawXml = envelope.rawXml.trim()

        // Check that the message contains basic CoT event tags
        // Skip messages that do not look like a complete CoT event
        if (!rawXml.contains("<event") || !rawXml.contains("</event>")) {
            logger("Skipping malformed CoT from serverId=${envelope.sourceServerId}", null)
            return TakServerCotIngestResult.MALFORMED
        }

        // Let the inbound handler check the message before normal processing
        // Return CONFIGURATION if another handler already processed the message
        if (sink.onInboundCot(envelope)) return TakServerCotIngestResult.CONFIGURATION

        if (dataPackageHandler != null && CotMessageParser.eventTypeOrNull(rawXml) == "b-f-t-r") {
            dataPackageHandler.invoke(TakDataPackageRequest.parse(rawXml, envelope.sourcePeerAddress), envelope)
            return TakServerCotIngestResult.DATA_PACKAGE
        }

        if (chatCommandHandler != null && CotMessageParser.isChatMessage(rawXml)) {
            val direct = CotMessageParser.parseChatRooms(rawXml, deviceUid(), deviceCallsign(), envelope.sourceServerId, envelope.receivedAt)
                .filter { !it.isGroup }
                .flatMap { it.messageList }
                .firstOrNull { it.message.startsWith(ChatCommandParser.PREFIX, ignoreCase = true) }
            if (direct != null) {
                val request = ChatCommandRequest(
                    senderUid = direct.uid,
                    senderCallsign = direct.callsign,
                    text = direct.message,
                    messageId = direct.messageId,
                    sentAtMs = CotMessageParser.eventTimeOrNull(rawXml) ?: 0L,
                    receivedAtMs = envelope.receivedAt,
                    sourceServerId = envelope.sourceServerId
                )
                if (chatCommandHandler(request)) return TakServerCotIngestResult.COMMAND
            }
        }

        // Check whether the incoming message is a chat
        // Only process chats when chat is enabled and the message is for this device
        if (isChatEnabled() &&
            CotMessageParser.isChatMessage(rawXml) &&
            CotMessageParser.isChatForRecipient(rawXml, deviceUid(), deviceCallsign())) {

            // Parse the chat message and find the rooms or conversations it belongs to
            // Include the source server and time the message was received
            val parsedRooms = CotMessageParser.parseChatRooms(
                rawXml = rawXml,
                myUid = deviceUid(),
                myCallsign = deviceCallsign(),
                sourceServerId = envelope.sourceServerId,
                receivedAt = envelope.receivedAt
            )

            // Skip the chat if no valid room information was found
            if (parsedRooms.isEmpty()) {
                logger("Skipping malformed TAK chat from serverId=${envelope.sourceServerId}", null)
                return TakServerCotIngestResult.MALFORMED
            }

            // Send the parsed chat information to the chat handler
            sink.onChat(
                TakChatEvent(
                    rawXml = rawXml,
                    sourceServerId = envelope.sourceServerId,
                    receivedAt = envelope.receivedAt,
                    parsedRooms = parsedRooms
                )
            )

            // Mark the message as a successfully handled chat
            return TakServerCotIngestResult.CHAT
        }


        // Check whether the message contains a supported geofence
        // Only process this type of geofence when geofences are enabled
        if (isGeofenceEnabled() && GeofenceCotParser.isRadioSilenceRectangleGeofence(rawXml)) {

            // Convert the geofence XML into a geofence object
            val geofence = GeofenceCotParser.parse(rawXml)

            // Skip the message if the geofence could not be parsed
            if (geofence == null) {
                logger("Skipping malformed TAK geofence from serverId=${envelope.sourceServerId}", null)
                return TakServerCotIngestResult.MALFORMED
            }

            // Add the source server and receive time to the geofence
            // Send the completed geofence to its handler
            sink.onGeofence(
                geofence.copy(
                    sourceServerId = envelope.sourceServerId,
                    receivedAt = envelope.receivedAt
                )
            )

            return TakServerCotIngestResult.GEOFENCE
        }


        // Check whether the message is a TAK channel change event
        // Channel change messages use the event type t-x-g-c
        if (CotMessageParser.eventTypeOrNull(rawXml) == CHANNEL_CHANGE_EVENT_TYPE) {

            // Notify the channel handler that a change was received
            sink.onChannelChanged(envelope)

            return TakServerCotIngestResult.CHANNEL_CHANGED
        }


        // Check whether the message contains a TAK route
        // Route CoT messages use the event type b-m-r
        // These messages contain the points needed to draw a route on the map
        if (CotMessageParser.eventTypeOrNull(rawXml) == ROUTE_EVENT_TYPE) {

            // Parse the route XML into a TakRoute object
            // Extract the route details and its ordered list of points
            // Include the source server ID to keep routes separated by server
            val route = TakRouteCotParser.parse(
                rawXml,
                envelope.sourceServerId
            )

            // Check whether the route was parsed successfully
            // A null result means the route data was missing or invalid
            if (route == null) {
                logger(
                    "Skipping malformed route CoT from serverId=${envelope.sourceServerId}",
                    null
                )

                // Skip the invalid route instead of passing it to the app
                return TakServerCotIngestResult.MALFORMED
            }

            // Send the parsed route to the route handler
            // The route can then be stored and made available to the map
            sink.onRoute(route, envelope)

            // Mark the message as a successfully handled route
            return TakServerCotIngestResult.ROUTE
        }


        // Check whether the message contains an emergency event
        // Emergency CoT uses specific event types recognized by the parser
        if (CotMessageParser.eventTypeOrNull(rawXml)?.let(CotMessageParser::isEmergencyType) == true) {

            // Parse the emergency details from the XML
            val event = CotMessageParser.parseEmergencyOrNull(rawXml)

            // Skip the message if the emergency information is invalid
            if (event == null) {
                logger("Skipping malformed emergency CoT source=${envelope.sourceServerId}", null)
                return TakServerCotIngestResult.MALFORMED
            }

            // Log the emergency details for debugging
            // Includes the event UID, status, location, and timing information
            logger(
                "Emergency ingress uid=${event.uid} source=${envelope.sourceServerId} " +
                        "cancelled=${event.cancelled} hasLocation=${event.hasLocation} time=${event.time} stale=${event.stale}",
                null
            )

            // Only forward emergencies that did not come from the current device
            // Prevents the app from processing its own emergency as a new incoming event
            if (!event.isFrom(deviceUid())) sink.onEmergency(event, envelope)

            return TakServerCotIngestResult.EMERGENCY
        }


        // Try to parse the message as a user location update
        // A valid user position contains a UID, timestamps, and coordinates
        CotMessageParser.parseUserPositionOrNull(rawXml)?.let { event ->

            // Make sure the user position has all required fields
            if (!event.hasValidRequiredFields()) {
                logger("Skipping malformed TAK user CoT from serverId=${envelope.sourceServerId}", null)
                return TakServerCotIngestResult.MALFORMED
            }

            // Only forward location updates from other devices
            // Skip the current watch to avoid processing its own position
            if (event.uid != deviceUid()) {
                sink.onUser(event, envelope)
            }

            return TakServerCotIngestResult.USER
        }


        // Try to parse the message as a map marker
        // Markers represent points or objects displayed on the TAK map
        CotMessageParser.parseMarkerOrNull(rawXml)?.let { event ->

            // Check that the marker has the required fields and valid coordinates
            if (!event.hasValidRequiredFields()) {
                logger("Skipping malformed TAK marker CoT from serverId=${envelope.sourceServerId}", null)
                return TakServerCotIngestResult.MALFORMED
            }

            // Send the marker to the map marker handler
            sink.onMarker(event, envelope)

            return TakServerCotIngestResult.MARKER
        }


        // Get the CoT event type if one is available
        val eventType = CotMessageParser.eventTypeOrNull(rawXml)

        // Check whether the message claims to be a supported user or marker type
        // Reaching this point means the earlier parsers could not process it
        // Treat the message as malformed rather than unsupported
        if (eventType != null &&
            (CotMessageParser.isUserPositionType(eventType) || CotMessageParser.isMarkerType(eventType))) {

            logger("Skipping malformed supported CoT from serverId=${envelope.sourceServerId}", null)
            return TakServerCotIngestResult.MALFORMED
        }

        // Skip messages that do not match any currently supported CoT type
        logger("Skipping unsupported CoT from serverId=${envelope.sourceServerId}", null)
        return TakServerCotIngestResult.UNSUPPORTED
    }


    // Checks whether a user location update has the required information
    // Requires a UID, valid timestamps, and usable coordinates
    private fun CotUserPositionEvent.hasValidRequiredFields(): Boolean =
        uid.isNotBlank() && time > 0L && start > 0L && stale > 0L && point.isFinite()


    // Checks whether a marker has the required information
    // Requires a UID, valid timestamps, and usable coordinates
    private fun CotMarkerEvent.hasValidRequiredFields(): Boolean =
        uid.isNotBlank() && time > 0L && start > 0L && stale > 0L && point.isFinite()


    // Checks that all coordinate and accuracy values are finite numbers
    // Rejects values such as NaN or infinity that cannot be used as valid numbers
    private fun CotPoint.isFinite(): Boolean =
        lat.isFinite() && lon.isFinite() && hae.isFinite() && ce.isFinite() && le.isFinite()


    // Constants used to identify the logger and specific CoT event types
    private companion object {

        // Tag used to identify messages from this class in Android logs
        const val TAG = "WearTAK-TakServerCotIngestor"

        // CoT event type used for channel change notifications
        const val CHANNEL_CHANGE_EVENT_TYPE = "t-x-g-c"

        // CoT event type used for TAK routes
        const val ROUTE_EVENT_TYPE = "b-m-r"
    }
}
