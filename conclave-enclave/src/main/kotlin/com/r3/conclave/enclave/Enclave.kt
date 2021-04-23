package com.r3.conclave.enclave

import com.r3.conclave.common.EnclaveInstanceInfo
import com.r3.conclave.common.EnclaveMode
import com.r3.conclave.common.internal.*
import com.r3.conclave.common.internal.SgxReport.body
import com.r3.conclave.common.internal.attestation.Attestation
import com.r3.conclave.common.internal.handler.*
import com.r3.conclave.enclave.Enclave.CallState.Receive
import com.r3.conclave.enclave.Enclave.CallState.Response
import com.r3.conclave.enclave.internal.AttestationEnclaveHandler
import com.r3.conclave.enclave.internal.EnclaveEnvironment
import com.r3.conclave.enclave.internal.InternalEnclave
import com.r3.conclave.enclave.internal.MockEnclaveEnvironment
import com.r3.conclave.mail.*
import com.r3.conclave.mail.internal.MailDecryptingStream
import com.r3.conclave.utilities.internal.*
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import kotlin.collections.HashMap

/**
 * Subclass this inside your enclave to provide an entry point. The outside world
 * communicates with the enclave via two mechanisms:
 *
 * 1. Local connections from the host. But remember the host is malicious in the SGX
 *    threat model, so anything received from the host cannot be completely trusted.
 *    Override and implement [receiveFromUntrustedHost] to receive the byte arrays sent via
 *    `EnclaveHost.callEnclave`.
 * 2. [EnclaveMail], an encrypted, authenticated and padded asynchronous messaging
 *    scheme. Clients that obtain a [EnclaveInstanceInfo] from the host can create
 *    mails and send it to the host for delivery. Override and implement [receiveMail] to receive mail via the host.
 *
 * Enclaves can sign things with a key that appears in the [com.r3.conclave.common.EnclaveInstanceInfo].
 * This can be useful when the enclave is being used to create a proof of correct
 * computation, rather than operate on secret data.
 */
abstract class Enclave {
    /**
     * Suppress kotlin specific companion objects from our API documentation.
     * @suppress
     */
    private companion object {
        private val signatureScheme = SignatureSchemeEdDSA()
    }

    private lateinit var env: EnclaveEnvironment

    // The signing key pair are assigned with the same value retrieved from getDefaultKey.
    // Such key should always be the same if the enclave is running within the same CPU and having the same MRSIGNER.
    private lateinit var signingKeyPair: KeyPair
    private lateinit var adminHandler: AdminHandler
    private lateinit var attestationHandler: AttestationEnclaveHandler
    private lateinit var enclaveMessageHandler: EnclaveMessageHandler

    private val postOffices = HashMap<DestinationAndTopic, EnclavePostOffice>()

    /**
     * Returns a [Signature] object pre-initialised with the private key corresponding
     * to the [signatureKey], ready for creation of digital signatures over
     * data you provide. The private key is not directly exposed to avoid accidental
     * mis-use (e.g. for encryption).
     */
    protected fun signer(): Signature {
        val signature = SignatureSchemeEdDSA.createSignature()
        signature.initSign(signingKeyPair.private)
        return signature
    }

    /** The public key used to sign data structures when [signer] is used. */
    protected val signatureKey: PublicKey get() = signingKeyPair.public

    /** The serializable remote attestation object for this enclave instance. */
    protected val enclaveInstanceInfo: EnclaveInstanceInfo get() = adminHandler.enclaveInstanceInfo

    /**
     * If this property is false (the default) then a lock will be taken and the enclave will process mail and calls
     * from the host serially. To build a multi-threaded enclave you must firstly, obviously, write thread safe code
     * so the untrusted host cannot cause malicious data corruption by causing race conditions inside the enclave, and
     * then override this method to make it return true. By doing so you signal that you're taking responsibility
     * for your own thread safety.
     */
    protected open val threadSafe: Boolean get() = false

    /**
     * Override this method to receive bytes from the untrusted host via `EnclaveHost.callEnclave`.
     *
     * Default implementation throws [UnsupportedOperationException] so you should not perform a supercall.
     *
     * Any uncaught exceptions thrown by this method propagate to the calling `EnclaveHost.callEnclave`. In Java, checked
     * exceptions can be made to propagate by rethrowing them in an unchecked one.
     *
     * @param bytes Bytes received from the host.
     *
     * @return Bytes to sent back to the host as the return value of the `EnclaveHost.callEnclave` call. Can be null.
     */
    protected open fun receiveFromUntrustedHost(bytes: ByteArray): ByteArray? {
        throw UnsupportedOperationException("This enclave does not support local host communication.")
    }

