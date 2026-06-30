package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.weartak.plugin.sdk.aidl.IWearTakPlugin
import com.weartak.plugin.sdk.model.PluginCapabilitiesParcel
import com.weartak.plugin.sdk.model.SettingsApplyMode
import com.weartak.plugin.sdk.model.SettingsApplyResultParcel
import com.weartak.plugin.sdk.model.SettingsSchemaParcel
import com.weartak.plugin.sdk.validation.ContractValidationResult
import com.weartak.plugin.sdk.validation.SettingsApplyValidator
import com.weartak.plugin.sdk.validation.SettingsSchemaContractValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Host-side settings cache, validation, and bidirectional sync with bound plugins.
 */
class PluginSettingsCoordinator(
    private val app: WearTakApplication,
    private val snapshotBuilder: HostSettingsSnapshotBuilder = HostSettingsSnapshotBuilder(app),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val cache = ConcurrentHashMap<String, PluginSettingsState>()
    private val pluginRefs = ConcurrentHashMap<String, IWearTakPlugin>()
    private val syncMutex = Mutex()
    private var hostWatcherStarted = false

    fun registerPlugin(pluginId: String, plugin: IWearTakPlugin) {
        pluginRefs[pluginId] = plugin
    }

    fun unregisterPlugin(pluginId: String) {
        pluginRefs.remove(pluginId)
        cache.remove(pluginId)
    }

    fun getState(pluginId: String): PluginSettingsState? = cache[pluginId]

    fun startHostSettingsWatcher() {
        if (hostWatcherStarted) return
        hostWatcherStarted = true
        val repo = app.repo
        scope.launch {
            val baseWatch = combine(
                repo.getCallsign,
                repo.getTeam,
                repo.getRole,
                repo.reportingUpdateIntervalSecs,
                repo.sitxStatus,
            ) { callsign, team, role, interval, sitx ->
                HostWatchKey(callsign, team, role, interval, sitx, false)
            }
            combine(
                baseWatch,
                repo.getEnableTakEndpoint,
            ) { base, takEnabled ->
                base.copy(takConnected = takEnabled)
            }
                .distinctUntilChanged()
                .collect {
                    pushHostSettingsToAllPlugins()
                }
        }
    }

    /**
     * Bind-time initialization: capabilities, schema/values, host snapshot push.
     */
    fun onPluginConnected(pluginId: String, plugin: IWearTakPlugin) {
        registerPlugin(pluginId, plugin)
        startHostSettingsWatcher()
        runBlocking {
            syncMutex.withLock {
                refreshFromPluginLocked(pluginId, plugin)
                pushHostSettingsToPluginLocked(pluginId, plugin)
            }
        }
    }

    fun onPluginSettingsSchemaChanged(pluginId: String) {
        val plugin = pluginRefs[pluginId] ?: return
        scope.launch {
            syncMutex.withLock { refreshFromPluginLocked(pluginId, plugin) }
        }
    }

    fun onPluginSettingsValuesChanged(pluginId: String) {
        val plugin = pluginRefs[pluginId] ?: return
        scope.launch {
            syncMutex.withLock { refreshValuesFromPluginLocked(pluginId, plugin) }
        }
    }

    fun pushHostSettingsToAllPlugins() {
        scope.launch {
            syncMutex.withLock {
                val snapshot = snapshotBuilder.build()
                for ((pluginId, plugin) in pluginRefs) {
                    pushHostSettingsSnapshotLocked(pluginId, plugin, snapshot)
                }
            }
        }
    }

    /**
     * Host-initiated apply (partial patch by default). Validates locally then calls plugin binder.
     */
    fun applyPluginSettings(
        pluginId: String,
        incoming: Bundle,
        applyMode: Int = SettingsApplyMode.PARTIAL_PATCH,
    ): SettingsApplyResultParcel {
        val plugin = pluginRefs[pluginId]
            ?: return SettingsApplyResultParcel(
                success = false,
                applyMode = applyMode,
                errors = listOf(
                    com.weartak.plugin.sdk.model.SettingsValidationErrorParcel(
                        code = "plugin.not_bound",
                        message = "Plugin not connected",
                    ),
                ),
            )
        val state = cache[pluginId]
            ?: return SettingsApplyResultParcel(
                success = false,
                applyMode = applyMode,
                errors = listOf(
                    com.weartak.plugin.sdk.model.SettingsValidationErrorParcel(
                        code = "settings.not_loaded",
                        message = "Settings cache empty",
                    ),
                ),
            )
        if (!state.settingsSupported) {
            return SettingsApplyResultParcel(
                success = false,
                applyMode = applyMode,
                errors = listOf(
                    com.weartak.plugin.sdk.model.SettingsValidationErrorParcel(
                        code = "settings.unsupported",
                        message = "Plugin does not expose settings",
                    ),
                ),
            )
        }
        val local = SettingsApplyValidator.applySettingsWithResult(
            schema = state.schema,
            previous = state.values,
            incoming = incoming,
            hostSettings = state.hostSettings,
            applyMode = applyMode,
        )
        if (!local.success) {
            updateCache(pluginId, state.copy(lastApplyResult = local, lastValidationErrors = local.errors))
            PluginDiagnostics.recordSettingsApply(pluginId, local)
            return local
        }
        val remote = invokePluginApply(plugin, incoming, applyMode)
        if (remote.success) {
            updateCache(
                pluginId,
                state.copy(
                    values = Bundle(remote.values),
                    lastApplyResult = remote,
                    lastValidationErrors = emptyList(),
                    lastSyncedAtMillis = System.currentTimeMillis(),
                ),
            )
        } else {
            updateCache(pluginId, state.copy(lastApplyResult = remote, lastValidationErrors = remote.errors))
        }
        PluginDiagnostics.recordSettingsApply(pluginId, remote)
        return remote
    }

    private suspend fun refreshFromPluginLocked(pluginId: String, plugin: IWearTakPlugin) {
        val caps = runCatching { plugin.capabilities }.getOrDefault(PluginCapabilitiesParcel())
        val settingsSupported = caps.hostedSettingsUi || caps.externalSettingsActivity
        var schema = SettingsSchemaParcel()
        var values = Bundle()
        var errors = emptyList<com.weartak.plugin.sdk.model.SettingsValidationErrorParcel>()
        if (settingsSupported) {
            val rawSchema = runCatching { plugin.settingsSchema }.getOrDefault(SettingsSchemaParcel())
            when (val v = SettingsSchemaContractValidator.validate(rawSchema)) {
                is ContractValidationResult.Ok -> schema = v.value
                is ContractValidationResult.Err -> {
                    errors = v.issues.map {
                        com.weartak.plugin.sdk.model.SettingsValidationErrorParcel(
                            code = it.code,
                            message = it.message,
                        )
                    }
                    Log.w(TAG, "settings schema validation failed pluginId=$pluginId errors=$errors")
                }
            }
            values = runCatching { plugin.settingsValues }.getOrDefault(Bundle())
        }
        val prior = cache[pluginId]
        val host = prior?.hostSettings ?: snapshotBuilder.build()
        updateCache(
            pluginId,
            PluginSettingsState(
                pluginId = pluginId,
                capabilities = caps,
                schema = schema,
                values = values,
                hostSettings = host,
                hostSettingsRevision = host.revision,
                lastValidationErrors = errors,
                settingsSupported = settingsSupported,
                lastSyncedAtMillis = System.currentTimeMillis(),
            ),
        )
        PluginDiagnostics.recordSettingsCache(pluginId, cache[pluginId])
        Log.i(
            TAG,
            "settings cache refreshed pluginId=$pluginId supported=$settingsSupported " +
                "hostedUi=${caps.hostedSettingsUi} external=${caps.externalSettingsActivity} fields=${schema.fields.size}",
        )
    }

    private suspend fun refreshValuesFromPluginLocked(pluginId: String, plugin: IWearTakPlugin) {
        val prior = cache[pluginId] ?: return
        if (!prior.settingsSupported) return
        val values = runCatching { plugin.settingsValues }.getOrDefault(Bundle())
        updateCache(
            pluginId,
            prior.copy(
                values = values,
                lastSyncedAtMillis = System.currentTimeMillis(),
            ),
        )
        PluginDiagnostics.recordSettingsCache(pluginId, cache[pluginId])
    }

    private suspend fun pushHostSettingsToPluginLocked(pluginId: String, plugin: IWearTakPlugin) {
        val snapshot = snapshotBuilder.build()
        pushHostSettingsSnapshotLocked(pluginId, plugin, snapshot)
    }

    private fun pushHostSettingsSnapshotLocked(
        pluginId: String,
        plugin: IWearTakPlugin,
        snapshot: com.weartak.plugin.sdk.model.HostSettingsParcel,
    ) {
        val prior = cache[pluginId]
        if (prior != null) {
            updateCache(
                pluginId,
                prior.copy(
                    hostSettings = snapshot,
                    hostSettingsRevision = snapshot.revision,
                ),
            )
        }
        runCatching { plugin.onHostSettingsChanged(snapshot) }
            .onFailure { Log.d(TAG, "onHostSettingsChanged not available pluginId=$pluginId", it) }
        PluginDiagnostics.recordHostSettingsPush(pluginId, snapshot)
        if (prior?.settingsSupported == true && prior.schema.fields.any { it.hostSettingKey != null }) {
            val recompute = SettingsApplyValidator.applySettingsWithResult(
                schema = prior.schema,
                previous = prior.values,
                incoming = Bundle(),
                hostSettings = snapshot,
                applyMode = SettingsApplyMode.HOST_DRIVEN_RECOMPUTE,
            )
            if (recompute.success && recompute.anyValueChanged) {
                invokePluginApply(plugin, Bundle(), SettingsApplyMode.HOST_DRIVEN_RECOMPUTE)
                updateCache(
                    pluginId,
                    prior.copy(
                        values = Bundle(recompute.values),
                        hostSettings = snapshot,
                        hostSettingsRevision = snapshot.revision,
                        lastApplyResult = recompute,
                        lastSyncedAtMillis = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private fun invokePluginApply(
        plugin: IWearTakPlugin,
        values: Bundle,
        applyMode: Int,
    ): SettingsApplyResultParcel {
        return runCatching { plugin.applySettingsWithResult(values, applyMode) }
            .getOrElse { remoteFailure ->
                Log.d(TAG, "applySettingsWithResult unavailable, using legacy apply", remoteFailure)
                return runCatching {
                    plugin.applySettings(values)
                    SettingsApplyResultParcel(
                        success = true,
                        applyMode = applyMode,
                        values = Bundle(values),
                        anyValueChanged = true,
                    )
                }.getOrElse {
                    SettingsApplyResultParcel(
                        success = false,
                        applyMode = applyMode,
                        errors = listOf(
                            com.weartak.plugin.sdk.model.SettingsValidationErrorParcel(
                                code = "binder.remote",
                                message = it.message ?: "apply failed",
                            ),
                        ),
                    )
                }
            }
    }

    private fun updateCache(pluginId: String, state: PluginSettingsState) {
        cache[pluginId] = state
    }

    private data class HostWatchKey(
        val callsign: String,
        val team: String,
        val role: String,
        val reportingInterval: Int,
        val sitx: DataRepository.SitxServerStatus,
        val takConnected: Boolean,
    )

    companion object {
        private const val TAG = "CEP-PluginSettings"
    }
}
