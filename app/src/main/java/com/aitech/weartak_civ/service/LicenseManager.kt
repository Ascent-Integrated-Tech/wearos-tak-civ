package com.aitech.weartak_civ.service

import android.util.Log
import com.cryptlex.android.lexactivator.LexActivator
import com.cryptlex.android.lexactivator.LexActivatorException
import org.xml.sax.ext.LexicalHandler


object LicenseManager {

    // A static-like boolean to hold the license validity status
    private var isLicenseValid: Boolean = false
    private const final val TAG = "LicenseManager"

    // Public method to check if the license is valid
    fun isLicenseValid(): Boolean {
        return isLicenseValid
    }

    // Public method to set the license validity status
    fun setLicenseValid(licenseValid: Boolean) {
        isLicenseValid = licenseValid
    }

    // Optionally, you can add logic here to validate the license as needed
    fun validateLicense(licenseKey: String) {
        // check the license validation here using IsLicenseGenuine
        // Initialize as invalid by default
        Log.v(TAG, "Validating License $licenseKey")
        val isValid = when (licenseKey) {
            "AscentFreeLicense",
            "1F9A-3F43-E661",
            "AE62-FFED-98A6",
            "2A2C-3788-CC46",
            "A2AB-4954-5277",
            "4BB4-1949-287B",
            "732A-CB7D-329C",
            "2374-6A83-B49E",
            "ADEB-4AE5-8F8C",
            "BCB8-5D53-5381" -> true
            else -> false
        }
        // Set license validity
        setLicenseValid(isValid)
    }

    fun verifyLicense() {
        try {
            initLicense()
            val status = LexActivator.IsLicenseGenuine()
            if (LexActivator.LA_OK == status) {
                Log.v(TAG, "License is genuinely activated!")
                setLicenseValid(true)
            } else if (LexActivator.LA_EXPIRED == status) {
                Log.v(TAG, "License is genuinely activated but has expired!")
                setLicenseValid(false)
            } else if (LexActivator.LA_GRACE_PERIOD_OVER == status) {
                Log.v(TAG, "License is genuinely activated but grace period is over!")
                setLicenseValid(false)
            } else if (LexActivator.LA_SUSPENDED == status) {
                Log.v(TAG, "License is genuinely activated but has been suspended!")
                setLicenseValid(false)
            } else {
                Log.v(TAG, "License is not activated. Error code: $status")
                setLicenseValid(false)
            }
        } catch (e: LexActivatorException) {
            Log.v(TAG, "Error code: " + e.code + " Error message: " + e.message)
        }
    }

    private fun authenticateUser() {
        Log.v(TAG, "Authenticating the user")
        LexActivator.AuthenticateUser("TEST.AIT", "TEST.AIT.Password01")
    }

