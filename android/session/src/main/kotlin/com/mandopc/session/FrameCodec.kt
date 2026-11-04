package com.mandopc.session

import com.mandopc.contracts.FRAME_SIZE
import com.mandopc.contracts.PlayerState
import com.mandopc.contracts.TouchState

data class Hello(
    val timestampUs: Long,
    val uuid: ByteArray,
    val requestedSlot: Int,
    val capabilities: Int,
    val protoMin: Int,
    val protoMax: Int,
    val model: String,
)

data class HelloAck(
    val timestampUs: Long,
    val assignedSlot: Int,
    val status: Int,
    val hostProto: Int,
    val maxPlayers: Int,
    val name: String,
)

data class Pong(val clientTsUs: Long, val hostTsUs: Long)

data class Rumble(val left: Int, val right: Int, val durationMs: Int, val flags: Int)

class Frame(
    val type: Int,
    val sessionId: Int,
    val seq: Int,
    val flags: Int,
    val data: ByteArray,
)

object FrameCodec {

    const val VERSION: Int = 1
    const val HEADER_SIZE: Int = 16
    const val PAYLOAD_SIZE: Int = FRAME_SIZE - HEADER_SIZE

    const val HELLO: Int = 1
    const val HELLO_ACK: Int = 2
    const val INPUT: Int = 3
    const val PING: Int = 4
    const val PONG: Int = 5
    const val RUMBLE: Int = 6
    const val BYE: Int = 7
    const val ERROR: Int = 8

    private const val MAGIC_0 = 0x4D
    private const val MAGIC_1 = 0x4E
    private const val MAGIC_2 = 0x44
    private const val MAGIC_3 = 0x4F
    private const val MAX_MODEL = 18
    private const val MAX_NAME = 35

    fun buildFrame(
        type: Int,
        sessionId: Int,
        seq: Int,
        payload: ByteArray,
        flags: Int = 0,
    ): ByteArray {
        require(type in HELLO..ERROR) { "unknown message type: $type" }
        require(payload.size <= PAYLOAD_SIZE) { "payload exceeds $PAYLOAD_SIZE bytes" }
        val frame = ByteArray(FRAME_SIZE)
        frame[0] = MAGIC_0.toByte()
        frame[1] = MAGIC_1.toByte()
        frame[2] = MAGIC_2.toByte()
        frame[3] = MAGIC_3.toByte()
        frame[4] = VERSION.toByte()
        frame[5] = type.toByte()
        frame.putU16(6, flags)
        frame.putU32(8, sessionId)
        frame.putU32(12, seq)
        payload.copyInto(frame, HEADER_SIZE)
        return frame
    }

    fun buildHello(
        timestampUs: Long,
        uuid: ByteArray,
        requestedSlot: Int,
        capabilities: Int,
        model: String,
        protoMin: Int = VERSION,
        protoMax: Int = VERSION,
    ): ByteArray {
        require(uuid.size == 16) { "uuid must be 16 bytes" }
        require(requestedSlot in 0..4) { "requestedSlot must be 0..4" }
        val payload = ByteArray(PAYLOAD_SIZE)
        payload.putU64(0, timestampUs)
        uuid.copyInto(payload, 8)
        payload[24] = requestedSlot.toByte()
        payload.putU16(25, capabilities)
        payload[27] = protoMin.toByte()
        payload[28] = protoMax.toByte()
        val modelBytes = model.toByteArray(Charsets.UTF_8)
        val length = minOf(modelBytes.size, MAX_MODEL)
        payload[29] = length.toByte()
        modelBytes.copyInto(payload, 30, 0, length)
        return buildFrame(HELLO, 0, 0, payload)
    }

    fun buildInput(
        timestampUs: Long,
        sessionId: Int,
        seq: Int,
        state: PlayerState,
        rttMs: Int = state.rttMs,
    ): ByteArray {
        val payload = ByteArray(PAYLOAD_SIZE)
        payload.putU64(0, timestampUs)
        payload.putU32(8, state.buttons)
        payload.putU16(12, state.lx and 0xFFFF)
        payload.putU16(14, state.ly and 0xFFFF)
        payload.putU16(16, state.rx and 0xFFFF)
        payload.putU16(18, state.ry and 0xFFFF)
        payload.putU16(20, state.lt and 0xFFFF)
        payload.putU16(22, state.rt and 0xFFFF)
        payload.putU16(24, state.gyro.at(0) and 0xFFFF)
        payload.putU16(26, state.gyro.at(1) and 0xFFFF)
        payload.putU16(28, state.gyro.at(2) and 0xFFFF)
        payload.putU16(30, state.accel.at(0) and 0xFFFF)
        payload.putU16(32, state.accel.at(1) and 0xFFFF)
        payload.putU16(34, state.accel.at(2) and 0xFFFF)
        payload[36] = if (state.touch.active) 1 else 0
        payload[37] = state.touch.id.toByte()
        payload.putU16(38, state.touch.x and 0xFFFF)
        payload.putU16(40, state.touch.y and 0xFFFF)
        payload.putU16(42, state.touch.pressure and 0xFFFF)
        payload[44] = state.battery.toByte()
        payload[45] = state.link.toByte()
        payload[46] = rttMs.toByte()
        payload[47] = 0
        return buildFrame(INPUT, sessionId, seq, payload)
    }

    fun buildPing(clientTsUs: Long, sessionId: Int, seq: Int = 0): ByteArray {
        val payload = ByteArray(PAYLOAD_SIZE)
        payload.putU64(0, clientTsUs)
        return buildFrame(PING, sessionId, seq, payload)
    }

