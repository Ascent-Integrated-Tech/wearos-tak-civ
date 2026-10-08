package com.aitech.weartak_civ.service

import android.app.Application
import com.aitech.weartak_civ.cot.CotCompanionPayload
import com.aitech.weartak_civ.cot.CotEndpointDispatcher
import com.aitech.weartak_civ.cot.CotEventTime
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CotDispatchServiceAlertReadinessTest {
    @Test
    fun usableRouteSendsManualAndAutomaticAlertsAndCancellationsWithoutFreshGps() = runTest {
        for (automatic in listOf(false, true)) {
            val fixture = Fixture(this)
            fixture.multicast.value = true
            for (location in listOf(null, fixture.location(System.currentTimeMillis() - 60_000))) {
                fixture.snapshotLocation = location
                val alert = fixture.alert("alert-${fixture.sent.size}")
                fixture.activeAlerts(automatic)[alert.uuid] = alert
                fixture.send(automatic, alert)
                fixture.clear(automatic, alert.uuid)
            }
            assertEquals(4, fixture.sent.size)
            fixture.sent.forEach {
                assertTrue(it.contains("ce='9999999.0'"))
                assertFalse(it.contains("lat='41.0'"))
            }
            assertEquals(2, fixture.sent.count { it.contains("type='b-a-o-can'") })
        }
    }

    @Test
    fun routeRecoveryDrainsQueuedAlertsAndCancellationsWithoutGpsAndWithoutDuplicateDrains() = runTest {
        for (automatic in listOf(false, true)) {
            val fixture = Fixture(this)
            fixture.snapshotLocation = fixture.location(System.currentTimeMillis() - 60_000)
            fixture.startRecoveryCollector()
            runCurrent()
            val alert = fixture.alert("queued")
            fixture.activeAlerts(automatic)[alert.uuid] = alert
            fixture.send(automatic, alert)
            fixture.clear(automatic, alert.uuid)
            assertEquals(0, fixture.sent.size)
            assertEquals(2, fixture.queue(automatic).size)

            // The endpoint set already contains another endpoint; recovery is not a
            // false-to-true transition of anyEndpointConnected.
            fixture.endpoints.value = setOf(DataRepository.Endpoint.TAK_SERVER)
            runCurrent()
            fixture.multicast.value = true
            launch { fixture.drain(automatic) }
            launch { fixture.drain(automatic) }
            runCurrent()
            delay(10)
            runCurrent()

            assertEquals(2, fixture.sent.size)
            assertFalse(fixture.sent[0].contains("type='b-a-o-can'"))
            assertTrue(fixture.sent[1].contains("type='b-a-o-can'"))
            assertTrue(fixture.queue(automatic).isEmpty())
            assertTrue(fixture.activeAlerts(automatic).isEmpty())
            fixture.stopRecoveryCollector()
        }
    }

    @Test
    fun freshLocationIsRetainedButInvalidLocationIsOmittedFromEveryPayload() = runTest {
        val fixture = Fixture(this)
        fixture.companionReady.value = true
        fixture.companionEnabled.value = true
        fixture.snapshotLocation = fixture.location(System.currentTimeMillis())
        fixture.send(false, fixture.alert("fresh"))
        assertEquals(41.0, fixture.payloads.single().snapshot.location?.lat)
        fixture.snapshotLocation = fixture.location(System.currentTimeMillis()).copy(lat = Double.NaN)
        fixture.send(false, fixture.alert("invalid"))
        assertEquals(null, fixture.payloads.last().snapshot.location)
    }

    private class Fixture(scope: TestScope) {
        val endpoints = MutableStateFlow<Set<DataRepository.Endpoint>>(emptySet())
        val multicast = MutableStateFlow(false)
        val companionReady = MutableStateFlow(false)
        val companionEnabled = MutableStateFlow(false)
        val sent = mutableListOf<String>()
        val payloads = mutableListOf<CotCompanionPayload.Emergency>()
        var snapshotLocation: DataRepository.LocationSnapshot? = null
        private val repo = mockk<DataRepository>(relaxed = true)
        private val companion = mockk<CompanionAppRepository>(relaxed = true)
        private val service: CotDispatchService
        private val collectorScope = CoroutineScope(
            scope.backgroundScope.coroutineContext + Job(scope.backgroundScope.coroutineContext[Job]),
        )

        init {
            MainActivity.mainActivity = mockk {
                every { applicationContext } returns RuntimeEnvironment.getApplication()
            }
            MainActivity.enableSmsForAlerts = false
            TAKConnectService.isAnyTakConnected = false
            DittoService.isRunning.set(false)
            every { repo.endpointConnected } returns endpoints
            every { repo.anyEndpointConnected } returns MutableStateFlow(false)
            every { repo.multicastPublisherRunning } returns multicast
            every { repo.sitxStatus } returns MutableStateFlow(DataRepository.SitxServerStatus.DISCONNECTED)
            every { repo.getEnableSitxService } returns MutableStateFlow(false)
            every { repo.getEnableCompanionAppService } returns companionEnabled
            every { companion.companionAppReady } returns companionReady
            coEvery { repo.snapshotEmergencyEvent(any()) } coAnswers {
                delay(1)
                val alert = firstArg<DataRepository.CustomEmergencyAlert>()
                DataRepository.EmergencyEventSnapshot(
                    alert.uuid, alert.alertState, alert.category, alert.description, alert.priority, false,
                    CotEventTime(0, 0, 0, "2026-10-08T00:00:00Z", "2026-10-08T00:00:00Z", "2026-10-08T00:15:00Z"),
                    snapshotLocation, null, mockk(relaxed = true),
                )
            }
            service = CotDispatchService()
            field("dataRepository").set(service, repo)
            field("serviceScope").set(service, collectorScope)
            field("capRepo\$delegate").set(service, lazyOf(companion))
            val dispatcher = CotEndpointDispatcher(
                sendTak = { _, _ -> error("Unexpected TAK route") },
                sendSitx = { error("Unexpected SitX route") },
                sendDitto = { _, _ -> error("Unexpected Ditto route") },
                sendMulticast = { sent += it },
                sendPliMulticast = { error("Unexpected PLI route") },
                sendCompanion = { payloads += it as CotCompanionPayload.Emergency },
            )
            field("endpointDispatcher\$delegate").set(service, lazyOf(dispatcher))
        }

        fun startRecoveryCollector() {
            CotDispatchService::class.java.getDeclaredMethod("startAnyEndpointConnectedCollector")
                .apply { isAccessible = true }.invoke(service)
        }

        fun stopRecoveryCollector() { collectorScope.coroutineContext[Job]?.cancel() }
        fun alert(uid: String) = DataRepository.CustomEmergencyAlert(uid, DataRepository.AlertState.ALERT, "Alert", "Help", 1, false, 0, false)
        fun location(time: Long) = DataRepository.LocationSnapshot(time, 41.0, -87.0, 100.0, 4f, 5f, null, null)
        suspend fun send(automatic: Boolean, alert: DataRepository.CustomEmergencyAlert) {
            call(sendMethod(automatic), CotDispatchService.CustomEmergencyAlertWithAddedState(alert = alert))
        }
        suspend fun drain(automatic: Boolean) { call(sendMethod(automatic), null) }
        suspend fun clear(automatic: Boolean, uid: String) {
            call(if (automatic) "handleClearPhysioAndAutoGenAlertUid" else "handleClearManualAlertUid", uid)
        }
        @Suppress("UNCHECKED_CAST")
        fun activeAlerts(automatic: Boolean) = field(if (automatic) "physioAndAutoGenAlertMap" else "manualAlertMap")
            .get(service) as MutableMap<String, DataRepository.CustomEmergencyAlert>
        @Suppress("UNCHECKED_CAST")
        fun queue(automatic: Boolean) = field(if (automatic) "pendingPhysioAndAutoGenAlertQueue" else "pendingManualAlertQueue")
            .get(service) as ArrayDeque<CotDispatchService.CustomEmergencyAlertWithAddedState>
        private fun sendMethod(automatic: Boolean) = if (automatic) "sendAllPhysioAndAutoGenAlerts" else "sendAllManualAlerts"
        private fun field(name: String) = CotDispatchService::class.java.getDeclaredField(name).apply { isAccessible = true }
        private suspend fun call(name: String, argument: Any?): Any? =
            suspendCoroutineUninterceptedOrReturn { continuation ->
                CotDispatchService::class.java.declaredMethods.single { it.name == name }
                    .apply { isAccessible = true }.invoke(service, argument, continuation)
            }
    }
}