    /**
     * Sends the given bytes to the callback provided to `EnclaveHost.callEnclave`.
     *
     * @return The bytes returned from the host's callback.
     *
     * @throws IllegalStateException If no callback was provided to `EnclaveHost.callEnclave`.
     */
    protected fun callUntrustedHost(bytes: ByteArray): ByteArray? = callUntrustedHostInternal(bytes, null)

    /**
     * Sends the given bytes to the callback provided to `EnclaveHost.callEnclave`.
     * If the host responds by doing another call back into the enclave rather than immediately returning
     * from the callback, that call will be routed to [callback]. In this way a form of virtual stack can
     * be built up between host and enclave as they call back and forth.
     *
     * @return The bytes returned from the host's callback.
     *
     * @throws IllegalStateException If no callback was provided to `EnclaveHost.callEnclave`.
     */
    protected fun callUntrustedHost(bytes: ByteArray, callback: Function<ByteArray, ByteArray?>): ByteArray? {
        return callUntrustedHostInternal(bytes, callback)
    }

    private fun callUntrustedHostInternal(bytes: ByteArray, callback: HostCallback?): ByteArray? {
        return enclaveMessageHandler.callUntrustedHost(bytes, callback)
    }

    @Suppress("unused")  // Accessed via reflection
    @PotentialPackagePrivate
    private fun initialise(env: EnclaveEnvironment, upstream: Sender): HandlerConnected<*> {
        this.env = env
        // If the Enclave class implements InternalEnclave then the behaviour of the enclave is entirely delegated
        // to the InternalEnclave implementation and the Conclave-specific APIs (e.g. callUntrustedHost, etc) are
        // disabled. This allows us to test the enclave environment in scenarios where we don't want the Conclave handlers.
        return if (this is InternalEnclave) {
            this.internalInitialise(env, upstream)
        } else {
            initCryptography()
            val exposeErrors = env.enclaveMode != EnclaveMode.RELEASE
            val connected = HandlerConnected.connect(ExceptionSendingHandler(exposeErrors = exposeErrors), upstream)
            val mux = connected.connection.setDownstream(SimpleMuxingHandler())
            adminHandler = mux.addDownstream(AdminHandler(this, env))
            attestationHandler = mux.addDownstream(object : AttestationEnclaveHandler(env) {
                override val reportData = createReportData()
            })
            enclaveMessageHandler = mux.addDownstream(EnclaveMessageHandler())
            connected
        }
    }

    /**
     * Initialise an enclave class using a Mock environment.
     */
    @Suppress("unused")  // Accessed via reflection
    @PotentialPackagePrivate
    private fun initialiseMock(upstream: Sender, isvProdId: Int, isvSvn: Int): HandlerConnected<*> {
        return initialise(MockEnclaveEnvironment(this, isvProdId, isvSvn), upstream)
    }

    /**
     * Return 256 bits of stable entropy for the given CPUSVN + ISVNSVN. Even across enclave and host restarts the same
     * bytes will be returned for the same SVN values. The bytes are secret to the enclave and must not be leaked out.
     */
    private fun getSecretEntropy(cpuSvn: ByteBuffer, isvSvn: Int): ByteArray {
        // We get 128 bits of stable pseudo-randomness from the CPU, based on the enclave signer, per-CPU key and other
        // pieces of data.
        val secretKey = env.getSecretKey { keyRequest ->
            keyRequest[SgxKeyRequest.keyName] = KeyName.SEAL
            keyRequest[SgxKeyRequest.keyPolicy] = KeyPolicy.MRSIGNER
            keyRequest[SgxKeyRequest.cpuSvn] = cpuSvn
            keyRequest[SgxKeyRequest.isvSvn] = isvSvn
        }
        // For Curve25519 and EdDSA we need 256 bit keys. We hash it to convert it to 256 bits. This is safe because
        // the underlying 128 bits of entropy remains, and that's "safe" in the sense that nobody can brute force
        // 128 bits of entropy, not enough energy exists on Earth to make that feasible. Curve25519 needs 256 bits
        // for both private and public keys due to the existence of attacks on elliptic curve cryptography that
        // effectively halve the key size, so 256 bit keys -> 128 bits of work to brute force.
        return digest("SHA-256") { update(secretKey) }
    }

