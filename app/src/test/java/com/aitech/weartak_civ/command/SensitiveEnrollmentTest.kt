package com.aitech.weartak_civ.command

import org.junit.Assert.*
import org.junit.Test

class SensitiveEnrollmentTest {
    @Test fun classificationDoesNotDependOnParsingOrPermission() {
        for (text in listOf("#weartakcommandenrollserver N,h,8089,u,fake-secret",
            "  #WearTAKCommand enrollserver broken", "\u00a0#weartakcommand\u2007enrollserver malformed",
            "#weartakcommandenrollserver" + "x".repeat(1000))) {
            assertTrue(SensitiveEnrollment.isSensitiveText(text))
            assertTrue(SensitiveEnrollment.containsSensitiveXml("<event><remarks>$text</remarks></event>"))
            assertTrue(SensitiveEnrollment.containsSensitiveDocument(mapOf("msg" to text)))
            assertTrue(SensitiveEnrollment.containsSensitiveDocument(mapOf("r" to "<detail><remarks>$text</remarks>")))
        }
        assertFalse(SensitiveEnrollment.isSensitiveText("ordinary message"))
        assertFalse(SensitiveEnrollment.containsSensitiveXml("<event><remarks>Hello</remarks></event>"))
    }

    @Test fun xmlCharacterReferencesCannotBypassSinkDetection() {
        assertTrue(SensitiveEnrollment.containsSensitiveXml(
            "<detail><remarks>&#35;weartakcommand&#32;enrollserver N,h,8089,u,fake-secret</remarks></detail>"))
    }
}
