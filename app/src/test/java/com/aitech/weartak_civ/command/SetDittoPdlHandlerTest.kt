package com.aitech.weartak_civ.command

import android.app.Application
import com.aitech.weartak_civ.data.DittoPdlSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SetDittoPdlHandlerTest {
    private val now = 1_000_000L
    private val appId = "12345678-1234-1234-1234-123456789abc"
    private var locked = false
    private var source = DittoPdlSource.COMMUNITY
    private var storedCustom: String? = null
    private var saves = 0
    private var applies = 0
    private var applyResult = true
    private val request = ChatCommandRequest("sender", "ALPHA", "", "id", now, now, "server")

    private fun pdl(expiry: Long = 2_000_000, edit: (JSONObject) -> Unit = {}): String {
        val json = JSONObject().put("v", 2).put("it", "SK").put("a", appId)
            .put("s", "synthetic-key").put("t", "synthetic-token").put("e", expiry)
        edit(json)
        val output = ByteArrayOutputStream()
        DeflaterOutputStream(output).use { it.write(json.toString().toByteArray()) }
        return Base64.getUrlEncoder().encodeToString(output.toByteArray())
    }

    private fun handler(community: String = pdl(), fail: Boolean = false) = SetDittoPdlHandler(
        isLocked = { locked }, communityPdl = { community },
        save = { selected, custom ->
            saves++
            source = selected
            if (custom != null) storedCustom = custom
        },
        apply = {
            applies++
            if (fail) throw IllegalArgumentException("synthetic-key synthetic-token")
            applyResult
        },
        now = { now }
    )

    private suspend fun execute(args: Map<String, String>, handler: SetDittoPdlHandler = handler()) =
        handler.execute(ChatCommand("setpdl", args), request)

    @Test fun customIdentityAndCommunityAreSavedBeforeRuntimeApply() = runBlocking {
        val custom = pdl()
        assertEquals(
            ChatCommandResult(true, "Ditto updated: source=custom app=12345678 expiry=1970-01-01T00:33:20Z"),
            execute(mapOf("pdl" to custom))
        )
        assertEquals(custom, storedCustom)
        assertEquals(DittoPdlSource.CUSTOM, source)
        assertEquals(1, saves)
        assertEquals(1, applies)
        assertTrue(execute(mapOf("source" to "community")).ok)
        assertEquals(custom, storedCustom)
        assertEquals(DittoPdlSource.COMMUNITY, source)
    }

    @Test fun contradictoryUnknownOrEnabledOnlyArgumentsChangeNothing() = runBlocking {
        listOf(
            emptyMap(), mapOf("enabled" to "true"), mapOf("source" to "custom"),
            mapOf("source" to "COMMUNITY"), mapOf("pdl" to pdl(), "source" to "community"),
            mapOf("source" to "community", "extra" to "x"),
            mapOf("source" to "community", "enabled" to "1")
        ).forEach { assertEquals("Ditto not changed: invalid arguments", execute(it).message) }
        assertEquals(0, saves)
        assertEquals(0, applies)
    }

    @Test fun malformedExpiredMissingFieldsAndUnsafeMetadataChangeNothing() = runBlocking {
        val invalid = listOf("", "bad", pdl(now), pdl(1),
            pdl { it.remove("s") }, pdl { it.remove("t") }, pdl { it.put("it", "other") },
            pdl { it.put("a", "synthetic-secret") }, pdl { it.put("v", 3) })
        invalid.forEach {
            assertEquals("Ditto not changed: invalid or expired identity", execute(mapOf("pdl" to it)).message)
        }
        assertFalse(execute(mapOf("source" to "community"), handler(pdl(1))).ok)
        assertEquals(0, saves)
        assertEquals(0, applies)
    }

    @Test fun lockedAndRuntimeFailureRepliesNeverClaimSuccessOrExposeCredentials() = runBlocking {
        locked = true
        assertEquals("Ditto not changed: settings locked", execute(mapOf("pdl" to pdl())).message)
        assertEquals(0, saves)
        locked = false
        applyResult = false
        assertEquals(ChatCommandResult(false, "Ditto update failed"), execute(mapOf("pdl" to pdl())))
        assertEquals(ChatCommandResult(false, "Ditto update failed"),
            execute(mapOf("pdl" to pdl()), handler(fail = true)))
    }

    @Test fun processorAuthorizationReplayAndAuditRemainCredentialFree() = runBlocking {
        val replies = mutableListOf<String>()
        val audit = mutableListOf<String>()
        val processor = ChatCommandProcessor(
            isEnabled = { true }, allowedCallsigns = { listOf("ALPHA") },
            handlers = mapOf("setpdl" to handler()), reply = { _, text -> replies += text },
            audit = audit::add, now = { now }
        )
        val secret = pdl()
        val command = request.copy(text = "#weartakcommandsetpdl $secret")
        processor.handle(command.copy(senderCallsign = "OTHER"))
        assertEquals(0, saves)
        processor.handle(command.copy(sentAtMs = now - 600_000))
        assertEquals(0, saves)
        processor.handle(command)
        processor.handle(command)
        assertEquals(1, saves)
        assertEquals(1, replies.size)
        assertFalse((replies + audit + command.toString() + ChatCommand("setpdl", mapOf("pdl" to secret))).any {
            it.toString().contains(secret) || it.toString().contains("synthetic-key") || it.toString().contains("synthetic-token")
        })
    }

    @Test fun cancellationPropagatesWithoutSuccessReply() = runBlocking {
        val handler = SetDittoPdlHandler({ false }, { pdl() }, { _, _ -> },
            { throw CancellationException() }, { now })
        try {
            execute(mapOf("source" to "community"), handler)
            fail("Cancellation must propagate")
        } catch (expected: CancellationException) {
            assertTrue(true)
        }
    }
}
