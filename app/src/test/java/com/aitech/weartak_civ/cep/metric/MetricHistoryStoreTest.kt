package com.aitech.weartak_civ.cep.metric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

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
    }
}
