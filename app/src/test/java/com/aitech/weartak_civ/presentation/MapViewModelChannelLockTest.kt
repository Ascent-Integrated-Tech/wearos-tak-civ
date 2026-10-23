package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.viewmodel.MapViewModel
import com.aitech.weartak_civ.service.TAKConnectService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MapViewModelChannelLockTest {
    private val dispatcher = StandardTestDispatcher()
    private val locked = MutableStateFlow(false)
    private val store = ViewModelStore()
    private lateinit var vm: MapViewModel

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        val activity = mockk<MainActivity>(relaxed = true)
        every { activity.applicationContext } returns RuntimeEnvironment.getApplication()
        MainActivity.mainActivity = activity
        val repo = mockk<DataRepository>()
        every { repo.getTakServerList } returns MutableStateFlow(emptyList())
        every { repo.takChannels } returns MutableStateFlow(emptyList())
        every { repo.networkSettingsLocked } returns locked
        MainActivity.dataRepository = repo
        mockkObject(TAKConnectService.Companion)
        every { TAKConnectService.refreshTakChannels(any()) } returns Unit
        every { TAKConnectService.updateTakChannel(any(), any(), any()) } returns Unit
        vm = MapViewModel()
        store.put("map", vm)
    }

    @After fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test fun lockStartsClosedAndTracksLiveChanges() = runTest(dispatcher) {
        assertTrue(vm.networkSettingsLocked.value)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.networkSettingsLocked.collect {}
        }
        advanceUntilIdle()
        assertFalse(vm.networkSettingsLocked.value)
        locked.value = true
        advanceUntilIdle()
        assertTrue(vm.networkSettingsLocked.value)
        locked.value = false
        advanceUntilIdle()
        assertFalse(vm.networkSettingsLocked.value)
    }

    @Test fun lockedActionsAreBlockedEvenWithoutUiSubscription() = runTest(dispatcher) {
        locked.value = true
        vm.refreshTakChannels("server")
        vm.updateTakChannel("server", 1, true)
        advanceUntilIdle()
        verify(exactly = 0) { TAKConnectService.refreshTakChannels(any()) }
        verify(exactly = 0) { TAKConnectService.updateTakChannel(any(), any(), any()) }
    }

    @Test fun actionsReadCurrentLockAndResumeAfterUnlock() = runTest(dispatcher) {
        vm.updateTakChannel("server", 1, true)
        locked.value = true
        advanceUntilIdle()
        verify(exactly = 0) { TAKConnectService.updateTakChannel(any(), any(), any()) }
        locked.value = false
        vm.refreshTakChannels("server")
        vm.updateTakChannel("server", 1, false)
        advanceUntilIdle()
        verify(exactly = 1) { TAKConnectService.refreshTakChannels("server") }
        verify(exactly = 1) { TAKConnectService.updateTakChannel("server", 1, false) }
    }
}
