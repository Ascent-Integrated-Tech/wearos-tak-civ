package com.aitech.weartak_civ.data.takserver

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class TakEnrollmentXmlAndroidTest {
    @Test fun androidParserAcceptsNamespacedConfigurationWithoutActivityInitialization() {
        assertEquals(listOf("O" to "Team"), TakEnrollment.parseConfig(
            """<t:certificateConfig xmlns:t="urn:tak"><t:nameEntry name="O" value="Team"/></t:certificateConfig>"""))
    }

    @Test fun androidParserRejectsEntitiesWithoutReadingExternalResources() {
        assertThrows(Exception::class.java) {
            TakEnrollment.parseConfig(
                """<!DOCTYPE certificateConfig [<!ENTITY x SYSTEM "file:///nonexistent">]><certificateConfig>&x;</certificateConfig>""")
        }
    }
}
