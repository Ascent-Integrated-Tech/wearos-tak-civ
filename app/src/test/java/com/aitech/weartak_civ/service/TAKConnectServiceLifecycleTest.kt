package com.aitech.weartak_civ.service

import android.app.Application
import android.net.Network
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.model.TakChannelStatus
import com.aitech.weartak_civ.data.takserver.TakConnectionFailure
import com.aitech.weartak_civ.data.takserver.TakConnectionCloser
import com.aitech.weartak_civ.data.takserver.TakEnrollmentException
import com.aitech.weartak_civ.data.takserver.TakEnrollmentStage
import com.aitech.weartak_civ.data.takserver.TakFailureCategory
import com.aitech.weartak_civ.data.takserver.TakServerRegistration
import com.aitech.weartak_civ.manager.NetworkManager
import com.aitech.weartak_civ.presentation.MainActivity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.isActive
import org.junit.Assert.assertNull
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import javax.net.ssl.SSLSocket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class TAKConnectServiceLifecycleTest {
    private val controllers = mutableListOf<ServiceController<TAKConnectService>>()
    private lateinit var repository: DataRepository
    private val serverId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private var previousStopping = false
    private var previousRunning = false
    private val companionConnected = MutableStateFlow(false)
    private val takEnabled = MutableStateFlow(true)
    private val worn = MutableStateFlow<Boolean?>(null)

    @Before
    fun setUp() {
        val activity = mockk<MainActivity>(relaxed = true)
        every { activity.applicationContext } returns RuntimeEnvironment.getApplication()
        MainActivity.mainActivity = activity
        repository = mockk(relaxed = true)
        MainActivity.dataRepository = repository
        coEvery { repository.tryAwaitTakServerList() } returns listOf(
            DataRepository.TakServerItem(
                true, "Test", "tak.example.invalid", 8089, false,
                "user", "password", "", "", serverId,
            )
        )
        every { repository.getEnableEsriEndpoint } returns flowOf(false)
        every { repository.getEnableTakEndpoint } returns takEnabled
        every { repository.latestDeviceWornObservation } returns worn
        every { repository.getEsriEndpoint } returns flowOf("")
        every { repository.takServerConfigurationRefreshes } returns emptyFlow()
        every { repository.geofenceServiceEnabled } returns MutableStateFlow(false)
        every { repository.multicastEnabled } returns MutableStateFlow(false)
        every { repository.multicastAddress } returns MutableStateFlow(null)
        every { repository.multicastPort } returns MutableStateFlow(null)
        every { repository.isCompanionConnected } returns companionConnected
        every { repository.takChannels } returns MutableStateFlow(emptyList())
        every { repository.networkSettingsLocked } returns flowOf(false)
        val networkManager = mockk<NetworkManager>(relaxed = true)
        every { networkManager.wifiNetwork } returns MutableStateFlow<Network?>(null)
        mockkObject(NetworkManager.Companion)
        every { NetworkManager.getInstance() } returns networkManager
        previousStopping = TAKConnectService.serviceStopping
        previousRunning = TAKConnectService.isRunning.get()
        TAKConnectService.isNetworkConnected = false
        TAKConnectService.takServerDetails = mutableListOf()
    }

    @After
    fun tearDown() = runBlocking {
        controllers.asReversed().forEach { it.destroy() }
        val scope = currentConnectionScope()
        scope.cancel()
        withTimeout(5_000) { scope.coroutineContext[Job]!!.join() }
        // Other service tests use static entry points without starting an Android service.
        connectionScopeField().set(null, CoroutineScope(Dispatchers.IO + SupervisorJob()))
        TAKConnectService.serviceStopping = previousStopping
        TAKConnectService.isRunning.set(previousRunning)
        TAKConnectService.takEndpointEnabled = true
        unmockkAll()
    }

    @Test
    fun offWristInvalidatesTcpHttpAndRetriesAndWornImmediatelyRestoresAdmission() {
        createService()
        val detail = TAKConnectService.getTakServerDetail(serverId)!!
        val socket = mockk<SSLSocket>(relaxed = true)
        val raw = mockk<java.net.Socket>(relaxed = true)
        val call = mockk<okhttp3.Call>(relaxed = true)
        val session = com.aitech.weartak_civ.data.takserver.TakHttpSession { true }
        session.register(call)
        val attempt = TAKConnectService.ConnectionAttempt(detail.runtime.generation)
        attempt.httpSession = session
        attempt.rawSocket = raw
        attempt.socket = socket
        detail.runtime.currentAttempt = attempt
        detail.secureSocket = socket
        detail.connectionStatus.set(TAKConnectService.TAK_SERVER_CONNECTED_COT_PORT)
        val retry = Job()
        detail.runtime.retryJob = retry
        worn.value = false
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(TakConnectionCloser.awaitPendingCloses())
        verify { socket.close(); raw.close(); call.cancel() }
        assertTrue(retry.isCancelled)
        assertNull(detail.runtime.currentAttempt)
        assertFalse(TAKConnectService.missionConnectionReady(detail))

        mockkObject(TAKConnectService.Companion)
        every { TAKConnectService.loadKeyStore(any(), any(), any()) } returns false
        every { TAKConnectService.fetchAndParseCertificateConfig(any(), any()) } throws
            TakEnrollmentException(TakConnectionFailure(
                TakEnrollmentStage.CONFIG, TakFailureCategory.AUTHENTICATION, 401))
        TAKConnectService.isNetworkConnected = true
        TAKConnectService.getClientCertificateAndConnect(detail)
        assertNull(detail.runtime.currentAttempt)
        worn.value = true
        shadowOf(Looper.getMainLooper()).idle()
        verify(timeout = 5_000) { TAKConnectService.fetchAndParseCertificateConfig(detail, any()) }
    }

    @Test
    fun takMasterOffClosesSocketsAndRetriesButKeepsSelectionsAndSharedScope() = runBlocking {
        createService()
        val detail = TAKConnectService.getTakServerDetail(serverId)!!
        val socket = mockk<SSLSocket>(relaxed = true)
        detail.secureSocket = socket
        val retry = Job()
        detail.runtime.retryJob = retry
        takEnabled.value = false
        verify(timeout = 5_000) { socket.close() }
        assertTrue(detail.isEnabled)
        assertTrue(retry.isCancelled)
        assertTrue(currentConnectionScope().isActive)
        assertTrue(TAKConnectService.isRunning.get())
        TAKConnectService.isNetworkConnected = true
        TAKConnectService.getClientCertificateAndConnect(detail)
        assertNull(detail.runtime.currentAttempt)
        assertFalse(TAKConnectService.missionConnectionReady(detail))
    }

    @Test
    fun companionConnectionClosesDirectTakSocketAndDisconnectRestoresConnectionAttempts() = runBlocking {
        createService()
        val detail = TAKConnectService.getTakServerDetail(serverId)!!
        val socket = mockk<SSLSocket>(relaxed = true)
        detail.secureSocket = socket
        companionConnected.value = true
        TAKConnectService.sendData(detail, "<event/>", true, 0)
        verify(exactly = 0) { socket.getOutputStream() }
        verify(timeout = 5_000) { socket.close() }

        mockkObject(TAKConnectService.Companion)
        every { TAKConnectService.loadKeyStore(any(), any(), any()) } returns false
        every { TAKConnectService.fetchAndParseCertificateConfig(any(), any()) } throws
            TakEnrollmentException(
                TakConnectionFailure(TakEnrollmentStage.CONFIG, TakFailureCategory.AUTHENTICATION, 401)
            )
        TAKConnectService.isNetworkConnected = true
        companionConnected.value = false
        verify(timeout = 5_000) { TAKConnectService.fetchAndParseCertificateConfig(detail, any()) }
        companionConnected.value = true
        TAKConnectService.getClientCertificateAndConnect(detail)
        verify(exactly = 1) { TAKConnectService.fetchAndParseCertificateConfig(detail, any()) }
    }

    @Test
    fun recreationRunsStaticConnectionAndChannelLaunchesAfterCancelledScope() = runBlocking {
        val first = createService()
        val oldScope = currentConnectionScope()
        val oldChild = oldScope.launch { awaitCancellation() }
        first.destroy()
        assertTrue(oldScope.coroutineContext[Job]!!.isCancelled)
        withTimeout(5_000) { oldChild.join() }

        createService()
        val newScope = currentConnectionScope()
        assertNotSame(oldScope, newScope)
        assertTrue(newScope.coroutineContext[Job]!!.isActive)
        assertFalse(TAKConnectService.serviceStopping)
        assertTrue(TAKConnectService.isRunning.get())
        assertConnectionAndChannelLaunchesRun()
    }

    @Test
    fun queuedChannelActionsRecheckNetworkLockBeforeExecuting() = runBlocking {
        createService()
        val locked = MutableStateFlow(false)
        every { repository.networkSettingsLocked } returns locked
        val detail = TAKConnectService.getTakServerDetail(serverId)!!
        detail.channelMutex.lock()
        val existingJobs = currentConnectionScope().coroutineContext[Job]!!.children.toSet()
        TAKConnectService.refreshTakChannels(serverId)
        TAKConnectService.updateTakChannel(serverId, 1, true)
        val queuedJobs = currentConnectionScope().coroutineContext[Job]!!.children
            .filter { it !in existingJobs }.toList()
        assertEquals(2, queuedJobs.size)
        locked.value = true
        detail.channelMutex.unlock()
        withTimeout(5_000) { queuedJobs.forEach { it.join() } }
        verify(exactly = 0) { repository.updateTakServerChannels(any()) }
        verify(exactly = 0) { repository.takChannels }
    }

    @Test
    fun lateDestructionCannotCancelReplacementJobsOrInvalidateItsAttempt() = runBlocking {
        val first = createService()
        val oldScope = currentConnectionScope()
        val oldChild = oldScope.launch { awaitCancellation() }
        val oldSocket = mockk<SSLSocket>(relaxed = true)
        TAKConnectService.getTakServerDetail(serverId)!!.secureSocket = oldSocket
        val replacement = createService()
        val newScope = currentConnectionScope()
        val replacementChild = newScope.launch { awaitCancellation() }
        withTimeout(5_000) { oldChild.join() }
        assertTrue(oldScope.coroutineContext[Job]!!.isCancelled)
        assertTrue(TakConnectionCloser.awaitPendingCloses())
        verify(exactly = 1) { oldSocket.close() }
        assertConnectionAndChannelLaunchesRun()
        val detail = TAKConnectService.getTakServerDetail(serverId)!!
        val attempt = detail.runtime.currentAttempt
        val replacementSocket = mockk<SSLSocket>(relaxed = true)
        detail.secureSocket = replacementSocket

        first.destroy()
        assertSame(newScope, currentConnectionScope())
        assertTrue(replacementChild.isActive)
        assertTrue(newScope.coroutineContext[Job]!!.isActive)
        assertFalse(TAKConnectService.serviceStopping)
        assertTrue(TAKConnectService.isRunning.get())
        assertSame(attempt, detail.runtime.currentAttempt)
        assertTrue(TakConnectionCloser.awaitPendingCloses())
        verify(exactly = 0) { replacementSocket.close() }

        replacement.destroy()
        assertTrue(newScope.coroutineContext[Job]!!.isCancelled)
        withTimeout(5_000) { replacementChild.join() }
        assertTrue(TAKConnectService.serviceStopping)
        assertFalse(TAKConnectService.isRunning.get())
        assertTrue(TakConnectionCloser.awaitPendingCloses())
        verify(exactly = 1) { replacementSocket.close() }
    }

    @Test
    fun offWristAtServiceStartClosesActiveTlsAndHttpOffMainThread() {
        createService()
        val detail = TAKConnectService.getTakServerDetail(serverId)!!
        val mainThread = Thread.currentThread()
        val closeThreads = java.util.Collections.synchronizedList(mutableListOf<Thread>())
        val socket = mockk<SSLSocket>(relaxed = true)
        every { socket.close() } answers {
            closeThreads += Thread.currentThread()
            if (Thread.currentThread() === mainThread) throw android.os.NetworkOnMainThreadException()
        }
        val call = mockk<okhttp3.Call>(relaxed = true)
        every { call.cancel() } answers { closeThreads += Thread.currentThread() }
        val session = com.aitech.weartak_civ.data.takserver.TakHttpSession { true }
        session.register(call)
        val attempt = TAKConnectService.ConnectionAttempt(detail.runtime.generation)
        attempt.httpSession = session
        attempt.socket = socket
        detail.runtime.currentAttempt = attempt
        detail.secureSocket = socket
        detail.connectionStatus.set(TAKConnectService.TAK_SERVER_CONNECTED_COT_PORT)

        // p368: process restart while off-wrist; onCreate and the Main.immediate off-body
        // collector both tear down the live TLS/HTTP session on the main thread.
        worn.value = false
        createService()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(detail.runtime.currentAttempt)
        assertTrue(TakConnectionCloser.awaitPendingCloses())
        verify(exactly = 1) { socket.close() }
        verify(exactly = 1) { call.cancel() }
        assertEquals(2, closeThreads.size)
        assertTrue(closeThreads.none { it === mainThread })
        assertTrue(closeThreads.all { it.name == TakConnectionCloser.THREAD_NAME })
    }

    @Test
    fun screenOffWifiDropWhileWornClosesActiveTlsAndHttpOffMainThread() {
        worn.value = true
        takEnabled.value = false // keep the network change from launching a real reconnect
        val controller = createService()
        selectedTakNetwork(controller.get()).value = mockk<Network>(relaxed = true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(TakConnectionCloser.awaitPendingCloses())
        val detail = TAKConnectService.getTakServerDetail(serverId)!!
        val mainThread = Thread.currentThread()
        val closeThreads = java.util.Collections.synchronizedList(mutableListOf<Thread>())
        val socket = mockk<SSLSocket>(relaxed = true)
        every { socket.close() } answers {
            closeThreads += Thread.currentThread()
            if (Thread.currentThread() === mainThread) throw android.os.NetworkOnMainThreadException()
        }
        val call = mockk<okhttp3.Call>(relaxed = true)
        every { call.cancel() } answers { closeThreads += Thread.currentThread() }
        val session = com.aitech.weartak_civ.data.takserver.TakHttpSession { true }
        session.register(call)
        val attempt = TAKConnectService.ConnectionAttempt(detail.runtime.generation)
        attempt.httpSession = session
        attempt.socket = socket
        detail.runtime.currentAttempt = attempt
        detail.secureSocket = socket
        detail.connectionStatus.set(TAKConnectService.TAK_SERVER_CONNECTED_COT_PORT)
        val app = RuntimeEnvironment.getApplication()
        val power = app.getSystemService(android.os.PowerManager::class.java)

        // p368 on-wrist: screen goes dark, WearWifiMediator toggles the Wi-Fi radio and the
        // main-looper network callback changes the selected TAK network on the main thread.
        shadowOf(power).setIsInteractive(false)
        app.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_SCREEN_OFF))
        selectedTakNetwork(controller.get()).value = null
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(power.isInteractive)
        assertNull(detail.runtime.currentAttempt)
        assertTrue(TakConnectionCloser.awaitPendingCloses())
        verify(exactly = 1) { socket.close() }
        verify(exactly = 1) { call.cancel() }
        assertEquals(2, closeThreads.size)
        assertTrue(closeThreads.none { it === mainThread })
        assertTrue(closeThreads.all { it.name == TakConnectionCloser.THREAD_NAME })
    }

    @Suppress("UNCHECKED_CAST")
    private fun selectedTakNetwork(service: TAKConnectService): MutableStateFlow<Network?> {
        val preference = TAKConnectService::class.java.getDeclaredField("takNetworkPreference")
            .apply { isAccessible = true }.get(service)!!
        return preference.javaClass.getDeclaredField("selected")
            .apply { isAccessible = true }.get(preference) as MutableStateFlow<Network?>
    }

    @Test
    fun creationCancelsAnyPreServiceScopeWithoutLeakingItsChildren() = runBlocking {
        val initialScope = currentConnectionScope()
        val started = CompletableDeferred<Unit>()
        val initialChild = initialScope.launch {
            started.complete(Unit)
            awaitCancellation()
        }
        withTimeout(5_000) { started.await() }
        createService()
        assertTrue(initialScope.coroutineContext[Job]!!.isCancelled)
        withTimeout(5_000) { initialChild.join() }
        assertNotSame(initialScope, currentConnectionScope())
        assertTrue(currentConnectionScope().coroutineContext[Job]!!.isActive)
    }

    @Test
    fun repeatedServiceStartKeepsAnExistingTakConnectionAttempt() = runBlocking {
        val controller = createService()
        val detail = TAKConnectService.getTakServerDetail(serverId)!!
        val attempt = TAKConnectService.ConnectionAttempt(1L)
        detail.runtime.generation = attempt.generation
        detail.runtime.currentAttempt = attempt
        detail.connectionStatus.set(TAKConnectService.TAK_SERVER_CONNECTED_COT_PORT)

        controller.get().connectToTakServers()

        assertSame(attempt, detail.runtime.currentAttempt)
        assertEquals(attempt.generation, detail.runtime.generation)
        assertEquals(TAKConnectService.TAK_SERVER_CONNECTED_COT_PORT, detail.connectionStatus.get())
    }

    @Test
    fun missionSyncRequiresAnActiveDirectTakConnection() {
        val detail = TAKConnectService.TakServerDetail(
            serverId, true, "Test", "tak.example.invalid", 8089, false,
            "user", "password", null, null,
        )

        assertFalse(TAKConnectService.missionConnectionReady(detail))
        detail.connectionStatus.set(TAKConnectService.TAK_SERVER_CONNECTED_COT_PORT)
        assertTrue(TAKConnectService.missionConnectionReady(detail))

        companionConnected.value = true
        assertFalse(TAKConnectService.missionConnectionReady(detail))
        companionConnected.value = false
        detail.isEnabled = false
        assertFalse(TAKConnectService.missionConnectionReady(detail))
    }

    @Test
    fun destructionEndsActiveEnrollmentAndRecreationCanRetryWithoutStalePublication() = runBlocking {
        val first = createService()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val failure = TakConnectionFailure(
            TakEnrollmentStage.CONFIG, TakFailureCategory.AUTHENTICATION, 401
        )
        mockkObject(TAKConnectService.Companion)
        every { TAKConnectService.loadKeyStore(any(), any(), any()) } returns false
        every { TAKConnectService.fetchAndParseCertificateConfig(any(), any()) } answers {
            started.complete(Unit)
            runBlocking { release.await() }
            throw TakEnrollmentException(failure)
        }
        TAKConnectService.isNetworkConnected = true
        try {
            TAKConnectService.serverRegistration.saveAndConnect(
                serverId, save = { TakServerRegistration.SaveResult.SAVED },
                connect = { TAKConnectService.getInstance().registerServer(serverId) }
            )
            withTimeout(5_000) { started.await() }
            val oldJob = TAKConnectService.getTakServerDetail(serverId)!!.runtime.currentAttempt!!.job!!
            assertTrue(TAKConnectService.serverRegistration.state.value.isLoading)
            first.destroy()
            assertEquals(
                TakServerRegistration.Status.FAILED, TAKConnectService.serverRegistration.state.value.status
            )

            TAKConnectService.isNetworkConnected = false
            createService()
            val newDetail = TAKConnectService.getTakServerDetail(serverId)!!
            TAKConnectService.serverRegistration.prepare(serverId)
            TAKConnectService.serverRegistration.bind(serverId, newDetail)
            release.complete(Unit)
            withTimeout(5_000) { oldJob.join() }
            assertTrue(oldJob.isCancelled)
            assertEquals(
                TakServerRegistration.Status.CONNECTING, TAKConnectService.serverRegistration.state.value.status
            )

            every { TAKConnectService.fetchAndParseCertificateConfig(any(), any()) } throws TakEnrollmentException(failure)
            TAKConnectService.isNetworkConnected = true
            TAKConnectService.getClientCertificateAndConnect(newDetail)
            withTimeout(5_000) { newDetail.runtime.currentAttempt!!.job!!.join() }
            assertEquals(failure, TAKConnectService.serverRegistration.state.value.failure)
        } finally {
            release.complete(Unit)
        }
    }

    private fun assertConnectionAndChannelLaunchesRun() = runBlocking {
        // Fail enrollment deterministically: reaching the config callback proves the
        // connection coroutine ran, without opening a certificate or TCP connection.
        mockkObject(TAKConnectService.Companion)
        every { TAKConnectService.loadKeyStore(any(), any(), any()) } returns false
        every { TAKConnectService.fetchAndParseCertificateConfig(any(), any()) } throws
            TakEnrollmentException(
                TakConnectionFailure(TakEnrollmentStage.CONFIG, TakFailureCategory.AUTHENTICATION, 401)
            )
        TAKConnectService.isNetworkConnected = true
        val detail = TAKConnectService.getTakServerDetail(serverId)!!
        TAKConnectService.getClientCertificateAndConnect(detail)
        val job = detail.runtime.currentAttempt!!.job!!
        withTimeout(5_000) { job.join() }
        verify(exactly = 1) { TAKConnectService.fetchAndParseCertificateConfig(detail, any()) }

        TAKConnectService.refreshTakChannels(serverId)
        verify(timeout = 5_000) {
            repository.updateTakServerChannels(match {
                it.serverId == serverId && it.status == TakChannelStatus.LOADING
            })
        }
    }

    private fun createService(): ServiceController<TAKConnectService> =
        Robolectric.buildService(TAKConnectService::class.java).also {
            controllers += it
            it.create()
        }

    private fun currentConnectionScope(): CoroutineScope =
        connectionScopeField().get(null) as CoroutineScope

    private fun connectionScopeField() =
        TAKConnectService::class.java.getDeclaredField("connectionScope").apply {
            isAccessible = true
        }
}
