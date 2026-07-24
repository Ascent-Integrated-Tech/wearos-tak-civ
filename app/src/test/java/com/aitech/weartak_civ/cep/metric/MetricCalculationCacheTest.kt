package com.aitech.weartak_civ.cep.metric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class MetricCalculationCacheTest {
    @Test
    fun cacheRoundTrip_preservesCoreAndRejectsWrongHash() {
        val directory = Files.createTempDirectory("metric_calculation_cache").toFile()
        val store = FileBackedMetricCalculationCacheStore(directory)
        val core = MetricBaselineCore(
            baseline = 72.5,
            shortTerm = 73.0,
            longTerm = 71.0,
            deviation = 0.5,
            percentDeviation = 0.69,
            lowThreshold = 58.0,
            lowThresholdWarn = 65.0,
            confidenceBase = 0.76,
            sampleCount = 42,
            coverageSeconds = 86_400,
            coveredSeconds = 43_200,
            activeDays = 3,
            latestValue = 74.0,
            latestTsMs = 123_000L
        )
        store.save("hr.test", "config-a", CachedMetricCore(456_000L, 42L, core))

        val loaded = store.load("hr.test", "config-a")
        assertNotNull(loaded)
        assertEquals(456_000L, loaded!!.calculatedAtMs)
        assertEquals(core, loaded.core)
        assertNull(store.load("hr.test", "config-b"))
    }
}
