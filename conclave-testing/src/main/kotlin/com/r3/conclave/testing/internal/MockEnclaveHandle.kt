package com.r3.conclave.testing.internal

import com.r3.conclave.common.EnclaveMode
import com.r3.conclave.common.internal.handler.Handler
import com.r3.conclave.common.internal.handler.HandlerConnected
import com.r3.conclave.common.internal.handler.LeafSender
import com.r3.conclave.common.internal.handler.Sender
import com.r3.conclave.enclave.Enclave
import com.r3.conclave.enclave.internal.EnclaveEnvironment
import com.r3.conclave.host.internal.EnclaveHandle
import com.r3.conclave.utilities.internal.EnclaveContext
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer

class MockEnclaveHandle<CONNECTION>(
        private val enclave: Enclave,
        private val isvProdId: Int,
        private val isvSvn: Int,
        hostHandler: Handler<CONNECTION>
) : EnclaveHandle<CONNECTION>, LeafSender() {
    companion object {
        // The use of reflection is not ideal but it means we don't expose something that shouldn't be in the public API.
        private val initialiseMethod = Enclave::class.java.getDeclaredMethod("initialise", EnclaveEnvironment::class.java, Sender::class.java).apply { isAccessible = true }

        init {
            EnclaveContext.Companion::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, ThreadLocalEnclaveContext)
        }
    }

    override val enclaveMode: EnclaveMode get() = EnclaveMode.MOCK

    override val connection: CONNECTION = hostHandler.connect(this)

    override val enclaveClassName: String get() = enclave.javaClass.name

    private val enclaveHandler by lazy {
        val sender = MockOcallSender(HandlerConnected(hostHandler, connection))
        try {
            initialiseMethod.invoke(enclave, MockEnclaveEnvironment(enclave, isvProdId, isvSvn), sender) as HandlerConnected<*>
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    override fun sendSerialized(serializedBuffer: ByteBuffer) {
        check(!EnclaveContext.isInsideEnclave())
        ThreadLocalEnclaveContext.set(true)
        try {
            enclaveHandler.onReceive(serializedBuffer)
        } finally {
            ThreadLocalEnclaveContext.set(false)
        }
    }

    override fun destroy() {
    }
}

object ThreadLocalEnclaveContext : EnclaveContext, ThreadLocal<Boolean>() {
    override fun initialValue(): Boolean = false
    override fun isInsideEnclave(): Boolean = get()
}
