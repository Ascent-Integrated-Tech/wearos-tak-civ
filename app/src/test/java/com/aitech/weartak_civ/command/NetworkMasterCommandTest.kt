package com.aitech.weartak_civ.command

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NetworkMasterCommandTest {
    private val now = 1_000_000L
    private fun request(name: String, source: String = "server", id: String = name) =
        ChatCommandRequest("sender", "ALPHA", "#weartakcommand $name", id, now, now, source)

    @Test fun fourZeroArgumentFormsAcceptJoinedAndSpacedPrefixButRejectArguments() {
        NetworkMasterCommandHandler.COMMANDS.forEach { name ->
            listOf("", " ", "\t").forEach { separator ->
                assertEquals(ChatCommandParse.Parsed(ChatCommand(name, emptyMap())),
                    ChatCommandParser.parse("#weartakcommand$separator$name"))
            }
            listOf("on", "state=on", "enabled=true").forEach { args ->
                assertTrue(ChatCommandParser.parse("#weartakcommand $name $args") is ChatCommandParse.Malformed)
            }
        }
        listOf("setcallsign callsign=ALPHA", "toggleditto off", "setpdl community",
            "usep12 ab server=test").forEach {
            assertEquals(ChatCommandParser.parse("#weartakcommand$it"),
                ChatCommandParser.parse("#weartakcommand $it"))
        }
    }

    @Test fun enableIsExplicitIdempotentAndUsesTheRequestedMaster() = runBlocking {
        val applied = mutableListOf<Pair<Boolean, Boolean>>()
        val handler = NetworkMasterCommandHandler({ null }, { tak, enabled -> applied += tak to enabled; null })
        listOf("enabletakservers", "enablemulticast").forEach { name ->
            repeat(2) { assertTrue(handler.execute(ChatCommand(name, emptyMap()), request(name)).ok) }
        }
        assertEquals(listOf(true to true, true to true, false to true, false to true), applied)
        assertFalse(handler.execute(ChatCommand("enabletakservers", mapOf("x" to "1")), request("x")).ok)
        assertEquals(4, applied.size)
    }

    @Test fun disableWritesWarningBeforeApplyingOnOriginIncludingAlternateTransport() = runBlocking {
        for (name in listOf("disabletakservers", "disablemulticast")) {
            for (source in listOf("server", "multicast:239.2.3.1:6969", "ditto:peer")) {
                val order = mutableListOf<String>()
                val handler = NetworkMasterCommandHandler({ null }, { tak, enabled ->
                    assertEquals(name.endsWith("takservers"), tak)
                    assertFalse(enabled)
                    order += "apply"
                    null
                })
                val p = processor(handler, confirmed = { req, warning ->
                    assertEquals(source, req.sourceServerId)
                    assertTrue(warning.contains("Re-enable on the watch"))
                    assertTrue(warning.contains("delivery is not confirmed"))
                    order += "write"
                    true
                })
                p.handle(request(name, source))
                assertEquals(listOf("write", "apply"), order)
            }
        }
    }

    @Test fun falseOrThrowingWarningWriteNeverAppliesDisable() = runBlocking {
        for (throws in listOf(false, true)) {
            var applies = 0
            val logs = mutableListOf<String>()
            val handler = NetworkMasterCommandHandler({ null }, { _, _ -> applies++; null })
            val p = processor(handler, confirmed = { _, _ ->
                if (throws) throw java.io.IOException("write failed")
                false
            }, logs = logs)
            p.handle(request("disabletakservers"))
            p.handle(request("disablemulticast"))
            assertEquals(0, applies)
            assertEquals(2, logs.count { it.contains(if (throws) "deferred execution failed" else "warning write not accepted") })
            assertFalse(logs.any { it.endsWith(": ok") })
        }
    }

    @Test fun lockedInitialCheckAndPolicyRaceAfterWarningBothRefuseApply() = runBlocking {
        var reason: String? = "settings locked"
        var writes = 0
        var mutations = 0
        val outcomes = mutableListOf<String>()
        val handler = NetworkMasterCommandHandler({ reason }, { _, _ ->
            reason ?: run { mutations++; null }
        })
        val p = processor(handler, confirmed = { _, _ ->
            writes++
            reason = "explicit administrator policy"
            true
        }, replies = outcomes)
        p.handle(request("disabletakservers", id = "locked"))
        assertEquals(0, writes)
        reason = null
        p.handle(request("disabletakservers", id = "raced"))
        assertEquals(1, writes)
        assertEquals(0, mutations)
        assertTrue(outcomes.last().contains("explicit administrator policy"))
    }

    @Test fun allowlistDisabledFreshnessAndCrossTransportReplayUseExistingAuthorization() = runBlocking {
        var mutations = 0
        var warnings = 0
        val handler = NetworkMasterCommandHandler({ null }, { _, _ -> mutations++; null })
        val p = processor(handler, confirmed = { _, _ -> warnings++; true })
        val command = request("disabletakservers")
        p.handle(command.copy(senderCallsign = "EVE", messageId = "unlisted"))
        p.handle(command.copy(sentAtMs = 1, messageId = "stale"))
        p.handle(command.copy(messageId = null))
        assertEquals(0, mutations)
        p.handle(command)
        p.handle(command.copy(sourceServerId = "ditto:peer"))
        assertEquals(1, mutations)
        assertEquals(1, warnings)
        val disabled = processor(handler, enabled = false)
        assertFalse(disabled.handle(request("enablemulticast")))
        assertEquals(1, mutations)
    }

    @Test fun legacyAfterReplyStillRunsEvenWhenReplyThrows() = runBlocking {
        var legacyApplied = false
        val p = ChatCommandProcessor({ true }, { listOf("ALPHA") },
            mapOf("toggleditto" to ChatCommandHandler { _, _ ->
                ChatCommandResult(true, "legacy", afterReply = { legacyApplied = true })
            }),
            reply = { _, _ -> throw java.io.IOException("failed") }, audit = {}, now = { now })
        try {
            p.handle(request("toggleditto off"))
            fail("expected reply failure")
        } catch (_: java.io.IOException) {
            assertTrue(legacyApplied)
        }
    }

    private fun processor(
        handler: ChatCommandHandler,
        confirmed: suspend (ChatCommandRequest, String) -> Boolean = { _, _ -> true },
        logs: MutableList<String> = mutableListOf(),
        replies: MutableList<String> = mutableListOf(),
        enabled: Boolean = true
    ) = ChatCommandProcessor(
        { enabled }, { listOf("ALPHA") },
        NetworkMasterCommandHandler.COMMANDS.associateWith { handler },
        reply = { _, text -> replies += text }, audit = { logs += it }, now = { now },
        confirmedReply = confirmed
    )
}
