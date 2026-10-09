package com.aitech.weartak_civ.integration.plugin

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import com.weartak.plugin.sdk.WearTakPluginContract
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginServiceQueryTest {

    private fun packageManager(vararg services: ResolveInfo): PackageManager = mockk {
        every {
            queryIntentServices(
                match<Intent> { it.action == WearTakPluginContract.ACTION_PLUGIN_SERVICE },
                any<PackageManager.ResolveInfoFlags>()
            )
        } returns services.toList()
    }

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
    fun discover_includesMatchingService() {
        val pluginPkg = "com.weartak.plugin.sample"
        val ri = resolveInfo(pluginPkg, ".SamplePluginService")
        val hostPkg = "com.host.app"
        val found = PluginServiceQuery.discoverPluginComponents(packageManager(ri), hostPkg)
        assertTrue(found.any { it.packageName == pluginPkg })
    }

    @Test
    fun discover_excludesHostPackage() {
        val hostPkg = "com.host.app"
        val ri = resolveInfo(hostPkg, ".EchoService")
        val found = PluginServiceQuery.discoverPluginComponents(packageManager(ri), hostPkg)
        assertEquals(0, found.size)
    }

    @Test
    fun discover_excludesServicesWithoutBindPermission() {
        val pluginPkg = "com.weartak.plugin.noperm"
        val ri = resolveInfo(pluginPkg, ".NoPermService", permission = null)
        val found = PluginServiceQuery.discoverPluginComponents(packageManager(ri), "com.host.app")
        assertTrue(found.none { it.packageName == pluginPkg })
    }
}
