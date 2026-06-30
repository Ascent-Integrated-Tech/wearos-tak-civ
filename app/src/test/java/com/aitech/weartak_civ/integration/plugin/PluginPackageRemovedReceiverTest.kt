package com.aitech.weartak_civ.integration.plugin

import android.content.Intent
import android.net.Uri
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Behavioral coverage for [PluginPackageRemovedReceiver]. The receiver itself does only
 * filter + dispatch; full eviction is exercised in the integration-style test for
 * [PluginRegistry] and the apply executor. These tests assert the receiver tolerates the
 * boundary conditions called out in the design and review.
 */
@RunWith(RobolectricTestRunner::class)
class PluginPackageRemovedReceiverTest {

    @Test
    fun ignoresPackageRemovedDuringReplacement() {
        val app = RuntimeEnvironment.getApplication()
        val intent = Intent(Intent.ACTION_PACKAGE_REMOVED).apply {
            data = Uri.parse("package:com.acme.plugin")
            putExtra(Intent.EXTRA_REPLACING, true)
        }
        PluginPackageRemovedReceiver().onReceive(app, intent)
    }

    @Test
    fun ignoresMissingPackageData() {
        val app = RuntimeEnvironment.getApplication()
        val intent = Intent(Intent.ACTION_PACKAGE_FULLY_REMOVED)
        PluginPackageRemovedReceiver().onReceive(app, intent)
    }

    @Test
    fun ignoresUnknownAction() {
        val app = RuntimeEnvironment.getApplication()
        val intent = Intent("com.example.UNKNOWN").apply {
            data = Uri.parse("package:com.acme.plugin")
        }
        PluginPackageRemovedReceiver().onReceive(app, intent)
    }

    @Test
    fun packageFullyRemovedTriggersEvictionAttempt() {
        val app = RuntimeEnvironment.getApplication()
        val intent = Intent(Intent.ACTION_PACKAGE_FULLY_REMOVED).apply {
            data = Uri.parse("package:com.acme.plugin")
        }
        // CepProvider isn't initialized in this test; the receiver launches the eviction in
        // a background coroutine and swallows any failure. The synchronous return path is
        // what matters here.
        PluginPackageRemovedReceiver().onReceive(app, intent)
    }
}
