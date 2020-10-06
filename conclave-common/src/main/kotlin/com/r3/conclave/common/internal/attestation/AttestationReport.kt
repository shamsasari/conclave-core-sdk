package com.r3.conclave.common.internal.attestation

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.r3.conclave.common.OpaqueBytes
import com.r3.conclave.common.SHA256Hash
import com.r3.conclave.common.internal.ByteCursor
import com.r3.conclave.common.internal.Cursor
import com.r3.conclave.common.internal.SgxQuote
import com.r3.conclave.common.internal.SgxSignedQuote
import java.time.Instant
import java.util.*

/**
 * Definitions taken from https://api.trustedservices.intel.com/documents/sgx-attestation-api-spec.pdf.
 *
 * The Attestation Verification Report is a data structure returned by the Attestation Service for Intel® SGX to the
 * Service Provider. It contains a cryptographically signed report of verification of the identity of ISV enclave and
 * the Trusted Computing Base (TCB) of the platform.
 *
 * @property id Representation of unique identifier of the Attestation Verification Report.
 *
 * @property isvEnclaveQuoteBody Body of [SgxSignedQuote] as received by the attestion service.
 *
 * @property timestamp Representation of date and time the Attestation Verification Report was created.
 *
 * @property version Integer that denotes the version of the Verification Attestation Evidence API that has been used to
 * generate the report (currently set to 3). Service Providers should verify this field to confirm that the report was
 * generated by the intended API version, instead of a different API version with potentially different security properties.
 *
 * @property revocationReason Integer corresponding to revocation reason code for a revoked EPID group listed in EPID
 * Group CRL. Allowed values are described in [RFC 5280](https://www.ietf.org/rfc/rfc5280.txt). This field will only be
 * present if value of isvEnclaveQuoteStatus is equal to GROUP_REVOKED.
 *
 * @property pseManifestStatus This field will only be present if the SGX Platform Service Security Property Descriptor
 * (pseManifest) is provided in Attestation Evidence Payload and isvEnclaveQuoteStatus is equal to OK, GROUP_OUT_OF_DATE
 * or CONFIGURATION_NEEDED.
 *
 * @property pseManifestHash SHA-256 calculated over SGX Platform Service Security Property Descriptor as received in
 * Attestation Evidence Payload. This field will only be present if pseManifest field is provided in Attestation Evidence
 * Payload.
 *
 * @property platformInfoBlob A TLV containing an opaque binary blob that the Service Provider and the ISV SGX Application
 * are supposed to forward to SGX Platform SW. This field will only be present if one the following conditions is met:
 * * isvEnclaveQuoteStatus is equal to GROUP_REVOKED, GROUP_OUT_OF_DATE or CONFIGURATION_NEEDED,
 * * pseManifestStatus is equal to one of the following values: OUT_OF_DATE, REVOKED or RL_VERSION_MISMATCH.
 *
 * @property nonce A string that represents a nonce value provided by SP in Attestation Evidence Payload. This field will
 * only be present if nonce field is provided in Attestation Evidence Payload.
 *
 * @property epidPseudonym Byte array representing EPID Pseudonym that consists of the concatenation of EPID B (64 bytes)
 * & EPID K (64 bytes) components of EPID signature. If two linkable EPID signatures for an EPID Group have the same EPID
 * Pseudonym, the two signatures were generated using the same EPID private key. This field will only be present if
 * Attestation Evidence Payload contains Quote with linkable EPID signature.
 *
 * @property advisoryURL URL to Intel® Product Security Center Advisories page that provides additional information on
 * SGX-related security issues. IDs of advisories for specific issues that may affect the attested platform are conveyed
 * by [advisoryIDs].
 *
 * @property advisoryIDs List of advisory IDs that can be searched on the page indicated by [advisoryURL]. Advisory IDs
 * refer to articles providing insight into enclave-related security issues that may affect the attested platform.
 *
 * This is only populated if [isvEnclaveQuoteStatus] is either [QuoteStatus.GROUP_OUT_OF_DATE], [QuoteStatus.CONFIGURATION_NEEDED],
 * [QuoteStatus.SW_HARDENING_NEEDED] or [QuoteStatus.CONFIGURATION_AND_SW_HARDENING_NEEDED].
 */
