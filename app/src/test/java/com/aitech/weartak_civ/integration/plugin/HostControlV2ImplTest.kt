package com.aitech.weartak_civ.integration.plugin

import android.os.Process
import com.aitech.weartak_civ.WearTakApplication
import com.weartak.plugin.sdk.v2.model.DetectionEventParcel
import com.weartak.plugin.sdk.v2.model.V2HostRejectionCode
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HostControlV2ImplTest {

    @Before
    fun resetDiagnostics() {
        PluginDiagnostics.resetForTest()
    }

    @Test
    fun publishDetectionEvent_returnsNotSupported() {
        val control = v2Control()
        val result = control.publishDetectionEvent(
            DetectionEventParcel(
                pluginId = "com.plugin.pkg",
                streamKey = "detector",
                detectionId = "d1",
                sequence = 1L,
                timestampUtcMillis = 100L,
                classificationLabel = "test",
            ),
        )
        assertFalse(result.accepted)
        assertEquals(V2HostRejectionCode.NOT_SUPPORTED, result.rejectionCode)
        assertEquals(1, PluginDiagnostics.unsupportedV2CallCount("com.plugin.pkg"))
    }

    @Test
    fun getHostCapabilities_detectionAndTaskCommandsFalse() {
        val control = v2Control()
        val caps = control.hostCapabilities
        assertTrue(caps.cepRules)
        assertFalse(caps.detectionEvents)
        assertFalse(caps.taskCommands)
    }

    private fun v2Control(): HostControlV2Impl {
        val app = mockk<WearTakApplication>(relaxed = true)
        return HostControlV2Impl(
            app = app,
            ingress = mockk(relaxed = true),
            pluginPackage = "com.plugin.pkg",
            expectedPluginUid = Process.myUid(),
        )
    }
}