    private fun initCryptography() {
        val reportBody = env.createReport(null, null)[body]
        val cpuSvn: ByteBuffer = reportBody[SgxReportBody.cpuSvn].read()
        val isvSvn: Int = reportBody[SgxReportBody.isvSvn].read()
        val entropy = getSecretEntropy(cpuSvn, isvSvn)
        signingKeyPair = signatureScheme.generateKeyPair(entropy)
        val private = Curve25519PrivateKey(entropy)
        encryptionKeyPair = KeyPair(private.publicKey, private)
    }

    private fun createReportData(): ByteCursor<SgxReportData> {
        val reportData = digest("SHA-512") {
            update(signatureKey.encoded)
            update(encryptionKeyPair.public.encoded)
        }
        return Cursor.wrap(SgxReportData, reportData)
    }

    /**
     * Handles the initial comms with the host - we send the host our info, it sends back an attestation response object
     * which we can use to build our [EnclaveInstanceInfo] to include in messages to other enclaves.
     */
    private class AdminHandler(private val enclave: Enclave, private val env: EnclaveEnvironment) :
        Handler<AdminHandler> {
        private lateinit var sender: Sender
        private var _enclaveInstanceInfo: EnclaveInstanceInfoImpl? = null

        override fun connect(upstream: Sender): AdminHandler {
            sender = upstream
            // At the time we send upstream the mux handler hasn't been configured for receiving, but that's OK.
            // The onReceive method will run later, when the AttestationResponse has been obtained from the attestation
            // servers.
            sendEnclaveInfo()
            return this
        }

        override fun onReceive(connection: AdminHandler, input: ByteBuffer) {
            val attestation = Attestation.get(input)
            val attestationReportBody = attestation.reportBody
            val enclaveReportBody = enclave.attestationHandler.report[body]
            check(attestationReportBody == enclaveReportBody) {
                """Host has provided attestation for a different enclave.
Expected: $enclaveReportBody
Received: $attestationReportBody"""
            }
            // It's also important to check the enclave modes match. Specifically we want to prevent an attestation marked
            // as secure from being used when the enclave is running in non-hardware mode (all non-hardware attestations
            // are insecure).
            check(attestation.enclaveMode == env.enclaveMode) {
                "The enclave mode of the attestation (${attestation.enclaveMode}) does not match ${env.enclaveMode}"
            }
            _enclaveInstanceInfo = EnclaveInstanceInfoImpl(
                enclave.signatureKey,
                enclave.encryptionKeyPair.public as Curve25519PublicKey,
                attestation
            )
        }

        private fun sendEnclaveInfo() {
            val encodedSigningKey = enclave.signatureKey.encoded   // 44 bytes
            val encodedEncryptionKey = enclave.encryptionKeyPair.public.encoded   // 32 bytes
            sender.send(1 + encodedSigningKey.size + encodedEncryptionKey.size) { buffer ->
                buffer.put(0)  // Enclave info
                buffer.put(encodedSigningKey)
                buffer.put(encodedEncryptionKey)
            }
        }

        /**
         * Return the [EnclaveInstanceInfoImpl] for this enclave. The first time this is called it asks the host for the
         * [Attestation] object it received from the attestation service. From that the enclave is able to construct the
         * info object. By making this lazy we avoid slowing down the enclave startup process if it's never used.
         */
        @get:Synchronized
        val enclaveInstanceInfo: EnclaveInstanceInfoImpl
            get() {
                if (_enclaveInstanceInfo == null) {
                    sendAttestationRequest()
                }
                return _enclaveInstanceInfo!!
            }

        /**
         * Send a request to the host for the [Attestation] object. The enclave has the other properties needed
         * to construct its [EnclaveInstanceInfoImpl]. This way less bytes are transferred and there's less checking that
         * needs to be done.
         */
        private fun sendAttestationRequest() {
            sender.send(1) { buffer ->
                buffer.put(1)
            }
        }
    }

    private class Watermark(var value: Long)

