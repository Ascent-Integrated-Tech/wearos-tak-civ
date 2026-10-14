package com.aitech.weartak_civ.presentation

import com.aitech.weartak_civ.integration.plugin.PluginLoadingStatus
import com.aitech.weartak_civ.integration.plugin.PluginUI
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginMenuEntryTest {
    @Test
    fun sortsBuiltInsAndInstalledPluginsTogetherIgnoringCase() {
        val entries = listOf(
            PluginMenuEntry.BuiltIn(BuiltInPlugin.SOMEWEAR_LABS, "Somewear Labs"),
            installed("zulu", "zulu"),
            PluginMenuEntry.BuiltIn(BuiltInPlugin.DITTO, "Ditto"),
            PluginMenuEntry.BuiltIn(BuiltInPlugin.EVERYWHERE_COMMS, "Everywhere Comms"),
            installed("alpha", "alpha"),
            PluginMenuEntry.BuiltIn(BuiltInPlugin.GOTENNA, "GoTenna"),
            PluginMenuEntry.BuiltIn(BuiltInPlugin.SENSOR_MANAGER, "Sensor Manager"),
            PluginMenuEntry.BuiltIn(BuiltInPlugin.COMMANDS, "Commands"),
            PluginMenuEntry.BuiltIn(BuiltInPlugin.FALCON, "FALCON"),
            PluginMenuEntry.BuiltIn(BuiltInPlugin.DART, "DART"),
            PluginMenuEntry.BuiltIn(BuiltInPlugin.CRM, "CRM"),
        )

        assertEquals(
            listOf("alpha", "Commands", "CRM", "DART", "Ditto", "Everywhere Comms", "FALCON", "GoTenna", "Sensor Manager", "Somewear Labs", "zulu"),
            entries.alphabetizedPluginEntries().map { it.title },
        )
        assertEquals("Somewear Labs", entries.first().title)
    }

    @Test
    fun duplicateNamesRetainDistinctKeysAndStableOrder() {
        val entries = listOf(
            PluginMenuEntry.BuiltIn(BuiltInPlugin.DITTO, "Ditto"),
            installed("DITTO", "DITTO"),
            installed("another-ditto", "ditto"),
        )

        val sorted = entries.alphabetizedPluginEntries()
        assertEquals(entries, sorted)
        assertEquals(3, sorted.map { it.key }.distinct().size)
    }

    @Test
    fun handlesEmptyList() {
        assertEquals(emptyList<PluginMenuEntry>(), emptyList<PluginMenuEntry>().alphabetizedPluginEntries())
    }

    private fun installed(id: String, title: String) = PluginMenuEntry.Installed(
        PluginUI(
            icon = null,
            title = title,
            desc = "",
            loadingStatus = PluginLoadingStatus.NOT_LOADED,
            pluginId = id,
            versionName = "",
            packageName = "",
        )
    )
}
