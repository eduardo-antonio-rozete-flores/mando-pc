package com.mandopc.transport

import com.mandopc.contracts.FRAME_SIZE

/**
 * Cola circular de tramas completas de 64 bytes.
 *
 * Reutiliza un unico arreglo y no asigna memoria por operacion. Si la
 * cola se llena, descarta la trama mas antigua: el protocolo no
 * retransmite y el estado mas reciente gana.
 */
class FrameRing(capacityFrames: Int = DEFAULT_CAPACITY) {

    companion object {
        const val DEFAULT_CAPACITY: Int = 512
    }

    private val lock = Any()
    private val capacity: Int
    private val storage: ByteArray
    private var head = 0
    private var count = 0

    init {
        require(capacityFrames > 0) { "capacityFrames must be > 0" }
        capacity = capacityFrames
        storage = ByteArray(capacityFrames * FRAME_SIZE)
    }

    val size: Int
        get() = synchronized(lock) { count }

    fun push(source: ByteArray, offset: Int = 0) {
        require(offset >= 0 && offset + FRAME_SIZE <= source.size) {
            "source must hold at least $FRAME_SIZE bytes from offset"
        }
        synchronized(lock) {
            if (count == capacity) {
                head = (head + 1) % capacity
                count -= 1
            }
            val tail = (head + count) % capacity
            System.arraycopy(source, offset, storage, tail * FRAME_SIZE, FRAME_SIZE)
            count += 1
        }
    }

    fun poll(out: ByteArray, offset: Int = 0): Boolean {
        require(offset >= 0 && offset + FRAME_SIZE <= out.size) {
            "out must hold at least $FRAME_SIZE bytes from offset"
        }
        synchronized(lock) {
            if (count == 0) return false
            System.arraycopy(storage, head * FRAME_SIZE, out, offset, FRAME_SIZE)
            head = (head + 1) % capacity
            count -= 1
            return true
        }
    }

    fun clear() {
        synchronized(lock) {
            head = 0
            count = 0
        }
    }
}
