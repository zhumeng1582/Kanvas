/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhumeng.kanvas.core.IndexRange
import com.zhumeng.kanvas.core.IndicatorDefinition
import com.zhumeng.kanvas.core.IndicatorOutput
import com.zhumeng.kanvas.core.IndicatorRegistrySnapshot
import com.zhumeng.kanvas.core.IndicatorRuntimeSnapshot
import com.zhumeng.kanvas.core.KlineCandle
import com.zhumeng.kanvas.core.KlineLoadingState
import com.zhumeng.kanvas.core.KlineInterval
import com.zhumeng.kanvas.core.KlineSpec
import com.zhumeng.kanvas.core.KlineSeries
import com.zhumeng.kanvas.core.KlineTimeUnit
import com.zhumeng.kanvas.core.KlineUiState
import com.zhumeng.kanvas.core.KlineViewport
import com.zhumeng.kanvas.core.KlineViewportConstraints
import com.zhumeng.kanvas.core.KlineViewportMath
import com.zhumeng.kanvas.core.Volume
import com.zhumeng.kanvas.drawing.DrawingController
import com.zhumeng.kanvas.drawing.DrawingCoordinateSpace
import com.zhumeng.kanvas.drawing.DrawingMagnifierConfig
import com.zhumeng.kanvas.drawing.DrawingPoint
import com.zhumeng.kanvas.drawing.DrawingRenderConfig
import com.zhumeng.kanvas.drawing.DrawingState
import com.zhumeng.kanvas.drawing.DrawingTextAreaStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

internal fun DrawScope.drawPaneResizeAffordances(
    layout: KlineLayout,
    config: KlineGridRenderConfig,
    style: KlineChartStyle,
    activeBoundaryY: Float?,
    mode: KlineLayoutMode,
) {
    if (!config.allowDragIndicatorHeight) return
    val panes = listOf(layout.mainPane) + layout.subPanes
    val lastIndex = if (mode == KlineLayoutMode.Fixed) panes.lastIndex - 1 else panes.lastIndex
    if (lastIndex < 0) return
    val hitHeight = logicalPx(config.dragHitTestMinDistancePx)
    (0..lastIndex).forEach { index ->
        val y = panes[index].outerRect.bottom
        val isActive = activeBoundaryY?.let { abs(it - y) < 1f } == true
        val backgroundAlpha = if (isActive) {
            config.draggingBackgroundOpacity
        } else {
            config.idleDragBackgroundOpacity
        }
        if (backgroundAlpha > 0f) {
            drawRect(
                color = style.dragBackground.copy(alpha = backgroundAlpha),
                topLeft = Offset(layout.chartRect.left, y - hitHeight / 2f),
                size = Size(layout.chartRect.width, hitHeight),
            )
        }
        if (isActive || config.dragLineOpacity > 0f) {
            val base = config.dragLine.color ?: style.markLine
            drawConfiguredLine(
                stroke = config.dragLine.copy(
                    color = base.copy(alpha = if (isActive) base.alpha else base.alpha * config.dragLineOpacity),
                ),
                fallbackColor = style.markLine,
                start = Offset(layout.chartRect.left, y),
                end = Offset(layout.chartRect.right, y),
            )
        }
    }
}

internal fun DrawScope.drawKlinePaneDividers(
    layout: KlineLayout,
    color: Color,
) {
    layout.dividerYPositions.forEach { y ->
        if (y <= layout.chartRect.top || y >= layout.chartRect.bottom) return@forEach
        drawLine(
            color = color,
            start = Offset(layout.chartRect.left, y),
            end = Offset(layout.chartRect.right, y),
            strokeWidth = 1f,
        )
    }
}
/** Draws the main-pane foreground loading spinner. */
@Composable

internal fun KlineLoadingOverlay(
    modifier: Modifier = Modifier,
    mainRect: Rect,
    config: KlineLoadingRenderConfig,
    style: KlineChartStyle,
) {
    val transition = rememberInfiniteTransition(label = "KanvasLoading")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1_000, easing = LinearEasing)),
        label = "KanvasLoadingRotation",
    )
    Canvas(modifier.fillMaxSize()) {
        drawLoadingSpinner(
            mainRect = mainRect,
            config = config,
            style = style,
            rotation = rotation,
        )
    }
}

internal fun DrawScope.drawLoadingSpinner(
    mainRect: Rect,
    config: KlineLoadingRenderConfig,
    style: KlineChartStyle,
    rotation: Float,
) {
    val diameter = min(
        config.sizePx * density,
        min(mainRect.width, mainRect.height),
    )
    if (diameter <= 0f) return
    val strokeWidth = min(config.strokeWidthPx * density, diameter / 2f)
    if (strokeWidth <= 0f) return
    val topLeft = Offset(
        mainRect.center.x - diameter / 2f,
        mainRect.center.y - diameter / 2f,
    )
    val spinnerSize = Size(diameter, diameter)
    val stroke = Stroke(width = strokeWidth)
    drawArc(
        color = config.backgroundColor ?: style.tooltipBackground,
        startAngle = 0f,
        sweepAngle = 360f,
        useCenter = false,
        topLeft = topLeft,
        size = spinnerSize,
        style = stroke,
    )
    drawArc(
        color = config.valueColor ?: style.textColor,
        startAngle = rotation - 90f,
        sweepAngle = 100f,
        useCenter = false,
        topLeft = topLeft,
        size = spinnerSize,
        style = stroke,
    )
}

internal fun DrawScope.drawGrid(
    plotRect: Rect,
    style: KlineChartStyle,
    config: KlineGridRenderConfig,
    textMeasurer: TextMeasurer,
    range: KlineValueRange,
    precision: Int,
    includeTopAxisLabel: Boolean = true,
    includeBottomAxisLabel: Boolean = true,
    drawYAxisTicks: Boolean = true,
) {
    if (config.horizontalShow) {
        for (step in 0..config.horizontalCount) {
            val fraction = step / config.horizontalCount.toFloat()
            val y = plotRect.top + plotRect.height * fraction
            if (config.show) {
                drawConfiguredLine(
                    config.horizontalStroke,
                    fallbackColor = style.gridLine,
                    start = Offset(plotRect.left, y),
                    end = Offset(plotRect.right, y),
                )
            }
           if (drawYAxisTicks && config.showYAxisTicks &&
               (step != 0 || includeTopAxisLabel) &&
               (step != config.horizontalCount || includeBottomAxisLabel)
           ) {
                val tickY = when (step) {
                    0 -> plotRect.top + logicalPx(8f)
                    config.horizontalCount -> plotRect.bottom - logicalPx(8f)
                    else -> y
                }
                drawYAxisTick(
                    plotRect = plotRect,
                    y = tickY,
                    value = range.maximum - range.span * fraction,
                    style = style,
                    textMeasurer = textMeasurer,
                    precision = precision,
                    textArea = config.ticksText,
                )
            }
        }
    }
    if (config.show && config.verticalShow) {
        for (step in 0..config.verticalCount) {
            val x = plotRect.left + plotRect.width * step / config.verticalCount
            drawConfiguredLine(
                config.verticalStroke,
                fallbackColor = style.gridLine,
                start = Offset(x, plotRect.top),
                end = Offset(x, plotRect.bottom),
            )
        }
    }
}
/** Candle price labels belong to the candle phase, not the grid background. */

internal fun DrawScope.drawYAxisTicks(
    plotRect: Rect,
    style: KlineChartStyle,
    config: KlineGridRenderConfig,
    textMeasurer: TextMeasurer,
    range: KlineValueRange,
    precision: Int,
    includeTopAxisLabel: Boolean = true,
    includeBottomAxisLabel: Boolean = true,
    textArea: KlineTextAreaRenderConfig = KlineTextAreaRenderConfig(),
) {
    if (!config.horizontalShow || !config.showYAxisTicks) return
    for (step in 0..config.horizontalCount) {
        if ((step == 0 && !includeTopAxisLabel) ||
            (step == config.horizontalCount && !includeBottomAxisLabel)
        ) {
            continue
        }
       val fraction = step / config.horizontalCount.toFloat()
       val y = plotRect.top + plotRect.height * fraction
        val tickY = when (step) {
            0 -> plotRect.top + logicalPx(8f)
            config.horizontalCount -> plotRect.bottom - logicalPx(8f)
            else -> y
        }
       drawYAxisTick(
           plotRect = plotRect,
            y = tickY,
           value = range.maximum - range.span * fraction,
            style = style,
            textMeasurer = textMeasurer,
            precision = precision,
            textArea = textArea,
        )
    }
}

internal fun DrawScope.drawYAxisTick(
    plotRect: Rect,
    y: Float,
    value: Double,
    style: KlineChartStyle,
    textMeasurer: TextMeasurer,
    precision: Int,
    textArea: KlineTextAreaRenderConfig = KlineTextAreaRenderConfig(),
) {
    drawKlineTextArea(
        text = formatPrice(value, precision),
        anchor = Offset(plotRect.right, y),
        horizontalAnchor = KlineTextAreaHorizontalAnchor.End,
        verticalAnchor = KlineTextAreaVerticalAnchor.Center,
        config = textArea,
        textMeasurer = textMeasurer,
        fallbackTextColor = style.ticksTextColor,
    )
}

