package com.aitech.weartak_civ.presentation

import com.aitech.weartak_civ.integration.plugin.PluginUI

internal enum class BuiltInPlugin {
    COMMANDS,
    DITTO,
    EVERYWHERE_COMMS,
    GOTENNA,
    SOMEWEAR_LABS,
}

internal sealed interface PluginMenuEntry {
    val key: String
    val title: String

    data class BuiltIn(val kind: BuiltInPlugin, override val title: String) : PluginMenuEntry {
        override val key = "builtin:${kind.name}"
    }

    data class Installed(val plugin: PluginUI) : PluginMenuEntry {
        override val key = "installed:${plugin.pluginId}"
        override val title = plugin.title
    }
}

internal fun List<PluginMenuEntry>.alphabetizedPluginEntries(): List<PluginMenuEntry> =
    sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
