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
//        LexActivator.SetProductData("MkM3RTU1Q0I1RjY5MjM0NEQ2ODgwRUQyNUY3N0JEMzg=.8gDH04Kj9jfbVMdZzvqWb5ePwfs1pvbnRRKHKnoXYddw/cVHJkFpLN5rNe63NZgBzMlmFGTeZumW9YODYyqJtnLADohzICEZBB8WOpcH+Cr74v5GV5QEsKxlXWcnnQMnSH4ybnGhlSJwaBY+yFCB5XBYtVIq0JVvr90J9qB+cgyKKWSjxwxuCgly0MgdUz6GjhS1Qrz66xP3CGSVOA/NqO+3kDI0yh+HaCM0/x/3lxkP32J+K/NhFupHYuQP6f+cCcvHpYVgiJvBoRH91/Jg/UjeHDWKRiExYboI60bCYUD75HkILuvpG3icPd2u1dePB/A1PSNqntw+l6K3j4hL/0u0/ATsdYDDpkzC8biJuLCViHu6jf7UtufBFa9Z77isqkNzJshN/hRG3ZbEG/VIOzzCmtSP+iQzLfu5ePgILgYmMsJgiwJTUwtwplljJ5B11GjPgMLLRz1kpSl8Jch/I3VJpwSCM7wUz3Lwfrlwo77ATkHdsFzhrY7IqvFHNtXb+n8rjruKbjeltOwzQlAhwoRBRjL7V2x6VKSkyYTXdYlQ1dCdzosSVkyCcY9y32WOLhTrI4xncURoA0D9f/HrrOlJNPo9izrPqYqO9/BgQvXaF3gpXEBJ7ljkcCf4wsBBltntBmLd1cuJflbHcQoDwrfaEynBNc/2xE3TztouGyIxX1gmhxi2/3d0lT8mo1cssnYWsiONvjsDXnfeRcFaA+/5Fip21qWVbRynC9Vi9pSVEo3O4LWRu5ksbM9fay7+2nOdm+S0Rf3sQjaH+65twwVVWSm2yJqmxmWOV3LxMJSUWboyRFF/2L+YxqnZC1RE");
        LexActivator.SetProductData("RDEyNTFCNzVERkNGNzMzRjk1NzFDNUNENDVBODU2MDE=.XliPyzDSLhGDn7P3qy92RmGP1lIgfIAJJglDPcoAjac6wVCYJ1N0vxnNyM2kP0G65l9LUXgGwedr1mKhMs6c8bWi8hKnnYvjmmfwNQDVfy1hNBtHiJKm6AAaHkfy0GZQbKYewQr3lu1Y06fnkXcLW/sccllHG98OoyKRcbrFrcDTudZlGMVa5/oUS3aEphLJ33Pis3NRSScfThJQKiYzWjX6dvKdtG1o3v2cbHrB+0J0VXLFHUBPN1MBhIy5/MhcHXDPxmq21uVp6L1fp8uk8sAOGpgslhbFfgsu8vxELqs0/NrfNvJn3VVlV7GXDAZ4pEWAKMr/nFXiywLX1ic21OijeiOrub1oRb40QSStdluiEluSV5BxLUQdmjd2hUE5nhlZRrIzv+hysqZMBrqsXykKyA5xf84ka63dZr7JzR7lUFcxx0LZnyRbuDQJks/tmn/jVyOaFer7k3yi3jO208IR7KEk2gVV2OmSkGSo969JyMiq4Voe/dh7tDwvZIK7RcwnI7Vv6V5EguWFNJLmkPSmUR+lA4DdTmxtorpwkM8hQ9uBj9WRb+xzL6y2kJzxRzv6rtcDlFt8Ms8/gJeylI2PHsEojqXTS81nEzxKrEkapurCfhnhnQzBLkrEmcdZaN7BXdWImgLrx/Iv4oBZZSxseCUjhE6dNkGt94MgTwfaUOXLavuQWGYLcf7W+lXQjBsTAKcxnYI68tiKKrRFKJ1jVDdiS/+6Acl4nTWTV46Bh8mAH81gtq8tlnQBs9IW6fcLJfcLYk8QSOudOVHidK3KJydUY60cYfGNMeGJFKmes0E3xFxn7chfwaJANrAp")
