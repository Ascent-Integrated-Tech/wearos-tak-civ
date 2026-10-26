package com.aitech.weartak_civ.service

import android.app.Application
import android.util.Log
import com.aitech.weartak_civ.cot.TakServerCotEnvelope
import com.aitech.weartak_civ.cot.TakServerCotIngestResult
import com.aitech.weartak_civ.data.model.TakChannel
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.every
import io.mockk.mockk
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TakIngressLoggingTest {
    private val tag = "WearTAK-TAKConnectService"

    @Before fun prepareServiceContext() {
        val app = RuntimeEnvironment.getApplication()
        MainActivity.mainActivity = mockk { every { applicationContext } returns app }
    }

    private fun envelope(uid: String = "fixture") = TakServerCotEnvelope(
        sourceServerId = "server-fixture",
        sourceServerAddress = "example.invalid",
        receivedAt = 1234L,
        rawXml = """<event uid="$uid" type="a-f-G-U-C"></event>""",
    )

    @Test fun sensitiveEnrollmentHasNoPayloadHashOrDebugProjectionBeforeDispatch() = runBlocking {
        ShadowLog.setLoggable(tag, Log.DEBUG)
        val sensitive = envelope().copy(rawXml =
            "<event><remarks>#weartakcommand enrollserver Ops,host,8089,u,fake-secret</remarks></event>")
        TAKConnectService.ingestServerCot(sensitive, "Fixture",
            activeChannels = { error("Sensitive payload must not enter debug projection") }) {
            assertTrue(ShadowLog.getLogsForTag(tag).isEmpty())
            TakServerCotIngestResult.COMMAND
        }
        assertTrue(ShadowLog.getLogsForTag(tag).isEmpty())
    }

    @Test fun disabledDebugSkipsProjectionAndLogsButStillIngestsSameEnvelope() = runBlocking {
        ShadowLog.setLoggable(tag, Log.INFO)
        val envelope = envelope()
        var ingested = 0
        val result = TAKConnectService.ingestServerCot(
            envelope, "Fixture",
            activeChannels = { error("Disabled diagnostics must not project channels") },
        ) {
            assertSame(envelope, it)
            ingested++
            TakServerCotIngestResult.USER
        }
        assertEquals(1, ingested)
        assertEquals(TakServerCotIngestResult.USER, result)
        assertTrue(ShadowLog.getLogsForTag(tag).isEmpty())
    }

    @Test fun enabledDebugKeepsExactMessagesAndReceiptBeforeIngestRoutingAfter() = runBlocking {
        ShadowLog.setLoggable(tag, Log.DEBUG)
        val envelope = envelope()
        var projections = 0
        TAKConnectService.ingestServerCot(
            envelope, "Fixture",
            activeChannels = {
                projections++
                listOf(TakChannel("Fixture channel", "IN", true, 4))
            },
        ) {
            assertSame(envelope, it)
            assertEquals(1, ShadowLog.getLogsForTag(tag).size)
            TakServerCotIngestResult.USER
        }
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(envelope.rawXml.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(1, projections)
        assertEquals(
            listOf(
                "Inbound CoT received serverId=server-fixture serverName=Fixture sourceAddress=example.invalid " +
                    "uid=fixture type=a-f-G-U-C eventTime=null receivedAt=1234 team=null role=null " +
                    "flowTags=[] explicitChannelStamps=[] " +
                    "activeChannelsAtReceipt=[Fixture channel{bitpos=4,direction=IN,active=true,updating=false}] " +
                    "channelOwnership=unavailable payloadSha256Prefix=$fingerprint " +
                    "payloadBytes=${envelope.rawXml.toByteArray(Charsets.UTF_8).size}",
                "Inbound CoT routed serverId=server-fixture uid=fixture type=a-f-G-U-C result=USER",
            ),
            ShadowLog.getLogsForTag(tag).map { it.msg },
        )
    }

    @Test fun disabledDebugDoesNotSuppressIngestWarningsErrorsOrFailures() = runBlocking {
        ShadowLog.setLoggable(tag, Log.INFO)
        val failure = IllegalStateException("fixture failure")
        try {
            TAKConnectService.ingestServerCot(
                envelope(), "Fixture", activeChannels = { error("Unexpected projection") },
            ) {
                Log.w(tag, "fixture warning")
                Log.e(tag, "fixture error")
                throw failure
            }
            error("Ingest failure must propagate")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals(listOf("fixture warning", "fixture error"), ShadowLog.getLogsForTag(tag).map { it.msg })
    }

    @Test fun concurrentReadersRetainPerReaderOrderWithoutCrossFrameDiagnostics() = runBlocking {
        ShadowLog.setLoggable(tag, Log.DEBUG)
        val received = Collections.synchronizedList(mutableListOf<String>())
        (0..1).map { reader ->
            async(Dispatchers.Default) {
                repeat(20) { frame ->
                    val uid = "$reader-$frame"
                    TAKConnectService.ingestServerCot(
                        envelope(uid), "Fixture", activeChannels = { emptyList() },
                    ) {
                        received.add(uid)
                        yield()
                        TakServerCotIngestResult.USER
                    }
                }
            }
        }.awaitAll()
        (0..1).forEach { reader ->
            assertEquals((0..19).map { "$reader-$it" }, received.filter { it.startsWith("$reader-") })
        }
        val logs = ShadowLog.getLogsForTag(tag).map { it.msg }
        assertEquals(80, logs.size)
        received.forEach { uid ->
            assertEquals(1, logs.count { it.startsWith("Inbound CoT received ") && "uid=$uid " in it })
            assertEquals(1, logs.count { it == "Inbound CoT routed serverId=server-fixture uid=$uid type=a-f-G-U-C result=USER" })
            assertTrue(logs.none { envelope(uid).rawXml in it })
        }
    }
}
