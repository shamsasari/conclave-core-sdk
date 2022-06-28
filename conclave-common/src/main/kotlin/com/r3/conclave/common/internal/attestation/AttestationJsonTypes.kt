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
import com.fasterxml.jackson.databind.module.SimpleModule
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

// We could avoid the redundant usage of @JsonProperty if we used the Kotlin Jackson module. However that makes shading
// Kotlin more difficult and so we just put up with this minor boilerplate.

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
 * This is only populated if [isvEnclaveQuoteStatus] is either [EpidQuoteStatus.GROUP_OUT_OF_DATE], [EpidQuoteStatus.CONFIGURATION_NEEDED],
 * [EpidQuoteStatus.SW_HARDENING_NEEDED] or [EpidQuoteStatus.CONFIGURATION_AND_SW_HARDENING_NEEDED].
 */
@JsonInclude(NON_NULL)
data class EpidVerificationReport @JsonCreator constructor(
    @JsonProperty("id")
    val id: String,

    @JsonProperty("isvEnclaveQuoteStatus")
    val isvEnclaveQuoteStatus: EpidQuoteStatus,

    @JsonProperty("isvEnclaveQuoteBody")
    @JsonSerialize(using = SgxQuoteSerializer::class)
    @JsonDeserialize(using = SgxQuoteDeserializer::class)
    val isvEnclaveQuoteBody: ByteCursor<SgxQuote>,

    @JsonProperty("platformInfoBlob")
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
    @JsonSerialize(using = Base64Serializer::class)
    @JsonDeserialize(using = Base64Deserializer::class)
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

    private class Base64Serializer : StdSerializer<OpaqueBytes>(OpaqueBytes::class.java) {
        override fun serialize(value: OpaqueBytes, gen: JsonGenerator, provider: SerializerProvider) {
            gen.writeBinary(value.bytes)
        }
    }

    private class Base64Deserializer : StdDeserializer<OpaqueBytes>(OpaqueBytes::class.java) {
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): OpaqueBytes = OpaqueBytes(p.binaryValue)
    }
}

/**
 * https://api.portal.trustedservices.intel.com/documentation#pcs-tcb-info-v2
 *
 * @property signature Signature calculated over [tcbInfo] body without whitespaces using TCB Signing Key
 * i.e: `{"version":2,"issueDate":"2019-07-30T12:00:00Z","nextUpdate":"2019-08-30T12:00:00Z",...}`
 */
data class SignedTcbInfo @JsonCreator constructor(
    @JsonProperty("tcbInfo")
    val tcbInfo: TcbInfo,

    @JsonProperty("signature")
    val signature: OpaqueBytes
)

/**
 * @property version Version of the structure.
 *
 * @property issueDate Date and time the TCB information was created.
 *
 * @property nextUpdate Date and time by which next TCB information will be issued.
 *
 * @property fmspc FMSPC (Family-Model-Stepping-Platform-CustomSKU).
 *
 * @property pceId PCE identifier.
 *
 * @property tcbType Type of TCB level composition that determines TCB level comparison logic.
 *
 * @property tcbEvaluationDataNumber A monotonically increasing sequence number changed when Intel updates the content
 * of the TCB evaluation data set: TCB Info, QE Idenity and QVE Identity. The tcbEvaluationDataNumber update is
 * synchronized across TCB Info for all flavors of SGX CPUs (Family-Model-Stepping-Platform-CustomSKU) and QE/QVE Identity.
 * This sequence number allows users to easily determine when a particular TCB Info/QE Idenity/QVE Identiy superseedes
 * another TCB Info/QE Identity/QVE Identity.
 *
 * @property tcbLevels Sorted list of supported TCB levels for given FMSPC.
 */
data class TcbInfo @JsonCreator constructor(
    @JsonProperty("version")
    val version: Int,

    @JsonProperty("issueDate")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    val issueDate: Instant,

    @JsonProperty("nextUpdate")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    val nextUpdate: Instant,

    @JsonProperty("fmspc")
    val fmspc: OpaqueBytes,

    @JsonProperty("pceId")
    val pceId: OpaqueBytes,

    @JsonProperty("tcbType")
    val tcbType: Int,

    @JsonProperty("tcbEvaluationDataNumber")
    val tcbEvaluationDataNumber: Int,

    @JsonProperty("tcbLevels")
    val tcbLevels: List<TcbLevel>
)

