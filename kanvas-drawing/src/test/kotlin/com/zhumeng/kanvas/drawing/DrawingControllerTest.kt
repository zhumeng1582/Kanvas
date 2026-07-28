/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas.drawing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import com.zhumeng.kanvas.core.KlineCandle
import com.zhumeng.kanvas.core.KlineSeries
import com.zhumeng.kanvas.core.KlineViewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DrawingControllerTest {
    private val candles = (1L..5L).reversed().map { timestamp ->
        KlineCandle(timestamp, 10.0, 12.0, 8.0, 11.0, 1.0)
    }
    private val series = KlineSeries.of(candles)
    private val space = DrawingCoordinateSpace(
        series = series,
        viewport = KlineViewport(candleWidthPx = 9f, candleSpacingPx = 1f),
        plotRect = Rect(0f, 0f, 100f, 100f),
        minValue = 0.0,
        maxValue = 20.0,
    )

    @Test
    fun `two point drawing follows prepared drawing editing exited lifecycle`() {
        val saved = mutableListOf<List<DrawingOverlay>>()
        val controller = DrawingController(store = DrawingOverlayStore { _, overlays -> saved += overlays })
        controller.switchSymbol("BTC-USDT")

        val overlay = controller.prepare(DrawingTypeDescriptor.TwoPointLine)
        assertIs<DrawingState.Drawing>(controller.snapshot.state)
        assertIs<DrawingState.Drawing>(controller.confirmPoint(DrawingPoint(5L, 10.0)))
        assertIs<DrawingState.Editing>(controller.confirmPoint(DrawingPoint(3L, 12.0)))
        assertTrue(controller.snapshot.overlays.single().isComplete)
        assertEquals(overlay.id, controller.snapshot.overlays.single().id)
        assertEquals(1, saved.last().size)

        controller.finishEditing()
        assertIs<DrawingState.Exited>(controller.snapshot.state)
    }

    @Test
    fun `screen coordinates round trip while persisted coordinates remain stable`() {
        val point = DrawingPoint(3L, 12.0)
        val offset = assertNotNull(space.project(point))
        val roundTrip = assertNotNull(space.unproject(offset))

        assertEquals(point.timestampMillis, roundTrip.timestampMillis)
        assertEquals(point.value, roundTrip.value, 0.0001)
    }

    @Test
    fun `selection honors z order and locked overlays cannot move`() {
        val controller = DrawingController()
        controller.switchSymbol("BTC-USDT")
        controller.prepare(DrawingTypeDescriptor.TwoPointLine)
        controller.confirmPoint(DrawingPoint(5L, 10.0))
        controller.confirmPoint(DrawingPoint(3L, 10.0))
        controller.updateSelected { it.copy(locked = true) }

        val center = assertNotNull(space.project(DrawingPoint(4L, 10.0)))
        assertNotNull(controller.select(center, space))
        assertFalse(controller.moveSelectedBy(1L, 1.0))
    }

    @Test
    fun `weak and strong magnet follow candle OHLC distance`() {
        val controller = DrawingController()
        controller.switchSymbol("BTC-USDT")
        val close = assertNotNull(space.project(DrawingPoint(4L, 11.0)))

        controller.setMagnetMode(DrawingMagnetMode.Weak)
        assertEquals(11.0, assertNotNull(controller.snap(close + Offset(0f, 2f), space, series.candles)).value)
        val far = assertNotNull(controller.snap(close + Offset(0f, 30f), space, series.candles, minDistancePx = 5f))
        assertTrue(far.value != 11.0)

        controller.setMagnetMode(DrawingMagnetMode.Strong)
        assertTrue(assertNotNull(controller.snap(close + Offset(0f, 30f), space, series.candles)).value in setOf(8.0, 10.0, 11.0, 12.0))
    }

    @Test
    fun `visibility ordering and remove all update controller state`() {
        val controller = DrawingController()
        controller.switchSymbol("BTC-USDT")
        repeat(2) {
            controller.prepare(DrawingTypeDescriptor.TwoPointLine)
            controller.confirmPoint(DrawingPoint(5L, 10.0 + it))
            controller.confirmPoint(DrawingPoint(3L, 10.0 + it))
            controller.finishEditing()
        }
        val top = controller.snapshot.overlays.maxBy(DrawingOverlay::zIndex)
        val hit = assertNotNull(space.project(DrawingPoint(4L, 11.0)))
        assertEquals(top.id, assertNotNull(controller.select(hit, space)).id)
        assertTrue(controller.isSelectedOnTop())
        assertTrue(controller.moveSelectedToBottom())
        assertTrue(controller.isSelectedOnBottom())

        controller.setVisible(false)
        assertFalse(controller.snapshot.visible)
        assertIs<DrawingState.Exited>(controller.snapshot.state)
        controller.setVisible(true)
        assertTrue(controller.snapshot.visible)
        assertIs<DrawingState.Prepared>(controller.snapshot.state)

        controller.removeAll()
        assertTrue(controller.snapshot.overlays.isEmpty())
    }

    @Test
    fun `lock line style and continuous operations update selected drawings`() {
        val controller = DrawingController()
        controller.switchSymbol("BTC-USDT")
        controller.prepare(DrawingTypeDescriptor.TwoPointLine)
        controller.confirmPoint(DrawingPoint(5L, 10.0))
        controller.confirmPoint(DrawingPoint(3L, 11.0))

        assertTrue(controller.setSelectedLocked(true))
        assertTrue(controller.snapshot.overlays.single().locked)
        assertTrue(
            controller.setSelectedLineStyle(
                color = Color.Red,
                strokeWidthPx = 3f,
                lineType = "dashed",
            ),
        )
        assertEquals(Color.Red, controller.snapshot.overlays.single().line.color)
        assertEquals(3f, controller.snapshot.overlays.single().line.strokeWidthPx)
        assertTrue(controller.snapshot.overlays.single().line.dashed)

        controller.setContinuous(true)
        controller.finishEditing()
        controller.prepare(DrawingTypeDescriptor.TwoPointLine)
        controller.confirmPoint(DrawingPoint(5L, 12.0))
        assertIs<DrawingState.Drawing>(controller.confirmPoint(DrawingPoint(3L, 13.0)))
        assertEquals(3, controller.snapshot.overlays.size)

        controller.setContinuous(false)
        assertFalse(controller.snapshot.continuous)
        assertIs<DrawingState.Prepared>(controller.snapshot.state)
        assertEquals(2, controller.snapshot.overlays.size)
    }

    @Test
    fun `all built in tools are registered and can be prepared`() {
        val registry = DrawingToolRegistry()
        assertEquals(DrawingTypeDescriptor.BuiltIns.toSet(), registry.supportedTypes)

        DrawingTypeDescriptor.BuiltIns.forEach { type ->
            val controller = DrawingController(registry)
            controller.switchSymbol("BTC-USDT")
            val overlay = controller.prepare(type)
            assertEquals(type.steps, overlay.points.size)
            assertNotNull(controller.toolFor(type))
        }
    }

    @Test
    fun `undo and redo restore committed drawings and clear an in progress gesture`() {
        val saved = mutableListOf<List<DrawingOverlay>>()
        val controller = DrawingController(store = DrawingOverlayStore { _, overlays -> saved += overlays })
        controller.switchSymbol("BTC-USDT")
        controller.prepare(DrawingTypeDescriptor.HorizontalLine)
        controller.confirmPoint(DrawingPoint(4L, 11.0))
        assertTrue(controller.canUndo)
        assertFalse(controller.canRedo)

        assertTrue(controller.undo())
        assertTrue(controller.snapshot.overlays.isEmpty())
        assertTrue(controller.canRedo)

        assertTrue(controller.redo())
        assertEquals(DrawingTypeDescriptor.HorizontalLine, controller.snapshot.overlays.single().type)
        assertEquals(1, saved.last().size)
    }

    @Test
    fun `axis rectangle ray and fibonacci tools hit their visible geometry`() {
        val registry = DrawingToolRegistry()
        fun overlay(type: DrawingTypeDescriptor, vararg points: DrawingPoint) = DrawingOverlay(
            symbol = "BTC-USDT",
            type = type,
            points = points.toList(),
        )

        val horizontal = overlay(DrawingTypeDescriptor.HorizontalLine, DrawingPoint(4L, 10.0))
        assertTrue(registry[horizontal.type]!!.hitTest(horizontal, space, Offset(50f, 50f), 1f))

        val rectangle = overlay(
            DrawingTypeDescriptor.Rectangle,
            DrawingPoint(5L, 14.0),
            DrawingPoint(3L, 6.0),
        )
        val topCenter = assertNotNull(space.project(DrawingPoint(4L, 14.0)))
        assertTrue(registry[rectangle.type]!!.hitTest(rectangle, space, topCenter, 2f))

        val diagonalPoints = arrayOf(DrawingPoint(5L, 14.0), DrawingPoint(3L, 6.0))
        val ray = overlay(DrawingTypeDescriptor.RayLine, *diagonalPoints)
        val rayPoint = assertNotNull(space.project(DrawingPoint(4L, 10.0)))
        assertTrue(registry[ray.type]!!.hitTest(ray, space, rayPoint, 2f))

        val fibonacci = overlay(DrawingTypeDescriptor.FibonacciRetracement, *diagonalPoints)
        assertTrue(registry[fibonacci.type]!!.hitTest(fibonacci, space, topCenter, 2f))
    }
}
