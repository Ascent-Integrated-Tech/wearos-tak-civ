package com.aitech.weartak_civ.presentation

import android.app.Application
import com.aitech.weartak_civ.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TakServerIndicatorTest {
    @Test fun exactTruthTableIncludesStaleConnectionsWhileOffOrUnconfigured() {
        val cases = listOf(
            Triple(false, false, false) to TakServerIndicator.NO_SERVER,
            Triple(false, false, true) to TakServerIndicator.NO_SERVER,
            Triple(false, true, false) to TakServerIndicator.NO_SERVER,
            Triple(false, true, true) to TakServerIndicator.NO_SERVER,
            Triple(true, false, false) to TakServerIndicator.OFF,
            Triple(true, false, true) to TakServerIndicator.OFF,
            Triple(true, true, false) to TakServerIndicator.NOT_CONNECTED,
            Triple(true, true, true) to TakServerIndicator.CONNECTED
        )
        cases.forEach { (inputs, expected) ->
            assertEquals(inputs.toString(), expected,
                TakServerIndicator.resolve(inputs.first, inputs.second, inputs.third))
        }
    }

    @Test fun reuseMainScreenGlyphsAndExposeSpecificUserFacingLabels() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals(R.drawable.tak_server_connected, TakServerIndicator.CONNECTED.drawable)
        assertEquals(R.drawable.tak_server_connecting, TakServerIndicator.OFF.drawable)
        assertEquals(R.drawable.tak_server_connecting, TakServerIndicator.NOT_CONNECTED.drawable)
        assertEquals(R.drawable.tak_server_disconnected, TakServerIndicator.NO_SERVER.drawable)
        assertEquals("Connected", app.getString(TakServerIndicator.CONNECTED.label))
        assertEquals("Switched off", app.getString(TakServerIndicator.OFF.label))
        assertEquals("Not connected", app.getString(TakServerIndicator.NOT_CONNECTED.label))
        assertEquals("No server configured", app.getString(TakServerIndicator.NO_SERVER.label))
        assertEquals(4, TakServerIndicator.entries.map { app.getString(it.label) }.toSet().size)
    }
}
