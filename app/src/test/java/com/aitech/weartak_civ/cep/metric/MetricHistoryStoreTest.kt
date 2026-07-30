package com.aitech.weartak_civ.cep.metric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.math.ln

class MetricHistoryStoreTest {

    @Test
    fun bucketRollupMath_isCorrect() {
        val base = FileBackedMetricHistoryStore.emptyBucket(0L, FileBackedMetricHistoryStore.HOUR_MS)
        val b1 = FileBackedMetricHistoryStore.addSample(base, NormalizedMetricSample(10.0, 1000L))
        val b2 = FileBackedMetricHistoryStore.addSample(b1, NormalizedMetricSample(20.0, 2000L))
        val b3 = FileBackedMetricHistoryStore.addSample(b2, NormalizedMetricSample(30.0, 3000L))

        assertEquals(3L, b3.count)
        assertEquals(60.0, b3.sum, 0.0)
        assertEquals(10.0, b3.min, 0.0)
        assertEquals(30.0, b3.max, 0.0)
        assertEquals(20.0, b3.mean(), 0.0001)
        assertEquals((ln(10.0) + ln(20.0) + ln(30.0)) / 3.0, b3.meanLog()!!, 0.0001)
        assertTrue(b3.variance() > 0.0)
    }

    @Test
    fun compactsHotToWarm() {
        val dir = Files.createTempDirectory("metric_history_hot_warm").toFile()
        val store = FileBackedMetricHistoryStore(dir)
        val metricId = "m.hotwarm"
        val now = 5 * FileBackedMetricHistoryStore.HOUR_MS

        repeat(4) { h ->
            store.appendHotSample(metricId, NormalizedMetricSample(50.0 + h, h * FileBackedMetricHistoryStore.HOUR_MS + 1000L))
        }

        store.compactHotToWarm(metricId, cutoffMs = 3 * FileBackedMetricHistoryStore.HOUR_MS)

        val hot = store.loadHotSamples(metricId, 0L)
        val warm = store.loadWarmBuckets(metricId, 0L)
        assertEquals(1, hot.size) // the 4th hour remains
        assertEquals(3, warm.size)
        assertTrue(warm.all { it.bucketSizeMs == FileBackedMetricHistoryStore.HOUR_MS })
        assertTrue(warm.all { it.bucketStartTs % FileBackedMetricHistoryStore.HOUR_MS == 0L })
        assertTrue(now > 0) // keep now used for readability
    }

    @Test
    fun compactsWarmToCold() {
        val dir = Files.createTempDirectory("metric_history_warm_cold").toFile()
        val store = FileBackedMetricHistoryStore(dir)
        val metricId = "m.warmcold"

        repeat(72) { h ->
            store.appendHotSample(metricId, NormalizedMetricSample(40.0, h * FileBackedMetricHistoryStore.HOUR_MS + 500L))
        }
        store.compactHotToWarm(metricId, cutoffMs = 72 * FileBackedMetricHistoryStore.HOUR_MS)
        store.compactWarmToCold(metricId, cutoffMs = 48 * FileBackedMetricHistoryStore.HOUR_MS)

        val warm = store.loadWarmBuckets(metricId, 0L)
        val cold = store.loadColdBuckets(metricId, 0L)
        assertTrue(cold.isNotEmpty())
        assertTrue(cold.all { it.bucketSizeMs == FileBackedMetricHistoryStore.DAY_MS })
        assertTrue(warm.size < 72)
    }

    @Test
    fun epochAlignment_hourAndUtcDay() {
        val ts = 3_723_456L
        val alignedHour = FileBackedMetricHistoryStore.alignToHour(ts)
        assertEquals(0L, alignedHour % FileBackedMetricHistoryStore.HOUR_MS)

        val alignedDay = FileBackedMetricHistoryStore.alignToUtcDay(ts)
        assertEquals(0L, alignedDay % FileBackedMetricHistoryStore.DAY_MS)
    }

    @Test
    fun persistedWarmCold_canBeReloaded() {
        val dir = Files.createTempDirectory("metric_history_recovery").toFile()
        val metricId = "m.persist"
        val store1 = FileBackedMetricHistoryStore(dir)

        repeat(36) { h ->
            store1.appendHotSample(metricId, NormalizedMetricSample(55.0, h * FileBackedMetricHistoryStore.HOUR_MS + 100L))
        }
        store1.compactHotToWarm(metricId, cutoffMs = 36 * FileBackedMetricHistoryStore.HOUR_MS)
        store1.compactWarmToCold(metricId, cutoffMs = 24 * FileBackedMetricHistoryStore.HOUR_MS)

        val store2 = FileBackedMetricHistoryStore(dir)
        val warm = store2.loadWarmBuckets(metricId, 0L)
        val cold = store2.loadColdBuckets(metricId, 0L)

        assertTrue(warm.isNotEmpty() || cold.isNotEmpty())
        assertTrue((warm + cold).all { it.meanLog() != null })
    }

