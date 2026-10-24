package com.aitech.weartak_civ.cot

import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface CotCompanionPayload {
    data class Pli(val snapshot: DataRepository.PliSnapshot) : CotCompanionPayload
    data class Chat(val snapshot: DataRepository.ChatEventSnapshot) : CotCompanionPayload
    data class Marker(val snapshot: DataRepository.MarkerSnapshot) : CotCompanionPayload
    data class OwnedPointDeletion(val uid: String) : CotCompanionPayload
    data class Emergency(val snapshot: DataRepository.EmergencyEventSnapshot) : CotCompanionPayload
}

data class CotOutboundMessage(
    val xml: String,
    val kind: CotMessageKind,
    val companionPayload: CotCompanionPayload,
    /** Associates the no-fix GeoChat notification with its emergency delivery receipt. */
    val emergencyDelivery: DataRepository.EmergencyEventSnapshot? = null,
)

class CotEndpointDispatcher(
    private val sendTak: (xml: String, addToQueue: Boolean) -> Unit,
    private val sendSitx: (xml: String) -> Unit,
    private val sendDitto: (collection: String, xml: String) -> Unit,
    private val sendMulticast: (xml: String) -> Unit,
    private val sendPliMulticast: (xml: String) -> Unit,
    private val sendCompanion: suspend (payload: CotCompanionPayload) -> Unit,
    private val sendPlugin: (xml: String) -> Unit = {},
) {
    private val emergencyMutex = Mutex()
    /**
     * An emergency is delivered once per uid, state and route. Newer fixes do not resend it:
     * receivers (TAK Server bridges, ATAK) treat every copy as a new alert notification.
     * The no-fix GeoChat notice and the positional alert are tracked separately so the
     * positional alert still goes out once GPS becomes available.
     */
    private data class DeliveryKey(
        val uid: String, val state: DataRepository.AlertState, val route: CotRoute, val positional: Boolean,
    )
    private val emergencyDeliveries = object : LinkedHashMap<DeliveryKey, Unit>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DeliveryKey, Unit>?) = size > 1024
    }

    private suspend fun deliver(message: CotOutboundMessage, route: CotRoute, send: suspend () -> Boolean): Boolean {
        val snapshot = message.emergencyDelivery
            ?: (message.companionPayload as? CotCompanionPayload.Emergency)?.snapshot
            ?: return send()
        return emergencyMutex.withLock {
            val positional = message.kind == CotMessageKind.EMERGENCY && snapshot.location != null
            val key = DeliveryKey(snapshot.uid, snapshot.alertState, route, positional)
            if (key in emergencyDeliveries) return@withLock true
            send().also { accepted -> if (accepted) emergencyDeliveries[key] = Unit }
        }
    }

    suspend fun dispatchGateway(message: CotOutboundMessage, send: suspend () -> Boolean): Boolean =
        deliver(message, CotRoute.COMPANION, send)

    suspend fun dispatch(
        message: CotOutboundMessage,
        availability: CotEndpointAvailability,
        readyPliOnly: Boolean = false
    ): Boolean {
        val routes = CotDispatchPolicy.routesFor(message.kind, availability).filter { route ->
            if (!readyPliOnly || message.kind != CotMessageKind.PLI) true else when (route) {
                CotRoute.TAK -> availability.tak
                CotRoute.SITX -> availability.sitx
                CotRoute.MULTICAST -> availability.multicast
                CotRoute.COMPANION -> availability.companion
                CotRoute.PLUGIN -> availability.plugin
                CotRoute.DITTO -> false
            }
        }
        routes.forEach { route ->
            deliver(message, route) {
                when (route) {
                    CotRoute.TAK -> sendTak(message.xml, message.kind != CotMessageKind.PLI)
                    CotRoute.SITX -> sendSitx(message.xml)
                    CotRoute.DITTO -> sendDitto(
                        if (message.kind == CotMessageKind.CHAT) "chat" else "mapitem",
                        message.xml
                    )
                    CotRoute.MULTICAST -> if (message.kind == CotMessageKind.PLI) {
                        sendPliMulticast(message.xml)
                    } else {
                        sendMulticast(message.xml)
                    }
                    CotRoute.COMPANION -> sendCompanion(message.companionPayload)
                    CotRoute.PLUGIN -> sendPlugin(message.xml)
                }
                true
            }
        }
        return routes.isNotEmpty()
    }
}
