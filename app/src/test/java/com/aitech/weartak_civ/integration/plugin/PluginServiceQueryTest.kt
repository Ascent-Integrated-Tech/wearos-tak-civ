package com.aitech.weartak_civ.integration.plugin

import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import com.weartak.plugin.sdk.WearTakPluginContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class PluginServiceQueryTest {

    @Test
    fun discover_includesShadowResolve() {
        val app = RuntimeEnvironment.getApplication()
        val pluginPkg = "com.weartak.plugin.sample"
        val svc = ServiceInfo().apply {
            packageName = pluginPkg
            name = ".SamplePluginService"
        }
        val ri = ResolveInfo().apply { serviceInfo = svc }
        Shadows.shadowOf(app.packageManager).addResolveInfoForIntent(
            Intent(WearTakPluginContract.ACTION_PLUGIN_SERVICE),
            ri
        )
        val hostPkg = "com.host.app"
        val found = PluginServiceQuery.discoverPluginComponents(app.packageManager, hostPkg)
        assertTrue(found.any { it.packageName == pluginPkg })
    }

    @Test
    fun discover_excludesHostPackage() {
        val app = RuntimeEnvironment.getApplication()
        val hostPkg = "com.host.app"
        val svc = ServiceInfo().apply {
            packageName = hostPkg
            name = ".EchoService"
        }
        val ri = ResolveInfo().apply { serviceInfo = svc }
        Shadows.shadowOf(app.packageManager).addResolveInfoForIntent(
            Intent(WearTakPluginContract.ACTION_PLUGIN_SERVICE),
            ri
        )
        val found = PluginServiceQuery.discoverPluginComponents(app.packageManager, hostPkg)
        assertEquals(0, found.size)
    }
}
