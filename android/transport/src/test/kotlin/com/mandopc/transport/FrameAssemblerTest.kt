package com.mandopc.transport

import com.mandopc.contracts.FRAME_SIZE
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameAssemblerTest {

    private fun pattern(frames: Int): ByteArray =
        ByteArray(frames * FRAME_SIZE) { (it % 251).toByte() }

    private fun feedInChunks(
        assembler: FrameAssembler,
        source: ByteArray,
        chunk: Int,
    ) {
        var offset = 0
        while (offset < source.size) {
            val length = minOf(chunk, source.size - offset)
            val part = ByteArray(length)
            System.arraycopy(source, offset, part, 0, length)
            assembler.feed(part, length)
            offset += length
        }
    }

    @Test
    fun `una lectura exacta produce una trama`() {
        val ring = FrameRing(4)
        val assembler = FrameAssembler(ring)
        val source = pattern(1)

        assembler.feed(source, FRAME_SIZE)

        assertEquals(1, ring.size)
        val out = ByteArray(FRAME_SIZE)
        assertTrue(ring.poll(out))
        assertArrayEquals(source, out)
    }

    @Test
    fun `fragmentos de un byte reensamblan las tramas`() {
        val ring = FrameRing(4)
        val assembler = FrameAssembler(ring)
        val source = pattern(2)

        feedInChunks(assembler, source, 1)

        assertEquals(2, ring.size)
        val out = ByteArray(FRAME_SIZE)
        assertTrue(ring.poll(out))
        assertArrayEquals(source.copyOfRange(0, FRAME_SIZE), out)
        assertTrue(ring.poll(out))
        assertArrayEquals(source.copyOfRange(FRAME_SIZE, FRAME_SIZE * 2), out)
    }

    @Test
    fun `una trama incompleta queda en espera`() {
        val ring = FrameRing(4)
        val assembler = FrameAssembler(ring)
        val source = pattern(2)

        assembler.feed(source, FRAME_SIZE + FRAME_SIZE / 2)

        assertEquals(1, ring.size)

        val rest = ByteArray(FRAME_SIZE / 2)
        System.arraycopy(source, FRAME_SIZE + FRAME_SIZE / 2, rest, 0, rest.size)
        assembler.feed(rest, rest.size)

        assertEquals(2, ring.size)
    }

    @Test
    fun `reset descarta el arrastre`() {
        val ring = FrameRing(4)
        val assembler = FrameAssembler(ring)
        val source = pattern(1)
        val half = ByteArray(FRAME_SIZE / 2)

        assembler.feed(source, half.size)
        assembler.reset()
        assembler.feed(half, half.size)

        assertEquals(0, ring.size)
        assertFalse(ring.poll(ByteArray(FRAME_SIZE)))
    }
}
