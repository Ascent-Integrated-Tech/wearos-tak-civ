/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.data.takserver

import com.aitech.weartak_civ.data.DataRepository.TakServerItem
import com.aitech.weartak_civ.data.takserver.TestClientP12.p12
import org.junit.Assert.assertThrows
import org.junit.Test

class TakServerP12ValidatorTest {
    @Test
    fun acceptsLoadableClientP12() {
        TakServerP12Validator.validate(listOf(server(p12("correct"), "correct")))
    }

    @Test
    fun rejectsWrongPassword() {
        assertThrows(TakServerP12ValidationException::class.java) {
            TakServerP12Validator.validate(listOf(server(p12("correct"), "wrong")))
        }
    }

    @Test
    fun rejectsMalformedBase64() {
        assertThrows(TakServerP12ValidationException::class.java) {
            TakServerP12Validator.validate(listOf(server("not-base64!", "correct")))
        }
    }

    @Test
    fun rejectsMissingP12() {
        assertThrows(TakServerP12ValidationException::class.java) {
            TakServerP12Validator.validate(listOf(server("", "correct")))
        }
    }

    private fun server(p12Cert: String, p12Password: String) = TakServerItem(
        isEnabled = true,
        name = "TAK",
        address = "tak.example",
        port = 8089,
        isP12Cert = true,
        username = "",
        password = "",
        p12Cert = p12Cert,
        p12CertPassword = p12Password,
        serverId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    )
}
