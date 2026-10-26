package com.aitech.weartak_civ.service

import android.app.Application
import android.provider.Settings
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PowerContextDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun suspendMathIncludesExactZeroUncertaintyShortIntervalsAndInvalidClocks() {
        val start = PowerClocks(1000, 1000)
        fun interval(elapsed: Long, uptime: Long) =
            suspendInterval(start, PowerClocks(1000 + elapsed, 1000 + uptime))
        assertEquals(9000L, interval(300000, 30000)[DiagnosticMetric.ASLEEP_BASIS_POINTS])
        assertEquals(10000L, interval(300000, 0)[DiagnosticMetric.ASLEEP_BASIS_POINTS])
        assertEquals(0L, interval(300000, 300000)[DiagnosticMetric.ASLEEP_BASIS_POINTS])
        assertEquals(1L, interval(300000, 300000)[DiagnosticMetric.NEVER_SUSPENDED])
        assertEquals(2L, interval(300000, 300002)[DiagnosticMetric.NEVER_SUSPENDED])
        assertEquals(2L, interval(300000, 299998)[DiagnosticMetric.NEVER_SUSPENDED])
        assertEquals(1L, interval(500, 250)[DiagnosticMetric.SHORT_INTERVAL])
        listOf(0L to 0L, -1L to 0L, 100L to -1L, 100L to 106L).forEach { (elapsed, uptime) ->
            assertEquals(2L, interval(elapsed, uptime)[DiagnosticMetric.CLOCK_STATUS])
            assertNull(interval(elapsed, uptime)[DiagnosticMetric.ASLEEP_BASIS_POINTS])
        }
        assertEquals(2L, suspendInterval(start, PowerClocks(2000, 1500, 6))[DiagnosticMetric.CLOCK_STATUS])
        assertEquals(0L, suspendInterval(null, start)[DiagnosticMetric.CLOCK_STATUS])
    }

    @Test fun readingsNeverInventZeroOrDiscardSignedCurrent() {
        assertEquals(NumericReading(1), NumericReading.setting(null))
        assertEquals(NumericReading(3), NumericReading.setting("vendor-unknown"))
        assertEquals(NumericReading(0, 0), NumericReading.setting("0"))
        assertEquals(NumericReading(4), NumericReading.battery(Long.MIN_VALUE))
        assertEquals(NumericReading(0, -12345), NumericReading.battery(-12345))
        assertEquals(NumericReading(0, 0), NumericReading.battery(0))
        assertTrue(powerSettingProbes.size <= 32)
        assertEquals(powerSettingProbes.size, powerSettingProbes.map { it.id }.toSet().size)
    }

    @Test fun detailBudgetBoundsBurstAndSteadyRateAndRetainsAbaLossSummary() {
        val budget = ContextDetailBudget()
        val metrics = mapOf(DiagnosticMetric.SETTING_ID to 0L)
        assertEquals(14, (1..1000).count {
            budget.admit(DiagnosticEvent.DEVICE_SETTING, metrics + (DiagnosticMetric.VALUE to (it % 2).toLong()), 0)
        })
        assertTrue(budget.admit(DiagnosticEvent.POWER_CONTEXT, emptyMap(), 0))
        assertTrue(budget.admit(DiagnosticEvent.POWER_CONTEXT, emptyMap(), 0))
        assertFalse(budget.admit(DiagnosticEvent.POWER_CONTEXT, emptyMap(), 0))
        val summary = budget.flush().first { it.first == DiagnosticEvent.DEVICE_SETTING }.second
        assertEquals(986L, summary[DiagnosticMetric.SUPPRESSED])
        assertEquals(0L, summary[DiagnosticMetric.VALUE])
        assertEquals(1L, summary[DiagnosticMetric.AGGREGATED])
        assertEquals(60, (1..60000).count {
            budget.admit(DiagnosticEvent.POWER_CONTEXT, emptyMap(), it.toLong())
        })
        budget.reset()
        assertTrue(budget.flush().isEmpty())
    }

    @Test fun disabledCallbacksDoNotReadOrWriteAndRejectedEndpointsDoNotAdvanceBaseline() {
        val app = RuntimeEnvironment.getApplication() as Application
        var reads = 0
        var time = PowerClocks(1000, 1000)
        var accept = true
        val records = mutableListOf<Map<DiagnosticMetric, Long>>()
        val context = PowerContextDiagnostics(app, { event, metrics ->
            if (event == DiagnosticEvent.POWER_CONTEXT) records.add(metrics)
            accept
        }, { reads++; time })
        context.snapshot()
        context.battery(true)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(0, reads)
        assertTrue(records.isEmpty())
        try {
            context.start()
            assertEquals(0L, records.last()[DiagnosticMetric.CLOCK_STATUS])
            time = PowerClocks(301000, 31000)
            accept = false
            context.snapshot()
            time = PowerClocks(601000, 61000)
            accept = true
            context.snapshot()
            assertEquals(600000L, records.last()[DiagnosticMetric.ELAPSED_DELTA_MS])
            assertEquals(9000L, records.last()[DiagnosticMetric.ASLEEP_BASIS_POINTS])
            context.battery(true)
            context.reset()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            context.snapshot()
            assertEquals(0L, records.last()[DiagnosticMetric.CLOCK_STATUS])
        } finally { context.stop() }
        val before = reads
        context.snapshot()
        context.battery(true)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(before, reads)
    }

    @Test fun missingSamsungSettingIsExplicitlyUnreadableAndChangesRemainDistinct() {
        val app = RuntimeEnvironment.getApplication() as Application
        val events = mutableListOf<Pair<DiagnosticEvent, Map<DiagnosticMetric, Long>>>()
        Settings.System.putString(app.contentResolver, "lift_to_wake", null)
        val context = PowerContextDiagnostics(app, { event, metrics -> events.add(event to metrics); true })
        try {
            context.start()
            fun values() = events.filter { it.first == DiagnosticEvent.DEVICE_SETTING &&
                it.second[DiagnosticMetric.SETTING_ID] == 9L }.map { it.second }
            assertEquals(1L, values().single()[DiagnosticMetric.READ_STATUS])
            assertNull(values().single()[DiagnosticMetric.VALUE])
            listOf("1", "0", "1").forEach {
                Settings.System.putString(app.contentResolver, "lift_to_wake", it)
                context.snapshot()
            }
            assertEquals(listOf(null, 1L, 0L, 1L), values().map { it[DiagnosticMetric.VALUE] })
        } finally { context.stop() }
    }

    @Test fun additiveContextRoundTripsWithinRecordLimit() = runBlocking {
        assertEquals(39, DiagnosticEvent.STAY_AWAKE_AUTO_DISABLED.ordinal)
        assertEquals(40, DiagnosticEvent.STAY_AWAKE_START_REFUSED.ordinal)
        assertEquals(41, DiagnosticEvent.MOTION_CONFIRMATION.ordinal)
        assertEquals(42, DiagnosticEvent.POWER_CONTEXT.ordinal)
        assertEquals(43, DiagnosticEvent.DEVICE_SETTING.ordinal)
        assertEquals(44, DiagnosticEvent.RESOURCE_LIFETIME.ordinal)
        val app = RuntimeEnvironment.getApplication() as Application
        app.getSharedPreferences("app_diagnostics", Application.MODE_PRIVATE).edit().clear().commit()
        val recorder = AppDiagnostics(app)
        try {
            recorder.clear()
            recorder.setEnabled(true)
            val events = JsonParser.parseString(recorder.exportJson()).asJsonObject["events"].asJsonArray
            val power = events.first { it.asJsonObject["event"].asString == "POWER_CONTEXT" }
            assertEquals(1, power.asJsonObject["schema"].asInt)
            assertEquals(1, power.asJsonObject["metrics"].asJsonObject["CONTEXT_VERSION"].asInt)
            events.filter { it.asJsonObject["event"].asString in setOf("POWER_CONTEXT", "DEVICE_SETTING") }
                .forEach { assertTrue(it.toString().toByteArray().size + 1 <= 2048) }
            recorder.clear()
            assertEquals(0, JsonParser.parseString(recorder.exportJson()).asJsonObject["events"].asJsonArray.size())
        } finally {
            recorder.setEnabled(false)
            recorder.close()
        }

    }

    @Test fun cumulativeUnionSummarySurvivesSuppressedDetailForSameResource() {
        val budget = ContextDetailBudget()
        repeat(16) { budget.admit(DiagnosticEvent.POWER_CONTEXT, emptyMap(), 0) }
        budget.admit(DiagnosticEvent.RESOURCE_LIFETIME, mapOf(
            DiagnosticMetric.RESOURCE_ID to 1L, DiagnosticMetric.ACTION to 10L,
            DiagnosticMetric.UNION_MS to 1234L), 0)
        budget.admit(DiagnosticEvent.RESOURCE_LIFETIME, mapOf(
            DiagnosticMetric.RESOURCE_ID to 1L, DiagnosticMetric.ACTION to 2L), 0)
        val summaries = budget.flush()
        assertEquals(2, summaries.size)
        assertEquals(1234L, summaries.single { it.second[DiagnosticMetric.ACTION] == 10L }
            .second[DiagnosticMetric.UNION_MS])
    }

    @Test fun newRecordCeilingIsEnforcedAndAllSummaryIdentitiesRemainBounded() {
        val journal = DiagnosticJournal({ temporary.root })
        assertThrows(java.io.IOException::class.java) {
            journal.append(DiagnosticRecord(wallTimeMs = 1, elapsedMs = 0,
                event = DiagnosticEvent.POWER_CONTEXT,
                metrics = DiagnosticMetric.entries.associateWith { Long.MAX_VALUE }))
        }
        val budget = ContextDetailBudget()
        repeat(16) { budget.admit(DiagnosticEvent.POWER_CONTEXT, emptyMap(), 0) }
        repeat(1000) { index ->
            repeat(32) { id ->
                budget.admit(DiagnosticEvent.DEVICE_SETTING, mapOf(
                    DiagnosticMetric.SETTING_ID to id.toLong(), DiagnosticMetric.VALUE to index.toLong()), 0)
                budget.admit(DiagnosticEvent.RESOURCE_LIFETIME, mapOf(
                    DiagnosticMetric.RESOURCE_ID to id.toLong(), DiagnosticMetric.ACTION to 1L), 0)
                budget.admit(DiagnosticEvent.RESOURCE_LIFETIME, mapOf(
                    DiagnosticMetric.RESOURCE_ID to id.toLong(), DiagnosticMetric.ACTION to 10L), 0)
            }
        }
        assertEquals(96, budget.flush().size)
        assertTrue(budget.flush().isEmpty())
    }
}
