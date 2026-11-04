package com.mandopc.ui

import com.mandopc.contracts.ButtonId

/**
 * Geometria del layout Xbox en coordenadas de vista.
 *
 * Orden clasico de Xbox en horizontal:
 *  - stick izquierdo arriba a la izquierda, cruceta abajo a la izquierda;
 *  - ABXY arriba a la derecha, stick derecho abajo a la derecha;
 *  - hombros y gatillos arriba; Start/Back/Guide al centro.
 *
 * No asigna nada al hacer hit-test: devuelve una zona codificada como Int.
 */
class LayoutGeometry(val width: Float, val height: Float) {

    companion object {
        const val ZONE_NONE = 0
        const val ZONE_LEFT_STICK = 1
        const val ZONE_RIGHT_STICK = 2
        const val ZONE_LT = 3
        const val ZONE_RT = 4
        const val ZONE_TOUCHPAD = 5
        const val ZONE_BUTTON_BASE = 100

        fun buttonZone(id: ButtonId): Int = ZONE_BUTTON_BASE + id.ordinal
    }

    val leftStickX: Float = width * 0.18f
    val leftStickY: Float = height * 0.38f
    val rightStickX: Float = width * 0.82f
    val rightStickY: Float = height * 0.72f

    val stickZoneRadius: Float = height * 0.17f
    val knobRadius: Float = height * 0.09f

    val touchpadLeft: Float = width * 0.39f
    val touchpadTop: Float = height * 0.84f
    val touchpadRight: Float = width * 0.61f
    val touchpadBottom: Float = height * 0.97f

    val ltRect: FloatArray = FloatArray(4)
    val rtRect: FloatArray = FloatArray(4)

    private val ids: IntArray = IntArray(ButtonId.entries.size)
    private val rects: FloatArray = FloatArray(ButtonId.entries.size * 4)
    private var count: Int = 0

    val buttonCount: Int get() = count
    val buttonIds: IntArray get() = ids
    val buttonRects: FloatArray get() = rects

    init {
        setRect(ltRect, width * 0.07f, height * 0.09f, width * 0.065f, height * 0.055f)
        setRect(rtRect, width * 0.93f, height * 0.09f, width * 0.065f, height * 0.055f)

        add(ButtonId.LB, width * 0.22f, height * 0.09f, width * 0.075f, height * 0.055f)
        add(ButtonId.RB, width * 0.78f, height * 0.09f, width * 0.075f, height * 0.055f)

        val bx = width * 0.82f
        val by = height * 0.38f
        val bo = height * 0.095f
        val bh = height * 0.048f
        add(ButtonId.A, bx, by + bo, bh, bh)
        add(ButtonId.B, bx + bo, by, bh, bh)
        add(ButtonId.X, bx - bo, by, bh, bh)
        add(ButtonId.Y, bx, by - bo, bh, bh)

        val dx = width * 0.18f
        val dy = height * 0.72f
        val da = height * 0.085f
        val dh = height * 0.048f
        add(ButtonId.DpadUp, dx, dy - da, dh, dh)
        add(ButtonId.DpadDown, dx, dy + da, dh, dh)
        add(ButtonId.DpadLeft, dx - da, dy, dh, dh)
        add(ButtonId.DpadRight, dx + da, dy, dh, dh)

        val ch = height * 0.040f
        add(ButtonId.Back, width * 0.43f, height * 0.12f, width * 0.030f, ch)
        add(ButtonId.Start, width * 0.57f, height * 0.12f, width * 0.030f, ch)
        add(ButtonId.Guide, width * 0.50f, height * 0.05f, width * 0.030f, ch)
        add(ButtonId.Capture, width * 0.35f, height * 0.12f, width * 0.030f, ch)
    }

    fun hitTest(x: Float, y: Float): Int {
        var i = 0
        while (i < count) {
            if (insideAt(i, x, y)) {
                return buttonZone(ButtonId.entries[ids[i]])
            }
            i += 1
        }
        if (inside(ltRect, x, y)) return ZONE_LT
        if (inside(rtRect, x, y)) return ZONE_RT
        if (insideCircle(x, y, leftStickX, leftStickY, stickZoneRadius)) return ZONE_LEFT_STICK
        if (insideCircle(x, y, rightStickX, rightStickY, stickZoneRadius)) return ZONE_RIGHT_STICK
        if (x in touchpadLeft..touchpadRight && y in touchpadTop..touchpadBottom) return ZONE_TOUCHPAD
        return ZONE_NONE
    }

    fun buttonIdAt(index: Int): ButtonId = ButtonId.entries[ids[index]]

    fun indexOfButton(id: ButtonId): Int {
        var i = 0
        while (i < count) {
            if (ids[i] == id.ordinal) return i
            i += 1
        }
        return -1
    }

    fun buttonCenterX(index: Int): Float = (rects[index * 4] + rects[index * 4 + 2]) * 0.5f

    fun buttonCenterY(index: Int): Float = (rects[index * 4 + 1] + rects[index * 4 + 3]) * 0.5f

    private fun add(id: ButtonId, cx: Float, cy: Float, halfW: Float, halfH: Float) {
        ids[count] = id.ordinal
        setRectAt(count, cx, cy, halfW, halfH)
        count += 1
    }

    private fun setRect(rect: FloatArray, cx: Float, cy: Float, halfW: Float, halfH: Float) {
        rect[0] = cx - halfW
        rect[1] = cy - halfH
        rect[2] = cx + halfW
        rect[3] = cy + halfH
    }

    private fun setRectAt(index: Int, cx: Float, cy: Float, halfW: Float, halfH: Float) {
        val offset = index * 4
        rects[offset] = cx - halfW
        rects[offset + 1] = cy - halfH
        rects[offset + 2] = cx + halfW
        rects[offset + 3] = cy + halfH
    }

    private fun insideAt(index: Int, x: Float, y: Float): Boolean {
        val offset = index * 4
        return x >= rects[offset] && x <= rects[offset + 2] &&
            y >= rects[offset + 1] && y <= rects[offset + 3]
    }

    private fun inside(rect: FloatArray, x: Float, y: Float): Boolean =
        x >= rect[0] && x <= rect[2] && y >= rect[1] && y <= rect[3]

    private fun insideCircle(x: Float, y: Float, cx: Float, cy: Float, radius: Float): Boolean {
        val dx = x - cx
        val dy = y - cy
        return dx * dx + dy * dy <= radius * radius
    }
}