    private inner class EnclaveMessageHandler : Handler<EnclaveMessageHandler> {
        private val currentEnclaveCall = ThreadLocal<Long>()
        private val enclaveCalls = ConcurrentHashMap<Long, StateManager<CallState>>()

        // Maps sender + topic pairs to the highest sequence number seen so far. Seqnos must start from zero and can only
        // increment by one for each delivered mail.
        private val sequenceWatermarks = HashMap<DestinationAndTopic, Watermark>()
        private lateinit var sender: Sender

        override fun connect(upstream: Sender): EnclaveMessageHandler {
            sender = upstream
            return this
        }

        // .values() returns a fresh array each time so cache it here.
        private val callTypeValues = InternalCallType.values()

        // Variable so we can compare it in an assertion later.
        private val receiveFromUntrustedHostCallback = HostCallback { receiveFromUntrustedHost(it) }

        // This method can be called concurrently by the host.
        override fun onReceive(connection: EnclaveMessageHandler, input: ByteBuffer) {
            val hostThreadID = input.getLong()
            val type = callTypeValues[input.get().toInt()]
            // Assign the host thread ID to the current thread so that callUntrustedHost/postMail/etc can pick up the
            // right state for the thread.
            currentEnclaveCall.set(hostThreadID)
            val stateManager = enclaveCalls.computeIfAbsent(hostThreadID) {
                // The initial state is to receive on receiveFromUntrustedHost.
                StateManager(Receive(receiveFromUntrustedHostCallback, receiveFromUntrustedHost = true))
            }
            if (type == InternalCallType.CALL_RETURN) {
                stateManager.state = Response(input.getRemainingBytes())
            } else if (type == InternalCallType.MAIL_DELIVERY) {
                val id = input.getLong()
                val routingHint = String(input.getIntLengthPrefixBytes()).takeIf { it.isNotEmpty() }
                // Wrap the remaining bytes in a InputStream to avoid copying.
                val decryptingStream = MailDecryptingStream(input.inputStream())
                val mail: EnclaveMail = decryptingStream.decryptMail { keyDerivation ->
                    requireNotNull(keyDerivation) {
                        "Key derivation header is required for decrypting enclave mail. Make sure EnclaveInstanceInfo.createPostOffice is used."
                    }
                    // Ignore any extra bytes in the keyDerivation.
                    require(keyDerivation.size >= SgxCpuSvn.size + SgxIsvSvn.size) { "Invalid key derivation header size" }
                    val keyDerivationBuffer = ByteBuffer.wrap(keyDerivation)
                    val cpuSvn = keyDerivationBuffer.getSlice(SgxCpuSvn.size)
                    val isvSvn = keyDerivationBuffer.getUnsignedShort()
                    val entropy = getSecretEntropy(cpuSvn, isvSvn)
                    // We now have the private key to decrypt the mail body and authenticate the header.
                    Curve25519PrivateKey(entropy)
                }
                checkMailOrdering(mail)
                // We do locking for the user by default, because otherwise it'd be easy to forget that the host can
                // enter on multiple threads even if you aren't prepared for it. Spotting missing thread safety would
                // require spotting the absence of something rather than the presence of something, which is hard.
                // This works even if the host calls back into the enclave on the same stack. However if the host
                // makes a call on a separate thread, it's treated as a separate call as you'd expect.
                if (!threadSafe) {
                    synchronized(this@Enclave) { this@Enclave.receiveMail(id, mail, routingHint) }
                } else {
                    this@Enclave.receiveMail(id, mail, routingHint)
                }
            } else {
                val state = stateManager.checkStateIs<Receive>()
                checkNotNull(state.callback) {
                    "The enclave has not provided a callback to callUntrustedHost to receive the host's call back in."
                }
                // We do locking for the user by default, because otherwise it'd be easy to forget that the host can
                // enter on multiple threads even if you aren't prepared for it. Spotting missing thread safety would
                // require spotting the absence of something rather than the presence of something, which is hard.
                // This works even if the host calls back into the enclave on the same stack. However if the host
                // makes a call on a separate thread, it's treated as a separate call as you'd expect.
                val response = if (!threadSafe) {
                    // If this is a recursive call, we already hold the lock and the synchronized statement is a no-op.
                    // This assertion is here to document that fact.
                    if (state.callback != receiveFromUntrustedHostCallback) check(Thread.holdsLock(this@Enclave))
                    synchronized(this@Enclave) { state.callback.apply(input.getRemainingBytes()) }
                } else {
                    state.callback.apply(input.getRemainingBytes())
                }
                if (response != null) {
                    // If the user calls back into the host whilst handling a local call or a mail, they end up
                    // inside callUntrustedHost. So, for a non-thread safe enclave it's OK that this is outside the
                    // lock, because it'll be held whilst calling out to the enclave during an operation which is when
                    // there's actual risk of corruption. By the time we get here the enclave should be done and ready
                    // for the next request.
                    sendCallToHost(hostThreadID, response, InternalCallType.CALL_RETURN)
                }
            }
        }

        private fun checkMailOrdering(mail: EnclaveMail) {
            synchronized(sequenceWatermarks) {
                val key = DestinationAndTopic(mail.authenticatedSender, mail.topic)
                val highestSeen = sequenceWatermarks.computeIfAbsent(key) { Watermark(-1) }
                // The -1 allows us to check the first mail in this sequence is zero.
                val expected = highestSeen.value + 1
                check(mail.sequenceNumber == expected) {
                    when {
                        highestSeen.value == -1L -> {
                            "First time seeing mail with topic ${mail.topic} so the sequence number must be zero but is " +
                                    "instead ${mail.sequenceNumber}. It may be the host is delivering mail out of order."
                        }
                        mail.sequenceNumber < expected -> {
                            "Mail with sequence number ${mail.sequenceNumber} on topic ${mail.topic} has already been seen, " +
                                    "was expecting $expected. Make sure the same PostOffice instance is used for the same " +
                                    "sender key and topic, or if the sender key is long-term then a per-process topic is used. " +
                                    "Otherwise it may be the host is replaying older messages."
                        }
                        else -> {
                            "Next sequence number on topic ${mail.topic} should be $expected but is instead " +
                                    "${mail.sequenceNumber}. It may be the host is delivering mail out of order."
                        }
                    }
                }
                highestSeen.value++
            }
        }

        fun callUntrustedHost(bytes: ByteArray, callback: HostCallback?): ByteArray? {
            val hostThreadID = checkNotNull(currentEnclaveCall.get()) {
                "Thread ${Thread.currentThread()} may not attempt to call out to the host outside the context of a call."
            }
            val stateManager = enclaveCalls.getValue(hostThreadID)
            val newReceiveState = Receive(callback, receiveFromUntrustedHost = false)
            // We don't expect the enclave to be in the Response state here as that implies a bug since Response is only
            // a temporary holder to capture the return value.
            // We take note of the current Receive state (i.e. the current callback) so that once this callUntrustedHost
            // has finished we revert back to it. This allows nested callUntrustedHost each with potentially their own
            // callback.
            val previousReceiveState = stateManager.transitionStateFrom<Receive>(to = newReceiveState)
            var response: Response? = null
            try {
                // This could re-enter the enclave in onReceive, if the user has provided a callback.
                sendCallToHost(hostThreadID, bytes, InternalCallType.CALL)
            } finally {
                // We revert the state even if an exception was thrown in the callback. This enables the user to have
                // their own exception handling and reuse of the host-enclave communication channel for another call.
                if (stateManager.state === newReceiveState) {
                    // If the state hasn't changed then it means the host didn't have a response
                    stateManager.state = previousReceiveState
                } else {
                    response = stateManager.transitionStateFrom(to = previousReceiveState)
                }
            }
            return response?.bytes
        }

        /**
         * Pass the given [bytes] to the host, who will receive them synchronously.
         *
         * @param threadID The thread ID received from the host which is sent back as is so that the host can know
         * which of the possible many concurrent calls this response is for.
         * @param type Tells the host whether these bytes are the return value of a callback
         * (in which case it has to return itself) or are from [callUntrustedHost] (in which case they need to be passed
         * to the callback).
         */
        private fun sendCallToHost(threadID: Long, bytes: ByteArray, type: InternalCallType) {
            sender.send(Long.SIZE_BYTES + 1 + bytes.size) { buffer ->
                buffer.putLong(threadID)
                buffer.put(type.ordinal.toByte())
                buffer.put(bytes)
            }
        }

        fun postMail(encryptedBytes: ByteArray, routingHint: String?) {
            val routingHintBytes = routingHint?.toByteArray()
            val size = Int.SIZE_BYTES + (routingHintBytes?.size ?: 0) + encryptedBytes.size
            sendMailCommandToHost(size, mailType = 0) { buffer ->
                if (routingHintBytes != null) {
                    buffer.putIntLengthPrefixBytes(routingHintBytes)
                } else {
                    buffer.putInt(0)
                }
                buffer.put(encryptedBytes)
            }
        }

        fun acknowledgeMail(mailID: Long) {
            sendMailCommandToHost(Long.SIZE_BYTES, mailType = 1) { buffer ->
                buffer.putLong(mailID)
            }
        }

        private fun sendMailCommandToHost(size: Int, mailType: Byte, block: (ByteBuffer) -> Unit) {
            val threadID = checkNotNull(currentEnclaveCall.get()) {
                "Thread ${Thread.currentThread()} may not attempt to send or acknowledge mail outside the context of a call or delivery."
            }

            sender.send(Long.SIZE_BYTES + 2 + size) { buffer ->
                buffer.putLong(threadID)
                buffer.put(InternalCallType.MAIL_DELIVERY.ordinal.toByte())
                buffer.put(mailType)
                block(buffer)
            }
        }
    }