    @Test
    fun persistedHotSamples_areReloadedAfterRestart() {
        val dir = Files.createTempDirectory("metric_history_hot_restart").toFile()
        val metricId = "m.hot.persist"
        val store1 = FileBackedMetricHistoryStore(dir)
        store1.appendHotSample(metricId, NormalizedMetricSample(61.0, 1_000L))
        store1.appendHotSample(metricId, NormalizedMetricSample(62.0, 2_000L))

        val store2 = FileBackedMetricHistoryStore(dir)
        val samples = store2.loadHotSamples(metricId, 0L)

        assertEquals(2, samples.size)
        assertEquals(61.0, samples.first().value, 0.0)
        assertEquals(2_000L, samples.last().tsMs)
    }

    @Test
    fun fileSizeCap_evictsOldestHotSamplesFirst() {
        val dir = Files.createTempDirectory("metric_history_file_cap").toFile()
        val store = FileBackedMetricHistoryStore(dir, maxFileBytes = 120L)
        val metricId = "m.cap"

        repeat(20) { index ->
            store.appendHotSample(metricId, NormalizedMetricSample(index.toDouble(), index.toLong()))
        }

        val file = dir.resolve("${metricId}_hot.csv")
        val samples = store.loadHotSamples(metricId, 0L)
        assertTrue(file.length() <= 120L)
        assertTrue(samples.isNotEmpty())
        assertEquals(19L, samples.last().tsMs)
        assertTrue(samples.zipWithNext().all { (left, right) -> left.tsMs < right.tsMs })
        assertTrue(samples.first().tsMs > 0L)
    }

    @Test
    fun legacyRestingHistory_isMigratedToContextualMetricId() {
        val dir = Files.createTempDirectory("metric_history_resting_migration").toFile()
        val legacy = dir.resolve("hr.resting_bpm_hot.csv")
        legacy.writeText("v1,ts_ms,value\n1000,62.0\n")

        val store = FileBackedMetricHistoryStore(dir)

        assertTrue(!legacy.exists())
        assertEquals(
            1,
            store.loadHotSamples("hr.resting_bpm.resting", 0L).size
        )
        assertTrue(dir.resolve("hr.resting_bpm.resting_hot.csv").exists())
    }

    @Test
    fun unknownPersistenceVersion_isIgnoredSafely() {
        val dir = Files.createTempDirectory("metric_history_unknown_version").toFile()
        val metricId = "m.version"
        Files.write(
            dir.toPath().resolve("${metricId}_hot.csv"),
            listOf("v2,ts_ms,value", "1000,60.0"),
            StandardOpenOption.CREATE
        )

        val store = FileBackedMetricHistoryStore(dir)
        assertTrue(store.loadHotSamples(metricId, 0L).isEmpty())
    }

    @Test
    fun legacyV1Buckets_reloadWithoutFabricatingLogMoments() {
        val dir = Files.createTempDirectory("metric_history_v1_compat").toFile()
        val metricId = "m.v1"
        Files.write(
            dir.toPath().resolve("${metricId}_warm.csv"),
            listOf(
                "v1,bucket_start,bucket_size,count,sum,sum_squares,min,max,first_ts,last_ts",
                "0,3600000,2,30.0,450.0,10.0,15.0,0,1000"
            ),
            StandardOpenOption.CREATE
        )

        val bucket = FileBackedMetricHistoryStore(dir).loadWarmBuckets(metricId, 0L).single()
        assertEquals(15.0, bucket.mean(), 0.0)
        assertEquals(null, bucket.meanLog())
    }

    @Test
    fun evictionWithoutExpiredRows_doesNotBumpHistoryVersion() {
        val dir = Files.createTempDirectory("metric_history_noop_eviction").toFile()
        val store = FileBackedMetricHistoryStore(dir)
        val metricId = "m.noop"
        store.appendHotSample(metricId, NormalizedMetricSample(60.0, 1_000L))
        val versionBefore = store.historyVersion(metricId)

        store.evictOlderThan(metricId, cutoffMs = 0L)

        assertEquals(versionBefore, store.historyVersion(metricId))
    }

    @Test
    fun scheduledCompactionRollsHotSamplesOlderThanHotWindowIntoWarm() {
        val dir = Files.createTempDirectory("metric_history_append_rollup").toFile()
        val store = FileBackedMetricHistoryStore(dir)
        val metricId = "m.rollup"
        val oldTs = 0L
        val newTs = 73L * FileBackedMetricHistoryStore.HOUR_MS

        store.appendHotSample(metricId, NormalizedMetricSample(55.0, oldTs))
        store.appendHotSample(metricId, NormalizedMetricSample(56.0, newTs))
        store.compactHotToWarm(metricId, cutoffMs = 72L * FileBackedMetricHistoryStore.HOUR_MS)

        assertEquals(1, store.loadHotSamples(metricId, Long.MIN_VALUE).size)
        assertEquals(1, store.loadWarmBuckets(metricId, Long.MIN_VALUE).size)
    }
}
