package com.aitech.weartak_civ.data

import android.app.Application
import android.location.Location
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PliStaleIntervalTest {
    @Test
    fun periodicAndInteractionPliUseTwiceTheCurrentIntervalPlusFifteenSeconds() = runBlocking {
        MainActivity.device_uid = "test-watch"
        MainActivity.versionNumber = "test"
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        val fix = Location("gps").apply {
            latitude = 41.0
            longitude = -87.0
            accuracy = 5f
            time = 1_700_000_000_000L
        }

        listOf(5, 15, 30, 60, 120, 300).forEach { interval ->
            repo.setAlmightyReportingInterval(interval)
            val ttlMillis = (2L * interval + 15L) * 1_000L
            val periodic = repo.snapshotPli().time
            val interaction = repo.snapshotInteractionPli(fix).time

            assertEquals(ttlMillis, periodic.stale - periodic.time)
            assertEquals(fix.time, interaction.time)
            assertEquals(fix.time + ttlMillis, interaction.stale)
            listOf(periodic, interaction).forEach { eventTime ->
                assertEquals(eventTime.stale, Instant.parse(eventTime.staleCot).toEpochMilli())
            }
        }
    }
}
