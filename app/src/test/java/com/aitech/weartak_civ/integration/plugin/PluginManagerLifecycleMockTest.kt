package com.aitech.weartak_civ.integration.plugin

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cep.plugin.PluginTrust
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bind / unbind / discovery orchestration for [PluginManager] without a real plugin APK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class PluginManagerLifecycleMockTest {

    private val pluginPkg = "com.weartak.fake.plugin"
    private val hostPkg = "com.aitech.weartak_civ"

    @Before
    fun setup() {
        PluginDiagnostics.resetForTest()
        PluginIngressLocator.clearTestOverride()
    }

    @After
    fun tearDown() {
        PluginIngressLocator.clearTestOverride()
    }

    private fun noopPluginTrust(): PluginTrust = mockk(relaxed = true)

    private fun stubIngressSenderPackages(ingress: PluginIngressPort, pkgs: Set<String>) {
        every { ingress.packagesForUid(any(), any()) } returns pkgs
    }

    private fun resolveInfoFor(
        pkg: String,
        className: String,
        permission: String? = com.weartak.plugin.sdk.WearTakPluginContract.PERMISSION_BIND_PLUGIN,
    ): ResolveInfo {
        val si = ServiceInfo().apply {
            packageName = pkg
            name = className
            this.permission = permission
        }
        return ResolveInfo().apply { serviceInfo = si }
    }

    private fun mockHostApp(
        resolveList: List<ResolveInfo>,
        onBind: (Intent, ServiceConnection) -> Unit = { _, _ -> },
    ): WearTakApplication {
        val pm = mockk<PackageManager>(relaxed = true)
        @Suppress("DEPRECATION")
        every { pm.queryIntentServices(any<Intent>(), any<Int>()) } returns resolveList
        @Suppress("DEPRECATION")
        every { pm.getApplicationInfo(any<String>(), any<Int>()) } returns ApplicationInfo().apply {
            uid = 10_001
        }

        val app = mockk<WearTakApplication>(relaxed = true)
        every { app.packageName } returns hostPkg
        every { app.packageManager } returns pm
        every {
            app.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>())
        } answers {
            val intent = invocation.args[0] as Intent
            val conn = invocation.args[1] as ServiceConnection
            onBind(intent, conn)
            true
        }
        every { app.unbindService(any<ServiceConnection>()) } answers { }
        return app
    }

    @Test
    fun refreshBindings_bindsDiscoveredPluginsOnce() {
        val ri = resolveInfoFor(pluginPkg, ".FakeSvc")
        val captured = mutableListOf<ServiceConnection>()
        val app = mockHostApp(listOf(ri)) { intent, conn ->
            captured.add(conn)
            val cn = intent.component!!
            conn.onServiceConnected(cn, FakeWearTakPlugin(pluginPkg).asBinder())
        }
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        stubIngressSenderPackages(ingress, setOf(pluginPkg))
        val mgr = PluginManager(app, ingress, noopPluginTrust())
        mgr.refreshBindings()
        mgr.refreshBindings()
        assertTrue(mgr.hasConnectionForTest(pluginPkg))
        assertEquals(1, mgr.activeConnectionCountForTest())
        assertEquals(1, captured.size)
        val snap = PluginDiagnostics.snapshot()
        assertTrue(snap.discovered.contains(pluginPkg))
        assertTrue(snap.bound.contains(pluginPkg))
        assertTrue(snap.connected.contains(pluginPkg))
    }

    @Test
    fun unbindPackage_removesConnectionAndRecordsUnbind() {
        val ri = resolveInfoFor(pluginPkg, ".FakeSvc")
        val app = mockHostApp(listOf(ri)) { intent, conn ->
            conn.onServiceConnected(intent.component!!, FakeWearTakPlugin(pluginPkg).asBinder())
        }
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        stubIngressSenderPackages(ingress, setOf(pluginPkg))
        val mgr = PluginManager(app, ingress, noopPluginTrust())
        mgr.refreshBindings()
        assertTrue(mgr.hasConnectionForTest(pluginPkg))
        mgr.unbindPackage(pluginPkg)
        assertFalse(mgr.hasConnectionForTest(pluginPkg))
        assertTrue(PluginDiagnostics.snapshot().unbindEvents >= 1)
        verify(atLeast = 1) { app.unbindService(any<ServiceConnection>()) }
    }

    @Test
    fun onServiceDisconnected_clearsConnectionFromManager() {
        val ri = resolveInfoFor(pluginPkg, ".FakeSvc")
        lateinit var sc: ServiceConnection
        val app = mockHostApp(listOf(ri)) { intent, conn ->
            sc = conn
            conn.onServiceConnected(intent.component!!, FakeWearTakPlugin(pluginPkg).asBinder())
        }
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        stubIngressSenderPackages(ingress, setOf(pluginPkg))
        val mgr = PluginManager(app, ingress, noopPluginTrust())
        mgr.refreshBindings()
        assertTrue(mgr.hasConnectionForTest(pluginPkg))
        sc.onServiceDisconnected(ComponentName(pluginPkg, ".FakeSvc"))
        assertFalse(mgr.hasConnectionForTest(pluginPkg))
        assertTrue(
            (PluginDiagnostics.snapshot().categoryCounts[PluginDiagnostics.AdmissionCategory.LIFECYCLE_DISCONNECT]
                ?: 0) >= 1
        )
    }

    @Test
    fun onConnectionFailed_fromNullBinder_recordsDiagnosticsAndEvicts() {
        val ri = resolveInfoFor(pluginPkg, ".FakeSvc")
        val app = mockHostApp(listOf(ri)) { intent, conn ->
            conn.onServiceConnected(intent.component!!, null)
        }
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        stubIngressSenderPackages(ingress, setOf(pluginPkg))
        val mgr = PluginManager(app, ingress, noopPluginTrust())
        mgr.refreshBindings()
        assertFalse(mgr.hasConnectionForTest(pluginPkg))
        assertTrue(PluginDiagnostics.snapshot().bindFailures >= 1)
        assertTrue(PluginDiagnostics.snapshot().deniedAdmission >= 1)
        assertEquals(
            1,
            PluginDiagnostics.snapshot().categoryCounts[PluginDiagnostics.AdmissionCategory.BINDER_PROTOCOL] ?: 0
        )
    }

    @Test
    fun shutdownAll_unbindsEveryActiveConnection() {
        val r1 = resolveInfoFor("com.plugin.one", ".A")
        val r2 = resolveInfoFor("com.plugin.two", ".B")
        val app = mockHostApp(listOf(r1, r2)) { intent, conn ->
            val pkg = intent.component!!.packageName
            conn.onServiceConnected(intent.component!!, FakeWearTakPlugin(pkg).asBinder())
        }
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        stubIngressSenderPackages(ingress, setOf("com.plugin.one", "com.plugin.two"))
        val mgr = PluginManager(app, ingress, noopPluginTrust())
        mgr.refreshBindings()
        assertEquals(2, mgr.activeConnectionCountForTest())
        mgr.shutdownAll()
        assertEquals(0, mgr.activeConnectionCountForTest())
        verify(atLeast = 2) { app.unbindService(any<ServiceConnection>()) }
    }
}
