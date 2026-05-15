package com.aitech.weartak_civ.integration.plugin

import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cep.engine.config.ParserException
import com.aitech.weartak_civ.cep.engine.config.ReasonCodes
import com.aitech.weartak_civ.cep.plugin.PluginTrust
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class PluginConnectionBindTrustTest {

    private val pluginPkg = "com.trust.fail.plugin"
    private val hostPkg = "com.aitech.weartak_civ"
    private val pluginUid = 10_001

    @Before
    fun setup() {
        PluginDiagnostics.resetForTest()
    }

    private fun resolveInfoFor(pkg: String, className: String = ".Svc"): ResolveInfo {
        val si = ServiceInfo().apply {
            packageName = pkg
            name = className
        }
        return ResolveInfo().apply { serviceInfo = si }
    }

    private fun mockHostApp(
        resolveList: List<ResolveInfo>,
        onBind: (Intent, ServiceConnection) -> Unit,
    ): WearTakApplication {
        val pm = mockk<PackageManager>(relaxed = true)
        @Suppress("DEPRECATION")
        every { pm.queryIntentServices(any<Intent>(), any<Int>()) } returns resolveList
        @Suppress("DEPRECATION")
        every { pm.getApplicationInfo(any<String>(), any<Int>()) } returns ApplicationInfo().apply {
            uid = pluginUid
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
    fun bind_rejectsWhenPluginTrustThrows() {
        val ri = resolveInfoFor(pluginPkg)
        val app = mockHostApp(listOf(ri)) { intent, conn ->
            conn.onServiceConnected(intent.component!!, FakeWearTakPlugin(pluginPkg).asBinder())
        }
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        every { ingress.packagesForUid(any(), any()) } returns setOf(pluginPkg)
        val trust = mockk<PluginTrust>()
        coEvery {
            trust.enforce(pluginPkg, pluginUid, setOf(pluginPkg))
        } throws ParserException(ReasonCodes.PLUGIN_CERT_CHANGED, "cert pin failed")
        val mgr = PluginManager(app, ingress, trust)
        mgr.refreshBindings()
        assertFalse(mgr.hasConnectionForTest(pluginPkg))
        val c = PluginDiagnostics.snapshot().categoryCounts
        assertTrue((c[PluginDiagnostics.AdmissionCategory.TRUST_FAILURE] ?: 0) >= 1)
    }

    @Test
    fun bind_rejectsWhenSenderPackagesDoNotOwnPluginId() {
        val ri = resolveInfoFor(pluginPkg)
        val app = mockHostApp(listOf(ri)) { intent, conn ->
            conn.onServiceConnected(intent.component!!, FakeWearTakPlugin(pluginPkg).asBinder())
        }
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        every { ingress.packagesForUid(any(), any()) } returns setOf("com.other.app")
        val trust = mockk<PluginTrust>(relaxed = true)
        val mgr = PluginManager(app, ingress, trust)
        mgr.refreshBindings()
        assertFalse(mgr.hasConnectionForTest(pluginPkg))
        val c = PluginDiagnostics.snapshot().categoryCounts
        assertTrue((c[PluginDiagnostics.AdmissionCategory.UID_PACKAGE_MISMATCH] ?: 0) >= 1)
    }
}
