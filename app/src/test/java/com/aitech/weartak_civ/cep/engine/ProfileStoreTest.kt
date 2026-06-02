package com.aitech.weartak_civ.cep.engine

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProfileStoreTest {

    @Test
    fun `hr resting metric baseline keeps legacy compatibility keys in sync`() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("cep_profile", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val store = ProfileStore(app)
        store.putMetricBaseline(
            ProfileStore.MetricBaselineSnapshot(
                metricId = "hr.resting_bpm",
                value = 58.0,
                sampleCount = 12,
                confidence = BaselineConfidence.MEDIUM,
                status = BaselineStatus.READY_MEDIUM_CONFIDENCE,
                windowStartTs = null,
                windowEndTs = 2_000L,
                lastUpdatedMs = 3_000L
            )
        )

        assertEquals(58.0f, prefs.getFloat("baseline.hr.resting_bpm.value", -1f))
        assertEquals(12, prefs.getInt("baseline.hr.resting_bpm.samples", -1))
        assertEquals(3_000L, prefs.getLong("baseline.hr.resting_bpm.updated_ms", -1L))

        store.clearMetricBaseline("hr.resting_bpm")

        assertFalse(prefs.contains("baseline.hr.resting_bpm.value"))
        assertFalse(prefs.contains("baseline.hr.resting_bpm.samples"))
        assertFalse(prefs.contains("baseline.hr.resting_bpm.updated_ms"))
    }
}