/**
 * @property tcbDate Date and time when the TCB level was certified not to be vulnerable to any issues described in SAs
 * that were originally published on or prior to this date.
 *
 * @property tcbStatus TCB level status.
 *
 * @property advisoryIDs Array of Advisory IDs describing vulnerabilities that this TCB level is vulnerable to.

 */
data class TcbLevel @JsonCreator constructor(
    @JsonProperty("tcb")
    val tcb: Tcb,

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    @JsonProperty("tcbDate")
    val tcbDate: Instant,

    @JsonProperty("tcbStatus")
    val tcbStatus: TcbStatus,

    @JsonProperty("advisoryIDs")
    val advisoryIDs: List<String>? = null
)

enum class TcbStatus : VerificationStatus {
    /** TCB level of the SGX platform is up-to-date. */
    UpToDate,

    /**
     * TCB level of the SGX platform is up-to-date but due to certain issues affecting the platform, additional SW
     * Hardening in the attesting SGX enclaves may be needed.
     */
    SWHardeningNeeded,

    /**
     * TCB level of the SGX platform is up-to-date but additional configuration of SGX platform may be needed.*/
    ConfigurationNeeded,

    /**
     * TCB level of the SGX platform is up-to-date but additional configuration for the platform and SW Hardening in the
     * attesting SGX enclaves may be needed.
     */
    ConfigurationAndSWHardeningNeeded,

    /** TCB level of SGX platform is outdated. */
    OutOfDate,

    /** TCB level of SGX platform is outdated and additional configuration of SGX platform may be needed. */
    OutOfDateConfigurationNeeded,

    /** TCB level of SGX platform is revoked. The platform is not trustworthy. */
    Revoked
}

data class Tcb @JsonCreator constructor(
    @JsonProperty("sgxtcbcomp01svn")
    val sgxtcbcomp01svn: Int,
    @JsonProperty("sgxtcbcomp02svn")
    val sgxtcbcomp02svn: Int,
    @JsonProperty("sgxtcbcomp03svn")
    val sgxtcbcomp03svn: Int,
    @JsonProperty("sgxtcbcomp04svn")
    val sgxtcbcomp04svn: Int,
    @JsonProperty("sgxtcbcomp05svn")
    val sgxtcbcomp05svn: Int,
    @JsonProperty("sgxtcbcomp06svn")
    val sgxtcbcomp06svn: Int,
    @JsonProperty("sgxtcbcomp07svn")
    val sgxtcbcomp07svn: Int,
    @JsonProperty("sgxtcbcomp08svn")
    val sgxtcbcomp08svn: Int,
    @JsonProperty("sgxtcbcomp09svn")
    val sgxtcbcomp09svn: Int,
    @JsonProperty("sgxtcbcomp10svn")
    val sgxtcbcomp10svn: Int,
    @JsonProperty("sgxtcbcomp11svn")
    val sgxtcbcomp11svn: Int,
    @JsonProperty("sgxtcbcomp12svn")
    val sgxtcbcomp12svn: Int,
    @JsonProperty("sgxtcbcomp13svn")
    val sgxtcbcomp13svn: Int,
    @JsonProperty("sgxtcbcomp14svn")
    val sgxtcbcomp14svn: Int,
    @JsonProperty("sgxtcbcomp15svn")
    val sgxtcbcomp15svn: Int,
    @JsonProperty("sgxtcbcomp16svn")
    val sgxtcbcomp16svn: Int,
    @JsonProperty("pcesvn")
    val pcesvn: Int
)

/**
 * https://api.portal.trustedservices.intel.com/documentation#pcs-qe-identity-v2
 *
 * @property signature Signature calculated over qeIdentity body (without whitespaces) using TCB Info Signing Key.
 */
