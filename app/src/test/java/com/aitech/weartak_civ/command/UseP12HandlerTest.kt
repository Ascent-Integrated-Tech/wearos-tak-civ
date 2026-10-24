package com.aitech.weartak_civ.command

import com.aitech.weartak_civ.data.takserver.TestClientP12
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class UseP12HandlerTest {
    private val request = ChatCommandRequest("sender", "ALPHA", "", "id", 1, 1, "ditto:peer")
    private fun hex(base64: String) = Base64.getDecoder().decode(base64)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun positionalGrammarRequiresExplicitTargetAndSupportsQuotedPasswordWithoutEchoing() = runBlocking {
        val base64 = TestClientP12.p12("test password")
        val encoded = hex(base64)
        val resultParse = ChatCommandParser.parse(
            "#weartakcommandusep12 $encoded server=server-1 password=\"test password\""
        )
        assertTrue(resultParse.toString(), resultParse is ChatCommandParse.Parsed)
        val parsed = resultParse as ChatCommandParse.Parsed
        assertEquals("server-1", parsed.command.args["server"])
        assertEquals("test password", parsed.command.args["password"])
        var installed = 0
        val handler = UseP12Handler({ false }, { it == "server-1" }) { id, p12 ->
            assertEquals("server-1", id)
            assertEquals(base64, p12.base64)
            assertFalse(p12.toString().contains("test password"))
            installed++
            true
        }
        val result = handler.execute(parsed.command, request)
        assertTrue(result.ok)
        assertTrue(result.message.startsWith("P12 installed: sha256="))
        assertFalse(result.message.contains(encoded))
        assertFalse(result.message.contains("test password"))
        assertEquals(1, installed)
        assertFalse(parsed.command.toString().contains(encoded))
        assertFalse(handler.execute(ChatCommand("usep12", mapOf("p12" to encoded)), request).ok)
        assertFalse(handler.execute(parsed.command.copy(args = parsed.command.args + ("server" to "unknown")), request).ok)
        assertEquals(1, installed)
    }

    @Test fun hexAndBase64ValidateWithDefaultPasswordButMalformedExpiredAndWrongPasswordDoNot() {
        val base64 = TestClientP12.p12("atakatak")
        assertEquals(ChatP12Validator.validate(base64, "atakatak").fingerprint,
            ChatP12Validator.validate(hex(base64).uppercase(), "atakatak").fingerprint)
        val expired = TestClientP12.p12("atakatak", System.currentTimeMillis() - 172800000L)
        listOf(base64 to "wrong", expired to "atakatak", "00" to "atakatak",
            "ab".repeat(ChatP12Validator.MAX_BYTES + 1) to "atakatak").forEach { (value, password) ->
            assertTrue(runCatching { ChatP12Validator.validate(value, password) }.isFailure)
        }
    }

    @Test fun parserBoundsAndInvalidOptionsAreSecretFree() {
        listOf(
            "#weartakcommandusep12",
            "#weartakcommandusep12 ab server=x extra=value",
            "#weartakcommandusep12 ab server=x server=y",
            "#weartakcommandusep12 ab password=\"unterminated",
            "#weartakcommandusep12 ${"ab".repeat(8193)} server=x"
        ).forEach {
            val result = ChatCommandParser.parse(it)
            assertTrue(result is ChatCommandParse.Malformed)
            assertFalse(result.toString().contains("unterminated"))
        }
    }

    @Test fun locksAndFailedApplyNeverClaimInstallation() = runBlocking {
        val encoded = TestClientP12.p12("atakatak")
        val command = ChatCommand("usep12", mapOf("p12" to encoded, "server" to "one"))
        val locked = UseP12Handler({ true }, { true }) { _, _ -> error("must not install") }
        assertFalse(locked.execute(command, request).ok)
        val failed = UseP12Handler({ false }, { true }) { _, _ -> false }
        assertEquals("P12 not changed: apply failed", failed.execute(command, request).message)
    }
}
