package com.aitech.weartak_civ.integration.plugin

import android.os.Process
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cep.di.CepProvider
import com.weartak.plugin.sdk.model.CepRuleEventParcel
import com.weartak.plugin.sdk.model.CepRuleOperation
import com.weartak.plugin.sdk.model.HostCapabilitiesParcel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HostControlImplTest {

    @Test
    fun getHostCapabilities_reportsToastAndCepStreaming() {
        val app = mockk<WearTakApplication>(relaxed = true)
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        val control = HostControlImpl(
            app = app,
            ingress = ingress,
            pluginPackage = "com.plugin.pkg",
            expectedPluginUid = Process.myUid(),
        )

        val caps: HostCapabilitiesParcel = control.hostCapabilities
        assertTrue(caps.toasts)
        assertTrue(caps.cepRuleStreaming)
        assertTrue(caps.markers)
        assertEquals(false, caps.mapLayers)
        assertEquals(false, caps.sharedMemory)
    }

    @Test
    fun applyCepRuleEvent_routesThroughIngress() {
        val app = mockk<WearTakApplication>(relaxed = true)
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        every {
            ingress.applyCepRuleEventBlocking(any(), any(), any(), any())
        } returns CepProvider.ApplyResult.Applied("hash", 1L)
        val control = HostControlImpl(
            app = app,
            ingress = ingress,
            pluginPackage = "com.plugin.pkg",
            expectedPluginUid = Process.myUid(),
        )
        val event = CepRuleEventParcel(
            operation = CepRuleOperation.ADD,
            pluginId = "com.plugin.pkg",
            sensorKey = "com.plugin.pkg.sensor",
            ruleSetId = "default",
            sequence = 1L,
            rulesJson = """{"rules":[{"id":"r1"}]}""",
        )

        control.applyCepRuleEvent(event)

        verify(exactly = 1) {
            ingress.applyCepRuleEventBlocking(
                "com.plugin.pkg",
                event,
                Process.myUid(),
                any(),
            )
        }
    }
}
