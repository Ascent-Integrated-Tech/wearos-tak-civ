/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCommandTest {
    private fun parsed(text: String) = (ChatCommandParser.parse(text) as ChatCommandParse.Parsed).command
    private fun malformed(text: String) = ChatCommandParser.parse(text) is ChatCommandParse.Malformed

    @Test
    fun ordinaryChatIsNeverACommand() {
        assertEquals(ChatCommandParse.NotACommand, ChatCommandParser.parse("hello there"))
        assertEquals(ChatCommandParse.NotACommand, ChatCommandParser.parse("see #weartakcommandsetcallsign callsign=X"))
    }

    @Test
    fun parsesNameCaseInsensitivelyAndArguments() {
        val c = parsed("  #WearTAKCommandSetCallsign Callsign=ALPHA-1 ")
        assertEquals("setcallsign", c.name)
        assertEquals(mapOf("callsign" to "ALPHA-1"), c.args)
    }

    @Test
    fun parsesQuotedValuesWithSpacesAndEscapes() {
        val c = parsed("#weartakcommandenrollserver name=\"TAK \\\"Ops\\\"\" url=cloud.tak-ops.com port=8089")
        assertEquals("TAK \"Ops\"", c.args["name"])
        assertEquals("cloud.tak-ops.com", c.args["url"])
        assertEquals("8089", c.args["port"])
    }

    @Test
    fun commandWithoutArgumentsParses() {
        assertEquals(emptyMap<String, String>(), parsed("#weartakcommandping").args)
    }

    @Test
    fun onlyPdlCommandValueHasLargerBoundedLimit() {
        val value = "a".repeat(ChatCommandParser.MAX_PDL_LENGTH)
        assertEquals(value, parsed("#weartakcommandsetpdl $value").args["pdl"])
        assertEquals(mapOf("source" to "community"), parsed("#weartakcommandsetpdl community").args)
        assertTrue(malformed("#weartakcommandsetpdl ${value}a"))
        assertTrue(malformed("#weartakcommandsetpdl"))
        assertTrue(malformed("#weartakcommandsetpdl community enabled=true"))
        assertTrue(malformed("#weartakcommandsetpdl source=community"))
        assertTrue(malformed("#weartakcommandsetpdl pdl=abc"))
        assertTrue(malformed("#weartakcommandsetpdl \"abc\""))
        assertTrue(malformed("#weartakcommandsetcallsign pdl=$value"))
        assertTrue(malformed("#weartakcommandsetpdl x y"))
        assertTrue(malformed("#weartakcommandsetpdl $value ${"a".repeat(1100)}"))
        assertTrue(malformed("#weartakcommandsetdittopdl pdl=$value"))
    }

    @Test
    fun rejectsMalformedInput() {
        assertTrue(malformed("#weartakcommand"))
        assertTrue(malformed("#weartakcommand9 a=b"))
        assertTrue(malformed("#weartakcommandx novalue"))
        assertTrue(malformed("#weartakcommandx a="))
        assertTrue(malformed("#weartakcommandx a=1 a=2"))
        assertTrue(malformed("#weartakcommandx a=\"open"))
        assertTrue(malformed("#weartakcommandx a=\"q\"junk"))
        assertTrue(malformed("#weartakcommandx 1a=b"))
        assertTrue(malformed("#weartakcommandx a=${"v".repeat(129)}"))
        assertTrue(malformed("#weartakcommandx " + (1..13).joinToString(" ") { "k$it=v" }))
        assertTrue(malformed("#weartakcommandx " + "a=b ".repeat(200)))
        assertTrue(malformed("#weartakcommandx a=b\u0007c"))
    }

    @Test
    fun authorizerRequiresAllowedFreshUniqueSender() {
        val auth = ChatCommandAuthorizer(maxAgeMs = 1000)
        val allowed = listOf("Alpha-1", " BRAVO ")
        assertEquals(ChatCommandDecision.SENDER_NOT_ALLOWED, auth.authorize("Eve", allowed, "m1", 10_000, 10_000))
        assertEquals(ChatCommandDecision.SENDER_NOT_ALLOWED, auth.authorize("", allowed, "m1", 10_000, 10_000))
        assertEquals(ChatCommandDecision.SENDER_NOT_ALLOWED, auth.authorize("alpha-1", emptyList(), "m1", 10_000, 10_000))
        assertEquals(ChatCommandDecision.STALE, auth.authorize("alpha-1", allowed, "old", 1_000, 10_000))
        assertEquals(ChatCommandDecision.STALE, auth.authorize("alpha-1", allowed, "future", 20_000, 10_000))
        assertEquals(ChatCommandDecision.STALE, auth.authorize("alpha-1", allowed, "zero", 0, 10_000))
        assertEquals(ChatCommandDecision.ALLOWED, auth.authorize("ALPHA-1", allowed, "m1", 10_000, 10_000))
        assertEquals(ChatCommandDecision.DUPLICATE, auth.authorize("alpha-1", allowed, "m1", 10_000, 10_100))
        assertEquals(ChatCommandDecision.DUPLICATE, auth.authorize("bravo", allowed, "m1", 10_000, 10_100))
        assertEquals(ChatCommandDecision.ALLOWED, auth.authorize("bravo", allowed, "different-sender:m1", 10_000, 10_100))
    }

    @Test
    fun rejectedMessagesAreNotRememberedAsDuplicates() {
        val auth = ChatCommandAuthorizer(maxAgeMs = 1000)
        assertEquals(ChatCommandDecision.SENDER_NOT_ALLOWED, auth.authorize("eve", listOf("a"), "m", 10_000, 10_000))
        assertEquals(ChatCommandDecision.ALLOWED, auth.authorize("a", listOf("a"), "m", 10_000, 10_000))
    }

    @Test
    fun allowListParsingTrimsAndDeduplicates() {
        assertEquals(listOf("A", "b"), ChatCommandAuthorizer.parseAllowList(" A, b ,a,, "))
    }

    @Test
    fun allowListIsSortedCaseInsensitivelyAndDeduplicated() {
        assertEquals(
            listOf("Alpha", "BRAVO", "zulu"),
            ChatCommandAuthorizer.sortedAllowList(listOf("zulu", "Alpha", "BRAVO", "alpha", " bravo "))
        )
    }

    @Test
    fun newCallsignsUseTheCommandValidationRulesAndDisplayUppercase() {
        assertEquals("ODIN-1", ChatCommandAuthorizer.validatedCallsign("  odin-1  "))
        assertEquals("A B._-9", ChatCommandAuthorizer.validatedCallsign("a b._-9"))
        assertEquals("A".repeat(32), ChatCommandAuthorizer.validatedCallsign("a".repeat(32)))
        assertEquals(null, ChatCommandAuthorizer.validatedCallsign(""))
        assertEquals(null, ChatCommandAuthorizer.validatedCallsign("A".repeat(33)))
        assertEquals(null, ChatCommandAuthorizer.validatedCallsign("bad;name"))
    }

    @Test
    fun legacyCommaSeparatedCallsignsRemainAvailable() {
        val legacy = "ODIN-ATAK-S9FE,ODIN-ATAK"
        assertEquals(
            listOf("ODIN-ATAK-S9FE", "ODIN-ATAK"),
            ChatCommandAuthorizer.parseAllowList(legacy)
        )
        assertEquals(
            listOf("ODIN-ATAK", "ODIN-ATAK-S9FE"),
            ChatCommandAuthorizer.sortedAllowList(ChatCommandAuthorizer.parseAllowList(legacy))
        )
    }
}