    private sealed class CallState {
        class Receive(val callback: HostCallback?, @Suppress("unused") val receiveFromUntrustedHost: Boolean) :
            CallState()

        class Response(val bytes: ByteArray) : CallState()
    }

    //region Mail
    private lateinit var encryptionKeyPair: KeyPair

    /**
     * Invoked when a mail has been delivered by the host (via `EnclaveHost.deliverMail`), successfully decrypted
     * and authenticated (so the [EnclaveMail.authenticatedSender] property is reliable).
     *
     * Default implementation throws [UnsupportedOperationException] so you should not
     * perform a supercall.
     *
     * Received mail should be acknowledged by passing it to [acknowledgeMail], as
     * otherwise it will be redelivered if the enclave restarts.
     *
     * By not acknowledging mail in a topic until a multi-step messaging conversation
     * is finished, you can ensure that the conversation survives restarts and
     * upgrades.
     *
     * Any uncaught exceptions thrown by this method propagate to the calling `EnclaveHost.deliverMail`. In Java, checked
     * exceptions can be made to propagate by rethrowing them in an unchecked one.
     *
     * @param id An opaque identifier for the mail.
     * @param mail Access to the decrypted/authenticated mail body+envelope.
     * @param routingHint An optional string provided by the host that can be passed to [postMail] to tell the
     * host that you wish to reply to whoever provided it with this mail (e.g. connection ID). Note that this may
     * not be the same as the logical sender of the mail if advanced anonymity techniques are being used, like
     * users passing mail around between themselves before it's delivered.
     */
    protected open fun receiveMail(id: Long, mail: EnclaveMail, routingHint: String?) {
        throw UnsupportedOperationException("This enclave does not support receiving mail.")
    }

