package com.mandopc.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContractsTest {
    @Test
    fun mockTransportDelivers1000FramesPerSecond() {
        val transport = MockTransport.create()
        transport.open()
        transport.advance(1_000_000)

        val buffer = ByteArray(FRAME_SIZE)
        var count = 0
        while (transport.read(buffer)) {
            assertEquals(FRAME_SIZE, buffer.size)
            count += 1
        }
        assertEquals(1000, count)
        assertEquals(1_000_000L, transport.clockUs())
    }

    @Test
    fun transportRequiresOpen() {
        val transport = MockTransport.create()
        assertFailsWith<TransportException.NotOpen> {
            transport.read(ByteArray(FRAME_SIZE))
        }
        transport.open()
        transport.close()
        assertFailsWith<TransportException.NotOpen> {
            transport.read(ByteArray(FRAME_SIZE))
        }
    }

    @Test
    fun disconnectIsReportedOnce() {
        val transport = MockTransport.create()
        transport.open()
        transport.disconnect(DisconnectReason.CableRemoved)

        assertFailsWith<TransportException.Disconnected> {
            transport.read(ByteArray(FRAME_SIZE))
        }
        assertEquals(DisconnectReason.CableRemoved, transport.onDisconnect())
        assertNull(transport.onDisconnect())
    }

    @Test
    fun transportWritesExactlyOneFrame() {
        val transport = MockTransport.create()
        transport.open()
        transport.write(ByteArray(FRAME_SIZE))

        assertEquals(1, transport.writes().size)
        assertEquals(FRAME_SIZE, transport.writes()[0].size)
    }

    @Test
    fun buttonEventSetsAndClearsMask() {
        val pressed = applyEvent(PlayerState.neutral(), InputEvent.Button(ButtonId.A, true))
        assertEquals(1, pressed.buttons and ButtonId.A.mask)

        val released = applyEvent(pressed, InputEvent.Button(ButtonId.A, false))
        assertEquals(0, released.buttons)
    }

    @Test
    fun inputSourceIsOrderedAndNonBlocking() {
        val source = MockInputSource(
            listOf(
                10L to InputEvent.Button(ButtonId.A, true),
                20L to InputEvent.Button(ButtonId.A, false),
            ),
        )
        assertNull(source.poll(5))
        assertEquals(InputEvent.Button(ButtonId.A, true), source.poll(10))
        assertNull(source.poll(19))
        assertEquals(InputEvent.Button(ButtonId.A, false), source.poll(20))
        assertNull(source.poll(30))
    }

    @Test
    fun inputSourceWithoutEventsNeverBlocks() {
        val source = MockInputSource()
        assertNull(source.poll(Long.MAX_VALUE))
        assertFalse(source.applyAll(0, PlayerState.neutral()).let { it.buttons != 0 })
        assertTrue(PlayerState.neutral().buttons == 0)
    }
}
