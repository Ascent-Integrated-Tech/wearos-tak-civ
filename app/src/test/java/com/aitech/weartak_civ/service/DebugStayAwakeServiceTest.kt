package com.aitech.weartak_civ.service

import android.app.Application
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowConnectivityManager
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34],
    shadows = [DebugStayAwakeServiceTest.StayAwakeConnectivityShadow::class])
class DebugStayAwakeServiceTest {
    private lateinit var controller: ServiceController<DebugStayAwakeService>
    private lateinit var service: DebugStayAwakeService
    private lateinit var connectivity: StayAwakeConnectivityShadow

    @Before
    fun setUp() {
        controller = Robolectric.buildService(DebugStayAwakeService::class.java).create()
        service = controller.get()
        connectivity = Shadow.extract(service.getSystemService(ConnectivityManager::class.java))
        service.getSharedPreferences("debug_stay_awake", Application.MODE_PRIVATE).edit().clear().commit()
        Settings.Global.putInt(service.contentResolver, Settings.Global.BOOT_COUNT, 1)
        setWirelessDebugging(false)
    }

    @After
    fun tearDown() {
        controller.destroy()
    }

    @Test
    fun keepsAllLocksWhenWirelessDebuggingStops() {
        setWirelessDebugging(true)
        start()
        assertTrue(DebugStayAwakeService.active.value)
        val screen = lock<PowerManager.WakeLock>("screenLock")
        val cpu = lock<PowerManager.WakeLock>("cpuLock")
        val wifi = lock<WifiManager.WifiLock>("wifiLock")
        assertTrue(screen.isHeld)
        assertTrue(cpu.isHeld)
        assertTrue(wifi.isHeld)

        setWirelessDebugging(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertTrue(DebugStayAwakeService.active.value)
        assertTrue(screen.isHeld)
        assertTrue(cpu.isHeld)
        assertTrue(wifi.isHeld)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals(1, connectivity.networkCallbacks.size)
    }

    @Test
    fun startsWithoutWirelessDebugging() {
        assertFalse(DebugStayAwakeService.active.value)
        start()
        assertTrue(DebugStayAwakeService.active.value)
        assertTrue(lock<PowerManager.WakeLock>("screenLock").isHeld)
        assertTrue(lock<PowerManager.WakeLock>("cpuLock").isHeld)
        assertTrue(lock<WifiManager.WifiLock>("wifiLock").isHeld)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals(0, Settings.Global.getInt(service.contentResolver, "adb_wifi_enabled", 0))
        assertEquals(1, connectivity.networkCallbacks.size)
        val request = connectivity.requests.single()
        assertTrue(request.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        assertFalse(request.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertEquals(null, service.getSystemService(ConnectivityManager::class.java).boundNetworkForProcess)
    }

    @Test
    fun stopActionReleasesLocksWithoutChangingWirelessDebugging() {
        setWirelessDebugging(true)
        start()
        val screen = lock<PowerManager.WakeLock>("screenLock")
        val cpu = lock<PowerManager.WakeLock>("cpuLock")
        val wifi = lock<WifiManager.WifiLock>("wifiLock")
        assertEquals(Service.START_NOT_STICKY,
            service.onStartCommand(Intent().setAction(DebugStayAwakeService.ACTION_STOP), 0, 2))
        assertFalse(DebugStayAwakeService.active.value)
        assertFalse(screen.isHeld)
        assertFalse(cpu.isHeld)
        assertFalse(wifi.isHeld)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(connectivity.networkCallbacks.isEmpty())
        assertTrue(Settings.Global.getInt(
            service.contentResolver, "adb_wifi_enabled", 0
        ) == 1)
    }

    @Test
    fun destructionReleasesLocks() {
        start()
        val screen = lock<PowerManager.WakeLock>("screenLock")
        val cpu = lock<PowerManager.WakeLock>("cpuLock")
        val wifi = lock<WifiManager.WifiLock>("wifiLock")
        service.onDestroy()
        assertFalse(screen.isHeld)
        assertFalse(cpu.isHeld)
        assertFalse(wifi.isHeld)
        assertFalse(DebugStayAwakeService.active.value)
        assertTrue(connectivity.networkCallbacks.isEmpty())
    }

    @Test
    fun nullRestartDoesNotEnableMode() {
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertFalse(DebugStayAwakeService.active.value)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(connectivity.requests.isEmpty())
    }

    @Test
    fun nullRestartRetainsEnabledIntentAndExistingLocks() {
        start()
        val screen = lock<PowerManager.WakeLock>("screenLock")
        val cpu = lock<PowerManager.WakeLock>("cpuLock")
        val wifi = lock<WifiManager.WifiLock>("wifiLock")
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 2))
        assertTrue(screen.isHeld)
        assertTrue(cpu.isHeld)
        assertTrue(wifi.isHeld)
        assertTrue(DebugStayAwakeService.active.value)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals(1, connectivity.networkCallbacks.size)
    }

    @Test
    fun removingRecentTaskKeepsSessionActive() {
        start()
        service.onTaskRemoved(Intent())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertTrue(DebugStayAwakeService.active.value)
        assertTrue(lock<PowerManager.WakeLock>("screenLock").isHeld)
        assertTrue(lock<PowerManager.WakeLock>("cpuLock").isHeld)
        assertTrue(lock<WifiManager.WifiLock>("wifiLock").isHeld)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals(1, connectivity.networkCallbacks.size)
    }

    @Test
    fun repeatedStartKeepsOnlyOneWifiRequest() {
        start()
        start()
        assertEquals(1, connectivity.requests.size)
        assertEquals(1, connectivity.networkCallbacks.size)
    }

    @Test
    fun deniedWifiRequestReleasesLocksAndStopsSession() {
        legacyOptIn(true)
        lateinit var screen: PowerManager.WakeLock
        lateinit var cpu: PowerManager.WakeLock
        lateinit var wifi: WifiManager.WifiLock
        connectivity.beforeRequest = {
            screen = lock("screenLock")
            cpu = lock("cpuLock")
            wifi = lock("wifiLock")
            assertTrue(screen.isHeld)
            assertTrue(cpu.isHeld)
            assertTrue(wifi.isHeld)
        }
        connectivity.denyRequest = true
        start()
        assertFalse(DebugStayAwakeService.active.value)
        assertFalse(wifi.isHeld)
        assertFalse(cpu.isHeld)
        assertFalse(screen.isHeld)
        assertEquals(0, shadowOf(service.getSystemService(WifiManager::class.java)).activeLockCount)
        assertTrue(connectivity.networkCallbacks.isEmpty())
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(DebugStayAwakePreferences(service).enabled)
        connectivity.denyRequest = false
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 2))
        assertTrue(DebugStayAwakeService.active.value)
    }

    @Test
    fun optedInSessionResumesAfterPackageReplacement() {
        legacyOptIn(true)
        start()
        service.onDestroy()
        assertFalse(DebugStayAwakeService.active.value)
        assertTrue(DebugStayAwakePreferences(service).enabled)

        DebugStayAwakeUpdateReceiver().onReceive(service, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        val restart = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals(DebugStayAwakeService::class.java.name, restart?.component?.className)
        assertEquals(Service.START_STICKY, service.onStartCommand(restart, 0, 2))
        assertTrue(DebugStayAwakeService.active.value)
    }

    @Test
    fun updateResumesWithoutOldOptInButNeverWhenExplicitlyOff() {
        start()
        service.onDestroy()
        DebugStayAwakeUpdateReceiver().onReceive(service, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(DebugStayAwakeService::class.java.name,
            shadowOf(RuntimeEnvironment.getApplication()).nextStartedService?.component?.className)
        service.onStartCommand(Intent().setAction(DebugStayAwakeService.ACTION_STOP), 0, 2)
        legacyOptIn(true)
        DebugStayAwakeUpdateReceiver().onReceive(service, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(null, shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    @Test
    fun explicitSwitchStopAndNotificationStopPreventUpdateResume() {
        val preferences = DebugStayAwakePreferences(service)
        legacyOptIn(true)
        start()
        DebugStayAwakeService.stop(service)
        assertFalse(preferences.enabled)
        start()
        service.onStartCommand(Intent().setAction(DebugStayAwakeService.ACTION_STOP), 0, 2)
        assertFalse(preferences.enabled)
    }

    @Test
    fun legacyOptOutDoesNotOverrideEnabledIntent() {
        legacyOptIn(true)
        start()
        legacyOptIn(false)
        service.onDestroy()
        DebugStayAwakeUpdateReceiver().onReceive(service, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(DebugStayAwakeService::class.java.name,
            shadowOf(RuntimeEnvironment.getApplication()).nextStartedService?.component?.className)
        assertFalse(DebugStayAwakeService.active.value)
    }

    @Test
    fun rebootRestoresEnabledIntentRegardlessOfOldBootCount() {
        val preferences = DebugStayAwakePreferences(service)
        legacyOptIn(true)
        start()
        service.onDestroy()
        DebugStayAwakeUpdateReceiver().onReceive(service, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(DebugStayAwakeService::class.java.name,
            shadowOf(RuntimeEnvironment.getApplication()).nextStartedService?.component?.className)
        Settings.Global.putInt(service.contentResolver, Settings.Global.BOOT_COUNT, 2)
        DebugStayAwakeUpdateReceiver().onReceive(service, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(DebugStayAwakeService::class.java.name,
            shadowOf(RuntimeEnvironment.getApplication()).nextStartedService?.component?.className)
        assertTrue(preferences.enabled)
    }

    @Test
    fun migrationPreservesEveryLegacyEnabledOffCombination() {
        for (enabled in listOf(false, true)) for (oldOptIn in listOf(false, true)) {
            val preferences = DebugStayAwakePreferences(service)
            legacyOptIn(oldOptIn)
            preferences.recordSession(enabled)
            Settings.Global.putInt(service.contentResolver, Settings.Global.BOOT_COUNT, 99)
            assertEquals(enabled, DebugStayAwakePreferences(service).enabled)
        }
    }

    @Test
    fun processRecreationRestoresLocksFromIntentWithoutStartingOtherServices() {
        start()
        controller.destroy()
        controller = Robolectric.buildService(DebugStayAwakeService::class.java).create()
        service = controller.get()
        connectivity = Shadow.extract(service.getSystemService(ConnectivityManager::class.java))
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 2))
        assertTrue(DebugStayAwakeService.active.value)
        assertTrue(lock<PowerManager.WakeLock>("screenLock").isHeld)
        assertTrue(lock<PowerManager.WakeLock>("cpuLock").isHeld)
        assertTrue(lock<WifiManager.WifiLock>("wifiLock").isHeld)
        assertEquals(null, shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    @Test
    fun alreadyReleasedWifiRequestDoesNotPreventLockCleanup() {
        start()
        val screen = lock<PowerManager.WakeLock>("screenLock")
        val cpu = lock<PowerManager.WakeLock>("cpuLock")
        val wifi = lock<WifiManager.WifiLock>("wifiLock")
        connectivity.alreadyReleased = true
        service.onStartCommand(Intent().setAction(DebugStayAwakeService.ACTION_STOP), 0, 2)
        service.onDestroy()
        assertFalse(screen.isHeld)
        assertFalse(cpu.isHeld)
        assertFalse(wifi.isHeld)
        assertFalse(DebugStayAwakeService.active.value)
        assertTrue(connectivity.networkCallbacks.isEmpty())
        assertEquals(1, connectivity.unregisterCalls)
    }

    @Test
    fun queuedRestoreCannotOverrideExplicitOff() {
        DebugStayAwakePreferences(service).recordSession(true)
        DebugStayAwakeUpdateReceiver().onReceive(service, Intent(Intent.ACTION_BOOT_COMPLETED))
        val restore = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        DebugStayAwakeService.stop(service)

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(restore, 0, 1))
        assertFalse(DebugStayAwakePreferences(service).enabled)
        assertFalse(DebugStayAwakeService.active.value)
        assertTrue(connectivity.requests.isEmpty())
    }

    @Test
    fun defaultOffAndExplicitOffNeverStartFromBootUpdateOrUnrelatedBroadcast() {
        for (enabled in listOf(false, true)) {
            DebugStayAwakePreferences(service).recordSession(enabled)
            DebugStayAwakeUpdateReceiver().onReceive(service, Intent(Intent.ACTION_TIME_CHANGED))
            assertEquals(null, shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        }
        DebugStayAwakeService.stop(service)
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            DebugStayAwakeUpdateReceiver().onReceive(service, Intent(action))
            assertEquals(null, shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        }
    }

    @Test
    fun restoreStartDeniedByAndroidKeepsSavedIntentAndOnlyTargetsStayAwake() {
        DebugStayAwakePreferences(service).recordSession(true)
        val context = object : android.content.ContextWrapper(service) {
            override fun startForegroundService(intent: Intent): android.content.ComponentName? {
                assertEquals(DebugStayAwakeService::class.java.name, intent.component?.className)
                throw IllegalStateException("Background foreground-service start denied")
            }
        }
        DebugStayAwakeUpdateReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(DebugStayAwakePreferences(service).enabled)
        assertFalse(DebugStayAwakeService.active.value)
        assertTrue(connectivity.requests.isEmpty())
        assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("WearTAK-DebugStayAwake")
            .any { it.msg.contains("saved intent remains enabled") && it.throwable is IllegalStateException })
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 2))
        assertTrue(DebugStayAwakeService.active.value)
    }

    @Test
    fun bareOrUnknownStartCannotEnableSavedOff() {
        for (intent in listOf(Intent(), Intent().setAction("unknown"))) {
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent, 0, 1))
            assertFalse(DebugStayAwakePreferences(service).enabled)
            assertFalse(DebugStayAwakeService.active.value)
            assertTrue(connectivity.requests.isEmpty())
        }
    }

    @Implements(ConnectivityManager::class)
    class StayAwakeConnectivityShadow : ShadowConnectivityManager() {
        val requests = mutableListOf<NetworkRequest>()
        var denyRequest = false
        var alreadyReleased = false
        var unregisterCalls = 0
        var beforeRequest: (() -> Unit)? = null

        @Implementation
        public override fun requestNetwork(
            request: NetworkRequest,
            callback: ConnectivityManager.NetworkCallback
        ) {
            beforeRequest?.invoke()
            if (denyRequest) throw SecurityException("Wi-Fi request denied")
            requests.add(request)
            super.requestNetwork(request, callback)
        }

        @Implementation
        public override fun unregisterNetworkCallback(callback: ConnectivityManager.NetworkCallback) {
            unregisterCalls++
            super.unregisterNetworkCallback(callback)
            if (alreadyReleased) throw IllegalArgumentException("Request already released")
        }
    }

    private fun start() {
        DebugStayAwakePreferences(service).recordSession(true)
        service.onStartCommand(Intent(service, DebugStayAwakeService::class.java), 0, 1)
    }

    private fun legacyOptIn(value: Boolean) {
        service.getSharedPreferences("debug_stay_awake", Application.MODE_PRIVATE)
            .edit().putBoolean("resume_after_update", value).commit()
    }

    private fun setWirelessDebugging(enabled: Boolean) {
        Settings.Global.putInt(
            RuntimeEnvironment.getApplication().contentResolver,
            "adb_wifi_enabled",
            if (enabled) 1 else 0
        )
    }

    private inline fun <reified T> lock(name: String): T {
        val field = DebugStayAwakeService::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(service) as T
    }
}
