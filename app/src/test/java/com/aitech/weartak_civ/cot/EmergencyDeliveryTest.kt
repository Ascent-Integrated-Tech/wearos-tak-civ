package com.aitech.weartak_civ.cot

import com.aitech.weartak_civ.data.DataRepository
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class EmergencyDeliveryTest {
    private fun message(
        uid: String = "911", state: DataRepository.AlertState = DataRepository.AlertState.ALERT,
        locationTime: Long = 1000,
    ): CotOutboundMessage {
        val snapshot = DataRepository.EmergencyEventSnapshot(
            uid, state, "Alert", "911", 1, false,
            CotEventTime(0, 0, 0, "", "", ""),
            DataRepository.LocationSnapshot(locationTime, 41.0, -87.0, 100.0, 5f, 5f, null, null),
            null, mockk(relaxed = true),
        )
        return CotOutboundMessage("xml", CotMessageKind.EMERGENCY, CotCompanionPayload.Emergency(snapshot))
    }

    @Test fun eighteenConcurrentRetriesAndNewerFixesSendOncePerRouteButCancelStillSends() = runTest {
        val sent = mutableListOf<CotRoute>()
        val dispatcher = dispatcher(sent)
        val standalone = CotEndpointAvailability(true, true, true, true, false, plugin = true)
        (1..18).map { async { dispatcher.dispatch(message(), standalone) } }.awaitAll()
        assertEquals(5, sent.size)
        assertEquals(5, sent.toSet().size)
        // A refined fix ~1 s later must not produce a duplicate alert at receivers.
        dispatcher.dispatch(message(locationTime = 2000), standalone)
        assertEquals(5, sent.size)
        dispatcher.dispatch(message(locationTime = 1000), standalone)
        assertEquals(5, sent.size)
        dispatcher.dispatch(message(state = DataRepository.AlertState.CANCEL, locationTime = 2000), standalone)
        assertEquals(10, sent.size)
        dispatcher.dispatch(message(state = DataRepository.AlertState.CANCEL, locationTime = 3000), standalone)
        assertEquals(10, sent.size)
        dispatcher.dispatch(message(uid = "second"), standalone)
        assertEquals(15, sent.size)
        dispatcher.dispatch(message(), standalone.copy(companion = true))
        assertEquals(1, sent.count { it == CotRoute.COMPANION })
        dispatcher.dispatch(message(locationTime = 5000), standalone.copy(companion = true))
        assertEquals(1, sent.count { it == CotRoute.COMPANION })
    }

    @Test fun noFixNoticeDoesNotSuppressLaterPositionalAlert() = runTest {
        val sent = mutableListOf<CotRoute>()
        val dispatcher = dispatcher(sent)
        val routes = CotEndpointAvailability(true, false, false, false, false)
        val positional = message()
        val snapshot = (positional.companionPayload as CotCompanionPayload.Emergency).snapshot.copy(location = null)
        val notice = CotOutboundMessage("chat", CotMessageKind.CHAT, mockk(relaxed = true), emergencyDelivery = snapshot)
        dispatcher.dispatch(notice, routes)
        dispatcher.dispatch(notice, routes)
        assertEquals(1, sent.size)
        dispatcher.dispatch(positional, routes)
        dispatcher.dispatch(message(locationTime = 2000), routes)
        assertEquals(2, sent.size)
    }

    @Test fun partiallyFailedFanoutDoesNotReplayRoutesAlreadyAccepted() = runTest {
        val sent = mutableListOf<CotRoute>()
        var failTak = true
        val dispatcher = dispatcher(sent) {
            if (failTak) throw IOException("transport unavailable")
        }
        val routes = CotEndpointAvailability(true, false, false, true, false, plugin = true)
        try {
            dispatcher.dispatch(message(), routes)
            fail("Expected transport failure")
        } catch (_: IOException) { }
        assertEquals(listOf(CotRoute.PLUGIN, CotRoute.MULTICAST), sent)
        failTak = false
        dispatcher.dispatch(message(), routes)
        assertEquals(listOf(CotRoute.PLUGIN, CotRoute.MULTICAST, CotRoute.TAK), sent)
    }

    @Test fun gatewayDoesNotRememberRejectedQueueAndDedupesAcceptedEmergency() = runTest {
        val dispatcher = dispatcher(mutableListOf())
        var attempts = 0
        assertFalse(dispatcher.dispatchGateway(message()) { attempts++; false })
        assertTrue(dispatcher.dispatchGateway(message()) { attempts++; true })
        assertTrue(dispatcher.dispatchGateway(message()) { attempts++; true })
        assertEquals(2, attempts)
    }

    private fun dispatcher(sent: MutableList<CotRoute>, beforeTak: () -> Unit = {}) = CotEndpointDispatcher(
        sendTak = { _, _ -> beforeTak(); sent += CotRoute.TAK },
        sendSitx = { sent += CotRoute.SITX },
        sendDitto = { _, _ -> sent += CotRoute.DITTO },
        sendMulticast = { sent += CotRoute.MULTICAST },
        sendPliMulticast = { error("Unexpected PLI") },
        sendCompanion = { sent += CotRoute.COMPANION },
        sendPlugin = { sent += CotRoute.PLUGIN },
    )
}
