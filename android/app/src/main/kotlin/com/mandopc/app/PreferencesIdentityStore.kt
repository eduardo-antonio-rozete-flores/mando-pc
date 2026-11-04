package com.mandopc.app

import android.content.Context
import com.mandopc.session.Identity
import com.mandopc.session.IdentityStore
import com.mandopc.session.hexToBytes
import com.mandopc.session.randomUuidBytes
import com.mandopc.session.toHex

class PreferencesIdentityStore(context: Context) : IdentityStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun loadOrCreate(): Identity {
        val stored = prefs.getString(KEY_UUID, null)
        val uuid = stored
            ?.let { runCatching { hexToBytes(it) }.getOrNull() }
            ?.takeIf { it.size == 16 }
            ?: createAndStoreUuid()
        val slot = prefs.getInt(KEY_SLOT, 0).coerceIn(0, 4)
        return Identity(uuid, slot)
    }

    override fun saveRequestedSlot(slot: Int) {
        require(slot in 0..4) { "slot must be 0..4" }
        prefs.edit().putInt(KEY_SLOT, slot).commit()
    }

    private fun createAndStoreUuid(): ByteArray {
        val uuid = randomUuidBytes()
        prefs.edit().putString(KEY_UUID, uuid.toHex()).commit()
        return uuid
    }

    private companion object {
        const val FILE = "mando_identity"
        const val KEY_UUID = "uuid_hex"
        const val KEY_SLOT = "requested_slot"
    }
}
