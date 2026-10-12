package com.aitech.weartak_civ.cot

import android.app.Application
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.TakChatEvent
import com.aitech.weartak_civ.presentation.MainActivity
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GroupChatIntegrationTest {
    private fun xml(sender: String = "alice", id: String = "m1", room: String = "team",
                    members: String = "uid1='watch' uid2='bob'", to: String = "",
                    title: String = "Team &amp; Friends") =
        """<event version='2.0' uid='GeoChat.$sender.$room.$id' type='b-t-f'
            time='2026-10-11T12:00:00Z' start='2026-10-11T12:00:00Z' stale='2026-10-11T12:15:00Z'>
            <point lat='0' lon='0' hae='0' ce='9999999' le='9999999'/>
            <detail><__chat id='$room' chatroom='$title' senderCallsign='$sender' messageId='$id'>
            <chatgrp id='$room' uid0='$sender' $members/></__chat>
            <remarks $to time='2026-10-11T12:00:00Z'>Hello &amp; welcome</remarks></detail></event>"""

    private fun parse(raw: String, now: Long = 100) =
        CotMessageParser.parseChatRooms(raw, "watch", "WATCH", "server", now)

    private fun repo(): DataRepository {
        MainActivity.device_uid = "watch"
        MainActivity.versionNumber = "test"
        return DataRepository(RuntimeEnvironment.getApplication())
    }

    private fun elements(raw: String, tag: String): List<Element> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader(raw))).getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test fun standaloneGroupPassesServerIngestionMergesSendersAndRoutesReplyToMembers() = runBlocking {
        val rooms = mutableListOf<ChatRoom>()
        val sink = object : TakServerCotSink {
            override suspend fun onChat(event: TakChatEvent) { rooms += event.parsedRooms.orEmpty() }
            override suspend fun onGeofence(geofence: com.aitech.weartak_civ.data.model.GeofenceCot) {}
            override suspend fun onUser(event: CotUserPositionEvent, envelope: TakServerCotEnvelope) {}
            override suspend fun onMarker(event: CotMarkerEvent, envelope: TakServerCotEnvelope) {}
            override suspend fun onEmergency(event: CotEmergencyEvent, envelope: TakServerCotEnvelope) {}
            override suspend fun onChannelChanged(envelope: TakServerCotEnvelope) {}
        }
        val ingestor = TakServerCotIngestor(sink, { true }, { false }, { "watch" }, { "WATCH" })
        assertEquals(TakServerCotIngestResult.CHAT, ingestor.ingest(TakServerCotEnvelope("server", "server.example", 100, xml())))
        assertEquals(TakServerCotIngestResult.CHAT, ingestor.ingest(TakServerCotEnvelope(
            "server", "server.example", 200, xml("bob", "m2", members = "uid1='watch' uid2='alice'"))))
        var room = ChatInboxMerge.merge(null, rooms.first()).copy(newMessagesPresent = false, timeLastClosed = 150)
        room = ChatInboxMerge.merge(room, rooms.last())
        assertEquals("team", room.uid)
        assertEquals("Team & Friends", room.title)
        assertTrue(room.isGroup)
        assertEquals(listOf("alice", "bob"), room.messageList.map { it.uid })
        assertEquals(150L, room.timeLastClosed)
        assertEquals(listOf(100L, 200L), room.messageList.map { it.timeReceived })
        assertTrue(room.newMessagesPresent)
        assertEquals(room, ChatInboxMerge.merge(room, rooms.first().copy(title = "Old group title",
            participantUids = listOf("alice", "watch", "removed-member"))))
        val repository = repo()
        repository.setChatRooms(listOf(room))
        val snapshot = repository.snapshotChatEvent(room.uid, room.title, "Reply & thanks")
        assertTrue(snapshot.isGroup)
        val reply = CotMessageBuilder.chat(snapshot, "WATCH", "watch", "reply", 1000)
        assertEquals(setOf("alice", "bob"), elements(reply, "dest").map { it.getAttribute("uid") }.toSet())
        assertTrue(elements(reply, "dest").all { !it.hasAttribute("callsign") })
        assertEquals("team", elements(reply, "__chat").single().getAttribute("id"))
        assertEquals("Team & Friends", elements(reply, "__chat").single().getAttribute("chatroom"))
        val recipient = CotMessageParser.parseChatRooms(reply, "bob", "BOB").single()
        assertEquals("team", recipient.uid)
        assertEquals("Reply & thanks", recipient.messageList.single().message)
        assertTrue(CotMessageParser.parseChatRooms(reply, "outsider", "OTHER").isEmpty())
        var sentXml = ""
        val dispatcher = CotEndpointDispatcher(
            sendTak = { raw, _ -> sentXml = raw }, sendSitx = {}, sendDitto = { _, _ -> },
            sendMulticast = {}, sendPliMulticast = {}, sendCompanion = {}
        )
        assertTrue(dispatcher.dispatch(CotOutboundMessage(reply, CotMessageKind.CHAT, CotCompanionPayload.Chat(snapshot)),
            CotEndpointAvailability(true, false, false, false, false)))
        assertEquals(reply, sentXml)
    }

    @Test fun privacyEchoAndMalformedGroupFiltersUseRecipientUidsNotRoomTitle() {
        assertTrue(parse(xml(members = "uid1='outsider' uid2='bob'")).isEmpty())
        assertFalse(CotMessageParser.isChatForRecipient(xml(members = "uid1='outsider'"), "watch", "WATCH"))
        assertTrue(parse(xml(sender = "watch")).isEmpty())
        assertTrue(parse(xml(room = "bob", members = "uid1='bob'", to = "to='bob'", title = "Bob")).isEmpty())
        assertTrue(parse(xml(title = "")).isEmpty())
        assertTrue(parse(xml().replace("id='team' uid0", "id='other' uid0")).isEmpty())
        assertTrue(parse(xml().replace("</event>", "")).isEmpty())
    }

    @Test fun allChatRoomsIsOneExplicitBroadcastRoomAndDirectRemainsPrivate() = runBlocking {
        val first = parse(xml(room = "All Chat Rooms", members = "uid1='All Chat Rooms'")).single()
        val second = parse(xml("bob", "m2", "All Chat Rooms", "uid1='All Chat Rooms'")).single()
        val broadcast = ChatInboxMerge.merge(first, second)
        assertEquals("All Chat Rooms", broadcast.uid)
        assertEquals(2, broadcast.messageList.size)
        assertTrue(broadcast.isGroup)
        val repository = repo()
        repository.setChatRooms(listOf(broadcast))
        val reply = CotMessageBuilder.chat(repository.snapshotChatEvent(broadcast.uid, broadcast.title, "All"), "WATCH", "watch")
        assertTrue(elements(reply, "dest").isEmpty())
        assertEquals("All Chat Rooms", elements(reply, "remarks").single().getAttribute("to"))
        assertEquals("All Chat Rooms", CotMessageParser.parseChatRooms(reply, "anyone", "ANYONE").single().uid)
        val direct = parse(xml(room = "watch", members = "uid1='watch'", to = "to='watch'", title = "WATCH")).single()
        assertEquals("alice", direct.uid)
        assertFalse(direct.isGroup)
    }

    @Test fun namedGroupWithNoRecipientsFailsRatherThanBroadcasting() = runBlocking<Unit> {
        val repository = repo()
        repository.setChatRooms(listOf(ChatRoom("team", "Team", emptyList(), false, 0, 0, isGroup = true)))
        val snapshot = repository.snapshotChatEvent("team", "Team", "No leak")
        assertThrows(IllegalArgumentException::class.java) { CotMessageBuilder.chat(snapshot, "WATCH", "watch") }
    }

    @Test fun outgoingRequestRetainsGroupRoutingWhenInboxChangesBeforeDispatch() = runBlocking {
        val repository = repo()
        val room = parse(xml()).single()
        repository.setChatRooms(listOf(room))
        val captured = async(start = CoroutineStart.UNDISPATCHED) { repository.chatRequestSend.first() }
        repository.sendChatMessage("team", room.title, "Pending", 123)
        val request = captured.await()
        assertTrue(request.isGroup)
        assertEquals(room.participantUids, request.participantUids)
        repository.setChatRooms(emptyList())
        val snapshot = repository.snapshotChatEvent(request.dstUid, request.dstChatroom, request.message,
            request.isGroup, request.participantUids)
        val reply = CotMessageBuilder.chat(snapshot, "WATCH", "watch")
        assertEquals(setOf("alice", "bob"), elements(reply, "dest").map { it.getAttribute("uid") }.toSet())
        assertEquals("team", elements(reply, "__chat").single().getAttribute("id"))
    }

    @Test fun companionGroupEnvelopeMergesSendersAndReplySnapshotKeepsGroupTarget() = runBlocking {
        fun incoming(sender: String, now: Long): ChatRoom {
            val envelope = Gson().fromJson(
                """{"vers":1,"msgType":"chat_received","payload":{"uid":"same-id","senderUid":"$sender",
                  "senderCallsign":"$sender","msg":"Hello","receivedAt":$now,"conversationUid":"team",
                  "conversationTitle":"Team","isGroup":true,"participantUids":["phone","alice","bob"]}}""",
                CompanionAppRepository.ChatReceivedEnvelope::class.java
            )
            return CompanionChatIngestor.room(envelope.payload, 300)
        }
        val first = incoming("alice", 100)
        val second = incoming("bob", 200)
        val room = ChatInboxMerge.merge(ChatInboxMerge.merge(null, first), second)
        assertEquals(2, room.messageList.size)
        val read = room.copy(newMessagesPresent = false, timeLastClosed = 250)
        assertEquals(read, ChatInboxMerge.merge(read, second))
        val repository = repo()
        repository.setChatRooms(listOf(room))
        val snapshot = repository.snapshotChatEvent(room.uid, room.title, "Group reply")
        val cap = CompanionAppRepository(RuntimeEnvironment.getApplication())
        val envelope = cap.buildChatEnvelopeFromSnapshot(snapshot)
        assertEquals("team", envelope.payload.rmUid)
        assertEquals("Team", envelope.payload.rmTitle)
        assertTrue(envelope.payload.isGroup)
        var sent: CotCompanionPayload? = null
        val dispatcher = CotEndpointDispatcher(
            sendTak = { _, _ -> fail("Companion chat must not also go to TAK") },
            sendSitx = { fail("No standalone send") }, sendDitto = { _, _ -> fail("No standalone send") },
            sendMulticast = { fail("No multicast send") }, sendPliMulticast = {},
            sendCompanion = { sent = it }
        )
        assertTrue(dispatcher.dispatch(CotOutboundMessage(CotMessageBuilder.chat(snapshot, "WATCH", "watch"),
            CotMessageKind.CHAT, CotCompanionPayload.Chat(snapshot)),
            CotEndpointAvailability(true, false, false, true, true)))
        assertEquals(snapshot, (sent as CotCompanionPayload.Chat).snapshot)
    }

    @Test fun legacyCompanionMetadataWithoutExplicitGroupFlagStaysDirectAndInvalidGroupFails() {
        val legacy = CompanionAppRepository.ChatReceivedPayload("m", "alice", "Alice", "Hi", 100,
            conversationUid = "phone", conversationTitle = "PHONE")
        assertEquals("alice", CompanionChatIngestor.room(legacy, 200).uid)
        assertFalse(CompanionChatIngestor.room(legacy.copy(conversationUid = "other"), 200).isGroup)
        listOf(legacy.copy(isGroup = true, conversationUid = null),
            legacy.copy(isGroup = true, conversationTitle = ""),
            legacy.copy(isGroup = true, conversationUid = "alice")).forEach {
            assertThrows(IllegalArgumentException::class.java) { CompanionChatIngestor.room(it, 200) }
        }
    }
}