internal fun DrawScope.drawConfiguredLine(
    stroke: KlineStrokeStyle,
    fallbackColor: androidx.compose.ui.graphics.Color,
    start: Offset,
    end: Offset,
) {
    if (stroke.widthPx <= 0f) return
    val paint = Paint().apply {
        color = stroke.color ?: fallbackColor
        strokeWidth = logicalPx(stroke.widthPx)
        style = if (stroke.paintStyle == KlinePaintStyle.Fill) {
            PaintingStyle.Fill
        } else {
            PaintingStyle.Stroke
        }
        blendMode = stroke.blendMode
        isAntiAlias = stroke.isAntiAlias
        pathEffect = stroke.dashPatternPx.takeIf { it.isNotEmpty() }
            ?.map(::logicalPx)
            ?.toFloatArray()
            ?.let(PathEffect::dashPathEffect)
    }
    drawContext.canvas.drawLine(start, end, paint)
}
/** Converts a public logical-pixel value to Canvas pixels. */

internal fun DrawScope.logicalPx(value: Float): Float = value * density

internal fun KlinePanePadding.toCanvasPixels(densityScale: Float): KlinePanePadding = KlinePanePadding(
    leftPx = leftPx * densityScale,
    topPx = topPx * densityScale,
    rightPx = rightPx * densityScale,
    bottomPx = bottomPx * densityScale,
)

internal fun DrawScope.drawBars(
    candles: List<KlineCandle>,
    range: IndexRange,
    plotRect: Rect,
    values: KlineValueRange,
    viewport: KlineViewport,
    chartStyle: KlineBarStyle,
    style: KlineChartStyle,
    config: KlineCandleRenderConfig,
) {
    val bodyHalf = max(logicalPx(0.5f), viewport.candleHalfStepPx - viewport.candleSpacingPx)
    val strokeWidth = logicalPx(config.candleLineWidthPx)
    val hollowStrokeWidth = logicalPx(config.hollowBarBorderWidthPx)
    for (index in range.startInclusive until range.endExclusive) {
        val candle = candles[index]
        if (!candle.isRenderable) continue
        val centerX = viewport.xForIndex(plotRect.right, index.toDouble()) - viewport.candleHalfStepPx
        val highY = values.yFor(candle.high, plotRect)
        val lowY = values.yFor(candle.low, plotRect)
        val openY = values.yFor(candle.open, plotRect)
        val closeY = values.yFor(candle.close, plotRect)
        val bullish = candle.close >= candle.open
        val color = if (bullish) style.bullish else style.bearish
        if (chartStyle == KlineBarStyle.Ohlc) {
            drawLine(color, Offset(centerX, highY), Offset(centerX, lowY), strokeWidth = strokeWidth)
            drawLine(color, Offset(centerX - bodyHalf, openY), Offset(centerX, openY), strokeWidth = strokeWidth)
            drawLine(color, Offset(centerX, closeY), Offset(centerX + bodyHalf, closeY), strokeWidth = strokeWidth)
            continue
        }
        drawLine(color, Offset(centerX, highY), Offset(centerX, lowY), strokeWidth = strokeWidth)
        val top = min(openY, closeY)
        val bottom = max(openY, closeY)
        val body = Rect(centerX - bodyHalf, top, centerX + bodyHalf, max(bottom, top + logicalPx(1f)))
        val hollow = chartStyle == KlineBarStyle.AllHollow ||
            (chartStyle == KlineBarStyle.UpHollow && bullish) ||
            (chartStyle == KlineBarStyle.DownHollow && !bullish)
        if (hollow) {
            drawRect(
                color,
                topLeft = body.topLeft,
                size = body.size,
                style = Stroke(width = hollowStrokeWidth),
            )
        } else {
            drawRect(color, topLeft = body.topLeft, size = body.size)
        }
    }
}

internal fun DrawScope.drawOrderMarkers(
    markerIndex: KlineOrderMarkerIndex,
    candles: List<KlineCandle>,
    paintRange: IndexRange,
    plotRect: Rect,
    values: KlineValueRange,
    viewport: KlineViewport,
    config: KlineOrderMarkerRenderConfig,
    textMeasurer: TextMeasurer,
    densityScale: Float,
) {
    val placements = resolveKlineOrderMarkerPlacements(
        markerIndex = markerIndex,
        candles = candles,
        paintRange = paintRange,
        plotRect = plotRect,
        valueRange = values,
        viewport = viewport,
        config = config,
        densityScale = densityScale,
    )
    if (placements.isEmpty()) return
    val halfSize = config.sizePx.coerceAtLeast(1f) * densityScale / 2f
    val pointerHeight = config.pointerHeightPx.coerceAtLeast(0f) * densityScale
    val buyText = textMeasurer.measure("B", style = TextStyle(color = config.textColor, fontSize = config.textSizeSp.sp))
    val sellText = textMeasurer.measure("S", style = TextStyle(color = config.textColor, fontSize = config.textSizeSp.sp))
    val pointer = Path()
    placements.forEach { placement ->
        val isBuy = placement.marker.side == KlineOrderSide.Buy
        val text = if (isBuy) buyText else sellText
        val color = if (isBuy) config.buyColor else config.sellColor
        val squareTop = placement.center.y - halfSize
        val squareBottom = placement.center.y + halfSize
        // Let the pointer overlap the square by one physical pixel. Sharing
        // only an anti-aliased edge can otherwise leave a visible hairline.
        val seamOverlap = 1f
        val pointerBaseY = if (isBuy) squareTop + seamOverlap else squareBottom - seamOverlap
        val pointerTipY = if (isBuy) squareTop - pointerHeight else squareBottom + pointerHeight
        pointer.reset()
        pointer.moveTo(placement.center.x, pointerTipY)
        pointer.lineTo(placement.center.x - halfSize, pointerBaseY)
        pointer.lineTo(placement.center.x + halfSize, pointerBaseY)
        pointer.close()
        drawPath(pointer, color)
        drawRect(
            color = color,
            topLeft = Offset(placement.center.x - halfSize, squareTop),
            size = Size(halfSize * 2f, halfSize * 2f),
        )
        drawText(
            textLayoutResult = text,
            topLeft = Offset(
                placement.center.x - text.size.width / 2f,
                placement.center.y - text.size.height / 2f,
            ),
        )
    }
}

internal fun DrawScope.drawLineChart(
    candles: List<KlineCandle>,
    range: IndexRange,
    plotRect: Rect,
    values: KlineValueRange,
    viewport: KlineViewport,
    chartStyle: KlineLineStyle,
    style: KlineChartStyle,
    config: KlineCandleRenderConfig,
    gradients: KlineCandleGradientConfiguration,
) {
    if (range.isEmpty) return
    val points = (range.startInclusive until range.endExclusive).mapNotNull { index ->
        candles[index].takeIf(KlineCandle::isRenderable)?.let { candle ->
            Offset(
                viewport.xForIndex(plotRect.right, index.toDouble()) - viewport.candleHalfStepPx,
                values.yFor(candle.close, plotRect),
            )
        }
    }
    if (points.isEmpty()) return
    if (chartStyle == KlineLineStyle.Normal) {
        val path = Path()
        points.forEachIndexed { index, point ->
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        drawPath(
            path,
            style.line,
            style = Stroke(width = logicalPx(config.candleLineWidthPx), cap = StrokeCap.Round),
        )
        drawKlineLineGradientFill(
            points = points,
            baselineY = plotRect.bottom,
            config = gradients.line,
            baseColor = style.line,
        )
        return
    }
    val latestBaselineY = values.yFor(candles.first().close, plotRect)
    splitKlineLineAtBaseline(points, latestBaselineY).forEach { segment ->
        val color = if (segment.isBullish) style.bullish else style.bearish
        val gradient = if (segment.isBullish) gradients.bullish else gradients.bearish
        val path = Path().apply {
            segment.points.forEachIndexed { index, point ->
                if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = logicalPx(config.candleLineWidthPx), cap = StrokeCap.Round),
        )
        drawKlineLineGradientFill(segment.points, latestBaselineY, gradient, color)
    }
}

internal data class KlineLineGradientSegment(
    val isBullish: Boolean,
    val points: List<Offset>,
)

internal fun splitKlineLineAtBaseline(
    points: List<Offset>,
    baselineY: Float,
): List<KlineLineGradientSegment> {
    if (points.size < 2) return emptyList()
    val result = mutableListOf<KlineLineGradientSegment>()
    var bullish = points
        .firstOrNull { it.y != baselineY }
        ?.let { it.y <= baselineY }
        ?: true
    var current = mutableListOf(points.first())
    points.zipWithNext().forEach { (start, end) ->
        val nextBullish = end.y <= baselineY
        if (start.y == baselineY && end.y != baselineY && nextBullish != bullish) {
            if (current.size >= 2) result += KlineLineGradientSegment(bullish, current)
            bullish = nextBullish
            current = mutableListOf(start)
        }
        val endBullish = end.y <= baselineY
        if (endBullish == bullish || end.y == baselineY) {
            current += end
        } else {
            val ratio = (baselineY - start.y) / (end.y - start.y)
            val crossing = Offset(start.x + (end.x - start.x) * ratio, baselineY)
            current += crossing
            result += KlineLineGradientSegment(bullish, current)
            bullish = endBullish
            current = mutableListOf(crossing, end)
        }
    }
    if (current.size >= 2) result += KlineLineGradientSegment(bullish, current)
    return result
}

internal fun DrawScope.drawKlineLineGradientFill(
    points: List<Offset>,
    baselineY: Float,
    config: KlineLineGradientRenderConfig?,
    baseColor: Color,
) {
    if (config?.enabled != true || points.size < 2) return
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
        lineTo(points.last().x, baselineY)
        lineTo(points.first().x, baselineY)
        close()
    }
    val bounds = path.getBounds()
    val colors = config.colors ?: listOf(
        baseColor.copy(alpha = config.startAlpha),
        baseColor.copy(alpha = config.endAlpha),
    )
    val start = config.begin.resolveIn(bounds)
    val end = config.end.resolveIn(bounds)
    val tileMode = when (config.tileMode) {
        KlineGradientTileMode.Clamp -> TileMode.Clamp
        KlineGradientTileMode.Repeated -> TileMode.Repeated
        KlineGradientTileMode.Mirror -> TileMode.Mirror
        KlineGradientTileMode.Decal -> TileMode.Decal
    }
    val brush = config.stops?.let { stops ->
        Brush.linearGradient(
            colorStops = stops.zip(colors).map { (stop, color) -> stop to color }.toTypedArray(),
            start = start,
            end = end,
            tileMode = tileMode,
        )
    } ?: Brush.linearGradient(colors, start, end, tileMode)
    drawPath(path = path, brush = brush)
}

