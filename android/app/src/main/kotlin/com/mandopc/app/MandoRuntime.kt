package com.mandopc.app

import com.mandopc.contracts.InputEvent
import com.mandopc.contracts.PlayerState
import com.mandopc.contracts.applyEvent
import com.mandopc.session.SessionPhase

object MandoRuntime {

    private val lock = Any()
    private var state = PlayerState.neutral()

    @Volatile
    var requestedSlot: Int = 0
        private set

    @Volatile
    var assignedSlot: Int = 0

    @Volatile
    var rttMs: Int = 0

    @Volatile
    var phase: SessionPhase = SessionPhase.Disconnected

    @Volatile
    var hostName: String = ""

    @Volatile
    var lastError: String? = null

    fun emit(event: InputEvent) {
        synchronized(lock) {
            state = applyEvent(state, event)
        }
    }

    fun snapshot(): PlayerState = synchronized(lock) { state }

    fun reset() {
        synchronized(lock) {
            state = PlayerState.neutral()
        }
    }

    fun setRequestedSlot(slot: Int) {
        requestedSlot = slot.coerceIn(0, 4)
    }
}
