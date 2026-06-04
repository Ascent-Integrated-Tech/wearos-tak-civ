package com.aitech.weartak_civ.integration.plugin

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cep.engine.config.ParserException
import com.aitech.weartak_civ.cep.plugin.PluginTrust
import com.weartak.plugin.sdk.WearTakPluginContract
import com.weartak.plugin.sdk.aidl.IWearTakPlugin
import com.weartak.plugin.sdk.model.PluginInitParcel
import com.weartak.plugin.sdk.v2.WearTakPluginV2Contract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Owns one bound plugin service: binder, death recipient, host stubs, and teardown.
 */
class PluginConnection(
    private val app: WearTakApplication,
    private val ingress: PluginIngressPort,
    private val component: ComponentName,
    private val pluginUid: Int,
    private val manager: PluginManager,
    private val pluginTrust: PluginTrust,
) : ServiceConnection {

    private val pluginPackage: String = component.packageName

    /** Package key for diagnostics when the connection is not yet in [PluginManager]'s map. */
    internal val connectionPackageName: String get() = pluginPackage

    private var pluginBinder: IBinder? = null
    private var plugin: IWearTakPlugin? = null
    private var hostControl: HostControlImpl? = null
    private var hostCallback: HostCallbackImpl? = null

    /**
     * Set when [onServiceConnected] fails before [PluginManager.bind] registers this connection.
     * Prevents a synchronous callback from evicting and then [bind] re-adding the same dead connection.
     */
    internal var suppressPostBindRegistration: Boolean = false
        private set

    private fun failBind(reason: String) {
        suppressPostBindRegistration = true
        manager.onConnectionFailed(this@PluginConnection, reason)
    }

    private val deathRecipient = IBinder.DeathRecipient {
        appScope.launch {
            onBinderDiedInternal()
        }
    }

    private fun onBinderDiedInternal() {
        PluginDiagnostics.recordBinderDeath(pluginPackage)
        manager.onConnectionDied(this)
    }

    /** Test seam: synchronous binder-death path (production uses [binderDeathRecipientForTest]). */
    internal fun triggerBinderDeathSyncForTest() {
        onBinderDiedInternal()
    }

    internal val binderDeathRecipientForTest: IBinder.DeathRecipient get() = deathRecipient

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        if (service == null) {
            PluginDiagnostics.recordBindFailure(component.flattenToString(), null, includeBinderProtocolCategory = false)
            failBind("null_binder")
            return
        }
        val iface = runCatching { IWearTakPlugin.Stub.asInterface(service) }.getOrElse { t ->
            PluginDiagnostics.recordBindFailure(component.flattenToString(), t, includeBinderProtocolCategory = false)
            failBind("asInterface")
            return
        }
        pluginBinder = service
        plugin = iface
        runCatching { service.linkToDeath(deathRecipient, 0) }
            .onFailure { Log.w(TAG, "linkToDeath failed pkg=$pluginPackage", it) }

        val info = runCatching { iface.pluginInfo }.getOrElse { t ->
            Log.e(TAG, "getPluginInfo failed", t)
            failBind("get_plugin_info")
            return
        }
        if (info.packageName != pluginPackage || info.pluginId != pluginPackage) {
            Log.w(
                TAG,
                "plugin info mismatch pkg=$pluginPackage info.pkg=${info.packageName} info.id=${info.pluginId}"
            )
            failBind("plugin_info_mismatch")
            return
        }

        val senderPackages = ingress.packagesForUid(app, pluginUid)
        if (pluginPackage !in senderPackages) {
            failBind("uid_package_mismatch")
            return
        }

        runCatching {
            runBlocking {
                pluginTrust.enforce(pluginPackage, pluginUid, senderPackages)
            }
        }.onFailure { e ->
            val code = (e as? ParserException)?.reasonCode ?: e.javaClass.simpleName
            failBind("trust_failure:$code")
            return
        }

        val api = runCatching { iface.apiVersion }.getOrDefault(-1)
        if (api != WearTakPluginContract.CONTRACT_API_VERSION && api != WearTakPluginV2Contract.CONTRACT_API_VERSION) {
            Log.w(TAG, "plugin apiVersion=$api unsupported pkg=$pluginPackage")
            failBind("api_version_mismatch:$api")
            return
        }

        val control = HostControlImpl(app, ingress, pluginPackage, pluginUid)
        val controlV2 = if (api == WearTakPluginV2Contract.CONTRACT_API_VERSION) {
            HostControlV2Impl(app, ingress, pluginPackage, pluginUid)
        } else {
            null
        }
        val callback = HostCallbackImpl(
            ingress = ingress,
            settingsCoordinator = manager.settingsCoordinator,
            pluginId = pluginPackage,
            pluginUid = pluginUid,
        )
        hostControl = control
        hostCallback = callback

        val init = PluginInitParcel(
            hostPackageName = app.packageName,
            hostUid = android.os.Process.myUid(),
            pluginIdExpected = pluginPackage,
            hostExtras = android.os.Bundle(),
        )
        try {
            iface.initializePluginV2(init, control, controlV2)
            iface.registerHostCallback(callback)
            iface.startPlugin()
            manager.settingsCoordinator.onPluginConnected(pluginPackage, iface)
        } catch (e: RemoteException) {
            Log.e(TAG, "plugin lifecycle failed pkg=$pluginPackage", e)
            failBind("remote_exception")
            return
        }
        PluginDiagnostics.markConnected(pluginPackage)
        Log.i(TAG, "initialized plugin pkg=$pluginPackage api=$api")
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        Log.w(TAG, "onServiceDisconnected pkg=$pluginPackage")
        tearDownBinder(reason = "disconnected")
        PluginDiagnostics.markDisconnected(pluginPackage)
        manager.onConnectionLost(this@PluginConnection)
    }

    fun tearDownBinder(reason: String) {
        Log.i(TAG, "tearDownBinder pkg=$pluginPackage reason=$reason")
        val iface = plugin
        val cb = hostCallback
        val binder = pluginBinder
        runCatching {
            if (iface != null && cb != null) {
                iface.unregisterHostCallback(cb)
            }
        }
        runCatching { iface?.stopPlugin() }
        runCatching { iface?.shutdownPlugin() }
        runCatching {
            if (binder != null) binder.unlinkToDeath(deathRecipient, 0)
        }
        manager.settingsCoordinator.unregisterPlugin(pluginPackage)
        plugin = null
        hostControl = null
        hostCallback = null
        pluginBinder = null
    }

    companion object {
        private const val TAG = "CEP-PluginConnection"
    }
}
