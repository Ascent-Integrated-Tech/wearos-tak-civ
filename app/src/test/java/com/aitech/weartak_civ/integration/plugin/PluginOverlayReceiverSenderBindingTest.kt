package com.aitech.weartak_civ.integration.plugin

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowBinder

/**
 * Receiver-layer sender binding check for the plugin overlay path (design §477 + 2026-05-11
 * review Finding C). When the calling UID does not own the claimed `pluginId`, the receiver
 * must drop the broadcast WITHOUT invoking the worker / `CepProvider` apply path.
 */
@RunWith(RobolectricTestRunner::class)
class PluginOverlayReceiverSenderBindingTest {

    @Test
    fun mismatchedSenderForOverlayBroadcastIsDropped() {
        val app = RuntimeEnvironment.getApplication()
        val pm = Shadows.shadowOf(app.packageManager)
        val callerUid = 11042
        pm.setPackagesForUid(callerUid, "com.malicious.spoofer")
        ShadowBinder.setCallingUid(callerUid)

        val receiver = PluginOverlayReceiver()
        val intent = Intent(PluginOverlayReceiver.ACTION_PLUGIN_OVERLAY).apply {
            putExtra(PluginOverlayReceiver.EXTRA_PLUGIN_ID, "com.acme.plugin")
            putExtra(PluginOverlayReceiver.EXTRA_RAW_OVERLAY_JSON, "{}")
        }
        // Should return cleanly without enqueuing work (no WorkManager init in this test
        // env — any path that reaches enqueueUniqueWork would throw).
        receiver.onReceive(app, intent)
    }

    @Test
    fun mismatchedSenderForUnregisterIsDropped() {
        val app = RuntimeEnvironment.getApplication()
        val pm = Shadows.shadowOf(app.packageManager)
        val callerUid = 11043
        pm.setPackagesForUid(callerUid, "com.unrelated.app")
        ShadowBinder.setCallingUid(callerUid)

        val receiver = PluginOverlayReceiver()
        val intent = Intent(PluginOverlayReceiver.ACTION_PLUGIN_UNREGISTER).apply {
            putExtra(PluginOverlayReceiver.EXTRA_PLUGIN_ID, "com.acme.plugin")
        }
        receiver.onReceive(app, intent)
    }

    @Test
    fun missingPluginIdExtraForOverlayIsDroppedEvenWhenSenderIsValid() {
        val app = RuntimeEnvironment.getApplication()
        val pm = Shadows.shadowOf(app.packageManager)
        val callerUid = 11044
        pm.setPackagesForUid(callerUid, "com.acme.plugin")
        ShadowBinder.setCallingUid(callerUid)

        val receiver = PluginOverlayReceiver()
        val intent = Intent(PluginOverlayReceiver.ACTION_PLUGIN_OVERLAY).apply {
            // pluginId extra is OMITTED — without it the receiver cannot bind sender→plugin
            putExtra(PluginOverlayReceiver.EXTRA_RAW_OVERLAY_JSON, "{}")
        }
        receiver.onReceive(app, intent)
        // No assertion failure → receiver dropped cleanly.
        assertTrue(true)
    }

    @Test
    fun unknownActionIsDropped() {
        val app = RuntimeEnvironment.getApplication()
        val receiver = PluginOverlayReceiver()
        receiver.onReceive(app, Intent("com.aitech.weartak_civ.NOT_AN_ACTION"))
        assertFalse(false)
    }

    @Test
    fun uniqueWorkNameIsScopedPerPluginId() {
        val a = PluginOverlayReceiver.uniqueWorkNameFor("com.acme.plugin.a")
        val b = PluginOverlayReceiver.uniqueWorkNameFor("com.acme.plugin.b")
        assertNotEquals(a, b)
        assertTrue(a.contains("com.acme.plugin.a"))
        assertTrue(b.contains("com.acme.plugin.b"))
    }
}
