package com.aitech.weartak_civ.presentation

import android.app.Application
import android.location.Location
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.TopDisplayMetric
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TopMetricDisplayTest {
    private fun value(metric: TopDisplayMetric, monitoring: Boolean = true, worn: Boolean = true,
                      location: Location? = null, locationEnabled: Boolean = true) =
        topMetricValue(metric, monitoring, worn, 80, .4f, 32f, 123,
            locationEnabled, location, "Disabled", "Off-Wrist", "No fix")

    @Test fun exactSixLocalizedSortedChoicesAndRemovedKeysFallBackWithoutChangingSteps() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        assertEquals(listOf("Exertion", "Heart Rate", "Lat/Long", "MGRS", "Skin Temp", "Steps"),
            topMetricChoices(app).map { it.second })
        MainActivity.device_uid = "test-watch"
        val repo = DataRepository(app)
        for (key in listOf("key_spo2", "key_spo2_tier", "key_calories", "key_floors", "key_crm", "unknown")) {
            repo.legacyDataStore().edit { it[stringPreferencesKey("selected_physio_metric")] = key }
            assertEquals("key_exertion", repo.getSelectedPhysioMetric.first())
        }
        repo.storeSelectedPhysioMetric("key_steps")
        assertEquals("key_steps", DataRepository(app).getSelectedPhysioMetric.first())
        repo.storeSelectedPhysioMetric("key_mgrs")
        assertEquals("key_mgrs", DataRepository(app).getSelectedPhysioMetric.first())
    }

    @Test fun disabledOverridesOffWristAndStaleMeasurementsIncludingSteps() {
        TopDisplayMetric.entries.filter { it.physiological }.forEach {
            assertEquals("Disabled", value(it, monitoring = false, worn = false))
        }
        listOf(TopDisplayMetric.EXERTION, TopDisplayMetric.HEART_RATE, TopDisplayMetric.SKIN_TEMP).forEach {
            assertEquals("Off-Wrist", value(it, worn = false))
        }
        assertEquals("123", value(TopDisplayMetric.STEPS, worn = false))
    }

    @Test fun coordinatesIgnorePhysioStateAndNeverInventZeroForMissingFix() {
        val fix = Location("test").apply { time = 100; latitude = 41.881; longitude = -87.63 }
        assertEquals("41.88100\n-87.63000", value(TopDisplayMetric.LAT_LONG, false, false, fix))
        assertEquals(Utils.latLonToMgrs(41.881, -87.63), value(TopDisplayMetric.MGRS, false, false, fix))
        for (metric in listOf(TopDisplayMetric.LAT_LONG, TopDisplayMetric.MGRS)) {
            assertEquals("No fix", value(metric))
            assertEquals("No fix", value(metric, location = fix, locationEnabled = false))
            assertEquals("No fix", value(metric, location = Location(fix).apply { latitude = Double.NaN }))
            assertEquals("No fix", value(metric, location = Location(fix).apply { time = 0 }))
        }
        assertEquals("0.00000\n0.00000", value(TopDisplayMetric.LAT_LONG,
            location = Location(fix).apply { latitude = 0.0; longitude = 0.0 }))
    }
}