internal fun KlineGradientAlignment.resolveIn(rect: Rect): Offset = Offset(
    x = rect.left + (x + 1f) * 0.5f * rect.width,
    y = rect.top + (y + 1f) * 0.5f * rect.height,
)
/** Renders generic computed columns as aligned newest-first line series. */

internal fun DrawScope.drawIndicatorLines(
    outputs: List<IndicatorOutput>,
    range: IndexRange,
    plotRect: Rect,
    values: KlineValueRange,
    viewport: KlineViewport,
    style: KlineChartStyle,
    colorOffset: Int = 0,
    lineStyles: List<KlineIndicatorLineStyle> = emptyList(),
    excludedColumns: Set<String> = emptySet(),
) {
    if (outputs.isEmpty() || range.isEmpty) return
    var colorIndex = colorOffset
    val indicatorColors = style.indicatorLines.ifEmpty { listOf(style.line) }
    outputs.forEach { output ->
        output.columns().forEachIndexed { columnIndex, column ->
            val configuredStyle = lineStyles.getOrNull(columnIndex)
            if (column.name in excludedColumns || configuredStyle?.visible == false) {
                colorIndex++
                return@forEachIndexed
            }
            val path = Path()
            var hasPoint = false
            for (index in range.startInclusive until range.endExclusive) {
                val value = column[index]
                if (!value.isFinite()) {
                    hasPoint = false
                    continue
                }
                val x = viewport.xForIndex(plotRect.right, index.toDouble()) - viewport.candleHalfStepPx
                val y = values.yFor(value, plotRect)
                if (hasPoint) path.lineTo(x, y) else path.moveTo(x, y)
                hasPoint = true
            }
            val color = configuredStyle?.color ?: indicatorColors[colorIndex % indicatorColors.size]
            val widthPx = configuredStyle?.widthPx ?: 1.25f
            drawPath(path, color, style = Stroke(width = logicalPx(widthPx), cap = StrokeCap.Round))
            colorIndex++
        }
    }
}
/**
 * One immutable indicator draw context resolved for a physical chart frame.
 *
 * Canvas normal drawing, optional overlay/cross hooks, and pointer tap routing
 * must agree on ALONE geometry and shared-sub-pane value ranges. This frame
 * gives non-normal phases the same deterministic inputs without allowing them
 * to recompute layout from mutable Canvas state.
 */

internal data class KlineIndicatorRenderFrame(
    val item: KlineIndicatorRenderItem,
    val context: KlineIndicatorDrawContext,
)
/** Indicator frames in main z-order and physical sub-pane order. */

internal data class KlineIndicatorRenderFrames(
    val main: List<KlineIndicatorRenderFrame>,
    val subByPane: LinkedHashMap<String, List<KlineIndicatorRenderFrame>>,
)
/** True only for a pane whose members are all backed by the native Volume calculator. */

internal fun List<KlineIndicatorRenderItem>.isVolumeOnlyPane(): Boolean =
    isNotEmpty() && all { it.definition.calculator === Volume }
/**
 * Resolves native indicator frames for optional overlay/cross/Top Tips/tap phases.
 * Regular Canvas painters use the same geometry/range equations below; this
 * helper keeps the non-normal phases independent of prior draw calls.
 */

internal fun resolveKlineIndicatorRenderFrames(
    state: KlineUiState,
    layout: KlineLayout,
    indicatorPanePlan: KlineIndicatorPanePlan,
    paintRange: IndexRange,
    mainValueRange: KlineValueRange,
    style: KlineChartStyle,
    densityScale: Float,
    hideMainIndicators: Boolean,
    sameValueExpansionRatios: List<Float> = listOf(0.1f, 0.05f),
): KlineIndicatorRenderFrames {

    fun KlinePaneLayout.isDrawable(): Boolean =
        plotRect.width > 0f && plotRect.height > 0f

    fun frame(
        item: KlineIndicatorRenderItem,
        pane: KlinePaneLayout,
        valueRange: KlineValueRange,
    ): KlineIndicatorRenderFrame = KlineIndicatorRenderFrame(
        item = item,
        context = KlineIndicatorDrawContext(
            state = state,
            definition = item.definition,
            output = item.output,
            pane = pane,
            paintRange = paintRange,
            valueRange = valueRange,
            viewport = state.viewport,
            style = style,
            colorIndex = item.colorIndex,
            densityScale = densityScale,
        ),
    )
    val main = if (hideMainIndicators) {
        emptyList()
    } else {
        indicatorPanePlan.mainPaintOrder.mapNotNull { item ->
            when (item.definition.paintMode) {
                com.zhumeng.kanvas.core.IndicatorPaintMode.COMBINE ->
                    layout.mainPane.takeIf(KlinePaneLayout::isDrawable)
                        ?.let { pane -> frame(item, pane, mainValueRange) }
                com.zhumeng.kanvas.core.IndicatorPaintMode.ALONE -> {
                    val pane = resolveMainAlonePane(layout.mainPane, item, densityScale)
                    if (!pane.isDrawable()) return@mapNotNull null
                    val valueRange = listOfNotNull(item.output).valueRange(
                        range = paintRange,
                        sameValueExpansionRatios = sameValueExpansionRatios,
                        rendererRanges = listOf(item).rendererValueRanges(
                            state = state,
                            paintRange = paintRange,
                            viewport = state.viewport,
                        ),
                    )
                    frame(item, pane, valueRange)
                }
            }
        }
    }
    val subByPane = linkedMapOf<String, List<KlineIndicatorRenderFrame>>()
    layout.subPanes.forEach { pane ->
        if (!pane.isDrawable()) return@forEach
        val items = indicatorPanePlan.subByPane[pane.id].orEmpty()
        if (items.isEmpty()) return@forEach
        val valueRange = items.mapNotNull(KlineIndicatorRenderItem::output).valueRange(
            range = paintRange,
            minimum = if (items.isVolumeOnlyPane()) 0.0 else null,
            sameValueExpansionRatios = sameValueExpansionRatios,
            rendererRanges = items.rendererValueRanges(
                state = state,
                paintRange = paintRange,
                viewport = state.viewport,
            ),
        )
        subByPane[pane.id] = items.map { item -> frame(item, pane, valueRange) }
    }
    return KlineIndicatorRenderFrames(main = main, subByPane = LinkedHashMap(subByPane))
}
/** Draws one optional overlay phase without changing normal painter clipping. */

internal fun DrawScope.drawIndicatorOverlays(
    frames: Iterable<KlineIndicatorRenderFrame>,
    layout: KlineLayout,
    policy: KlineIndicatorOverlayRenderConfig,
) {
    frames.forEach { frame ->
        (frame.item.renderer as? KlineIndicatorOverlayRenderer)?.drawOverlay(
            scope = this,
            context = KlineIndicatorOverlayDrawContext(
                indicator = frame.context,
                layout = layout,
                policy = policy,
            ),
        )
    }
}
/** Draws optional per-indicator Cross decorations in the supplied stable order. */

internal fun DrawScope.drawIndicatorCross(
    frames: Iterable<KlineIndicatorRenderFrame>,
    layout: KlineLayout,
    crosshair: KlineIndicatorCrosshairContext,
) {
    frames.forEach { frame ->
        val valueAtPointer = crosshair.inputPosition
            .takeIf(frame.context.pane.plotRect::contains)
            ?.let { position -> frame.context.valueRange.valueAt(position.y, frame.context.pane.plotRect) }
        (frame.item.renderer as? KlineIndicatorCrossRenderer)?.drawCross(
            scope = this,
            context = KlineIndicatorCrossDrawContext(
                indicator = frame.context,
                crosshair = crosshair,
                layout = layout,
                valueAtPointer = valueAtPointer,
            ),
        )
    }
}
/** Shows a sub-pane-local Y value before custom crosshair decorations. */