// We could avoid the redundant usage of @JsonProperty if we used the Kotlin Jackson module. However that makes shading
// Kotlin more difficult and so we just put up with this minor boilerplate.
@JsonInclude(NON_NULL)
data class AttestationReport @JsonCreator constructor(
        @JsonProperty("id")
        val id: String,

        @JsonProperty("isvEnclaveQuoteStatus")
        val isvEnclaveQuoteStatus: QuoteStatus,

        @JsonProperty("isvEnclaveQuoteBody")
        @JsonSerialize(using = SgxQuoteSerializer::class)
        @JsonDeserialize(using = SgxQuoteDeserializer::class)
        val isvEnclaveQuoteBody: ByteCursor<SgxQuote>,

        @JsonProperty("platformInfoBlob")
        @JsonSerialize(using = ToStringSerializer::class)
        @JsonDeserialize(using = Base16Deserializer::class)
        val platformInfoBlob: OpaqueBytes? = null,

        @JsonProperty("revocationReason")
        val revocationReason: Int? = null,

        @JsonProperty("pseManifestStatus")
        val pseManifestStatus: ManifestStatus? = null,

        @JsonProperty("pseManifestHash")
        @JsonSerialize(using = ToStringSerializer::class)
        @JsonDeserialize(using = Sha256Deserializer::class)
        val pseManifestHash: SHA256Hash? = null,

        @JsonProperty("nonce")
        val nonce: String? = null,

        @JsonProperty("epidPseudonym")
        @JsonSerialize(using = OpaqueBytesSerializer::class)
        @JsonDeserialize(using = OpaqueBytesDeserializer::class)
        val epidPseudonym: OpaqueBytes? = null,

        @JsonProperty("advisoryURL")
        val advisoryURL: String? = null,

        @JsonProperty("advisoryIDs")
        val advisoryIDs: List<String>? = null,

        @JsonProperty("timestamp")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS", timezone = "UTC")
        val timestamp: Instant,

        @JsonProperty("version")
        val version: Int
) {
    private class Base16Deserializer : StdDeserializer<OpaqueBytes>(OpaqueBytes::class.java) {
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): OpaqueBytes {
            return OpaqueBytes.parse(p.valueAsString)
        }
    }

    private class SgxQuoteSerializer : JsonSerializer<ByteCursor<SgxQuote>>() {
        override fun serialize(value: ByteCursor<SgxQuote>, gen: JsonGenerator, provider: SerializerProvider) {
            gen.writeBinary(value.bytes)
        }
    }

    private class SgxQuoteDeserializer : JsonDeserializer<ByteCursor<SgxQuote>>() {
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): ByteCursor<SgxQuote> {
            return Cursor.wrap(SgxQuote, p.binaryValue)
        }
    }

    private class Sha256Deserializer : StdDeserializer<SHA256Hash>(SHA256Hash::class.java) {
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): SHA256Hash {
            return SHA256Hash.parse(p.valueAsString)
        }
    }

    private class OpaqueBytesSerializer : StdSerializer<OpaqueBytes>(OpaqueBytes::class.java) {
        override fun serialize(value: OpaqueBytes, gen: JsonGenerator, provider: SerializerProvider) {
            gen.writeBinary(value.bytes)
        }
    }

    private class OpaqueBytesDeserializer : StdDeserializer<OpaqueBytes>(OpaqueBytes::class.java) {
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): OpaqueBytes = OpaqueBytes(p.binaryValue)
    }
}

// fields are nullable for easier unit-testing
@JsonInclude(NON_NULL)
data class TcbInfoSigned @JsonCreator constructor(
        @JsonProperty("tcbInfo")
        val tcbInfo: TcbInfo? = null,

        @JsonProperty("signature")
        val signature: String? = null
)

@JsonInclude(NON_NULL)
data class TcbInfo @JsonCreator constructor(
        @JsonProperty("version")
        val version: Int? = null,

        @JsonProperty("issueDate")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss", timezone = "UTC")
        val issueDate: Date? = null,

        @JsonProperty("nextUpdate")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss", timezone = "UTC")
        val nextUpdate: Date? = null,

        @JsonProperty("fmspc")
        val fmspc: String? = null,

        @JsonProperty("pceId")
        val pceId: String? = null,

        @JsonProperty("tcbType")
        val tcbType: Int? = null,

        @JsonProperty("tcbEvaluationDataNumber")
        val tcbEvaluationDataNumber: Int? = null,

        @JsonProperty("tcbLevels")
        val tcbLevels: List<TcbLevel>? = null
)

