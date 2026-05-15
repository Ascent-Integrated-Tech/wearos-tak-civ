package com.aitech.weartak_civ.integration.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aitech.weartak_civ.WearTakApplication

/**
 * Plugin install/update hook.
 *
 * The host performs an initial scan at app startup; this receiver makes newly installed or
 * updated plugins visible without requiring a full host restart.
 */
class PluginPackageChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_PACKAGE_ADDED && action != Intent.ACTION_PACKAGE_REPLACED) {
            return
        }
        if (action == Intent.ACTION_PACKAGE_ADDED && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
            return
        }
        val pkg = intent.data?.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: return
        Log.i(TAG, "package changed: $pkg — refreshing plugin bindings")
        val app = context.applicationContext as? WearTakApplication ?: return
        app.pluginManager.refreshBindings()
    }

    companion object {
        private const val TAG = "CEP-PluginPkgChanged"
    }
}
