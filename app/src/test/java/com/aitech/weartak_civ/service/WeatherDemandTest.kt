package com.aitech.weartak_civ.service

import android.app.Application
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class WeatherDemandTest {
    @Test fun demandStartsAndStopsAcrossEffectiveConfigurationChanges() = runTest {
        val demand = MutableStateFlow(false)
        val config = ServiceConfiguration(RuntimeEnvironment.getApplication(), mockk(), mockk(),
            demand, "test-key")
        val observed = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            config.getServiceEnabledFlow(ReactiveServiceManager.ServiceType.WEATHER).collect { observed += it }
        }
        demand.value = true
        demand.value = false
        demand.value = true
        assertEquals(listOf(false, true, false, true), observed)
    }

    @Test fun absentKeyNeverRequestsServiceEvenWithActiveConsumers() = runTest {
        for (key in listOf(null, "", "  ")) {
            val demand = MutableStateFlow(true)
            val config = ServiceConfiguration(RuntimeEnvironment.getApplication(), mockk(), mockk(), demand, key)
            val observed = mutableListOf<Boolean>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                config.getServiceEnabledFlow(ReactiveServiceManager.ServiceType.WEATHER).collect { observed += it }
            }
            demand.value = false
            demand.value = true
            assertEquals(listOf(false), observed)
            collector.cancel()
        }
    }
}
