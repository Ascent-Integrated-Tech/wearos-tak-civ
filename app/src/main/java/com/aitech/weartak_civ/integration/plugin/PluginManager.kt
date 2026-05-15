package com.aitech.weartak_civ.integration.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cep.di.CepProvider
import com.aitech.weartak_civ.cep.plugin.PluginTrust
import com.weartak.plugin.sdk.WearTakPluginContract
import java.util.concurrent.ConcurrentHashMap

/**
 * Discovers plugin services, binds with [Context.BIND_AUTO_CREATE], and coordinates
 * [PluginConnection] teardown on death or explicit eviction.
 */
open class PluginManager(
    private val app: WearTakApplication,
    private val ingress: PluginIngressPort,
    private val pluginTrust: PluginTrust = CepProvider.pluginTrust(),
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val connections = ConcurrentHashMap<String, PluginConnection>()

    fun startDeferred() {
        mainHandler.post { refreshBindings() }
    }

    fun refreshBindings() {
        val targets = PluginServiceQuery.discoverPluginComponents(app.packageManager, app.packageName)
        PluginDiagnostics.markDiscovered(targets.map { it.packageName })
        val targetPkgs = targets.map { it.packageName }.toSet()
        for (existing in connections.keys.toList()) {
            if (existing !in targetPkgs) {
                unbindPackage(existing)
            }
        }
        for (cn in targets) {
            if (connections.containsKey(cn.packageName)) continue
            bind(cn)
        }
    }

    fun unbindPackage(packageName: String) {
        val c = connections.remove(packageName) ?: return
        runCatching { app.unbindService(c) }
        c.tearDownBinder(reason = "unbindPackage")
        PluginDiagnostics.markUnbound(packageName)
    }

    fun shutdownAll() {
        for (pkg in connections.keys.toList()) {
            unbindPackage(pkg)
        }
    }

    internal open fun onConnectionDied(conn: PluginConnection) {
        val entry = connections.entries.firstOrNull { it.value === conn } ?: return
        unbindPackage(entry.key)
    }

    internal open fun onConnectionLost(conn: PluginConnection) {
        val entry = connections.entries.firstOrNull { it.value === conn } ?: return
        connections.remove(entry.key)
        conn.tearDownBinder(reason = "service_disconnected")
        PluginDiagnostics.markDisconnected(entry.key)
    }

    internal open fun onConnectionFailed(conn: PluginConnection, reason: String) {
        Log.w(TAG, "connection failed: $reason")
        val entry = connections.entries.firstOrNull { it.value === conn }
        val pkg = entry?.key ?: conn.connectionPackageName
        if (entry != null) {
            connections.remove(entry.key)
        }
        conn.tearDownBinder(reason = reason)
        runCatching { app.unbindService(conn) }
        PluginDiagnostics.recordAdmissionRejected(
            pkg,
            PluginDiagnostics.admissionCategoryForAidlBindFailure(reason),
            reason,
        )
    }

    private fun bind(cn: ComponentName) {
        val uid = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.packageManager.getPackageUid(
                    cn.packageName,
                    PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                app.packageManager.getApplicationInfo(cn.packageName, 0).uid
            }
        }.getOrElse {
            PluginDiagnostics.recordBindFailure(cn.flattenToString(), it)
            return
        }
        val conn = PluginConnection(app, ingress, cn, uid, this, pluginTrust)
        val intent = Intent(WearTakPluginContract.ACTION_PLUGIN_SERVICE).setComponent(cn)
        val ok = runCatching {
            app.bindService(
                intent,
                conn,
                Context.BIND_AUTO_CREATE
            )
        }.getOrElse {
            PluginDiagnostics.recordBindFailure(cn.flattenToString(), it)
            false
        }
        if (ok) {
            if (conn.suppressPostBindRegistration) return
            connections[cn.packageName] = conn
            PluginDiagnostics.markBound(cn.packageName)
        } else {
            PluginDiagnostics.recordBindFailure(cn.flattenToString(), null)
        }
    }

    internal fun discoverPluginComponents(): List<ComponentName> =
        PluginServiceQuery.discoverPluginComponents(app.packageManager, app.packageName)

    internal fun activeConnectionCountForTest(): Int = connections.size

    internal fun hasConnectionForTest(packageName: String): Boolean =
        connections.containsKey(packageName)

    internal fun connectionForTest(packageName: String): PluginConnection? =
        connections[packageName]

    companion object {
        private const val TAG = "CEP-PluginManager"
    }
}
