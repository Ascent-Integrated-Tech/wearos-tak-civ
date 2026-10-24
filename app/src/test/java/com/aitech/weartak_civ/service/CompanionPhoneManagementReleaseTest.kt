package com.aitech.weartak_civ.service

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import androidx.datastore.preferences.core.edit
import com.aitech.weartak_civ.cot.CompanionGatewaySession
import com.aitech.weartak_civ.data.CompanionAppRepository
import com.aitech.weartak_civ.data.BloodhoundRepository
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.aitech.weartak_civ.data.InMemoryTakServerSecrets
import com.aitech.weartak_civ.data.InMemoryTakServerSecrets.Companion.server
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CompanionPhoneManagementReleaseTest {
    private val service = CompanionAppService()
    private val scheduler = TestCoroutineScheduler()
    private val job = SupervisorJob()
    private val coroutineErrors = mutableListOf<Throwable>()
    private lateinit var repo: DataRepository
    private lateinit var capRepo: CompanionAppRepository
    private lateinit var peer: BluetoothDevice
    private lateinit var gattServer: BluetoothGattServer
    private lateinit var txCharacteristic: BluetoothGattCharacteristic
    private val adapter = mockk<BluetoothAdapter>(relaxed = true)
    private var bonded = true
    private var ready = true
    private val secrets = InMemoryTakServerSecrets()

    @Before fun setup() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        MainActivity.device_uid = "test-watch"
        MainActivity.versionNumber = "test"
        MainActivity.mainActivity = mockk { every { applicationContext } returns app }
        repo = DataRepository(app)
        repo.takServerSecretsOverride = secrets.repository
        repo.legacyDataStore().edit { it.clear() }
        peer = mockk()
        every { peer.address } returns "AA:BB"
        every { peer.bondState } answers {
            if (bonded) BluetoothDevice.BOND_BONDED else BluetoothDevice.BOND_NONE
        }
        capRepo = mockk(relaxed = true)
        every { capRepo.gateway } returns CompanionGatewaySession()
        every { capRepo.companionAppReady } answers { MutableStateFlow(ready) }
        every { capRepo.envelopeToJson(any<CompanionAppRepository.Envelope<Any>>()) } returns
            """{"msgType":"settings_request"}"""
        inject("repo\$delegate", lazyOf(repo))
        every { adapter.name } returns "Galaxy Watch"
        inject("bluetoothAdapter", adapter)
        inject("capRepo\$delegate", lazyOf(capRepo))
        val scope = CoroutineScope(job + StandardTestDispatcher(scheduler) +
            CoroutineExceptionHandler { _, error -> coroutineErrors.add(error) })
        inject("serviceScope", scope)
        inject("notifyScope", scope)
        inject("connectedDevice", peer)
        inject("notificationsEnabled", true)
        gattServer = mockk(relaxed = true)
        txCharacteristic = mockk(relaxed = true)
        inject("gattServer", gattServer)
        inject("txCharacteristic", txCharacteristic)
    }

    @After fun cleanup() {
        job.cancel()
        scheduler.runCurrent()
        coroutineErrors.firstOrNull()?.let { throw AssertionError("Service coroutine failed", it) }
        verify(exactly = 0) { adapter.setName(any()) }
    }

    private suspend fun prepareIdentity() {
        MainActivity.teamEntriesValuesList = MainActivity.Constants.TeamsJson(listOf("Cyan", "Red"), listOf("Cyan", "Red"))
        MainActivity.categoryRolesEntryLists = listOf(
            com.aitech.weartak_civ.Utils.SettingsUtils.CategoryRolesEntry("MIL", listOf("Team Lead", "Medic"))
        )
        repo.storeCallsign("ODIN-WEARTAK")
        repo.enablePhoneManagedSettings("AA:BB")
        repo.applyPhoneManagedSettings("PHONE", "Cyan", "Team Lead", 15)
        val real = CompanionAppRepository(RuntimeEnvironment.getApplication())
        every { capRepo.buildSettingsAck(any(), any()) } answers {
            real.buildSettingsAck(firstArg(), secondArg())
        }
        every { capRepo.envelopeToJson(any<CompanionAppRepository.Envelope<Any>>()) } answers {
            com.google.gson.Gson().toJson(firstArg<CompanionAppRepository.Envelope<Any>>()).also {
                identityAcks.add(JSONObject(it))
            }
        }
    }

    private val identityAcks = java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()

    private fun awaitIdentityAck(): JSONObject {
        repeat(200) {
            scheduler.runCurrent()
            identityAcks.poll()?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("Identity acknowledgement was not built")
    }

    @Test fun sparseIdentityMessageAcknowledgesAndRefreshesRuntimeWithoutChangingReporting() = runBlocking {
        prepareIdentity()
        handle("set_callsign", JSONObject().put("callsign", "LIVE"))
        val ack = awaitIdentityAck()
        assertEquals("settings_set_ack", ack.getString("msgType"))
        assertTrue(ack.getJSONObject("payload").getBoolean("ok"))
        assertEquals("LIVE", repo.getCallsign.first())
        assertEquals("LIVE", MainActivity.Constants.callsign)
        assertEquals("Cyan", repo.getTeam.first())
        assertEquals("Team Lead", repo.getRole.first())
        assertEquals(15, repo.getConstantReportingInterval.first())
        assertEquals("ODIN-WEARTAK", repo.getStandaloneCallsign.first())
        verify { gattServer.notifyCharacteristicChanged(peer, txCharacteristic, false) }

        handle("set_team", JSONObject().put("team", "Red"))
        assertTrue(awaitIdentityAck().getJSONObject("payload").getBoolean("ok"))
        assertEquals("Team Lead", repo.getRole.first())
        handle("set_role", JSONObject().put("role", "Medic"))
        assertTrue(awaitIdentityAck().getJSONObject("payload").getBoolean("ok"))
        assertEquals("LIVE", MainActivity.Constants.callsign)
        assertEquals("Red", MainActivity.Constants.team)
        assertEquals("Medic", MainActivity.Constants.role)
        assertEquals("MIL", MainActivity.Constants.category)
    }

    @Test fun identityRejectsWrongOwnerUnbondedUnreadyReleasedAndMalformedRequests() = runBlocking {
        prepareIdentity()
        for (payload in listOf(JSONObject(), JSONObject().put("callsign", JSONObject.NULL),
            JSONObject().put("team", 123), JSONObject().put("role", " "),
            JSONObject().put("callsign", "BAD").put("reportIntSecs", 10))) {
            handle("set_callsign", payload)
            val ack = awaitIdentityAck().getJSONObject("payload")
            assertFalse(ack.getBoolean("ok"))
            assertTrue(ack.has("error"))
            assertEquals("PHONE", repo.getCallsign.first())
        }
        for ((type, payload) in listOf(
            "set_callsign" to JSONObject().put("callsign", "A".repeat(33)),
            "set_callsign" to JSONObject().put("callsign", "BAD!"),
            "set_team" to JSONObject().put("team", "not a team"),
            "set_role" to JSONObject().put("role", "not a role"),
            "set_role" to JSONObject().put("role", JSONObject.NULL)
        )) {
            handle(type, payload)
            assertFalse(awaitIdentityAck().getJSONObject("payload").getBoolean("ok"))
        }
        repo.enablePhoneManagedSettings("CC:DD")
        handle("set_callsign", JSONObject().put("callsign", "BAD"))
        assertFalse(awaitIdentityAck().getJSONObject("payload").getBoolean("ok"))
        repo.enablePhoneManagedSettings("AA:BB")
        bonded = false
        handle("set_callsign", JSONObject().put("callsign", "BAD"))
        assertFalse(awaitIdentityAck().getJSONObject("payload").getBoolean("ok"))
        verify { capRepo.buildSettingsAck(false, any()) }
        bonded = true
        ready = false
        handle("set_callsign", JSONObject().put("callsign", "BAD"))
        assertFalse(awaitIdentityAck().getJSONObject("payload").getBoolean("ok"))
        ready = true
        handle(JSONObject().put("requestId", UUID.randomUUID().toString()))
        assertTrue(awaitAck().getJSONObject("payload").getBoolean("ok"))
        handle("set_callsign", JSONObject().put("callsign", "BAD"))
        assertFalse(awaitIdentityAck().getJSONObject("payload").getBoolean("ok"))
        assertEquals("ODIN-WEARTAK", repo.getCallsign.first())
    }

    @Test fun queuedIdentityCannotCrossSubscriptionGeneration() = runBlocking {
        prepareIdentity()
        val mutex = field("phoneManagementSettingsMutex") as kotlinx.coroutines.sync.Mutex
        mutex.lock()
        rawHandle("set_callsign", JSONObject().put("callsign", "STALE"))
        scheduler.runCurrent()
        service.beginCompanionSubscription()
        mutex.unlock()
        scheduler.runCurrent()
        assertEquals("PHONE", repo.getCallsign.first())
        verify { capRepo.buildSettingsAck(false, any()) }
    }

    @Test fun releaseAckRestoresWatchLockedValuesAndIsIdempotent() = runBlocking {
        repo.storeEnableDynamicReportingStrategy(true)
        repo.storeConstantReportingInterval(60)
        repo.storeCallsign("ODIN-WEARTAK")
        repo.storeTeam("Dark Green")
        repo.storeRole("K9")
        repo.enablePhoneManagedSettings("AA:BB")
        repo.applyPhoneManagedSettings("PHONE", "Cyan", "Team Lead", 15)
        repo.setPhoneManagementSessionActive(true)
        (field("phoneManagementLease") as PhoneManagementLease).start("AA:BB", generation().get(), 0)

        repeat(2) {
            val id = UUID.randomUUID().toString()
            handle(JSONObject().put("requestId", id))
            val response = awaitAck()
            assertEquals("phone_management_release_ack", response.getString("msgType"))
            assertEquals(id, response.getJSONObject("payload").getString("requestId"))
            assertTrue(response.getJSONObject("payload").getBoolean("ok"))
        }
        assertFalse(repo.phoneManagedSettingsEnabled.first())
        assertFalse(repo.phoneManagedSettingsLocked.first())
        assertFalse(service.expirePhoneManagement(PhoneManagementLease.TIMEOUT_MS))
        assertTrue(repo.getEnableDynamicReportingStrategy.first())
        assertEquals(60, repo.getConstantReportingInterval.first())
        assertEquals("ODIN-WEARTAK", repo.getCallsign.first())
        assertEquals("Dark Green", repo.getTeam.first())
        assertEquals("K9", repo.getRole.first())
        assertEquals("ODIN-WEARTAK", MainActivity.Constants.callsign)
        assertEquals("K9", MainActivity.Constants.role)
        assertTrue(MainActivity.Constants.enableDynamicReportingStrategy)
        assertEquals(60, MainActivity.Constants.constantReportingInterval)
    }

    @Test fun releaseRejectsWrongPeerInvalidUuidAndStaleMutationFence() = runBlocking {
        repo.enablePhoneManagedSettings("CC:DD")
        handle(JSONObject().put("requestId", UUID.randomUUID().toString()))
        assertFalse(awaitAck().getJSONObject("payload").getBoolean("ok"))

        repo.enablePhoneManagedSettings("AA:BB")
        handle(JSONObject().put("requestId", "1-1-1-1-1"))
        val invalidIdAck = awaitAck().getJSONObject("payload")
        assertFalse(invalidIdAck.getBoolean("ok"))
        assertTrue(invalidIdAck.has("error"))
        assertTrue(repo.phoneManagedSettingsEnabled.first())

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repo.clearPhoneManagedSettingsForPeer("AA:BB") { false } }
        }
        assertTrue(repo.phoneManagedSettingsEnabled.first())
    }

    @Test fun rejectedManagementActivationLeavesSettingsUnmanagedWithoutOwner() = runBlocking {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repo.enablePhoneManagedSettings("AA:BB") { false } }
        }

        assertFalse(repo.phoneManagedSettingsEnabled.first())
        assertNull(repo.phoneManagedCompanionAddress.first())
    }

    @Test fun heartbeatTimeoutRestoresWatchValuesKeepsManagementAndReconnectRelocks() = runBlocking {
        repo.storeCallsign("ODIN-WEARTAK")
        repo.storeTeam("Dark Green")
        repo.storeConstantReportingInterval(60)
        stubServerPersistence()
        service.applySettingsFromCompanion(managedSettings())
        assertTrue(repo.phoneManagedSettingsLocked.first())
        val now = android.os.SystemClock.elapsedRealtime()
        (field("phoneManagementLease") as PhoneManagementLease).start("AA:BB", generation().get(), now)
        assertFalse(service.expirePhoneManagement(now + PhoneManagementLease.TIMEOUT_MS - 1))
        assertTrue(service.expirePhoneManagement(now + PhoneManagementLease.TIMEOUT_MS))
        assertFalse(repo.phoneManagedSettingsLocked.first())
        assertTrue(repo.phoneManagedSettingsEnabled.first())
        assertEquals("AA:BB", repo.phoneManagedCompanionAddress.first())
        assertEquals("PHONE", repo.getCallsign.first())
        assertEquals(30, repo.getConstantReportingInterval.first())

        assertTrue(service.restorePhoneManagedSettingsIfUnleased(now + PhoneManagementLease.TIMEOUT_MS))
        assertEquals("ODIN-WEARTAK", repo.getCallsign.first())
        assertEquals("Dark Green", repo.getTeam.first())
        assertEquals(60, repo.getConstantReportingInterval.first())
        assertEquals("ODIN-WEARTAK", MainActivity.Constants.callsign)
        assertTrue(repo.phoneManagedSettingsEnabled.first())
        assertEquals("AA:BB", repo.phoneManagedCompanionAddress.first())
        assertFalse(service.restorePhoneManagedSettingsIfUnleased(now + PhoneManagementLease.TIMEOUT_MS))

        service.beginCompanionSubscription()
        assertFalse(repo.phoneManagedSettingsLocked.first())
        service.applySettingsFromCompanion(managedSettings())
        assertTrue(repo.phoneManagedSettingsLocked.first())
        assertEquals("PHONE", repo.getCallsign.first())

        repo.clearPhoneManagedSettingsForPeer("AA:BB")
        assertEquals("ODIN-WEARTAK", repo.getCallsign.first())
        assertEquals("Dark Green", repo.getTeam.first())
        assertEquals(60, repo.getConstantReportingInterval.first())
    }

    @Test fun bondModeRestoresWatchServersOnReleaseAck() = runBlocking {
        repo.storeTakServerList(listOf(server("own")))
        repo.storeCallsign("ODIN-WEARTAK")
        service.applySettingsFromCompanion(managedSettings().copy(takServerList = listOf(server("phone"))))
        assertEquals(listOf("phone"), secrets.liveServers().map { it.name })
        service.applySettingsFromCompanion(managedSettings().copy(takServerList = listOf(server("phone-2"))))
        assertEquals(listOf("own"), secrets.snapshotServers().map { it.name })

        handle(JSONObject().put("requestId", UUID.randomUUID().toString()))
        assertTrue(awaitAck().getJSONObject("payload").getBoolean("ok"))
        assertEquals(listOf("own"), secrets.liveServers().map { it.name })
        assertNull(secrets.snapshot.value)
        assertEquals("ODIN-WEARTAK", repo.getCallsign.first())
    }

    @Test fun configureModeAfterBondRestoresThenKeepsConfiguredSettings() = runBlocking {
        repo.storeTakServerList(listOf(server("own")))
        repo.storeCallsign("ODIN-WEARTAK")
        service.applySettingsFromCompanion(managedSettings().copy(takServerList = listOf(server("phone"))))
        assertTrue(repo.phoneManagedSettingsEnabled.first())

        service.applySettingsFromCompanion(
            managedSettings().copy(
                takServerList = listOf(server("configured")),
                callsign = "CONFIGURED",
                phoneManagedSettings = false
            )
        )
        assertEquals(listOf("configured"), secrets.liveServers().map { it.name })
        assertNull(secrets.snapshot.value)
        assertEquals("CONFIGURED", repo.getCallsign.first())
        assertFalse(repo.phoneManagedSettingsEnabled.first())
        assertNull(repo.clearPhoneManagedSettingsForPeer("AA:BB"))
        assertEquals(listOf("configured"), secrets.liveServers().map { it.name })
    }

    @Test fun configureModeWithoutBondKeepsPushedServers() = runBlocking {
        repo.storeTakServerList(listOf(server("own")))
        service.applySettingsFromCompanion(
            managedSettings().copy(takServerList = listOf(server("configured")), phoneManagedSettings = false)
        )
        assertEquals(listOf("configured"), secrets.liveServers().map { it.name })
        assertNull(secrets.snapshot.value)
        assertFalse(service.restorePhoneManagedSettingsIfUnleased(Long.MAX_VALUE))
        assertEquals(listOf("configured"), secrets.liveServers().map { it.name })
    }

    @Test fun briefDropInsideHeartbeatGraceKeepsPhoneValues() = runBlocking {
        repo.storeCallsign("ODIN-WEARTAK")
        stubServerPersistence()
        service.applySettingsFromCompanion(managedSettings())
        val now = android.os.SystemClock.elapsedRealtime()
        (field("phoneManagementLease") as PhoneManagementLease).start("AA:BB", generation().get(), now)
        service.beginCompanionSubscription()

        assertFalse(service.expirePhoneManagement(now + PhoneManagementLease.TIMEOUT_MS - 1))
        assertFalse(service.restorePhoneManagedSettingsIfUnleased(now + PhoneManagementLease.TIMEOUT_MS - 1))
        assertEquals("PHONE", repo.getCallsign.first())

        service.applySettingsFromCompanion(managedSettings())
        assertTrue(repo.phoneManagedSettingsLocked.first())
        assertEquals("PHONE", repo.getCallsign.first())
        repo.clearPhoneManagedSettingsForPeer("AA:BB")
        assertEquals("ODIN-WEARTAK", repo.getCallsign.first())
    }

    @Test fun restartedServiceWaitsForHandshakeGraceBeforeRestoring() = runBlocking {
        repo.storeCallsign("ODIN-WEARTAK")
        repo.enablePhoneManagedSettings("AA:BB")
        repo.applyPhoneManagedSettings("PHONE", "Cyan", "Team Member", 30)
        val now = android.os.SystemClock.elapsedRealtime()
        inject("phoneManagedRestoreNotBefore", now + PhoneManagementLease.TIMEOUT_MS)

        assertFalse(service.restorePhoneManagedSettingsIfUnleased(now))
        assertEquals("PHONE", repo.getCallsign.first())
        assertTrue(service.restorePhoneManagedSettingsIfUnleased(now + PhoneManagementLease.TIMEOUT_MS))
        assertEquals("ODIN-WEARTAK", repo.getCallsign.first())
        assertTrue(repo.phoneManagedSettingsEnabled.first())
    }

    @Test fun currentHeartbeatRenewsButOldSessionAndDuplicatePongsDoNot() = runBlocking {
        stubServerPersistence()
        service.applySettingsFromCompanion(managedSettings())
        val lease = field("phoneManagementLease") as PhoneManagementLease
        val now = android.os.SystemClock.elapsedRealtime()
        val token = requireNotNull(lease.probe("AA:BB", generation().get(), now))
        val pong = JSONObject().put("msg_type", "phone_management_pong")
            .put("version", 1).put("token", token)
        CompanionAppService::class.java.getDeclaredMethod("handleCompanionLine", String::class.java)
            .apply { isAccessible = true }.invoke(service, pong.toString())
        assertFalse(lease.acknowledge("AA:BB", generation().get(), token, now))
        service.beginCompanionSubscription()
        assertNull(lease.probe("AA:BB", generation().get(), now + 10_000))
        assertTrue(service.expirePhoneManagement(now + PhoneManagementLease.TIMEOUT_MS + 1_000))
        assertFalse(repo.phoneManagedSettingsLocked.first())
    }

    @Test fun newSubscriptionAllowsManagedSettingsAndFencesReleasedGeneration() = runBlocking {
        repo.enablePhoneManagedSettings("AA:BB")
        val releasedGeneration = generation().get()
        handle(JSONObject().put("requestId", UUID.randomUUID().toString()))
        assertTrue(awaitAck().getJSONObject("payload").getBoolean("ok"))

        assertThrows(IllegalStateException::class.java) {
            runBlocking { service.applySettingsFromCompanion(managedSettings(), releasedGeneration) }
        }

        service.beginCompanionSubscription()
        val currentGeneration = generation().get()
        assertNotEquals(releasedGeneration, currentGeneration)

        val serviceRepo = mockk<DataRepository>(relaxed = true)
        val mayEnable = slot<() -> Boolean>()
        coEvery { serviceRepo.storeProvisionedTakServerList(any()) } returns mockk(relaxed = true)
        coEvery { serviceRepo.awaitTakServerList() } returns emptyList()
        coEvery {
            serviceRepo.enablePhoneManagedSettings("AA:BB", capture(mayEnable))
        } returns Unit
        every { serviceRepo.getTeamNames } returns MutableStateFlow("Cyan,Red")
        every { serviceRepo.getTeamColors } returns MutableStateFlow("Blue,Red")
        inject("repo\$delegate", lazyOf(serviceRepo))
        service.applySettingsFromCompanion(managedSettings(), currentGeneration)
        coVerify(exactly = 1) { serviceRepo.enablePhoneManagedSettings("AA:BB", any()) }
        assertTrue(mayEnable.captured())
        repo.enablePhoneManagedSettings("AA:BB", mayEnable.captured)
        assertTrue(repo.phoneManagedSettingsEnabled.first())
        assertEquals("AA:BB", repo.phoneManagedCompanionAddress.first())

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repo.enablePhoneManagedSettings("CC:DD") {
                    generation().get() == releasedGeneration
                }
            }
        }
        assertTrue(repo.phoneManagedSettingsEnabled.first())
        assertEquals("AA:BB", repo.phoneManagedCompanionAddress.first())
    }

    @Test fun duplicateSubscriptionInvalidatesSettingsQueuedForPreviousSession() = runBlocking {
        repo.storeCallsign("Before")
        val firstSubscriptionGeneration = generation().get()
        val settingsMutex = field("phoneManagementSettingsMutex") as kotlinx.coroutines.sync.Mutex
        settingsMutex.lock()

        rawHandle("set_settings", JSONObject().put("payload", JSONObject()
            .put("takServerList", org.json.JSONArray())
            .put("reportIntSecs", 60)
            .put("callsign", "Stale")
            .put("team", "Red")
            .put("role", "Team Member")
            .put("phoneManagedSettings", true)))
        scheduler.runCurrent()

        service.beginCompanionSubscription()
        val secondSubscriptionGeneration = generation().get()
        service.beginCompanionSubscription()
        assertNotEquals(firstSubscriptionGeneration, secondSubscriptionGeneration)
        assertNotEquals(secondSubscriptionGeneration, generation().get())

        settingsMutex.unlock()
        scheduler.runCurrent()

        assertEquals("Before", repo.getCallsign.first())
        assertFalse(repo.phoneManagedSettingsEnabled.first())
        assertNull(repo.phoneManagedCompanionAddress.first())
        verify(exactly = 0) {
            gattServer.notifyCharacteristicChanged(peer, txCharacteristic, false)
        }
    }

    @Test fun lateAutomaticManagedSettingsSnapshotCannotRelockReleasedSession() = runBlocking {
        repo.enablePhoneManagedSettings("AA:BB")
        val settingsMutex = field("phoneManagementSettingsMutex") as kotlinx.coroutines.sync.Mutex
        settingsMutex.lock()
        handle("set_settings", JSONObject().put("payload", JSONObject()
            .put("takServerList", org.json.JSONArray())
            .put("reportIntSecs", 30)
            .put("callsign", "PHONE")
            .put("team", "Cyan")
            .put("role", "Team Member")
            .put("phoneManagedSettings", true)))
        rawHandle(JSONObject().put("requestId", UUID.randomUUID().toString()))
        scheduler.runCurrent()
        settingsMutex.unlock()

        assertTrue(awaitAck().getJSONObject("payload").getBoolean("ok"))
        assertFalse(repo.phoneManagedSettingsEnabled.first())
    }

    @Test fun unbondedOrEndedGenerationCannotReleaseOrAcknowledge() = runBlocking {
        repo.enablePhoneManagedSettings("AA:BB")
        inject("notificationsEnabled", false)
        handle(JSONObject().put("requestId", UUID.randomUUID().toString()))
        assertNull(ack())
        assertTrue(repo.phoneManagedSettingsEnabled.first())

        inject("notificationsEnabled", true)
        ready = false
        handle(JSONObject().put("requestId", UUID.randomUUID().toString()))
        assertNull(ack())
        assertTrue(repo.phoneManagedSettingsEnabled.first())

        ready = true
        bonded = false
        handle(JSONObject().put("requestId", UUID.randomUUID().toString()))
        assertNull(ack())
        assertTrue(repo.phoneManagedSettingsEnabled.first())

        bonded = true
        rawHandle(JSONObject().put("requestId", UUID.randomUUID().toString()))
        generation().incrementAndGet()
        scheduler.runCurrent()
        assertNull(ack())
        assertTrue(repo.phoneManagedSettingsEnabled.first())
    }

    @Test fun delayedSettingsRequestIsDiscardedAfterConnectionGenerationChanges() {
        val buildStarted = CompletableDeferred<Unit>()
        val finishBuild = CompletableDeferred<Unit>()
        val envelope = settingsEnvelope()
        coEvery { capRepo.buildWatchSettingsEnvelope() } coAnswers {
            buildStarted.complete(Unit)
            finishBuild.await()
            envelope
        }

        rawHandle("request_settings", JSONObject())
        scheduler.runCurrent()
        assertTrue(buildStarted.isCompleted)

        generation().incrementAndGet()
        finishBuild.complete(Unit)
        scheduler.runCurrent()

        coVerify(exactly = 1) { capRepo.buildWatchSettingsEnvelope() }
        verify(exactly = 0) {
            gattServer.notifyCharacteristicChanged(peer, txCharacteristic, false)
        }
    }

    @Test fun currentSettingsRequestSendsSnapshot() {
        coEvery { capRepo.buildWatchSettingsEnvelope() } returns settingsEnvelope()

        rawHandle("request_settings", JSONObject())
        scheduler.runCurrent()

        coVerify(exactly = 1) { capRepo.buildWatchSettingsEnvelope() }
        verify(exactly = 1) {
            gattServer.notifyCharacteristicChanged(peer, txCharacteristic, false)
        }
    }

    @Test fun settingsWaitingForMutexAreNotAppliedAfterConnectionGenerationChanges() = runBlocking {
        repo.storeCallsign("Before")
        repo.storeConstantReportingInterval(15)
        val settingsMutex = field("phoneManagementSettingsMutex") as kotlinx.coroutines.sync.Mutex
        settingsMutex.lock()

        rawHandle("set_settings", JSONObject().put("payload", JSONObject()
            .put("takServerList", org.json.JSONArray())
            .put("reportIntSecs", 60)
            .put("callsign", "Stale")
            .put("team", "Red")
            .put("role", "Team Member")
            .put("phoneManagedSettings", false)))
        scheduler.runCurrent()

        generation().incrementAndGet()
        settingsMutex.unlock()
        scheduler.runCurrent()

        assertEquals("Before", repo.getCallsign.first())
        assertEquals(15, repo.getConstantReportingInterval.first())
        verify(exactly = 0) {
            gattServer.notifyCharacteristicChanged(peer, txCharacteristic, false)
        }
    }

    @Test fun queuedJsonNotificationIsNotDeliveredAfterConnectionGenerationChanges() = runBlocking {
        val notifyMutex = field("notifyMutex") as kotlinx.coroutines.sync.Mutex
        notifyMutex.lock()
        service.sendJsonToCompanion("""{"msgType":"status"}""")
        scheduler.runCurrent()

        generation().incrementAndGet()
        notifyMutex.unlock()
        scheduler.runCurrent()

        verify(exactly = 0) {
            gattServer.notifyCharacteristicChanged(peer, txCharacteristic, false)
        }
    }

    @Test fun disableDeliversStopAfterDiscardingQueuedDiagnosticsWithWorkerPaused() {
        val session = prepareDiagnosticDisable()
        val chunks = mutableListOf<ByteArray>()
        every { txCharacteristic.setValue(capture(chunks)) } returns true
        every { gattServer.notifyCharacteristicChanged(peer, txCharacteristic, false) } returns true

        disableDiagnostics()
        invokeDiagnosticMethod("refreshLocationDiagnosticsSession")

        // Start the worker only after disable has returned: it cannot rescue a drained stop.
        startDiagnosticFrameWorker()
        scheduler.runCurrent()
        assertFalse("Disable must leave a stop frame for the notification worker", chunks.isEmpty())
        val delivered = JSONObject(chunks.fold(ByteArray(0)) { bytes, chunk -> bytes + chunk }
            .toString(Charsets.UTF_8))
        assertEquals(LocationDiagnosticsProtocol.STOP, delivered.getString("msgType"))
        assertEquals(session, delivered.getString("session"))
        assertNotNull(LocationDiagnosticsProtocol.parse(delivered, LocationDiagnosticsProtocol.STOP, session))
        assertNull((field("locationDiagnosticFrames") as Channel<*>).tryReceive().getOrNull())
    }

    @Test fun disabledSessionStopCannotReachReplacementConnection() {
        prepareDiagnosticDisable()
        disableDiagnostics()
        generation().incrementAndGet()

        startDiagnosticFrameWorker()
        scheduler.runCurrent()

        verify(exactly = 0) { gattServer.notifyCharacteristicChanged(peer, txCharacteristic, false) }
    }

    private fun prepareDiagnosticDisable(): String {
        val diagnostics = mockk<BondedLocationDiagnostics>(relaxed = true)
        inject("locationDiagnostics\$delegate", lazyOf(diagnostics))
        inject("negotiatedMtu", 517)
        inject("locationDiagnosticsEnabled", true)
        invokeDiagnosticMethod("refreshLocationDiagnosticsSession")
        val session = field("locationDiagnosticsSession") as String
        assertFalse((field("locationDiagnosticFrames") as Channel<*>).isEmpty)
        return session
    }

    private fun disableDiagnostics() {
        inject("locationDiagnosticsEnabled", false)
        CompanionAppService::class.java.getDeclaredMethod(
            "stopLocationDiagnosticsSession", String::class.java
        ).apply { isAccessible = true }.invoke(service, "disabled")
        val diagnostics = (field("locationDiagnostics\$delegate") as Lazy<*>).value as BondedLocationDiagnostics
        verify(exactly = 1) { diagnostics.stop() }
    }

    private fun startDiagnosticFrameWorker() {
        val bloodhound = mockk<BloodhoundRepository>(relaxed = true)
        every { bloodhound.activeUid } returns MutableStateFlow(null)
        every { bloodhound.activeBhItemMap } returns MutableStateFlow(null)
        every { bloodhound.routeFollow } returns MutableStateFlow(null)
        every { bloodhound.activeTrackingItem() } returns null
        every { capRepo.bleConnectionState } returns
            MutableStateFlow(CompanionAppRepository.BleConnectionState.NOT_CONNECTED)
        inject("bhRepo\$delegate", lazyOf(bloodhound))
        invokeDiagnosticMethod("startBloodhoundSync")
    }

    private fun invokeDiagnosticMethod(name: String) {
        CompanionAppService::class.java.getDeclaredMethod(name).apply {
            isAccessible = true
        }.invoke(service)
    }

    private fun handle(payload: JSONObject) {
        handle("release_phone_management", payload)
    }

    private fun handle(type: String, payload: JSONObject) {
        rawHandle(type, payload)
        scheduler.runCurrent()
    }

    private fun rawHandle(payload: JSONObject) {
        rawHandle("release_phone_management", payload)
    }

    private fun rawHandle(type: String, payload: JSONObject) {
        val method = CompanionAppService::class.java.getDeclaredMethod(
            "handleCompanionLine", String::class.java
        ).apply { isAccessible = true }
        method.invoke(
            service,
            JSONObject().put("msg_type", type).put("payload", payload).toString()
        )
    }

    private fun generation(): AtomicLong = field("bloodhoundConnectionGeneration") as AtomicLong

    private fun stubServerPersistence() {
        val serviceRepo = io.mockk.spyk(repo)
        coEvery { serviceRepo.storeProvisionedTakServerList(any()) } returns mockk(relaxed = true)
        coEvery { serviceRepo.awaitTakServerList() } returns emptyList()
        inject("repo\$delegate", lazyOf(serviceRepo))
    }

    private fun managedSettings() = CompanionAppRepository.SetSettingsPayload(
        takServerList = emptyList(),
        reportIntSecs = 30,
        callsign = "PHONE",
        team = "Cyan",
        role = "Team Member",
        phoneManagedSettings = true
    )

    private fun settingsEnvelope() = CompanionAppRepository.Envelope(
        vers = 1,
        msgType = "settings_request",
        msgUid = UUID.randomUUID().toString(),
        t = "test",
        src = "wearos",
        payload = CompanionAppRepository.WearTakSettingsSnapshot(
            timeMillis = 0L,
            takServerList = emptyList(),
            reportIntSecs = 30,
            callsign = "PHONE",
            team = "Cyan",
            role = "Team Member",
            phoneManagedSettings = false
        )
    )

    private fun inject(name: String, value: Any) {
        CompanionAppService::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(service, value)
        }
    }

    private fun awaitAck(): JSONObject {
        repeat(200) {
            scheduler.runCurrent()
            ack()?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("Management release acknowledgement was not queued")
    }

    private fun ack(): JSONObject? {
        val frame = (field("bloodhoundFrames") as Channel<*>).tryReceive().getOrNull() ?: return null
        val bytes = frame.javaClass.getDeclaredField("bytes").apply { isAccessible = true }.get(frame) as ByteArray
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }

    private fun field(name: String): Any =
        CompanionAppService::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }.get(service) ?: error("Missing service field: $name")
}