data class SignedEnclaveIdentity @JsonCreator constructor(
    @JsonProperty("enclaveIdentity")
    val enclaveIdentity: EnclaveIdentity,

    @JsonProperty("signature")
    val signature: OpaqueBytes
)

/**
 * @property id Identifier of the SGX Enclave issued by Intel. Supported values are QE and QVE.
 *
 * @property version Version of the structure.
 *
 * @property issueDate Date and time the QE Identity information was created.
 *
 * @property nextUpdate Date and time by which next QE identity information will be issued.
 *
 * @property tcbEvaluationDataNumber A monotonically increasing sequence number changed when Intel updates the content
 * of the TCB evaluation data set: TCB Info, QE Idenity and QVE Identity. The tcbEvaluationDataNumber update is
 * synchronized across TCB Info for all flavors of SGX CPUs (Family-Model-Stepping-Platform-CustomSKU) and QE/QVE Identity.
 * This sequence number allows users to easily determine when a particular TCB Info/QE Idenity/QVE Identiy superseedes
 * another TCB Info/QE Identity/QVE Identity.
 *
 * @property miscselect miscselect "golden" value (upon applying mask).
 *
 * @property miscselectMask Mask to be applied to [miscselect] value retrieved from the platform.
 *
 * @property attributes attributes "golden" value (upon applying mask).
 *
 * @property attributesMask Mask to be applied to attributes value retrieved from the platform.
 *
 * @property mrsigner mrsigner hash.
 *
 * @property isvprodid Enclave Product ID.
 *
 * @property tcbLevels Sorted list of supported Enclave TCB levels for given QE.
 */
data class EnclaveIdentity @JsonCreator constructor(
    @JsonProperty("id")
    val id: String,

    @JsonProperty("version")
    val version: Int,

    @JsonProperty("issueDate")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    val issueDate: Instant,

    @JsonProperty("nextUpdate")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    val nextUpdate: Instant,

    @JsonProperty("tcbEvaluationDataNumber")
    val tcbEvaluationDataNumber: Int,

    @JsonProperty("miscselect")
    val miscselect: OpaqueBytes,

    @JsonProperty("miscselectMask")
    val miscselectMask: OpaqueBytes,

    @JsonProperty("attributes")
    val attributes: OpaqueBytes,

    @JsonProperty("attributesMask")
    val attributesMask: OpaqueBytes,

    @JsonProperty("mrsigner")
    val mrsigner: OpaqueBytes,

    @JsonProperty("isvprodid")
    val isvprodid: Int,

    @JsonProperty("tcbLevels")
    val tcbLevels: List<EnclaveTcbLevel>
)

/**
 * @property tcbDate Date and time when the TCB level was certified not to be vulnerable to any issues described in SAs
 * that were originally published on or prior to this date.
 *
 * @property tcbStatus TCB level status.
 */
data class EnclaveTcbLevel @JsonCreator constructor(
    @JsonProperty("tcb")
    val tcb: EnclaveTcb,

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    @JsonProperty("tcbDate")
    val tcbDate: Instant,

    @JsonProperty("tcbStatus")
    val tcbStatus: EnclaveTcbStatus
)

enum class EnclaveTcbStatus {
    /** TCB level of the SGX platform is up-to-date.. */
    UpToDate,

    /** TCB level of SGX platform is outdated. */
    OutOfDate,

    /** TCB level of SGX platform is revoked. The platform is not trustworthy. */
    Revoked
}

/**
 * @property isvsvn SGX Enclave’s ISV SVN.
 */
data class EnclaveTcb @JsonCreator constructor(
    @JsonProperty("isvsvn")
    val isvsvn: Int
)

val attestationObjectMapper = ObjectMapper().apply {
    registerModule(SimpleModule().apply {
        addDeserializer(OpaqueBytes::class.java, Base16Deserializer())
        addSerializer(OpaqueBytes::class.java, ToStringSerializer.instance)
    })
    registerModule(JavaTimeModule())
}

private class Base16Deserializer : StdDeserializer<OpaqueBytes>(OpaqueBytes::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): OpaqueBytes {
        return OpaqueBytes.parse(p.valueAsString)
    }
}
