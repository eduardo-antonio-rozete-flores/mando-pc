package com.mandopc.ui

import com.mandopc.contracts.InputEvent

fun interface InputEventSink {
    fun emit(event: InputEvent)
}
