package com.aitech.weartak_civ.service

import android.Manifest
import android.app.Application
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.service.ReactiveServiceManager.ServiceType
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.EnumMap

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReactiveServiceStartOrderTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var manager: ReactiveServiceManager
    private lateinit var registry: ServiceRegistry

    @Before fun setup() {
        MainActivity.mainActivity = mockk(relaxed = true) { every { applicationContext } returns app }
        registry = ServiceRegistry()
        shadowOf(app).grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES
        )
        ServiceType.entries.forEach { registry.setServiceRunning(it, false) }
        manager = Robolectric.buildService(ReactiveServiceManager::class.java).get()
        val repo = mockk<DataRepository>(relaxed = true)
        set("repo\$delegate", lazyOf(repo))
        set("registry", registry)
        set("config", ServiceConfiguration(app, repo))
        set("runnableServicesList", registry.getAllServiceTypes())
    }

    @After fun reset() = ServiceType.entries.forEach { registry.setServiceRunning(it, false) }

    @Test fun companionStartsBeforeSlowerCoreServicesOnColdStart() {
        desire(ServiceType.TAK_CONNECT, ServiceType.COT_DISPATCH, ServiceType.LOCATION,
            ServiceType.BLE_BEACON, ServiceType.COMPANION_APP, ServiceType.CEP_SIMULATION)

        val started = reconcile()

        assertEquals(CompanionAppService::class.java.name, started.first())
        assertEquals(6, started.size)
    }

    @Test fun otherServicesKeepDependencyOrderAfterCompanion() {
        desire(ServiceType.TAK_CONNECT, ServiceType.CHAT, ServiceType.LOCATION,
            ServiceType.WEATHER, ServiceType.COMPANION_APP)

        val started = reconcile()

        assertEquals(
            listOf(CompanionAppService::class.java, TAKConnectService::class.java,
                ChatService::class.java, LocationService::class.java, WeatherService::class.java)
                .map { it.name },
            started
        )
    }

    @Test fun companionStillPreemptsDesiredDitto() {
        desire(ServiceType.DITTO, ServiceType.COMPANION_APP)

        assertEquals(listOf(CompanionAppService::class.java.name), reconcile())
    }

    @Test fun disabledCompanionIsNotStarted() {
        desire(ServiceType.TAK_CONNECT)

        assertEquals(listOf(TAKConnectService::class.java.name), reconcile())
    }

    @Test fun disablingRunningCompanionStopsOnlyCompanionNotBeacon() {
        registry.setServiceRunning(ServiceType.COMPANION_APP, true)
        registry.setServiceRunning(ServiceType.BLE_BEACON, true)
        desire(ServiceType.BLE_BEACON)

        assertEquals(emptyList<String>(), reconcile())
        val shadow = shadowOf(app)
        val stopped = generateSequence { shadow.nextStoppedService }.map { it.component!!.className }.toList()
        assertEquals(listOf(CompanionAppService::class.java.name), stopped)
    }

    @Test fun savedCompanionToggleControlsItsOwnFlowIndependentlyOfBeacon() = runBlocking {
        val enabled = MutableStateFlow(false)
        val repo = mockk<DataRepository>(relaxed = true)
        every { repo.getEnableCompanionAppService } returns enabled
        every { repo.getEnableBleBeaconService } returns MutableStateFlow(true)
        val config = ServiceConfiguration(app, repo)

        for (value in listOf(false, true, false)) {
            enabled.value = value
            assertEquals(value, config.getServiceEnabledFlow(ServiceType.COMPANION_APP).first())
            assertEquals(true, config.getServiceEnabledFlow(ServiceType.BLE_BEACON).first())
        }
    }

    private fun desire(vararg types: ServiceType) {
        val desired = EnumMap<ServiceType, Boolean?>(ServiceType::class.java)
        ServiceType.entries.forEach { desired[it] = it in types }
        @Suppress("UNCHECKED_CAST")
        val map = get("desiredServicesStateMap") as EnumMap<ServiceType, Boolean?>
        map.clear()
        map.putAll(desired)
    }

    private fun reconcile(): List<String> {
        ReactiveServiceManager::class.java
            .getDeclaredMethod("reconcileStates", ReactiveServiceManager.RecType::class.java)
            .apply { isAccessible = true }
            .invoke(manager, ReactiveServiceManager.RecType.INIT)
        val shadow = shadowOf(app)
        return generateSequence { shadow.nextStartedService }.map { it.component!!.className }.toList()
    }

    private fun get(field: String): Any? =
        ReactiveServiceManager::class.java.getDeclaredField(field).run {
            isAccessible = true
            get(manager)
        }

    private fun set(field: String, value: Any?) {
        ReactiveServiceManager::class.java.getDeclaredField(field).apply {
            isAccessible = true
            set(manager, value)
        }
    }
}
