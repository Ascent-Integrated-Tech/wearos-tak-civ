package com.aitech.weartak_civ.service

import android.app.Application
import android.Manifest
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.LocationInput
import com.aitech.weartak_civ.data.LocationInputStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import com.aitech.weartak_civ.service.ActivityRecognitionService.Companion.ActivityRecognized
import com.aitech.weartak_civ.events.PowerManagerEvent
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReportingIntervalStrategyServiceTest {
    class PolicyObserver {
        var updates = 0
        @Subscribe fun receive(event: PowerManagerEvent) { updates++ }
    }

    private fun await(condition: () -> Boolean) = runBlocking {
        withTimeout(3_000) {
            while (!condition()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(5)
            }
        }
    }

    @Test fun realSpeedWorksWithoutPhysiologyAndExpiresWithoutRewritingRatesOrAcquisition() {
        val repo = DataRepository(RuntimeEnvironment.getApplication())
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACTIVITY_RECOGNITION)
        runBlocking {
            repo.storeEnableLocationService(true)
            repo.storeLocationInput(LocationInput.FUSED)
            repo.storeEnablePhysiologicalServices(false)
            repo.storeEnableDynamicReportingStrategy(true)
            repo.storeEnableActivityRecognitionService(true)
            repo.storeConstantReportingInterval(43)
            repo.storeOnFootReportingInterval(30)
            repo.storeVehicleReportingInterval(60)
            repo.storeStationaryReportingInterval(3600)
            repo.storeWhileAlertingReportingInterval(7)
        }
        val service = Robolectric.buildService(ReportingIntervalStrategyService::class.java).get()
        fun inject(name: String, value: Any) {
            ReportingIntervalStrategyService::class.java.getDeclaredField(name).apply {
                isAccessible = true; set(service, value)
            }
        }
        fun call(name: String) {
            ReportingIntervalStrategyService::class.java.getDeclaredMethod(name).apply {
                isAccessible = true; invoke(service)
            }
        }
        inject("dataRepository", repo)
        val previousReport = CotDispatchService.desiredReportingIntervalSecs
        val previousObservation = CotDispatchService.desiredLocationObservationIntervalSecs
        LocationService.speedObservation.value = null
        val policyObserver = PolicyObserver()
        EventBus.getDefault().register(policyObserver)
        try {
            call("initializeVariables")
            call("startSpeedCollector")
            call("startActivityCollector")
            call("startEnableDynamicStrategyCollector")
            call("startIsAlertingCollector")
            assertEquals(60, CotDispatchService.desiredReportingIntervalSecs)
            fun observe(speed: Float?, future: Boolean = false, lifetimeMillis: Long = 100_000) {
                val fix = Location("fused").apply {
                    latitude = 41.0; longitude = -87.0; altitude = 100.0; accuracy = 5f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() +
                        if (future) 1_000_000_000L else 0L
                    speed?.let { this.speed = it }
                }
                runBlocking { repo.storeLatestLocation(fix) }
                repo.locationInputStatus.value = LocationInputStatus.READY
                LocationService.speedObservation.value = LocationService.Companion.SpeedObservation(
                    fix, SystemClock.elapsedRealtimeNanos() + lifetimeMillis * 1_000_000L,
                )
            }
            observe(0f)
            await { CotDispatchService.desiredReportingIntervalSecs == 3600 }
            assertEquals(60, CotDispatchService.desiredLocationObservationIntervalSecs)
            observe(100f)
            await { CotDispatchService.desiredReportingIntervalSecs == 60 }
            observe(null)
            await { CotDispatchService.desiredReportingIntervalSecs == 60 }
            observe(0f, future = true)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(60, CotDispatchService.desiredReportingIntervalSecs)
            observe(0f, lifetimeMillis = 2_000)
            await { CotDispatchService.desiredReportingIntervalSecs == 3600 }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            await { CotDispatchService.desiredReportingIntervalSecs == 60 }
            assertEquals(60, CotDispatchService.desiredLocationObservationIntervalSecs)
            observe(0f)
            await { CotDispatchService.desiredReportingIntervalSecs == 3600 }
            runBlocking { repo.storeLocationInput(LocationInput.INTERNAL_GNSS) }
            await { CotDispatchService.desiredReportingIntervalSecs == 60 }
            runBlocking { repo.storeLatestActivityRecognized(ActivityRecognized.STILL) }
            await { CotDispatchService.desiredReportingIntervalSecs == 3600 &&
                CotDispatchService.desiredLocationObservationIntervalSecs == 3600 }
            runBlocking { repo.storeLocationInput(LocationInput.FUSED) }
            observe(100f)
            await { CotDispatchService.desiredReportingIntervalSecs == 60 }
            assertEquals(3600, CotDispatchService.desiredLocationObservationIntervalSecs)
            val beforeActivity = policyObserver.updates
            runBlocking { repo.storeLatestActivityRecognized(ActivityRecognized.WALKING) }
            await { CotDispatchService.desiredLocationObservationIntervalSecs == 30 }
            assertEquals(60, CotDispatchService.desiredReportingIntervalSecs)
            assertTrue(policyObserver.updates > beforeActivity)
            runBlocking { repo.storeLatestActivityRecognized(ActivityRecognized.STILL) }
            await { CotDispatchService.desiredLocationObservationIntervalSecs == 3600 }
            observe(null)
            await { CotDispatchService.desiredReportingIntervalSecs == 3600 }
            runBlocking { repo.storeLatestActivityRecognized(ActivityRecognized.WALKING) }
            await { CotDispatchService.desiredReportingIntervalSecs == 30 &&
                CotDispatchService.desiredLocationObservationIntervalSecs == 30 }
            runBlocking { repo.storeLatestActivityRecognized(ActivityRecognized.UNKNOWN) }
            await { CotDispatchService.desiredReportingIntervalSecs == 60 &&
                CotDispatchService.desiredLocationObservationIntervalSecs == 60 }
            runBlocking { repo.appendManualAlert(DataRepository.CustomEmergencyAlert(
                "test-alert", DataRepository.AlertState.ALERT, "test", "test",
                1, false, System.currentTimeMillis(), false,
            )) }
            await { CotDispatchService.desiredReportingIntervalSecs == 7 &&
                CotDispatchService.desiredLocationObservationIntervalSecs == 7 }
            runBlocking { repo.storeEnableDynamicReportingStrategy(false) }
            await { CotDispatchService.desiredReportingIntervalSecs == 43 &&
                CotDispatchService.desiredLocationObservationIntervalSecs == 43 }
            assertEquals(30, runBlocking { repo.getOnFootReportingInterval.first() })
            assertEquals(60, runBlocking { repo.getVehicleReportingInterval.first() })
            assertEquals(3600, runBlocking { repo.getStationaryReportingInterval.first() })
            assertFalse(runBlocking { repo.getEnablePhysiologicalServices.first() })
        } finally {
            EventBus.getDefault().unregister(policyObserver)
            (ReportingIntervalStrategyService::class.java.getDeclaredField("serviceScope").apply {
                isAccessible = true
            }.get(service) as CoroutineScope).cancel()
            LocationService.speedObservation.value = null
            CotDispatchService.desiredReportingIntervalSecs = previousReport
            CotDispatchService.desiredLocationObservationIntervalSecs = previousObservation
        }
    }
}
