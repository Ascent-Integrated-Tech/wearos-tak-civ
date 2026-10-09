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
    fun nullRestartReleasesExistingLocks() {
        start()
        val screen = lock<PowerManager.WakeLock>("screenLock")
        val cpu = lock<PowerManager.WakeLock>("cpuLock")
        val wifi = lock<WifiManager.WifiLock>("wifiLock")
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 2))
        assertFalse(screen.isHeld)
        assertFalse(cpu.isHeld)
        assertFalse(wifi.isHeld)
        assertFalse(DebugStayAwakeService.active.value)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(connectivity.networkCallbacks.isEmpty())
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
        DebugStayAwakePreferences(service).resumeAfterUpdate = true
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
        assertFalse(DebugStayAwakePreferences(service).shouldResumeAfterUpdate())
    }

    @Test
    fun optedInSessionResumesAfterPackageReplacement() {
        val preferences = DebugStayAwakePreferences(service)
        preferences.resumeAfterUpdate = true
        start()
        service.onDestroy()
        assertFalse(DebugStayAwakeService.active.value)
        assertTrue(DebugStayAwakePreferences(service).shouldResumeAfterUpdate())

        DebugStayAwakeUpdateReceiver().onReceive(service, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        val restart = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals(DebugStayAwakeService::class.java.name, restart.component?.className)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(restart, 0, 2))
        assertTrue(DebugStayAwakeService.active.value)
    }

    @Test
    fun updateDoesNotResumeWithoutOptInOrWithoutEnabledSession() {
        start()
        DebugStayAwakeUpdateReceiver().onReceive(service, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(null, shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        service.onStartCommand(Intent().setAction(DebugStayAwakeService.ACTION_STOP), 0, 2)
        DebugStayAwakePreferences(service).resumeAfterUpdate = true
        DebugStayAwakeUpdateReceiver().onReceive(service, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(null, shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    @Test
    fun explicitSwitchStopAndNotificationStopPreventUpdateResume() {
        val preferences = DebugStayAwakePreferences(service)
        preferences.resumeAfterUpdate = true
        start()
        DebugStayAwakeService.stop(service)
        assertFalse(preferences.shouldResumeAfterUpdate())
        start()
        service.onStartCommand(Intent().setAction(DebugStayAwakeService.ACTION_STOP), 0, 2)
        assertFalse(preferences.shouldResumeAfterUpdate())
        assertTrue(preferences.resumeAfterUpdate)
    }

    @Test
    fun uncheckingUpdateResumePreventsRestoration() {
        val preferences = DebugStayAwakePreferences(service)
        preferences.resumeAfterUpdate = true
        start()
        preferences.resumeAfterUpdate = false
        DebugStayAwakeUpdateReceiver().onReceive(service, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(null, shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        assertTrue(DebugStayAwakeService.active.value)
    }

    @Test
    fun rebootAndOtherBroadcastsDoNotRestoreRememberedSession() {
        val preferences = DebugStayAwakePreferences(service)
        preferences.resumeAfterUpdate = true
        start()
        DebugStayAwakeUpdateReceiver().onReceive(service, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(null, shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        Settings.Global.putInt(service.contentResolver, Settings.Global.BOOT_COUNT, 2)
        DebugStayAwakeUpdateReceiver().onReceive(service, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(null, shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        assertFalse(preferences.shouldResumeAfterUpdate())
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
        assertEquals(Service.START_NOT_STICKY,
            service.onStartCommand(Intent(service, DebugStayAwakeService::class.java), 0, 1))
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
