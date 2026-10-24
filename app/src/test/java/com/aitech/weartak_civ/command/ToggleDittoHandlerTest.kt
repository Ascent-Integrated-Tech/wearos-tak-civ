package com.aitech.weartak_civ.command

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ToggleDittoHandlerTest {
    private val request = ChatCommandRequest("sender", "ALPHA", "", "id", 1, 1, "ditto:peer")

    @Test fun explicitOnOffOnlyAndIdempotentIntentPreservesIdentity() = runBlocking {
        val applied = mutableListOf<Boolean>()
        val handler = ToggleDittoHandler({ false }) { applied += it; true }
        repeat(2) { handler.execute(ChatCommand("toggleditto", mapOf("state" to "on")), request) }
        assertEquals(listOf(true, true), applied)
        listOf("", "toggle", "true", "on off", "enabled=true").forEach {
            assertTrue(ChatCommandParser.parse("#weartakcommandtoggleditto $it") is ChatCommandParse.Malformed)
        }
        val result = handler.execute(ChatCommand("toggleditto", mapOf("state" to "off")), request)
        assertEquals(2, applied.size)
        assertTrue(result.message.contains("not confirmed"))
        result.afterReply!!.invoke()
        assertEquals(listOf(true, true, false), applied)
    }

    @Test fun processorAttemptsDittoOffReplyBeforeDisableEvenWhenAckRouteFails() = runBlocking {
        val order = mutableListOf<String>()
        val processor = ChatCommandProcessor({ true }, { listOf("ALPHA") },
            mapOf("toggleditto" to ToggleDittoHandler({ false }) { order += "off"; true }),
            reply = { _, _ -> order += "ack"; error("Unavailable") }, audit = {}, now = { 1 })
        assertTrue(runCatching {
            processor.handle(request.copy(text = "#weartakcommandtoggleditto off"))
        }.isFailure)
        assertEquals(listOf("ack", "off"), order)
    }
}
