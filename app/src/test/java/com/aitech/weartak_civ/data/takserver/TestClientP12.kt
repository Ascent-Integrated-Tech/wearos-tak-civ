package com.aitech.weartak_civ.data.takserver

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Base64
import java.util.Date

internal object TestClientP12 {
    fun p12(password: String): String {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=WearTAK Test Client")
        val now = System.currentTimeMillis()
        val certificate = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(
                name, BigInteger.ONE, Date(now - 60_000), Date(now + 86_400_000),
                name, keyPair.public
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
}
