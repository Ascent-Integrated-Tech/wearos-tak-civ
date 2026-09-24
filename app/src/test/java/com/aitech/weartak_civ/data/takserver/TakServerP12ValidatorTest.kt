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
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Base64
import java.util.Date

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

    private fun p12(password: String): String {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=WearTAK Test Client")
        val now = System.currentTimeMillis()
        val certificate = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(
                name,
                BigInteger.ONE,
                Date(now - 60_000),
                Date(now + 86_400_000),
                name,
                keyPair.public
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
        )
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("client", keyPair.private, password.toCharArray(), arrayOf(certificate))
        }
        val bytes = ByteArrayOutputStream().use { output ->
            keyStore.store(output, password.toCharArray())
            output.toByteArray()
        }
        return Base64.getEncoder().encodeToString(bytes)
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
