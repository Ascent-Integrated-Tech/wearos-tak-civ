package com.aitech.weartak_civ.integration.plugin

import android.content.Context
import com.aitech.weartak_civ.WearTakApplication

/**
 * Resolves the active [PluginIngressPort] for legacy adapters and workers.
 *
 * Unit tests may set [testOverride] to assert delegation without booting a full host graph.
 * Production always uses [WearTakApplication.pluginIngress] when [testOverride] is null.
 */
object PluginIngressLocator {
    @Volatile
    internal var testOverride: PluginIngressPort? = null

    fun resolve(context: Context): PluginIngressPort? =
        testOverride ?: (context.applicationContext as? WearTakApplication)?.pluginIngress

    internal fun clearTestOverride() {
        testOverride = null
    }
}
