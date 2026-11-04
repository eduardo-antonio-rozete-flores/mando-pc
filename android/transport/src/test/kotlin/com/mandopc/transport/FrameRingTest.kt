package com.mandopc.transport

import com.mandopc.contracts.FRAME_SIZE
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRingTest {

    private fun frame(value: Int): ByteArray =
        ByteArray(FRAME_SIZE) { value.toByte() }

    @Test
    fun `poll devuelve las tramas en orden`() {
        val ring = FrameRing(4)
        ring.push(frame(1))
        ring.push(frame(2))

        val out = ByteArray(FRAME_SIZE)
        assertTrue(ring.poll(out))
        assertArrayEquals(frame(1), out)
        assertTrue(ring.poll(out))
        assertArrayEquals(frame(2), out)
        assertFalse(ring.poll(out))
    }

    @Test
    fun `la cola da vuelta sin perder tramas`() {
        val ring = FrameRing(2)
        val out = ByteArray(FRAME_SIZE)

        ring.push(frame(1))
        assertTrue(ring.poll(out))
        ring.push(frame(2))
        ring.push(frame(3))

        assertEquals(2, ring.size)
        assertTrue(ring.poll(out))
        assertArrayEquals(frame(2), out)
        assertTrue(ring.poll(out))
        assertArrayEquals(frame(3), out)
        assertFalse(ring.poll(out))
    }

    @Test
    fun `al llenarse descarta la trama mas antigua`() {
        val ring = FrameRing(2)
        ring.push(frame(1))
        ring.push(frame(2))
        ring.push(frame(3))

        val out = ByteArray(FRAME_SIZE)
        assertEquals(2, ring.size)
        assertTrue(ring.poll(out))
        assertArrayEquals(frame(2), out)
        assertTrue(ring.poll(out))
        assertArrayEquals(frame(3), out)
        assertFalse(ring.poll(out))
    }

    @Test
    fun `clear vacia la cola`() {
        val ring = FrameRing(2)
        ring.push(frame(1))
        ring.clear()

        assertEquals(0, ring.size)
        assertFalse(ring.poll(ByteArray(FRAME_SIZE)))
    }

    @Test
    fun `push y poll respetan los offsets`() {
        val ring = FrameRing(1)
        val source = ByteArray(FRAME_SIZE + 8)
        for (index in 0 until FRAME_SIZE) {
            source[8 + index] = index.toByte()
        }
        ring.push(source, 8)

        val out = ByteArray(FRAME_SIZE + 5)
        assertTrue(ring.poll(out, 5))
        for (index in 0 until FRAME_SIZE) {
            assertEquals(index.toByte(), out[5 + index])
        }
    }
}
