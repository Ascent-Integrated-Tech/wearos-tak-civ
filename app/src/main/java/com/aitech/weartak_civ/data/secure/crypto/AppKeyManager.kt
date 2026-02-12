/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.data.secure.crypto

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager

class AppKeyManager(private val context: Context) {

    init {
        AeadConfig.register()
    }

    /**
     * Returns an AEAD bound to a particular keyset name and Keystore alias.
     * keysetName: unique per "domain" (sitx, takserver, etc.)
     * keystoreAlias: unique per "domain" and versioned when you rotate.
     */
    fun getAead(keysetName: String, keystoreAlias: String): Aead {
        val masterKeyUri = "android-keystore://$keystoreAlias"

        val handle: KeysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(context, keysetName, CryptoConfig.KEYSET_PREFS_NAME)
            .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
            .withMasterKeyUri(masterKeyUri)
            .build()
            .keysetHandle

        return handle.getPrimitive(Aead::class.java)
    }
}