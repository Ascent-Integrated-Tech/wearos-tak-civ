package com.aitech.weartak_civ.presentation

import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.viewmodel.MapViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class WearTakApplicationColdStartTest {
    @Test
    fun mapViewModelCanBeCreatedBeforeMainActivity() {
        val repository = mockk<DataRepository>()
        every { repository.getTakServerList } returns MutableStateFlow(emptyList())
        every { repository.takChannels } returns MutableStateFlow(emptyList())
        every { repository.networkSettingsLocked } returns MutableStateFlow(true)
        val app = mockk<WearTakApplication>()
        every { app.repo } returns repository
        every { app.bhRepo } returns mockk()
        every { app.sitxRepo } returns mockk()
        WearTakApplication.installProcessRepositories(app)

        val viewModel = MapViewModel()
        assertSame(repository, MainActivity.dataRepository)
        assertSame(repository, ChatActivity.dataRepository)
        assertSame(repository, PhysioAlertsActivity.dataRepository)
        assertSame(repository.takChannels, viewModel.takChannels)
    }
}
