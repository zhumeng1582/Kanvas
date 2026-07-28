/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas.drawing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs

interface DrawingTool {
    val type: DrawingTypeDescriptor

    fun hitTest(
        overlay: DrawingOverlay,
        space: DrawingCoordinateSpace,
        position: Offset,
        maxDistancePx: Float,
    ): Boolean

    fun DrawScope.draw(
        overlay: DrawingOverlay,
        space: DrawingCoordinateSpace,
        selected: Boolean,
        pointer: Offset?,
        config: DrawingRenderConfig = DrawingRenderConfig(),
        densityScale: Float = 1f,
    )
}

private fun DrawingOverlay.projectedPoints(space: DrawingCoordinateSpace, pointer: Offset?): List<Offset> =
    points.mapNotNull { it?.let(space::project) } + listOfNotNull(pointer)

private fun DrawingLineStyle.stroke(densityScale: Float): Stroke = Stroke(
    width = strokeWidthPx * densityScale,
    pathEffect = if (dashed) {
        PathEffect.dashPathEffect(floatArrayOf(dashOnPx * densityScale, dashOffPx * densityScale))
    } else {
        null
    },
)

private fun DrawScope.drawHandles(
    points: List<Offset>,
    overlay: DrawingOverlay,
    config: DrawingRenderConfig,
    densityScale: Float,
) {
    val pointStyle = config.drawPoint
    points.forEach { point ->
        val radius = pointStyle.radiusPx * densityScale
        val borderWidth = pointStyle.borderWidthPx * densityScale
        drawCircle(pointStyle.borderColor ?: overlay.line.color, radius, point)
        drawCircle(
            pointStyle.color ?: Color.White,
            (radius - borderWidth).coerceAtLeast(0f),
            point,
        )
    }
}

abstract class TwoPointDrawingTool : DrawingTool {
    protected fun projected(overlay: DrawingOverlay, space: DrawingCoordinateSpace): List<Offset> =
        overlay.points.mapNotNull { it?.let(space::project) }

    protected fun DrawScope.handles(
        overlay: DrawingOverlay,
        points: List<Offset>,
        selected: Boolean,
        config: DrawingRenderConfig,
        densityScale: Float,
    ) {
        if (selected) drawHandles(points, overlay, config, densityScale)
    }
}

class TwoPointLineDrawingTool : TwoPointDrawingTool() {
    override val type = DrawingTypeDescriptor.TwoPointLine

    override fun hitTest(overlay: DrawingOverlay, space: DrawingCoordinateSpace, position: Offset, maxDistancePx: Float): Boolean {
        val points = projected(overlay, space)
        return points.any { (it - position).getDistance() <= maxDistancePx } ||
            (points.size == 2 && distanceToSegment(position, points[0], points[1]) <= maxDistancePx)
    }

    override fun DrawScope.draw(
        overlay: DrawingOverlay,
        space: DrawingCoordinateSpace,
        selected: Boolean,
        pointer: Offset?,
        config: DrawingRenderConfig,
        densityScale: Float,
    ) {
        val points = overlay.projectedPoints(space, pointer)
        if (points.size >= 2) drawLine(overlay.line.color, points[0], points[1], strokeWidth = overlay.line.strokeWidthPx * densityScale, pathEffect = overlay.line.stroke(densityScale).pathEffect)
        handles(overlay, points, selected, config, densityScale)
    }
}

class RayLineDrawingTool : TwoPointDrawingTool() {
    override val type = DrawingTypeDescriptor.RayLine

    override fun hitTest(overlay: DrawingOverlay, space: DrawingCoordinateSpace, position: Offset, maxDistancePx: Float): Boolean {
        val points = projected(overlay, space)
        return points.any { (it - position).getDistance() <= maxDistancePx } ||
            (points.size == 2 && distanceToRay(position, points[0], points[1]) <= maxDistancePx)
    }

