package com.aitech.weartak_civ.service

import android.app.Application
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.DeflaterOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DittoCommunityLicenseHelperTest {
    private fun fixture(expiry: Long = System.currentTimeMillis() + 86_400_000) =
        JSONObject().put("v", 2).put("it", "SK").put("a", "synthetic-app")
            .put("s", "synthetic-key").put("t", "synthetic-token").put("e", expiry)

    private fun encode(json: JSONObject): String {
        val buffer = ByteArrayOutputStream()
        DeflaterOutputStream(buffer).use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
        return Base64.encodeToString(buffer.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
    }

    @Test fun sharedKeyPdlRetainsIdentityAndExpiry() {
        val json = fixture()
        val license = DittoCommunityLicenseHelper.parse(encode(json))
        assertEquals("synthetic-app", license.appId)
        assertEquals("synthetic-key", license.sharedKey)
        assertEquals("synthetic-token", license.licenseToken)
        assertEquals(json.getLong("e"), license.expiresAtMillis)
        assertFalse(license.isExpired)
        assertTrue(DittoCommunityLicenseHelper.parse(encode(fixture(1))).isExpired)
    }

    @Test fun invalidOrIncompleteLicensesAreRejected() {
        listOf("v", "it", "a", "s", "t", "e").forEach { field ->
            val json = fixture().apply { remove(field) }
            assertThrows(IllegalArgumentException::class.java) {
                DittoCommunityLicenseHelper.parse(encode(json))
            }
        }
    }

    @Test fun authorizedProvisioningArtifactPassesActualParserWithoutLoggingSecrets() {
        val path = System.getenv("WEARTAK_TEST_COMMUNITY_PDL_FILE")
        assumeTrue("Only runs during authorized local provisioning", !path.isNullOrBlank())
        val encoded = File(requireNotNull(path)).readText().trim()
        val license = DittoCommunityLicenseHelper.parse(encoded)
        assertEquals("a8172f2a-d0f0-4a9c-99b5-1d5db439ed4c", license.appId)
        assertEquals("SK", license.identityType)
        assertTrue("Shared key must be present", license.sharedKey.isNotBlank())
        assertTrue("Offline token must be present", license.licenseToken.isNotBlank())
        assertFalse("Community license must be unexpired", license.isExpired)
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        println("Authorized Community PDL validated: length=${encoded.length}, sha256=$digest")
    }
}
