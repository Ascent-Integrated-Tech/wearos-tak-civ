package com.aitech.weartak_civ.service

import android.app.Application
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.integration.plugin.PluginSettingsCoordinator
import com.aitech.weartak_civ.cot.CotCompanionPayload
import com.aitech.weartak_civ.cot.CotEndpointDispatcher
import com.aitech.weartak_civ.cot.CotEventTime
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
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
    fun pluginOnlyAvailabilityDrainsQueuedEmergencyWithoutEnablingNetworkRoutes() = runTest {
        val fixture = Fixture(this)
        fixture.snapshotLocation = fixture.location(System.currentTimeMillis())
        fixture.startRecoveryCollector()
        runCurrent()
        val alert = fixture.alert("plugin-only")
        fixture.activeAlerts(false)[alert.uuid] = alert
        fixture.send(false, alert)
        assertTrue(fixture.sent.isEmpty())
        fixture.pluginAvailable.value = true
        runCurrent()
        delay(10)
        runCurrent()
        assertEquals(1, fixture.sent.size)
        assertTrue(fixture.endpoints.value.isEmpty())
        assertFalse(fixture.multicast.value)
        assertFalse(fixture.companionEnabled.value)
        fixture.stopRecoveryCollector()
    }

    @Test
    fun usableRouteSendsManualAndAutomaticAlertsAndCancellationsWithAnOldValidFix() = runTest {
        for (automatic in listOf(false, true)) {
            val fixture = Fixture(this)
            fixture.multicast.value = true
            for (location in listOf(fixture.location(System.currentTimeMillis() - 60_000), fixture.location(System.currentTimeMillis()))) {
                fixture.snapshotLocation = location
                val alert = fixture.alert("alert-${fixture.sent.size}")
                fixture.activeAlerts(automatic)[alert.uuid] = alert
                fixture.send(automatic, alert)
                fixture.clear(automatic, alert.uuid)
            }
            assertEquals(4, fixture.sent.size)
            fixture.sent.forEach {
                assertFalse(it.contains("ce='9999999.0'"))
                assertTrue(it.contains("lat='41.0'"))
                assertTrue(it.contains("<weartak_location"))
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
            assertEquals(1, fixture.queue(automatic).size)

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

            assertEquals(1, fixture.sent.size)
            assertTrue(fixture.sent[0].contains("type='b-a-o-can'"))
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
        assertEquals(1, fixture.payloads.size)
        assertTrue(fixture.chats.single().snapshot.message.contains("location unavailable, acquiring GPS"))
    }

    @Test fun noFixSendsChatImmediatelyThenFirstRealFixSendsPositionalAlertAndUpdates() = runTest {
        val fixture = Fixture(this)
        fixture.multicast.value = true
        val alert = fixture.alert("no-fix")
        fixture.activeAlerts(false)[alert.uuid] = alert
        fixture.send(false, alert)
        assertEquals(1, fixture.sent.size)
        assertTrue(fixture.sent.single().contains("type='b-t-f'"))
        assertTrue(fixture.sent.single().contains("location unavailable, acquiring GPS"))
        repeat(18) { fixture.drain(false) }
        assertEquals(1, fixture.sent.size)
        assertEquals(1, fixture.queue(false).size)
        fixture.snapshotLocation = fixture.location(System.currentTimeMillis())
        fixture.drain(false)
        assertEquals(2, fixture.sent.size)
        assertTrue(fixture.sent.last().contains("lat='41.0'"))
        assertFalse(fixture.sent.last().contains("type='b-t-f'"))
        repeat(18) { fixture.drain(false) }
        assertEquals(2, fixture.sent.size)
        fixture.snapshotLocation = fixture.snapshotLocation!!.copy(timeMillis = System.currentTimeMillis() + 1, lat = 42.0)
        fixture.drain(false)
        assertEquals(3, fixture.sent.size)
        assertTrue(fixture.sent.last().contains("lat='42.0'"))
        fixture.clear(false, alert.uuid)
        fixture.snapshotLocation = fixture.snapshotLocation!!.copy(lat = 43.0)
        fixture.drain(false)
        assertEquals(4, fixture.sent.size)
        assertTrue(fixture.sent.last().contains("type='b-a-o-can'"))
    }

    @Test fun clearingBeforeFirstFixNeverReplaysTheCancelledPositionalAlert() = runTest {
        val fixture = Fixture(this)
        fixture.multicast.value = true
        val alert = fixture.alert("cancel-before-fix")
        fixture.activeAlerts(false)[alert.uuid] = alert
        fixture.send(false, alert)
        fixture.clear(false, alert.uuid)
        fixture.snapshotLocation = fixture.location(System.currentTimeMillis())
        fixture.drain(false)
        assertEquals(2, fixture.sent.size)
        assertTrue(fixture.sent.all { it.contains("type='b-t-f'") })
        assertTrue(fixture.queue(false).isEmpty())
    }

    @Test fun queuedNoFixAlertDoesNotBlockAnotherImmediateEmergencyNotification() = runTest {
        val fixture = Fixture(this)
        fixture.multicast.value = true
        fixture.send(false, fixture.alert("first"))
        fixture.send(false, fixture.alert("second"))
        assertEquals(2, fixture.sent.size)
        assertTrue(fixture.sent.all { it.contains("type='b-t-f'") })
        assertEquals(2, fixture.queue(false).size)
        fixture.drain(false)
        assertEquals(2, fixture.sent.size)
    }

    private class Fixture(scope: TestScope) {
        val endpoints = MutableStateFlow<Set<DataRepository.Endpoint>>(emptySet())
        val multicast = MutableStateFlow(false)
        val companionReady = MutableStateFlow(false)
        val companionEnabled = MutableStateFlow(false)
        val pluginAvailable = MutableStateFlow(false)
        val sent = mutableListOf<String>()
        val payloads = mutableListOf<CotCompanionPayload.Emergency>()
        val chats = mutableListOf<CotCompanionPayload.Chat>()
        var snapshotLocation: DataRepository.LocationSnapshot? = null
        private val repo = mockk<DataRepository>(relaxed = true)
        private val companion = mockk<CompanionAppRepository>(relaxed = true)
        private val service: CotDispatchService
        private val collectorScope = CoroutineScope(
            scope.backgroundScope.coroutineContext + Job(scope.backgroundScope.coroutineContext[Job]),
        )

        init {
            every { companion.gateway } returns com.aitech.weartak_civ.cot.CompanionGatewaySession()
            MainActivity.mainActivity = mockk {
                every { applicationContext } returns RuntimeEnvironment.getApplication()
            }
            MainActivity.enableSmsForAlerts = false
            TAKConnectService.isAnyTakConnected = false
            DittoService.isRunning.set(false)
            every { repo.endpointConnected } returns endpoints
            every { repo.anyEndpointConnected } returns MutableStateFlow(false)
            every { repo.latestLocation } returns MutableStateFlow<android.location.Location?>(null)
            every { repo.multicastPublisherRunning } returns multicast
            every { repo.sitxStatus } returns MutableStateFlow(DataRepository.SitxServerStatus.DISCONNECTED)
            every { repo.getEnableSitxService } returns MutableStateFlow(false)
            every { repo.getEnableCompanionAppService } returns companionEnabled
            every { companion.companionAppReady } returns companionReady
            every { repo.isCompanionConnected } returns companionReady
            coEvery { repo.snapshotEmergencyEvent(any()) } coAnswers {
                delay(1)
                val alert = firstArg<DataRepository.CustomEmergencyAlert>()
                DataRepository.EmergencyEventSnapshot(
                    alert.uuid, alert.alertState, alert.category, alert.description, alert.priority, false,
                    CotEventTime(0, 0, 0, "2026-10-08T00:00:00Z", "2026-10-08T00:00:00Z", "2026-10-08T00:15:00Z"),
                    snapshotLocation, null, mockk(relaxed = true),
                )
            }
            val coordinator = mockk<PluginSettingsCoordinator>(relaxed = true)
            every { coordinator.outboundCotAvailable } returns pluginAvailable
            every { coordinator.anyPluginHasSettingEnabled(any()) } answers { pluginAvailable.value }
            val app = mockk<WearTakApplication>(relaxed = true)
            every { app.pluginManager.settingsCoordinator } returns coordinator
            service = spyk(CotDispatchService())
            every { service.applicationContext } returns app
            field("dataRepository").set(service, repo)
            field("serviceScope").set(service, collectorScope)
            field("capRepo\$delegate").set(service, lazyOf(companion))
            val dispatcher = CotEndpointDispatcher(
                sendTak = { _, _ -> error("Unexpected TAK route") },
                sendSitx = { error("Unexpected SitX route") },
                sendDitto = { _, _ -> error("Unexpected Ditto route") },
                sendMulticast = { sent += it },
                sendPliMulticast = { error("Unexpected PLI route") },
                sendCompanion = {
                    when (it) {
                        is CotCompanionPayload.Emergency -> payloads += it
                        is CotCompanionPayload.Chat -> chats += it
                        else -> error("Unexpected payload")
                    }
                },
                sendPlugin = { sent += it },
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
