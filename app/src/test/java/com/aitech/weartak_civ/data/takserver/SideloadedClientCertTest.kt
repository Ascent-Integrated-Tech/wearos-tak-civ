package com.aitech.weartak_civ.data.takserver

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class SideloadedClientCertTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val address = "tak.example.com"

    private fun writeP12(password: String, name: String = address): File {
        val dir = temp.root
        File(dir, "$name.p12").writeBytes(Base64.getDecoder().decode(TestClientP12.p12(password)))
        return dir
    }

    @Test
    fun noFileIsNone() {
        assertEquals(SideloadedClientCert.Status.None, SideloadedClientCert.detect(temp.root, address))
    }

    @Test
    fun missingDirectoryOrBlankAddressIsNone() {
        assertEquals(SideloadedClientCert.Status.None, SideloadedClientCert.detect(null, address))
        assertEquals(SideloadedClientCert.Status.None, SideloadedClientCert.detect(temp.root, "  "))
    }

    @Test
    fun pathLikeAddressIsIgnored() {
        writeP12("atakatak", "x")
        assertEquals(SideloadedClientCert.Status.None, SideloadedClientCert.detect(temp.root, "../x"))
    }

    @Test
    fun binaryP12WithPasswordFileReportsCommonName() {
        val dir = writeP12("s3cret")
        File(dir, "$address.pwd").writeText("s3cret\n")
        assertEquals(
            SideloadedClientCert.Status.Found("WearTAK Test Client"),
            SideloadedClientCert.detect(dir, " $address ")
        )
    }

    @Test
    fun defaultPasswordIsUsedWithoutPasswordFile() {
        val dir = writeP12("atakatak")
        assertEquals(
            SideloadedClientCert.Status.Found("WearTAK Test Client"),
            SideloadedClientCert.detect(dir, address)
        )
    }

    @Test
    fun base64P12IsDetected() {
        File(temp.root, "$address.p12.b64").writeText(TestClientP12.p12("atakatak").chunked(64).joinToString("\n"))
        assertEquals(
            SideloadedClientCert.Status.Found("WearTAK Test Client"),
            SideloadedClientCert.detect(temp.root, address)
        )
    }

    @Test
    fun wrongPasswordIsUnreadable() {
        val dir = writeP12("s3cret")
        File(dir, "$address.pwd").writeText("wrong")
        assertEquals(SideloadedClientCert.Status.Unreadable, SideloadedClientCert.detect(dir, address))
    }

    @Test
    fun corruptFileIsUnreadable() {
        File(temp.root, "$address.p12").writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(SideloadedClientCert.Status.Unreadable, SideloadedClientCert.detect(temp.root, address))
    }
}
