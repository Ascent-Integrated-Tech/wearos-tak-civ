package com.aitech.weartak_civ.presentation

import androidx.annotation.DrawableRes
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.integration.plugin.PluginUI

internal enum class BuiltInPlugin(@DrawableRes val brandIconRes: Int? = null) {
    COMMANDS,
    CRM,
    DART(R.drawable.ic_integration_certus),
    DATA_SYNC,
    DITTO(R.drawable.ic_integration_ditto),
    EVERYWHERE_COMMS(R.drawable.ic_integration_everywhere),
    FALCON(R.drawable.ic_integration_certus),
    GOTENNA(R.drawable.ic_integration_gotenna),
    SENSOR_MANAGER,
    SOMEWEAR_LABS(R.drawable.ic_integration_somewear),
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
