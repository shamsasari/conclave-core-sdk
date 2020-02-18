package com.r3.conclave.host.internal

import com.fasterxml.jackson.databind.ObjectMapper
import com.r3.sgx.core.common.ByteCursor
import com.r3.sgx.core.common.SgxSignedQuote
import java.io.InputStream
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.*

/**
 * A mock implementation of [AttestationService] permitting to reproduce the attestation flow in simulation mode
 */
class MockAttestationService : AttestationService {
    override fun requestSignature(signedQuote: ByteCursor<SgxSignedQuote>): AttestationResponse {
        val reportBytes = AttestationReport(
                id = "MOCK-RESPONSE: ${UUID.randomUUID()}",
                isvEnclaveQuoteStatus = QuoteStatus.OK,
                isvEnclaveQuoteBody = signedQuote[signedQuote.encoder.quote],
                timestamp = Instant.now(),
                version = 3
        ).let(reportMapper::writeValueAsBytes)

        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(signingKey)
            update(reportBytes)
        }.sign()

        return AttestationResponse(reportBytes, signature, certPath)
    }

    companion object {
        private val signingKey = readResource("mock-as.key") {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(it.readBytes()))
        }
        private val certPath = readResource("mock-as-certificate.pem") { it.reader().readText().parsePemCertPath() }

        private val reportMapper = AttestationReport.register(ObjectMapper())

        private inline fun <R> readResource(name: String, block: (InputStream) -> R): R {
            val stream = checkNotNull(MockAttestationService::class.java.getResourceAsStream("/mock-as/$name")) {
                "Cannot find resource file $name"
            }
            return stream.use(block)
        }
    }
}
