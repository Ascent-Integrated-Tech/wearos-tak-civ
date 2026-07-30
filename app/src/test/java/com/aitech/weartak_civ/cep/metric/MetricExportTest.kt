package com.aitech.weartak_civ.cep.metric

import com.aitech.weartak_civ.cep.engine.FeatureStore
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MetricExportTest {
    @Test
    fun exportCopiesMetricFilesWithoutChangingSources() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val historyDir = File(app.filesDir, "metric_history")
        val resultsDir = File(app.filesDir, "metric_results")
        val history = File(historyDir, "export_test.csv")
        val result = File(resultsDir, "export_test.json")
        historyDir.mkdirs()
        resultsDir.mkdirs()
        history.writeText("timestamp_ms,value\n1,60.0\n")
        result.writeText("{\"test\":true}")

        val destination = File.createTempFile("weartak_metric_export", ".zip")
        try {
            val orchestrator = MetricRuntimeOrchestrator(
                dataRepository = null,
                metricSpecs = emptyList(),
                appContext = app
            )
            orchestrator.exportFiles(destination)

            val entries = mutableMapOf<String, String>()
            ZipInputStream(destination.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes().decodeToString()
                }
            }
            assertEquals("timestamp_ms,value\n1,60.0\n", history.readText())
            assertEquals("{\"test\":true}", result.readText())
            assertEquals("timestamp_ms,value\n1,60.0\n", entries["metric_history/export_test.csv"])
            assertEquals("{\"test\":true}", entries["metric_results/export_test.json"])
            assertTrue(entries.containsKey("export_metadata.txt"))
        } finally {
            destination.delete()
            history.delete()
            result.delete()
        }
    }

    @Test
    fun explicitUserResetClearsHistoryAndCalculationCache() = runBlocking {
        val history = RecordingHistoryStore()
        val cache = RecordingCalculationCacheStore()
        val orchestrator = MetricRuntimeOrchestrator(
            dataRepository = null,
            metricSpecs = emptyList(),
            historyStore = history,
            calculationCacheStore = cache
        )

        orchestrator.resetAndReinitializeForUserChange(FeatureStore(), 1L)

        assertTrue(history.cleared)
        assertTrue(cache.cleared)
    }

    @Test
    fun forcedRecalculationDoesNotClearHistoryOrCalculationCache() {
        val history = RecordingHistoryStore()
        val cache = RecordingCalculationCacheStore()
        val orchestrator = MetricRuntimeOrchestrator(
            dataRepository = null,
            metricSpecs = emptyList(),
            historyStore = history,
            calculationCacheStore = cache
        )

        orchestrator.calculateAllNow(1L, FeatureStore())

        assertFalse(history.cleared)
        assertFalse(cache.cleared)
    }

    private class RecordingHistoryStore : MetricHistoryStore {
        var cleared = false
        override fun clearAll() { cleared = true }
        override fun appendHotSample(metricId: String, sample: NormalizedMetricSample) = Unit
        override fun loadHotSamples(metricId: String, sinceMs: Long) = emptyList<NormalizedMetricSample>()
        override fun loadWarmBuckets(metricId: String, sinceMs: Long) = emptyList<MetricBucket>()
        override fun loadColdBuckets(metricId: String, sinceMs: Long) = emptyList<MetricBucket>()
        override fun compactHotToWarm(metricId: String, cutoffMs: Long) = Unit
        override fun compactWarmToCold(metricId: String, cutoffMs: Long) = Unit
        override fun evictOlderThan(metricId: String, cutoffMs: Long) = Unit
    }

    private class RecordingCalculationCacheStore : MetricCalculationCacheStore {
        var cleared = false
        override fun load(metricId: String, configHash: String): CachedMetricCore? = null
        override fun save(metricId: String, configHash: String, cached: CachedMetricCore) = Unit
        override fun clearAll() { cleared = true }
    }
}
