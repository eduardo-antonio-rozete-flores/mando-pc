package com.mandopc.session

import com.mandopc.contracts.PlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEngineTest {

    private val uuid = ByteArray(16) { it.toByte() }
    private val sent = mutableListOf<ByteArray>()
    private val engine = SessionEngine(
        uuid = uuid,
        sink = FrameSink { sent.add(it.copyOf()) },
        model = "Edge 30",
    )

    private fun countOf(type: Int): Int = sent.count { (it[5].toInt() and 0xFF) == type }

    private fun frameOfType(type: Int): Frame =
        FrameCodec.parse(sent.last { (it[5].toInt() and 0xFF) == type })

    @Test
    fun `beginHandshake envia HELLO con uuid y slot pedido`() {
        engine.setRequestedSlot(3, 0L)
        engine.beginHandshake(1_000L)

        assertEquals(1, sent.size)
        val hello = FrameCodec.parseHello(FrameCodec.parse(sent.single()))
        assertEquals(1_000L, hello.timestampUs)
        assertEquals(3, hello.requestedSlot)
        assertEquals(1, hello.protoMin)
        assertEquals(1, hello.protoMax)
        assertEquals("Edge 30", hello.model)
        assertTrue(hello.uuid.contentEquals(uuid))
        assertEquals(SessionPhase.Handshaking, engine.phase)
    }

    @Test
    fun `HELLO_ACK inicia streaming y el primer INPUT usa la sesion`() {
        engine.beginHandshake(0L)
        sent.clear()

        engine.onFrame(TestFrames.helloAck(sessionId = 5, assignedSlot = 2), 10L)
        assertEquals(SessionPhase.Streaming, engine.phase)
        assertEquals(5, engine.sessionId)
        assertEquals(2, engine.assignedSlot)

        engine.tick(1_000L, PlayerState.neutral().copy(buttons = 1))
        val frame = FrameCodec.parse(sent.single())
        assertEquals(FrameCodec.INPUT, frame.type)
        assertEquals(5, frame.sessionId)
        val input = FrameCodec.parseInput(frame)
        assertEquals(1L, input.seq)
        assertEquals(1, input.buttons)
        assertEquals(1_000L, input.timestampUs)
    }

    @Test
    fun `slot ocupado reintenta automatico tras 250 ms`() {
        engine.setRequestedSlot(2, 0L)
        engine.beginHandshake(0L)
        sent.clear()

        engine.onFrame(
            TestFrames.helloAck(sessionId = 0, status = SessionEngine.STATUS_SLOT_BUSY),
            100L,
        )
        assertEquals(SessionPhase.Handshaking, engine.phase)
        engine.tick(250_099L, PlayerState.neutral())
        assertTrue(sent.isEmpty())

        engine.tick(250_100L, PlayerState.neutral())
        val hello = FrameCodec.parseHello(FrameCodec.parse(sent.single()))
        assertEquals(0, hello.requestedSlot)
    }

    @Test
    fun `versiones incompatibles terminan en error sin reintento`() {
        engine.beginHandshake(0L)
        engine.onFrame(
            TestFrames.helloAck(sessionId = 0, status = SessionEngine.STATUS_VERSION_MISMATCH),
            100L,
        )
        assertEquals(SessionPhase.Error, engine.phase)
        assertEquals("versiones incompatibles", engine.lastError)

        sent.clear()
        engine.tick(10_000_000L, PlayerState.neutral())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `host lleno reintenta el handshake`() {
        engine.beginHandshake(0L)
        engine.onFrame(
            TestFrames.helloAck(sessionId = 0, status = SessionEngine.STATUS_HOST_FULL),
            100L,
        )
        assertEquals(SessionPhase.Error, engine.phase)
        sent.clear()

        engine.tick(2_000_100L, PlayerState.neutral())
        assertEquals(SessionPhase.Handshaking, engine.phase)
        assertEquals(1, countOf(FrameCodec.HELLO))
    }

    @Test
    fun `HELLO sin ACK expira a los 2000 ms`() {
        engine.beginHandshake(0L)
        engine.tick(1_999_999L, PlayerState.neutral())
        assertEquals(SessionPhase.Handshaking, engine.phase)

        engine.tick(2_000_000L, PlayerState.neutral())
        assertEquals(SessionPhase.Error, engine.phase)
        assertEquals("HELLO timeout", engine.lastError)
    }

    @Test
    fun `PONG calcula el RTT y el INPUT lo publica`() {
        engine.beginHandshake(0L)
        engine.onFrame(TestFrames.helloAck(sessionId = 5), 0L)
        engine.onFrame(
            TestFrames.pong(sessionId = 5, clientTsUs = 1_000_000L),
            1_012_000L,
        )
        assertEquals(12, engine.rttMs)

        sent.clear()
        engine.tick(1_012_000L, PlayerState.neutral())
        val input = FrameCodec.parseInput(FrameCodec.parse(sent.first()))
        assertEquals(12, input.rttMs)
    }

    @Test
    fun `PONG de otra sesion se ignora`() {
        engine.beginHandshake(0L)
        engine.onFrame(TestFrames.helloAck(sessionId = 5), 0L)
        engine.onFrame(TestFrames.pong(sessionId = 6, clientTsUs = 0L), 12_000L)
        assertEquals(0, engine.rttMs)
    }

    @Test
    fun `la tasa de INPUT se limita a 1000 Hz`() {
        engine.beginHandshake(0L)
        engine.onFrame(TestFrames.helloAck(sessionId = 5), 0L)
        sent.clear()

        engine.tick(1_000L, PlayerState.neutral())
        assertEquals(1, sent.size)
        engine.tick(1_500L, PlayerState.neutral())
        assertEquals(1, sent.size)
        engine.tick(2_000L, PlayerState.neutral())
        assertEquals(2, sent.size)
    }

    @Test
    fun `disconnect envia BYE y reconectar reinicia la sesion`() {
        engine.beginHandshake(0L)
        engine.onFrame(TestFrames.helloAck(sessionId = 7), 0L)
        sent.clear()

        engine.disconnect()
        assertEquals(SessionPhase.Disconnected, engine.phase)
        assertEquals(1, countOf(FrameCodec.BYE))
        assertEquals(7, frameOfType(FrameCodec.BYE).sessionId)

        engine.beginHandshake(20L)
        engine.onFrame(TestFrames.helloAck(sessionId = 8, assignedSlot = 3), 30L)
        sent.clear()
        engine.tick(1_000L, PlayerState.neutral())
        val frame = FrameCodec.parse(sent.first())
        assertEquals(8, frame.sessionId)
        assertEquals(1L, FrameCodec.parseInput(frame).seq)
        assertEquals(3, engine.assignedSlot)
    }

    @Test
    fun `RUMBLE se entrega al callback`() {
        var received: Rumble? = null
        engine.onRumble = { received = it }
        engine.beginHandshake(0L)
        engine.onFrame(TestFrames.helloAck(sessionId = 5), 0L)
        engine.onFrame(
            TestFrames.rumble(sessionId = 5, left = 1_000, right = 2_000, durationMs = 300),
            1L,
        )
        assertEquals(1_000, received?.left)
        assertEquals(2_000, received?.right)
        assertEquals(300, received?.durationMs)
    }

    @Test
    fun `ERROR del host termina la sesion`() {
        engine.beginHandshake(0L)
        engine.onFrame(TestFrames.error(sessionId = 0, code = 2), 1L)
        assertEquals(SessionPhase.Error, engine.phase)
        assertEquals("host error", engine.lastError)
    }

    @Test
    fun `host_proto fuera de rango se rechaza`() {
        engine.beginHandshake(0L)
        engine.onFrame(TestFrames.helloAck(sessionId = 5, hostProto = 2), 0L)
        assertEquals(SessionPhase.Error, engine.phase)
        assertEquals("host_proto no soportado", engine.lastError)
    }

    @Test
    fun `session_id cero en el ACK se rechaza`() {
        engine.beginHandshake(0L)
        engine.onFrame(TestFrames.helloAck(sessionId = 0), 0L)
        assertEquals(SessionPhase.Error, engine.phase)
        assertEquals("HELLO_ACK sin session_id", engine.lastError)
    }

    @Test
    fun `cambiar de slot en streaming reinicia el handshake`() {
        engine.beginHandshake(0L)
        engine.onFrame(TestFrames.helloAck(sessionId = 5), 0L)
        sent.clear()

        engine.setRequestedSlot(4, 1_000L)
        assertEquals(SessionPhase.Handshaking, engine.phase)
        val hello = FrameCodec.parseHello(FrameCodec.parse(sent.single()))
        assertEquals(4, hello.requestedSlot)
    }
}
