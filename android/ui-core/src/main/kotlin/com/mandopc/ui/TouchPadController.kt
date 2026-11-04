package com.mandopc.ui

import com.mandopc.contracts.ButtonId
import com.mandopc.contracts.InputEvent
import com.mandopc.contracts.StickId
import com.mandopc.contracts.TouchState
import com.mandopc.contracts.TriggerId
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Maquina de estados tactil del layout Xbox.
 *
 * Recibe primitivas de punteros (sin MotionEvent y sin colecciones) y
 * emite InputEvent por [sink]. El hot path no asigna objetos salvo el
 * propio evento que exige el contrato.
 */
class TouchPadController(
    private val sink: InputEventSink,
    private val deadZone: Float = 0.15f,
) {

    companion object {
        private const val MAX_POINTERS = 32
        private const val NO_POINTER = -1
        private const val MAX_STICK = 32_767
        private const val MIN_STICK = -32_768
    }

    var geometry: LayoutGeometry = LayoutGeometry(1f, 1f)
        private set

    val renderState = PadRenderState()

    private val pointerZone = IntArray(MAX_POINTERS)
    private val pointerActive = BooleanArray(MAX_POINTERS)
    private val buttonHold = IntArray(ButtonId.entries.size)
    private var triggerHoldLeft = 0
    private var triggerHoldRight = 0
    private var leftStickPointer = NO_POINTER
    private var rightStickPointer = NO_POINTER
    private var touchPointer = NO_POINTER

    private var stickScratchX = 0
    private var stickScratchY = 0

    private val buttonDownEvents = Array(ButtonId.entries.size) {
        InputEvent.Button(ButtonId.entries[it], true)
    }
    private val buttonUpEvents = Array(ButtonId.entries.size) {
        InputEvent.Button(ButtonId.entries[it], false)
    }
    private val zeroLeftStick = InputEvent.Stick(StickId.Left, 0, 0)
    private val zeroRightStick = InputEvent.Stick(StickId.Right, 0, 0)
    private val leftTriggerOn = InputEvent.Trigger(TriggerId.Left, 65_535)
    private val leftTriggerOff = InputEvent.Trigger(TriggerId.Left, 0)
    private val rightTriggerOn = InputEvent.Trigger(TriggerId.Right, 65_535)
    private val rightTriggerOff = InputEvent.Trigger(TriggerId.Right, 0)
    private val touchUpEvents = Array(MAX_POINTERS) {
        InputEvent.Touch(TouchState(active = false, id = it))
    }

    fun setBounds(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        if (geometry.width == width && geometry.height == height) return
        cancelAll()
        geometry = LayoutGeometry(width, height)
    }

    fun onPointerDown(id: Int, x: Float, y: Float, pressure: Float) {
        if (id !in 0 until MAX_POINTERS || pointerActive[id]) return
        val zone = geometry.hitTest(x, y)
        if (zone == LayoutGeometry.ZONE_NONE) return

        when (zone) {
            LayoutGeometry.ZONE_LEFT_STICK -> {
                if (leftStickPointer != NO_POINTER) return
                leftStickPointer = id
            }

            LayoutGeometry.ZONE_RIGHT_STICK -> {
                if (rightStickPointer != NO_POINTER) return
                rightStickPointer = id
            }

            LayoutGeometry.ZONE_TOUCHPAD -> {
                if (touchPointer != NO_POINTER) return
                touchPointer = id
            }
        }

        pointerActive[id] = true
        pointerZone[id] = zone
        renderState.activePointers += 1

        when (zone) {
            LayoutGeometry.ZONE_LEFT_STICK -> updateLeftStick(x, y)
            LayoutGeometry.ZONE_RIGHT_STICK -> updateRightStick(x, y)
            LayoutGeometry.ZONE_LT -> pressTrigger(left = true, pressed = true)
            LayoutGeometry.ZONE_RT -> pressTrigger(left = false, pressed = true)
            LayoutGeometry.ZONE_TOUCHPAD -> {
                pressButton(ButtonId.Touchpad, pressed = true)
                updateTouch(x, y, pressure)
            }

            else -> {
                if (zone >= LayoutGeometry.ZONE_BUTTON_BASE) {
                    pressButton(ButtonId.entries[zone - LayoutGeometry.ZONE_BUTTON_BASE], true)
                }
            }
        }
    }

    fun onPointerMove(id: Int, x: Float, y: Float, pressure: Float) {
        if (id !in 0 until MAX_POINTERS || !pointerActive[id]) return
        when (pointerZone[id]) {
            LayoutGeometry.ZONE_LEFT_STICK -> {
                if (leftStickPointer == id) updateLeftStick(x, y)
            }

            LayoutGeometry.ZONE_RIGHT_STICK -> {
                if (rightStickPointer == id) updateRightStick(x, y)
            }

            LayoutGeometry.ZONE_TOUCHPAD -> {
                if (touchPointer == id) updateTouch(x, y, pressure)
            }
        }
    }

    fun onPointerUp(id: Int) {
        if (id !in 0 until MAX_POINTERS || !pointerActive[id]) return
        release(id)
    }

    fun onPointerCancel(id: Int) {
        onPointerUp(id)
    }

    fun cancelAll() {
        var id = 0
        while (id < MAX_POINTERS) {
            if (pointerActive[id]) release(id)
            id += 1
        }
    }

    private fun release(id: Int) {
        when (val zone = pointerZone[id]) {
            LayoutGeometry.ZONE_LEFT_STICK -> {
                if (leftStickPointer == id) {
                    leftStickPointer = NO_POINTER
                    resetLeftStick()
                }
            }

            LayoutGeometry.ZONE_RIGHT_STICK -> {
                if (rightStickPointer == id) {
                    rightStickPointer = NO_POINTER
                    resetRightStick()
                }
            }

            LayoutGeometry.ZONE_LT -> pressTrigger(left = true, pressed = false)
            LayoutGeometry.ZONE_RT -> pressTrigger(left = false, pressed = false)

            LayoutGeometry.ZONE_TOUCHPAD -> {
                if (touchPointer == id) {
                    touchPointer = NO_POINTER
                    renderState.touchActive = false
                    renderState.touchPressure = 0
                    pressButton(ButtonId.Touchpad, pressed = false)
                    sink.emit(touchUpEvents[id])
                }
            }

            else -> {
                if (zone >= LayoutGeometry.ZONE_BUTTON_BASE) {
                    pressButton(ButtonId.entries[zone - LayoutGeometry.ZONE_BUTTON_BASE], false)
                }
            }
        }

        pointerActive[id] = false
        pointerZone[id] = LayoutGeometry.ZONE_NONE
        if (renderState.activePointers > 0) renderState.activePointers -= 1
    }

    private fun pressButton(id: ButtonId, pressed: Boolean) {
        val index = id.ordinal
        if (pressed) {
            if (buttonHold[index] == 0) {
                renderState.buttonsMask = renderState.buttonsMask or id.mask
                sink.emit(buttonDownEvents[index])
            }
            buttonHold[index] += 1
        } else if (buttonHold[index] > 0) {
            buttonHold[index] -= 1
            if (buttonHold[index] == 0) {
                renderState.buttonsMask = renderState.buttonsMask and id.mask.inv()
                sink.emit(buttonUpEvents[index])
            }
        }
    }

    private fun pressTrigger(left: Boolean, pressed: Boolean) {
        if (left) {
            if (pressed) {
                if (triggerHoldLeft == 0) {
                    renderState.lt = 65_535
                    sink.emit(leftTriggerOn)
                }
                triggerHoldLeft += 1
            } else if (triggerHoldLeft > 0) {
                triggerHoldLeft -= 1
                if (triggerHoldLeft == 0) {
                    renderState.lt = 0
                    sink.emit(leftTriggerOff)
                }
            }
        } else {
            if (pressed) {
                if (triggerHoldRight == 0) {
                    renderState.rt = 65_535
                    sink.emit(rightTriggerOn)
                }
                triggerHoldRight += 1
            } else if (triggerHoldRight > 0) {
                triggerHoldRight -= 1
                if (triggerHoldRight == 0) {
                    renderState.rt = 0
                    sink.emit(rightTriggerOff)
                }
            }
        }
    }

    private fun updateLeftStick(x: Float, y: Float) {
        computeStick(geometry.leftStickX, geometry.leftStickY, x, y)
        if (stickScratchX == renderState.leftX && stickScratchY == renderState.leftY) return
        renderState.leftX = stickScratchX
        renderState.leftY = stickScratchY
        sink.emit(InputEvent.Stick(StickId.Left, stickScratchX, stickScratchY))
    }

    private fun updateRightStick(x: Float, y: Float) {
        computeStick(geometry.rightStickX, geometry.rightStickY, x, y)
        if (stickScratchX == renderState.rightX && stickScratchY == renderState.rightY) return
        renderState.rightX = stickScratchX
        renderState.rightY = stickScratchY
        sink.emit(InputEvent.Stick(StickId.Right, stickScratchX, stickScratchY))
    }

    private fun computeStick(cx: Float, cy: Float, x: Float, y: Float) {
        val dx = (x - cx) / geometry.stickZoneRadius
        val dy = (y - cy) / geometry.stickZoneRadius
        val distance = sqrt(dx * dx + dy * dy)
        if (distance <= deadZone || distance == 0f) {
            stickScratchX = 0
            stickScratchY = 0
            return
        }
        val scaled = ((distance - deadZone) / (1f - deadZone)).coerceIn(0f, 1f)
        val nx = dx / distance * scaled
        val ny = dy / distance * scaled
        stickScratchX = (nx * MAX_STICK).roundToInt().coerceIn(MIN_STICK, MAX_STICK)
        stickScratchY = (-ny * MAX_STICK).roundToInt().coerceIn(MIN_STICK, MAX_STICK)
    }

    private fun resetLeftStick() {
        if (renderState.leftX != 0 || renderState.leftY != 0) {
            renderState.leftX = 0
            renderState.leftY = 0
            sink.emit(zeroLeftStick)
        }
    }

    private fun resetRightStick() {
        if (renderState.rightX != 0 || renderState.rightY != 0) {
            renderState.rightX = 0
            renderState.rightY = 0
            sink.emit(zeroRightStick)
        }
    }

    private fun updateTouch(x: Float, y: Float, pressure: Float) {
        val nx = ((x - geometry.touchpadLeft) / (geometry.touchpadRight - geometry.touchpadLeft))
            .coerceIn(0f, 1f)
        val ny = ((y - geometry.touchpadTop) / (geometry.touchpadBottom - geometry.touchpadTop))
            .coerceIn(0f, 1f)
        val tx = (nx * 65_535f).roundToInt().coerceIn(0, 65_535)
        val ty = (ny * 65_535f).roundToInt().coerceIn(0, 65_535)
        val tp = (pressure.coerceIn(0f, 1f) * 65_535f).roundToInt().coerceIn(0, 65_535)

        renderState.touchActive = true
        renderState.touchX = tx
        renderState.touchY = ty
        renderState.touchPressure = tp
        sink.emit(
            InputEvent.Touch(
                TouchState(active = true, id = touchPointer, x = tx, y = ty, pressure = tp),
            ),
        )
    }
}