data class TcbLevel @JsonCreator constructor(
        @JsonProperty("tcb")
        val tcb: Tcb? = null,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss", timezone = "UTC")
        @JsonProperty("tcbDate")
        val tcbDate: Date? = null,

        @JsonProperty("tcbStatus")
        val tcbStatus: String? = null
)

data class Tcb @JsonCreator constructor(
        @JsonProperty("sgxtcbcomp01svn")
        val sgxtcbcomp01svn: Int?,
        @JsonProperty("sgxtcbcomp02svn")
        val sgxtcbcomp02svn: Int?,
        @JsonProperty("sgxtcbcomp03svn")
        val sgxtcbcomp03svn: Int?,
        @JsonProperty("sgxtcbcomp04svn")
        val sgxtcbcomp04svn: Int?,
        @JsonProperty("sgxtcbcomp05svn")
        val sgxtcbcomp05svn: Int?,
        @JsonProperty("sgxtcbcomp06svn")
        val sgxtcbcomp06svn: Int?,
        @JsonProperty("sgxtcbcomp07svn")
        val sgxtcbcomp07svn: Int?,
        @JsonProperty("sgxtcbcomp08svn")
        val sgxtcbcomp08svn: Int?,
        @JsonProperty("sgxtcbcomp09svn")
        val sgxtcbcomp09svn: Int?,
        @JsonProperty("sgxtcbcomp10svn")
        val sgxtcbcomp10svn: Int?,
        @JsonProperty("sgxtcbcomp11svn")
        val sgxtcbcomp11svn: Int?,
        @JsonProperty("sgxtcbcomp12svn")
        val sgxtcbcomp12svn: Int?,
        @JsonProperty("sgxtcbcomp13svn")
        val sgxtcbcomp13svn: Int?,
        @JsonProperty("sgxtcbcomp14svn")
        val sgxtcbcomp14svn: Int?,
        @JsonProperty("sgxtcbcomp15svn")
        val sgxtcbcomp15svn: Int?,
        @JsonProperty("sgxtcbcomp16svn")
        val sgxtcbcomp16svn: Int?,
        @JsonProperty("pcesvn")
        val pcesvn: Int?
)

data class EnclaveIdentitySigned @JsonCreator constructor(
        @JsonProperty("enclaveIdentity")
        val enclaveIdentity: EnclaveIdentity? = null,

        @JsonProperty("signature")
        val signature: String? = null
)

data class EnclaveIdentity @JsonCreator constructor(
        @JsonProperty("id")
        val id: String? = null,

        @JsonProperty("version")
        val version: Int? = null,

        @JsonProperty("issueDate")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss", timezone = "UTC")
        val issueDate: Date? = null,

        @JsonProperty("nextUpdate")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss", timezone = "UTC")
        val nextUpdate: Date? = null,

        @JsonProperty("tcbEvaluationDataNumber")
        val tcbEvaluationDataNumber: Int? = null,

        @JsonProperty("miscselect")
        val miscselect: String? = null,

        @JsonProperty("miscselectMask")
        val miscselectMask: String? = null,

        @JsonProperty("attributes")
        val attributes: String? = null,

        @JsonProperty("attributesMask")
        val attributesMask: String? = null,

        @JsonProperty("mrsigner")
        val mrsigner: String? = null,

        @JsonProperty("isvprodid")
        val isvprodid: Int? = null,

        @JsonProperty("tcbLevels")
        val tcbLevels: List<TcbLevelShort>? = null
)

data class TcbLevelShort @JsonCreator constructor(
        @JsonProperty("tcb")
        val tcb: TcbShort? = null,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss", timezone = "UTC")
        @JsonProperty("tcbDate")
        val tcbDate: Date? = null,

        @JsonProperty("tcbStatus")
        val tcbStatus: String? = null
)

data class TcbShort @JsonCreator constructor(
        @JsonProperty("isvsvn")
        val isvsvn: Int
)

val attestationObjectMapper = ObjectMapper().apply { registerModule(JavaTimeModule()) }
