package com.mandopc.session

import com.mandopc.contracts.PlayerState
import com.mandopc.contracts.TouchState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCodecTest {

    private fun hex(text: String): String = text.filter { !it.isWhitespace() }

    private val helloHex = hex(
        """
        4d 4e 44 4f 01 01 00 00 00 00 00 00 00 00 00 00
        e8 03 00 00 00 00 00 00 00 01 02 03 04 05 06 07
        08 09 0a 0b 0c 0d 0e 0f 00 0f 00 01 01 07 45 64
        67 65 20 33 30 00 00 00 00 00 00 00 00 00 00 00
        """,
    )

    private val helloAckHex = hex(
        """
        4d 4e 44 4f 01 02 00 00 01 00 00 00 00 00 00 00
        d0 07 00 00 00 00 00 00 01 00 01 04 09 66 65 64
        6f 72 61 2d 70 63 00 00 00 00 00 00 00 00 00 00
        00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
        """,
    )

    private val inputHex = hex(
        """
        4d 4e 44 4f 01 03 00 00 01 00 00 00 0a 00 00 00
        e8 03 00 00 00 00 00 00 01 00 00 00 00 00 00 00
        00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
        00 00 00 00 00 00 00 00 00 00 00 00 64 64 00 00
        """,
    )

    private val pingHex = hex(
        """
        4d 4e 44 4f 01 04 00 00 01 00 00 00 00 00 00 00
        88 13 00 00 00 00 00 00 00 00 00 00 00 00 00 00
        00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
        00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
        """,
    )

    private val pongHex = hex(
        """
        4d 4e 44 4f 01 05 00 00 01 00 00 00 00 00 00 00
        88 13 00 00 00 00 00 00 58 1b 00 00 00 00 00 00
        00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
        00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
        """,
    )

    @Test
    fun `HELLO coincide con el vector del documento`() {
        val frame = FrameCodec.buildHello(
            timestampUs = 1_000L,
            uuid = ByteArray(16) { it.toByte() },
            requestedSlot = 0,
            capabilities = 0b1111,
            model = "Edge 30",
        )
        assertEquals(helloHex, frame.toHex())
        val hello = FrameCodec.parseHello(FrameCodec.parse(frame))
        assertEquals(1_000L, hello.timestampUs)
        assertEquals("000102030405060708090a0b0c0d0e0f", hello.uuid.toHex())
        assertEquals(0, hello.requestedSlot)
        assertEquals(15, hello.capabilities)
        assertEquals(1, hello.protoMin)
        assertEquals(1, hello.protoMax)
        assertEquals("Edge 30", hello.model)
    }

    @Test
    fun `HELLO_ACK del documento se decodifica`() {
        val parsed = FrameCodec.parse(hexToBytes(helloAckHex))
        assertEquals(FrameCodec.HELLO_ACK, parsed.type)
        assertEquals(1, parsed.sessionId)
        val ack = FrameCodec.parseHelloAck(parsed)
        assertEquals(2_000L, ack.timestampUs)
        assertEquals(1, ack.assignedSlot)
        assertEquals(0, ack.status)
        assertEquals(1, ack.hostProto)
        assertEquals(4, ack.maxPlayers)
        assertEquals("fedora-pc", ack.name)
    }

    @Test
    fun `INPUT del documento coincide y se decodifica`() {
        val state = PlayerState(buttons = 1, battery = 100, link = 100)
        val frame = FrameCodec.buildInput(1_000L, 1, 10, state, rttMs = 0)
        assertEquals(inputHex, frame.toHex())
        val input = FrameCodec.parseInput(FrameCodec.parse(frame))
        assertEquals(1_000L, input.timestampUs)
        assertEquals(10L, input.seq)
        assertEquals(1, input.buttons)
        assertEquals(100, input.battery)
        assertEquals(100, input.link)
        assertEquals(0, input.rttMs)
    }

    @Test
    fun `PING y PONG coinciden con el documento`() {
        val ping = FrameCodec.buildPing(5_000L, 1)
        assertEquals(pingHex, ping.toHex())
        val pong = FrameCodec.parse(hexToBytes(pongHex))
        assertEquals(1, pong.sessionId)
        val decoded = FrameCodec.parsePong(pong)
        assertEquals(5_000L, decoded.clientTsUs)
        assertEquals(7_000L, decoded.hostTsUs)
    }

    @Test
    fun `INPUT conserva signos y campos tactiles`() {
        val state = PlayerState(
            buttons = 0x0001_0001,
            lx = -32_768,
            ly = 32_767,
            rx = -1,
            ry = 1,
            lt = 65_535,
            rt = 1_234,
            gyro = intArrayOf(-32_768, 0, 32_767),
            accel = intArrayOf(1_000, -1_000, 0),
            touch = TouchState(active = true, id = 7, x = 1_234, y = 5_678, pressure = 65_535),
            battery = 100,
            link = 90,
            rttMs = 12,
        )
        val parsed = FrameCodec.parseInput(FrameCodec.parse(FrameCodec.buildInput(1_000L, 5, 10, state)))
        assertEquals(0x0001_0001, parsed.buttons)
        assertEquals(-32_768, parsed.lx)
        assertEquals(32_767, parsed.ly)
        assertEquals(-1, parsed.rx)
        assertEquals(1, parsed.ry)
        assertEquals(65_535, parsed.lt)
        assertEquals(1_234, parsed.rt)
        assertArrayEquals(intArrayOf(-32_768, 0, 32_767), parsed.gyro)
        assertArrayEquals(intArrayOf(1_000, -1_000, 0), parsed.accel)
        assertTrue(parsed.touch.active)
        assertEquals(7, parsed.touch.id)
        assertEquals(1_234, parsed.touch.x)
        assertEquals(5_678, parsed.touch.y)
        assertEquals(65_535, parsed.touch.pressure)
        assertEquals(100, parsed.battery)
        assertEquals(90, parsed.link)
        assertEquals(12, parsed.rttMs)
    }

    @Test
    fun `RUMBLE se decodifica`() {
        val frame = FrameCodec.parse(TestFrames.rumble(5, 1_000, 2_000, 300))
        val rumble = FrameCodec.parseRumble(frame)
        assertEquals(1_000, rumble.left)
        assertEquals(2_000, rumble.right)
        assertEquals(300, rumble.durationMs)
        assertEquals(0, rumble.flags)
    }

    @Test
    fun `el modelo largo se trunca a 18 bytes`() {
        val frame = FrameCodec.buildHello(
            timestampUs = 1L,
            uuid = ByteArray(16),
            requestedSlot = 2,
            capabilities = 0,
            model = "123456789012345678901234567890",
        )
        val hello = FrameCodec.parseHello(FrameCodec.parse(frame))
        assertEquals(2, hello.requestedSlot)
        assertEquals(18, hello.model.length)
        assertEquals("123456789012345678", hello.model)
    }

    @Test
    fun `BYE lleva la razon`() {
        val frame = FrameCodec.buildBye(SessionEngine.BYE_REASON_APP_CLOSED, 5, 9)
        assertEquals(FrameCodec.BYE, frame[5].toInt() and 0xFF)
        assertEquals(SessionEngine.BYE_REASON_APP_CLOSED, frame[16].toInt() and 0xFF)
        assertEquals(5, FrameCodec.parse(frame).sessionId)
        assertEquals(9, FrameCodec.parse(frame).seq)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rechaza magic invalido`() {
        val frame = hexToBytes(helloHex)
        frame[0] = 0
        FrameCodec.parse(frame)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rechaza version no soportada`() {
        val frame = hexToBytes(helloHex)
        frame[4] = 2
        FrameCodec.parse(frame)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rechaza longitud distinta de 64`() {
        FrameCodec.parse(ByteArray(63))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rechaza tipo desconocido`() {
        val frame = hexToBytes(helloHex)
        frame[5] = 9
        FrameCodec.parse(frame)
    }
}
