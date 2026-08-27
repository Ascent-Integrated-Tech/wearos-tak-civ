/**
 * Parses Ditto Community licenses.
 * Community licenses come in as a Base64-encoded and compressed payload.
 * This helper handles decoding the payload, reading the JSON inside,
 * validating the required fields, and returning a DittoCommunityLicense
 * instance for the rest of the app to use.
 */

package com.aitech.weartak_civ.service

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream

// Holds the license information after it has been decoded and parsed
data class DittoCommunityLicense(
    val appId: String,
    val sharedKey: String,
    val licenseToken: String,
    val expiresAtMillis: Long,
    val identityType: String
) {
    // Checks whether the license expiration date has already passed
    val isExpired: Boolean
        get() = expiresAtMillis > 0 &&
                System.currentTimeMillis() >= expiresAtMillis
}

object DittoCommunityLicenseHelper {

    // Takes the encoded license string and turns it into a license object
    // that the rest of the app can use
    fun parse(encodedPdl: String): DittoCommunityLicense {

        // Make sure we actually received a license before trying to parse it
        require(encodedPdl.isNotBlank()) {
            "Ditto Community license is missing"
        }

        // The license is stored as a Base64 string
        // Decode it back into compressed bytes first
        val compressedBytes = try {
            Base64.decode(
                encodedPdl.trim(),
                Base64.URL_SAFE or Base64.NO_WRAP
            )
        } catch (error: IllegalArgumentException) {
            // This happens when the license string is not valid Base64
            throw IllegalArgumentException(
                "Ditto Community license is not valid Base64",
                error
            )
        }

        // The decoded bytes are still compressed
        // Decompress them so we can read the JSON stored inside
        val jsonText = try {
            InflaterInputStream(
                ByteArrayInputStream(compressedBytes)
            ).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readText()
            }
        } catch (error: Exception) {
            // This usually means the decoded data was not compressed correctly
            // or was not a valid Ditto license payload
            throw IllegalArgumentException(
                "Could not decompress Ditto Community license",
                error
            )
        }

        // Turn the decompressed JSON text into a JSONObject
        // so we can read each license value by its key
        val json = try {
            JSONObject(jsonText)
        } catch (error: Exception) {
            // The data was decompressed, but it was not valid JSON
            throw IllegalArgumentException(
                "Ditto Community license does not contain valid JSON",
                error
            )
        }

        // Read the license format version.
        // A value of -1 means the "v" field was missing
        val version = json.optInt("v", -1)

        // This parser only knows how to handle version 2 licenses
        require(version == 2) {
            "Unsupported Ditto Community license version: $version"
        }

        // Read the identity type from the license.
        // "SK" means the license uses SharedKey authentication
        val identityType = json.optString("it")

        // This app expects a SharedKey license, so reject other identity types
        require(identityType == "SK") {
            "Expected SharedKey Ditto identity but found: $identityType"
        }

        // Pull the values we need out of the license JSON
        // The JSON uses shortened keys to keep the license payload smaller
        val appId = json.optString("a")            // "a" contains the Ditto app ID.
        val sharedKey = json.optString("s")        // "s" contains the SharedKey.
        val licenseToken = json.optString("t")     // "t" contains the offline license token.
        val expiresAtMillis = json.optLong("e", 0L) // "e" contains the expiration timestamp.

        // Make sure the app ID was included in the license
        require(appId.isNotBlank()) {
            "Ditto Community license app ID is missing"
        }

        // Make sure the SharedKey was included in the license
        require(sharedKey.isNotBlank()) {
            "Ditto Community license shared key is missing"
        }

        // Make sure the offline license token was included
        require(licenseToken.isNotBlank()) {
            "Ditto Community offline token is missing"
        }

        // The expiration time has to be a positive Unix timestamp in milliseconds
        // A value of 0 means the expiration field was missing or invalid
        require(expiresAtMillis > 0L) {
            "Ditto Community license expiration is missing"
        }

        // Everything has been decoded, parsed, and validated
        // Return the values as a DittoCommunityLicense object
        return DittoCommunityLicense(
            appId = appId,
            sharedKey = sharedKey,
            licenseToken = licenseToken,
            expiresAtMillis = expiresAtMillis,
            identityType = identityType
        )
    }
}