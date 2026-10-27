package com.aitech.weartak_civ.service

import android.app.Application
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.model.TakMission
import com.aitech.weartak_civ.data.model.TakMissionState
import com.aitech.weartak_civ.data.model.TakMissionStatus
import com.aitech.weartak_civ.data.model.TakServerMissions
import com.aitech.weartak_civ.data.takserver.TakMissionApi
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MissionListRefreshTest {
    private val enabled = MutableStateFlow(true)
    private val companion = MutableStateFlow(false)
    private val missions = MutableStateFlow<List<TakServerMissions>>(emptyList())
    private val repository = mockk<DataRepository>(relaxed = true)
    private val api = mockk<TakMissionApi>()
    private val scopeField = TAKConnectService::class.java.getDeclaredField("connectionScope").apply {
        isAccessible = true
    }
    private var oldScope: Any? = null
    private var oldDetails = mutableListOf<TAKConnectService.TakServerDetail>()
    private var oldStopping = false
    private var oldEnabled = true

    @Before fun prepare() {
        val app = RuntimeEnvironment.getApplication()
        MainActivity.mainActivity = mockk(relaxed = true) {
            every { applicationContext } returns app
        }
        MainActivity.dataRepository = repository
        every { repository.getEnableDataSync } returns enabled
        every { repository.isCompanionConnected } returns companion
        every { repository.shouldSuppressWatchTransportNow() } answers { companion.value }
        every { repository.takMissions } returns missions
        every { repository.takRouteCopies } returns emptyList()
        every { repository.getSubscribedMissions(any()) } answers {
            missions.value.firstOrNull { it.serverId == firstArg<String>() }
                ?.missions?.filter { it.subscribed }.orEmpty()
        }
        coEvery { api.getAllMissions() } returns listOf(TakMission("Available"))
        coEvery { api.getMissionChanges(any()) } returns emptyList()
        coEvery { api.getMissionCot(any()) } returns "<events/>"
        mockkObject(TAKConnectService.Companion, recordPrivateCalls = true)
        every { TAKConnectService.Companion["missionApi"](any<TAKConnectService.TakServerDetail>()) } returns api
        every { TAKConnectService.getTakServerDetail(any()) } answers {
            TAKConnectService.takServerDetails.firstOrNull { it.serverId == firstArg<String>() }
        }
        oldScope = scopeField.get(null)
        oldDetails = TAKConnectService.takServerDetails
        oldStopping = TAKConnectService.serviceStopping
        oldEnabled = TAKConnectService.takEndpointEnabled
        TAKConnectService.serviceStopping = false
        TAKConnectService.takEndpointEnabled = true
        TAKConnectService.takServerDetails = mutableListOf()
    }

    @After fun cleanup() {
        scopeField.set(null, oldScope)
        TAKConnectService.takServerDetails = oldDetails
        TAKConnectService.serviceStopping = oldStopping
        TAKConnectService.takEndpointEnabled = oldEnabled
        unmockkAll()
    }

    @Test fun eachEntryFetchesEachEligibleServerOnceWithoutCooldown() = runBlocking {
        val one = detail("one")
        val two = detail("two")
        detail("disabled").isEnabled = false
        detail("offline").connectionStatus.set(TAKConnectService.TAK_SERVER_CONNECTION_FAILED)
        TAKConnectService.refreshMissionListsOnScreenEntry()
        coVerify(exactly = 2) { api.getAllMissions() }
        listOf(one, two).forEach {
            verify(exactly = 1) {
                repository.updateTakMissions(it.serverId, it.name, it.address, listOf(TakMission("Available")))
            }
        }
        TAKConnectService.refreshMissionListsOnScreenEntry()
        coVerify(exactly = 4) { api.getAllMissions() }
    }

    @Test fun pausedEntryDoesNotWaitForEnableAndCompanionOrDisabledEndpointSkips() = runBlocking {
        detail("one")
        enabled.value = false
        TAKConnectService.refreshMissionListsOnScreenEntry()
        enabled.value = true
        coVerify(exactly = 0) { api.getAllMissions() }
        companion.value = true
        TAKConnectService.refreshMissionListsOnScreenEntry()
        companion.value = false
        TAKConnectService.takEndpointEnabled = false
        TAKConnectService.refreshMissionListsOnScreenEntry()
        coVerify(exactly = 0) { api.getAllMissions() }
        TAKConnectService.takEndpointEnabled = true
        TAKConnectService.refreshMissionListsOnScreenEntry()
        coVerify(exactly = 1) { api.getAllMissions() }
    }

    @Test fun failedServerDoesNotPreventOtherServersListAndScreenCancellationStopsWork() = runBlocking {
        detail("one")
        detail("two")
        coEvery { api.getAllMissions() } throws IOException("offline")
        TAKConnectService.refreshMissionListsOnScreenEntry()
        coVerify(exactly = 2) { api.getAllMissions() }
        verify(exactly = 2) { repository.updateTakMissionError(any(), any(), any(), "offline") }
        val started = CompletableDeferred<Unit>()
        coEvery { api.getAllMissions() } coAnswers {
            started.complete(Unit)
            awaitCancellation()
        }
        val entry = launch {
            TAKConnectService.refreshMissionListsOnScreenEntry()
        }
        started.await()
        entry.cancelAndJoin()
        assertTrue(entry.isCancelled)
        verify(exactly = 0) { repository.updateTakMissions(any(), any(), any(), any()) }
    }

    @Test fun replacedConnectionCannotPublishFetchedList() = runBlocking {
        val server = detail("one")
        coEvery { api.getAllMissions() } answers {
            server.runtime.currentAttempt = TAKConnectService.ConnectionAttempt(2)
            server.runtime.generation = 2
            listOf(TakMission("Stale"))
        }
        TAKConnectService.refreshMissionListsOnScreenEntry()
        verify(exactly = 0) { repository.updateTakMissions(any(), any(), any(), any()) }
    }

    @Test fun connectResumeAndRepeatedContentPollingNeverDiscoverList() = runTest {
        val server = detail("one")
        scopeField.set(null, backgroundScope)
        val joined = TakMissionState(TakMission("Joined"), subscribed = true, status = TakMissionStatus.IDLE)
        coEvery { repository.restorePersistedMissionState("one", "one", "one.invalid") } coAnswers {
            missions.value = listOf(TakServerMissions("one", "one", "one.invalid", listOf(joined)))
        }
        startAfterConnect(server)
        runCurrent()
        coVerify(exactly = 1) { api.getMissionChanges("Joined") }
        advanceTimeBy(60_001)
        runCurrent()
        coVerify(exactly = 4) { api.getMissionChanges("Joined") }
        enabled.value = false
        runCurrent()
        advanceTimeBy(60_001)
        runCurrent()
        coVerify(exactly = 4) { api.getMissionChanges("Joined") }
        enabled.value = true
        runCurrent()
        coVerify(exactly = 5) { api.getMissionChanges("Joined") }
        coVerify(exactly = 2) { repository.restorePersistedMissionState("one", "one", "one.invalid") }
        coVerify(exactly = 0) { api.getAllMissions() }
        server.runtime.currentAttempt = TAKConnectService.ConnectionAttempt(2)
        server.runtime.generation = 2
        advanceTimeBy(1_001)
        runCurrent()
        startAfterConnect(server)
        runCurrent()
        coVerify(exactly = 6) { api.getMissionChanges("Joined") }
        coVerify(exactly = 0) { api.getAllMissions() }
    }

    @Test fun diagnosticsAppendWithoutRenumberingMotionConfirmation() {
        assertEquals(41, DiagnosticEvent.MOTION_CONFIRMATION.ordinal)
        assertEquals(42, DiagnosticEvent.POWER_CONTEXT.ordinal)
        assertEquals(43, DiagnosticEvent.DEVICE_SETTING.ordinal)
        assertEquals(44, DiagnosticEvent.RESOURCE_LIFETIME.ordinal)
        assertEquals(45, DiagnosticEvent.MISSION_LIST.ordinal)
    }

    private fun startAfterConnect(server: TAKConnectService.TakServerDetail) {
        TAKConnectService.Companion::class.java.getDeclaredMethod(
            "loadTakMissionsAfterConnect",
            TAKConnectService.TakServerDetail::class.java,
            TAKConnectService.ConnectionAttempt::class.java
        ).apply { isAccessible = true }.invoke(TAKConnectService.Companion, server, server.runtime.currentAttempt)
    }

    private fun detail(id: String): TAKConnectService.TakServerDetail {
        val server = TAKConnectService.TakServerDetail(
            id, true, id, "$id.invalid", 8089, false, "", "", null, null
        )
        server.runtime.generation = 1
        server.runtime.currentAttempt = TAKConnectService.ConnectionAttempt(1)
        server.connectionStatus.set(TAKConnectService.TAK_SERVER_CONNECTED_COT_PORT)
        TAKConnectService.takServerDetails.add(server)
        return server
    }
}
