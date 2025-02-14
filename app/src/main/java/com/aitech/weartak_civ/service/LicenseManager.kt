package com.aitech.weartak_civ.service

import android.util.Log
import com.cryptlex.android.lexactivator.LexActivator
import com.cryptlex.android.lexactivator.LexActivatorException


object LicenseManager {

    // A static-like boolean to hold the license validity status
    private var isLicenseValid: Boolean = false

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
                Log.d("LicenseManager", "License is genuinely activated!")
                setLicenseValid(true)
            } else if (LexActivator.LA_EXPIRED == status) {
                Log.d("LicenseManager", "License is genuinely activated but has expired!")
                setLicenseValid(false)
            } else if (LexActivator.LA_GRACE_PERIOD_OVER == status) {
                Log.d("LicenseManager", "License is genuinely activated but grace period is over!")
                setLicenseValid(false)
            } else if (LexActivator.LA_SUSPENDED == status) {
                Log.d("LicenseManager", "License is genuinely activated but has been suspended!")
                setLicenseValid(false)
            } else {
                Log.d("LicenseManager", "License is not activated. Error code: $status")
                setLicenseValid(false)
            }
        } catch (e: LexActivatorException) {
            Log.d("LicenseManager", "Error code: " + e.code + " Error message: " + e.message)
        }
    }

    private fun initLicense() {
        LexActivator.SetProductData("RDEyNTFCNzVERkNGNzMzRjk1NzFDNUNENDVBODU2MDE=.XliPyzDSLhGDn7P3qy92RmGP1lIgfIAJJglDPcoAjac6wVCYJ1N0vxnNyM2kP0G65l9LUXgGwedr1mKhMs6c8bWi8hKnnYvjmmfwNQDVfy1hNBtHiJKm6AAaHkfy0GZQbKYewQr3lu1Y06fnkXcLW/sccllHG98OoyKRcbrFrcDTudZlGMVa5/oUS3aEphLJ33Pis3NRSScfThJQKiYzWjX6dvKdtG1o3v2cbHrB+0J0VXLFHUBPN1MBhIy5/MhcHXDPxmq21uVp6L1fp8uk8sAOGpgslhbFfgsu8vxELqs0/NrfNvJn3VVlV7GXDAZ4pEWAKMr/nFXiywLX1ic21OijeiOrub1oRb40QSStdluiEluSV5BxLUQdmjd2hUE5nhlZRrIzv+hysqZMBrqsXykKyA5xf84ka63dZr7JzR7lUFcxx0LZnyRbuDQJks/tmn/jVyOaFer7k3yi3jO208IR7KEk2gVV2OmSkGSo969JyMiq4Voe/dh7tDwvZIK7RcwnI7Vv6V5EguWFNJLmkPSmUR+lA4DdTmxtorpwkM8hQ9uBj9WRb+xzL6y2kJzxRzv6rtcDlFt8Ms8/gJeylI2PHsEojqXTS81nEzxKrEkapurCfhnhnQzBLkrEmcdZaN7BXdWImgLrx/Iv4oBZZSxseCUjhE6dNkGt94MgTwfaUOXLavuQWGYLcf7W+lXQjBsTAKcxnYI68tiKKrRFKJ1jVDdiS/+6Acl4nTWTV46Bh8mAH81gtq8tlnQBs9IW6fcLJfcLYk8QSOudOVHidK3KJydUY60cYfGNMeGJFKmes0E3xFxn7chfwaJANrAp")
        LexActivator.SetProductId("20d0b481-a6d1-4a98-9552-7752a360d8b0", LexActivator.LA_USER)
        LexActivator.SetLicenseKey("5E994E-FC342D-481A9F-486338-9BAC4D-2952B7")
        Log.d("LicenseManager", "Setting Product Data and Product ID for Lex")
    }

    fun activateLicense(licenseKey: String) {
        initLicense()
        try {
            val status = LexActivator.ActivateLicense();
            if (LexActivator.LA_OK == status) {
                Log.d("LicenseManager", "$status License Activated Successfully")
            } else if (LexActivator.LA_EXPIRED == status ) {
                Log.d("LicenseManager", "$status License EXPIRED")
            } else if (LexActivator.LA_SUSPENDED == status){
                Log.d("LicenseManager", "$status License is suspended.")
            } else {
                Log.d("LicenseManager", "$status License was not activated successfully.")
            }
        } catch (e: LexActivatorException) {
            Log.d("LicenseManager", "the license no activate error code $e")
        }
    }
}