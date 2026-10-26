package com.aitech.weartak_civ

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

class BackupExclusionTest {
    @Test fun manifestAndExtractionRulesExcludeAllApplicationDataFromBackupAndTransfer() {
        val parser = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder()
        val source = File("src", "main")
        val manifest = parser.parse(File(source, "AndroidManifest.xml"))
        val applications = manifest.getElementsByTagName("application")
        assertEquals(1, applications.length)
        val application = applications.item(0) as Element
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        assertEquals("false", application.getAttributeNS(androidNamespace, "allowBackup"))
        assertEquals("false", application.getAttributeNS(androidNamespace, "fullBackupContent"))
        assertEquals("@xml/data_extraction_rules",
            application.getAttributeNS(androidNamespace, "dataExtractionRules"))

        val rules = parser.parse(File(File(File(source, "res"), "xml"), "data_extraction_rules.xml"))
        val domains = setOf("root", "file", "database", "sharedpref", "external")
        for (mode in listOf("cloud-backup", "device-transfer")) {
            val sections = rules.getElementsByTagName(mode)
            assertEquals("$mode must have one rules section", 1, sections.length)
            val excludes = (sections.item(0) as Element).getElementsByTagName("exclude")
            val excludedDomains = (0 until excludes.length).mapNotNull { index ->
                val exclude = excludes.item(index) as Element
                exclude.getAttribute("domain").takeIf { exclude.getAttribute("path") == "." }
            }.toSet()
            assertEquals("$mode must exclude every application data domain at its root",
                domains, excludedDomains)
        }
    }
}
