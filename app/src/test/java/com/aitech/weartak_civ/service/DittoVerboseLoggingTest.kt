package com.aitech.weartak_civ.service

import android.app.Application
import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DittoVerboseLoggingTest {
    @Test fun disabledVerboseDoesNotFormatDocumentsButEnabledVerboseStillLogsOnce() {
        val tag = "WearTAK-DittoService"
        var formatted = 0
        val document = object {
            override fun toString(): String {
                formatted++
                return "fixture-document"
            }
        }
        ShadowLog.setLoggable(tag, Log.INFO)
        DittoService.logVerbose { "Wrote: $document" }
        assertEquals(0, formatted)
        assertEquals(0, ShadowLog.getLogsForTag(tag).size)
        ShadowLog.setLoggable(tag, Log.VERBOSE)
        DittoService.logVerbose { "Wrote: $document" }
        assertEquals(1, formatted)
        assertEquals(listOf("Wrote: fixture-document"), ShadowLog.getLogsForTag(tag).map { it.msg })
    }
}
