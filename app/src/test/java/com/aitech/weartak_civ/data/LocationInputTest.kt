package com.aitech.weartak_civ.data

import android.app.Application
import android.location.Location
import android.os.SystemClock
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.service.CotDispatchService.BleLocationOutput
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LocationInputTest {
    @Before fun clearSelection() = runBlocking {
        MainActivity.device_uid = "test-watch"
        MainActivity.versionNumber = "test"
        DataRepository(RuntimeEnvironment.getApplication()).legacyDataStore().edit {
            it.remove(stringPreferencesKey("location_input"))
        }
        Unit
    }

    private fun fix(provider: String) = Location(provider).apply {
        latitude = 41.0; longitude = -87.0; accuracy = 5f; altitude = 100.0
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    @Test fun unsetDefaultIsRealFusedWithoutWritingAnImplicitChoiceOrRelabelingGps() = runBlocking {
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        assertEquals(LocationInput.FUSED, repo.getLocationInput.first())
        assertFalse(repo.hasConfiguredLocationInput.first())
        assertNull(LocationInput.fromKey("invalid-provider"))
        repo.storeLatestLocation(fix("gps"))
        assertNull(repo.latestLocation.value)
        assertNull(repo.bestKnownLocation())
        repo.storeLatestLocation(fix("network"))
        assertNull(repo.bestKnownLocation())
        repo.storeLatestLocation(fix("fused"))
        assertEquals("fused", repo.bestKnownLocation()?.provider)
        assertFalse(repo.hasConfiguredLocationInput.first())
        assertEquals(LocationInput.FUSED, DataRepository(RuntimeEnvironment.getApplication()).getLocationInput.first())
    }

    @Test fun explicitGnssFusedAndLegacyNetworkSurviveRepositoryRecreation() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val store = DataRepository(app).legacyDataStore()
        for (input in LocationInput.entries) {
            store.edit { it[stringPreferencesKey("location_input")] = input.provider }
            val upgraded = DataRepository(app)
            assertEquals(input, upgraded.getLocationInput.first())
            assertTrue(upgraded.hasConfiguredLocationInput.first())
            assertEquals(input, DataRepository(app).getLocationInput.first())
        }
        store.edit { it[stringPreferencesKey("location_input")] = "invalid-provider" }
        assertNull(DataRepository(app).getLocationInput.first())
    }

    @Test fun switchingInputClearsBothCachesAndRejectsDelayedOtherProviderAndPreviousGeneration() = runBlocking {
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        repo.storeLocationInput(LocationInput.INTERNAL_GNSS)
        val old = fix("gps")
        repo.storeLatestLocation(old)
        ShadowSystemClock.advanceBy(Duration.ofMillis(10))
        assertTrue(repo.storeLocationInput(LocationInput.NETWORK))
        assertNull(repo.latestLocation.value)
        assertNull(repo.bestKnownLocation())
        repo.storeLatestLocation(old)
        assertNull(repo.latestLocation.value)
        val network = fix("network")
        repo.storeLatestLocation(network)
        assertEquals("network", repo.bestKnownLocation()?.provider)
        val snapshot = repo.snapshotLocation(network)!!
        assertEquals("network", snapshot.provider)
        ShadowSystemClock.advanceBy(Duration.ofMillis(10))
        assertTrue(repo.storeLocationInput(LocationInput.INTERNAL_GNSS))
        assertFalse(repo.isCurrentLocationSnapshot(snapshot))
        repo.storeLatestLocation(old)
        assertNull(repo.latestLocation.value)
        assertNull(repo.bestKnownLocation())
        val fresh = fix("gps").apply { latitude = 0.0; longitude = 0.0 }
        repo.storeLatestLocation(fresh)
        assertEquals(0.0, repo.bestKnownLocation()!!.latitude, 0.0)
    }

    @Test fun unchangedProviderSelectionStillRequiresANewFixAndKeepsActualSampleTime() = runBlocking {
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        repo.storeLocationInput(LocationInput.INTERNAL_GNSS)
        val previous = fix("gps")
        repo.storeLatestLocation(previous)
        ShadowSystemClock.advanceBy(Duration.ofMillis(10))
        repo.storeLocationInput(LocationInput.INTERNAL_GNSS)
        repo.storeLatestLocation(previous)
        assertNull(repo.bestKnownLocation())
        val fresh = fix("gps")
        repo.storeLatestLocation(fresh)
        assertEquals(fresh.time, repo.snapshotInteractionPli(fresh).time.time)
        assertEquals(fresh.time, repo.snapshotLocation()!!.timeMillis)
        assertTrue(repo.hasConfiguredLocationInput.first())
    }

    @Test fun phoneManagedLockRejectsSelectionWithoutClearingTheCurrentFix() = runBlocking {
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        repo.storeLocationInput(LocationInput.INTERNAL_GNSS)
        repo.storeLatestLocation(fix("gps"))
        repo.enablePhoneManagedSettings("AA:BB:CC:DD:EE:FF")
        repo.setPhoneManagementSessionActive(true)
        try {
            assertFalse(repo.storeLocationInput(LocationInput.NETWORK))
            assertEquals(LocationInput.INTERNAL_GNSS, repo.getLocationInput.first())
            assertEquals("gps", repo.bestKnownLocation()?.provider)
            assertNotNull(repo.latestLocation.value)
        } finally { repo.setPhoneManagementSessionActive(false) }
    }

    @Test fun managedUnsetDefaultCannotBecomeAnExplicitGnssChoice() = runBlocking {
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        repo.enablePhoneManagedSettings("AA:BB:CC:DD:EE:FF")
        repo.setPhoneManagementSessionActive(true)
        try {
            assertFalse(repo.storeLocationInput(LocationInput.INTERNAL_GNSS))
            assertEquals(LocationInput.FUSED, repo.getLocationInput.first())
            assertFalse(repo.hasConfiguredLocationInput.first())
        } finally { repo.setPhoneManagementSessionActive(false) }
    }

    @Test fun explicitBleRefinementPreservesAcquisitionProviderButDoesNotClaimItsCoordinatesAreGnss() = runBlocking {
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        repo.storeLocationInput(LocationInput.INTERNAL_GNSS)
        repo.storeEnableBleBeaconService(true)
        mockkObject(Utils.LocationUtils)
        try {
            every { Utils.LocationUtils.mapLocationAgainstClosestBleBeacon(any()) } returns
                BleLocationOutput(42.0, -88.0, 200.0, 2f)
            val snapshot = repo.snapshotLocation(fix("gps"))!!
            assertEquals("gps", snapshot.provider)
            assertEquals("ble", snapshot.coordinateSource)
            assertEquals(42.0, snapshot.lat, 0.0)
            assertTrue(repo.isCurrentLocationSnapshot(snapshot))
        } finally {
            unmockkObject(Utils.LocationUtils)
            repo.storeEnableBleBeaconService(false)
        }
    }
}
