package com.mandopc.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.mandopc.contracts.ButtonId
import com.mandopc.ui.InputEventSink
import com.mandopc.ui.LayoutGeometry
import com.mandopc.ui.PadRenderState
import com.mandopc.ui.TouchPadController

class XboxControllerView(context: Context) : View(context) {

    private companion object {
        val BACKGROUND: Int = 0xFF101418.toInt()
        val SURFACE: Int = 0xFF232A31.toInt()
        val SURFACE_ACTIVE: Int = 0xFF2E3A44.toInt()
        val ACCENT: Int = 0xFF3DDC97.toInt()
        val OUTLINE: Int = 0xFF3A444D.toInt()
        val TEXT: Int = 0xFFE6EDF3.toInt()
        val KNOB: Int = 0xFF8B9AA8.toInt()

        fun labelFor(id: ButtonId): String = when (id) {
            ButtonId.DpadUp -> "▲"
            ButtonId.DpadDown -> "▼"
            ButtonId.DpadLeft -> "◀"
            ButtonId.DpadRight -> "▶"
            ButtonId.Guide -> "G"
            ButtonId.Capture -> "CAP"
            else -> id.name
        }
    }

    private var externalSink: InputEventSink? = null

    private val viewSink = InputEventSink { event ->
        externalSink?.emit(event)
        postInvalidateOnAnimation()
    }

    private val pad = TouchPadController(viewSink)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val labels = Array(ButtonId.entries.size) {
        labelFor(ButtonId.entries[it])
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun setEventSink(sink: InputEventSink?) {
        externalSink = sink
    }

    fun cancelAll() {
        pad.cancelAll()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pad.setBounds(w.toFloat(), h.toFloat())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        requestUnbufferedDispatch(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                pad.onPointerDown(
                    event.getPointerId(index),
                    event.getX(index),
                    event.getY(index),
                    event.getPressure(index),
                )
            }

            MotionEvent.ACTION_MOVE -> processMoves(event, includeHistory = true)

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                processMoves(event, includeHistory = false)
                pad.onPointerUp(event.getPointerId(event.actionIndex))
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }

            MotionEvent.ACTION_CANCEL -> pad.cancelAll()

            else -> return false
        }
        return true
    }

    private fun processMoves(event: MotionEvent, includeHistory: Boolean) {
        val pointerCount = event.pointerCount
        if (includeHistory) {
            val historySize = event.historySize
            var h = 0
            while (h < historySize) {
                var i = 0
                while (i < pointerCount) {
                    pad.onPointerMove(
                        event.getPointerId(i),
                        event.getHistoricalX(i, h),
                        event.getHistoricalY(i, h),
                        event.getHistoricalPressure(i, h),
                    )
                    i += 1
                }
                h += 1
            }
        }
        var i = 0
        while (i < pointerCount) {
            pad.onPointerMove(
                event.getPointerId(i),
                event.getX(i),
                event.getY(i),
                event.getPressure(i),
            )
            i += 1
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val geometry = pad.geometry
        val state = pad.renderState
        canvas.drawColor(BACKGROUND)
        if (geometry.width <= 1f || geometry.height <= 1f) return

        drawTrigger(canvas, geometry.ltRect, state.lt > 0, "LT")
        drawTrigger(canvas, geometry.rtRect, state.rt > 0, "RT")

        var index = 0
        while (index < geometry.buttonCount) {
            val id = geometry.buttonIdAt(index)
            val pressed = (state.buttonsMask and id.mask) != 0
            drawButton(canvas, geometry, index, id, pressed)
            index += 1
        }

        drawStick(
            canvas,
            geometry.leftStickX,
            geometry.leftStickY,
            geometry.stickZoneRadius,
            geometry.knobRadius,
            state.leftX,
            state.leftY,
        )
        drawStick(
            canvas,
            geometry.rightStickX,
            geometry.rightStickY,
            geometry.stickZoneRadius,
            geometry.knobRadius,
            state.rightX,
            state.rightY,
        )
        drawTouchpad(canvas, geometry, state)
    }

    private fun drawTrigger(canvas: Canvas, rect: FloatArray, pressed: Boolean, label: String) {
        val left = rect[0]
        val top = rect[1]
        val right = rect[2]
        val bottom = rect[3]
        val radius = (bottom - top) * 0.22f
        fillPaint.color = if (pressed) ACCENT else SURFACE
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, fillPaint)
        strokePaint.color = OUTLINE
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, strokePaint)

        textPaint.color = TEXT
        textPaint.textSize = (bottom - top) * 0.5f
        val centerY = (top + bottom) * 0.5f - (textPaint.ascent() + textPaint.descent()) * 0.5f
        canvas.drawText(label, (left + right) * 0.5f, centerY, textPaint)
    }

    private fun drawButton(
        canvas: Canvas,
        geometry: LayoutGeometry,
        index: Int,
        id: ButtonId,
        pressed: Boolean,
    ) {
        val offset = index * 4
        val left = geometry.buttonRects[offset]
        val top = geometry.buttonRects[offset + 1]
        val right = geometry.buttonRects[offset + 2]
        val bottom = geometry.buttonRects[offset + 3]
        val radius = (bottom - top) * 0.22f

        fillPaint.color = if (pressed) ACCENT else SURFACE
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, fillPaint)
        strokePaint.color = OUTLINE
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, strokePaint)

        val label = labels[id.ordinal]
        textPaint.color = TEXT
        textPaint.textSize = (bottom - top) * if (label.length >= 5) 0.32f else 0.55f
        val centerY = (top + bottom) * 0.5f - (textPaint.ascent() + textPaint.descent()) * 0.5f
        canvas.drawText(label, (left + right) * 0.5f, centerY, textPaint)
    }

    private fun drawStick(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        zoneRadius: Float,
        knobRadius: Float,
        x: Int,
        y: Int,
    ) {
        fillPaint.color = SURFACE
        canvas.drawCircle(centerX, centerY, zoneRadius, fillPaint)
        strokePaint.color = OUTLINE
        canvas.drawCircle(centerX, centerY, zoneRadius, strokePaint)

        val travel = (zoneRadius - knobRadius).coerceAtLeast(1f)
        val knobX = centerX + (x / 32_767f) * travel
        val knobY = centerY - (y / 32_767f) * travel
        fillPaint.color = if (x != 0 || y != 0) ACCENT else KNOB
        canvas.drawCircle(knobX, knobY, knobRadius, fillPaint)
    }

    private fun drawTouchpad(canvas: Canvas, geometry: LayoutGeometry, state: PadRenderState) {
        val left = geometry.touchpadLeft
        val top = geometry.touchpadTop
        val right = geometry.touchpadRight
        val bottom = geometry.touchpadBottom
        val radius = (bottom - top) * 0.18f

        fillPaint.color = if (state.touchActive) SURFACE_ACTIVE else SURFACE
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, fillPaint)
        strokePaint.color = OUTLINE
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, strokePaint)

        textPaint.color = TEXT
        textPaint.textSize = (bottom - top) * 0.30f
        val centerY = (top + bottom) * 0.5f - (textPaint.ascent() + textPaint.descent()) * 0.5f
        canvas.drawText("TOUCH", (left + right) * 0.5f, centerY, textPaint)

        if (state.touchActive) {
            val tx = left + (state.touchX / 65_535f) * (right - left)
            val ty = top + (state.touchY / 65_535f) * (bottom - top)
            fillPaint.color = ACCENT
            canvas.drawCircle(tx, ty, geometry.knobRadius * 0.35f, fillPaint)
        }
    }
}
