package com.r3.conclave.host

import com.r3.conclave.common.EnclaveInstanceInfo
import com.r3.conclave.common.internal.EnclaveInstanceInfoImpl
import com.r3.conclave.enclave.Enclave
import com.r3.conclave.internaltesting.throwableWithMailCorruptionErrorMessage
import com.r3.conclave.mail.*
import com.r3.conclave.testing.MockHost
import com.r3.conclave.testing.internal.MockEnclaveEnvironment
import com.r3.conclave.testing.internal.MockInternals
import com.r3.conclave.utilities.internal.deserialise
import com.r3.conclave.utilities.internal.readIntLengthPrefixBytes
import com.r3.conclave.utilities.internal.writeData
import com.r3.conclave.utilities.internal.writeIntLengthPrefixBytes
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class MailHostTest {
    companion object {
        private val messageBytes = "message".toByteArray()
    }

    private val keyPair = Curve25519KeyPairGenerator().generateKeyPair()
    private val echo by lazy { MockHost.loadMock<MailEchoEnclave>() }
    private val noop by lazy { MockHost.loadMock<NoopEnclave>() }

    @AfterEach
    fun reset() {
        MockEnclaveEnvironment.platformReset()
    }

    @Test
    fun `encrypt and deliver mail`() {
        echo.start(null, null, null, null)
        val mail: MutableMail = buildMail(echo)
        var response: ByteArray? = null
        echo.deliverMail(1, mail.encrypt()) { bytes ->
            response = bytes
            null  // No response back to enclave.
        }
        response!!.deserialise {
            assertArrayEquals(messageBytes, readIntLengthPrefixBytes())
            assertEquals(1, readInt())
        }
    }

    @Test
    fun `deliver mail and answer enclave`() {
        echo.start(null, null, null, null)
        val mail: MutableMail = buildMail(echo)
        // In response to the delivered mail, the enclave sends us a local message, and we send a local message back.
        // It asserts the answer we give is as expected.
        echo.deliverMail(1, mail.encrypt()) { "an answer".toByteArray() }
    }

    @Test
    fun `mail acknowledgement`() {
        var acknowledgementID: Long? = null
        echo.start(null, null, object : EnclaveHost.MailCallbacks {
            override fun acknowledgeMail(mailID: Long) {
                acknowledgementID = mailID
            }
        }, null)
        val mail: MutableMail = buildMail(echo)
        // First delivery doesn't acknowledge because we don't tell it to.
        echo.deliverMail(1, mail.encrypt()) { null }
        assertNull(acknowledgementID)
        // Try again and this time we'll get an ack.
        mail.incrementSequenceNumber()
        echo.deliverMail(2, mail.encrypt()) { "acknowledge".toByteArray() }
        assertEquals(2, acknowledgementID!!)
    }

    @Test
    fun `sequence numbers`() {
        // Verify that the enclave rejects a replay of the same message, or out of order delivery.
        noop.start(null, null, null, null)
        val encrypted0 = buildMail(noop, "message 0".toByteArray()).encrypt()
        val encrypted1 = buildMail(noop, "message 1".toByteArray()).also { it.sequenceNumber = 1 }.encrypt()
        val encrypted2 = buildMail(noop, "message 2".toByteArray()).also { it.sequenceNumber = 2 }.encrypt()
        val encrypted50 = buildMail(noop, "message 50".toByteArray()).also { it.sequenceNumber = 50 }.encrypt()
        // Deliver message 1.
        noop.deliverMail(100, encrypted0)
        // Cannot deliver message 2 twice even with different IDs.
        noop.deliverMail(100, encrypted1)
        var msg = assertThrows<RuntimeException> { noop.deliverMail(100, encrypted1) }.message!!
        assertTrue("Highest sequence number seen is 1, attempted delivery of 1" in msg) { msg }
        // Cannot now re-deliver message 1 because the sequence number would be going backwards.
        msg = assertThrows<RuntimeException> { noop.deliverMail(100, encrypted0) }.message!!
        assertTrue("Highest sequence number seen is 1, attempted delivery of 0" in msg) { msg }
        // Can deliver message 3
        noop.deliverMail(101, encrypted2)
        // Seq nums may not have gaps.
        msg = assertThrows<RuntimeException> { noop.deliverMail(102, encrypted50) }.message!!
        assertTrue("Highest sequence number seen is 2, attempted delivery of 50" in msg) { msg }

        // Seq nums of different topics are independent
        val secondTopic = buildMail(noop).also { it.topic = "another-topic" }.encrypt()
        noop.deliverMail(100, secondTopic)
    }

    @Test
    fun corruption() {
        // Check the enclave correctly rejects messages with corrupted headers or bodies.
        noop.start(null, null, null, null)
        val mail = buildMail(noop)
        val encrypted = mail.encrypt()
        for (i in encrypted.indices) {
            encrypted[i]++
            assertThatThrownBy {
                noop.deliverMail(i.toLong(), encrypted)
            }.`is`(throwableWithMailCorruptionErrorMessage)
            encrypted[i]--
        }
    }

    @Test
    fun routingHint() {
        // Make a call into enclave1, which then requests sending a mail to a client with its routing hint set. Tests
        // posting mail from inside a local call using an EnclaveInstanceInfo.
        class Enclave1 : Enclave() {
            override fun receiveMail(id: Long, mail: EnclaveMail) {
                val outbound = createMail(mail.authenticatedSender!!, "hello".toByteArray())
                postMail(outbound, mail.from!!)
                acknowledgeMail(id)
            }
        }
        val host = MockHost.loadMock<Enclave1>()
        host.start(null, null, object : EnclaveHost.MailCallbacks {
            override fun postMail(encryptedBytes: ByteArray, routingHint: String?) {
                assertEquals("bob", routingHint!!)
                val message: EnclaveMail = Mail.decrypt(encryptedBytes, keyPair.private)
                assertEquals("hello", String(message.bodyAsBytes))
            }
        }, null)
        val messageFromBob = buildMail(host)
        messageFromBob.from = "bob"
        host.deliverMail(1, messageFromBob.encrypt())
    }

    @ParameterizedTest
    @EnumSource
    fun `enclave can read mail targeted for older platform version`(api: CreateMailApi) {
        val enclave1 = MockHost.loadMock<CreateMailEnclave>()
        enclave1.start(null, null, null, null)
        val oldEncryptedMail = api.createMail("secret".toByteArray(), enclave1)
        enclave1.close()

        // Shutdown the enclave and "update" the platform so that we have a new CPUSVN. The new enclave's (default)
        // encryption key will be different from its old one, but we still expect the enclave to be able to decrypt it.
        MockEnclaveEnvironment.platformUpdate()

        val enclave2 = MockHost.loadMock<CreateMailEnclave>()
        enclave2.start(null, null, null, null)
        var decryptedByEnclave: String? = null
        enclave2.deliverMail(1, oldEncryptedMail) { bytes ->
            decryptedByEnclave = String(bytes)
            null
        }

        assertThat(decryptedByEnclave).isEqualTo("terces")
    }

    @ParameterizedTest
    @EnumSource
    fun `enclave cannot read mail targeted for newer platform version`(api: CreateMailApi) {
        // Imagine the current platform version has a bug in it and so we update and the client creates mail from that.
        MockEnclaveEnvironment.platformUpdate()
        val enclave1 = MockHost.loadMock<CreateMailEnclave>()
        enclave1.start(null, null, null, null)
        val newEncryptedMail = api.createMail("secret".toByteArray(), enclave1)
        enclave1.close()

        // Let's revert the update and return the platform to its insecure version.
        MockEnclaveEnvironment.platformDowngrade()

        val enclave2 = MockHost.loadMock<CreateMailEnclave>()
        enclave2.start(null, null, null, null)
        assertThatThrownBy {
            enclave2.deliverMail(1, newEncryptedMail) { null }
        }.hasMessageContaining("SGX_ERROR_INVALID_CPUSVN")
    }

    @ParameterizedTest
    @EnumSource
    fun `enclave with higher revocation level can read older mail`(api: CreateMailApi) {
        val oldEnclave = MockInternals.createMock(CreateMailEnclave::class.java, isvProdId = 1, isvSvn = 1)
        oldEnclave.start(null, null, null, null)
        val oldEncryptedMail = api.createMail("secret!".toByteArray(), oldEnclave)
        oldEnclave.close()

        val newEnclave = MockInternals.createMock(CreateMailEnclave::class.java, isvProdId = 1, isvSvn = 2)
        newEnclave.start(null, null, null, null)
        var decryptedByEnclave: String? = null
        newEnclave.deliverMail(1, oldEncryptedMail) { bytes ->
            decryptedByEnclave = String(bytes)
            null
        }

        assertThat(decryptedByEnclave).isEqualTo("!terces")
    }

    @ParameterizedTest
    @EnumSource
    fun `enclave with lower revocation level cannot read newer mail`(api: CreateMailApi) {
        val newEnclave = MockInternals.createMock(CreateMailEnclave::class.java, isvProdId = 1, isvSvn = 2)
        newEnclave.start(null, null, null, null)
        val newEncryptedMail = api.createMail("secret!".toByteArray(), newEnclave)
        newEnclave.close()

        val oldEnclave = MockInternals.createMock(CreateMailEnclave::class.java, isvProdId = 1, isvSvn = 1)
        oldEnclave.start(null, null, null, null)
        assertThatThrownBy {
            oldEnclave.deliverMail(1, newEncryptedMail) { null }
        }.hasMessageContaining("SGX_ERROR_INVALID_ISVSVN")
    }

    private fun buildMail(host: MockHost<*>, body: ByteArray = messageBytes): MutableMail {
        val mail = host.enclaveInstanceInfo.createMail(body)
        mail.topic = "topic-123"
        mail.sequenceNumber = 0
        mail.privateKey = keyPair.private
        return mail
    }

    class NoopEnclave : Enclave() {
        override fun receiveMail(id: Long, mail: EnclaveMail) {
        }
    }

    // Receives mail, decrypts it and gives the body back to the host.
    class MailEchoEnclave : Enclave() {
        override fun receiveMail(id: Long, mail: EnclaveMail) {
            val answer: ByteArray? = callUntrustedHost(writeData {
                writeIntLengthPrefixBytes(mail.bodyAsBytes)
                writeInt(id.toInt())
            })
            when (val str = answer?.let { String(it) }) {
                "acknowledge" -> acknowledgeMail(id)
                "an answer" -> return
                "post" -> postMail(createMail(Curve25519PublicKey(mail.bodyAsBytes), "sent to second enclave".toByteArray()), "routing hint")
                null -> return
                else -> throw IllegalStateException(str)
            }
        }
    }

    /**
     * This enclave intentionally uses [Enclave.createMail] (as apposed to `enclaveInstanceInfo.createMail`) to create
     * a mail to itself.
     */
    class CreateMailEnclave : Enclave() {
        // Encrypt
        override fun receiveFromUntrustedHost(bytes: ByteArray): ByteArray {
            return createMail(
                    to = (enclaveInstanceInfo as EnclaveInstanceInfoImpl).encryptionKey,
                    body = bytes.reversedArray()
            ).encrypt()
        }
        // Decrypt
        override fun receiveMail(id: Long, mail: EnclaveMail) {
            callUntrustedHost(mail.bodyAsBytes)
        }
    }

    enum class CreateMailApi {
        /** @see Enclave.createMail */
        ENCLAVE {
            override fun createMail(body: ByteArray, host: EnclaveHost): ByteArray {
                // Assumes the enclave is CreateMailEnclave
                return host.callEnclave(body)!!
            }
        },
        /** @see EnclaveInstanceInfo.createMail */
        ENCLAVE_INSTANCE_INFO {
            override fun createMail(body: ByteArray, host: EnclaveHost): ByteArray {
                return host.enclaveInstanceInfo.createMail(body.reversedArray()).encrypt()
            }
        };

        abstract fun createMail(body: ByteArray, host: EnclaveHost): ByteArray
    }
}
