package com.aitech.weartak_civ.data

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aitech.weartak_civ.data.model.TakMission
import com.aitech.weartak_civ.data.model.TakMissionStatus
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MissionListStateTest {
    private lateinit var repository: DataRepository

    @Before fun prepare(): Unit = runBlocking {
        MainActivity.device_uid = "test-watch"
        repository = DataRepository(RuntimeEnvironment.getApplication())
        repository.legacyDataStore().edit {
            it.remove(stringPreferencesKey("tak_mission_subscriptions"))
        }
    }

    @Test fun coldStartRestoresOnlyThisServersJoinedMissionsWithoutDiscovery() = runBlocking {
        repository.persistMissionSubscribed("one", "Joined")
        repository.persistMissionSyncProgress("one", "Joined", 1234L, setOf("seen"))
        repository.persistMissionSubscribed("two", "Other")
        repository.restorePersistedMissionState("one", "One", "one.example")

        val server = repository.takMissions.value.single()
        assertEquals("one", server.serverId)
        val joined = repository.getSubscribedMissions("one").single()
        assertEquals("Joined", joined.mission.name)
        assertEquals(1234L, joined.lastSyncTime)
        assertEquals(setOf("seen"), joined.processedChangeKeys)
        assertEquals(TakMissionStatus.IDLE, joined.status)
        assertTrue(repository.getSubscribedMissions("two").isEmpty())

        val discovered = TakMission("Joined", creatorUid = "creator")
        repository.updateTakMissions("one", "One", "one.example", listOf(discovered, TakMission("Available")))
        val updated = repository.getSubscribedMissions("one").single()
        assertEquals(discovered, updated.mission)
        assertEquals(joined.processedChangeKeys, updated.processedChangeKeys)
        assertEquals(joined.lastSyncTime, updated.lastSyncTime)
        assertFalse(repository.takMissions.value.single().missions.last().subscribed)
    }

    @Test fun unchangedListRetainsStateObjectsAndResumeDoesNotResetActiveSync() = runBlocking {
        val missions = listOf(TakMission("Joined"), TakMission("Available"))
        repository.updateTakMissions("one", "One", "one.example", missions)
        repository.persistMissionSubscribed("one", "Joined")
        repository.restorePersistedMissionState("one", "One", "one.example")
        repository.setMissionSyncing("one", "Joined")
        val before = repository.takMissions.value

        repository.updateTakMissions("one", "One", "one.example", missions)
        repository.restorePersistedMissionState("one", "One", "one.example")
        assertSame(before, repository.takMissions.value)
        assertEquals(TakMissionStatus.SYNCING, repository.getSubscribedMissions("one").single().status)
    }

    @Test fun unchangedEmptyListClearsPriorErrorAndLeavesOtherServersAlone() {
        repository.updateTakMissions("one", "One", "one.example", emptyList())
        repository.updateTakMissions("two", "Two", "two.example", listOf(TakMission("Other")))
        val other = repository.takMissions.value.last()
        repository.updateTakMissionError("one", "One", "one.example", "offline")
        repository.updateTakMissions("one", "One", "one.example", emptyList())
        val server = repository.takMissions.value.first { it.serverId == "one" }
        assertEquals(TakMissionStatus.EMPTY, server.status)
        assertNull(server.errorMessage)
        assertSame(other, repository.takMissions.value.first { it.serverId == "two" })
    }
}
