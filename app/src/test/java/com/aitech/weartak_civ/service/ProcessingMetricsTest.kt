package com.aitech.weartak_civ.service

import android.app.Application
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ProcessingMetricsTest {
    private lateinit var diagnostics: AppDiagnostics
    private val instance = AppDiagnostics::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private var previous: Any? = null
    private var closed = false

    @Before fun prepare() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("app_diagnostics", Application.MODE_PRIVATE).edit().clear().commit()
        app.getExternalFilesDir("diagnostics")!!.let { root ->
            listOf("events.jsonl", "events.1.jsonl", "events.2.jsonl").forEach { root.resolve(it).delete() }
        }
        previous = instance.get(null)
        diagnostics = AppDiagnostics(app)
        instance.set(null, diagnostics)
    }

    @After fun cleanup() = runBlocking {
        try {
            if (!closed) {
                diagnostics.setEnabled(false)
                diagnostics.close()
            }
        } finally {
            instance.set(null, previous)
        }
    }

    private suspend fun records(): List<JsonObject> =
        JsonParser.parseString(diagnostics.exportJson()).asJsonObject["events"].asJsonArray
            .map { it.asJsonObject }.filter { it["event"].asString == "PROCESSING_WORK" }

    @Test fun disabledMetricsNeverEvaluateSelectorsOrRetainPayloadAndPreserveNullableResult() = runBlocking {
        var calls = 0
        val result = AppDiagnostics.measureProcessing<String?>(
            ProcessingStage.TCP_READ, bytes = { error("Disabled bytes selector") },
            frames = { error("Disabled frames selector") },
        ) { calls++; null }
        assertNull(result)
        assertEquals(1, calls)
        assertTrue(records().isEmpty())
    }

    @Test fun callbacksAggregateExactCountsAndDurationsUntilThirtySecondsOrFinalExport() = runBlocking {
        diagnostics.setEnabled(true)
        repeat(10_000) {
            AppDiagnostics.measureProcessing<Int>(ProcessingStage.TCP_FRAMING, frames = { it.toLong() }) { 2 }
        }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        AppDiagnostics.measureProcessing<Int>(ProcessingStage.TCP_FRAMING, frames = { it.toLong() }) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(2))
            3
        }
        AppDiagnostics.measureProcessing<Int>(ProcessingStage.TCP_FRAMING, frames = { it.toLong() }) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(1))
            4
        }
        val records = records()
        assertEquals(2, records.size)
        val metrics = records.map { it["metrics"].asJsonObject }
        assertEquals(10_002L, metrics.sumOf { it["COUNT"].asLong })
        assertEquals(20_007L, metrics.sumOf { it["FRAMES"].asLong })
        assertEquals(3_000L, metrics.sumOf { it["DURATION_US"].asLong })
        assertEquals(2_000L, metrics.maxOf { it["MAX_DURATION_US"].asLong })
        assertEquals(30_002L, metrics.first()["WINDOW_MS"].asLong)
        assertEquals(0L, metrics.sumOf { it["FAILURES"].asLong })
        assertEquals(records, records())
    }

    @Test fun concurrentReadersCountActualStreamBytesOnceWithoutChangingInput() = runBlocking {
        diagnostics.setEnabled(true)
        val payload = ("fixture-" + '\u00e9').toByteArray(Charsets.UTF_8)
        (0 until 4).map {
            async(Dispatchers.Default) {
                repeat(100) {
                    ProcessingInputStream(ByteArrayInputStream(payload)).use { input ->
                        assertEquals(payload[0].toInt(), input.read())
                        assertArrayEquals(payload.drop(1).toByteArray(), input.readBytes())
                    }
                }
            }
        }.awaitAll()
        val metrics = records().single()["metrics"].asJsonObject
        assertEquals(ProcessingStage.TCP_READ.code, metrics["KIND"].asLong)
        assertEquals(payload.size * 400L, metrics["BYTES"].asLong)
        assertTrue(metrics["COUNT"].asLong >= 800L)
        val exported = diagnostics.exportJson()
        listOf("fixture", "secret", "example.invalid", "uid", "payload").forEach {
            assertFalse(it, exported.contains(it))
        }
        assertTrue(metrics.entrySet().all { it.value.isJsonPrimitive && it.value.asJsonPrimitive.isNumber })
    }

    @Test fun failuresPropagateAndAreCountedWithoutExceptionText() = runBlocking {
        diagnostics.setEnabled(true)
        val failure = IOException("private payload credential")
        try {
            ProcessingInputStream(object : InputStream() {
                override fun read(): Int = throw failure
            }).read()
            fail("Read failure must propagate")
        } catch (actual: IOException) {
            assertSame(failure, actual)
        }
        val metric = records().single()["metrics"].asJsonObject
        assertEquals(1L, metric["COUNT"].asLong)
        assertEquals(1L, metric["FAILURES"].asLong)
        assertEquals(0L, metric["BYTES"].asLong)
        assertFalse(diagnostics.exportJson().contains(failure.message!!))
    }

    @Test fun disableReenableAndClearDoNotAdmitWorkFromEarlierGeneration() = runBlocking {
        diagnostics.setEnabled(true)
        AppDiagnostics.measureProcessing(ProcessingStage.USER_STORE) {
            diagnostics.setEnabled(false)
            diagnostics.setEnabled(true)
        }
        assertTrue(records().isEmpty())
        AppDiagnostics.measureProcessing(ProcessingStage.USER_STORE) { diagnostics.clear() }
        assertTrue(records().isEmpty())
        AppDiagnostics.measureProcessing(ProcessingStage.USER_STORE) { Unit }
        assertEquals(1L, records().single()["metrics"].asJsonObject["COUNT"].asLong)
    }

    @Test fun shutdownFlushesCompletedPartialWindowWithoutTimersOrDuplicateExport() = runBlocking {
        diagnostics.setEnabled(true)
        repeat(20) { AppDiagnostics.measureProcessing(ProcessingStage.PLUGIN_OFFER) { Unit } }
        AppDiagnostics.flushProcessingWork()
        val events = records()
        assertEquals(20L, events.single()["metrics"].asJsonObject["COUNT"].asLong)
        assertEquals(events, records())
        repeat(3) { AppDiagnostics.measureProcessing(ProcessingStage.MARKER_STORE) { Unit } }
        diagnostics.close()
        closed = true
        val file = RuntimeEnvironment.getApplication().getExternalFilesDir("diagnostics")!!.resolve("events.jsonl")
        val marker = file.readLines().map { JsonParser.parseString(it).asJsonObject }.single {
            it["event"].asString == "PROCESSING_WORK" &&
                it["metrics"].asJsonObject["KIND"].asLong == ProcessingStage.MARKER_STORE.code
        }
        assertEquals(3L, marker["metrics"].asJsonObject["COUNT"].asLong)
    }
}
