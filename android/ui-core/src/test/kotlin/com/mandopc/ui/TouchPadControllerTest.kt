package com.mandopc.ui

import com.mandopc.contracts.ButtonId
import com.mandopc.contracts.InputEvent
import com.mandopc.contracts.StickId
import com.mandopc.contracts.TriggerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TouchPadControllerTest {

    private class RecordingSink : InputEventSink {
        val events = ArrayList<InputEvent>()

        override fun emit(event: InputEvent) {
            events.add(event)
        }
    }

    private class CountingSink : InputEventSink {
        var count = 0

        override fun emit(event: InputEvent) {
            count += 1
        }
    }

    private fun controllerWith(sink: InputEventSink): TouchPadController =
        TouchPadController(sink).apply { setBounds(1600f, 900f) }

    private fun centerX(geometry: LayoutGeometry, id: ButtonId): Float {
        val index = geometry.indexOfButton(id)
        require(index >= 0) { "button $id not in layout" }
        return geometry.buttonCenterX(index)
    }

    private fun centerY(geometry: LayoutGeometry, id: ButtonId): Float {
        val index = geometry.indexOfButton(id)
        require(index >= 0) { "button $id not in layout" }
        return geometry.buttonCenterY(index)
    }

    private fun rectCenterX(rect: FloatArray): Float = (rect[0] + rect[2]) * 0.5f

    private fun rectCenterY(rect: FloatArray): Float = (rect[1] + rect[3]) * 0.5f

    @Test
    fun tenFingersPressTenIndependentControls() {
        val sink = RecordingSink()
        val controller = controllerWith(sink)
        val geometry = controller.geometry

        val ids = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        val xs = floatArrayOf(
            geometry.leftStickX + geometry.stickZoneRadius,
            geometry.rightStickX - geometry.stickZoneRadius,
            centerX(geometry, ButtonId.A),
            centerX(geometry, ButtonId.B),
            centerX(geometry, ButtonId.X),
            centerX(geometry, ButtonId.Y),
            centerX(geometry, ButtonId.DpadUp),
            centerX(geometry, ButtonId.DpadDown),
            centerX(geometry, ButtonId.DpadLeft),
            centerX(geometry, ButtonId.DpadRight),
        )
        val ys = floatArrayOf(
            geometry.leftStickY,
            geometry.rightStickY,
            centerY(geometry, ButtonId.A),
            centerY(geometry, ButtonId.B),
            centerY(geometry, ButtonId.X),
            centerY(geometry, ButtonId.Y),
            centerY(geometry, ButtonId.DpadUp),
            centerY(geometry, ButtonId.DpadDown),
            centerY(geometry, ButtonId.DpadLeft),
            centerY(geometry, ButtonId.DpadRight),
        )

        for (index in 0 until 10) {
            controller.onPointerDown(ids[index], xs[index], ys[index], 1f)
        }

        assertEquals(10, controller.renderState.activePointers)
        val expected = ButtonId.A.mask or ButtonId.B.mask or ButtonId.X.mask or
            ButtonId.Y.mask or ButtonId.DpadUp.mask or ButtonId.DpadDown.mask or
            ButtonId.DpadLeft.mask or ButtonId.DpadRight.mask
        assertEquals(expected, controller.renderState.buttonsMask and expected)
        assertTrue(controller.renderState.leftX > 30_000)
        assertTrue(controller.renderState.rightX < -30_000)
        assertTrue(sink.events.isNotEmpty())

        for (id in ids) controller.onPointerUp(id)

        assertEquals(0, controller.renderState.activePointers)
        assertEquals(0, controller.renderState.buttonsMask)
        assertEquals(0, controller.renderState.leftX)
        assertEquals(0, controller.renderState.rightX)
    }

    @Test
    fun twoFingersOnSameButtonKeepItPressedUntilTheLastOneLeaves() {
        val sink = RecordingSink()
        val controller = controllerWith(sink)
        val geometry = controller.geometry
        val x = centerX(geometry, ButtonId.A)
        val y = centerY(geometry, ButtonId.A)

        controller.onPointerDown(1, x, y, 1f)
        controller.onPointerDown(2, x, y, 1f)
        assertTrue((controller.renderState.buttonsMask and ButtonId.A.mask) != 0)
        val downEvents = sink.events.count {
            it is InputEvent.Button && it.id == ButtonId.A && it.pressed
        }
        assertEquals(1, downEvents)

        controller.onPointerUp(1)
        assertTrue((controller.renderState.buttonsMask and ButtonId.A.mask) != 0)

        controller.onPointerUp(2)
        assertEquals(0, controller.renderState.buttonsMask and ButtonId.A.mask)
        val upEvents = sink.events.count {
            it is InputEvent.Button && it.id == ButtonId.A && !it.pressed
        }
        assertEquals(1, upEvents)
    }

    @Test
    fun stickDeadZoneAndAxisConventionAreCorrect() {
        val sink = RecordingSink()
        val controller = controllerWith(sink)
        val geometry = controller.geometry
        val centerX = geometry.leftStickX
        val centerY = geometry.leftStickY

        controller.onPointerDown(1, centerX, centerY, 1f)
        assertTrue(sink.events.filterIsInstance<InputEvent.Stick>().isEmpty())

        controller.onPointerMove(1, centerX + geometry.stickZoneRadius, centerY, 1f)
        val right = sink.events.filterIsInstance<InputEvent.Stick>().last()
        assertEquals(StickId.Left, right.id)
        assertTrue(right.x > 30_000)
        assertTrue(right.y in -1_000..1_000)

        controller.onPointerMove(1, centerX, centerY - geometry.stickZoneRadius, 1f)
        val up = sink.events.filterIsInstance<InputEvent.Stick>().last()
        assertTrue(up.y > 30_000, "protocol Y must grow upwards, got ${up.y}")

        controller.onPointerUp(1)
        assertEquals(0, controller.renderState.leftX)
        assertEquals(0, controller.renderState.leftY)
    }

    @Test
    fun dpadAcceptsTwoDirectionsAtOnce() {
        val sink = RecordingSink()
        val controller = controllerWith(sink)
        val geometry = controller.geometry

        controller.onPointerDown(
            1,
            centerX(geometry, ButtonId.DpadUp),
            centerY(geometry, ButtonId.DpadUp),
            1f,
        )
        controller.onPointerDown(
            2,
            centerX(geometry, ButtonId.DpadRight),
            centerY(geometry, ButtonId.DpadRight),
            1f,
        )

        val mask = controller.renderState.buttonsMask
        assertTrue((mask and ButtonId.DpadUp.mask) != 0)
        assertTrue((mask and ButtonId.DpadRight.mask) != 0)

        controller.cancelAll()
        assertEquals(0, controller.renderState.buttonsMask)
        assertEquals(0, controller.renderState.activePointers)
    }

    @Test
    fun touchpadEmitsNormalizedTouchAndButton() {
        val sink = RecordingSink()
        val controller = controllerWith(sink)
        val geometry = controller.geometry
        val x = (geometry.touchpadLeft + geometry.touchpadRight) * 0.5f
        val y = (geometry.touchpadTop + geometry.touchpadBottom) * 0.5f

        controller.onPointerDown(3, x, y, 0.5f)
        assertTrue((controller.renderState.buttonsMask and ButtonId.Touchpad.mask) != 0)
        val firstTouch = sink.events.filterIsInstance<InputEvent.Touch>().last()
        assertTrue(firstTouch.state.active)
        assertEquals(3, firstTouch.state.id)
        assertTrue(firstTouch.state.x in 30_000..35_000)
        assertTrue(firstTouch.state.pressure in 32_000..33_000)

        controller.onPointerMove(3, geometry.touchpadRight, geometry.touchpadBottom, 1f)
        val lastTouch = sink.events.filterIsInstance<InputEvent.Touch>().last()
        assertEquals(65_535, lastTouch.state.x)
        assertEquals(65_535, lastTouch.state.y)

        controller.onPointerUp(3)
        val released = sink.events.filterIsInstance<InputEvent.Touch>().last()
        assertEquals(false, released.state.active)
        assertEquals(0, controller.renderState.buttonsMask and ButtonId.Touchpad.mask)
    }

    @Test
    fun triggersAreDigitalButIndependent() {
        val sink = RecordingSink()
        val controller = controllerWith(sink)
        val geometry = controller.geometry

        controller.onPointerDown(
            1,
            rectCenterX(geometry.ltRect),
            rectCenterY(geometry.ltRect),
            1f,
        )
        controller.onPointerDown(
            2,
            rectCenterX(geometry.rtRect),
            rectCenterY(geometry.rtRect),
            1f,
        )
        assertEquals(65_535, controller.renderState.lt)
        assertEquals(65_535, controller.renderState.rt)

        val left = sink.events.filterIsInstance<InputEvent.Trigger>()
            .last { it.id == TriggerId.Left }
        assertEquals(65_535, left.value)

        controller.onPointerUp(1)
        assertEquals(0, controller.renderState.lt)
        assertEquals(65_535, controller.renderState.rt)
    }

    @Test
    fun cancelAllReleasesEverything() {
        val sink = RecordingSink()
        val controller = controllerWith(sink)
        val geometry = controller.geometry

        controller.onPointerDown(
            1,
            centerX(geometry, ButtonId.A),
            centerY(geometry, ButtonId.A),
            1f,
        )
        controller.onPointerDown(2, geometry.leftStickX, geometry.leftStickY - 50f, 1f)
        controller.onPointerDown(
            3,
            rectCenterX(geometry.ltRect),
            rectCenterY(geometry.ltRect),
            1f,
        )
        controller.onPointerDown(
            4,
            (geometry.touchpadLeft + geometry.touchpadRight) * 0.5f,
            (geometry.touchpadTop + geometry.touchpadBottom) * 0.5f,
            1f,
        )

        controller.cancelAll()

        assertEquals(0, controller.renderState.activePointers)
        assertEquals(0, controller.renderState.buttonsMask)
        assertEquals(0, controller.renderState.lt)
        assertEquals(0, controller.renderState.leftX)
        assertEquals(false, controller.renderState.touchActive)
    }

    @Test
    fun tenFingerMovesStayUnderFiveMillisecondsPerEvent() {
        val sink = CountingSink()
        val controller = controllerWith(sink)
        val geometry = controller.geometry
        val xs = floatArrayOf(
            geometry.leftStickX + geometry.stickZoneRadius,
            geometry.rightStickX - geometry.stickZoneRadius,
            centerX(geometry, ButtonId.A),
            centerX(geometry, ButtonId.B),
            centerX(geometry, ButtonId.X),
            centerX(geometry, ButtonId.Y),
            centerX(geometry, ButtonId.DpadUp),
            centerX(geometry, ButtonId.DpadDown),
            centerX(geometry, ButtonId.DpadLeft),
            centerX(geometry, ButtonId.DpadRight),
        )
        val ys = floatArrayOf(
            geometry.leftStickY,
            geometry.rightStickY,
            centerY(geometry, ButtonId.A),
            centerY(geometry, ButtonId.B),
            centerY(geometry, ButtonId.X),
            centerY(geometry, ButtonId.Y),
            centerY(geometry, ButtonId.DpadUp),
            centerY(geometry, ButtonId.DpadDown),
            centerY(geometry, ButtonId.DpadLeft),
            centerY(geometry, ButtonId.DpadRight),
        )

        for (id in 0 until 10) controller.onPointerDown(id, xs[id], ys[id], 1f)

        repeat(200) { frame ->
            val offset = ((frame % 40) - 20) * geometry.stickZoneRadius / 40f
            for (id in 0 until 10) {
                controller.onPointerMove(id, xs[id] + offset, ys[id] + offset, 1f)
            }
        }

        val moves = 2_000
        val start = System.nanoTime()
        repeat(200) { frame ->
            val offset = ((frame % 40) - 20) * geometry.stickZoneRadius / 40f
            for (id in 0 until 10) {
                controller.onPointerMove(id, xs[id] + offset, ys[id] + offset, 1f)
            }
        }
        val averageNs = (System.nanoTime() - start) / moves
        assertTrue(
            averageNs < 5_000_000,
            "average ${averageNs / 1_000} us per event exceeds 5 ms",
        )
    }
}
