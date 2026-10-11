package com.aitech.weartak_civ.cot

import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.service.BatteryService.DockType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CotEndpointDispatcherTest {
    @Test
    fun deletionNeverCallsExternalDispatchOrLegacyCompanionCotWithEveryTransportAvailable() = runBlocking {
        val calls = mutableListOf<String>()
        val dispatcher = CotEndpointDispatcher(
            sendTak = { _, _ -> calls += "TAK" },
            sendSitx = { calls += "SITX" },
            sendDitto = { _, _ -> calls += "Ditto" },
            sendMulticast = { calls += "multicast" },
            sendPliMulticast = { calls += "PLI multicast" },
            sendCompanion = { calls += "legacy companion CoT" },
            sendPlugin = { calls += "plugin" },
        )
        listOf(false, true).forEach { ready ->
            val sent = dispatcher.dispatch(
                CotOutboundMessage("<deletion-must-not-be-sent/>", CotMessageKind.MARKER_CLEAR,
                    CotCompanionPayload.Chat(chatSnapshot())),
                allAvailable.copy(companion = ready, plugin = true))
            assertEquals(false, sent)
            assertTrue(calls.isEmpty())
        }
    }
    @Test
    fun dispatchesChatOnlyToReadyCompanion() = runBlocking {
        val calls = mutableListOf<String>()
        val dispatcher = dispatcher(calls)
        val snapshot = chatSnapshot()

        val sent = dispatcher.dispatch(
            CotOutboundMessage("<chat/>", CotMessageKind.CHAT, CotCompanionPayload.Chat(snapshot)),
            allAvailable,
        )

        assertTrue(sent)
        assertEquals(
            listOf("companion:Chat"),
            calls,
        )
    }

    @Test
    fun chatUsesDirectRoutesWhenCompanionIsDisconnected() = runBlocking {
        val calls = mutableListOf<String>()
        val dispatcher = dispatcher(calls)
        dispatcher.dispatch(
            CotOutboundMessage("<chat/>", CotMessageKind.CHAT, CotCompanionPayload.Chat(chatSnapshot())),
            allAvailable.copy(companion = false),
        )
        assertEquals(
            listOf("multicast:<chat/>", "ditto:mapitem:<chat/>", "tak:true:<chat/>", "sitx:<chat/>"),
            calls,
        )
    }

    @Test
    fun pliUsesOnlyTheReadyCompanionRoute() = runBlocking {
        val calls = mutableListOf<String>()
        val dispatcher = dispatcher(calls)
        val snapshot = pliSnapshot()

        dispatcher.dispatch(
            CotOutboundMessage("<pli/>", CotMessageKind.PLI, CotCompanionPayload.Pli(snapshot)),
            CotEndpointAvailability(tak = true, sitx = true, ditto = true, multicast = true, companion = true),
        )

        assertEquals(listOf("companion:Pli"), calls)
    }

    private fun dispatcher(calls: MutableList<String>) = CotEndpointDispatcher(
        sendTak = { xml, queue -> calls += "tak:$queue:$xml" },
        sendSitx = { xml -> calls += "sitx:$xml" },
        sendDitto = { collection, xml -> calls += "ditto:$collection:$xml" },
        sendMulticast = { xml -> calls += "multicast:$xml" },
        sendPliMulticast = { xml -> calls += "pliMulticast:$xml" },
        sendCompanion = { payload -> calls += "companion:${payload::class.simpleName}" },
    )

    private fun pliSnapshot() = DataRepository.PliSnapshot(location(), time(), physio(), system())
    private fun chatSnapshot() = DataRepository.ChatEventSnapshot("ignored", "room", "Room", "Hello", time(), location(), physio(), system())
    private fun location() = DataRepository.LocationSnapshot(0, 41.0, -87.0, 100.0, 4f, 5f, 45f, 3f)
    private fun time() = CotEventTime(0, 0, 0, START, START, STALE)
    private fun physio() = DataRepository.PhysioSnapshot(0, DataRepository.PhysioData(72, 98.6f, 30, 70, 60, .25, null, null))
    private fun system() = DataRepository.SystemSnapshot(0, 80, "EAGLE", "watch-1", "1", 10, DataRepository.SystemStates(0, DockType.NONE, false, true, 10, 10))

    private val allAvailable = CotEndpointAvailability(true, true, true, true, true)

    private companion object {
        const val START = "2026-08-19T12:00:00.000Z"
        const val STALE = "2026-08-19T12:00:30.000Z"
    }
}
