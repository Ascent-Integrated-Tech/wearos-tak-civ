package com.aitech.weartak_civ.integration.plugin

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.aitech.weartak_civ.WearTakApplication

class PluginViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository: PluginRepository =
        (application as WearTakApplication).pluginRepository

    val plugins = repository.plugins
    val selectedPlugin = repository.selectedPlugin
    val operationOutcome = repository.operationOutcome

    fun refresh() = repository.refresh()
    fun refreshBindings() = repository.refreshBindings()
    fun addPlugin(pluginId: String) = repository.addPlugin(pluginId)
    fun select(pluginId: String?) = repository.select(pluginId)
    fun loadPlugin(pluginId: String) = repository.loadPlugin(pluginId)
    fun unloadPlugin(pluginId: String) = repository.unloadPlugin(pluginId)
    fun removePlugin(pluginId: String) = repository.removePlugin(pluginId)
    fun uninstallPlugin(pluginId: String): PluginUninstallRequest = repository.uninstallPlugin(pluginId)
}
