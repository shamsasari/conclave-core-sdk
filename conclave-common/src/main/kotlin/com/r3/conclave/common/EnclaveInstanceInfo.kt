package com.r3.conclave.common

import com.r3.conclave.common.internal.*
import com.r3.conclave.common.internal.attestation.AttestationResponse
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory

/**
 * Contains serializable information about an instantiated enclave running on a
 * specific machine, with the measurement and instance signing key verified by
 * remote attestation. The remote attestation infrastructure backing all trusted
 * computing schemes is what gives you confidence that the data in this object is
 * correct and can be trusted, as long as [securityInfo] and [enclaveInfo]
 * match what you expect.
 *
 * An [EnclaveInstanceInfo] should be fetched from the host via some app specific
 * mechanism, such as via an HTTP request, a directory service lookup, shared file
 * etc.
 *
 */
interface EnclaveInstanceInfo {
    /** Contains information about the enclave code that was loaded. */
    val enclaveInfo: EnclaveInfo

    /**
     * A key used by the enclave to digitally sign static data structures.
     * This is not the same as the enclave code signing key, which just links
     * a specific enclave file to its author.
     */
    val dataSigningKey: PublicKey

    /**
     * Returns a [Signature] object pre-initialised with [dataSigningKey], ready for the verification of digitial signatures
     * generated by the enclave.
     */
    fun verifier(): Signature

    // TODO encryptionKey, to be added as part of Mail

    /**
     * Exposes how secure the remote enclave is currently considered to be.
     */
    val securityInfo: EnclaveSecurityInfo

    /** Serializes this object to a custom format and returns the byte array. */
    fun serialize(): ByteArray

    /**
     * Suppress kotlin specific companion objects from our API documentation.
     * The public items within the object are still published in the documentation.
     * @suppress
     */
    companion object {
        private val magic = "EII".toByteArray()
        private val signatureScheme = SignatureSchemeEdDSA()

        /**
         * Deserializes this object from its custom format.
         *
         * @throws IllegalArgumentException If the bytes are invalid.
         */
        @JvmStatic
        fun deserialize(from: ByteArray): EnclaveInstanceInfo {
            return from.deserialise {
                require(readNBytes(magic.size).contentEquals(magic)) { "Not EnclaveInstanceInfo bytes" }
                val dataSigningKey = readIntLengthPrefixBytes().let(signatureScheme::decodePublicKey)
                val reportBytes = readIntLengthPrefixBytes()
                val signature = readIntLengthPrefixBytes()
                val certPath = readIntLengthPrefixBytes().inputStream().let(CertificateFactory.getInstance("X.509")::generateCertPath)
                val enclaveMode = read().let { EnclaveMode.values()[it] }
                // New fields need to be behind an availablity check before being read. Use dis.available() to check if there
                // are more bytes available and only parse them if there are. If not then provide defaults.
                EnclaveInstanceInfoImpl(
                        dataSigningKey,
                        AttestationResponse(reportBytes, signature, certPath),
                        enclaveMode
                )
            }
        }
    }
}