    override fun DrawScope.draw(overlay: DrawingOverlay, space: DrawingCoordinateSpace, selected: Boolean, pointer: Offset?, config: DrawingRenderConfig, densityScale: Float) {
        val points = overlay.projectedPoints(space, pointer)
        if (points.size >= 2) {
            val end = rayEndInRect(points[0], points[1], space.plotRect) ?: points[1]
            drawLine(overlay.line.color, points[0], end, strokeWidth = overlay.line.strokeWidthPx * densityScale, pathEffect = overlay.line.stroke(densityScale).pathEffect)
        }
        handles(overlay, points, selected, config, densityScale)
    }
}

class StraightLineDrawingTool : TwoPointDrawingTool() {
    override val type = DrawingTypeDescriptor.StraightLine

    override fun hitTest(overlay: DrawingOverlay, space: DrawingCoordinateSpace, position: Offset, maxDistancePx: Float): Boolean {
        val points = projected(overlay, space)
        return points.any { (it - position).getDistance() <= maxDistancePx } ||
            (points.size == 2 && distanceToInfiniteLine(position, points[0], points[1]) <= maxDistancePx)
    }

    override fun DrawScope.draw(overlay: DrawingOverlay, space: DrawingCoordinateSpace, selected: Boolean, pointer: Offset?, config: DrawingRenderConfig, densityScale: Float) {
        val points = overlay.projectedPoints(space, pointer)
        if (points.size >= 2) {
            lineIntersectionsWithRect(points[0], points[1], space.plotRect)?.let { (start, end) ->
                drawLine(overlay.line.color, start, end, strokeWidth = overlay.line.strokeWidthPx * densityScale, pathEffect = overlay.line.stroke(densityScale).pathEffect)
            }
        }
        handles(overlay, points, selected, config, densityScale)
    }
}

abstract class OnePointAxisDrawingTool : DrawingTool {
    protected fun point(overlay: DrawingOverlay, space: DrawingCoordinateSpace): Offset? = overlay.points.firstOrNull()?.let(space::project)

    protected fun DrawScope.handle(overlay: DrawingOverlay, point: Offset?, selected: Boolean, config: DrawingRenderConfig, densityScale: Float) {
        if (selected && point != null) drawHandles(listOf(point), overlay, config, densityScale)
    }
}

class HorizontalLineDrawingTool : OnePointAxisDrawingTool() {
    override val type = DrawingTypeDescriptor.HorizontalLine
    override fun hitTest(overlay: DrawingOverlay, space: DrawingCoordinateSpace, position: Offset, maxDistancePx: Float): Boolean =
        point(overlay, space)?.let { abs(position.y - it.y) <= maxDistancePx } == true

    override fun DrawScope.draw(overlay: DrawingOverlay, space: DrawingCoordinateSpace, selected: Boolean, pointer: Offset?, config: DrawingRenderConfig, densityScale: Float) {
        val anchor = point(overlay, space) ?: pointer
        anchor?.let { drawLine(overlay.line.color, Offset(space.plotRect.left, it.y), Offset(space.plotRect.right, it.y), strokeWidth = overlay.line.strokeWidthPx * densityScale, pathEffect = overlay.line.stroke(densityScale).pathEffect) }
        handle(overlay, anchor, selected, config, densityScale)
    }
}

class VerticalLineDrawingTool : OnePointAxisDrawingTool() {
    override val type = DrawingTypeDescriptor.VerticalLine
    override fun hitTest(overlay: DrawingOverlay, space: DrawingCoordinateSpace, position: Offset, maxDistancePx: Float): Boolean =
        point(overlay, space)?.let { abs(position.x - it.x) <= maxDistancePx } == true

    override fun DrawScope.draw(overlay: DrawingOverlay, space: DrawingCoordinateSpace, selected: Boolean, pointer: Offset?, config: DrawingRenderConfig, densityScale: Float) {
        val anchor = point(overlay, space) ?: pointer
        anchor?.let { drawLine(overlay.line.color, Offset(it.x, space.plotRect.top), Offset(it.x, space.plotRect.bottom), strokeWidth = overlay.line.strokeWidthPx * densityScale, pathEffect = overlay.line.stroke(densityScale).pathEffect) }
        handle(overlay, anchor, selected, config, densityScale)
    }
}