//        LexActivator.SetProductId("a0cd6a11-fd66-4d9f-9793-296ae46500ba", LexActivator.LA_USER);
        LexActivator.SetProductId("20d0b481-a6d1-4a98-9552-7752a360d8b0", LexActivator.LA_IN_MEMORY)
        LexActivator.SetLicenseKey("5E994E-FC342D-481A9F-486338-9BAC4D-2952B7")
        Log.d("LicenseManager", "Setting Product Data and Product ID for Lex")
    }

    fun activateLicense(licenseKey: String) {
//        LexActivator.SetProductData("MkM3RTU1Q0I1RjY5MjM0NEQ2ODgwRUQyNUY3N0JEMzg=.8gDH04Kj9jfbVMdZzvqWb5ePwfs1pvbnRRKHKnoXYddw/cVHJkFpLN5rNe63NZgBzMlmFGTeZumW9YODYyqJtnLADohzICEZBB8WOpcH+Cr74v5GV5QEsKxlXWcnnQMnSH4ybnGhlSJwaBY+yFCB5XBYtVIq0JVvr90J9qB+cgyKKWSjxwxuCgly0MgdUz6GjhS1Qrz66xP3CGSVOA/NqO+3kDI0yh+HaCM0/x/3lxkP32J+K/NhFupHYuQP6f+cCcvHpYVgiJvBoRH91/Jg/UjeHDWKRiExYboI60bCYUD75HkILuvpG3icPd2u1dePB/A1PSNqntw+l6K3j4hL/0u0/ATsdYDDpkzC8biJuLCViHu6jf7UtufBFa9Z77isqkNzJshN/hRG3ZbEG/VIOzzCmtSP+iQzLfu5ePgILgYmMsJgiwJTUwtwplljJ5B11GjPgMLLRz1kpSl8Jch/I3VJpwSCM7wUz3Lwfrlwo77ATkHdsFzhrY7IqvFHNtXb+n8rjruKbjeltOwzQlAhwoRBRjL7V2x6VKSkyYTXdYlQ1dCdzosSVkyCcY9y32WOLhTrI4xncURoA0D9f/HrrOlJNPo9izrPqYqO9/BgQvXaF3gpXEBJ7ljkcCf4wsBBltntBmLd1cuJflbHcQoDwrfaEynBNc/2xE3TztouGyIxX1gmhxi2/3d0lT8mo1cssnYWsiONvjsDXnfeRcFaA+/5Fip21qWVbRynC9Vi9pSVEo3O4LWRu5ksbM9fay7+2nOdm+S0Rf3sQjaH+65twwVVWSm2yJqmxmWOV3LxMJSUWboyRFF/2L+YxqnZC1RE");
//        LexActivator.SetProductData("MUUyMENFRTJDMDg3RERBMjZFRjUxQkJDNjY5OTEzQTY=.hvKIPh+4E7srrb4gDMb1xDvtWIzYZ/XdQpd1MZ2JgOe98cG5Z1a7bX73rRI3U4O7cGch/3EPEsvOCE0MEppBqvu3pZLvfiKquv9Oc2ACNL5k//RIvVjC/EW3XfUSEEeBDsaBV56dHieQyNpx6CU0+QSYMG2/d8rSSeiRYd0gXyR17WalnYu1eQvw86ebgbQ+vv3mZVM7rhiy6c6k7JRMZqciwchuwAA3NiH2pXeXVmYfiG6XyLKhLGjBUI2XpBUqiN+OIAvH8M67EgRZwnueC2BNLUzJ9pyHANPyahe0h2cDJ1RM6MhfR/iXNsNqxP1PGX53Bk29FZH3O8zzOaXqBYWnbAn88S73JgUf9+mArfvSAojVJHdjr/j+wHHNKOBEnoQHpyAFBUilzTJNrs7hgpXD139pP14KQLncb0f5Na9CqpmvSlzlaSH/IZcn6I719ZvOpIAnTjHGeD+fUirNNR2mkSmJNVpbA8Sf/AwAYHena/XYrJA3R+k8UiROh1EfwYr7E3xUJEHN4aus72BQnE637zjGuDeMMio46MQcxnj5RNvL/1PsdkpJjxF3tTRlpLN/nx2HI5y0a77RsMeD3iCWBiI6r84wEEEtYnx7ss0IYFE3j9HvoKFMUBE+oRN1yJBPZGoiSG3ISG/rokaP/UmlLjqY+rsNl3ZkpVhptcmwIKQzk41o6Gxa9K5QSiAJxA3ushH/3GP+ASNREcKe/TfOFJlpH0dEwSkBP/rsmFUHtmQkBIRW0n1XeGMBm2gJfWrMsIaUUuL4POdsjGemBLtfMPbWMF3xxUJ3DZoPWxZ7w1bQs/E6uBF8fKDeoaKG")
//        LexActivator.SetProductId("20d0b481-a6d1-4a98-9552-7752a360d8b0", LexActivator.LA_USER)
        initLicense()
        try {
//            LexActivator.SetLicenseKey(licenseKey)
            var status: Int = 10

            try {
                Log.d("LicenseManager", "Setting Status to ActivateLicense()")
                status = LexActivator.ActivateLicense();
            } catch (e:Exception) {
                Log.d("LicenseManager", "Couldn't set status to activate license error $e")
            }
            if (LexActivator.LA_OK == status) {
                Log.d("LicenseManager", "$status License Activated Successfully")

            } else if (LexActivator.LA_EXPIRED == status ) {
                Log.d("LicenseManager", "$status License EXPIRED")
            } else {
                Log.d("LicenseManager", "$status License was not activated successfully. trying other shit")
                try {
                    LexActivator.SetProductId("20d0b481-a6d1-4a98-9552-7752a360d8b0", LexActivator.LA_SYSTEM)
                    status = LexActivator.ActivateLicense();
                } catch (e:Exception) {
                    Log.d("LicenseManager", "$status Other shit didn't work.")
                }
            }
        } catch (e: LexActivatorException) {
            Log.d("LicenseManager", "Fuccck the license didn't activate fuckckkkkkkckkkkcc error code $e")
        }

    }

}