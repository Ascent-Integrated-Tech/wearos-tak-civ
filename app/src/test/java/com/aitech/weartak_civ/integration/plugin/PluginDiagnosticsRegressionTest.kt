package com.aitech.weartak_civ.integration.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PluginDiagnosticsRegressionTest {

    @Test
    fun resetForTest_clearsSnapshotFields() {
        PluginDiagnostics.markDiscovered(listOf("a", "b"))
        PluginDiagnostics.markBound("a")
        PluginDiagnostics.markConnected("a")
        PluginDiagnostics.markRejected("x", "reason")
        PluginDiagnostics.recordBindFailure("c", null)
        PluginDiagnostics.recordBinderDeath("a")
        PluginDiagnostics.markUnbound("a")
        PluginDiagnostics.resetForTest()
        val s = PluginDiagnostics.snapshot()
        assertTrue(s.discovered.isEmpty())
        assertTrue(s.bound.isEmpty())
        assertTrue(s.connected.isEmpty())
        assertTrue(s.rejected.isEmpty())
        assertEquals(0, s.bindFailures)
        assertEquals(0, s.deniedAdmission)
        assertEquals(0, s.binderDeaths)
        assertEquals(0, s.unbindEvents)
        assertEquals(0, s.discoveryRefreshCount)
        assertEquals(0, s.packageRemovals)
        assertTrue(s.categoryCounts.isEmpty())
    }

    @Test
    fun markDisconnected_clearsBoundState() {
        PluginDiagnostics.markBound("pkg")
        PluginDiagnostics.markDisconnected("pkg")
        val s = PluginDiagnostics.snapshot()
        assertTrue(s.bound.isEmpty())
        assertTrue(s.connected.isEmpty())
    }
}