class RectangleDrawingTool : TwoPointDrawingTool() {
    override val type = DrawingTypeDescriptor.Rectangle

    override fun hitTest(overlay: DrawingOverlay, space: DrawingCoordinateSpace, position: Offset, maxDistancePx: Float): Boolean {
        val points = projected(overlay, space)
        if (points.size != 2) return false
        val rect = rectFromPoints(points[0], points[1])
        return points.any { (it - position).getDistance() <= maxDistancePx } ||
            (position.x in (rect.left - maxDistancePx)..(rect.right + maxDistancePx) &&
                position.y in (rect.top - maxDistancePx)..(rect.bottom + maxDistancePx) &&
                minOf(abs(position.x - rect.left), abs(position.x - rect.right), abs(position.y - rect.top), abs(position.y - rect.bottom)) <= maxDistancePx)
    }

    override fun DrawScope.draw(overlay: DrawingOverlay, space: DrawingCoordinateSpace, selected: Boolean, pointer: Offset?, config: DrawingRenderConfig, densityScale: Float) {
        val points = overlay.projectedPoints(space, pointer)
        if (points.size >= 2) {
            val rect = rectFromPoints(points[0], points[1])
            drawRect(overlay.line.color.copy(alpha = 0.08f), topLeft = rect.topLeft, size = rect.size)
            drawRect(overlay.line.color, topLeft = rect.topLeft, size = rect.size, style = overlay.line.stroke(densityScale))
        }
        handles(overlay, points, selected, config, densityScale)
    }
}

class FibonacciRetracementDrawingTool : TwoPointDrawingTool() {
    override val type = DrawingTypeDescriptor.FibonacciRetracement
    private val levels = listOf(0f, 0.236f, 0.382f, 0.5f, 0.618f, 0.786f, 1f)

    override fun hitTest(overlay: DrawingOverlay, space: DrawingCoordinateSpace, position: Offset, maxDistancePx: Float): Boolean {
        val points = projected(overlay, space)
        if (points.size != 2) return false
        val left = minOf(points[0].x, points[1].x)
        val right = maxOf(points[0].x, points[1].x)
        return points.any { (it - position).getDistance() <= maxDistancePx } || levels.any { level ->
            val y = points[0].y + (points[1].y - points[0].y) * level
            position.x in (left - maxDistancePx)..(right + maxDistancePx) && abs(position.y - y) <= maxDistancePx
        }
    }

    override fun DrawScope.draw(overlay: DrawingOverlay, space: DrawingCoordinateSpace, selected: Boolean, pointer: Offset?, config: DrawingRenderConfig, densityScale: Float) {
        val points = overlay.projectedPoints(space, pointer)
        if (points.size >= 2) {
            val left = minOf(points[0].x, points[1].x)
            val right = maxOf(points[0].x, points[1].x)
            levels.forEach { level ->
                val y = points[0].y + (points[1].y - points[0].y) * level
                drawLine(overlay.line.color.copy(alpha = if (level == 0f || level == 1f) 1f else 0.72f), Offset(left, y), Offset(right, y), strokeWidth = overlay.line.strokeWidthPx * densityScale, pathEffect = overlay.line.stroke(densityScale).pathEffect)
            }
        }
        handles(overlay, points, selected, config, densityScale)
    }
}

class DrawingToolRegistry(
    tools: Iterable<DrawingTool> = listOf(
        TwoPointLineDrawingTool(),
        RayLineDrawingTool(),
        StraightLineDrawingTool(),
        HorizontalLineDrawingTool(),
        VerticalLineDrawingTool(),
        RectangleDrawingTool(),
        FibonacciRetracementDrawingTool(),
    ),
) {
    private val toolsByType = tools.associateBy(DrawingTool::type).toMutableMap()

    fun register(tool: DrawingTool) {
        toolsByType[tool.type] = tool
    }

    operator fun get(type: DrawingTypeDescriptor): DrawingTool? = toolsByType[type]

    val supportedTypes: Set<DrawingTypeDescriptor> get() = toolsByType.keys.toSet()
}