    private fun initLicense() {
        LexActivator.SetProductData("NTc2MjE4QUMzMzcwRDg1NjM3NzEyNDA4M0YwNDI0ODQ=.gRc+EAYBpk9k2n8LrBiu4QpFydUDxng5lBe9yFBA42tvh8xBbd6aBz3i+dqHrz5YqSbDIUuIokW5jaFP5lQY6c9bpYqGxGjh32/ztOHobpcKz7HTTyNl08xs5eeeC8yz56xB2YuRJVYo5apG/iunXYQ64CQ7/kf8leaHdYfFS9XpeiwetjsKJmyNWd6/jUfywBjXBuJTpq6honUOqBb7emiCCt3V4ZhTGh9AgH6n4/pukOSmzspupWfeR9vGNYrGyTqDKpOS2e1uZXEmcKeh7yahwOrXZndLdKga3mZdYN3eBtlmAqlO91mtPhr+3NgyC4cEZOmf1IHj9jvht3PCNmNQ7/3KO0mVhXEhk/mvxo3jO18YwBV7LVyo1avOel9qglAHZFvwMk0BvCwC42QVmNBwynt2vgxTN9oDGJIMceqZsDgFQ7nKRRQCuBv243OJqCV3nWglsGiToW6KwLvmEse1Bij4dVhHMwX3qGQNYadDX+bEIkZ6qbUToZ6uO6lHr+y7+dQAvZ9qwwaQ7mN+yaalWR469iP9bOmLHZcvrgcUwOhdml67Xkx3nDlJPzuuREYCB7LMYpNiHooQX+San0VHRdBxgeKNWPjWd/DuLROqEAIZf+k90nOvvncSbC/9mXPwQbwSVJp9rWajqHIhgireL810awtXtFLuuimjrrYQZAoIU7kWHTitnfwxjfnLGSWQhTquPPqm5I1uZBB+n1HPUqpMVyoOxXOGGfKLLZ++mso+smr1V5QxI0nitO8xvROYhox4heiTB11hj+Eg7klBfhzeSk3HG82BxPF0DI0rf8GjSDVTDt0kAyGwttLt")
        LexActivator.SetProductId("a0cd6a11-fd66-4d9f-9793-296ae46500ba", LexActivator.LA_USER)
        LexActivator.SetLicenseKey("6D69C0-E17A3E-474DAB-08F3BD-0CE029-7B31ED")
        /*
        LexActivator.SetProductData("ODE2MDRFQ0E2Mjk5RjBFQzhFQkY0REUyN0U4RTYzOEQ=.A2aGhSgdg9KaRkKDQKGhcHFzoyO0WJKIEuXur4VITIiRdvX/sCGrW7sh0OdGmbgQytOv2/WykBTcDzR0a/YbmLMzZ8l100DUinb665qCTLdpZKKR6n3fLpEYBSCNpKTC2QFSFfLxckOykjvcuR+eUE+54gGm4U9Pk1kPc6sQgA+z38LAptzI7O4uG/tfjFlyKVQuAaVKmb7RfowABGTnyOMr/ycmMK5ROazvzUgpWhxIS92uWnHgnNY6qbNkNHpWvi+K5tmrnswaE0l1oT/hq437CmKLfm3djhcFytvSxXkA7bcEjyUqcm10L6a7wO10MTRZxVyjWMlt2mYgjYiCzrC/HmODxDCyI+ESVmHCcA0lDCc6j43sSWxnMBY8+OaVNg2ZcJgcpmEGX+CgVONqZ5TGqcXP/ENsJaSToLz16u9ahp6wsuyd+NHdjJ5THlsIgUIQ+BXGGDbZnxfKBfr3ggTR9gP4fWRsfYrxGEQAIJJ3v3qzh/9NSfo4FtbqxsBbRuKk2JKd056xt84ItEy47UxVvTOJywYjM2RWUvqv7UHJHb66mx0kWR96wWXFsAtIRfYqkPukZOoPdr0a8CcKoDr0b3YqCdZVzTJzHbmTx2Vbv9yI4uiTCT6aa0n918ztf3l4hYXkRW80oDMx6XTw8+w7964fWAHDaYY+WVW5podtqmkovlPco6uY7PW9JAztIfCkOZlT9X+zDedYxiuv8q0C/oobGlbo/KmhAdN7lkvjmno3a4pN87WUOK/2pXC6GOde1GhnAr3MjbiY5i9Hj/Wu8Q9OXmB8kI764inob6sltb6gL1xYEs10Ef6Wwjni")
        LexActivator.SetProductId("20d0b481-a6d1-4a98-9552-7752a360d8b0", LexActivator.LA_USER)
        LexActivator.SetLicenseKey("5E994E-FC342D-481A9F-486338-9BAC4D-2952B7")
        */

        Log.v(TAG, "Setting Product Data and Product ID for Lex")
    }

    fun activateLicense(licenseKey: String) {
        initLicense()
        try {
            val status = LexActivator.ActivateLicense();
            if (LexActivator.LA_OK == status) {
                Log.v(TAG, "License Activated Successfully")

                val department = LexActivator.GetLicenseMetadata("Department")
                val state = LexActivator.GetLicenseMetadata("State")
                val vers = LexActivator.GetProductVersionName()
                val expiry = LexActivator.GetLicenseExpiryDate()

                var features = ""
                if (vers == "AllNineYards") {
                    features = "Location, Biometrics, Ditto, Meshtastic"
                } else if (vers == "Vanilla") {
                    features = "Location"
                }

                Log.v(TAG, """
                    License Metadata:
                    ===============================
                    Department: $department
                    State:      $state
                    Prod Vers:  $vers
                    Features:   $features.toString()
                """.trimIndent())

            } else if (LexActivator.LA_EXPIRED == status ) {
                Log.v(TAG, "$status License EXPIRED")
            } else if (LexActivator.LA_SUSPENDED == status){
                Log.v(TAG, "$status License is suspended.")
            } else {
                Log.v(TAG, "$status License was not activated successfully.")
            }
        } catch (e: LexActivatorException) {
            Log.v(TAG, "the license no activate error code $e")
        }
    }
}