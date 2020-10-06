package com.r3.conclave.enclave

import com.r3.conclave.host.EnclaveHost
import com.r3.conclave.internaltesting.RecordingCallback
import com.r3.conclave.internaltesting.dynamic.EnclaveBuilder
import com.r3.conclave.internaltesting.dynamic.EnclaveConfig
import com.r3.conclave.internaltesting.dynamic.TestEnclaves
import com.r3.conclave.internaltesting.threadWithFuture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Function
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

class EnclaveTest {
    companion object {
        @JvmField
        @RegisterExtension
        val testEnclaves = TestEnclaves()
    }

    private var closeHost: Boolean = false
    private lateinit var host: EnclaveHost

    @AfterEach
    fun cleanUp() {
        if (closeHost) {
            host.close()
        }
    }

    @Test
    fun `TCS allocation policy`() {
        val tcs = 20
        start<IncrementingEnclave>(EnclaveBuilder(config = EnclaveConfig().withTCSNum(tcs)))
        val lock = ReentrantLock()
        // Check that TCS are NOT by default bound to application threads
        val concurrentCalls = tcs + 20
        val ongoing = CountDownLatch(concurrentCalls)
        val futures = (1..concurrentCalls).map {
            threadWithFuture {
                lock.withLock {
                    val response = host.callEnclave(it.toByteArray())!!
                    assertThat(response.toInt()).isEqualTo(it + 1)
                }
                ongoing.countDown()
                ongoing.await()
            }
        }

        futures.forEach { it.join() }
    }

    @Test
    fun `TCS reallocation`() {
        val tcs = WaitingEnclave.PARALLEL_ECALLS + 3 // Some TCS are reserved for Avian internal threads
        start<WaitingEnclave>(EnclaveBuilder(config = EnclaveConfig().withTCSNum(tcs)))
        repeat (3) {
            val responses = RecordingCallback()
            val futures = (1..WaitingEnclave.PARALLEL_ECALLS).map {
                threadWithFuture {
                    host.callEnclave(it.toByteArray(), responses)
                }
            }
            futures.forEach { it.join() }
            assertThat(responses.calls).hasSameSizeAs(futures)
        }
    }

    @Test
    fun `threading in enclave`() {
        val n = 15
        start<ThreadingEnclave>(EnclaveBuilder(config = EnclaveConfig().withTCSNum(20)))
        val response = host.callEnclave(n.toByteArray())!!
        assertThat(response.toInt()).isEqualTo((n * (n + 1)) / 2)
    }

    @Test
    fun `exception is thrown if too many threads are requested`() {
        val n = 15
        start<ThreadingEnclave>(EnclaveBuilder(config = EnclaveConfig().withTCSNum(10)))
        assertThatThrownBy {
            host.callEnclave(n.toByteArray())
        }.hasMessageContaining("SGX_ERROR_OUT_OF_TCS")
        // Note: enclaveHandle.destroy hangs due to inconsistent internal Avian thread state after SGX_ERROR_OUT_OF_TCS,
        // so we cant properly shutdown in this case
        closeHost = false
    }

    @Test
    fun `test JNI memory leaks`() {
        start<EchoEnclave>()
        val message = ByteArray(500000) { (it % 256).toByte() }
        for (n in 1..1000) {
            host.callEnclave(message)
        }
    }

    @Test
    fun `destroy while OCALL in progress`() {
        start<EchoCallbackEnclave>()
        val semaphore = CompletableFuture<Unit>()
        val callback = object : Function<ByteArray, ByteArray?> {
            val ocalls = AtomicInteger(0)
            override fun apply(bytes: ByteArray): ByteArray? {
                return if (ocalls.getAndIncrement() == 0) {
                    semaphore.get()
                    bytes
                } else {
                    null
                }
            }
        }
        val ecall = threadWithFuture {
            host.callEnclave(ByteArray(16), callback)
        }
        while (callback.ocalls.get() == 0) {
            Thread.sleep(100)
        }
        val destructor = threadWithFuture {
            host.close()
        }
        semaphore.complete(Unit)
        destructor.join()
        ecall.join()
        assertThat(callback.ocalls.get()).isEqualTo(2)
    }

