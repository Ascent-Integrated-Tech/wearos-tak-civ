package com.aitech.weartak_civ.service

import android.app.Application
import com.aitech.weartak_civ.events.DittoDocumentEvent
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.greenrobot.eventbus.EventBus
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AppDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication()
    private var recorder: AppDiagnostics? = null

    @Before fun reset() {
        app.getSharedPreferences("app_diagnostics", Application.MODE_PRIVATE).edit().clear().commit()
        val root = app.getExternalFilesDir("diagnostics")!!
        listOf("events.jsonl", "events.1.jsonl", "events.2.jsonl").forEach { root.resolve(it).delete() }
    }

    @After fun cleanup() {
        runBlocking {
            recorder?.setEnabled(false)
            recorder?.close()
        }
    }

    @Test fun defaultOffDoesNotSubscribeOrWrite() {
        val diagnostics = AppDiagnostics(app).also { recorder = it }
        assertFalse(diagnostics.enabled.value)
        assertFalse(EventBus.getDefault().isRegistered(diagnostics))
        diagnostics.onAppEvent(DittoDocumentEvent("chat", "secret payload"))
        assertFalse(app.getExternalFilesDir("diagnostics")!!.resolve("events.jsonl").exists())
    }

    @Test fun enabledPreferenceAndLogsSurviveRecorderRecreation() = runBlocking {
        val first = AppDiagnostics(app).also { recorder = it }
        first.setEnabled(true)
        first.onAppEvent(DittoDocumentEvent("chat", "not retained"))
        assertTrue(first.exportJson().contains("SYNC"))
        first.close()
        val restored = AppDiagnostics(app).also { recorder = it }
        assertTrue(restored.enabled.value)
        val events = JsonParser.parseString(restored.exportJson()).asJsonObject["events"].asJsonArray
        assertTrue(events.any { it.asJsonObject["event"].asString == "SYNC" })
        assertTrue(events.any { it.asJsonObject["event"].asString == "PROCESS_START" })
        assertTrue(app.getSharedPreferences("app_diagnostics", Application.MODE_PRIVATE).getBoolean("enabled", false))
    }

    @Test fun payloadsAreNeverReadAndRepeatedEventsAreAggregated() = runBlocking {
        val diagnostics = AppDiagnostics(app).also { recorder = it }
        diagnostics.setEnabled(true)
        repeat(100) {
            diagnostics.onAppEvent(DittoDocumentEvent(
                "sensitive-server-address", "<point lat=\"41.123\"/><remarks>token-secret callsign contact</remarks>"))
        }
        val exported = diagnostics.exportJson()
        listOf("41.123", "token-secret", "callsign", "contact", "sensitive-server-address", "remarks").forEach {
            assertFalse(it, exported.contains(it))
        }
        val events = JsonParser.parseString(exported).asJsonObject["events"].asJsonArray
            .filter { it.asJsonObject["event"].asString == "SYNC" }
        assertEquals(2, events.size)
        assertEquals(100, events.sumOf { it.asJsonObject["metrics"].asJsonObject["COUNT"].asInt })
        assertTrue(events.all { it.asJsonObject["elapsedMs"].asLong >= 0 })
    }

    @Test fun configurationSnapshotDoesNotStartServicesOrAcquireLocation() = runBlocking {
        com.aitech.weartak_civ.presentation.MainActivity.device_uid = "diagnostics-test"
        val repository = com.aitech.weartak_civ.data.DataRepository(app)
        val input = repository.getLocationInput.first()
        val location = repository.latestLocation.value
        val diagnostics = AppDiagnostics(app).also { recorder = it }
        diagnostics.bindRepository(repository)
        diagnostics.setEnabled(true)
        val json = withTimeout(5_000) {
            var result = diagnostics.exportJson()
            while (!result.contains("CONFIGURATION")) {
                delay(20)
                result = diagnostics.exportJson()
            }
            result
        }
        val configuration = JsonParser.parseString(json).asJsonObject["events"].asJsonArray
            .first { it.asJsonObject["event"].asString == "CONFIGURATION" }.asJsonObject["metrics"].asJsonObject
        assertEquals(input?.ordinal ?: -1, configuration["SOURCE"].asInt)
        assertEquals(repository.getOnFootReportingInterval.first(), configuration["FOOT_SECONDS"].asInt)
        assertEquals(input, repository.getLocationInput.first())
        assertSame(location, repository.latestLocation.value)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun explicitOffStopsRecordingWithoutErasingAndClearKeepsPreference() = runBlocking {
        val diagnostics = AppDiagnostics(app).also { recorder = it }
        diagnostics.setEnabled(true)
        diagnostics.onAppEvent(DittoDocumentEvent("track", "private"))
        assertTrue(diagnostics.exportJson().contains("SYNC"))
        diagnostics.setEnabled(false)
        assertFalse(EventBus.getDefault().isRegistered(diagnostics))
        val stopped = diagnostics.exportJson()
        repeat(20) { diagnostics.onAppEvent(DittoDocumentEvent("track", "private")) }
        assertEquals(stopped, diagnostics.exportJson())
        assertTrue(stopped.contains("SYNC"))
        diagnostics.setEnabled(true)
        diagnostics.clear()
        assertTrue(diagnostics.enabled.value)
        assertEquals(0, JsonParser.parseString(diagnostics.exportJson()).asJsonObject["events"].asJsonArray.size())
    }

    @Test fun rotationRetentionAndClearOnlyTouchOwnedFiles() {
        val root = temporary.newFolder()
        var now = System.currentTimeMillis()
        val journal = DiagnosticJournal({ root }, maxBytes = 700, wallClock = { now })
        val unrelated = root.resolve("keep.txt").apply { writeText("not diagnostics") }
        repeat(30) { index -> journal.append(sample(index.toLong())) }
        val owned = root.listFiles()!!.filter { it.name.startsWith("events") }
        assertTrue(owned.size <= 3)
        assertTrue(owned.all { it.length() <= 700 })
        val events = JsonParser.parseString(journal.exportJson()).asJsonObject["events"].asJsonArray
        assertTrue(events.size() in 1 until 30)
        assertTrue(events.first().asJsonObject["elapsedMs"].asLong > 0)
        assertEquals(29, events.last().asJsonObject["elapsedMs"].asInt)
        now += 4 * 24 * 60 * 60 * 1000L
        assertEquals(0, JsonParser.parseString(journal.exportJson()).asJsonObject["events"].asJsonArray.size())
        journal.append(sample(31))
        journal.clear()
        assertEquals(listOf("keep.txt"), root.listFiles()!!.map { it.name })
        assertEquals("not diagnostics", unrelated.readText())
    }

    @Test fun storageAndIncompleteExportFailuresAreExplicit() {
        val file = temporary.newFile()
        val unavailable = DiagnosticJournal({ file })
        assertThrows(IOException::class.java) { unavailable.append(sample(1)) }
        val root = temporary.newFolder()
        root.resolve("events.jsonl").writeText("{partial")
        val corrupt = DiagnosticJournal({ root })
        assertThrows(IOException::class.java) { corrupt.exportJson() }
        corrupt.clear()
        assertFalse(root.resolve("events.jsonl").exists())
    }

    @Test fun javaCrashEvidencePreservesTheExistingHandlerWithoutErrorContents() = runBlocking {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        var forwarded = 0
        val expected = IllegalStateException("sensitive crash message")
        val inherited = Thread.UncaughtExceptionHandler { _, error ->
            assertSame(expected, error)
            forwarded++
        }
        Thread.setDefaultUncaughtExceptionHandler(inherited)
        try {
            val diagnostics = AppDiagnostics(app).also { recorder = it }
            diagnostics.setEnabled(true)
            val firstHandler = Thread.getDefaultUncaughtExceptionHandler()!!
            val wrapper = Thread.UncaughtExceptionHandler { thread, error ->
                firstHandler.uncaughtException(thread, error)
            }
            Thread.setDefaultUncaughtExceptionHandler(wrapper)
            diagnostics.setEnabled(false)
            diagnostics.setEnabled(true)
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), expected)
            assertEquals(1, forwarded)
            val exported = diagnostics.exportJson()
            assertTrue(exported.contains("JAVA_CRASH"))
            assertFalse(exported.contains("sensitive crash message"))
            diagnostics.setEnabled(false)
            assertSame(wrapper, Thread.getDefaultUncaughtExceptionHandler())
        } finally {
            recorder?.setEnabled(false)
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    private fun sample(elapsed: Long) = DiagnosticRecord(
        wallTimeMs = System.currentTimeMillis(), elapsedMs = elapsed,
        event = DiagnosticEvent.BATTERY, metrics = mapOf(DiagnosticMetric.BATTERY_PERCENT to 75)
    )
}
