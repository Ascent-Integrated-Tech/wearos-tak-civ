package com.aitech.weartak_civ.service

import android.app.Application
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BatteryPowerObserverTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var repo: DataRepository
    private lateinit var observer: BatteryPowerObserver

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        MainActivity.mainActivity = mockk(relaxed = true) { every { applicationContext } returns app }
        repo = mockk(relaxed = true)
        observer = BatteryPowerObserver(app, repo)
    }

    @After fun cleanup() {
        observer.stop()
        Dispatchers.resetMain()
    }

    private fun battery(level: Int, plug: Int = 0, status: Int = BatteryManager.BATTERY_STATUS_DISCHARGING,
                        scale: Int = 100) = Intent(Intent.ACTION_BATTERY_CHANGED)
        .putExtra(BatteryManager.EXTRA_LEVEL, level).putExtra(BatteryManager.EXTRA_SCALE, scale)
        .putExtra(BatteryManager.EXTRA_PLUGGED, plug).putExtra(BatteryManager.EXTRA_STATUS, status)

    private fun sticky(intent: Intent) {
        app.sendStickyBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun initialStickySampleIsAvailableWithoutBatteryAlertsOrAnyServiceStart() {
        sticky(battery(63, BatteryManager.BATTERY_PLUGGED_WIRELESS, BatteryManager.BATTERY_STATUS_FULL))
        observer.start()
        shadowOf(Looper.getMainLooper()).idle()
        coVerify(exactly = 1) { repo.storeLatestBatteryPercentage(63) }
        coVerify(exactly = 1) { repo.updateDockType(BatteryPowerObserver.DockType.WIRELESS) }
        coVerify(exactly = 1) { repo.updateChargingState(true) }
        val registry = ServiceRegistry()
        assertNull(registry.getServiceClass(ReactiveServiceManager.ServiceType.BATTERY))
        assertTrue(registry.isServiceRunning(ReactiveServiceManager.ServiceType.BATTERY))
        assertTrue(ReactiveServiceManager.ServiceType.BATTERY.isNotRunnable)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun repeatedStartAndDuplicateSamplesDoNotDuplicateUpdates() {
        sticky(battery(44))
        observer.start()
        observer.start()
        sticky(battery(44))
        coVerify(exactly = 1) { repo.storeLatestBatteryPercentage(44) }
        coVerify(exactly = 1) { repo.updateDockType(BatteryPowerObserver.DockType.NONE) }
        coVerify(exactly = 1) { repo.updateChargingState(false) }
    }

    @Test fun chargingAndDockChangesArePreservedAtSamePercentage() {
        sticky(battery(44))
        observer.start()
        sticky(battery(44, BatteryManager.BATTERY_PLUGGED_USB, BatteryManager.BATTERY_STATUS_CHARGING))
        coVerify(exactly = 1) { repo.storeLatestBatteryPercentage(44) }
        coVerify(exactly = 1) { repo.updateDockType(BatteryPowerObserver.DockType.USB) }
        coVerify(exactly = 1) { repo.updateChargingState(true) }
        app.sendBroadcast(Intent(Intent.ACTION_POWER_CONNECTED))
        shadowOf(Looper.getMainLooper()).idle()
        coVerify(exactly = 1) { repo.updateChargingState(true) }
    }

    @Test fun stopUnregistersAndRestartRefreshesInitialStickyState() {
        sticky(battery(44))
        observer.start()
        observer.stop()
        observer.stop()
        assertFalse(BatteryPowerObserver.isRunning.get())
        sticky(battery(45))
        coVerify(exactly = 0) { repo.storeLatestBatteryPercentage(45) }
        observer.start()
        coVerify(exactly = 1) { repo.storeLatestBatteryPercentage(45) }
    }

    @Test fun invalidStickySampleDoesNotInventZeroPercentOrChargingState() {
        sticky(battery(-1, scale = 0))
        observer.start()
        coVerify(exactly = 0) { repo.storeLatestBatteryPercentage(any()) }
        coVerify(exactly = 0) { repo.updateDockType(any()) }
        coVerify(exactly = 0) { repo.updateChargingState(any()) }
    }
}
