/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas.drawing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.zhumeng.kanvas.core.KlineSeries
import com.zhumeng.kanvas.core.KlineViewport
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class DrawingCoordinateSpace(
    val series: KlineSeries,
    val viewport: KlineViewport,
    val plotRect: Rect,
    val minValue: Double,
    val maxValue: Double,
) {
    init {
        require(minValue.isFinite() && maxValue.isFinite() && maxValue > minValue)
    }

    fun project(point: DrawingPoint): Offset? {
        val index = series.timestampToFractionalIndex(point.timestampMillis) ?: return null
        return Offset(
            x = viewport.xForIndex(plotRect.right, index),
            y = valueToY(point.value),
        )
    }

    fun unproject(offset: Offset): DrawingPoint? {
        if (!offset.x.isFinite() || !offset.y.isFinite()) return null
        val index = viewport.fractionalIndexAt(plotRect.right, offset.x)
        val timestamp = series.fractionalIndexToTimestamp(index) ?: return null
        return DrawingPoint(timestamp, yToValue(offset.y))
    }

    fun valueToY(value: Double): Float =
        (plotRect.bottom - ((value - minValue) / (maxValue - minValue)).toFloat() * plotRect.height)

    fun yToValue(y: Float): Double =
        maxValue - ((y - plotRect.top) / plotRect.height).coerceIn(0f, 1f) * (maxValue - minValue)
}

internal fun distanceToSegment(point: Offset, start: Offset, end: Offset): Float {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0f) return hypot(point.x - start.x, point.y - start.y)
    val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared).coerceIn(0f, 1f)
    return hypot(point.x - (start.x + t * dx), point.y - (start.y + t * dy))
}

internal fun distanceToInfiniteLine(point: Offset, start: Offset, end: Offset): Float {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val length = hypot(dx, dy)
    if (length == 0f) return (point - start).getDistance()
    return kotlin.math.abs(dy * point.x - dx * point.y + end.x * start.y - end.y * start.x) / length
}

internal fun distanceToRay(point: Offset, start: Offset, through: Offset): Float {
    val direction = through - start
    val lengthSquared = direction.x * direction.x + direction.y * direction.y
    if (lengthSquared == 0f) return (point - start).getDistance()
    val projection = ((point - start).x * direction.x + (point - start).y * direction.y) / lengthSquared
    return if (projection < 0f) (point - start).getDistance() else distanceToInfiniteLine(point, start, through)
}

internal fun lineIntersectionsWithRect(start: Offset, end: Offset, rect: Rect): Pair<Offset, Offset>? {
    val direction = end - start
    if (direction.getDistance() == 0f) return null
    val intersections = mutableListOf<Offset>()
    fun addIfInside(point: Offset) {
        if (point.x in rect.left..rect.right && point.y in rect.top..rect.bottom &&
            intersections.none { (it - point).getDistance() < 0.5f }
        ) {
            intersections += point
        }
    }
    if (direction.x != 0f) {
        listOf(rect.left, rect.right).forEach { x ->
            val t = (x - start.x) / direction.x
            addIfInside(Offset(x, start.y + t * direction.y))
        }
    }
    if (direction.y != 0f) {
        listOf(rect.top, rect.bottom).forEach { y ->
            val t = (y - start.y) / direction.y
            addIfInside(Offset(start.x + t * direction.x, y))
        }
    }
    if (intersections.size < 2) return null
    return intersections.minBy { (it - start).getDistance() } to intersections.maxBy { (it - start).getDistance() }
}

internal fun rayEndInRect(start: Offset, through: Offset, rect: Rect): Offset? {
    val direction = through - start
    if (direction.getDistance() == 0f) return null
    val candidates = mutableListOf<Pair<Float, Offset>>()
    if (direction.x != 0f) {
        listOf(rect.left, rect.right).forEach { x ->
            val t = (x - start.x) / direction.x
            val point = Offset(x, start.y + t * direction.y)
            if (t >= 0f && point.y in rect.top..rect.bottom) candidates += t to point
        }
    }
    if (direction.y != 0f) {
        listOf(rect.top, rect.bottom).forEach { y ->
            val t = (y - start.y) / direction.y
            val point = Offset(start.x + t * direction.x, y)
            if (t >= 0f && point.x in rect.left..rect.right) candidates += t to point
        }
    }
    return candidates.maxByOrNull(Pair<Float, Offset>::first)?.second
}

internal fun rectFromPoints(first: Offset, second: Offset): Rect = Rect(
    left = min(first.x, second.x),
    top = min(first.y, second.y),
    right = max(first.x, second.x),
    bottom = max(first.y, second.y),
)