    /**
     * Informs the host that the mail should not be redelivered after the next
     * restart and can be safely deleted. Mail acknowledgements are atomic and
     * only take effect once the enclave returns control to the host, so, calling
     * this method multiple times on multiple different mails is safe even if the
     * enclave is interrupted part way through.
     */
    protected fun acknowledgeMail(mailID: Long) {
        enclaveMessageHandler.acknowledgeMail(mailID)
    }

    /**
     * Returns a post office for mail targeted at the given destination key, and having the given topic. The post office
     * is setup with the enclave's private encryption key so the receipient can be sure mail originated from this enclave.
     *
     * The enclave will cache post offices so that the same instance is used for the same public key and topic. This
     * ensures mail is sequenced correctly.
     *
     * The recipient should use [PostOffice.decryptMail] to make sure it verifies this enclave as the sender of the mail.
     *
     * If the destination is an enclave then use the overload which takes in an [EnclaveInstanceInfo] instead.
     */
    protected fun postOffice(destinationPublicKey: PublicKey, topic: String): EnclavePostOffice {
        synchronized(postOffices) {
            return postOffices.computeIfAbsent(DestinationAndTopic(destinationPublicKey, topic)) {
                EnclavePostOfficeImpl(destinationPublicKey, topic, null)
            }
        }
    }

