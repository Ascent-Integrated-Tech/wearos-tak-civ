package com.aitech.weartak_civ.service

import android.app.Application
import android.net.NetworkCapabilities
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.takserver.TakConnectionFailure
import com.aitech.weartak_civ.data.takserver.TakEnrollmentStage
import com.aitech.weartak_civ.data.takserver.TakFailureCategory
import com.aitech.weartak_civ.presentation.MainActivity
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import javax.net.ssl.SSLSocket

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ConnectionDiagnosticsTest {
    private lateinit var diagnostics: AppDiagnostics
    private val instance = AppDiagnostics::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private var previous: Any? = null
    private var previousDetails = mutableListOf<TAKConnectService.TakServerDetail>()
    private var previousConnected = false
    private var previousConnecting = false
    private val companionConnected = MutableStateFlow(false)

    @Before
    fun prepare() {
        val app = RuntimeEnvironment.getApplication()
        val activity = mockk<MainActivity>(relaxed = true)
        every { activity.applicationContext } returns app
        MainActivity.mainActivity = activity
        val repository = mockk<DataRepository>(relaxed = true)
        every { repository.isCompanionConnected } returns companionConnected
        MainActivity.dataRepository = repository
        app.getSharedPreferences("app_diagnostics", Application.MODE_PRIVATE).edit().clear().commit()
        app.getExternalFilesDir("diagnostics")!!.let { root ->
            listOf("events.jsonl", "events.1.jsonl", "events.2.jsonl").forEach { root.resolve(it).delete() }
        }
        previous = instance.get(null)
        diagnostics = AppDiagnostics(app)
        instance.set(null, diagnostics)
        previousDetails = TAKConnectService.takServerDetails
        previousConnected = TAKConnectService.isAnyTakConnected
        previousConnecting = TAKConnectService.isAnyTakConnecting
        TAKConnectService.takServerDetails = mutableListOf()
    }

    @After
    fun cleanup() = runBlocking {
        try {
            diagnostics.setEnabled(false)
            diagnostics.close()
        } finally {
            instance.set(null, previous)
            TAKConnectService.takServerDetails = previousDetails
            TAKConnectService.isAnyTakConnected = previousConnected
            TAKConnectService.isAnyTakConnecting = previousConnecting
            unmockkAll()
        }
        Unit
    }

    private suspend fun records(): List<JsonObject> =
        JsonParser.parseString(diagnostics.exportJson()).asJsonObject["events"].asJsonArray
            .map { it.asJsonObject }.filter {
                it["event"].asString in setOf("TAK_CONNECTION", "TAK_CONNECTION_COUNTS", "NETWORK_LIFECYCLE")
            }

    @Test
    fun disabledDoesNotEvaluateMetricsOrAcquireConnectionState() = runBlocking {
        ConnectionDiagnostics.record(DiagnosticEvent.TAK_CONNECTION) { error("disabled selector") }
        ConnectionDiagnostics.network(NetworkTransition.LOST, false, null)
        TAKConnectService.recordConnectionCounts()
        assertTrue(records().isEmpty())
    }

    @Test
    fun networkEventsDescribeValidationAndTransportWithoutNetworkIdentities() = runBlocking {
        diagnostics.setEnabled(true)
        val capabilities = mockk<NetworkCapabilities>()
        var validated = false
        every { capabilities.hasTransport(any()) } answers {
            firstArg<Int>() == NetworkCapabilities.TRANSPORT_WIFI
        }
        every { capabilities.hasCapability(any()) } answers {
            firstArg<Int>() == NetworkCapabilities.NET_CAPABILITY_INTERNET ||
                (validated && firstArg<Int>() == NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        ConnectionDiagnostics.network(NetworkTransition.AVAILABLE, true, null)
        ConnectionDiagnostics.network(NetworkTransition.CAPABILITIES, true, capabilities)
        validated = true
        ConnectionDiagnostics.network(NetworkTransition.CAPABILITIES, true, capabilities)
        ConnectionDiagnostics.network(NetworkTransition.LOST, false, null)
        val all = records()
        val validationStates = all.filter { it["metrics"].asJsonObject["KIND"].asLong == 2L }
            .map { it["metrics"].asJsonObject["VALIDATED"].asLong }
        assertTrue(validationStates.containsAll(listOf(0L, 1L)))
        assertTrue(all.all { row ->
            row["metrics"].asJsonObject.entrySet().all { (_, value) -> value.isJsonPrimitive && value.asJsonPrimitive.isNumber }
        })
        assertTrue(all.any { it["metrics"].asJsonObject["TRANSPORT"].asLong == 1L })
        assertTrue(all.any { it["metrics"].asJsonObject["KIND"].asLong == 3L })
    }

    @Test
    fun lifecycleMetadataDoesNotExportServerCredentialsPayloadOrExceptionText() = runBlocking {
        diagnostics.setEnabled(true)
        val detail = detail()
        detail.runtime.generation = 12
        detail.numConnAttempts = 3
        detail.connectionStatus.set(TAKConnectService.TAK_SERVER_CONNECTING_COT_PORT)
        TAKConnectService.recordConnection(detail, ConnectionTransition.RETRY_SCHEDULED,
            ConnectionReason.CONNECTIVITY, generation = 11, delayMs = 8_000)
        val data = records().single()["metrics"].asJsonObject
        assertEquals(11L, data["GENERATION"].asLong)
        assertEquals(3L, data["ATTEMPT"].asLong)
        assertEquals(8_000L, data["DELAY_MS"].asLong)
        val exported = diagnostics.exportJson()
        listOf("private-server-id", "private.example", "secret-user", "secret-password", "secret-p12")
            .forEach { assertFalse(exported.contains(it)) }
        assertEquals(ConnectionReason.TLS, ConnectionDiagnostics.reason(
            TakConnectionFailure.from(TakEnrollmentStage.CONNECTION,
                javax.net.ssl.SSLException("private.example secret-password"))))
    }

    @Test
    fun aggregateSnapshotExposesUiRuntimeMismatchWithoutChangingStatus() = runBlocking {
        diagnostics.setEnabled(true)
        val connected = detail()
        connected.connectionStatus.set(TAKConnectService.TAK_SERVER_CONNECTED_COT_PORT)
        connected.secureSocket = mockk<SSLSocket> {
            every { isConnected } returns true
            every { isClosed } returns false
        }
        val connecting = detail()
        connecting.connectionStatus.set(TAKConnectService.TAK_SERVER_CONNECTING_COT_PORT)
        TAKConnectService.takServerDetails = mutableListOf(connected, connecting)
        TAKConnectService.isAnyTakConnected = false
        TAKConnectService.isAnyTakConnecting = true
        TAKConnectService.recordConnectionCounts()
        val data = records().single()["metrics"].asJsonObject
        assertEquals(1L, data["CONNECTED_COUNT"].asLong)
        assertEquals(1L, data["CONNECTING_COUNT"].asLong)
        assertEquals(1L, data["SOCKET_COUNT"].asLong)
        assertEquals(0L, data["UI_CONNECTED"].asLong)
        assertEquals(1L, data["UI_CONNECTING"].asLong)
        assertEquals(TAKConnectService.TAK_SERVER_CONNECTED_COT_PORT, connected.connectionStatus.get())
        assertFalse(TAKConnectService.isAnyTakConnected)
    }

    @Test
    fun concurrentRepeatedEventsAreBoundedAndFlushLatestMetadataOnExportAndDisable() = runBlocking {
        diagnostics.setEnabled(true)
        val detail = detail()
        (0 until 4).map {
            async(Dispatchers.Default) {
                repeat(100) {
                    TAKConnectService.recordConnection(detail, ConnectionTransition.SKIPPED,
                        ConnectionReason.STALE_GENERATION, generation = it.toLong())
                }
            }
        }.awaitAll()
        val all = records()
        assertTrue(all.size <= 2)
        assertEquals(400L, all.sumOf { it["metrics"].asJsonObject["COUNT"].asLong })
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
        TAKConnectService.recordConnection(detail, ConnectionTransition.SUCCESS)
        diagnostics.setEnabled(false)
        val count = records().size
        ConnectionDiagnostics.record(DiagnosticEvent.TAK_CONNECTION) { error("disabled after consent") }
        assertEquals(count, records().size)
    }

    @Test
    fun fixedReasonCodesNeverNeedRawFailureMessages() {
        assertEquals(setOf(ConnectionReason.AUTHENTICATION, ConnectionReason.HTTP, ConnectionReason.TLS,
            ConnectionReason.CONNECTIVITY, ConnectionReason.CERTIFICATE),
            TakFailureCategory.values().map {
                ConnectionDiagnostics.reason(TakConnectionFailure(TakEnrollmentStage.CONNECTION, it, 401))
            }.toSet())
        assertEquals(ConnectionTransition.values().size, ConnectionTransition.values().map { it.code }.toSet().size)
        assertEquals(ConnectionReason.values().size, ConnectionReason.values().map { it.code }.toSet().size)
    }

    @Test
    fun delayedRetryRecordsStaleFenceWithoutStartingAnotherAttempt() = runTest {
        diagnostics.setEnabled(true)
        val detail = detail()
        detail.runtime.generation = 1
        val scopeField = TAKConnectService::class.java.getDeclaredField("connectionScope").apply { isAccessible = true }
        val oldScope = scopeField.get(null)
        val oldNetwork = TAKConnectService.isNetworkConnected
        val oldStopping = TAKConnectService.serviceStopping
        try {
            scopeField.set(null, this)
            TAKConnectService.isNetworkConnected = true
            TAKConnectService.serviceStopping = false
            scheduleRetry(detail, 1)
            detail.runtime.generation = 2
            advanceUntilIdle()
            val all = records().map { it["metrics"].asJsonObject }
            assertTrue(all.any { it["KIND"].asLong == ConnectionTransition.RETRY_SCHEDULED.code })
            assertTrue(all.any { it["KIND"].asLong == ConnectionTransition.SKIPPED.code &&
                it["RESULT"].asLong == ConnectionReason.STALE_GENERATION.code })
            assertFalse(all.any { it["KIND"].asLong == ConnectionTransition.RETRY_STARTED.code })
            assertNull(detail.runtime.currentAttempt)
            assertEquals(0, detail.numConnAttempts)
        } finally {
            scopeField.set(null, oldScope)
            TAKConnectService.isNetworkConnected = oldNetwork
            TAKConnectService.serviceStopping = oldStopping
        }
    }

    @Test
    fun delayedRetryRetainsCompanionGateAndRecordsActualDecision() = runTest {
        diagnostics.setEnabled(true)
        val detail = detail()
        detail.runtime.generation = 1
        val scopeField = TAKConnectService::class.java.getDeclaredField("connectionScope").apply { isAccessible = true }
        val oldScope = scopeField.get(null)
        val oldNetwork = TAKConnectService.isNetworkConnected
        val oldStopping = TAKConnectService.serviceStopping
        try {
            scopeField.set(null, this)
            TAKConnectService.isNetworkConnected = true
            TAKConnectService.serviceStopping = false
            scheduleRetry(detail, 1)
            companionConnected.value = true
            advanceUntilIdle()
            val all = records().map { it["metrics"].asJsonObject }
            assertFalse(all.any { it["KIND"].asLong == ConnectionTransition.RETRY_STARTED.code })
            assertTrue(all.any { it["KIND"].asLong == ConnectionTransition.SKIPPED.code &&
                it["RESULT"].asLong == ConnectionReason.COMPANION.code })
            assertNull(detail.runtime.currentAttempt)
            assertEquals(0, detail.numConnAttempts)
        } finally {
            scopeField.set(null, oldScope)
            TAKConnectService.isNetworkConnected = oldNetwork
            TAKConnectService.serviceStopping = oldStopping
        }
    }

    private fun scheduleRetry(detail: TAKConnectService.TakServerDetail, generation: Long) {
        TAKConnectService.Companion::class.java.getDeclaredMethod("scheduleReconnect",
            TAKConnectService.TakServerDetail::class.java, Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(TAKConnectService.Companion, detail, 1_000L, generation)
    }

    private fun detail() = TAKConnectService.TakServerDetail(
        "private-server-id", true, "private-server-name", "private.example", 8089,
        true, "secret-user", "secret-password", "secret-p12", "secret-p12-password"
    )
}
