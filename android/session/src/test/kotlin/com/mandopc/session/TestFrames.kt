package com.mandopc.session

internal object TestFrames {

    fun helloAck(
        sessionId: Int,
        timestampUs: Long = 2_000L,
        assignedSlot: Int = 1,
        status: Int = 0,
        hostProto: Int = 1,
        maxPlayers: Int = 4,
        name: String = "fedora-pc",
    ): ByteArray {
        val payload = ByteArray(FrameCodec.PAYLOAD_SIZE)
        payload.putU64(0, timestampUs)
        payload[8] = assignedSlot.toByte()
        payload[9] = status.toByte()
        payload[10] = hostProto.toByte()
        payload[11] = maxPlayers.toByte()
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val length = minOf(nameBytes.size, 35)
        payload[12] = length.toByte()
        nameBytes.copyInto(payload, 13, 0, length)
        return FrameCodec.buildFrame(FrameCodec.HELLO_ACK, sessionId, 0, payload)
    }

    fun pong(sessionId: Int, clientTsUs: Long, hostTsUs: Long = clientTsUs + 500L): ByteArray {
        val payload = ByteArray(FrameCodec.PAYLOAD_SIZE)
        payload.putU64(0, clientTsUs)
        payload.putU64(8, hostTsUs)
        return FrameCodec.buildFrame(FrameCodec.PONG, sessionId, 0, payload)
    }

    fun rumble(sessionId: Int, left: Int, right: Int, durationMs: Int): ByteArray {
        val payload = ByteArray(FrameCodec.PAYLOAD_SIZE)
        payload.putU16(0, left)
        payload.putU16(2, right)
        payload.putU16(4, durationMs)
        return FrameCodec.buildFrame(FrameCodec.RUMBLE, sessionId, 0, payload)
    }

    fun error(sessionId: Int, code: Int): ByteArray {
        val payload = ByteArray(FrameCodec.PAYLOAD_SIZE)
        payload.putU16(0, code)
        return FrameCodec.buildFrame(FrameCodec.ERROR, sessionId, 0, payload)
    }

    private fun ByteArray.putU16(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun ByteArray.putU64(offset: Int, value: Long) {
        var i = 0
        while (i < 8) {
            this[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
            i += 1
        }
    }
}
