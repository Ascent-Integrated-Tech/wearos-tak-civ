package com.aitech.weartak_civ.service

import android.app.Application
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PowerManagerLifecycleTest {
    private fun service(scope: CoroutineScope): PowerManagerService {
        MainActivity.mainActivity = mockk(relaxed = true) {
            every { applicationContext } returns RuntimeEnvironment.getApplication()
        }
        val repo = mockk<DataRepository>(relaxed = true)
        every { repo.dockType } returns MutableStateFlow(BatteryPowerObserver.DockType.NONE)
        every { repo.getEnableWifiBatteryOptimization } returns flowOf(true)
        every { repo.getEnableAllWifiBatteryOptimization } returns flowOf(true)
        every { repo.getWifiSsidList } returns flowOf(emptyList())
        MainActivity.dataRepository = repo
        return Robolectric.buildService(PowerManagerService::class.java).get().also { service ->
            PowerManagerService::class.java.getDeclaredField("scope").apply {
                isAccessible = true
                (get(service) as CoroutineScope).cancel()
                set(service, scope)
            }
        }
    }

    @Test fun startupRunsAfterFiveSecondsAndResetsOnEachActivation() = runTest {
        PowerManagerService.initialStartDelayDone.set(true)
        val service = service(backgroundScope)
        service.onCreate()
        try {
            assertFalse(PowerManagerService.initialStartDelayDone.get())
            runCurrent()
            advanceTimeBy(4_999)
            runCurrent()
            assertFalse(PowerManagerService.initialStartDelayDone.get())
            advanceTimeBy(1)
            runCurrent()
            assertTrue(PowerManagerService.initialStartDelayDone.get())
            assertTrue(PowerManagerService.isRunning.get())
        } finally { service.onDestroy() }
        assertFalse(PowerManagerService.initialStartDelayDone.get())
    }

    @Test fun stoppingBeforeStartupCancelsPendingWorkWithoutDedicatedThread() = runTest {
        val service = service(backgroundScope)
        service.onCreate()
        runCurrent()
        service.onDestroy()
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(PowerManagerService.initialStartDelayDone.get())
        assertFalse(PowerManagerService.isRunning.get())
        assertFalse(PowerManagerService::class.java.declaredFields.any {
            it.type == android.os.HandlerThread::class.java
        })
    }
}
