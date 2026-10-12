/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.command

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCommandProcessorTest {
    private val now = 1_000_000L
    private val replies = mutableListOf<String>()
    private val audits = mutableListOf<String>()
    private var enabled = true
    private var locked = false
    private val storedCallsigns = mutableListOf<String>()
    private val prompts = mutableListOf<ServerEnrollment>()

    private fun processor() = ChatCommandProcessor(
        isEnabled = { enabled },
        allowedCallsigns = { listOf("Alpha-1") },
        handlers = mapOf(
            "setcallsign" to SetCallsignHandler({ locked }, { storedCallsigns += it }),
            "enrollserver" to EnrollServerHandler({ locked }, { prompts += it })
        ),
        reply = { _, text -> replies += text },
        audit = { audits += it },
        now = { now }
    )

    private fun request(text: String, sender: String = "Alpha-1", id: String? = "m1") =
        ChatCommandRequest("uid", sender, text, id, now, now, "srv")

    @Test
    fun ordinaryChatAndDisabledFeatureAreNotConsumed() = runBlocking {
        val p = processor()
        assertFalse(p.handle(request("hello")))
        enabled = false
        assertFalse(p.handle(request("#weartakcommandsetcallsign callsign=X")))
        assertTrue(storedCallsigns.isEmpty() && replies.isEmpty())
    }

    @Test
    fun unlistedSenderIsSwallowedSilently() = runBlocking {
        assertTrue(processor().handle(request("#weartakcommandsetcallsign callsign=X", sender = "Eve")))
        assertTrue(storedCallsigns.isEmpty() && replies.isEmpty())
    }

    @Test
    fun setCallsignAppliesForAllowedSender() = runBlocking {
        assertTrue(processor().handle(request("#weartakcommandsetcallsign callsign=ODIN-2")))
        assertEquals(listOf("ODIN-2"), storedCallsigns)
        assertEquals(listOf("Callsign set to ODIN-2"), replies)
    }

    @Test
    fun replayedMessageRunsOnce() = runBlocking {
        val p = processor()
        p.handle(request("#weartakcommandsetcallsign callsign=ODIN-2"))
        p.handle(request("#weartakcommandsetcallsign callsign=ODIN-2"))
        assertEquals(1, storedCallsigns.size)
    }

    @Test
    fun invalidCallsignAndLockedSettingsAreRejected() = runBlocking {
        val p = processor()
        p.handle(request("#weartakcommandsetcallsign callsign=bad;name", id = "a"))
        locked = true
        p.handle(request("#weartakcommandsetcallsign callsign=OK", id = "b"))
        assertTrue(storedCallsigns.isEmpty())
        assertEquals(2, replies.size)
    }

    @Test
    fun enrollServerPromptsAndRefusesCredentialsInChat() = runBlocking {
        PendingServerEnrollment.clear()
        val p = processor()
        p.handle(request("#weartakcommandenrollserver name=\"TAK Ops\" url=cloud.tak-ops.com port=8089", id = "a"))
        assertEquals(1, prompts.size)
        assertEquals("TAK Ops", prompts[0].name)
        assertEquals("cloud.tak-ops.com", prompts[0].address)
        assertEquals(8089, prompts[0].port)

        p.handle(request("#weartakcommandenrollserver name=X url=h.com password=secret", id = "b"))
        assertEquals(1, prompts.size)
        assertFalse(replies.any { it.contains("secret") })
        assertFalse(audits.any { it.contains("secret") })
    }

    @Test
    fun enrollServerValidatesArguments() = runBlocking {
        val p = processor()
        p.handle(request("#weartakcommandenrollserver name=X url=https://h.com", id = "a"))
        p.handle(request("#weartakcommandenrollserver name=X url=h.com port=70000", id = "b"))
        p.handle(request("#weartakcommandenrollserver url=h.com", id = "c"))
        assertTrue(prompts.isEmpty())
        assertEquals(3, replies.size)
    }

    @Test
    fun unknownAndMalformedCommandsGetReplies() = runBlocking {
        val p = processor()
        p.handle(request("#weartakcommandreboot now=1", id = "a"))
        p.handle(request("#weartakcommandsetcallsign broken", id = "b"))
        assertEquals(2, replies.size)
        assertTrue(replies[0].startsWith("Unknown command"))
    }

    @Test
    fun pendingEnrollmentRequiresMatchingTokenAndIsSingleUse() {
        PendingServerEnrollment.clear()
        val e = PendingServerEnrollment.offer("N", "h.com", 8089)
        assertNull(PendingServerEnrollment.take("wrong"))
        assertNull(PendingServerEnrollment.take(null))
        assertNotNull(PendingServerEnrollment.take(e.token))
        assertNull(PendingServerEnrollment.take(e.token))
    }
}