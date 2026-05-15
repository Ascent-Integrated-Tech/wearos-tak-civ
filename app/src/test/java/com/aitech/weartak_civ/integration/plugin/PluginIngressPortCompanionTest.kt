package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PluginIngressPortCompanionTest {

    @Test
    fun sensorTopic_addsPluginPrefixWhenMissing() {
        assertEquals(
            "com.p.sensors.t",
            PluginIngressPort.sensorTopic("com.p", "sensors.t")
        )
    }

    @Test
    fun sensorTopic_keepsAlreadyNamespacedKey() {
        assertEquals(
            "com.p.sensors.t",
            PluginIngressPort.sensorTopic("com.p", "com.p.sensors.t")
        )
    }

    @Test
    fun firstNumericFromValues_prefersKnownKeys() {
        val b = Bundle()
        b.putDouble("value", 3.5)
        b.putDouble("valueDouble", 9.0)
        assertEquals(3.5, PluginIngressPort.firstNumericFromValues(b), 0.001)
    }
}
