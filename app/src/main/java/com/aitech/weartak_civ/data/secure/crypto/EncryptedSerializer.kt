/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.data.secure.crypto

import android.util.Base64
import com.google.crypto.tink.Aead
import java.nio.ByteBuffer

object EncryptedSerializer {

    fun encryptToBase64(aead: Aead, plaintext: String, aad: ByteArray): String {
        val pt = plaintext.toByteArray(Charsets.UTF_8)
        val ct = aead.encrypt(pt, aad)

        val bb = ByteBuffer.allocate(4 + ct.size)
        bb.putInt(CryptoConfig.CURRENT_CRYPTO_VERSION)
        bb.put(ct)

        return Base64.encodeToString(bb.array(), Base64.NO_WRAP)
    }

    fun decryptFromBase64(aead: Aead, encoded: String, aad: ByteArray): DecryptResult {
        return runCatching {
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            val bb = ByteBuffer.wrap(raw)

            val version = bb.int
            val ct = ByteArray(bb.remaining())
            bb.get(ct)

            val pt = aead.decrypt(ct, aad)
            DecryptResult.Success(version, pt.toString(Charsets.UTF_8))
        }.getOrElse { DecryptResult.Failure(it) }
    }

    sealed interface DecryptResult {
        data class Success(val version: Int, val plaintext: String) : DecryptResult
        data class Failure(val error: Throwable) : DecryptResult
    }
}