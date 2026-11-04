package com.mandopc.contracts

import java.nio.ByteBuffer
import java.nio.ByteOrder

class MockTransport private constructor(
    private val sessionId: Int,
    private val intervalUs: Long,
) : Transport {
    private val frames = ArrayDeque<ByteArray>()
    private val writes = mutableListOf<ByteArray>()
    private var opened = false
    private var disconnectReason: DisconnectReason? = null
    private var clockUs = 0L
    private var nextAtUs = 0L
    private var syntheticSeq = 1

    companion object {
        fun create(sessionId: Int = 1, intervalUs: Long = 1_000): MockTransport =
            MockTransport(sessionId, intervalUs)

        fun withFrames(initial: List<ByteArray>, sessionId: Int = 1): MockTransport =
            MockTransport(sessionId, 1_000).apply {
                initial.forEach { frame ->
                    require(frame.size == FRAME_SIZE) { "frame must be $FRAME_SIZE bytes" }
                    frames.addLast(frame.copyOf())
                }
            }
    }

    override fun open() {
        opened = true
    }

    override fun close() {
        opened = false
    }

    override fun read(out: ByteArray): Boolean {
        require(out.size >= FRAME_SIZE) { "buffer must hold $FRAME_SIZE bytes" }
        if (!opened) throw TransportException.NotOpen()
        disconnectReason?.let { throw TransportException.Disconnected(it) }
        val frame = frames.removeFirstOrNull() ?: return false
        frame.copyInto(out, 0)
        return true
    }

    override fun write(frame: ByteArray) {
        require(frame.size == FRAME_SIZE) { "frame must be $FRAME_SIZE bytes" }
        if (!opened) throw TransportException.NotOpen()
        writes.add(frame.copyOf())
    }

    override fun onDisconnect(): DisconnectReason? {
        val reason = disconnectReason
        disconnectReason = null
        return reason
    }

    fun disconnect(reason: DisconnectReason) {
        disconnectReason = reason
    }

    fun advance(deltaUs: Long) {
        clockUs += deltaUs
        while (nextAtUs < clockUs) {
            frames.addLast(syntheticFrame(nextAtUs))
            syntheticSeq += 1
            nextAtUs += intervalUs
        }
    }

    fun clockUs(): Long = clockUs

    fun writes(): List<ByteArray> = writes.toList()

    private fun syntheticFrame(timestampUs: Long): ByteArray {
        val frame = ByteArray(FRAME_SIZE)
        frame[0] = 'M'.code.toByte()
        frame[1] = 'N'.code.toByte()
        frame[2] = 'D'.code.toByte()
        frame[3] = 'O'.code.toByte()
        frame[4] = 1
        frame[5] = 3
        ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(8, sessionId)
            putInt(12, syntheticSeq)
            putLong(16, timestampUs)
        }
        return frame
    }
}

class MockInputSource(events: List<Pair<Long, InputEvent>> = emptyList()) : InputSource {
    private val pending = ArrayDeque(events.sortedBy { it.first })

    fun push(atUs: Long, event: InputEvent) {
        pending.addLast(atUs to event)
    }

    override fun poll(nowUs: Long): InputEvent? {
        val head = pending.firstOrNull() ?: return null
        if (head.first > nowUs) return null
        pending.removeFirst()
        return head.second
    }

    fun applyAll(nowUs: Long, state: PlayerState): PlayerState {
        var current = state
        while (true) {
            val event = poll(nowUs) ?: return current
            current = applyEvent(current, event)
        }
    }
}