    @Disabled("This test demonstrates the waiting behaviour of enclave destruction")
    @Test
    fun `destroy while ECALL in progress`() {
        start<SpinningEnclave>()
        val recorder = RecordingCallback()
        thread(isDaemon = true) {
            host.callEnclave(byteArrayOf(), recorder)
        }
        while (recorder.calls.isEmpty()) {
            // Wait until the enclave signals the ECALL is in progress
            Thread.sleep(1)
        }
        host.close() // hang
    }

    @Test
    fun `destroy in OCALL`() {
        start<EchoCallbackEnclave>()
        var called = false
        assertThatThrownBy {
            host.callEnclave(byteArrayOf()) {
                called = true
                host.close()
                null
            }
        }
        assertTrue(called, "Ocall must be called")
    }

    @Disabled("https://r3-cev.atlassian.net/browse/CON-100")
    @Test
    fun `child thread can do OCALLs`() {
        start<ChildThreadSendingEnclave>(EnclaveBuilder(config = EnclaveConfig().withTCSNum(10)))
        val recorder = RecordingCallback()
        host.callEnclave(byteArrayOf(), recorder)
        assertThat(recorder.calls.single()).isEqualTo("test".toByteArray())
    }

    private inline fun <reified T : Enclave> start(enclaveBuilder: EnclaveBuilder = EnclaveBuilder()) {
        host = testEnclaves.hostTo<T>(enclaveBuilder).apply {
            start(null, null, null, null)
        }
        closeHost = true
    }

    class EchoEnclave : Enclave() {
        override fun receiveFromUntrustedHost(bytes: ByteArray): ByteArray? = bytes
    }

    class EchoCallbackEnclave : Enclave() {
        override fun receiveFromUntrustedHost(bytes: ByteArray): ByteArray? {
            var echoBack = bytes
            while (true) {
                val response = callUntrustedHost(echoBack) ?: return null
                echoBack = response
            }
        }
    }

    class IncrementingEnclave : Enclave() {
        override fun receiveFromUntrustedHost(bytes: ByteArray): ByteArray? {
            val n = bytes.toInt()
            return (n + 1).toByteArray()
        }
    }

    class WaitingEnclave : Enclave() {
        companion object {
            const val PARALLEL_ECALLS = 16
        }

        private val ecalls = AtomicInteger(0)

        override fun receiveFromUntrustedHost(bytes: ByteArray): ByteArray? {
            ecalls.incrementAndGet()
            while (ecalls.get() < PARALLEL_ECALLS) {
                // Wait
            }
            synchronized(this) {
                callUntrustedHost(bytes)
            }
            return null
        }
    }

    class ThreadingEnclave : Enclave() {
        override fun receiveFromUntrustedHost(bytes: ByteArray): ByteArray? {
            val n = bytes.toInt()
            val latchBefore = CountDownLatch(n)
            val latchAfter = CountDownLatch(n)
            val sum = AtomicInteger(0)
            val semaphore = Semaphore(0)
            try {
                for (i in 1..n) {
                    thread {
                        latchBefore.countDown()
                        semaphore.acquire()
                        sum.addAndGet(i)
                        latchAfter.countDown()
                    }
                }
                latchBefore.await() // wait until all threads are started
                semaphore.release(n) // unblock threads
                latchAfter.await() // wait until all threads finished adding
                return sum.get().toByteArray()
            } finally {
                semaphore.release(n) // in case an exception occurred before releasing the semaphore
            }
        }
    }

    class SpinningEnclave : Enclave() {
        override fun receiveFromUntrustedHost(bytes: ByteArray): ByteArray? {
            callUntrustedHost(bytes)
            while (true) {
                // Spin
            }
        }
    }

    class ChildThreadSendingEnclave : Enclave() {
        override fun receiveFromUntrustedHost(bytes: ByteArray): ByteArray? {
            threadWithFuture {
                callUntrustedHost("test".toByteArray())
            }.join()
            return null
        }
    }

}

private fun Int.toByteArray(): ByteArray = ByteBuffer.allocate(4).putInt(this).array()

private fun ByteArray.toInt(): Int = ByteBuffer.wrap(this).getInt()
