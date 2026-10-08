package com.aitech.weartak_civ.data

import android.app.Application
import android.location.Location
import androidx.datastore.preferences.core.edit
import com.aitech.weartak_civ.events.InteractionPliRefresh
import com.aitech.weartak_civ.events.PliRefreshReason
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.runBlocking
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class InteractionPliTriggersTest {
    @Before fun initializeDevice() {
        MainActivity.device_uid = "test-watch"
        MainActivity.versionNumber = "test"
    }

    class Observer {
        val reasons = mutableListOf<PliRefreshReason>()
        @Subscribe fun receive(event: InteractionPliRefresh) { reasons.add(event.reason) }
    }

    @Test fun persistedIdentityChangesOnlyTriggerOnceAndSnapshotUsesPersistedIdentity() = runBlocking {
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        repo.legacyDataStore().edit { it.clear() }
        val observer = Observer()
        EventBus.getDefault().register(observer)
        try {
            repo.storeCallsign("")
            repo.storeTeam("Orange")
            repo.storeRole("Team Member")
            assertEquals(emptyList<PliRefreshReason>(), observer.reasons)
            repo.storeCallsign("Fresh")
            repo.storeCallsign("Fresh")
            repo.storeTeam("Blue")
            repo.storeTeam("Blue")
            repo.storeRole("Medic")
            repo.storeRole("Medic")
            assertEquals(listOf(PliRefreshReason.CALLSIGN_CHANGED, PliRefreshReason.TEAM_CHANGED, PliRefreshReason.ROLE_CHANGED), observer.reasons)
            val system = repo.snapshotSystem()
            assertEquals("Fresh", system.callsign)
            assertEquals("Blue", system.team)
            assertEquals("Medic", system.role)
            val fix = Location("gps").apply {
                latitude = 41.0
                longitude = -87.0
                accuracy = 5f
                time = 1_700_000_000_000
            }
            repo.storeLatestLocation(Location("gps").apply { latitude = 42.0 })
            val snapshot = repo.snapshotInteractionPli(fix)
            assertEquals(fix.time, snapshot.time.time)
            assertEquals(fix.time, snapshot.loc?.timeMillis)
            assertEquals(fix.latitude, snapshot.loc!!.lat, 0.0)
            assertEquals("Fresh", snapshot.systemData.callsign)
        } finally {
            EventBus.getDefault().unregister(observer)
        }
    }

    @Test fun allAlertClearVariantsChatAndMapUseSharedRefreshPath() = runBlocking {
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        val observer = Observer()
        EventBus.getDefault().register(observer)
        try {
            repo.addManualAlertRequest(DataRepository.AlertState.ALERT, "SOS", "test", 1)
            repo.clearManualAlertRequestUid("one")
            repo.clearManualAlertRequestLast()
            repo.clearManualAlertRequestAll()
            repo.addPhysioAndAutoGenAlertRequest("two", "physio", "test", 1, false, 1)
            repo.clearPhysioAndAutoGenAlertRequestUid("two")
            repo.clearPhysioAndAutoGenAlertRequestLast()
            repo.clearPhysioAndAutoGenAlertRequestAll()
            repo.sendChatMessage("room", "room", "hello", 1)
            repo.requestInteractionPliRefresh(PliRefreshReason.MAP_OPENED)
            assertEquals(listOf(
                PliRefreshReason.MANUAL_ALERT_ACTIVATED,
                PliRefreshReason.MANUAL_ALERT_CLEARED, PliRefreshReason.MANUAL_ALERT_CLEARED, PliRefreshReason.MANUAL_ALERT_CLEARED,
                PliRefreshReason.PHYSIO_ALERT_ACTIVATED,
                PliRefreshReason.PHYSIO_ALERT_CLEARED, PliRefreshReason.PHYSIO_ALERT_CLEARED, PliRefreshReason.PHYSIO_ALERT_CLEARED,
                PliRefreshReason.CHAT_SENT, PliRefreshReason.MAP_OPENED
            ), observer.reasons)
        } finally {
            EventBus.getDefault().unregister(observer)
        }
    }
}