    fun buildBye(reason: Int, sessionId: Int, seq: Int = 0): ByteArray {
        val payload = ByteArray(PAYLOAD_SIZE)
        payload[0] = reason.toByte()
        return buildFrame(BYE, sessionId, seq, payload)
    }

    fun parse(frame: ByteArray): Frame {
        require(frame.size == FRAME_SIZE) { "frame must be exactly $FRAME_SIZE bytes" }
        require(
            frame[0] == MAGIC_0.toByte() &&
                frame[1] == MAGIC_1.toByte() &&
                frame[2] == MAGIC_2.toByte() &&
                frame[3] == MAGIC_3.toByte(),
        ) { "bad magic" }
        val version = frame[4].toInt() and 0xFF
        require(version == VERSION) { "unsupported version: $version" }
        val type = frame[5].toInt() and 0xFF
        require(type in HELLO..ERROR) { "unknown message type: $type" }
        return Frame(
            type = type,
            sessionId = frame.u32(8),
            seq = frame.u32(12),
            flags = frame.u16(6),
            data = frame,
        )
    }

    fun parseHello(frame: Frame): Hello {
        require(frame.type == HELLO) { "expected HELLO" }
        val data = frame.data
        val length = data[45].toInt() and 0xFF
        return Hello(
            timestampUs = data.u64(16),
            uuid = data.copyOfRange(24, 40),
            requestedSlot = data[40].toInt() and 0xFF,
            capabilities = data.u16(41),
            protoMin = data[43].toInt() and 0xFF,
            protoMax = data[44].toInt() and 0xFF,
            model = String(data, 46, minOf(length, MAX_MODEL), Charsets.UTF_8),
        )
    }

    fun parseHelloAck(frame: Frame): HelloAck {
        require(frame.type == HELLO_ACK) { "expected HELLO_ACK" }
        val data = frame.data
        val length = data[28].toInt() and 0xFF
        return HelloAck(
            timestampUs = data.u64(16),
            assignedSlot = data[24].toInt() and 0xFF,
            status = data[25].toInt() and 0xFF,
            hostProto = data[26].toInt() and 0xFF,
            maxPlayers = data[27].toInt() and 0xFF,
            name = String(data, 29, minOf(length, MAX_NAME), Charsets.UTF_8),
        )
    }

    fun parseInput(frame: Frame): PlayerState {
        require(frame.type == INPUT) { "expected INPUT" }
        val data = frame.data
        return PlayerState(
            timestampUs = data.u64(16),
            seq = frame.seq.toLong() and 0xFFFF_FFFFL,
            buttons = data.u32(24),
            lx = data.i16(28),
            ly = data.i16(30),
            rx = data.i16(32),
            ry = data.i16(34),
            lt = data.u16(36),
            rt = data.u16(38),
            gyro = intArrayOf(data.i16(40), data.i16(42), data.i16(44)),
            accel = intArrayOf(data.i16(46), data.i16(48), data.i16(50)),
            touch = TouchState(
                active = data[52].toInt() != 0,
                id = data[53].toInt() and 0xFF,
                x = data.u16(54),
                y = data.u16(56),
                pressure = data.u16(58),
            ),
            battery = data[60].toInt() and 0xFF,
            link = data[61].toInt() and 0xFF,
            rttMs = data[62].toInt() and 0xFF,
        )
    }

    fun parsePong(frame: Frame): Pong {
        require(frame.type == PONG) { "expected PONG" }
        val data = frame.data
        return Pong(clientTsUs = data.u64(16), hostTsUs = data.u64(24))
    }

    fun parseRumble(frame: Frame): Rumble {
        require(frame.type == RUMBLE) { "expected RUMBLE" }
        val data = frame.data
        return Rumble(
            left = data.u16(16),
            right = data.u16(18),
            durationMs = data.u16(20),
            flags = data[22].toInt() and 0xFF,
        )
    }
}

private val HEX = "0123456789abcdef".toCharArray()

fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    var i = 0
    while (i < size) {
        val value = this[i].toInt() and 0xFF
        chars[i * 2] = HEX[value ushr 4]
        chars[i * 2 + 1] = HEX[value and 0x0F]
        i += 1
    }
    return String(chars)
}

fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hex length must be even" }
    val out = ByteArray(hex.length / 2)
    var i = 0
    while (i < out.size) {
        val high = Character.digit(hex[i * 2], 16)
        val low = Character.digit(hex[i * 2 + 1], 16)
        require(high >= 0 && low >= 0) { "invalid hex" }
        out[i] = ((high shl 4) or low).toByte()
        i += 1
    }
    return out
}

private fun IntArray.at(index: Int): Int = if (index < size) this[index] else 0

private fun ByteArray.u16(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.i16(offset: Int): Int = u16(offset).toShort().toInt()

private fun ByteArray.u32(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

private fun ByteArray.u64(offset: Int): Long {
    var value = 0L
    var i = 7
    while (i >= 0) {
        value = (value shl 8) or (this[offset + i].toLong() and 0xFF)
        i -= 1
    }
    return value
}

private fun ByteArray.putU16(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
}

private fun ByteArray.putU32(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    this[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    this[offset + 3] = ((value ushr 24) and 0xFF).toByte()
}

private fun ByteArray.putU64(offset: Int, value: Long) {
    var i = 0
    while (i < 8) {
        this[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
        i += 1
    }
}