internal fun DrawScope.drawSubPaneCrossValue(
    pane: KlinePaneLayout,
    frames: List<KlineIndicatorRenderFrame>,
    pointer: Offset,
    style: KlineChartStyle,
    config: KlineCrosshairRenderConfig,
    precision: Int,
    textMeasurer: TextMeasurer,
) {
    val frame = frames.firstOrNull() ?: return
    val value = resolveKlinePaneCrossValue(pointer, pane.plotRect, frame.context.valueRange) ?: return
    drawKlineTextArea(
        text = formatPrice(value, precision),
        anchor = Offset(pane.plotRect.right - logicalPx(config.ticksSpacingPx), pointer.y),
        horizontalAnchor = KlineTextAreaHorizontalAnchor.End,
        verticalAnchor = KlineTextAreaVerticalAnchor.Center,
        config = config.ticksText,
        textMeasurer = textMeasurer,
        fallbackTextColor = style.crossTextColor,
        fallbackBackground = style.crossTextBackground,
        fallbackBorder = style.crosshair,
        drawableRect = pane.plotRect,
    )
}

internal fun resolveKlinePaneCrossValue(
    pointer: Offset,
    plotRect: Rect,
    valueRange: KlineValueRange,
): Double? = pointer.takeIf(plotRect::contains)?.let { valueRange.valueAt(it.y, plotRect) }
/**
 * Dispatches a confirmed tap in main-then-sub logical order.
 * Cross-tooltip targets, when implemented, must be invoked before this helper.
 */

internal fun dispatchKlineIndicatorTap(
    position: Offset,
    frames: KlineIndicatorRenderFrames,
    layout: KlineLayout,
    crosshair: KlineIndicatorCrosshairContext?,
): Boolean {

    fun dispatch(frame: KlineIndicatorRenderFrame): Boolean =
        (frame.item.renderer as? KlineIndicatorTapHandler)?.onTap(
            KlineIndicatorTapContext(
                position = position,
                indicator = frame.context,
                layout = layout,
                crosshair = crosshair,
            ),
        ) == true
    frames.main.forEach { frame -> if (dispatch(frame)) return true }
    layout.subPanes.forEach { pane ->
        frames.subByPane[pane.id].orEmpty().forEach { frame ->
            if (dispatch(frame)) return true
        }
    }
    return false
}

internal fun DrawScope.drawIndicatorFrames(
    frames: List<KlineIndicatorRenderFrame>,
) {
    frames.forEach { frame -> drawIndicatorFrame(frame) }
}

internal fun DrawScope.drawIndicatorFrame(frame: KlineIndicatorRenderFrame) {
    frame.item.renderer.draw(scope = this, context = frame.context)
}

internal fun DrawScope.drawSubIndicatorFrames(
    pane: KlinePaneLayout,
    frames: List<KlineIndicatorRenderFrame>,
    style: KlineChartStyle,
) {
    if (pane.plotRect.width <= 0f || pane.plotRect.height <= 0f || frames.isEmpty()) return
    clipRect(pane.plotRect.left, pane.plotRect.top, pane.plotRect.right, pane.plotRect.bottom) {
        drawIndicatorFrames(frames)
    }
    // Sub panes intentionally omit the chart grid, but retain a clear bottom
    // divider so adjacent indicator panes remain visually separated.
    drawLine(
        color = style.gridLine,
        start = Offset(pane.outerRect.left, pane.outerRect.bottom - 0.5f),
        end = Offset(pane.outerRect.right, pane.outerRect.bottom - 0.5f),
        strokeWidth = 1f,
    )
}
/** Resolves a bottom-aligned standalone main rect, not a new sub pane. */

internal fun resolveMainAlonePane(
    mainPane: KlinePaneLayout,
    item: KlineIndicatorRenderItem,
    densityScale: Float,
): KlinePaneLayout {
    val drawable = mainPane.outerRect
    val ownPadding = item.definition.layoutHint.padding
        ?.toPanePadding()
        ?.toCanvasPixels(densityScale)
        ?: KlinePanePadding()
    val left = (drawable.left + ownPadding.leftPx).coerceAtMost(drawable.right)
    val right = (drawable.right - ownPadding.rightPx).coerceAtLeast(left)
    val topLimit = (drawable.top + ownPadding.topPx).coerceAtMost(drawable.bottom)
    val bottom = (drawable.bottom - ownPadding.bottomPx).coerceAtLeast(topLimit)
    val available = (bottom - topLimit).coerceAtLeast(0f)
    val preferredLogical = item.definition.layoutHint.resolvedHeight
        ?: item.definition.layoutHint.minHeight
        ?: (available / densityScale)
    val minimumLogical = item.definition.layoutHint.minHeight ?: 0f
    val height = (preferredLogical * densityScale)
        .coerceAtLeast(minimumLogical * densityScale)
        .coerceIn(0f, available)
    val rect = Rect(
        left = left,
        top = (bottom - height).coerceAtLeast(topLimit),
        right = right,
        bottom = bottom,
    )
    return KlinePaneLayout(
        id = "main-alone:${item.definition.key.kind}:${item.definition.key.id}",
        // The drawable rectangle remains the full main rectangle; only the
        // child plot is bottom-aligned by its own height and padding.
        outerRect = drawable,
        plotRect = rect,
        requestedHeightPx = preferredLogical * densityScale,
        resolvedHeightPx = height,
    )
}
/** Draw main items in one global z-order; combine and alone are geometry choices, not paint passes. */

internal fun DrawScope.drawMainIndicatorFrames(
    mainPane: KlinePaneLayout,
    frames: List<KlineIndicatorRenderFrame>,
    style: KlineChartStyle,
    grid: KlineGridRenderConfig,
    textMeasurer: TextMeasurer,
    precision: Int,
) {
    frames.forEach { frame ->
        when (frame.item.definition.paintMode) {
            com.zhumeng.kanvas.core.IndicatorPaintMode.COMBINE -> {
                clipRect(
                    mainPane.plotRect.left,
                    mainPane.plotRect.top,
                    mainPane.plotRect.right,
                    mainPane.plotRect.bottom,
                ) {
                    drawIndicatorFrame(frame)
                }
            }
            com.zhumeng.kanvas.core.IndicatorPaintMode.ALONE -> {
                val pane = frame.context.pane
                if (pane.plotRect.width <= 0f || pane.plotRect.height <= 0f) return@forEach
                drawGrid(
                    plotRect = pane.plotRect,
                    style = style,
                    config = grid,
                    textMeasurer = textMeasurer,
                    range = frame.context.valueRange,
                    precision = precision,
                    includeTopAxisLabel = false,
                    includeBottomAxisLabel = false,
                )
                clipRect(pane.plotRect.left, pane.plotRect.top, pane.plotRect.right, pane.plotRect.bottom) {
                    drawIndicatorFrame(frame)
                }
            }
        }
    }
}

internal fun DrawScope.drawVolumeBars(
    candles: List<KlineCandle>,
    valuesColumn: com.zhumeng.kanvas.core.IndicatorColumn,
    range: IndexRange,
    plotRect: Rect,
    values: KlineValueRange,
    viewport: KlineViewport,
    style: KlineChartStyle,
) {
    val halfWidth = max(logicalPx(0.5f), viewport.candleHalfStepPx - viewport.candleSpacingPx)
    for (index in range.startInclusive until range.endExclusive) {
        val volume = valuesColumn[index]
        if (!volume.isFinite()) continue
        val candle = candles[index]
        val x = viewport.xForIndex(plotRect.right, index.toDouble()) - viewport.candleHalfStepPx
        val top = values.yFor(volume, plotRect)
        val color = if (candle.close >= candle.open) style.volumeBullish else style.volumeBearish
        val body = Rect(x - halfWidth, top, x + halfWidth, plotRect.bottom)
        drawRect(color, topLeft = body.topLeft, size = body.size)
    }
}

internal fun DrawScope.drawHighLowMarks(
    candles: List<KlineCandle>,
    range: IndexRange,
    plotRect: Rect,
    values: KlineValueRange,
    viewport: KlineViewport,
    precision: Int,
    style: KlineChartStyle,
    config: KlineCandleOverlayRenderConfig,
    textMeasurer: TextMeasurer,
) {
    if (range.isEmpty) return
    val indices = (range.startInclusive until range.endExclusive).filter { candles[it].isRenderable }
    if (indices.isEmpty()) return
    if (config.high.show) {
        val index = indices.maxBy { candles[it].high }
        drawPriceExtremeMark(
            index = index,
            value = candles[index].high,
            plotRect = plotRect,
            values = values,
            viewport = viewport,
            precision = precision,
            style = style,
            config = config.high,
            textMeasurer = textMeasurer,
        )
    }
    if (config.low.show) {
        val index = indices.minBy { candles[it].low }
        drawPriceExtremeMark(
            index = index,
            value = candles[index].low,
            plotRect = plotRect,
            values = values,
            viewport = viewport,
            precision = precision,
            style = style,
            config = config.low,
            textMeasurer = textMeasurer,
        )
    }
}

