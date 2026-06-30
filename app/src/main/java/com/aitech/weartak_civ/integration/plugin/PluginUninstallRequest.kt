package com.aitech.weartak_civ.integration.plugin

/**
 * Result of asking the host repository to prepare an uninstall.
 *
 * This is a request-target handoff, not the final uninstall completion state.
 * The UI layer can use [Ready.packageName] to launch the OS uninstall flow.
 */
sealed class PluginUninstallRequest {
    data class Ready(
        val packageName: String,
    ) : PluginUninstallRequest()

    data class Failure(
        val pluginId: String,
        val reason: String,
    ) : PluginUninstallRequest()
}
