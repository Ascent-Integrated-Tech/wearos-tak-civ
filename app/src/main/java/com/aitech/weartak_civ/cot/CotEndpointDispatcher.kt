package com.aitech.weartak_civ.cot

import com.aitech.weartak_civ.data.DataRepository

sealed interface CotCompanionPayload {
    data class Pli(val snapshot: DataRepository.PliSnapshot) : CotCompanionPayload
    data class Chat(val snapshot: DataRepository.ChatEventSnapshot) : CotCompanionPayload
    data class Marker(val snapshot: DataRepository.MarkerSnapshot) : CotCompanionPayload
    data class Emergency(val snapshot: DataRepository.EmergencyEventSnapshot) : CotCompanionPayload
}

data class CotOutboundMessage(
    val xml: String,
    val kind: CotMessageKind,
    val companionPayload: CotCompanionPayload,
)

class CotEndpointDispatcher(
    private val sendTak: (xml: String, addToQueue: Boolean) -> Unit,
    private val sendSitx: (xml: String) -> Unit,
    private val sendDitto: (collection: String, xml: String) -> Unit,
    private val sendMulticast: (xml: String) -> Unit,
    private val sendPliMulticast: (xml: String) -> Unit,
    private val sendCompanion: suspend (payload: CotCompanionPayload) -> Unit,
) {
    suspend fun dispatch(message: CotOutboundMessage, availability: CotEndpointAvailability): Boolean {
        val routes = CotDispatchPolicy.routesFor(message.kind, availability)
        routes.forEach { route ->
            when (route) {
                CotRoute.TAK -> sendTak(message.xml, message.kind != CotMessageKind.PLI)
                CotRoute.SITX -> sendSitx(message.xml)
                CotRoute.DITTO -> sendDitto("mapitem", message.xml)
                CotRoute.MULTICAST -> if (message.kind == CotMessageKind.PLI) {
                    sendPliMulticast(message.xml)
                } else {
                    sendMulticast(message.xml)
                }
                CotRoute.COMPANION -> sendCompanion(message.companionPayload)
            }
        }
        return routes.isNotEmpty()
    }
}
