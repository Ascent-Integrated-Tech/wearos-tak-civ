/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */
package com.aitech.weartak_civ.data.secure.crypto

object CryptoConfig {
    // Increment when you change envelope format or algorithms.
    const val CURRENT_CRYPTO_VERSION: Int = 1

    // Tink AEAD is the high-level primitive; actual algorithm is embedded in the key template.
    // (e.g., AES256_GCM or AES256_GCM_SIV depending on your choice).
    const val KEYSET_PREFS_NAME: String = "weartak_secure_keysets"
}