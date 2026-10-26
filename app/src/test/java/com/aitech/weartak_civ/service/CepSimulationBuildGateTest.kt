package com.aitech.weartak_civ.service

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import com.aitech.weartak_civ.BuildConfig
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.service.ReactiveServiceManager.ServiceType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CepSimulationBuildGateTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        MainActivity.mainActivity = mockk(relaxed = true) { every { applicationContext } returns app }
    }

    @Test fun registryAndDesiredStateMatchBuildFlag() = runBlocking {
        val registry = ServiceRegistry()
        val configuration = ServiceConfiguration(app, mockk(relaxed = true), mockk(relaxed = true))
        assertEquals(BuildConfig.CEP_SIMULATION,
            configuration.getServiceEnabledFlow(ServiceType.CEP_SIMULATION).first())
        assertEquals(BuildConfig.CEP_SIMULATION,
            ServiceType.CEP_SIMULATION in registry.getAllServiceTypes())
        assertEquals(if (BuildConfig.CEP_SIMULATION) CEPSimulationService::class.java else null,
            registry.getServiceClass(ServiceType.CEP_SIMULATION))
        if (!BuildConfig.CEP_SIMULATION) {
            registry.setServiceRunning(ServiceType.CEP_SIMULATION, true)
            assertFalse(registry.isServiceRunning(ServiceType.CEP_SIMULATION))
            assertFalse(ServiceType.CEP_SIMULATION in registry.getRunningServices())
            assertFalse(ServiceType.CEP_SIMULATION in registry.getStoppedServices())
        }
    }

    @Test fun manifestComponentMatchesBuildFlag() {
        @Suppress("DEPRECATION")
        val info = app.packageManager.getServiceInfo(
            ComponentName(app, CEPSimulationService::class.java), PackageManager.GET_DISABLED_COMPONENTS)
        assertEquals(BuildConfig.CEP_SIMULATION, info.enabled)
    }

    @Test fun publicEntryPointsCannotStartServiceWhenDisabled() {
        CEPSimulationService.start(app)
        if (BuildConfig.CEP_SIMULATION) {
            assertEquals(CEPSimulationService::class.java.name,
                shadowOf(app).nextStartedService.component?.className)
        } else {
            CEPSimulationService.startPhase(app, "P1")
            CEPSimulationService.startScenario(app, "fixture")
            CEPSimulationService.startSpo2Scenario(app, "fixture")
            CEPSimulationService.stop(app)
            assertNull(shadowOf(app).nextStartedService)
        }
    }

    @Test fun disabledDirectCreationHasNoNotificationOrChannel() {
        val controller = Robolectric.buildService(CEPSimulationService::class.java).create()
        try {
            val service = controller.get()
            val notificationManager = app.getSystemService(NotificationManager::class.java)
            if (BuildConfig.CEP_SIMULATION) {
                assertEquals(8123, shadowOf(service).lastForegroundNotificationId)
                assertTrue(notificationManager.getNotificationChannel("cep_sim_channel") != null)
            } else {
                assertTrue(shadowOf(service).isStoppedBySelf)
                assertNull(shadowOf(service).lastForegroundNotification)
                assertNull(notificationManager.getNotificationChannel("cep_sim_channel"))
                assertEquals(android.app.Service.START_NOT_STICKY,
                    service.onStartCommand(Intent(app, CEPSimulationService::class.java), 0, 1))
                assertNull(shadowOf(service).lastForegroundNotification)
            }
        } finally {
            controller.destroy()
        }
    }
}