internal fun DrawScope.drawPriceExtremeMark(
    index: Int,
    value: Double,
    plotRect: Rect,
    values: KlineValueRange,
    viewport: KlineViewport,
    precision: Int,
    style: KlineChartStyle,
    config: KlinePriceMarkerRenderConfig,
    textMeasurer: TextMeasurer,
) {
    val start = Offset(
        viewport.xForIndex(plotRect.right, index.toDouble()) - viewport.candleHalfStepPx,
        values.yFor(value, plotRect),
    )
    val direction = if (start.x > plotRect.center.x) -1f else 1f
    val lineLength = logicalPx(config.lineLengthPx ?: 0f)
    val end = Offset(start.x + direction * lineLength, start.y)
    drawConfiguredLine(config.line, fallbackColor = style.markLine, start = start, end = end)
    val textArea = config.text
    val textStyle = textArea.toComposeTextStyle(style.ticksTextColor)
    val text = formatPrice(value, precision)
    val naturalLayout = textMeasurer.measure(
        text = AnnotatedString(text),
        style = textStyle,
        maxLines = textArea.maxLines,
        overflow = TextOverflow.Ellipsis,
    )
    val contentWidth = textArea.resolveContentWidth(naturalLayout.size.width.toFloat(), density)
    val layout = textMeasurer.measure(
        text = AnnotatedString(text),
        style = textStyle,
        constraints = Constraints.fixedWidth(contentWidth.roundToInt().coerceAtLeast(1)),
        maxLines = textArea.maxLines,
        overflow = TextOverflow.Ellipsis,
    )
    val paddingLeft = logicalPx(textArea.padding.leftPx)
    val paddingRight = logicalPx(textArea.padding.rightPx)
    val paddingTop = logicalPx(textArea.padding.topPx)
    val paddingBottom = logicalPx(textArea.padding.bottomPx)
    val areaWidth = contentWidth + paddingLeft + paddingRight
    val areaHeight = layout.size.height + paddingTop + paddingBottom
    val preferredLeft = if (direction < 0f) {
        end.x - logicalPx(config.spacingPx) - areaWidth
    } else {
        end.x + logicalPx(config.spacingPx)
    }
    val left = preferredLeft.coerceIn(plotRect.left, (plotRect.right - areaWidth).coerceAtLeast(plotRect.left))
    val top = (start.y - areaHeight / 2f)
        .coerceIn(plotRect.top, (plotRect.bottom - areaHeight).coerceAtLeast(plotRect.top))
    drawKlineTextAreaBackground(
        rect = Rect(left, top, left + areaWidth, top + areaHeight),
        config = textArea,
        fallbackBackground = null,
        fallbackBorder = null,
    )
    drawText(
        textMeasurer = textMeasurer,
        text = AnnotatedString(text),
        topLeft = Offset(left + paddingLeft, top + paddingTop),
        style = textStyle,
        size = Size(contentWidth, layout.size.height.toFloat()),
        maxLines = textArea.maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

internal fun DrawScope.drawLatestLinePoint(
    latest: KlineCandle?,
    plotRect: Rect,
    values: KlineValueRange,
    viewport: KlineViewport,
    style: KlineChartStyle,
    config: KlineCandleOverlayRenderConfig,
) {
    val candle = latest ?: return
    val point = config.latestPoint ?: return
    if (!config.showLatestPoint || !candle.isRenderable) return
    val center = Offset(
        viewport.xForIndex(plotRect.right, 0.0) - viewport.candleHalfStepPx,
        values.yFor(candle.close, plotRect),
    )
    if (center.x !in plotRect.left..plotRect.right) return
    val radius = logicalPx(point.radiusPx)
    if (radius <= 0f) return
    drawCircle(point.color ?: style.line, radius = radius, center = center)
    if (point.borderWidthPx > 0f) {
        drawCircle(
            color = point.borderColor ?: style.line.copy(alpha = 0.5f),
            radius = radius,
            center = center,
            style = Stroke(width = logicalPx(point.borderWidthPx)),
        )
    }
}

internal fun resolveLatestPriceMarkerVisual(
    latest: KlineCandle?,
    plotRect: Rect,
    values: KlineValueRange,
    viewport: KlineViewport,
    precision: Int,
    style: KlineChartStyle,
    config: KlineCandleOverlayRenderConfig,
    textMeasurer: TextMeasurer,
    densityScale: Float,
): KlineLatestPriceMarkerVisual? {
    val candle = latest ?: return null
    if (!candle.isRenderable) return null
    val latestCenter = viewport.xForIndex(plotRect.right, 0.0) - viewport.candleHalfStepPx
    val inView = latestCenter in plotRect.left..plotRect.right
    val marker = if (inView) config.inViewPrice else config.offViewPrice
    if (!marker.show) return null
    val candleColor = if (candle.close >= candle.open) style.bullish else style.bearish
    val fallbackLineColor = if (config.useCandleColorForLatestPriceBackground && inView) {
        candleColor
    } else {
        style.markLine
    }
    val text = buildString {
        append(formatPrice(candle.close, precision))
        if (!inView) append(" ▸")
    }
    val textArea = marker.text
    val fallbackTextColor =
        if (config.useCandleColorForLatestPriceBackground && inView) {
            Color.White
        } else if (inView) {
            style.ticksTextColor
        } else {
            style.lastPriceTextColor
        }
    val textStyle = textArea.toComposeTextStyle(fallbackTextColor)
    val naturalLayout = textMeasurer.measure(
        text = AnnotatedString(text),
        style = textStyle,
        maxLines = textArea.maxLines,
        overflow = TextOverflow.Ellipsis,
    )
    val paddingLeft = textArea.padding.leftPx * densityScale
    val paddingRight = textArea.padding.rightPx * densityScale
    val paddingTop = textArea.padding.topPx * densityScale
    val paddingBottom = textArea.padding.bottomPx * densityScale
    val contentWidth = textArea.resolveContentWidth(naturalLayout.size.width.toFloat(), densityScale)
    val layout = textMeasurer.measure(
        text = AnnotatedString(text),
        style = textStyle,
        constraints = Constraints.fixedWidth(contentWidth.roundToInt().coerceAtLeast(1)),
        maxLines = textArea.maxLines,
        overflow = TextOverflow.Ellipsis,
    )
    val geometry = resolveKlineLatestPriceMarkerGeometry(
        plotRect = plotRect,
        latestCenterX = latestCenter,
        latestPriceY = values.yFor(candle.close, plotRect),
        marker = marker,
        labelSize = Size(
            contentWidth + paddingLeft + paddingRight,
            layout.size.height.toFloat() + paddingTop + paddingBottom,
        ),
        densityScale = densityScale,
    ) ?: return null
    val background = if (config.useCandleColorForLatestPriceBackground && inView) {
        candleColor
    } else {
        if (inView) style.latestPriceBackground else style.lastPriceBackground
    }
    return KlineLatestPriceMarkerVisual(
        geometry = geometry,
        text = text,
        textStyle = textStyle,
        textTopLeft = Offset(
            geometry.labelRect.left + paddingLeft,
            geometry.labelRect.top + paddingTop,
        ),
        textSize = Size(contentWidth, layout.size.height.toFloat()),
        fallbackLineColor = fallbackLineColor,
        background = background,
        textArea = textArea,
        borderColor = if (config.useCandleColorForLatestPriceBackground && inView) {
            Color.Transparent
        } else {
            style.markLine
        },
    )
}

internal fun DrawScope.drawLatestPrice(
    latest: KlineCandle?,
    interval: KlineInterval?,
    nowMillis: Long,
    plotRect: Rect,
    values: KlineValueRange,
    viewport: KlineViewport,
    precision: Int,
    style: KlineChartStyle,
    config: KlineCandleOverlayRenderConfig,
    textMeasurer: TextMeasurer,
) {
    val visual = resolveLatestPriceMarkerVisual(
        latest = latest,
        plotRect = plotRect,
        values = values,
        viewport = viewport,
        precision = precision,
        style = style,
        config = config,
        textMeasurer = textMeasurer,
        densityScale = density,
    ) ?: return
    val geometry = visual.geometry
    drawConfiguredLine(
        stroke = geometry.marker.line,
        fallbackColor = visual.fallbackLineColor,
        start = Offset(geometry.lineStartX, geometry.y),
        end = Offset(geometry.labelRect.left, geometry.y),
    )
    drawKlineTextAreaBackground(
        rect = geometry.labelRect,
        config = visual.textArea,
        fallbackBackground = visual.background,
        fallbackBorder = visual.borderColor,
    )
    drawText(
        textMeasurer = textMeasurer,
        text = AnnotatedString(visual.text),
        topLeft = visual.textTopLeft,
        style = visual.textStyle,
        size = visual.textSize,
        maxLines = visual.textArea.maxLines,
        overflow = TextOverflow.Ellipsis,
    )
    val countdownText = if (geometry.isInView && config.countdown.show) {
        resolveKlineCountdownText(latest, interval, nowMillis)
    } else {
        null
    } ?: return
    val countdown = config.countdown.text
    val countdownStyle = countdown.toComposeTextStyle(style.textColor)
    val countdownPaddingTop = logicalPx(countdown.padding.topPx)
    val countdownPaddingBottom = logicalPx(countdown.padding.bottomPx)
    val countdownContentWidth = (
        geometry.labelRect.width -
            logicalPx(countdown.padding.leftPx) -
            logicalPx(countdown.padding.rightPx)
        ).coerceAtLeast(1f)
    val countdownLayout = textMeasurer.measure(
        text = AnnotatedString(countdownText),
        style = countdownStyle,
        constraints = Constraints.fixedWidth(countdownContentWidth.roundToInt().coerceAtLeast(1)),
        overflow = TextOverflow.Ellipsis,
        maxLines = countdown.maxLines,
    )
    val countdownHeight = countdownLayout.size.height + countdownPaddingTop + countdownPaddingBottom
    val countdownRect = Rect(
        left = geometry.labelRect.left,
        top = geometry.labelRect.bottom - 0.5f * density,
        right = geometry.labelRect.right,
        bottom = geometry.labelRect.bottom - 0.5f * density + countdownHeight,
    )
    drawKlineTextAreaBackground(
        rect = countdownRect,
        config = if (config.useCandleColorForLatestPriceBackground) {
            countdown.copy(borderWidthPx = 0f)
        } else {
            countdown
        },
        fallbackBackground = style.countdownBackground,
        fallbackBorder = style.markLine,
    )
    drawText(
        textMeasurer = textMeasurer,
        text = AnnotatedString(countdownText),
        topLeft = Offset(
            countdownRect.left + logicalPx(countdown.padding.leftPx),
            countdownRect.top + countdownPaddingTop,
        ),
        style = countdownStyle,
        size = Size(countdownContentWidth, countdownLayout.size.height.toFloat()),
        maxLines = countdown.maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

internal fun KlineTextAreaRenderConfig.resolveContentWidth(
    naturalWidthPx: Float,
    densityScale: Float,
): Float = textWidthPx?.times(densityScale)
    ?: naturalWidthPx
        .coerceAtLeast((minWidthPx ?: 0f) * densityScale)
        .let { width -> maxWidthPx?.let { width.coerceAtMost(it * densityScale) } ?: width }

internal fun KlineTextAreaRenderConfig.toComposeTextStyle(fallbackColor: Color): TextStyle = TextStyle(
    color = textColor ?: fallbackColor,
    fontSize = fontSizeSp.sp,
    fontFamily = when (fontFamily?.lowercase()) {
        "serif" -> FontFamily.Serif
        "monospace" -> FontFamily.Monospace
        "cursive" -> FontFamily.Cursive
        "sans-serif", "sansserif" -> FontFamily.SansSerif
        else -> null
    },
    fontStyle = fontStyle,
    fontWeight = fontWeight,
    lineHeight = (
        strutHeightMultiplier.takeIf { forceStrutHeight } ?: lineHeightMultiplier ?: strutHeightMultiplier
        )?.let { (fontSizeSp * it).sp } ?: androidx.compose.ui.unit.TextUnit.Unspecified,
    textDecoration = textDecoration,
    textAlign = when (textAlign) {
        KlineTextAlign.Start -> TextAlign.Start
        KlineTextAlign.Center -> TextAlign.Center
        KlineTextAlign.End -> TextAlign.End
        KlineTextAlign.Left -> TextAlign.Left
        KlineTextAlign.Right -> TextAlign.Right
        KlineTextAlign.Justify -> TextAlign.Justify
    },
)

internal fun DrawScope.drawKlineTextAreaBackground(
    rect: Rect,
    config: KlineTextAreaRenderConfig,
    fallbackBackground: Color?,
    fallbackBorder: Color?,
) {
    val radius = config.borderRadius
    val roundRect = RoundRect(
        rect = rect,
        topLeft = CornerRadius(logicalPx(radius.topLeft.xPx), logicalPx(radius.topLeft.yPx)),
        topRight = CornerRadius(logicalPx(radius.topRight.xPx), logicalPx(radius.topRight.yPx)),
        bottomRight = CornerRadius(logicalPx(radius.bottomRight.xPx), logicalPx(radius.bottomRight.yPx)),
        bottomLeft = CornerRadius(logicalPx(radius.bottomLeft.xPx), logicalPx(radius.bottomLeft.yPx)),
    )
    val path = Path().apply { addRoundRect(roundRect) }
    (config.backgroundColor ?: fallbackBackground)?.let { drawPath(path, color = it) }
    val borderColor = config.borderColor ?: fallbackBorder
    if (config.borderWidthPx > 0f && borderColor != null) {
        drawPath(
            path = path,
            color = borderColor,
            style = Stroke(width = logicalPx(config.borderWidthPx)),
        )
    }
}

internal enum class KlineTextAreaHorizontalAnchor {
    Start,
    Center,
    End,
}

internal enum class KlineTextAreaVerticalAnchor {
    Top,
    Center,
    Bottom,
}
/** Shared Canvas implementation for styled text areas. */

internal fun DrawScope.drawKlineTextArea(
    text: String,
    anchor: Offset,
    horizontalAnchor: KlineTextAreaHorizontalAnchor,
    verticalAnchor: KlineTextAreaVerticalAnchor,
    config: KlineTextAreaRenderConfig,
    textMeasurer: TextMeasurer,
    fallbackTextColor: Color,
    fallbackBackground: Color? = null,
    fallbackBorder: Color? = null,
    drawableRect: Rect? = null,
): Rect {
    val textStyle = config.toComposeTextStyle(fallbackTextColor)
    val naturalLayout = textMeasurer.measure(
        text = AnnotatedString(text),
        style = textStyle,
        maxLines = config.maxLines,
        overflow = TextOverflow.Ellipsis,
    )
    val contentWidth = config.resolveContentWidth(naturalLayout.size.width.toFloat(), density)
    val layout = textMeasurer.measure(
        text = AnnotatedString(text),
        style = textStyle,
        constraints = Constraints.fixedWidth(contentWidth.roundToInt().coerceAtLeast(1)),
        maxLines = config.maxLines,
        overflow = TextOverflow.Ellipsis,
    )
    val paddingLeft = logicalPx(config.padding.leftPx)
    val paddingTop = logicalPx(config.padding.topPx)
    val width = contentWidth + paddingLeft + logicalPx(config.padding.rightPx)
    val height = layout.size.height + paddingTop + logicalPx(config.padding.bottomPx)
    var left = when (horizontalAnchor) {
        KlineTextAreaHorizontalAnchor.Start -> anchor.x
        KlineTextAreaHorizontalAnchor.Center -> anchor.x - width / 2f
        KlineTextAreaHorizontalAnchor.End -> anchor.x - width
    }
    var top = when (verticalAnchor) {
        KlineTextAreaVerticalAnchor.Top -> anchor.y
        KlineTextAreaVerticalAnchor.Center -> anchor.y - height / 2f
        KlineTextAreaVerticalAnchor.Bottom -> anchor.y - height
    }
    drawableRect?.let { bounds ->
        left = left.coerceIn(bounds.left, (bounds.right - width).coerceAtLeast(bounds.left))
        top = top.coerceIn(bounds.top, (bounds.bottom - height).coerceAtLeast(bounds.top))
    }
    val rect = Rect(left, top, left + width, top + height)
    drawKlineTextAreaBackground(rect, config, fallbackBackground, fallbackBorder)
    drawText(
        textMeasurer = textMeasurer,
        text = AnnotatedString(text),
        topLeft = Offset(left + paddingLeft, top + paddingTop),
        style = textStyle,
        size = Size(contentWidth, layout.size.height.toFloat()),
        maxLines = config.maxLines,
        overflow = TextOverflow.Ellipsis,
    )
    return rect
}
/**
 * Remaining interval time below the in-view latest-price marker.
 *
 * Uses the candle opening timestamp plus the configured interval, hides
 * intervals of one second or less, and suppresses expired values.
 */

internal fun resolveKlineCountdownText(
    candle: KlineCandle?,
    interval: KlineInterval?,
    nowMillis: Long,
): String? {
    val durationMillis = interval?.approximateDurationMillis ?: return null
    if (!interval.isValid || durationMillis <= 1_000L) return null
    val nextUpdateMillis = try {
        Math.addExact(candle?.timestampMillis ?: return null, durationMillis)
    } catch (_: ArithmeticException) {
        return null
    }
    val remainingMillis = nextUpdateMillis - nowMillis
    if (remainingMillis <= 0L) return null
    val totalSeconds = remainingMillis / 1_000L
    val secondsPerMinute = 60L
    val secondsPerHour = 60L * secondsPerMinute
    val secondsPerDay = 24L * secondsPerHour
    return when {
        totalSeconds >= secondsPerDay -> {
            val days = totalSeconds / secondsPerDay
            val hours = totalSeconds % secondsPerDay / secondsPerHour
            "${days}D:${hours.twoCountdownDigits()}H"
        }
        totalSeconds >= secondsPerHour -> {
            val hours = totalSeconds / secondsPerHour
            val minutes = totalSeconds % secondsPerHour / secondsPerMinute
            val seconds = totalSeconds % secondsPerMinute
            "${hours.twoCountdownDigits()}:${minutes.twoCountdownDigits()}:${seconds.twoCountdownDigits()}"
        }
        else -> {
            val minutes = totalSeconds / secondsPerMinute
            val seconds = totalSeconds % secondsPerMinute
            "${minutes.twoCountdownDigits()}:${seconds.twoCountdownDigits()}"
        }
    }
}

internal fun Long.twoCountdownDigits(): String =
    if (this < 10L) "0$this" else toString()

internal fun DrawScope.drawTimeAxis(
    candles: List<KlineCandle>,
    range: IndexRange,
    chartRect: Rect,
    timeRect: Rect,
    timeOuterRect: Rect,
    viewport: KlineViewport,
    style: KlineChartStyle,
    textMeasurer: TextMeasurer,
    interval: KlineInterval?,
    config: KlineTimeAxisRenderConfig,
    formatter: KlineTimeLabelFormatter,
) {
    if (range.isEmpty || interval?.isValid == false) return
    val textArea = config.text
    val cadenceWidth = textArea.textWidthPx ?: textArea.minWidthPx ?: 80f
    // Use the globally stable candle index for cadence so labels do not jump
    // phase while panning.
    val every = max(1, (max(60f, cadenceWidth) * density / viewport.candleStepPx).roundToInt())

    fun paintLabels() {
        for (index in range.startInclusive until range.endExclusive) {
            if (index % every != 0) continue
            val x = viewport.xForIndex(chartRect.right, index.toDouble()) - viewport.candleHalfStepPx
            if (x < chartRect.left - logicalPx(cadenceWidth) || x > chartRect.right) continue
            val label = formatter(candles[index], interval)
            drawKlineTextArea(
                text = label,
                anchor = Offset(x, timeRect.center.y),
                horizontalAnchor = KlineTextAreaHorizontalAnchor.Center,
                verticalAnchor = KlineTextAreaVerticalAnchor.Center,
                config = textArea,
                textMeasurer = textMeasurer,
                fallbackTextColor = style.ticksTextColor,
            )
        }
    }
    if (config.clipToDrawableRect) {
        clipRect(timeOuterRect.left, timeOuterRect.top, timeOuterRect.right, timeOuterRect.bottom) {
            paintLabels()
        }
    } else {
        paintLabels()
    }
}
/** Crosshair time label rendered in the resolved Time pane. */

internal fun DrawScope.drawCrossTimeLabel(
    candle: KlineCandle,
    snappedX: Float,
    timeRect: Rect,
    style: KlineChartStyle,
    textMeasurer: TextMeasurer,
    interval: KlineInterval?,
    config: KlineCrosshairRenderConfig,
    formatter: KlineTimeLabelFormatter,
) {
    if (timeRect.width <= 0f || timeRect.height <= 0f || interval?.isValid == false) return
    val text = formatter(candle, interval)
    drawKlineTextArea(
        text = text,
        anchor = Offset(snappedX, timeRect.center.y),
        horizontalAnchor = KlineTextAreaHorizontalAnchor.Center,
        verticalAnchor = KlineTextAreaVerticalAnchor.Center,
        config = config.ticksText,
        textMeasurer = textMeasurer,
        fallbackTextColor = style.crossTextColor,
        fallbackBackground = style.crossTextBackground,
        fallbackBorder = style.crosshair,
        drawableRect = timeRect,
    )
}

internal fun DrawScope.drawCrosshair(
    crosshair: KlineIndicatorCrosshairContext,
    state: KlineUiState,
    plotRect: Rect,
    tooltipAnchorRect: Rect,
    verticalRect: Rect,
    crosshairRightPx: Float,
    values: KlineValueRange,
    style: KlineChartStyle,
    config: KlineCrosshairRenderConfig,
    textMeasurer: TextMeasurer,
    crossTooltipProvider: KlineCrossTooltipProvider?,
    stableTooltipContentWidthPx: Float?,
    timeLabelFormatter: KlineTimeLabelFormatter,
): KlineCrosshairPaintResult? {
    if (!config.enabled) return null
    val position = crosshair.rawPosition
    val snappedX = crosshair.position.x
    val value = values.valueAt(position.y, plotRect)
    val crossPosition = crosshair.position
    val effectiveRight = crosshairRightPx.coerceIn(plotRect.left, plotRect.right)
    clipRect(plotRect.left, verticalRect.top, effectiveRight, verticalRect.bottom) {
        drawConfiguredLine(
            stroke = config.stroke,
            fallbackColor = style.crosshair,
            start = Offset(snappedX, verticalRect.top),
            end = Offset(snappedX, verticalRect.bottom),
        )
        drawConfiguredLine(
            stroke = config.stroke,
            fallbackColor = style.crosshair,
            start = Offset(plotRect.left, position.y),
            end = Offset(effectiveRight, position.y),
        )
    }
    val pointRadius = logicalPx(config.pointRadiusPx)
    if (pointRadius > 0f) {
        clipRect(plotRect.left, verticalRect.top, effectiveRight, verticalRect.bottom) {
            drawCircle(config.pointColor ?: style.background, radius = pointRadius, center = crossPosition)
            if (config.pointBorderWidthPx > 0f) {
                drawCircle(
                    config.pointBorderColor ?: config.stroke.color ?: style.crosshair,
                    radius = pointRadius,
                    center = crossPosition,
                    style = Stroke(width = logicalPx(config.pointBorderWidthPx)),
                )
            }
        }
    }
    drawKlineTextArea(
        text = formatPrice(value, state.spec?.precision ?: KlineSpec.DefaultPrecision),
        anchor = Offset(plotRect.right - logicalPx(config.ticksSpacingPx), position.y),
        horizontalAnchor = KlineTextAreaHorizontalAnchor.End,
        verticalAnchor = KlineTextAreaVerticalAnchor.Center,
        config = config.ticksText,
        textMeasurer = textMeasurer,
        fallbackTextColor = style.crossTextColor,
        fallbackBackground = style.crossTextBackground,
        fallbackBorder = style.crosshair,
        drawableRect = plotRect,
    )
    resolveKlineCrossTooltipPresentation(
        crosshair = crosshair,
        state = state,
        plotRect = plotRect,
        anchorRect = tooltipAnchorRect,
        config = config,
        style = style,
        textMeasurer = textMeasurer,
        densityScale = density,
        timeLabelFormatter = timeLabelFormatter,
        provider = crossTooltipProvider,
        stableContentWidthPx = stableTooltipContentWidthPx,
    )?.let(::drawKlineCrossTooltip)
    return KlineCrosshairPaintResult(context = crosshair, value = value)
}
/** Builds deterministic two-column Cross Tooltip content, geometry, and text measurements. */

internal fun resolveKlineCrossTooltipPresentation(
    crosshair: KlineIndicatorCrosshairContext,
    state: KlineUiState,
    plotRect: Rect,
    anchorRect: Rect,
    config: KlineCrosshairRenderConfig,
    style: KlineChartStyle,
    textMeasurer: TextMeasurer,
    densityScale: Float,
    timeLabelFormatter: KlineTimeLabelFormatter,
    provider: KlineCrossTooltipProvider?,
    stableContentWidthPx: Float?,
): KlineCrossTooltipPresentation? {
    if (!config.enabled || !config.showTooltip) return null
    val selected = crosshair.candle ?: return null
    val context = KlineCrossTooltipContext(
        crosshair = crosshair,
        spec = state.spec,
        timeLabel = timeLabelFormatter(selected, state.spec?.interval),
    )
    val items = provider?.provide(context) ?: defaultKlineCrossTooltipItems(context, style)
    if (items.isEmpty()) return null
    val defaultTextStyle = config.tooltipTextArea?.toComposeTextStyle(style.tooltipTextColor)
        ?: TextStyle(color = style.tooltipTextColor, fontSize = 10.sp)

    fun measureItems(valueMaxWidthPx: Int? = null): List<KlineCrossTooltipTextLayouts> = items.map { item ->
        KlineCrossTooltipTextLayouts(
            label = textMeasurer.measure(
                text = AnnotatedString(item.label),
                style = defaultTextStyle.merge(item.labelStyle).copy(textAlign = TextAlign.Start),
            ),
            value = textMeasurer.measure(
                text = AnnotatedString(item.value),
                style = defaultTextStyle.merge(item.valueStyle).copy(textAlign = TextAlign.End),
                constraints = valueMaxWidthPx?.let { maxWidth -> Constraints(maxWidth = maxWidth) } ?: Constraints(),
            ),
        )
    }

    fun measurements(textLayouts: List<KlineCrossTooltipTextLayouts>): List<KlineCrossTooltipItemMeasurement> =
        textLayouts.map { measured ->
            KlineCrossTooltipItemMeasurement(
                label = Size(measured.label.size.width.toFloat(), measured.label.size.height.toFloat()),
                value = Size(measured.value.size.width.toFloat(), measured.value.size.height.toFloat()),
            )
        }
    val padding = (config.tooltipTextArea?.padding ?: config.tooltipPadding).toCanvasPixels(densityScale)
    val margin = config.tooltipMargin.toCanvasPixels(densityScale)
    val columnSpacingPx = config.tooltipSpacingPx * densityScale
    var textLayouts = measureItems()
    var layout = layoutKlineCrossTooltip(
        plotRect = plotRect,
        crossX = crosshair.position.x,
        itemMeasurements = measurements(textLayouts),
        padding = padding,
        margin = margin,
        columnSpacingPx = columnSpacingPx,
        minContentWidthPx = stableContentWidthPx,
        anchorRect = anchorRect,
    ) ?: return null
    val maxLabelWidth = textLayouts.maxOf { measured -> measured.label.size.width.toFloat() }
    val maxValueWidth = textLayouts.maxOf { measured -> measured.value.size.width.toFloat() }
    val valueMaxWidthPx = (layout.contentWidthPx - maxLabelWidth - columnSpacingPx).coerceAtLeast(0f)
    if (valueMaxWidthPx > 0f && valueMaxWidthPx < maxValueWidth) {
        // Lay out values a second time when a narrow plot constrains their
        // column. Preserve the already selected card width while
        // recomputing wrapped row heights and hit targets from those layouts.
        val constrainedContentWidthPx = layout.contentWidthPx
        textLayouts = measureItems(valueMaxWidthPx.roundToInt().coerceAtLeast(1))
        layout = layoutKlineCrossTooltip(
            plotRect = plotRect,
            crossX = crosshair.position.x,
            itemMeasurements = measurements(textLayouts),
            padding = padding,
            margin = margin,
            columnSpacingPx = columnSpacingPx,
            minContentWidthPx = constrainedContentWidthPx,
            anchorRect = anchorRect,
        ) ?: return null
    }
    return KlineCrossTooltipPresentation(
        context = context,
        items = items,
        layout = layout,
        textLayouts = textLayouts,
        backgroundColor = config.tooltipTextArea?.backgroundColor ?: style.tooltipBackground,
        textArea = config.tooltipTextArea,
    )
}
/** Draws a [KlineCrossTooltipPresentation] without creating persistent Canvas state. */

internal fun DrawScope.drawKlineCrossTooltip(presentation: KlineCrossTooltipPresentation) {
    val card = presentation.layout.cardBounds
    presentation.textArea?.let { textArea ->
        drawKlineTextAreaBackground(
            rect = card,
            config = textArea,
            fallbackBackground = presentation.backgroundColor,
            fallbackBorder = null,
        )
    } ?: drawRect(
        color = presentation.backgroundColor,
        topLeft = Offset(card.left, card.top),
        size = Size(card.width, card.height),
    )
    presentation.layout.rows.forEach { row ->
        val text = presentation.textLayouts[row.index]
        drawText(textLayoutResult = text.label, topLeft = row.labelTopLeft)
        drawText(textLayoutResult = text.value, topLeft = row.valueTopLeft)
    }
}
/**
 * Combined main indicators share the candle plot and must expand its value
 * range even when their values lie outside OHLC. The passed
 * candles are the paint range while indicator columns retain their full,
 * newest-first series indexing.
 */

internal fun List<KlineCandle>.valueRangeIncluding(
    mainOutputs: List<IndicatorOutput>,
    paintRange: IndexRange,
    rendererRanges: List<KlineIndicatorValueRange> = emptyList(),
    sameValueExpansionRatios: List<Float> = listOf(0.1f, 0.05f),
    includeMainIndicators: Boolean = true,
): KlineValueRange {
    var low = Double.POSITIVE_INFINITY
    var high = Double.NEGATIVE_INFINITY
    forEach { candle ->
        if (!candle.isRenderable) return@forEach
        low = min(low, candle.low)
        high = max(high, candle.high)
    }
    if (includeMainIndicators) {
        mainOutputs.forEach { output ->
            output.columns().forEach { column ->
                for (index in paintRange.startInclusive until paintRange.endExclusive) {
                    val value = column[index]
                    if (!value.isFinite()) continue
                    low = min(low, value)
                    high = max(high, value)
                }
            }
        }
        rendererRanges.forEach { range ->
            low = min(low, range.minimum)
            high = max(high, range.maximum)
        }
    }
    return if (low.isFinite() && high.isFinite()) {
        normalizedValueRange(low = low, high = high, sameValueExpansionRatios)
    } else {
        KlineValueRange(-1.0, 1.0)
    }
}

internal fun List<KlineCandle>.valueRange(): KlineValueRange =
    valueRangeIncluding(mainOutputs = emptyList(), paintRange = IndexRange(0, size))

internal fun List<IndicatorOutput>.valueRange(
    range: IndexRange,
    minimum: Double? = null,
    rendererRanges: List<KlineIndicatorValueRange> = emptyList(),
    sameValueExpansionRatios: List<Float> = listOf(0.1f, 0.05f),
): KlineValueRange {
    var low = minimum ?: Double.POSITIVE_INFINITY
    var high = Double.NEGATIVE_INFINITY
    forEach { output ->
        output.columns().forEach { column ->
            for (index in range.startInclusive until range.endExclusive) {
                val value = column[index]
                if (value.isFinite()) {
                    low = min(low, value)
                    high = max(high, value)
                }
            }
        }
    }
    rendererRanges.forEach { contribution ->
        low = min(low, contribution.minimum)
        high = max(high, contribution.maximum)
    }
    if (!low.isFinite()) low = minimum ?: -1.0
    if (!high.isFinite()) high = if (minimum != null) minimum + 1.0 else 1.0
    return normalizedValueRange(low, high, sameValueExpansionRatios)
}

internal fun com.zhumeng.kanvas.core.IndicatorColumn.valueRange(
    range: IndexRange,
    minimum: Double? = null,
): KlineValueRange {
    var low = minimum ?: Double.POSITIVE_INFINITY
    var high = Double.NEGATIVE_INFINITY
    for (index in range.startInclusive until range.endExclusive) {
        val value = this[index]
        if (value.isFinite()) {
            low = min(low, value)
            high = max(high, value)
        }
    }
    if (!low.isFinite()) low = minimum ?: -1.0
    if (!high.isFinite()) high = if (minimum != null) minimum + 1.0 else 1.0
    return normalizedValueRange(low, high)
}

internal fun normalizedValueRange(
    low: Double,
    high: Double,
    sameValueExpansionRatios: List<Float> = listOf(0.1f, 0.05f),
): KlineValueRange {
    var adjustedLow = low
    var adjustedHigh = high
    if (abs(adjustedHigh - adjustedLow) < 1e-12) {
        val value = adjustedHigh
        val upperRatio = sameValueExpansionRatios.getOrNull(0)?.coerceAtLeast(0f)?.toDouble() ?: 0.0
        val lowerRatio = sameValueExpansionRatios.getOrNull(1)?.coerceAtLeast(0f)?.toDouble() ?: 0.0
        adjustedHigh = value * (1.0 + upperRatio)
        adjustedLow = value * (1.0 - lowerRatio)
        if (adjustedLow > adjustedHigh) {
            val swap = adjustedLow
            adjustedLow = adjustedHigh
            adjustedHigh = swap
        }
        if (abs(adjustedHigh - adjustedLow) < 1e-12) {
            adjustedHigh = value + 1.0
            adjustedLow = value - 1.0
        }
    }
    val padding = (adjustedHigh - adjustedLow) * 0.05
    return KlineValueRange(adjustedLow - padding, adjustedHigh + padding)
}

internal fun KlineValueRange.yFor(value: Double, plotRect: Rect): Float =
    (plotRect.bottom - ((value - minimum) / span).toFloat() * plotRect.height)

internal fun KlineValueRange.valueAt(y: Float, plotRect: Rect): Double =
    minimum + ((plotRect.bottom - y) / plotRect.height).toDouble() * span
/**
 * Resolves one immutable Cross selection for Canvas, native indicator hooks,
 * and host callbacks. The original pointer is retained because an indicator
 * can live in a sub pane while the built-in Cross line is clamped to Main.
 */

internal fun resolveKlineIndicatorCrosshairContext(
    inputPosition: Offset,
    state: KlineUiState,
    plotRect: Rect,
    renderConfig: KlineCrosshairRenderConfig,
    crosshairRightPx: Float = plotRect.right,
): KlineIndicatorCrosshairContext? {
    if (state.series.isEmpty || plotRect.width <= 0f || plotRect.height <= 0f) return null
    val effectiveRight = crosshairRightPx.coerceIn(plotRect.left, plotRect.right)
    val clamped = Offset(
        inputPosition.x.coerceIn(plotRect.left, effectiveRight),
        inputPosition.y.coerceIn(plotRect.top, plotRect.bottom),
    )
    val xResolution = resolveKlineCrosshairX(
        viewport = state.viewport,
        // Match the candle renderer's anchor. The price-axis boundary only
        // clips the painted line; it must not shift the candle index grid.
        plotRightPx = plotRect.right,
        rawX = clamped.x,
        candleCount = state.series.size,
        moveByCandleInBlank = renderConfig.moveByCandleInBlank,
        showLatestTipsInBlank = renderConfig.showLatestTipsInBlank,
    )
    val candleIndex = xResolution.candleIndex
    return KlineIndicatorCrosshairContext(
        inputPosition = inputPosition,
        rawPosition = clamped,
        position = Offset(xResolution.snappedX, clamped.y),
        candleIndex = candleIndex,
        candle = candleIndex?.let(state.series.candles::get),
        // Core candle data is newest-first, so index + 1 is the older
        // neighbour used by indicator/cross calculations.
        previousCandle = candleIndex?.plus(1)?.let { index -> state.series.candles.getOrNull(index) },
    )
}

internal fun Offset.toKlineCrosshair(
    state: KlineUiState,
    plotRect: Rect,
    renderConfig: KlineChartRenderConfig,
    valueRange: KlineValueRange,
    crosshairRightPx: Float = plotRect.right,
): KlineCrosshair? {
    val context = resolveKlineIndicatorCrosshairContext(
        inputPosition = this,
        state = state,
        plotRect = plotRect,
        renderConfig = renderConfig.crosshair,
        crosshairRightPx = crosshairRightPx,
    ) ?: return null
    return KlineCrosshair(
        position = context.position,
        candleIndex = context.candleIndex,
        candle = context.candle,
        value = valueRange.valueAt(context.rawPosition.y, plotRect),
    )
}

internal fun formatPrice(value: Double, precision: Int): String =
    String.format(Locale.US, "%.${precision.coerceIn(0, 12)}f", value)
