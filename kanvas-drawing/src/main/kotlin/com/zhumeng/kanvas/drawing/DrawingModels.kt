/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas.drawing

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.util.UUID

data class DrawingTypeDescriptor(
    val id: String,
    val steps: Int,
    val groupId: String = UnknownGroup,
) {
    init {
        require(id.isNotBlank()) { "Drawing type id must not be blank." }
        require(steps > 0) { "Drawing type steps must be positive." }
    }

    companion object {
        const val UnknownGroup: String = "unknown"
        val TwoPointLine = DrawingTypeDescriptor("line", 2, "line")
        val RayLine = DrawingTypeDescriptor("ray", 2, "trend")
        val StraightLine = DrawingTypeDescriptor("straight_line", 2, "trend")
        val HorizontalLine = DrawingTypeDescriptor("horizontal_line", 1, "line")
        val VerticalLine = DrawingTypeDescriptor("vertical_line", 1, "line")
        val Rectangle = DrawingTypeDescriptor("rectangle", 2, "shape")
        val FibonacciRetracement = DrawingTypeDescriptor("fibonacci_retracement", 2, "fibonacci")

        /** Built-ins ordered for compact tool pickers. */
        val BuiltIns: List<DrawingTypeDescriptor> = listOf(
            TwoPointLine,
            RayLine,
            StraightLine,
            HorizontalLine,
            VerticalLine,
            Rectangle,
            FibonacciRetracement,
        )
    }
}

/** Stable persisted coordinate. Screen coordinates are deliberately excluded. */
data class DrawingPoint(
    val timestampMillis: Long,
    val value: Double,
) {
    init {
        require(value.isFinite()) { "Drawing point value must be finite." }
    }
}

data class DrawingLineStyle(
    val color: Color = Color(0xFF4C8DFF),
    val strokeWidthPx: Float = 1f,
    val dashed: Boolean = false,
    val dashOnPx: Float = 5f,
    val dashOffPx: Float = 3f,
    val lineType: String = if (dashed) "dashed" else "solid",
    val lengthPx: Float? = null,
    val paintStyle: String = "stroke",
    val blendMode: String = "srcOver",
    val isAntiAlias: Boolean = true,
) {
    init {
        require(strokeWidthPx >= 0f)
        require(dashOnPx > 0f && dashOffPx > 0f)
        require(lengthPx == null || lengthPx >= 0f)
    }
}

data class DrawingPointStyle(
    val radiusPx: Float = 9f,
    val widthPx: Float = 0f,
    val color: Color? = null,
    val borderWidthPx: Float = 1f,
    val borderColor: Color? = null,
) {
    init {
        require(radiusPx >= 0f && widthPx >= 0f && borderWidthPx >= 0f)
    }
}

/** Drawing-local text projection, avoiding a dependency back from Drawing to the chart module. */
data class DrawingTextAreaStyle(
    val textColor: Color? = null,
    val fontSizeSp: Float = 10f,
    val fontStyle: FontStyle? = null,
    val fontWeight: FontWeight? = null,
    val lineHeightMultiplier: Float? = null,
    val backgroundColor: Color? = null,
    val paddingLeftPx: Float = 2f,
    val paddingTopPx: Float = 2f,
    val paddingRightPx: Float = 2f,
    val paddingBottomPx: Float = 2f,
    val borderColor: Color? = null,
    val borderWidthPx: Float = 0f,
    val borderRadiusPx: Float = 2f,
)

data class DrawingOverlay(
    val id: String = UUID.randomUUID().toString(),
    val symbol: String,
    val type: DrawingTypeDescriptor,
    val points: List<DrawingPoint?> = List(type.steps) { null },
    val zIndex: Int = 0,
    val locked: Boolean = false,
    val line: DrawingLineStyle = DrawingLineStyle(),
    /** Optional platform payload used by adapters for lossless unknown-field round trips. */
    val sourcePayload: String? = null,
) {
    init {
        require(id.isNotBlank())
        require(points.size == type.steps) {
            "${type.id} requires ${type.steps} points, received ${points.size}."
        }
    }

    val isInitial: Boolean get() = points.all { it == null }
    val isDrawing: Boolean get() = points.any { it == null }
    val isComplete: Boolean get() = points.all { it != null }
    val nextPointIndex: Int get() = points.indexOfFirst { it == null }.let { if (it < 0) points.lastIndex else it }
}

enum class DrawingMagnetMode {
    Normal,
    Weak,
    Strong;

    fun next(includeStrong: Boolean = true): DrawingMagnetMode = when (this) {
        Normal -> Weak
        Weak -> if (includeStrong) Strong else Normal
        Strong -> Normal
    }
}

data class DrawingRenderConfig(
    val enabled: Boolean = true,
    val allowSelectWhenExited: Boolean = true,
    val continueDrawing: Boolean = false,
    val hitTestDistancePx: Float = 10f,
    val magnetDistancePx: Float = 10f,
    val crossPoint: DrawingPointStyle = DrawingPointStyle(radiusPx = 2f, widthPx = 0f, borderWidthPx = 2f),
    val crosshair: DrawingLineStyle = DrawingLineStyle(strokeWidthPx = 0.5f, dashed = true),
    val drawLine: DrawingLineStyle = DrawingLineStyle(),
    val drawPoint: DrawingPointStyle = DrawingPointStyle(),
    val ticksText: DrawingTextAreaStyle = DrawingTextAreaStyle(),
    val ticksSpacingPx: Float = 1f,
    val ticksGapBackgroundOpacity: Float = 0.1f,
) {
    init {
        require(hitTestDistancePx >= 0f && magnetDistancePx >= 0f)
        require(ticksSpacingPx >= 0f)
        require(ticksGapBackgroundOpacity in 0f..1f)
    }
}

data class DrawingMagnifierConfig(
    val enabled: Boolean = true,
    val size: DpSize = DpSize(80.dp, 80.dp),
    val zoom: Float = 2f,
    val cornerRadius: androidx.compose.ui.unit.Dp = 40.dp,
    val elevation: androidx.compose.ui.unit.Dp = 4.dp,
    val marginPx: Float = 8f,
    val marginLeftPx: Float = marginPx,
    val marginTopPx: Float = marginPx,
    val marginRightPx: Float = marginPx,
    val marginBottomPx: Float = marginPx,
    val clip: Boolean = false,
    val decorationOpacity: Float = 1f,
    val borderColor: Color? = null,
    val borderWidthPx: Float = 0f,
    val borderStyle: String = "none",
) {
    init {
        require(zoom > 0f)
        require(listOf(marginPx, marginLeftPx, marginTopPx, marginRightPx, marginBottomPx, borderWidthPx).all { it >= 0f })
        require(decorationOpacity in 0f..1f)
    }
}

sealed interface DrawingState {
    data object Prepared : DrawingState
    data class Drawing(val overlayId: String, val pointerIndex: Int) : DrawingState
    data class Editing(val overlayId: String, val selectedPointIndex: Int? = null) : DrawingState
    data object Exited : DrawingState
}
