package com.r3.conclave.integrationtests.djvm.host

import com.r3.conclave.common.OpaqueBytes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.nio.file.Paths

@Disabled
class BytecodeGenerationTests {
    companion object {
        private val enclaveHost = DjvmEnclaveHost()

        @BeforeAll
        @JvmStatic
        fun start() {
            val spid = OpaqueBytes.parse(System.getProperty("conclave.spid"))
            val attestationKey = checkNotNull(System.getProperty("conclave.attestation-key"))
            enclaveHost.start(spid, attestationKey)
            enclaveHost.loadJar(Paths.get(System.getProperty("simple-test-bundle-jar")))
        }

        @AfterAll
        @JvmStatic
        fun shutdown() {
            enclaveHost.close()
        }
    }

    /**
     * Auxiliary code when comparing the DJVM generated bytecode between different JVMs
     */
    @Test
    fun testByteCodeGeneration() {
        val className = "java.lang.Math"
        val response = enclaveHost.generateBytecode(className)
        assertThat(response.className).isEqualTo("sandbox/$className".replace(".","/"))
    }
}
