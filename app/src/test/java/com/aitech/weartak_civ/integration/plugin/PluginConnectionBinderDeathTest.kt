package com.aitech.weartak_civ.integration.plugin

import android.content.ComponentName
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cep.plugin.PluginTrust
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class PluginConnectionBinderDeathTest {

    @Before
    fun resetDiag() {
        PluginDiagnostics.resetForTest()
    }

    @Test
    fun triggerBinderDeathSyncForTest_notifiesManagerAndDiagnostics() {
        val app = mockk<WearTakApplication>(relaxed = true)
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        val mgr = mockk<PluginManager>(relaxed = true)
        val cn = ComponentName("com.death.plugin", ".Svc")
        val trust = mockk<PluginTrust>(relaxed = true)
        val conn = PluginConnection(app, ingress, cn, 100, mgr, trust)
        conn.triggerBinderDeathSyncForTest()
        verify(exactly = 1) { mgr.onConnectionDied(conn) }
        val snap = PluginDiagnostics.snapshot()
        assertEquals(1, snap.binderDeaths)
        assertEquals(1, snap.categoryCounts[PluginDiagnostics.AdmissionCategory.BINDER_DEATH] ?: 0)
    }

    @Test
    fun binderDeathRecipient_postsToMainAndEvicts() {
        val app = mockk<WearTakApplication>(relaxed = true)
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        val mgr = mockk<PluginManager>(relaxed = true)
        val trust = mockk<PluginTrust>(relaxed = true)
        val conn = PluginConnection(app, ingress, ComponentName("com.death2", ".Svc"), 101, mgr, trust)
        conn.binderDeathRecipientForTest.binderDied()
        ShadowLooper.shadowMainLooper().idle()
        verify(exactly = 1) { mgr.onConnectionDied(conn) }
        val snap = PluginDiagnostics.snapshot()
        assertEquals(1, snap.binderDeaths)
        assertEquals(1, snap.categoryCounts[PluginDiagnostics.AdmissionCategory.BINDER_DEATH] ?: 0)
    }
}
