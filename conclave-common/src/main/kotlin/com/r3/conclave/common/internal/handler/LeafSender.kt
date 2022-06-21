package com.r3.conclave.common.internal.handler

import java.nio.ByteBuffer
import java.util.function.Consumer

/**
 * Helper abstract class for leaf senders.
 */
abstract class LeafSender : Sender {
    /**
     * Send the now-serialized [ByteBuffer]
     */
    abstract fun sendSerialized(serializedBuffer: ByteBuffer)

    /**
     * Allocate a [needBytes] sized buffer, then traverse and call [serializers] backwards.
     */
    final override fun send(needBytes: Int, serializers: MutableList<Consumer<ByteBuffer>>) {
        val buffer = ByteBuffer.allocate(needBytes)
        for (serializer in serializers.asReversed()) {
            serializer.accept(buffer)
        }
        check(buffer.position() == needBytes)
        buffer.flip()
        sendSerialized(buffer)
    }
}
