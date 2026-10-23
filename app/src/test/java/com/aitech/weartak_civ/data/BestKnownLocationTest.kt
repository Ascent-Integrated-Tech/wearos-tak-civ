package com.aitech.weartak_civ.data

import android.app.Application
import android.location.Location
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BestKnownLocationTest {
    @Test fun invalidAndOutOfOrderCallbacksCannotEraseTheNewestValidFix() = runBlocking {
        MainActivity.device_uid = "test-watch"
        MainActivity.versionNumber = "test"
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        assertNull(repo.bestKnownLocation())
        val fix = Location("gps").apply {
            latitude = 41.0; longitude = -87.0; time = 1000; accuracy = 5f
        }
        repo.storeLatestLocation(fix)
        fix.latitude = 80.0
        repo.storeLatestLocation(Location("gps").apply {
            latitude = Double.NaN; longitude = 0.0; time = 2000
        })
        assertEquals(41.0, repo.bestKnownLocation()!!.latitude, 0.0)
        repo.storeLatestLocation(Location("gps").apply {
            latitude = 42.0; longitude = -88.0; time = 500
        })
        assertEquals(1000L, repo.bestKnownLocation()!!.time)
        repo.bestKnownLocation()!!.latitude = 70.0
        assertEquals(41.0, repo.bestKnownLocation()!!.latitude, 0.0)
    }

    @Test fun snapshotUsesOldValidFixWithOriginalTimestampAndUnknownMissingAccuracy() = runBlocking {
        MainActivity.device_uid = "test-watch"
        MainActivity.versionNumber = "test"
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        repo.storeLatestLocation(Location("gps").apply {
            latitude = 41.0; longitude = -87.0; time = 1000
        })
        repo.storeLatestLocation(Location("gps").apply { latitude = Double.NaN })
        val alert = DataRepository.CustomEmergencyAlert(
            "alert", DataRepository.AlertState.ALERT, "Alert", "911", 1, false, 2000, false,
        )
        val snapshot = repo.snapshotEmergencyEvent(alert)
        assertEquals(1000L, snapshot.location!!.timeMillis)
        assertEquals(41.0, snapshot.location.lat, 0.0)
        assertEquals(9_999_999f, snapshot.location.ce!!, 0f)
    }
}
