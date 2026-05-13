package com.aitech.weartak_civ.integration.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aitech.weartak_civ.cep.di.CepProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Plugin uninstall-time eviction (design §540 — "package uninstall" trigger). On a fully
 * removed package, evict the corresponding plugin overlay (pluginId == packageName in v1)
 * and trigger a recompose so alert hygiene runs over the contributed rules — mirroring the
 * managed apply path used for definitional rule removals.
 *
 * Replacement-on-update (`EXTRA_REPLACING == true`) is ignored: the new install will
 * resubmit its overlay during normal handshake and lease renewal.
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
        scope.launch {
            runCatching { CepProvider.evictPluginByPackage(pkg) }
                .onFailure { Log.w(TAG, "evictPluginByPackage($pkg) failed", it) }
        }
    }

    companion object {
        private const val TAG = "CEP-PluginPkgRemoved"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
