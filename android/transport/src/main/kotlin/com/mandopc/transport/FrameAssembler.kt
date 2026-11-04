package com.mandopc.transport

import com.mandopc.contracts.FRAME_SIZE

/**
 * Reensambla el flujo de bytes de AOA en tramas exactas de 64 bytes.
 *
 * Las lecturas USB pueden devolver fragmentos arbitrarios. El buffer
 * de arrastre es fijo, por lo que [feed] no asigna memoria.
 */
class FrameAssembler(private val ring: FrameRing) {

    private val carry = ByteArray(FRAME_SIZE)
    private var carryLength = 0

    fun reset() {
        carryLength = 0
    }

    fun feed(buffer: ByteArray, length: Int) {
        require(length in 0..buffer.size) { "length out of range" }
        var offset = 0
        while (offset < length) {
            if (carryLength == 0 && length - offset >= FRAME_SIZE) {
                ring.push(buffer, offset)
                offset += FRAME_SIZE
                continue
            }
            val take = minOf(FRAME_SIZE - carryLength, length - offset)
            System.arraycopy(buffer, offset, carry, carryLength, take)
            carryLength += take
            offset += take
            if (carryLength == FRAME_SIZE) {
                ring.push(carry)
                carryLength = 0
            }
        }
    }
}
