package com.aitech.weartak_civ.integration.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
/**
 * Plugin uninstall-time eviction (design §540). Delegates to [PluginIngressPort] so the same
 * eviction path can be reused from [PluginManager] during package reconciliation later.
 */
class PluginPackageRemovedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_PACKAGE_FULLY_REMOVED && action != Intent.ACTION_PACKAGE_REMOVED) {
            return
        }
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
        val pkg = intent.data?.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: return
        Log.i(TAG, "package removed: $pkg — evicting plugin overlay if present")
        val ingress = PluginIngressLocator.resolve(context) ?: return
        ingress.onPackageFullyRemoved(pkg)
    }

    companion object {
        private const val TAG = "CEP-PluginPkgRemoved"
    }
}
