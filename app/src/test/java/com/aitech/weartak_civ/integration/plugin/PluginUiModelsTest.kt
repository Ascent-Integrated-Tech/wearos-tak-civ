package com.aitech.weartak_civ.integration.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginUiModelsTest {
    private fun plugin(status: PluginLoadingStatus, subtitle: String?) = PluginUI(
        icon = null,
        title = "Sample",
        desc = "",
        loadingStatus = status,
        pluginId = "sample",
        versionName = "1",
        packageName = "com.weartak.plugin.sample",
        isLoaded = status == PluginLoadingStatus.LOADED,
        menuSubtitle = subtitle,
    )

    @Test
    fun subtitleOverridesOnlyMenuLabelForEveryLoadingStatus() {
        for (status in PluginLoadingStatus.entries) {
            val original = plugin(status, null)
            val overridden = original.copy(menuSubtitle = "Unfunded Request")

            assertEquals("Unfunded Request", overridden.menuLabel)
            assertEquals(original, overridden.copy(menuSubtitle = null))
            assertEquals(status, overridden.loadingStatus)
            assertEquals(original.isLoaded, overridden.isLoaded)
        }
    }

    @Test
    fun missingOrBlankSubtitlePreservesEveryLoadingStatusLabel() {
        for (status in PluginLoadingStatus.entries) {
            for (subtitle in listOf(null, "", " \n\t ")) {
                assertEquals(status.toString(), plugin(status, subtitle).menuLabel)
            }
        }
    }
}
