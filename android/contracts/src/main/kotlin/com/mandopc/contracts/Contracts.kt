package com.mandopc.contracts

const val FRAME_SIZE: Int = 64
const val MAX_SLOTS: Int = 4

@JvmInline
value class SlotId private constructor(val value: Int) {
    companion object {
        fun of(value: Int): SlotId {
            require(value in 1..MAX_SLOTS) { "slot must be in 1..$MAX_SLOTS" }
            return SlotId(value)
        }
    }
}

@JvmInline
value class SessionId(val value: Int)

data class TouchState(
    val active: Boolean = false,
    val id: Int = 0,
    val x: Int = 0,
    val y: Int = 0,
    val pressure: Int = 0,
)

data class PlayerState(
    val timestampUs: Long = 0,
    val seq: Long = 0,
    val buttons: Int = 0,
    val lx: Int = 0,
    val ly: Int = 0,
    val rx: Int = 0,
    val ry: Int = 0,
    val lt: Int = 0,
    val rt: Int = 0,
    val gyro: IntArray = IntArray(3),
    val accel: IntArray = IntArray(3),
    val touch: TouchState = TouchState(),
    val battery: Int = 255,
    val link: Int = 0,
    val rttMs: Int = 0,
) {
    companion object {
        fun neutral(): PlayerState = PlayerState()
    }

    fun withButton(id: ButtonId, pressed: Boolean): PlayerState {
        val next = if (pressed) buttons or id.mask else buttons and id.mask.inv()
        return copy(buttons = next)
    }
}

enum class ButtonId(val mask: Int) {
    A(1 shl 0),
    B(1 shl 1),
    X(1 shl 2),
    Y(1 shl 3),
    LB(1 shl 4),
    RB(1 shl 5),
    Back(1 shl 6),
    Start(1 shl 7),
    Guide(1 shl 8),
    L3(1 shl 9),
    R3(1 shl 10),
    DpadUp(1 shl 11),
    DpadDown(1 shl 12),
    DpadLeft(1 shl 13),
    DpadRight(1 shl 14),
    Touchpad(1 shl 15),
    Capture(1 shl 16),
}

enum class StickId { Left, Right }

enum class TriggerId { Left, Right }

sealed interface InputEvent {
    data class Button(val id: ButtonId, val pressed: Boolean) : InputEvent
    data class Stick(val id: StickId, val x: Int, val y: Int) : InputEvent
    data class Trigger(val id: TriggerId, val value: Int) : InputEvent
    data class Touch(val state: TouchState) : InputEvent
    data class Gyro(val values: IntArray) : InputEvent
    data class Accel(val values: IntArray) : InputEvent
    data class Battery(val value: Int) : InputEvent
    data class Link(val value: Int) : InputEvent
}

fun applyEvent(state: PlayerState, event: InputEvent): PlayerState = when (event) {
    is InputEvent.Button -> state.withButton(event.id, event.pressed)
    is InputEvent.Stick -> when (event.id) {
        StickId.Left -> state.copy(lx = event.x, ly = event.y)
        StickId.Right -> state.copy(rx = event.x, ry = event.y)
    }
    is InputEvent.Trigger -> when (event.id) {
        TriggerId.Left -> state.copy(lt = event.value)
        TriggerId.Right -> state.copy(rt = event.value)
    }
    is InputEvent.Touch -> state.copy(touch = event.state)
    is InputEvent.Gyro -> state.copy(gyro = event.values.copyOf())
    is InputEvent.Accel -> state.copy(accel = event.values.copyOf())
    is InputEvent.Battery -> state.copy(battery = event.value)
    is InputEvent.Link -> state.copy(link = event.value)
}

enum class DisconnectReason {
    Normal,
    AppClosed,
    CableRemoved,
    Timeout,
    Error,
}

sealed class TransportException(message: String) : Exception(message) {
    class NotOpen : TransportException("transport is not open")
    class Disconnected(val reason: DisconnectReason) :
        TransportException("transport disconnected: $reason")
    class Io(message: String) : TransportException(message)
}

interface Transport {
    fun open()
    fun close()
    fun read(out: ByteArray): Boolean
    fun write(frame: ByteArray)
    fun onDisconnect(): DisconnectReason?
}

interface InputSource {
    fun poll(nowUs: Long): InputEvent?
}
