package com.aitech.weartak_civ.integration.plugin

sealed class PluginRepositoryOutcome {
    data class Success(
        val action: String,
        val pluginId: String,
        val packageName: String,
    ) : PluginRepositoryOutcome()

    data class Failure(
        val action: String,
        val pluginId: String,
        val reason: String,
    ) : PluginRepositoryOutcome()
}
