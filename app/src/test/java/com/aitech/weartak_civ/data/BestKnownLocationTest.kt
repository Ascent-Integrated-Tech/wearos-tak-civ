package com.aitech.weartak_civ.data

import android.app.Application
import android.location.Location
import android.os.SystemClock
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
    private suspend fun repository() =
        DataRepository(RuntimeEnvironment.getApplication()).also { it.storeLocationInput(LocationInput.FUSED) }

    private fun fix() = Location("fused").apply { elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() }

    @Test fun periodicPliWaitsForFixAndKeepsLastKnownPositionInsteadOfZeroFallback() = runBlocking {
        MainActivity.device_uid = "test-watch"
        MainActivity.versionNumber = "test"
        val repo = repository()
        assertNull(repo.snapshotPli().loc)
        repo.storeLatestLocation(fix())
        assertNull(repo.snapshotPliLocation())
        repo.storeLatestLocation(fix().apply {
            time = System.currentTimeMillis() - 60_000
            latitude = 41.879815
            longitude = -87.642469
            altitude = Double.NaN
            accuracy = 5f
        })
        repo.storeLatestLocation(fix().apply { latitude = Double.NaN })
        val snapshot = repo.snapshotPli().loc!!
        assertEquals(41.879815, snapshot.lat, 0.0)
        assertEquals(-87.642469, snapshot.lon, 0.0)
        assertNull(snapshot.hae)
        assertTrue(snapshot.ce!! > 5f)
        assertTrue(snapshot.timeMillis < System.currentTimeMillis() - 30_000)
    }

    @Test fun invalidAndOutOfOrderCallbacksCannotEraseTheNewestValidFix() = runBlocking {
        MainActivity.device_uid = "test-watch"
        MainActivity.versionNumber = "test"
        val repo = repository()
        assertNull(repo.bestKnownLocation())
        val newest = fix().apply {
            latitude = 41.0; longitude = -87.0; time = 1000; accuracy = 5f
        }
        repo.storeLatestLocation(newest)
        newest.latitude = 80.0
        repo.storeLatestLocation(fix().apply {
            latitude = Double.NaN; longitude = 0.0; time = 2000
        })
        assertEquals(41.0, repo.bestKnownLocation()!!.latitude, 0.0)
        repo.storeLatestLocation(fix().apply {
            latitude = 42.0; longitude = -88.0; time = 500
        })
        assertEquals(1000L, repo.bestKnownLocation()!!.time)
        repo.bestKnownLocation()!!.latitude = 70.0
        assertEquals(41.0, repo.bestKnownLocation()!!.latitude, 0.0)
    }

    @Test fun snapshotUsesOldValidFixWithOriginalTimestampAndUnknownMissingAccuracy() = runBlocking {
        MainActivity.device_uid = "test-watch"
        MainActivity.versionNumber = "test"
        val repo = repository()
        repo.storeLatestLocation(fix().apply {
            latitude = 41.0; longitude = -87.0; time = 1000
        })
        repo.storeLatestLocation(fix().apply { latitude = Double.NaN })
        val alert = DataRepository.CustomEmergencyAlert(
            "alert", DataRepository.AlertState.ALERT, "Alert", "911", 1, false, 2000, false,
        )
        val snapshot = repo.snapshotEmergencyEvent(alert)
        assertEquals(1000L, snapshot.location!!.timeMillis)
        assertEquals(41.0, snapshot.location.lat, 0.0)
        assertEquals(9_999_999f, snapshot.location.ce!!, 0f)
    }
}
