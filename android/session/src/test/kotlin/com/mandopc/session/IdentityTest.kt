package com.mandopc.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityTest {

    @Test
    fun `loadOrCreate conserva el uuid`() {
        val store = InMemoryIdentityStore()
        val first = store.loadOrCreate()
        val second = store.loadOrCreate()

        assertEquals(16, first.uuid.size)
        assertTrue(first.uuid.contentEquals(second.uuid))
        assertEquals(0, second.requestedSlot)
    }

    @Test
    fun `saveRequestedSlot persiste sin cambiar el uuid`() {
        val store = InMemoryIdentityStore()
        val uuid = store.loadOrCreate().uuid.copyOf()

        store.saveRequestedSlot(3)
        val reloaded = store.loadOrCreate()

        assertEquals(3, reloaded.requestedSlot)
        assertTrue(uuid.contentEquals(reloaded.uuid))
    }

    @Test
    fun `hex ida y vuelta`() {
        val bytes = ByteArray(16) { (it * 17).toByte() }
        val encoded = bytes.toHex()

        assertEquals(32, encoded.length)
        assertTrue(bytes.contentEquals(hexToBytes(encoded)))
    }

    @Test
    fun `uuid aleatorio es de 16 bytes y distinto`() {
        val first = randomUuidBytes()
        val second = randomUuidBytes()

        assertEquals(16, first.size)
        assertFalse(first.all { it == 0.toByte() })
        assertFalse(first.contentEquals(second))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `slot fuera de rango se rechaza`() {
        InMemoryIdentityStore().saveRequestedSlot(5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `uuid de tamano incorrecto se rechaza`() {
        Identity(ByteArray(15))
    }
}
