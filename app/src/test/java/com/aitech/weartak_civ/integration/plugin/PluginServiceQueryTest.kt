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

    private fun resolveInfo(
        packageName: String,
        className: String,
        permission: String? = WearTakPluginContract.PERMISSION_BIND_PLUGIN,
    ): ResolveInfo {
        val svc = ServiceInfo().apply {
            this.packageName = packageName
            name = className
            this.permission = permission
        }
        return ResolveInfo().apply { serviceInfo = svc }
    }

    @Test
    fun discover_includesShadowResolve() {
        val app = RuntimeEnvironment.getApplication()
        val pluginPkg = "com.weartak.plugin.sample"
        val ri = resolveInfo(pluginPkg, ".SamplePluginService")
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
        val ri = resolveInfo(hostPkg, ".EchoService")
        Shadows.shadowOf(app.packageManager).addResolveInfoForIntent(
            Intent(WearTakPluginContract.ACTION_PLUGIN_SERVICE),
            ri
        )
        val found = PluginServiceQuery.discoverPluginComponents(app.packageManager, hostPkg)
        assertEquals(0, found.size)
    }

    @Test
    fun discover_excludesServicesWithoutBindPermission() {
        val app = RuntimeEnvironment.getApplication()
        val pluginPkg = "com.weartak.plugin.noperm"
        val ri = resolveInfo(pluginPkg, ".NoPermService", permission = null)
        Shadows.shadowOf(app.packageManager).addResolveInfoForIntent(
            Intent(WearTakPluginContract.ACTION_PLUGIN_SERVICE),
            ri
        )
        val found = PluginServiceQuery.discoverPluginComponents(app.packageManager, "com.host.app")
        assertTrue(found.none { it.packageName == pluginPkg })
    }
}
