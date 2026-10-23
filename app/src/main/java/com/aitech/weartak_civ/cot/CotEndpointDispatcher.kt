package com.aitech.weartak_civ.cot

import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private data class DeliveryKey(val uid: String, val state: DataRepository.AlertState, val route: CotRoute)
    private data class EmergencyVersion(
        val time: Long?, val lat: Double?, val lon: Double?,
        val category: String, val description: String, val priority: Int,
    )
    private val emergencyDeliveries = object : LinkedHashMap<DeliveryKey, EmergencyVersion>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DeliveryKey, EmergencyVersion>?) = size > 1024
    }

    private suspend fun deliver(message: CotOutboundMessage, route: CotRoute, send: suspend () -> Boolean): Boolean {
        val snapshot = message.emergencyDelivery
            ?: (message.companionPayload as? CotCompanionPayload.Emergency)?.snapshot
            ?: return send()
        return emergencyMutex.withLock {
            val key = DeliveryKey(snapshot.uid, snapshot.alertState, route)
            val version = EmergencyVersion(
                snapshot.location?.timeMillis, snapshot.location?.lat, snapshot.location?.lon,
                snapshot.category, snapshot.description, snapshot.priority,
            )
            val previous = emergencyDeliveries[key]
            if (previous == version ||
                (previous?.time != null && (version.time == null || previous.time > version.time))) return@withLock true
            send().also { accepted -> if (accepted) emergencyDeliveries[key] = version }
        }
    }

    suspend fun dispatchGateway(message: CotOutboundMessage, send: suspend () -> Boolean): Boolean =
        deliver(message, CotRoute.COMPANION, send)

    suspend fun dispatch(message: CotOutboundMessage, availability: CotEndpointAvailability): Boolean {
        val routes = CotDispatchPolicy.routesFor(message.kind, availability)
        routes.forEach { route ->
            deliver(message, route) {
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
                    CotRoute.PLUGIN -> sendPlugin(message.xml)
                }
                true
            }
        }
        return routes.isNotEmpty()
    }
}
