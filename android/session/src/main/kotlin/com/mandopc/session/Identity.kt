package com.mandopc.session

import java.nio.ByteBuffer
import java.util.UUID

data class Identity(
    val uuid: ByteArray,
    val requestedSlot: Int = 0,
) {
    init {
        require(uuid.size == 16) { "uuid must be 16 bytes" }
        require(requestedSlot in 0..4) { "requestedSlot must be 0..4" }
    }

    fun uuidHex(): String = uuid.toHex()
}

interface IdentityStore {
    fun loadOrCreate(): Identity
    fun saveRequestedSlot(slot: Int)
}

class InMemoryIdentityStore(
    private var identity: Identity? = null,
) : IdentityStore {

    override fun loadOrCreate(): Identity {
        val current = identity
        if (current != null) return current
        return Identity(randomUuidBytes()).also { identity = it }
    }

    override fun saveRequestedSlot(slot: Int) {
        require(slot in 0..4) { "slot must be 0..4" }
        identity = loadOrCreate().copy(requestedSlot = slot)
    }
}

fun randomUuidBytes(): ByteArray {
    val uuid = UUID.randomUUID()
    val buffer = ByteBuffer.allocate(16)
    buffer.putLong(uuid.mostSignificantBits)
    buffer.putLong(uuid.leastSignificantBits)
    return buffer.array()
}