    /**
     * Returns a post office for mail targeted at the given destination key, and having the topic "default". The post office
     * is setup with the enclave's private encryption key so the receipient can be sure mail originated from this enclave.
     *
     * The enclave will cache post offices so that the same instance is used for the same public key and topic. This
     * ensures mail is sequenced correctly.
     *
     * The recipient should use [PostOffice.decryptMail] to make sure it verifies this enclave as the sender of the mail.
     *
     * If the destination is an enclave then use the overload which takes in an [EnclaveInstanceInfo] instead.
     */
    protected fun postOffice(destinationPublicKey: PublicKey): EnclavePostOffice {
        return postOffice(destinationPublicKey, "default")
    }

    /**
     * Returns a post office for responding back to the sender of the given mail. This is a convenience method which calls
     * `postOffice(PublicKey, String)` with the mail's authenticated sender key and topic.
     */
    protected fun postOffice(mail: EnclaveMail): EnclavePostOffice = postOffice(mail.authenticatedSender, mail.topic)

    /**
     * Returns a post office for mail targeted to an enclave with the given topic. The target enclave can be one running
     * on this host or on another machine, and can even be this enclave if [enclaveInstanceInfo] is used (and thus enabling
     * the mail-to-self pattern). The post office is setup with the enclave's private encryption key so the receipient
     * can be sure mail originated from this enclave.
     *
     * The enclave will cache post offices so that the same instance is used for the same [EnclaveInstanceInfo] and topic.
     * This ensures mail is sequenced correctly.
     *
     * The recipient should use [PostOffice.decryptMail] to make sure it verifies this enclave as the sender of the mail.
     *
     * // TODO Add in docs how to do mail-to-self
     */
    protected fun postOffice(enclaveInstanceInfo: EnclaveInstanceInfo, topic: String): EnclavePostOffice {
        enclaveInstanceInfo as EnclaveInstanceInfoImpl
        synchronized(postOffices) {
            return postOffices.computeIfAbsent(DestinationAndTopic(enclaveInstanceInfo.encryptionKey, topic)) {
                EnclavePostOfficeImpl(enclaveInstanceInfo.encryptionKey, topic, enclaveInstanceInfo.keyDerivation)
            }
        }
    }

    /**
     * Returns a post office for mail targeted to an enclave with the topic "default". The target enclave can be one running
     * on this host or on another machine, and can even be this enclave if [enclaveInstanceInfo] is used (and thus enabling
     * the mail-to-self pattern). The post office is setup with the enclave's private encryption key so the receipient
     * can be sure mail did indeed originate from this enclave.
     *
     * The enclave will cache post offices so that the same instance is used for the same [EnclaveInstanceInfo] and topic.
     * This ensures mail is sequenced correctly.
     *
     * The recipient should use [PostOffice.decryptMail] to make sure it verifies this enclave as the sender of the mail.
     *
     * // TODO Add in docs how to do mail-to-self
     */
    protected fun postOffice(enclaveInstanceInfo: EnclaveInstanceInfo): EnclavePostOffice {
        return postOffice(enclaveInstanceInfo, "default")
    }

    /**
     * The provided mail will be encrypted, authenticated and passed to the host
     * for delivery.
     *
     * Where the mail gets delivered depends on the host logic: in some
     * applications the public key may be sufficient, in others, the enclave may
     * need or want to provide additional direction using the [routingHint]
     * parameter.
     *
     * Note that posting and acknowledging mail is transactional. The delivery will
     * only actually take place once the current enclave call or [receiveMail] call
     * is finished. All posts and acknowledgements take place atomically, that is,
     * you can acknowledge a mail and post a reply, or the other way around, and it
     * doesn't matter which order you pick: you cannot get lost or replayed messages.
     */
    protected fun postMail(encryptedMail: ByteArray, routingHint: String?) {
        enclaveMessageHandler.postMail(encryptedMail, routingHint)
    }

    private inner class EnclavePostOfficeImpl(
        destinationPublicKey: PublicKey,
        topic: String,
        override val keyDerivation: ByteArray?
    ) : EnclavePostOffice(destinationPublicKey, topic) {
        init {
            minSizePolicy = defaultMinSizePolicy
        }

        override val senderPrivateKey: PrivateKey get() = encryptionKeyPair.private
    }

    // By default let all post office instances use the same moving average instance to make it harder to analyse mail
    // sizes within any given topic.
    private val defaultMinSizePolicy = MinSizePolicy.movingAverage()

    private data class DestinationAndTopic(val destination: PublicKey, val topic: String)
    //endregion
}

// Typealias to make this code easier to read.
private typealias HostCallback = Function<ByteArray, ByteArray?>
