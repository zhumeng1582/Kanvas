/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import android.os.Trace
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
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
import androidx.compose.ui.layout.MeasurePolicy
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

private inline fun <T> traceKlineSection(name: String, block: () -> T): T {
    Trace.beginSection(name)
    return try {
        block()
    } finally {
        Trace.endSection()
    }
}
/** Resolved crosshair details sent to the host for custom tooltips or analytics. */

data class KlineCrosshair(
    val position: Offset,
    val candleIndex: Int?,
    val candle: KlineCandle?,
    val value: Double?,
)

data class KlineChartSlotContext(
    val state: KlineUiState,
    val layout: KlineLayout,
    val style: KlineChartStyle,
)
/** Runtime-only formatter for time-axis tick labels. */
typealias KlineTimeLabelFormatter = (KlineCandle, KlineInterval?) -> String

private class DefaultKlineTimeLabelFormatter(locale: Locale) {

    private data class CacheKey(
        val timestampMillis: Long,
        val interval: KlineInterval?,
    )

    private val cache = object : LinkedHashMap<CacheKey, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, String>?): Boolean =
            size > 512
    }

    private val date = SimpleDateFormat("yyyy/M/d", locale)

    private val minute = SimpleDateFormat("M/d HH:mm", locale)

    private val second = SimpleDateFormat("HH:mm:ss", locale)

    fun format(candle: KlineCandle, interval: KlineInterval?): String {
        val key = CacheKey(candle.timestampMillis, interval)
        cache[key]?.let { return it }
        val formatter = when (interval?.unit) {
            KlineTimeUnit.Day,
            KlineTimeUnit.Week,
            KlineTimeUnit.Month,
            KlineTimeUnit.Year,
            -> date
            KlineTimeUnit.Minute,
            KlineTimeUnit.Hour,
            -> minute
            else -> second
        }
        return formatter.format(Date(candle.timestampMillis)).also { formatted -> cache[key] = formatted }
    }
}
data class KlineValueRange(
    val minimum: Double,
    val maximum: Double,
) {
    val span: Double get() = maximum - minimum
}

internal data class KlineCrosshairPaintResult(
    val context: KlineIndicatorCrosshairContext,
    val value: Double,
) {
    val candle: KlineCandle? get() = context.candle
    val snappedX: Float get() = context.position.x
}
/** Measured text retained with the immutable geometry used for Cross drawing and taps. */

internal data class KlineCrossTooltipTextLayouts(
    val label: TextLayoutResult,
    val value: TextLayoutResult,
)

internal data class KlineCrossTooltipPresentation(
    val context: KlineCrossTooltipContext,
    val items: List<KlineCrossTooltipItem>,
    val layout: KlineCrossTooltipLayout,
    val textLayouts: List<KlineCrossTooltipTextLayouts>,
    val itemMeasurements: List<KlineCrossTooltipItemMeasurement>,
    val backgroundColor: Color,
    val textArea: KlineTextAreaRenderConfig?,
    val useNativeSimpleText: Boolean = false,
    val defaultTextColor: Color = Color.Unspecified,
)

private fun DrawingTextAreaStyle.toKlineTextAreaRenderConfig(): KlineTextAreaRenderConfig =
    KlineTextAreaRenderConfig(
        textColor = textColor,
        fontSizeSp = fontSizeSp,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        lineHeightMultiplier = lineHeightMultiplier,
        backgroundColor = backgroundColor,
        padding = KlinePanePadding(
            leftPx = paddingLeftPx,
            topPx = paddingTopPx,
            rightPx = paddingRightPx,
            bottomPx = paddingBottomPx,
        ),
        borderColor = borderColor,
        borderWidthPx = borderWidthPx,
        borderRadius = KlineBorderRadius.all(borderRadiusPx),
    )
/**
 * Physical Canvas geometry shared by latest-price painting and its off-view
 * tap target. Keeping this separate from text styling makes the layout
 * deterministic and JVM-testable.
 */

internal data class KlineLatestPriceMarkerGeometry(
    val isInView: Boolean,
    val marker: KlinePriceMarkerRenderConfig,
    val lineStartX: Float,
    val y: Float,
    val labelRect: Rect,
)

internal data class KlineLatestPriceMarkerVisual(
    val geometry: KlineLatestPriceMarkerGeometry,
    val text: String,
    val textStyle: TextStyle,
    val textTopLeft: Offset,
    val textSize: Size,
    val fallbackLineColor: Color,
    val background: Color,
    val textArea: KlineTextAreaRenderConfig,
    val borderColor: Color,
)
/** Resolved crosshair X position and optional candle selection. */

internal data class KlineCrosshairXResolution(
    val candleIndex: Int?,
    val snappedX: Float,
)

internal fun resolveKlineCrosshairX(
    viewport: KlineViewport,
    plotRightPx: Float,
    rawX: Float,
    candleCount: Int,
    moveByCandleInBlank: Boolean,
    showLatestTipsInBlank: Boolean,
): KlineCrosshairXResolution {
    if (candleCount <= 0) return KlineCrosshairXResolution(candleIndex = null, snappedX = rawX)
    val rawIndex = viewport
        .fractionalIndexAt(plotRightPx, rawX + viewport.candleHalfStepPx)
        .roundToInt()
    val inRange = rawIndex.takeIf { it in 0 until candleCount }
    val edge = rawIndex.coerceIn(0, candleCount - 1)
    // A crosshair is always a candle selection. Blank regions and the gaps
    // between candles resolve to the nearest edge/candle index so the
    // vertical line can never float away from a rendered K line.
    val snapIndex = inRange ?: edge
    val tooltipIndex = inRange ?: edge
    return KlineCrosshairXResolution(
        candleIndex = tooltipIndex,
        snappedX = snapIndex?.let { index ->
            viewport.xForIndex(plotRightPx, index.toDouble()) - viewport.candleHalfStepPx
        } ?: rawX,
    )
}
/**
 * Resolves the label rectangle exactly as the latest-price renderer does.
 * [marker] must already be the in-view or off-view configuration selected by
 * the caller; Kanvas logical dimensions are converted with [densityScale]
 * before becoming Canvas geometry.
 */

internal fun resolveKlineLatestPriceMarkerGeometry(
    plotRect: Rect,
    latestCenterX: Float,
    latestPriceY: Float,
    marker: KlinePriceMarkerRenderConfig,
    labelSize: Size,
    densityScale: Float,
): KlineLatestPriceMarkerGeometry? {
    if (!marker.show || plotRect.width <= 0f || plotRect.height <= 0f) return null
    require(densityScale.isFinite() && densityScale > 0f) {
        "Density must be finite and positive."
    }
    val inView = latestCenterX in plotRect.left..plotRect.right
    val labelHalfHeight = min(labelSize.height / 2f, plotRect.height / 2f)
    val y = latestPriceY.coerceIn(
        plotRect.top + labelHalfHeight,
        plotRect.bottom - labelHalfHeight,
    )
    val labelLeft = (plotRect.right - marker.spacingPx * densityScale - labelSize.width)
        .coerceAtLeast(plotRect.left)
    return KlineLatestPriceMarkerGeometry(
        isInView = inView,
        marker = marker,
        lineStartX = if (inView) latestCenterX.coerceIn(plotRect.left, plotRect.right) else plotRect.left,
        y = y,
        labelRect = Rect(
            left = labelLeft,
            top = y - labelSize.height / 2f,
            right = labelLeft + labelSize.width,
            bottom = y + labelSize.height / 2f,
        ),
    )
}
/** Expands a Kanvas logical-pixel hit margin into physical Canvas coordinates. */

internal fun Rect.expandKlineHitTarget(hitTestMarginPx: Float, densityScale: Float): Rect {
    require(hitTestMarginPx >= 0f) { "Hit-test margin must not be negative." }
    require(densityScale.isFinite() && densityScale > 0f) {
        "Density must be finite and positive."
    }
    val margin = hitTestMarginPx * densityScale
    return Rect(left - margin, top - margin, right + margin, bottom + margin)
}
/** Kanvas only shows its built-in spinner for visible loading states and auto-load. */

internal fun shouldShowKlineLoadingOverlay(
    loadingState: KlineLoadingState,
    autoLoadMore: Boolean,
): Boolean = loadingState.showLoading && autoLoadMore
/** Whether a visible Cross was opened by a tap or is only held for a long press. */

private enum class CrosshairSession {
    Persistent,
    Hold,
    Hover,
}
/**
 * Pointer coroutines outlive individual Compose snapshots. Keep the session
 * owner in a stable holder so a new gesture immediately observes the Cross
 * created by the preceding gesture.
 */

private class CrosshairSessionRef(var value: CrosshairSession? = null)

private class CrossTooltipStableWidthRef(var value: Float? = null)

/** Optional high-level state stream whose viewport is observed only by draw and pointer phases. */
internal val LocalKanvasLiveChartState = staticCompositionLocalOf<State<KlineUiState>?> { null }

private class KlineRangeUpdateRef(
    var series: KlineSeries? = null,
    var viewport: KlineViewport? = null,
    var loadingState: KlineLoadingState = KlineLoadingState.None,
    var realtimeMode: Boolean = false,
)

internal fun resolveKlineRealtimeRangeMode(
    viewportChanged: Boolean,
    latestCandleOnlyUpdate: Boolean,
    seriesChanged: Boolean,
    previousMode: Boolean,
): Boolean = when {
    // Viewport semantics win when one composition observes both a MOVE and a
    // market tick; otherwise the realtime factor leaks into later draw-only
    // MOVE frames and the Y axis trails the finger.
    viewportChanged -> false
    latestCandleOnlyUpdate -> true
    seriesChanged -> false
    else -> previousMode
}

/**
 * Isolates series-only ticks during a pointer/fling session without changing
 * viewport-driven auto-range semantics. Tokens keep a cancelled old fling
 * from releasing a newer gesture's state.
 */
internal class KlineGestureValueRangeRef {
    private var nextToken: Long = 0L
    private var activeToken: Long? = null
    private var activeViewport: KlineViewport? = null
    private var lastPresented: KlineValueRange? = null
    private var frozen: KlineValueRange? = null

    val isActive: Boolean get() = activeToken != null

    fun begin(viewport: KlineViewport): Long {
        val token = ++nextToken
        activeToken = token
        activeViewport = viewport
        frozen = lastPresented
        return token
    }

    fun end(token: Long) {
        if (activeToken != token) return
        activeToken = null
        activeViewport = null
        frozen = null
    }

    fun hasViewportChanged(viewport: KlineViewport): Boolean =
        activeToken != null && activeViewport != viewport

    fun resolve(
        target: KlineValueRange,
        viewport: KlineViewport,
        freezeRealtimeTick: Boolean,
    ): KlineValueRange {
        val viewportChanged = hasViewportChanged(viewport)
        val shouldFreeze = activeToken != null && !viewportChanged && freezeRealtimeTick
        val resolved = if (shouldFreeze) {
            frozen ?: lastPresented ?: target
        } else {
            target
        }
        if (activeToken != null && !shouldFreeze) {
            activeViewport = viewport
            frozen = target
        } else if (activeToken != null && frozen == null) {
            frozen = resolved
        }
        lastPresented = resolved
        return resolved
    }
}

private data class KlineMainBodyLayerKey(
    val state: KlineUiState,
    val layout: KlineLayout,
    val renderRange: KlineMainRenderRange,
    val frames: List<KlineIndicatorRenderFrame>,
    val chartType: KlineChartType,
    val mainChartRenderer: KlineMainChartRenderer?,
    val candleZIndex: Int,
    val style: KlineChartStyle,
    val renderConfig: KlineChartRenderConfig,
    val candleOverlayConfig: KlineCandleOverlayRenderConfig,
    val candleGradients: KlineCandleGradientConfiguration,
    val densityScale: Float,
    val hideMainIndicators: Boolean,
    val textMeasurer: TextMeasurer,
)

private data class KlineCachedIndicatorLayerKey(
    val state: KlineUiState,
    val layout: KlineLayout,
    val renderRange: KlineMainRenderRange,
    val frames: List<KlineIndicatorRenderFrame>,
    val style: KlineChartStyle,
    val grid: KlineGridRenderConfig,
    val precision: Int,
    val textMeasurer: TextMeasurer,
)

private class KlineIdentityRef(private val value: Any?) {
    override fun equals(other: Any?): Boolean = other is KlineIdentityRef && value === other.value
    override fun hashCode(): Int = System.identityHashCode(value)
}

private data class KlineIndicatorPatchInputKey(
    val definition: IndicatorDefinition,
    val renderer: KlineIdentityRef,
    val output: KlineIdentityRef,
    val colorIndex: Int,
)

/** Main-price layer dependencies excluding the pan-only right-edge offset. */
private data class KlineTranslatableMainBodyLayerKey(
    val series: KlineSeries,
    val indicatorInputs: List<KlineIndicatorPatchInputKey>,
    val layout: KlineLayout,
    val renderRange: KlineMainRenderRange,
    val candleWidthPx: Float,
    val candleSpacingPx: Float,
    val chartType: KlineChartType,
    val candleZIndex: Int,
    val style: KlineChartStyle,
    val renderConfig: KlineChartRenderConfig,
    val candleOverlayConfig: KlineCandleOverlayRenderConfig,
    val candleGradients: KlineCandleGradientConfiguration,
    val densityScale: Float,
    val hideMainIndicators: Boolean,
    val precision: Int,
    val textMeasurer: TextMeasurer,
)

/** Dependencies of the built-in historical body; index zero is never recorded here. */
private data class KlineHistoricalMainBodyLayerKey(
    val series: KlineSeries,
    val frames: List<KlineIndicatorRenderFrame>,
    val layout: KlineLayout,
    val renderRange: KlineMainRenderRange,
    val candleWidthPx: Float,
    val candleSpacingPx: Float,
    val chartType: KlineChartType,
    val candleZIndex: Int,
    val style: KlineChartStyle,
    val renderConfig: KlineChartRenderConfig,
    val candleOverlayConfig: KlineCandleOverlayRenderConfig,
    val candleGradients: KlineCandleGradientConfiguration,
    val densityScale: Float,
    val hideMainIndicators: Boolean,
    val precision: Int,
    val textMeasurer: TextMeasurer,
) {
    fun canReuseRecordedHistory(recorded: KlineHistoricalMainBodyLayerKey): Boolean =
        copy(series = recorded.series, frames = recorded.frames) == recorded &&
            (series === recorded.series || series.differsOnlyAtLatestFrom(recorded.series)) &&
            frames.shareHistoricalInputsWith(recorded.frames)
}

private fun List<KlineIndicatorRenderFrame>.shareHistoricalInputsWith(
    recorded: List<KlineIndicatorRenderFrame>,
): Boolean {
    if (size != recorded.size) return false
    return indices.all { index ->
        val currentFrame = this[index]
        val recordedFrame = recorded[index]
        val currentItem = currentFrame.item
        val recordedItem = recordedFrame.item
        currentItem.definition == recordedItem.definition &&
            currentItem.renderer === recordedItem.renderer &&
            currentItem.declarationOrder == recordedItem.declarationOrder &&
            currentItem.colorIndex == recordedItem.colorIndex &&
            currentFrame.context.copy(
                state = recordedFrame.context.state,
                output = recordedFrame.context.output,
            ) == recordedFrame.context &&
            when {
                currentItem.output === recordedItem.output -> true
                currentItem.output == null || recordedItem.output == null -> false
                else -> currentItem.output.differsOnlyAtLatestFrom(recordedItem.output)
            }
    }
}

private data class KlineMainGridLayerKey(
    val plotRect: Rect,
    val style: KlineChartStyle,
    val grid: KlineGridRenderConfig,
    val textMeasurer: TextMeasurer,
)

private data class KlineMainAxisLayerKey(
    val plotRect: Rect,
    val valueRange: KlineValueRange,
    val style: KlineChartStyle,
    val grid: KlineGridRenderConfig,
    val precision: Int,
    val textMeasurer: TextMeasurer,
)

private data class KlineTranslatableTimeAxisLayerKey(
    val series: KlineSeries,
    val layout: KlineLayout,
    val paintRange: IndexRange,
    val candleWidthPx: Float,
    val candleSpacingPx: Float,
    val style: KlineChartStyle,
    val interval: KlineInterval?,
    val config: KlineTimeAxisRenderConfig,
    val formatter: KlineIdentityRef,
    val textMeasurer: TextMeasurer,
) {
    fun canReuseTimeline(recorded: KlineTranslatableTimeAxisLayerKey): Boolean =
        copy(series = recorded.series) == recorded &&
            (series === recorded.series || series.differsOnlyAtLatestFrom(recorded.series))
}

private data class KlineOrderMarkerLayerKey(
    val placements: List<KlineOrderMarkerPlacement>,
    val orderMarkerConfig: KlineOrderMarkerRenderConfig,
    val style: KlineChartStyle,
    val densityScale: Float,
)

private data class KlineLatestPriceLayerKey(
    val latest: KlineCandle?,
    val interval: KlineInterval?,
    val countdownText: String?,
    val plotRect: Rect,
    val valueRange: KlineValueRange,
    val viewport: KlineViewport,
    val precision: Int,
    val style: KlineChartStyle,
    val config: KlineCandleOverlayRenderConfig,
    val textMeasurer: TextMeasurer,
)

private data class KlineCandleOverlayLayerKey(
    val content: Any?,
    val layout: KlineLayout,
    val renderRange: KlineMainRenderRange,
    val chartType: KlineChartType,
    val mainChartRenderer: KlineIdentityRef,
    val style: KlineChartStyle,
    val config: KlineCandleOverlayRenderConfig,
    val densityScale: Float,
    val precision: Int,
    val textMeasurer: TextMeasurer,
)

private data class KlineCachedSubIndicatorLayerKey(
    val state: KlineUiState,
    val layout: KlineLayout,
    val frames: Map<String, List<KlineIndicatorRenderFrame>>,
    val style: KlineChartStyle,
)

private data class KlineHistoricalSubIndicatorLayerKey(
    val series: KlineSeries,
    val layout: KlineLayout,
    val frames: Map<String, List<KlineIndicatorRenderFrame>>,
    val style: KlineChartStyle,
) {
    fun canReuseRecordedHistory(recorded: KlineHistoricalSubIndicatorLayerKey): Boolean =
        copy(series = recorded.series, frames = recorded.frames) == recorded &&
            (series === recorded.series || series.differsOnlyAtLatestFrom(recorded.series)) &&
            frames.shareHistoricalInputsWith(recorded.frames)
}

private fun Map<String, List<KlineIndicatorRenderFrame>>.shareHistoricalInputsWith(
    recorded: Map<String, List<KlineIndicatorRenderFrame>>,
): Boolean = keys == recorded.keys && all { (paneId, frames) ->
    frames.shareHistoricalInputsWith(recorded.getValue(paneId))
}

/** Plain draw-phase holder; it never participates in Compose snapshot observation. */
private class KlineRecordedLayerCache {
    private var key: Any? = null
    private var size: IntSize = IntSize.Zero

    fun needsRecord(key: Any, size: IntSize): Boolean = this.key != key || this.size != size

    fun recordedSizeFor(key: Any): IntSize? = size.takeIf { this.key == key }

    fun markRecorded(key: Any, size: IntSize) {
        this.key = key
        this.size = size
    }
}

private class KlineHistoricalSubRecordedLayerCache {
    private var key: KlineHistoricalSubIndicatorLayerKey? = null
    private var size: IntSize = IntSize.Zero

    fun needsRecord(key: KlineHistoricalSubIndicatorLayerKey, size: IntSize): Boolean {
        val recorded = this.key ?: return true
        return this.size != size || !key.canReuseRecordedHistory(recorded)
    }

    fun markRecorded(key: KlineHistoricalSubIndicatorLayerKey, size: IntSize) {
        this.key = key
        this.size = size
    }
}

private data class KlineCrossVerticalLayerKey(
    val top: Float,
    val bottom: Float,
    val stroke: KlineStrokeStyle,
    val color: Color,
)

private data class KlineCrossHorizontalLayerKey(
    val left: Float,
    val right: Float,
    val stroke: KlineStrokeStyle,
    val color: Color,
)

private data class KlineCrossPointLayerKey(
    val config: KlineCrosshairRenderConfig,
    val style: KlineChartStyle,
)

private data class KlineCrossPriceLayerKey(
    val text: String,
    val plotRect: Rect,
    val config: KlineCrosshairRenderConfig,
    val style: KlineChartStyle,
    val textMeasurer: TextMeasurer,
)

private data class KlineCrossSelectionLayerKey(
    val candleIndex: Int?,
    val candle: KlineCandle?,
    val previousCandle: KlineCandle?,
    val snappedX: Float,
    val stateSpec: KlineSpec?,
    val layout: KlineLayout,
    val config: KlineCrosshairRenderConfig,
    val style: KlineChartStyle,
    val tooltipPresentation: KlineIdentityRef,
    val formatter: KlineIdentityRef,
    val textMeasurer: TextMeasurer,
)

private data class KlineCrossTimeLayerKey(
    val text: String,
    val timeRect: Rect,
    val config: KlineCrosshairRenderConfig,
    val style: KlineChartStyle,
    val textMeasurer: TextMeasurer,
)

/** Tracks the exact viewport used to record a horizontally translatable GraphicsLayer. */
private class KlineTranslatableRecordedLayerCache {
    private var key: Any? = null
    private var size: IntSize = IntSize.Zero
    private var recordedRightEdgeOffsetPx: Float = 0f

    fun translationFor(
        key: Any,
        size: IntSize,
        currentRightEdgeOffsetPx: Float,
        maximumTranslationPx: Float,
    ): Float? {
        if (this.key != key || this.size != size) return null
        val translation = currentRightEdgeOffsetPx - recordedRightEdgeOffsetPx
        return translation.takeIf { abs(it) <= maximumTranslationPx }
    }

    fun markRecorded(key: Any, size: IntSize, rightEdgeOffsetPx: Float) {
        this.key = key
        this.size = size
        recordedRightEdgeOffsetPx = rightEdgeOffsetPx
    }
}

/** Latest-only market updates retain this cache even though their snapshot identity changes. */
private class KlineHistoricalTranslatableRecordedLayerCache {
    private var key: KlineHistoricalMainBodyLayerKey? = null
    private var size: IntSize = IntSize.Zero
    private var recordedRightEdgeOffsetPx: Float = 0f

    fun translationFor(
        key: KlineHistoricalMainBodyLayerKey,
        size: IntSize,
        currentRightEdgeOffsetPx: Float,
        maximumTranslationPx: Float,
    ): Float? {
        val recordedKey = this.key ?: return null
        if (this.size != size || !key.canReuseRecordedHistory(recordedKey)) return null
        val translation = currentRightEdgeOffsetPx - recordedRightEdgeOffsetPx
        return translation.takeIf { abs(it) <= maximumTranslationPx }
    }

    fun markRecorded(
        key: KlineHistoricalMainBodyLayerKey,
        size: IntSize,
        rightEdgeOffsetPx: Float,
    ) {
        this.key = key
        this.size = size
        recordedRightEdgeOffsetPx = rightEdgeOffsetPx
    }
}

private class KlineTimelineTranslatableRecordedLayerCache {
    private var key: KlineTranslatableTimeAxisLayerKey? = null
    private var size: IntSize = IntSize.Zero
    private var recordedRightEdgeOffsetPx: Float = 0f

    fun translationFor(
        key: KlineTranslatableTimeAxisLayerKey,
        size: IntSize,
        currentRightEdgeOffsetPx: Float,
        maximumTranslationPx: Float,
    ): Float? {
        val recordedKey = this.key ?: return null
        if (this.size != size || !key.canReuseTimeline(recordedKey)) return null
        val translation = currentRightEdgeOffsetPx - recordedRightEdgeOffsetPx
        return translation.takeIf { abs(it) <= maximumTranslationPx }
    }

    fun markRecorded(
        key: KlineTranslatableTimeAxisLayerKey,
        size: IntSize,
        rightEdgeOffsetPx: Float,
    ) {
        this.key = key
        this.size = size
        recordedRightEdgeOffsetPx = rightEdgeOffsetPx
    }
}

/** Small cache shared by Canvas and pointer routes; base and Tips-inset layouts occupy two entries. */
private class KlineMainRenderRangeCache(
    private val capacity: Int = 4,
) {
    private data class Entry(
        val series: KlineSeries,
        val panePlan: KlineIndicatorPanePlan,
        val paintRange: IndexRange,
        val hideMainIndicators: Boolean,
        val includeMainIndicators: Boolean,
        val rawRange: KlineRawValueRange?,
    )

    private val entries = ArrayList<Entry>(capacity)

    fun resolve(
        state: KlineUiState,
        layout: KlineLayout,
        panePlan: KlineIndicatorPanePlan,
        renderConfig: KlineChartRenderConfig,
        hideMainIndicators: Boolean,
        densityScale: Float,
    ): KlineMainRenderRange? {
        val paintRange = resolveKlineMainPaintRange(
            state = state,
            plotRect = layout.mainPane.plotRect,
            renderConfig = renderConfig,
            densityScale = densityScale,
        ) ?: return null
        val includeMainIndicators = !hideMainIndicators &&
            renderConfig.includeMainIndicatorsInValueRange
        if (entries.isNotEmpty() &&
            (entries.first().series !== state.series || entries.first().panePlan !== panePlan)
        ) {
            entries.clear()
        }
        val cachedEntry = entries.firstOrNull { entry ->
            entry.series === state.series &&
                entry.panePlan === panePlan &&
                entry.paintRange == paintRange &&
                entry.hideMainIndicators == hideMainIndicators &&
                entry.includeMainIndicators == includeMainIndicators
        }
        val rawRange = if (cachedEntry != null) {
            cachedEntry.rawRange
        } else {
            state.series.candles.rawValueRangeIncluding(
                candleRange = paintRange,
                mainOutputs = if (includeMainIndicators) panePlan.mainCombineOutputs else emptyList(),
                paintRange = paintRange,
                includeMainIndicators = includeMainIndicators,
            ).also { calculated ->
                if (entries.size == capacity) entries.removeAt(0)
                entries += Entry(
                    series = state.series,
                    panePlan = panePlan,
                    paintRange = paintRange,
                    hideMainIndicators = hideMainIndicators,
                    includeMainIndicators = includeMainIndicators,
                    rawRange = calculated,
                )
            }
        }
        // Renderer-owned ranges retain exact viewport semantics. Only the
        // potentially expensive immutable Candle/output scan is cached.
        val rendererRanges = if (includeMainIndicators) {
            panePlan.mainCombine.rendererValueRanges(
                state = state,
                paintRange = paintRange,
                viewport = state.viewport,
            )
        } else {
            emptyList()
        }
        return KlineMainRenderRange(
            paintRange = paintRange,
            valueRange = rawRange.normalizedIncluding(
                rendererRanges = rendererRanges,
                sameValueExpansionRatios = renderConfig.sameValueRangeExpansionRatios,
            ),
        )
    }
}

/** Reuses immutable indicator contexts while chart geometry and output inputs are unchanged. */
private class KlineIndicatorRenderFramesCache(
    private val capacity: Int = 2,
) {
    private val baseRangeCache = KlineIndicatorBaseRangeCache()
    private val preparedLineCache = KlinePreparedIndicatorLineCache()

    private data class Entry(
        val state: KlineUiState,
        val layout: KlineLayout,
        val panePlan: KlineIndicatorPanePlan,
        val paintRange: IndexRange,
        val mainValueRange: KlineValueRange,
        val style: KlineChartStyle,
        val densityScale: Float,
        val hideMainIndicators: Boolean,
        val sameValueExpansionRatios: List<Float>,
        val subPaneRangeSmoothers: KlinePaneValueRangeSmoothers?,
        val subPaneRangeResetKey: Any?,
        val subPaneRangeTransition: KlineValueRangeTransition?,
        val prepareComputedLines: Boolean,
        val includeSubPanes: Boolean,
        val value: KlineIndicatorRenderFrames,
    )

    private val entries = ArrayList<Entry>(capacity)

    fun resolve(
        state: KlineUiState,
        layout: KlineLayout,
        panePlan: KlineIndicatorPanePlan,
        paintRange: IndexRange,
        mainValueRange: KlineValueRange,
        style: KlineChartStyle,
        densityScale: Float,
        hideMainIndicators: Boolean,
        sameValueExpansionRatios: List<Float>,
        subPaneRangeSmoothers: KlinePaneValueRangeSmoothers?,
        subPaneRangeResetKey: Any?,
        subPaneRangeTransition: KlineValueRangeTransition?,
        prepareComputedLines: Boolean = true,
        includeSubPanes: Boolean = true,
    ): KlineIndicatorRenderFrames {
        if (entries.isNotEmpty() && entries.first().state !== state) entries.clear()
        entries.firstOrNull { entry ->
            entry.state === state &&
                entry.layout == layout &&
                entry.panePlan === panePlan &&
                entry.paintRange == paintRange &&
                entry.mainValueRange == mainValueRange &&
                entry.style == style &&
                entry.densityScale == densityScale &&
                entry.hideMainIndicators == hideMainIndicators &&
                entry.sameValueExpansionRatios == sameValueExpansionRatios &&
                entry.subPaneRangeSmoothers === subPaneRangeSmoothers &&
                entry.subPaneRangeResetKey == subPaneRangeResetKey &&
                entry.subPaneRangeTransition == subPaneRangeTransition &&
                entry.prepareComputedLines == prepareComputedLines &&
                entry.includeSubPanes == includeSubPanes
        }?.let { return it.value }
        val value = resolveKlineIndicatorRenderFrames(
            state = state,
            layout = layout,
            indicatorPanePlan = panePlan,
            paintRange = paintRange,
            mainValueRange = mainValueRange,
            style = style,
            densityScale = densityScale,
            hideMainIndicators = hideMainIndicators,
            sameValueExpansionRatios = sameValueExpansionRatios,
            subPaneRangeSmoothers = subPaneRangeSmoothers,
            subPaneRangeResetKey = subPaneRangeResetKey,
            subPaneRangeTransition = subPaneRangeTransition,
            prepareComputedLines = prepareComputedLines,
            includeSubPanes = includeSubPanes,
            baseRangeCache = baseRangeCache,
            preparedLineCache = preparedLineCache,
        )
        if (entries.size == capacity) entries.removeAt(0)
        entries += Entry(
            state = state,
            layout = layout,
            panePlan = panePlan,
            paintRange = paintRange,
            mainValueRange = mainValueRange,
            style = style,
            densityScale = densityScale,
            hideMainIndicators = hideMainIndicators,
            sameValueExpansionRatios = sameValueExpansionRatios,
            subPaneRangeSmoothers = subPaneRangeSmoothers,
            subPaneRangeResetKey = subPaneRangeResetKey,
            subPaneRangeTransition = subPaneRangeTransition,
            prepareComputedLines = prepareComputedLines,
            includeSubPanes = includeSubPanes,
            value = value,
        )
        return value
    }
}

private data class KlineCrossTooltipPresentationCacheKey(
    val candleIndex: Int?,
    val candle: KlineCandle?,
    val previousCandle: KlineCandle?,
    val snappedX: Float,
    val spec: KlineSpec?,
    val plotRect: Rect,
    val anchorRect: Rect,
    val config: KlineCrosshairRenderConfig,
    val style: KlineChartStyle,
    val densityScale: Float,
    val fontScale: Float,
    val timeLabelFormatter: KlineTimeLabelFormatter,
    val provider: KlineCrossTooltipProvider?,
    val stableContentWidthPx: Float?,
)

/** Retains the immutable tooltip layout shared by width tracking, drawing, and taps. */
private class KlineCrossTooltipPresentationCache {
    private var key: KlineCrossTooltipPresentationCacheKey? = null
    private var resolved: Boolean = false
    private var presentation: KlineCrossTooltipPresentation? = null

    fun matches(key: KlineCrossTooltipPresentationCacheKey): Boolean =
        resolved && this.key == key

    fun value(): KlineCrossTooltipPresentation? = presentation

    fun update(
        key: KlineCrossTooltipPresentationCacheKey,
        presentation: KlineCrossTooltipPresentation?,
    ) {
        this.key = key
        this.presentation = presentation
        resolved = true
    }
}

/** Immutable geometry handed from the content Canvas to the Cross-only layer. */
private data class KlineInteractionDrawGeometry(
    val state: KlineUiState,
    val layout: KlineLayout,
    val valueRange: KlineValueRange,
    val indicatorFrames: KlineIndicatorRenderFrames,
)

private data class KlineCrossPriceNodeContent(
    val text: String,
    val heightPx: Float,
)

private data class KlineCrossNodePosition(
    val snappedX: Float,
    val rawY: Float,
)

/**
 * Draw-thread handoff from the content Canvas to the independently invalidated
 * Cross nodes.
 *
 * Most Cross movement reads [value] directly and therefore does not recompose
 * the chart. A candle/viewport frame can change the value range without moving
 * the pointer, though, so [observedValue] exposes a narrow invalidation signal
 * for the derived Cross content that depends on the completed draw geometry.
 */
private class KlineInteractionDrawGeometryRef {
    var value: KlineInteractionDrawGeometry? = null

    private val version = mutableLongStateOf(0L)

    val observedValue: KlineInteractionDrawGeometry?
        get() {
            version.longValue
            return value
        }

    fun update(next: KlineInteractionDrawGeometry) {
        val previous = value
        value = next
        if (
            previous == null ||
            previous.state !== next.state ||
            previous.layout != next.layout ||
            previous.valueRange != next.valueRange ||
            previous.indicatorFrames !== next.indicatorFrames
        ) {
            version.longValue++
        }
    }

    fun clear() {
        if (value == null) return
        value = null
        version.longValue++
    }
}

/** Small per-chart LRU for the built-in computed Top Tips text measurements. */
private class KlineBuiltInTopTipsPlanCache(
    private val capacity: Int = 64,
) {
    private data class Entry(
        val frames: List<KlineIndicatorRenderFrame>,
        val chartLayout: KlineLayout,
        val selectedIndex: Int,
        val textMeasurer: TextMeasurer,
        val stackMainTips: Boolean,
        val plan: KlineIndicatorTopTipsRenderPlan,
    )

    private val entries = ArrayList<Entry>(capacity)

    fun resolve(
        frames: List<KlineIndicatorRenderFrame>,
        chartLayout: KlineLayout,
        selection: KlineIndicatorTopTipsSelection,
        textMeasurer: TextMeasurer,
        stackMainTips: Boolean,
    ): KlineIndicatorTopTipsRenderPlan {
        // This renderer's prepared output depends on the selected column index
        // and immutable frame inputs only. The public cache policy deliberately
        // does not imply the same contract for third-party Top Tips hooks.
        if (frames.any { frame ->
                val renderer = frame.item.renderer
                renderer is KlineIndicatorTopTipsRenderer &&
                    (renderer as? KlineImmutableTopTipsRenderer)?.hasImmutableTopTips != true
            }
        ) {
            return resolveKlineIndicatorTopTipsRenderPlan(
                frames = frames,
                chartLayout = chartLayout,
                selection = selection,
                textMeasurer = textMeasurer,
                stackMainTips = stackMainTips,
            )
        }
        val selectedIndex = when (selection) {
            is KlineIndicatorTopTipsSelection.Latest -> 0
            is KlineIndicatorTopTipsSelection.Cross -> selection.crosshair.candleIndex ?: -1
        }
        val hit = entries.indexOfLast { entry ->
            entry.frames === frames &&
                entry.chartLayout == chartLayout &&
                entry.selectedIndex == selectedIndex &&
                entry.textMeasurer === textMeasurer &&
                entry.stackMainTips == stackMainTips
        }
        if (hit >= 0) {
            val entry = entries.removeAt(hit)
            entries += entry
            return entry.plan
        }
        val plan = resolveKlineIndicatorTopTipsRenderPlan(
            frames = frames,
            chartLayout = chartLayout,
            selection = selection,
            textMeasurer = textMeasurer,
            stackMainTips = stackMainTips,
        )
        if (plan.entries.isEmpty()) return plan
        if (entries.size == capacity) entries.removeAt(0)
        entries += Entry(
            frames = frames,
            chartLayout = chartLayout,
            selectedIndex = selectedIndex,
            textMeasurer = textMeasurer,
            stackMainTips = stackMainTips,
            plan = plan,
        )
        return plan
    }
}

private const val KlineHistoricalSubPaneRangeTransitionDurationMillis = 160

/** Current owner of one Compose pointer session; later owners reserve higher-priority branches. */

private enum class PointerGestureOwner {
    Pending,
    DrawingEdit,
    DrawingMove,
    CrossDrag,
    Pan,
    Pinch,
    PaneResize,
    VerticalZoom,
}

private fun String.toComposeEasing(): Easing = when (this) {
    "linear" -> LinearEasing
    "easeIn" -> CubicBezierEasing(0.42f, 0f, 1f, 1f)
    "easeOut" -> CubicBezierEasing(0f, 0f, 0.58f, 1f)
    "easeInOut" -> CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
    "easeInCubic" -> CubicBezierEasing(0.55f, 0.055f, 0.675f, 0.19f)
    "easeInOutCubic" -> CubicBezierEasing(0.645f, 0.045f, 0.355f, 1f)
    else -> CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)
}

internal fun resolveChartLayout(
    canvasSize: Size,
    axisWidthPx: Float,
    timeAxisHeightPx: Float,
    densityScale: Float,
    paneConfig: KlinePaneRenderConfig,
    subPaneSpecs: List<KlineIndicatorSubPaneSpec>,
): KlineLayout {
    val subSpecs = subPaneSpecs.map { pane ->
        val explicit = paneConfig.explicitPaneFor(pane.id)
        val fallback = paneConfig.paneFor(pane.id)
        val hints = pane.layoutHints
        // A shared non-default pane is an Android extension. A null member
        // hint means *that member* inherits the host fallback, rather than
        // contributing zero to the shared geometry. Resolve every member
        // against the fallback first, then take the smallest pane that can
        // contain all of them. An explicit named pane remains a full host
        // override for the extension.
        val logicalPadding = explicit?.padding ?: hints
            .map { hint -> hint.padding?.toPanePadding() ?: fallback.padding }
            .let { paddings ->
                KlinePanePadding(
                    leftPx = paddings.maxOfOrNull(KlinePanePadding::leftPx) ?: fallback.padding.leftPx,
                    topPx = paddings.maxOfOrNull(KlinePanePadding::topPx) ?: fallback.padding.topPx,
                    rightPx = paddings.maxOfOrNull(KlinePanePadding::rightPx) ?: fallback.padding.rightPx,
                    bottomPx = paddings.maxOfOrNull(KlinePanePadding::bottomPx) ?: fallback.padding.bottomPx,
                )
            }
        val logicalMinHeight = when (paneConfig.mode) {
            // Adapt layout uses requested indicator heights without applying
            // fixed-layout minimums.
            KlineLayoutMode.Adapt -> 0f
            KlineLayoutMode.Fixed -> explicit?.minHeight?.value
                ?: hints.maxOfOrNull { hint ->
                    maxOf(fallback.minHeight.value, hint.minHeight ?: 0f)
                }
                ?: fallback.minHeight.value
        }
        KlinePaneSpec(
            id = pane.id,
            // A host's explicit named pane remains the Android escape hatch.
            // Otherwise an indicator's own geometry wins over the default
            // pane fallback.
            preferredHeightPx = (explicit?.preferredHeight?.value
                ?: hints.maxOfOrNull { hint -> hint.resolvedHeight ?: fallback.preferredHeight.value }
                ?: fallback.preferredHeight.value) * densityScale,
            minHeightPx = logicalMinHeight * densityScale,
            padding = logicalPadding.toCanvasPixels(densityScale),
        )
    }
    val mainMinHeight = paneConfig.mainMinHeight.value * densityScale
    val mainPreferred = if (paneConfig.mode == KlineLayoutMode.Fixed) {
        (canvasSize.height - timeAxisHeightPx - subSpecs.sumOf { it.normalizedPreferredHeightPx.toDouble() }.toFloat())
            .coerceAtLeast(mainMinHeight)
    } else {
        paneConfig.mainPreferredHeight.value * densityScale
    }
    return KlineLayoutEngine.resolve(
        KlineLayoutSpec(
            availableSize = canvasSize,
            axisWidthPx = axisWidthPx,
            mainPane = KlinePaneSpec(
                id = "main",
                preferredHeightPx = mainPreferred,
                minHeightPx = mainMinHeight,
                padding = paneConfig.mainPadding.toCanvasPixels(densityScale),
            ),
            subPanes = subSpecs,
            timePane = KlinePaneSpec(
                id = "time",
                preferredHeightPx = timeAxisHeightPx,
                padding = paneConfig.timePadding.toCanvasPixels(densityScale),
            ),
            timePanePosition = paneConfig.timePosition,
            mode = paneConfig.mode,
        ),
    )
}
/** The visible main-pane sample range and the exact value range used for chart painting. */

internal data class KlineMainRenderRange(
    val paintRange: IndexRange,
    val valueRange: KlineValueRange,
)

internal data class KlinePanPaintBucket(
    val paintRange: IndexRange,
    val horizontalOverscanPx: Float,
)

internal data class KlineRealtimeLayerRanges(
    val historical: IndexRange,
    val patch: IndexRange,
)

/**
 * Index zero belongs exclusively to the live patch. Line-like painters retain
 * index one in that patch as the minimum continuity point for the newest
 * segment; the historical layer starts at one and remains immutable.
 */
internal fun resolveKlineRealtimeLayerRanges(
    paintRange: IndexRange,
    candleCount: Int,
    lineLike: Boolean,
): KlineRealtimeLayerRanges {
    if (paintRange.isEmpty || candleCount <= 0) {
        return KlineRealtimeLayerRanges(IndexRange.Empty, IndexRange.Empty)
    }
    val historicalStart = max(1, paintRange.startInclusive)
    val historical = if (historicalStart < paintRange.endExclusive) {
        IndexRange(historicalStart, paintRange.endExclusive)
    } else {
        IndexRange.Empty
    }
    val includesLatest = paintRange.startInclusive == 0 && paintRange.endExclusive > 0
    val patchEnd = if (includesLatest) {
        min(candleCount, if (lineLike) 2 else 1)
    } else {
        0
    }
    return KlineRealtimeLayerRanges(
        historical = historical,
        patch = if (patchEnd > 0) IndexRange(0, patchEnd) else IndexRange.Empty,
    )
}

/** Keeps a wider geometry range stable for roughly half a screen of panning. */
internal fun resolveKlinePanPaintBucket(
    currentPaintRange: IndexRange,
    candleCount: Int,
    plotWidthPx: Float,
    candleStepPx: Float,
): KlinePanPaintBucket {
    if (currentPaintRange.isEmpty || candleCount <= 0 || candleStepPx <= 0f) {
        return KlinePanPaintBucket(currentPaintRange, 0f)
    }
    val halfScreenCandles = max(
        8,
        kotlin.math.ceil(plotWidthPx / candleStepPx / 2f).toInt(),
    )
    val bucketStart = (currentPaintRange.startInclusive / halfScreenCandles) * halfScreenCandles
    // Derive the bucket's center capacity from fixed viewport geometry. The
    // exact visible range can alternate between N and N+1 candles while a
    // fractional pan crosses candle boundaries; using its current length
    // would invalidate an otherwise identical bucket almost every frame.
    val stablePaintCapacity = kotlin.math.ceil(plotWidthPx / candleStepPx)
        .toInt() + 3
    val start = (bucketStart - halfScreenCandles).coerceAtLeast(0)
    val end = (
        bucketStart +
            halfScreenCandles +
            stablePaintCapacity +
            halfScreenCandles
        ).coerceAtMost(candleCount)
    return KlinePanPaintBucket(
        paintRange = IndexRange(start, end),
        horizontalOverscanPx = (halfScreenCandles + 2) * candleStepPx,
    )
}

private fun resolveKlineMainPaintRange(
    state: KlineUiState,
    plotRect: Rect,
    renderConfig: KlineChartRenderConfig,
    densityScale: Float,
): IndexRange? {
    if (state.series.isEmpty || plotRect.width <= 0f || plotRect.height <= 0f) return null
    val constraints = state.viewportConstraints
        ?: renderConfig.viewport.constraints(plotRect.width, densityScale)
    val visible = KlineViewportMath.visibleRange(state.series.size, state.viewport, constraints)
    return IndexRange(
        (visible.startInclusive - 1).coerceAtLeast(0),
        (visible.endExclusive + 1).coerceAtMost(state.series.size),
    )
}
/**
 * Resolves the shared Candle/Main-COMBINE scale once for Canvas, off-view hit
 * testing, and host Cross callbacks. Keeping these paths on one helper
 * prevents renderer-owned Direct/External ranges from changing pixels while
 * the callback still reports an OHLC-only value.
 */

internal fun resolveKlineMainRenderRange(
    state: KlineUiState,
    plotRect: Rect,
    renderConfig: KlineChartRenderConfig,
    indicatorPanePlan: KlineIndicatorPanePlan,
    hideMainIndicators: Boolean,
    densityScale: Float,
): KlineMainRenderRange? {
    val paintRange = resolveKlineMainPaintRange(
        state = state,
        plotRect = plotRect,
        renderConfig = renderConfig,
        densityScale = densityScale,
    ) ?: return null
    val mainOutputsForRange = if (hideMainIndicators) {
        emptyList()
    } else {
        indicatorPanePlan.mainCombineOutputs
    }
    val mainRendererRanges = if (hideMainIndicators) {
        emptyList()
    } else {
        indicatorPanePlan.mainCombine.rendererValueRanges(
            state = state,
            paintRange = paintRange,
            viewport = state.viewport,
        )
    }
    val valueRange = state.series.candles.rawValueRangeIncluding(
        candleRange = paintRange,
        mainOutputs = mainOutputsForRange,
        paintRange = paintRange,
        includeMainIndicators = renderConfig.includeMainIndicatorsInValueRange,
    ).normalizedIncluding(
        rendererRanges = if (renderConfig.includeMainIndicatorsInValueRange) {
            mainRendererRanges
        } else {
            emptyList()
        },
        sameValueExpansionRatios = renderConfig.sameValueRangeExpansionRatios,
    )
    return KlineMainRenderRange(paintRange = paintRange, valueRange = valueRange)
}

internal fun com.zhumeng.kanvas.core.IndicatorInsets.toPanePadding(): KlinePanePadding =
    KlinePanePadding(leftPx = left, topPx = top, rightPx = right, bottomPx = bottom)
/**
 * First Canvas implementation of the Kanvas render stack.
 *
 * It deliberately renders from immutable [KlineUiState], publishes viewport
 * changes to the host, and has no data-source dependency. The draw order is
 * background → grid → chart → latest-price overlay → crosshair.
 */
@Composable

fun KanvasChart(
    state: KlineUiState,
    onViewportChange: (KlineViewport) -> Unit,
    modifier: Modifier = Modifier,
    onViewportConstraintsChange: (KlineViewportConstraints) -> Unit = {},
    /** Receives physical Canvas layout after size/config/active-pane changes. */
    onLayoutChange: (KlineLayout) -> Unit = {},
    /** `true` requests a visible boundary loader; `false` is silent prefetch. */
    onLoadMoreRequested: (showLoading: Boolean) -> Unit = { _ -> },
    /**
     * Optional direct bridge to [com.zhumeng.kanvas.core.KlineController.moveToInitialPosition].
     * When omitted, the chart publishes the same viewport offset through
     * [onViewportChange].
     */
    onMoveToInitialPosition: (() -> Unit)? = null,
    onCrosshairChange: (KlineCrosshair?) -> Unit = {},
    chartType: KlineChartType = KlineChartType.Bar(),
    /** Optional extensible price renderer; non-null replaces the built-in Bar/Line painter. */
    mainChartRenderer: KlineMainChartRenderer? = null,
    /** Main-pane z-index for the candle chart body. */
    candleZIndex: Int = -1,
    style: KlineChartStyle = KlineChartStyle(),
    renderConfig: KlineChartRenderConfig = KlineChartRenderConfig(),
    /** Orders anchored to candle timestamps and rendered as compact B/S markers. */
    orderMarkers: List<KlineOrderMarker> = emptyList(),
    orderMarkerConfig: KlineOrderMarkerRenderConfig = KlineOrderMarkerRenderConfig(),
    indicatorSnapshot: IndicatorRuntimeSnapshot? = null,
    /**
     * Optional active/retained lifecycle selection from [IndicatorRegistrySnapshot].
     * When supplied, [indicatorSnapshot] must be calculated through
     * `IndicatorRuntime.calculate(series = ..., registry = ...)`; a stale
     * generation intentionally renders no indicator output.
     */
    indicatorRegistrySnapshot: IndicatorRegistrySnapshot? = null,
    /** Kotlin renderer implementations for active Direct/Computed/External indicators. */
    indicatorRendererRegistry: KlineIndicatorRendererRegistry = KlineIndicatorRendererRegistry.Default,
    /**
     * Optional per-key stateful renderer owner. Create it with
     * [rememberKlineIndicatorRendererLifecycleHost] and supply an
     * [IndicatorRegistrySnapshot] so its lifecycle can reconcile correctly.
     */
    indicatorRendererLifecycleHost: KlineIndicatorRendererLifecycleHost? = null,
    /** Active declarations with no matching Android Canvas renderer. */
    onUnsupportedIndicators: (List<IndicatorDefinition>) -> Unit = {},
    paneConfig: KlinePaneRenderConfig = KlinePaneRenderConfig(),
    /**
     * Optional native configuration for the built-in candle and time
     * indicators. It overrides only the Canvas fields represented by
     * [KlineBuiltInIndicatorConfiguration].
     */
    builtInIndicators: KlineBuiltInIndicatorConfiguration? = null,
    timeAxisConfig: KlineTimeAxisRenderConfig = KlineTimeAxisRenderConfig(),
    /** Runtime-only formatter; Kanvas deliberately excludes `tickFormatter` from JSON. */
    timeLabelFormatter: KlineTimeLabelFormatter? = null,
    axisWidth: Dp = 64.dp,
    timeAxisHeight: Dp = 15.dp,
    /**
     * Optional Kotlin-native Cross Tooltip content. `null` uses the built-in
     * Time/O/H/L/C/Chg/%Chg/Range/Amount/Turnover provider; an empty result
     * intentionally hides the card. Item callbacks consume their row tap
     * before the latest-price marker, indicator hooks, or Cross toggle.
     */
    crossTooltipProvider: KlineCrossTooltipProvider? = null,
    /** Kanvas `mainBackgroundView`, constrained and positioned to the main pane. */
    mainBackgroundContent: (@Composable (KlineChartSlotContext) -> Unit)? = null,
    /** Kanvas `mainForegroundViewBuilder`; non-null replaces the default loading spinner. */
    mainForegroundContent: (@Composable (KlineChartSlotContext) -> Unit)? = null,
    /** Optional persistent drawing state. Drawing/edit owns pointer input before Cross and chart gestures. */
    drawingController: DrawingController? = null,
    drawingConfig: DrawingRenderConfig = DrawingRenderConfig(),
    drawingMagnifierConfig: DrawingMagnifierConfig = DrawingMagnifierConfig(),
    /** Native pointer-transparent watermark rendered inside the chart Canvas. */
    watermarkConfig: KanvasWatermarkConfig? = null,
    /** Physical Canvas hit target used when `gesture.isManualSetZoomRect=true`. */
    verticalZoomHitRect: Rect? = null,
    /** Called only after a confirmed double tap; single-tap routes wait for the double-tap timeout. */
    onDoubleTap: (() -> Unit)? = null,
    /**
     * Touch-accessible exit affordance shown while vertical zoom is active.
     * `null` uses the built-in circular close button.
     */
    verticalZoomExitContent: (@Composable (onExit: () -> Unit) -> Unit)? = null,
) {
    // A chart frame commonly contains more than the default eight distinct
    // axis, time, Tips, and Cross strings. Retain enough layouts to avoid
    // evicting and remeasuring the same labels on every drag frame.
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)
    val mainBodyLayer = rememberGraphicsLayer()
    val mainBodyLayerCache = remember { KlineTranslatableRecordedLayerCache() }
    val historicalMainBodyLayer = rememberGraphicsLayer()
    val historicalMainBodyLayerCache = remember { KlineHistoricalTranslatableRecordedLayerCache() }
    val realtimeMainPatchLayer = rememberGraphicsLayer()
    val realtimeMainPatchLayerCache = remember { KlineTranslatableRecordedLayerCache() }
    val cacheableIndicatorBelowLayer = rememberGraphicsLayer()
    val cacheableIndicatorBelowLayerCache = remember { KlineRecordedLayerCache() }
    val cacheableIndicatorAboveLayer = rememberGraphicsLayer()
    val cacheableIndicatorAboveLayerCache = remember { KlineRecordedLayerCache() }
    val crossVerticalLayer = rememberGraphicsLayer()
    val crossVerticalLayerCache = remember { KlineRecordedLayerCache() }
    val crossHorizontalLayer = rememberGraphicsLayer()
    val crossHorizontalLayerCache = remember { KlineRecordedLayerCache() }
    val crossPointLayer = rememberGraphicsLayer()
    val crossPointLayerCache = remember { KlineRecordedLayerCache() }
    val crossPriceLayer = rememberGraphicsLayer()
    val crossPriceLayerCache = remember { KlineRecordedLayerCache() }
    val crossSelectionLayer = rememberGraphicsLayer()
    val crossSelectionLayerCache = remember { KlineRecordedLayerCache() }
    val crossTimeLayer = rememberGraphicsLayer()
    val crossTimeLayerCache = remember { KlineRecordedLayerCache() }
    val gestureValueRangeRef = remember { KlineGestureValueRangeRef() }
    var gestureValueRangeEpoch by remember { mutableLongStateOf(0L) }
    val mainGridLayer = rememberGraphicsLayer()
    val mainGridLayerCache = remember { KlineRecordedLayerCache() }
    val mainAxisLayer = rememberGraphicsLayer()
    val mainAxisLayerCache = remember { KlineRecordedLayerCache() }
    val candleOverlayLayer = rememberGraphicsLayer()
    val candleOverlayLayerCache = remember { KlineRecordedLayerCache() }
    val builtInMainOverlayLayer = rememberGraphicsLayer()
    val builtInMainOverlayLayerCache = remember { KlineRecordedLayerCache() }
    val latestPriceLayer = rememberGraphicsLayer()
    val latestPriceLayerCache = remember { KlineRecordedLayerCache() }
    val cachedSubIndicatorLayer = rememberGraphicsLayer()
    val cachedSubIndicatorLayerCache = remember { KlineHistoricalSubRecordedLayerCache() }
    val realtimeSubIndicatorPatchLayer = rememberGraphicsLayer()
    val realtimeSubIndicatorPatchLayerCache = remember { KlineRecordedLayerCache() }
    val cacheableCustomSubIndicatorLayer = rememberGraphicsLayer()
    val cacheableCustomSubIndicatorLayerCache = remember { KlineRecordedLayerCache() }
    val timeAxisLayer = rememberGraphicsLayer()
    val timeAxisLayerCache = remember { KlineTimelineTranslatableRecordedLayerCache() }
    val orderMarkerIndex = remember(orderMarkers) { KlineOrderMarkerIndex(orderMarkers) }
    val defaultTimeLabelFormatter = remember { DefaultKlineTimeLabelFormatter(Locale.getDefault()) }
    val drawingTimeLabelFormatter = remember { SimpleDateFormat("M/d HH:mm", Locale.getDefault()) }
    val defaultTimeLabelFormatterFunction: KlineTimeLabelFormatter = remember(defaultTimeLabelFormatter) {
        { candle, interval -> defaultTimeLabelFormatter.format(candle, interval) }
    }
    val appliedTimeLabelFormatter: KlineTimeLabelFormatter = timeLabelFormatter
        ?: defaultTimeLabelFormatterFunction
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // This state is deliberately read only by pointer callbacks and the
    // Canvas draw phase. Pointer movement then invalidates drawing without
    // recomposing this large chart function on every pixel.
    val crosshairState = remember { mutableStateOf<Offset?>(null) }
    val crosshairSessionRef = remember { CrosshairSessionRef() }
    var isChartZooming by remember { mutableStateOf(false) }
    var verticalZoomInsetPx by remember { mutableFloatStateOf(0f) }
    var verticalZoomShiftPx by remember { mutableFloatStateOf(0f) }
    var hoverInVerticalZoomTarget by remember { mutableStateOf(false) }
    var paneHeightOverridesPx by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var activeResizeBoundaryY by remember { mutableStateOf<Float?>(null) }
    var panRangeSmoothFactor by remember { mutableFloatStateOf(1f) }
    val mainRangeSmoother = remember { KlineValueRangeSmoother() }
    val rangeUpdateRef = remember { KlineRangeUpdateRef() }
    val previousRangeSeries = rangeUpdateRef.series
    val seriesChanged = previousRangeSeries !== state.series
    val viewportChanged = rangeUpdateRef.viewport != state.viewport
    // Use KlineSeries' storage-identity proof instead of comparing size and
    // timestamp. A same-sized rebuilt snapshot is structural data and must not
    // be frozen like an in-place latest-candle replacement.
    val latestCandleOnlyUpdate =
        seriesChanged && state.series.differsOnlyAtLatestFrom(previousRangeSeries)
    val realtimeRangeMode = resolveKlineRealtimeRangeMode(
        viewportChanged = viewportChanged,
        latestCandleOnlyUpdate = latestCandleOnlyUpdate,
        seriesChanged = seriesChanged,
        previousMode = rangeUpdateRef.realtimeMode,
    )
    val animateHistoricalRange = shouldAnimateHistoricalRangeTransition(
        previousSeries = previousRangeSeries,
        series = state.series,
        previousLoadingState = rangeUpdateRef.loadingState,
        loadingState = state.loadingState,
    )
    // The old candles keep their horizontal indexes when an older page is
    // appended, but the newly filled blank area may change a sub pane's
    // automatic range (most visibly Volume). Start the transition in the same
    // composition as the new page so there is no one-frame jump first.
    val historicalRangeTransitionKey = remember(state.series) {
        state.revision.takeIf { animateHistoricalRange }
    }
    val historicalRangeProgress = remember(state.series) {
        historicalRangeTransitionKey?.let { Animatable(0f) }
    }
    LaunchedEffect(historicalRangeProgress) {
        historicalRangeProgress?.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = KlineHistoricalSubPaneRangeTransitionDurationMillis,
                easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
            ),
        )
    }
    val historicalRangeProgressValue = historicalRangeProgress?.value ?: 1f
    val historicalRangeTransition = if (
        historicalRangeTransitionKey != null && historicalRangeProgressValue < 1f
    ) {
        KlineValueRangeTransition(
            key = historicalRangeTransitionKey,
            progress = historicalRangeProgressValue,
        )
    } else {
        null
    }
    SideEffect {
        rangeUpdateRef.series = state.series
        rangeUpdateRef.viewport = state.viewport
        rangeUpdateRef.loadingState = state.loadingState
        rangeUpdateRef.realtimeMode = realtimeRangeMode
    }
    val focusRequester = remember { FocusRequester() }
    val composeDensity = androidx.compose.ui.platform.LocalDensity.current
    // Tooltip width is stored in physical pixels. Reset its Cross-session
    // cache whenever density or accessibility font scale changes so an old
    // pixel width cannot distort freshly measured text.
    val crossTooltipStableContentWidthRef = remember(composeDensity.density, composeDensity.fontScale) {
        CrossTooltipStableWidthRef()
    }
    val axisWidthPx = with(composeDensity) { axisWidth.toPx() }
    val densityScale = composeDensity.density
    val appliedChartType = builtInIndicators?.candle?.resolveChartType(
        interval = state.spec?.interval,
        candleWidthPx = state.viewport.candleWidthPx,
        minCandleWidthPx = renderConfig.viewport.resolvedMinCandleWidthPx(densityScale),
    ) ?: chartType
    val isLineLikeMainChart = mainChartRenderer?.isLineLike ?: (appliedChartType is KlineChartType.Line)
    val appliedCandleZIndex = builtInIndicators?.candle?.zIndex ?: candleZIndex
    val appliedStyle = style.withCandleColorOverrides(
        builtInIndicators?.candle?.colorOverrides ?: KlineCandleColorOverrides(),
    )
    val appliedCandleOverlayConfig =
        builtInIndicators?.candle?.overlay ?: renderConfig.candleOverlay
    val appliedCandleGradients = remember(builtInIndicators?.candle?.gradients) {
        builtInIndicators?.candle?.gradients ?: KlineCandleGradientConfiguration()
    }
    val basePaneConfig = builtInIndicators?.time?.let { time ->
        paneConfig.copy(
            timePosition = time.position,
            timePadding = time.padding,
        )
    } ?: paneConfig
    val appliedTimeAxisHeight = builtInIndicators?.time?.height ?: timeAxisHeight
    val appliedTimeAxisConfig = builtInIndicators?.time?.let { time ->
        timeAxisConfig.copy(
            text = time.text,
            clipToDrawableRect = time.clipToDrawableRect,
        )
    } ?: timeAxisConfig
    val timeAxisHeightPx = with(composeDensity) { appliedTimeAxisHeight.toPx() }
    val hideMainIndicatorsInLineChartMode =
        builtInIndicators?.candle?.hideMainIndicatorsInLineChartMode ?: false
    val activeRendererResolver: KlineIndicatorRendererResolver =
        if (indicatorRegistrySnapshot != null && indicatorRendererLifecycleHost != null) {
            indicatorRendererLifecycleHost
        } else {
            indicatorRendererRegistry
        }
    // Lifecycle operations run after successful composition, never while the
    // Canvas is drawing. The host returns a pending no-op resolver for one
    // frame so a factory-owned Computed pane still has geometry before init.
    SideEffect {
        indicatorRendererLifecycleHost?.let { host ->
            host.reconcile(state, indicatorRegistrySnapshot)
            host.dispatchPendingResolutionErrors()
        }
    }
    val lifecycleEpoch = indicatorRendererLifecycleHost.observeInvalidationEpoch()
    // Horizontal viewport changes do not affect indicator selection or pane
    // ownership. Retain the immutable plan across drag frames and rebuild it
    // only when its actual data/lifecycle inputs change.
    val indicatorPanePlan = remember(
        indicatorSnapshot,
        indicatorRegistrySnapshot,
        activeRendererResolver,
        lifecycleEpoch,
        state.series,
        state.revision,
        state.spec,
    ) {
        indicatorSnapshot.resolveIndicatorPanePlan(
            state = state,
            registry = indicatorRegistrySnapshot,
            renderers = activeRendererResolver,
        )
    }
    val currentIndicatorPanePlan = rememberUpdatedState(indicatorPanePlan)
    val indicatorSubPaneSpecs = remember(indicatorPanePlan) { indicatorPanePlan.subPaneSpecs }
    val subPaneRangeSmoothers = remember(state.spec?.key) { KlinePaneValueRangeSmoothers() }
    SideEffect {
        subPaneRangeSmoothers.retain(indicatorSubPaneSpecs.map(KlineIndicatorSubPaneSpec::id))
    }
    val appliedPaneConfig = remember(
        basePaneConfig,
        paneHeightOverridesPx,
        verticalZoomInsetPx,
        verticalZoomShiftPx,
        densityScale,
    ) {
        basePaneConfig.copy(
            mainPreferredHeight = paneHeightOverridesPx["main"]
                ?.let { (it / densityScale).dp }
                ?: basePaneConfig.mainPreferredHeight,
            mainPadding = basePaneConfig.mainPadding.copy(
                topPx = basePaneConfig.mainPadding.topPx +
                    (verticalZoomInsetPx + verticalZoomShiftPx) / densityScale,
                bottomPx = basePaneConfig.mainPadding.bottomPx +
                    (verticalZoomInsetPx - verticalZoomShiftPx) / densityScale,
            ),
            subPanes = buildList {
                addAll(basePaneConfig.subPanes.filterNot { it.id in paneHeightOverridesPx })
                paneHeightOverridesPx
                    .filterKeys { it != "main" }
                    .forEach { (id, heightPx) ->
                        val fallback = basePaneConfig.paneFor(id)
                        add(
                            fallback.copy(
                                preferredHeight = (heightPx / densityScale).dp,
                            ),
                        )
                    }
            },
        )
    }
    // Pointer input must not restart after every StateFlow viewport update. It
    // reads these remembered holders during the gesture instead.
    val currentState = rememberUpdatedState(state)
    val liveChartState = LocalKanvasLiveChartState.current
    fun currentChartState(): KlineUiState = liveChartState?.value ?: currentState.value
    val currentRenderConfig = rememberUpdatedState(renderConfig)
    val currentOnUnsupportedIndicators = rememberUpdatedState(onUnsupportedIndicators)
    val currentOnLayoutChange = rememberUpdatedState(onLayoutChange)
    val currentPaneConfig = rememberUpdatedState(appliedPaneConfig)
    val currentIsLineLikeMainChart = rememberUpdatedState(isLineLikeMainChart)
    val currentStyle = rememberUpdatedState(appliedStyle)
    val currentCandleOverlayConfig = rememberUpdatedState(appliedCandleOverlayConfig)
    val currentHideMainIndicatorsInLineChartMode = rememberUpdatedState(hideMainIndicatorsInLineChartMode)
    val currentAxisWidthPx = rememberUpdatedState(axisWidthPx)
    val currentTimeAxisHeightPx = rememberUpdatedState(timeAxisHeightPx)
    val currentDensityScale = rememberUpdatedState(densityScale)
    val currentOnViewportChange = rememberUpdatedState(onViewportChange)
    val currentOnLoadMoreRequested = rememberUpdatedState(onLoadMoreRequested)
    val currentOnMoveToInitialPosition = rememberUpdatedState(onMoveToInitialPosition)
    val currentOnCrosshairChange = rememberUpdatedState(onCrosshairChange)
    val currentCrossTooltipProvider = rememberUpdatedState(crossTooltipProvider)
    val currentTimeLabelFormatter = rememberUpdatedState(appliedTimeLabelFormatter)
    val currentDrawingController = rememberUpdatedState(drawingController)
    val currentDrawingConfig = rememberUpdatedState(drawingConfig)
    val currentVerticalZoomHitRect = rememberUpdatedState(verticalZoomHitRect)
    val currentOnDoubleTap = rememberUpdatedState(onDoubleTap)
    var countdownNowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val shouldTickCountdown =
        renderConfig.autoStartLastPriceCountdownTimer &&
        appliedCandleOverlayConfig.countdown.show &&
            (state.spec?.interval?.approximateDurationMillis ?: 0L) > 1_000L
    LaunchedEffect(shouldTickCountdown, state.series.latest?.timestampMillis, state.spec?.interval) {
        if (!shouldTickCountdown) return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            countdownNowMillis = now
            if (resolveKlineCountdownText(state.series.latest, state.spec?.interval, now) == null) break
            delay(1_000L - now.mod(1_000L))
        }
    }
    LaunchedEffect(indicatorPanePlan.unsupportedDefinitions) {
        currentOnUnsupportedIndicators.value(indicatorPanePlan.unsupportedDefinitions)
    }
    LaunchedEffect(state.spec?.symbol, drawingController) {
        val symbol = state.spec?.symbol ?: return@LaunchedEffect
        drawingController?.switchSymbol(symbol)
    }
    // `Adapt` must be negotiated with the host's parent measurement. The
    // Canvas cannot resize its own Compose parent, so publish the exact
    // physical requested height whenever layout-affecting inputs change.
    val reportedLayout = remember(
        canvasSize,
        axisWidthPx,
        timeAxisHeightPx,
        densityScale,
        appliedPaneConfig,
        indicatorSubPaneSpecs,
    ) {
        canvasSize.takeIf { it.width > 0 && it.height > 0 }?.let { size ->
            resolveChartLayout(
                canvasSize = Size(size.width.toFloat(), size.height.toFloat()),
                axisWidthPx = axisWidthPx,
                timeAxisHeightPx = timeAxisHeightPx,
                densityScale = densityScale,
                paneConfig = appliedPaneConfig,
                subPaneSpecs = indicatorSubPaneSpecs,
            )
        }
    }
    val currentReportedLayout = rememberUpdatedState(reportedLayout)
    val mainRenderRangeCache = remember { KlineMainRenderRangeCache() }
    val indicatorRenderFramesCache = remember { KlineIndicatorRenderFramesCache() }
    val translatableMainFramesCache = remember { KlineIndicatorRenderFramesCache() }
    val crossTooltipPresentationCache = remember { KlineCrossTooltipPresentationCache() }
    val interactionDrawGeometryRef = remember { KlineInteractionDrawGeometryRef() }
    val builtInTopTipsPlanCache = remember { KlineBuiltInTopTipsPlanCache() }
    val independentCrossNodesEnabled = !appliedPaneConfig.drawBelowTipsArea
    val crossPriceNodeHeightPx = remember(
        renderConfig.crosshair.ticksText,
        appliedStyle.crossTextColor,
        textMeasurer,
        densityScale,
    ) {
        textMeasurer.measure(
            text = AnnotatedString("0"),
            style = renderConfig.crosshair.ticksText.toComposeTextStyle(appliedStyle.crossTextColor),
            maxLines = renderConfig.crosshair.ticksText.maxLines,
            overflow = TextOverflow.Ellipsis,
        ).size.height +
            (renderConfig.crosshair.ticksText.padding.topPx +
                renderConfig.crosshair.ticksText.padding.bottomPx) * densityScale
    }
    val quantizedCrossSelectionPosition by remember(
        independentCrossNodesEnabled,
        crossTooltipProvider,
        appliedTimeLabelFormatter,
        defaultTimeLabelFormatterFunction,
    ) {
        derivedStateOf {
            val raw = crosshairState.value ?: return@derivedStateOf null
            val current = currentChartState()
            val geometry = interactionDrawGeometryRef.observedValue
                ?.takeIf { cached -> cached.state === current }
                ?: return@derivedStateOf raw
            val plotRect = geometry.layout.mainPane.plotRect
            val crosshair = resolveKlineIndicatorCrosshairContext(
                inputPosition = raw,
                state = current,
                plotRect = plotRect,
                renderConfig = currentRenderConfig.value.crosshair,
                crosshairRightPx = plotRect.right,
            ) ?: return@derivedStateOf raw
            val frames = geometry.indicatorFrames.main.asSequence() +
                geometry.indicatorFrames.subByPane.values.asSequence().flatten()
            val requiresRawPosition =
                !independentCrossNodesEnabled ||
                    crossTooltipProvider != null ||
                    appliedTimeLabelFormatter !== defaultTimeLabelFormatterFunction ||
                    frames.any { frame ->
                        frame.item.renderer.hasCrossCapability() ||
                            frame.item.renderer.hasDynamicTopTipsCapability()
                    } ||
                    raw.y !in plotRect.top..plotRect.bottom
            if (requiresRawPosition) {
                raw
            } else {
                Offset(crosshair.position.x, plotRect.center.y)
            }
        }
    }
    val crossPriceNodeContent by remember(
        independentCrossNodesEnabled,
        renderConfig.crosshair,
        appliedStyle,
        densityScale,
        textMeasurer,
    ) {
        derivedStateOf {
            if (!independentCrossNodesEnabled) return@derivedStateOf null
            val raw = crosshairState.value ?: return@derivedStateOf null
            val current = currentChartState()
            val geometry = interactionDrawGeometryRef.observedValue
                ?.takeIf { cached -> cached.state === current }
                ?: return@derivedStateOf null
            val plotRect = geometry.layout.mainPane.plotRect
            val rawY = raw.y.coerceIn(plotRect.top, plotRect.bottom)
            val precision = geometry.state.spec?.precision ?: KlineSpec.DefaultPrecision
            val text = formatPrice(geometry.valueRange.valueAt(rawY, plotRect), precision)
            KlineCrossPriceNodeContent(
                text = text,
                heightPx = crossPriceNodeHeightPx,
            )
        }
    }
    val crossNodePosition by remember(independentCrossNodesEnabled, reportedLayout) {
        derivedStateOf {
            if (!independentCrossNodesEnabled) return@derivedStateOf null
            val raw = crosshairState.value ?: return@derivedStateOf null
            val layout = reportedLayout ?: return@derivedStateOf null
            val current = currentChartState()
            val crosshair = resolveKlineIndicatorCrosshairContext(
                inputPosition = raw,
                state = current,
                plotRect = layout.mainPane.plotRect,
                renderConfig = currentRenderConfig.value.crosshair,
                crosshairRightPx = layout.mainPane.plotRect.right,
            ) ?: return@derivedStateOf null
            KlineCrossNodePosition(
                snappedX = crosshair.position.x,
                rawY = crosshair.rawPosition.y,
            )
        }
    }
    // A parent may replace its callback after the layout has stabilized; send
    // the current value to the new observer as well as on geometry changes.
    LaunchedEffect(reportedLayout, currentOnLayoutChange.value) {
        reportedLayout?.let(currentOnLayoutChange.value)
    }

    fun resolveCurrentLayout(): KlineLayout? {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return null
        currentReportedLayout.value?.takeIf { layout ->
            layout.canvasRect.width == canvasSize.width.toFloat() &&
                layout.canvasRect.height == canvasSize.height.toFloat()
        }?.let { return it }
        val active = currentIndicatorPanePlan.value
        return resolveChartLayout(
            canvasSize = Size(canvasSize.width.toFloat(), canvasSize.height.toFloat()),
            axisWidthPx = currentAxisWidthPx.value,
            timeAxisHeightPx = currentTimeAxisHeightPx.value,
            densityScale = currentDensityScale.value,
            paneConfig = currentPaneConfig.value,
            subPaneSpecs = active.subPaneSpecs,
        )
    }

    fun updateCrosshair(position: Offset?) {
        crosshairState.value = position
        if (position == null) {
            crosshairSessionRef.value = null
            crossTooltipStableContentWidthRef.value = null
        }
        val current = currentChartState()
        val activeAndLayout = if (position != null && canvasSize != IntSize.Zero) {
            resolveCurrentLayout()?.let { layout -> currentIndicatorPanePlan.value to layout }
        } else {
            null
        }
        val layout = activeAndLayout?.second
        val mainRange = activeAndLayout?.let { (active, resolvedLayout) ->
            mainRenderRangeCache.resolve(
                state = current,
                layout = resolvedLayout,
                panePlan = active,
                renderConfig = currentRenderConfig.value,
                hideMainIndicators = currentHideMainIndicatorsInLineChartMode.value &&
                    currentIsLineLikeMainChart.value,
                densityScale = currentDensityScale.value,
            )
        }
        currentOnCrosshairChange.value(
            position?.takeIf { layout != null && mainRange != null }?.toKlineCrosshair(
                state = current,
                plotRect = checkNotNull(layout).mainPane.plotRect,
                renderConfig = currentRenderConfig.value,
                valueRange = checkNotNull(mainRange).valueRange,
                crosshairRightPx = checkNotNull(layout).mainPane.plotRect.right,
            ),
        )
    }

    fun startCrosshair(position: Offset, session: CrosshairSession) {
        crosshairSessionRef.value = session
        updateCrosshair(position)
    }

    fun resolveDrawingSpace(current: KlineUiState): DrawingCoordinateSpace? {
        val layout = resolveCurrentLayout() ?: return null
        val active = currentIndicatorPanePlan.value
        val mainRange = mainRenderRangeCache.resolve(
            state = current,
            layout = layout,
            panePlan = active,
            renderConfig = currentRenderConfig.value,
            hideMainIndicators = currentHideMainIndicatorsInLineChartMode.value &&
                currentIsLineLikeMainChart.value,
            densityScale = currentDensityScale.value,
        ) ?: return null
        return DrawingCoordinateSpace(
            series = current.series,
            viewport = current.viewport,
            plotRect = layout.mainPane.plotRect,
            minValue = mainRange.valueRange.minimum,
            maxValue = mainRange.valueRange.maximum,
        )
    }
    fun resolveCachedCrossTooltipPresentation(
        crosshair: KlineIndicatorCrosshairContext,
        state: KlineUiState,
        plotRect: Rect,
        anchorRect: Rect,
        config: KlineCrosshairRenderConfig,
        style: KlineChartStyle,
        densityScale: Float,
        timeLabelFormatter: KlineTimeLabelFormatter,
        provider: KlineCrossTooltipProvider?,
        stableContentWidthPx: Float?,
    ): KlineCrossTooltipPresentation? {
        // Runtime callbacks may intentionally read host-owned mutable state.
        // Cache only the built-in pure provider/formatter path so those reads
        // keep their original draw invalidation semantics.
        if (provider != null || timeLabelFormatter !== defaultTimeLabelFormatterFunction) {
            return resolveKlineCrossTooltipPresentation(
                crosshair = crosshair,
                state = state,
                plotRect = plotRect,
                anchorRect = anchorRect,
                config = config,
                style = style,
                textMeasurer = textMeasurer,
                densityScale = densityScale,
                fontScale = composeDensity.fontScale,
                timeLabelFormatter = timeLabelFormatter,
                provider = provider,
                stableContentWidthPx = stableContentWidthPx,
            )
        }
        val key = KlineCrossTooltipPresentationCacheKey(
            candleIndex = crosshair.candleIndex,
            candle = crosshair.candle,
            previousCandle = crosshair.previousCandle,
            snappedX = crosshair.position.x,
            spec = state.spec,
            plotRect = plotRect,
            anchorRect = anchorRect,
            config = config,
            style = style,
            densityScale = densityScale,
            fontScale = composeDensity.fontScale,
            timeLabelFormatter = timeLabelFormatter,
            provider = provider,
            stableContentWidthPx = stableContentWidthPx,
        )
        if (crossTooltipPresentationCache.matches(key)) return crossTooltipPresentationCache.value()
        val presentation = resolveKlineCrossTooltipPresentation(
            crosshair = crosshair,
            state = state,
            plotRect = plotRect,
            anchorRect = anchorRect,
            config = config,
            style = style,
            textMeasurer = textMeasurer,
            densityScale = densityScale,
            fontScale = composeDensity.fontScale,
            timeLabelFormatter = timeLabelFormatter,
            provider = provider,
            stableContentWidthPx = stableContentWidthPx,
        )
        crossTooltipPresentationCache.update(key, presentation)
        presentation?.layout?.contentWidthPx?.let { contentWidth ->
            crossTooltipStableContentWidthRef.value = max(
                crossTooltipStableContentWidthRef.value ?: 0f,
                contentWidth,
            )
        }
        return presentation
    }
    /** Rebuilds Cross tooltip geometry for both Canvas stability tracking and pointer routing. */

    fun resolveCurrentCrossTooltipPresentation(): KlineCrossTooltipPresentation? {
        val position = crosshairState.value ?: return null
        val current = currentChartState()
        val layout = resolveCurrentLayout() ?: return null
        val config = currentRenderConfig.value.crosshair
        val crosshairContext = resolveKlineIndicatorCrosshairContext(
            inputPosition = position,
            state = current,
            plotRect = layout.mainPane.plotRect,
            renderConfig = config,
            crosshairRightPx = layout.mainPane.plotRect.right,
        ) ?: return null
        return resolveCachedCrossTooltipPresentation(
            crosshair = crosshairContext,
            state = current,
            plotRect = layout.mainPane.plotRect,
            anchorRect = layout.mainPane.outerRect,
            config = config,
            style = currentStyle.value,
            densityScale = currentDensityScale.value,
            timeLabelFormatter = currentTimeLabelFormatter.value,
            provider = currentCrossTooltipProvider.value,
            stableContentWidthPx = crossTooltipStableContentWidthRef.value,
        )
    }
    /** Kanvas consumes a visible custom-tooltip row before all chart-level tap routes. */

    fun dispatchCurrentCrossTooltipTap(position: Offset): Boolean {
        val presentation = resolveCurrentCrossTooltipPresentation() ?: return false
        return dispatchKlineCrossTooltipItemTap(
            position = position,
            layout = presentation.layout,
            items = presentation.items,
            hitTestMarginPx = currentRenderConfig.value.crosshair.tooltipHitTestMarginPx * currentDensityScale.value,
        )
    }
    /** Rebuilds the immutable indicator geometry used by pointer hook routing. */

    fun resolveCurrentIndicatorFrames(
        current: KlineUiState,
    ): Pair<KlineLayout, KlineIndicatorRenderFrames>? {
        if (canvasSize.width <= 0 || canvasSize.height <= 0 || current.series.isEmpty) return null
        val active = currentIndicatorPanePlan.value
        val density = currentDensityScale.value
        val layout = resolveCurrentLayout() ?: return null
        val hideMain = currentHideMainIndicatorsInLineChartMode.value &&
            currentIsLineLikeMainChart.value
        val mainRange = mainRenderRangeCache.resolve(
            state = current,
            layout = layout,
            panePlan = active,
            renderConfig = currentRenderConfig.value,
            hideMainIndicators = hideMain,
            densityScale = density,
        ) ?: return null
        return layout to indicatorRenderFramesCache.resolve(
            state = current,
            layout = layout,
            panePlan = active,
            paintRange = mainRange.paintRange,
            mainValueRange = mainRange.valueRange,
            style = currentStyle.value,
            densityScale = density,
            hideMainIndicators = hideMain,
            sameValueExpansionRatios = currentRenderConfig.value.sameValueRangeExpansionRatios,
            subPaneRangeSmoothers = subPaneRangeSmoothers,
            subPaneRangeResetKey = current.spec?.key,
            subPaneRangeTransition = historicalRangeTransition,
        )
    }
    /** Dispatches confirmed pointer taps before the Cross persistent-toggle route. */

    fun dispatchCurrentIndicatorTap(position: Offset): Boolean {
        val current = currentChartState()
        val (layout, frames) = resolveCurrentIndicatorFrames(current) ?: return false
        val activeCrosshair = crosshairState.value?.let { crossPosition ->
            resolveKlineIndicatorCrosshairContext(
                inputPosition = crossPosition,
                state = current,
                plotRect = layout.mainPane.plotRect,
                renderConfig = currentRenderConfig.value.crosshair,
            crosshairRightPx = layout.mainPane.plotRect.right,
            )
        }
        return dispatchKlineIndicatorTap(
            position = position,
            frames = frames,
            layout = layout,
            crosshair = activeCrosshair,
        )
    }
    /**
     * Kanvas dispatches the Candle off-view label before starting Cross. The
     * target is recalculated here rather than written from Canvas so pointer
     * handling cannot lag behind a viewport/configuration update.
     */

    fun resolveOffViewLatestPriceHitTarget(): Rect? {
        val current = currentChartState()
        if (current.series.isEmpty) return null
        val layout = resolveCurrentLayout() ?: return null
        val plotRect = layout.mainPane.plotRect
        if (plotRect.width <= 0f || plotRect.height <= 0f) return null
        val density = currentDensityScale.value
        val active = currentIndicatorPanePlan.value
        val range = mainRenderRangeCache.resolve(
            state = current,
            layout = layout,
            panePlan = active,
            renderConfig = currentRenderConfig.value,
            hideMainIndicators = currentHideMainIndicatorsInLineChartMode.value &&
                currentIsLineLikeMainChart.value,
            densityScale = density,
        )?.valueRange ?: return null
        val visual = resolveLatestPriceMarkerVisual(
            latest = current.series.latest,
            plotRect = plotRect,
            values = range,
            viewport = current.viewport,
            precision = current.spec?.precision ?: KlineSpec.DefaultPrecision,
            style = currentStyle.value,
            config = currentCandleOverlayConfig.value,
            textMeasurer = textMeasurer,
            densityScale = density,
        ) ?: return null
        return visual.geometry
            .takeIf { !it.isInView }
            ?.labelRect
            ?.expandKlineHitTarget(visual.geometry.marker.hitTestMarginPx, density)
    }

    fun requestMoveToInitialPosition() {
        currentOnMoveToInitialPosition.value?.let { callback ->
            callback()
            return
        }
        val current = currentChartState()
        if (current.series.isEmpty) return
        val layout = resolveCurrentLayout() ?: return
        val constraints = current.viewportConstraints
            ?: currentRenderConfig.value.viewport.constraints(
                plotWidthPx = layout.mainPane.plotRect.width,
                densityScale = currentDensityScale.value,
            )
        val initialOffset = KlineViewportMath.bounds(
            candleCount = current.series.size,
            viewport = current.viewport,
            constraints = constraints,
        ).initialOffsetPx
        val next = current.viewport.copy(rightEdgeOffsetPx = initialOffset)
        if (next != current.viewport) currentOnViewportChange.value(next)
    }

    fun resetVerticalZoom() {
        verticalZoomInsetPx = 0f
        verticalZoomShiftPx = 0f
        isChartZooming = false
    }

    fun dispatchConfirmedTap(position: Offset) {
        val selectedDrawing = currentDrawingController.value?.takeIf {
            currentDrawingConfig.value.enabled && currentDrawingConfig.value.allowSelectWhenExited
        }?.let { controller ->
            resolveDrawingSpace(currentChartState())?.let { space ->
                controller.select(
                    position = position,
                    space = space,
                    maxDistancePx =
                        currentDrawingConfig.value.hitTestDistancePx * currentDensityScale.value,
                )
            }
        }
        if (selectedDrawing != null) {
            updateCrosshair(null)
        } else if (dispatchCurrentCrossTooltipTap(position)) {
            // Tooltip rows consume the confirmed tap first.
        } else if (resolveOffViewLatestPriceHitTarget()?.contains(position) == true) {
            requestMoveToInitialPosition()
        } else if (dispatchCurrentIndicatorTap(position)) {
            // Native indicator handlers run before persistent Cross.
        } else if (currentRenderConfig.value.crosshair.enabled) {
            if (crosshairSessionRef.value == CrosshairSession.Persistent) {
                updateCrosshair(null)
            } else {
                startCrosshair(position, CrosshairSession.Persistent)
            }
        }
    }

    fun updateNonTouchViewport(position: Offset, scrollDelta: Offset) {
        val current = currentChartState()
        val layout = resolveCurrentLayout() ?: return
        val gesture = currentRenderConfig.value.gesture
        val constraints = current.viewportConstraints
            ?: currentRenderConfig.value.viewport.constraints(
                layout.mainPane.plotRect.width,
                currentDensityScale.value,
            )
        val old = current.viewport
        val next = if (abs(scrollDelta.y) > abs(scrollDelta.x) && gesture.enableScale) {
            val direction = scrollDelta.y.compareTo(0f)
            val width = (
                old.candleWidthPx - direction * gesture.effectiveScaleSpeed
                ).coerceIn(
                currentRenderConfig.value.viewport.resolvedMinCandleWidthPx(currentDensityScale.value),
                currentRenderConfig.value.viewport.resolvedMaxCandleWidthPx(currentDensityScale.value),
            )
            KlineInteractionMath.scaleToWidth(
                viewport = old,
                newCandleWidthPx = width,
                focalX = position.x - layout.mainPane.plotRect.left,
                plotWidthPx = layout.mainPane.plotRect.width,
                anchor = gesture.scaleAnchor,
            )
        } else if (abs(scrollDelta.x) > 1f) {
            old.copy(rightEdgeOffsetPx = old.rightEdgeOffsetPx - scrollDelta.x)
        } else {
            old
        }
        val clamped = KlineInteractionMath.clamp(current.series.size, next, constraints)
        if (clamped != old) currentOnViewportChange.value(clamped)
    }
    LaunchedEffect(state.interactionEpoch) {
        updateCrosshair(null)
    }
    LaunchedEffect(canvasSize, renderConfig.viewport, densityScale, axisWidthPx, appliedPaneConfig) {
        if (canvasSize.width > 0) {
            val horizontal = resolveMainPlotHorizontalGeometry(
                canvasWidthPx = canvasSize.width.toFloat(),
                axisWidthPx = axisWidthPx,
                mainPadding = appliedPaneConfig.mainPadding,
                densityScale = densityScale,
            )
            onViewportConstraintsChange(
                renderConfig.viewport.constraints(
                    plotWidthPx = horizontal.widthPx,
                    densityScale = densityScale,
                ),
            )
        }
    }
    val magnifierModifier = if (
        drawingController != null &&
        drawingConfig.enabled &&
        drawingMagnifierConfig.enabled
    ) {
        modifier.magnifier(
            sourceCenter = {
                drawingController.snapshot.pointer ?: Offset.Unspecified
            },
            magnifierCenter = {
                val pointer = drawingController.snapshot.pointer
                if (pointer == null || canvasSize == IntSize.Zero) {
                    Offset.Unspecified
                } else {
                    val halfWidth = drawingMagnifierConfig.size.width.toPx() / 2f
                    val halfHeight = drawingMagnifierConfig.size.height.toPx() / 2f
                    Offset(
                        x = if (pointer.x > canvasSize.width / 2f) {
                            halfWidth + drawingMagnifierConfig.marginLeftPx
                        } else {
                            canvasSize.width - halfWidth - drawingMagnifierConfig.marginRightPx
                        },
                        y = halfHeight + drawingMagnifierConfig.marginTopPx,
                    )
                }
            },
            zoom = drawingMagnifierConfig.zoom,
            size = drawingMagnifierConfig.size,
            cornerRadius = drawingMagnifierConfig.cornerRadius,
            elevation = drawingMagnifierConfig.elevation,
            clip = drawingMagnifierConfig.clip,
        )
    } else {
        modifier
    }
    val chartModifier = magnifierModifier
        .preferredFrameRate(renderConfig.preferredFrameRate)
        .focusRequester(focusRequester)
        .focusable()
        .onKeyEvent { event ->
            val gesture = currentRenderConfig.value.gesture
            if (!gesture.supportKeyboardShortcuts || event.type != KeyEventType.KeyDown) {
                return@onKeyEvent false
            }
            if (event.key == Key.Escape) {
                updateCrosshair(null)
                resetVerticalZoom()
                return@onKeyEvent true
            }
            val current = currentChartState()
            if (current.series.isEmpty || canvasSize.width <= 0) return@onKeyEvent false
            val horizontal = resolveMainPlotHorizontalGeometry(
                canvasWidthPx = canvasSize.width.toFloat(),
                axisWidthPx = currentAxisWidthPx.value,
                mainPadding = currentPaneConfig.value.mainPadding,
                densityScale = currentDensityScale.value,
            )
            val constraints = current.viewportConstraints
                ?: currentRenderConfig.value.viewport.constraints(
                    horizontal.widthPx,
                    currentDensityScale.value,
                )
            val old = current.viewport
            val candidate = when {
                event.key == Key.DirectionLeft -> old.copy(
                    rightEdgeOffsetPx = old.rightEdgeOffsetPx - old.candleStepPx * 3f,
                )
                event.key == Key.DirectionRight -> old.copy(
                    rightEdgeOffsetPx = old.rightEdgeOffsetPx + old.candleStepPx * 3f,
                )
                event.utf16CodePoint == '+'.code || event.utf16CodePoint == '='.code ->
                    KlineInteractionMath.scaleToWidth(
                        viewport = old,
                        newCandleWidthPx = (old.candleWidthPx + gesture.effectiveScaleSpeed)
                            .coerceAtMost(
                                currentRenderConfig.value.viewport.resolvedMaxCandleWidthPx(
                                    currentDensityScale.value,
                                ),
                            ),
                        focalX = horizontal.widthPx / 2f,
                        plotWidthPx = horizontal.widthPx,
                        anchor = gesture.scaleAnchor,
                    )
                event.utf16CodePoint == '-'.code ->
                    KlineInteractionMath.scaleToWidth(
                        viewport = old,
                        newCandleWidthPx = (old.candleWidthPx - gesture.effectiveScaleSpeed)
                            .coerceAtLeast(
                                currentRenderConfig.value.viewport.resolvedMinCandleWidthPx(
                                    currentDensityScale.value,
                                ),
                            ),
                        focalX = horizontal.widthPx / 2f,
                        plotWidthPx = horizontal.widthPx,
                        anchor = gesture.scaleAnchor,
                    )
                else -> return@onKeyEvent false
            }
            val next = KlineInteractionMath.clamp(current.series.size, candidate, constraints)
            if (next != old) currentOnViewportChange.value(next)
            true
        }
        .onSizeChanged { size ->
            canvasSize = size
            val horizontal = resolveMainPlotHorizontalGeometry(
                canvasWidthPx = size.width.toFloat(),
                axisWidthPx = axisWidthPx,
                mainPadding = appliedPaneConfig.mainPadding,
                densityScale = densityScale,
            )
            onViewportConstraintsChange(
                renderConfig.viewport.constraints(
                    plotWidthPx = horizontal.widthPx,
                    densityScale = densityScale,
                ),
            )
        }
        .pointerHoverIcon(
            if (hoverInVerticalZoomTarget) PointerIcon.Hand else PointerIcon.Crosshair,
        )
        .pointerInput("non-touch-input") {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    // Touch move events share PointerEventType.Move with
                    // mouse/stylus hover. They belong exclusively to the
                    // touch gesture router below and must never open Cross.
                    if (change.type == PointerType.Touch) continue
                    when (event.type) {
                        PointerEventType.Enter -> startCrosshair(change.position, CrosshairSession.Hover)
                        PointerEventType.Move -> {
                            val position = change.position
                            val layout = resolveCurrentLayout()
                            hoverInVerticalZoomTarget =
                                currentRenderConfig.value.gesture.enableZoom &&
                                    layout != null &&
                                    position.x in layout.axisRect.left..layout.axisRect.right &&
                                    position.y in
                                    layout.mainPane.outerRect.top..layout.mainPane.outerRect.bottom
                            val controller = currentDrawingController.value
                            if (
                                controller != null &&
                                controller.snapshot.visible &&
                                controller.snapshot.state is DrawingState.Drawing
                            ) {
                                controller.updatePointer(position)
                                if (crosshairSessionRef.value == CrosshairSession.Hover) updateCrosshair(null)
                            } else if (!hoverInVerticalZoomTarget) {
                                startCrosshair(position, CrosshairSession.Hover)
                            } else if (crosshairSessionRef.value == CrosshairSession.Hover) {
                                updateCrosshair(null)
                            }
                        }
                        PointerEventType.Exit -> {
                            hoverInVerticalZoomTarget = false
                            if (crosshairSessionRef.value == CrosshairSession.Hover) updateCrosshair(null)
                        }
                        PointerEventType.Scroll -> {
                            val layout = resolveCurrentLayout()
                            val inZoomTarget = currentRenderConfig.value.gesture.enableZoom &&
                                layout != null &&
                                change.position.x in layout.axisRect.left..layout.axisRect.right &&
                                change.position.y in
                                layout.mainPane.outerRect.top..layout.mainPane.outerRect.bottom
                            if (
                                inZoomTarget &&
                                abs(change.scrollDelta.y) > abs(change.scrollDelta.x)
                            ) {
                                val delta = change.scrollDelta.y.sign *
                                    kotlin.math.sqrt(abs(change.scrollDelta.y)) *
                                    currentRenderConfig.value.gesture.zoomSpeed
                                val maxInset = layout.mainPane.outerRect.height / 2f - 1f
                                verticalZoomInsetPx =
                                    (verticalZoomInsetPx + delta).coerceIn(0f, maxInset)
                                verticalZoomShiftPx =
                                    verticalZoomShiftPx.coerceIn(
                                        -verticalZoomInsetPx,
                                        verticalZoomInsetPx,
                                    )
                                isChartZooming = verticalZoomInsetPx > 0f
                            } else {
                                updateNonTouchViewport(change.position, change.scrollDelta)
                            }
                            change.consume()
                        }
                    }
                }
            }
        }
        .pointerInput(Unit) {
            coroutineScope outer@{
                var inertialPanJob: Job? = null
                var pendingSingleTapJob: Job? = null
                var previousTapUptimeMillis: Long? = null
                var previousTapPosition: Offset? = null
                awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false).also { down ->
                    focusRequester.requestFocus()
                    // Visible loading owns the foreground above the gesture
                    // detector. A new touch during InitLoading/LoadingMore
                    // must not cancel the
                    // current fling or start a gesture against a data boundary
                    // that is about to change. awaitEachGesture drains the
                    // remaining pointer events before beginning another turn.
                    val stateAtDown = currentChartState()
                    if (
                        shouldShowKlineLoadingOverlay(
                            stateAtDown.loadingState,
                            currentRenderConfig.value.gesture.autoLoadMore,
                        )
                    ) {
                        return@also
                    }
                    val gestureValueRangeToken = gestureValueRangeRef.begin(stateAtDown.viewport)
                    gestureValueRangeEpoch++
                    // Size, density and viewport constraints cannot meaningfully change in the
                    // middle of one pointer stream. Resolve them once here instead of rebuilding
                    // horizontal geometry and constraints for every Move event (and every fling
                    // animation frame after release).
                    val gestureConfig = currentRenderConfig.value
                    val gestureDensity = currentDensityScale.value
                    val gestureHorizontal = resolveMainPlotHorizontalGeometry(
                        canvasWidthPx = canvasSize.width.toFloat(),
                        axisWidthPx = currentAxisWidthPx.value,
                        mainPadding = currentPaneConfig.value.mainPadding,
                        densityScale = gestureDensity,
                    )
                    val gesturePlotWidth = gestureHorizontal.widthPx
                    val gestureConstraints = stateAtDown.viewportConstraints
                        ?: gestureConfig.viewport.constraints(gesturePlotWidth, gestureDensity)
                    val gestureMinCandleWidthPx =
                        gestureConfig.viewport.resolvedMinCandleWidthPx(gestureDensity)
                    val gestureMaxCandleWidthPx =
                        gestureConfig.viewport.resolvedMaxCandleWidthPx(gestureDensity)
                    inertialPanJob?.cancel()
                    inertialPanJob = null
                    var owner = PointerGestureOwner.Pending
                    var latestPosition = down.position
                    var latestUptimeMillis = down.uptimeMillis
                    var accumulatedPan = Offset.Zero
                    var viewportDuringGesture: KlineViewport? = null
                    var gestureStartViewport: KlineViewport? = null
                    var pendingViewportCommit: KlineViewport? = null
                    var viewportCommitJob: Job? = null
                    var pointerActive = true
                    var completedPanVelocityX = 0f
                    var completedWithPan = false
                    var resizeEntries: List<KlinePaneResizeEntry> = emptyList()
                    var resizeUpperIndex: Int? = null
                    var drawingMoveLastPoint: DrawingPoint? = null
                    val velocityTracker = VelocityTracker().apply {
                        addPosition(down.uptimeMillis, down.position)
                    }
                    val touchSlop = viewConfiguration.touchSlop
                    val longPressTimeoutMillis = viewConfiguration.longPressTimeoutMillis
                        var longPressJob: Job? = null

                        fun cancelLongPress() {
                            longPressJob?.cancel()
                            longPressJob = null
                        }

                        fun consumePositionChanges(event: androidx.compose.ui.input.pointer.PointerEvent) {
                            event.changes.forEach { change ->
                                if (change.positionChanged()) change.consume()
                            }
                        }

                        fun commitPendingViewport() {
                            val next = pendingViewportCommit ?: return
                            pendingViewportCommit = null
                            val current = currentChartState()
                            if (next != current.viewport) {
                                traceKlineSection("Kanvas.viewport.commit") {
                                    currentOnViewportChange.value(next)
                                }
                            }
                        }

                        fun scheduleViewportCommit(next: KlineViewport) {
                            pendingViewportCommit = next
                            if (viewportCommitJob?.isActive == true) return
                            viewportCommitJob = this@outer.launch {
                                withFrameNanos { }
                                commitPendingViewport()
                                viewportCommitJob = null
                            }
                        }

                        fun updateViewport(
                            pan: Offset,
                            zoom: Float,
                            focal: Offset,
                            scaleOnly: Boolean,
                        ): KlineViewport? {
                            val current = currentChartState()
                            if (canvasSize.width <= 0 || canvasSize.height <= 0 || current.series.isEmpty) {
                                return viewportDuringGesture
                            }
                            val old = viewportDuringGesture ?: current.viewport
                            if (!scaleOnly && gestureStartViewport == null) {
                                gestureStartViewport = old
                            }
                            val candidate = if (
                                scaleOnly && gestureConfig.gesture.enableScale && abs(zoom - 1f) > 0.001f
                            ) {
                                KlineInteractionMath.scaleByGesture(
                                    viewport = old,
                                    zoomDelta = zoom - 1f,
                                    focalX = gestureHorizontal.localX(focal.x)
                                        .coerceIn(0f, gesturePlotWidth),
                                    plotWidthPx = gesturePlotWidth,
                                    minCandleWidthPx = gestureMinCandleWidthPx,
                                    maxCandleWidthPx = gestureMaxCandleWidthPx,
                                    scaleSpeed = gestureConfig.gesture.effectiveScaleSpeed * gestureDensity,
                                    anchor = gestureConfig.gesture.scaleAnchor,
                                )
                            } else if (!scaleOnly) {
                                old.copy(
                                    rightEdgeOffsetPx =
                                        old.rightEdgeOffsetPx +
                                            pan.x * gestureConfig.gesture.effectivePanSensitivity,
                                )
                            } else {
                                old
                            }
                            val next = KlineInteractionMath.clamp(
                                current.series.size,
                                candidate,
                                gestureConstraints,
                            )
                            viewportDuringGesture = next
                            if (next != old) scheduleViewportCommit(next)
                            return next
                        }

                        fun requestLoadMoreFor(projectedViewport: KlineViewport) {
                            val current = currentChartState()
                            val config = currentRenderConfig.value
                            if (
                                !config.gesture.autoLoadMore ||
                                current.loadingState == KlineLoadingState.InitLoading ||
                                current.series.isEmpty
                            ) {
                                return
                            }
                            val currentDensity = currentDensityScale.value
                            val horizontal = resolveMainPlotHorizontalGeometry(
                                canvasWidthPx = canvasSize.width.toFloat(),
                                axisWidthPx = currentAxisWidthPx.value,
                                mainPadding = currentPaneConfig.value.mainPadding,
                                densityScale = currentDensity,
                            )
                            val plotWidth = horizontal.widthPx
                            val constraints = current.viewportConstraints
                                ?: config.viewport.constraints(plotWidth, currentDensity)
                            KlineInteractionMath.loadMoreIntent(
                                candleCount = current.series.size,
                                projectedViewport = projectedViewport,
                                constraints = constraints,
                                loadMoreWhenNoEnoughDistancePx = config.gesture.loadMoreWhenNoEnoughDistancePx
                                    ?.times(currentDensity),
                                loadMoreWhenNoEnoughCandles = config.gesture.loadMoreWhenNoEnoughCandles,
                            )
                                .dispatchFor(current.loadingState)
                                ?.let(currentOnLoadMoreRequested.value)
                        }
                        val drawingAtStart = currentDrawingController.value
                        val drawingSpaceAtStart = if (
                            drawingAtStart != null && currentDrawingConfig.value.enabled
                        ) {
                            resolveDrawingSpace(currentChartState())
                        } else {
                            null
                        }
                        when (drawingAtStart?.snapshot?.state) {
                            is DrawingState.Drawing -> {
                                if (drawingSpaceAtStart?.plotRect?.contains(down.position) == true) {
                                    owner = PointerGestureOwner.DrawingEdit
                                    drawingAtStart.updatePointer(down.position)
                                }
                            }
                            is DrawingState.Editing -> {
                                val selectedPoint = drawingSpaceAtStart?.let { space ->
                                    drawingAtStart.selectPoint(
                                        position = down.position,
                                        space = space,
                                        maxDistancePx =
                                            currentDrawingConfig.value.hitTestDistancePx *
                                                currentDensityScale.value,
                                    )
                                }
                                if (selectedPoint != null) {
                                    owner = PointerGestureOwner.DrawingEdit
                                } else if (drawingSpaceAtStart != null) {
                                    val selectedOverlay = drawingAtStart.select(
                                        position = down.position,
                                        space = drawingSpaceAtStart,
                                        maxDistancePx =
                                            currentDrawingConfig.value.hitTestDistancePx *
                                                currentDensityScale.value,
                                    )
                                    if (selectedOverlay != null && !selectedOverlay.locked) {
                                        owner = PointerGestureOwner.DrawingMove
                                        drawingMoveLastPoint = drawingSpaceAtStart.unproject(down.position)
                                        drawingAtStart.updatePointer(down.position)
                                    }
                                }
                            }
                            DrawingState.Exited,
                            DrawingState.Prepared,
                            null,
                            -> Unit
                        }
                        resolveCurrentLayout()?.let { startLayout ->
                            val paneConfigAtStart = currentPaneConfig.value
                            val resizablePanes = listOf(startLayout.mainPane) + startLayout.subPanes
                            if (
                                owner == PointerGestureOwner.Pending &&
                                currentRenderConfig.value.grid.allowDragIndicatorHeight
                            ) {
                                resizeUpperIndex = KlinePaneResizeMath.hitBoundary(
                                    panes = resizablePanes,
                                    y = down.position.y,
                                    hitDistancePx =
                                        currentRenderConfig.value.grid.dragHitTestMinDistancePx *
                                            currentDensityScale.value,
                                    mode = paneConfigAtStart.mode,
                                )
                                if (resizeUpperIndex != null) {
                                    owner = PointerGestureOwner.PaneResize
                                    resizeEntries = resizablePanes.map { pane ->
                                        KlinePaneResizeEntry(
                                            id = pane.id,
                                            heightPx = pane.resolvedHeightPx,
                                            minHeightPx = if (pane.id == "main") {
                                                paneConfigAtStart.mainMinHeight.value * currentDensityScale.value
                                            } else {
                                                paneConfigAtStart.paneFor(pane.id).minHeight.value *
                                                    currentDensityScale.value
                                            },
                                        )
                                    }
                                    activeResizeBoundaryY =
                                        resizablePanes[checkNotNull(resizeUpperIndex)].outerRect.bottom
                                }
                            }
                            if (
                                owner == PointerGestureOwner.Pending &&
                                currentRenderConfig.value.gesture.enableZoom &&
                                (
                                    if (currentRenderConfig.value.gesture.isManualSetZoomRect) {
                                        currentVerticalZoomHitRect.value?.contains(down.position) == true
                                    } else {
                                        down.position.x in
                                            startLayout.axisRect.left..startLayout.axisRect.right &&
                                            down.position.y in
                                            startLayout.mainPane.outerRect.top..startLayout.mainPane.outerRect.bottom
                                    }
                                )
                            ) {
                                owner = PointerGestureOwner.VerticalZoom
                            }
                        }
                        if (
                            owner == PointerGestureOwner.Pending &&
                            crosshairSessionRef.value == null &&
                            currentRenderConfig.value.crosshair.enabled &&
                            currentRenderConfig.value.gesture.enableLongPress
                        ) {
                            longPressJob = this@outer.launch {
                                delay(longPressTimeoutMillis)
                                if (
                                    pointerActive &&
                                    owner == PointerGestureOwner.Pending &&
                                    crosshairSessionRef.value == null
                                ) {
                                    owner = PointerGestureOwner.CrossDrag
                                    startCrosshair(latestPosition, CrosshairSession.Hold)
                                }
                            }
                        }
                        try {
                            while (pointerActive) {
                                val event = awaitPointerEvent()
                                event.changes.firstOrNull()?.let { change ->
                                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                                    latestUptimeMillis = change.uptimeMillis
                                }
                                pointerActive = event.changes.any { it.pressed }
                                val centroid = event.calculateCentroid(useCurrent = true)
                                if (centroid != Offset.Unspecified) latestPosition = centroid
                                if (!pointerActive) {
                                    cancelLongPress()
                                    when (owner) {
                                        PointerGestureOwner.Pending -> {
                                            val doubleTapCallback = currentOnDoubleTap.value
                                            val previousTime = previousTapUptimeMillis
                                            val previousPosition = previousTapPosition
                                            val elapsed = previousTime?.let { latestUptimeMillis - it }
                                            val isDoubleTap =
                                                doubleTapCallback != null &&
                                                    elapsed != null &&
                                                    elapsed in
                                                    viewConfiguration.doubleTapMinTimeMillis..
                                                        viewConfiguration.doubleTapTimeoutMillis &&
                                                    previousPosition != null &&
                                                    (latestPosition - previousPosition).getDistance() <= touchSlop * 2f
                                            if (isDoubleTap) {
                                                pendingSingleTapJob?.cancel()
                                                pendingSingleTapJob = null
                                                previousTapUptimeMillis = null
                                                previousTapPosition = null
                                                doubleTapCallback?.invoke()
                                            } else if (doubleTapCallback == null) {
                                                dispatchConfirmedTap(latestPosition)
                                            } else {
                                                pendingSingleTapJob?.cancel()
                                                previousTapUptimeMillis = latestUptimeMillis
                                                previousTapPosition = latestPosition
                                                val tapPosition = latestPosition
                                                val tapUptime = latestUptimeMillis
                                                pendingSingleTapJob = this@outer.launch {
                                                    delay(viewConfiguration.doubleTapTimeoutMillis)
                                                    if (previousTapUptimeMillis == tapUptime) {
                                                        previousTapUptimeMillis = null
                                                        previousTapPosition = null
                                                        dispatchConfirmedTap(tapPosition)
                                                    }
                                                    pendingSingleTapJob = null
                                                }
                                            }
                                        }
                                        PointerGestureOwner.CrossDrag -> {
                                            if (crosshairSessionRef.value == CrosshairSession.Hold) {
                                                // A completed long press keeps the selected
                                                // candle visible after the finger is lifted.
                                                // A later single-finger pan or second pointer
                                                // explicitly clears this persistent session.
                                                startCrosshair(latestPosition, CrosshairSession.Persistent)
                                            }
                                        }
                                        PointerGestureOwner.DrawingEdit -> {
                                            val controller = currentDrawingController.value
                                            val space = resolveDrawingSpace(currentChartState())
                                            when (controller?.snapshot?.state) {
                                                is DrawingState.Drawing -> {
                                                    val point = space?.let {
                                                        controller.snap(
                                                            position = latestPosition,
                                                            space = it,
                                                            candles = currentChartState().series.candles,
                                                            minDistancePx =
                                                                currentDrawingConfig.value.magnetDistancePx *
                                                                    currentDensityScale.value,
                                                        )
                                                    }
                                                    if (point != null) {
                                                        controller.confirmPoint(
                                                            point,
                                                            continueDrawing = currentDrawingConfig.value.continueDrawing,
                                                        )
                                                    }
                                                }
                                                is DrawingState.Editing -> controller.finishPointMove()
                                                else -> Unit
                                            }
                                        }
                                        PointerGestureOwner.DrawingMove -> {
                                            currentDrawingController.value?.finishOverlayMove()
                                        }
                                        PointerGestureOwner.Pan,
                                        -> {
                                            completedWithPan = true
                                            completedPanVelocityX = velocityTracker.calculateVelocity().x
                                        }
                                        PointerGestureOwner.Pinch -> {
                                            requestLoadMoreFor(viewportDuringGesture ?: currentChartState().viewport)
                                        }
                                        PointerGestureOwner.PaneResize,
                                        PointerGestureOwner.VerticalZoom,
                                        -> Unit
                                    }
                                    break
                                }
                                if (
                                    event.changes.count { it.pressed } >= 2 &&
                                    owner != PointerGestureOwner.DrawingEdit &&
                                    owner != PointerGestureOwner.DrawingMove &&
                                    owner != PointerGestureOwner.PaneResize &&
                                    owner != PointerGestureOwner.VerticalZoom
                                ) {
                                    cancelLongPress()
                                    pendingSingleTapJob?.cancel()
                                    pendingSingleTapJob = null
                                    previousTapUptimeMillis = null
                                    previousTapPosition = null
                                    // A second touch always exits Cross before pinch handling.
                                    // Keeping a persistent/held Cross alive here makes its drag
                                    // route compete with scale updates and produces a sticky,
                                    // visibly uneven two-finger gesture.
                                    if (crosshairSessionRef.value != null) updateCrosshair(null)
                                    owner = PointerGestureOwner.Pinch
                                    if (currentRenderConfig.value.gesture.enableScale) isChartZooming = true
                                }
                                if (owner == PointerGestureOwner.CrossDrag) {
                                    updateCrosshair(latestPosition)
                                    consumePositionChanges(event)
                                    continue
                                }
                                when (owner) {
                                    PointerGestureOwner.Pending -> {
                                        accumulatedPan += event.calculatePan()
                                        if (accumulatedPan.getDistance() > touchSlop) {
                                            cancelLongPress()
                                            // Committing to a fresh drag cancels a delayed tap
                                            // and any Cross left visible by an earlier tap. Only
                                            // a Hold created inside this pointer session owns
                                            // Cross dragging.
                                            pendingSingleTapJob?.cancel()
                                            pendingSingleTapJob = null
                                            previousTapUptimeMillis = null
                                            previousTapPosition = null
                                            if (crosshairSessionRef.value == CrosshairSession.Persistent) {
                                                updateCrosshair(null)
                                            }
                                            owner = PointerGestureOwner.Pan
                                            panRangeSmoothFactor =
                                                currentRenderConfig.value.gesture.tolerance.effectivePanSmoothFactor
                                            updateViewport(
                                                pan = accumulatedPan,
                                                zoom = 1f,
                                                focal = latestPosition,
                                                scaleOnly = false,
                                            )
                                            accumulatedPan = Offset.Zero
                                            consumePositionChanges(event)
                                        }
                                    }
                                    PointerGestureOwner.DrawingEdit -> {
                                        val controller = currentDrawingController.value
                                        val space = resolveDrawingSpace(currentChartState())
                                        if (
                                            controller?.snapshot?.state is DrawingState.Editing &&
                                            space != null
                                        ) {
                                            val moved = controller.snap(
                                                position = latestPosition,
                                                space = space,
                                                candles = currentChartState().series.candles,
                                                minDistancePx =
                                                    currentDrawingConfig.value.magnetDistancePx *
                                                        currentDensityScale.value,
                                            )?.let { point ->
                                                controller.moveSelectedPoint(point, pointer = latestPosition)
                                            } == true
                                            if (!moved) controller.updatePointer(latestPosition)
                                        } else {
                                            controller?.updatePointer(latestPosition)
                                        }
                                        consumePositionChanges(event)
                                    }
                                    PointerGestureOwner.DrawingMove -> {
                                        val controller = currentDrawingController.value
                                        val space = resolveDrawingSpace(currentChartState())
                                        val currentPoint = space?.unproject(latestPosition)
                                        val previousPoint = drawingMoveLastPoint
                                        if (controller != null && currentPoint != null && previousPoint != null) {
                                            val moved = controller.moveSelectedBy(
                                                deltaTimestampMillis =
                                                    currentPoint.timestampMillis - previousPoint.timestampMillis,
                                                deltaValue = currentPoint.value - previousPoint.value,
                                                pointer = latestPosition,
                                            )
                                            drawingMoveLastPoint = currentPoint
                                            if (!moved) controller.updatePointer(latestPosition)
                                        }
                                        consumePositionChanges(event)
                                    }
                                    PointerGestureOwner.Pan -> {
                                        val pan = event.calculatePan()
                                        updateViewport(
                                            pan = pan,
                                            zoom = 1f,
                                            focal = latestPosition,
                                            scaleOnly = false,
                                        )
                                        if (verticalZoomInsetPx > 0f && pan.y != 0f) {
                                            verticalZoomShiftPx =
                                                (verticalZoomShiftPx + pan.y)
                                                    .coerceIn(-verticalZoomInsetPx, verticalZoomInsetPx)
                                        }
                                        consumePositionChanges(event)
                                    }
                                    PointerGestureOwner.Pinch -> {
                                        panRangeSmoothFactor = 1f
                                        updateViewport(
                                            pan = Offset.Zero,
                                            zoom = event.calculateZoom(),
                                            focal = latestPosition,
                                            scaleOnly = true,
                                        )
                                        consumePositionChanges(event)
                                    }
                                    PointerGestureOwner.PaneResize -> {
                                        val upperIndex = resizeUpperIndex
                                        if (upperIndex != null) {
                                            val resized = KlinePaneResizeMath.resize(
                                                entries = resizeEntries,
                                                upperIndex = upperIndex,
                                                requestedDeltaPx = event.calculatePan().y,
                                                mode = currentPaneConfig.value.mode,
                                            )
                                            resizeEntries = resized.entries
                                            if (resized.appliedDeltaPx != 0f) {
                                                paneHeightOverridesPx = resized.entries.associate {
                                                    entry -> entry.id to entry.heightPx
                                                }
                                                activeResizeBoundaryY =
                                                    activeResizeBoundaryY?.plus(resized.appliedDeltaPx)
                                            }
                                        }
                                        consumePositionChanges(event)
                                    }
                                    PointerGestureOwner.VerticalZoom -> {
                                        val delta = event.calculatePan().y / 2f *
                                            currentRenderConfig.value.gesture.zoomSpeed
                                        if (delta != 0f) {
                                            val layout = resolveCurrentLayout()
                                            val maxInset = layout?.mainPane?.outerRect?.height
                                                ?.div(2f)
                                                ?.minus(1f)
                                                ?.coerceAtLeast(0f)
                                                ?: Float.MAX_VALUE
                                            verticalZoomInsetPx =
                                                (verticalZoomInsetPx + delta).coerceIn(0f, maxInset)
                                            verticalZoomShiftPx = verticalZoomShiftPx.coerceIn(
                                                -verticalZoomInsetPx,
                                                verticalZoomInsetPx,
                                            )
                                            isChartZooming = verticalZoomInsetPx > 0f
                                        }
                                        consumePositionChanges(event)
                                    }
                                    PointerGestureOwner.CrossDrag -> Unit
                                }
                            }
                        } finally {
                            pointerActive = false
                            viewportCommitJob?.cancel()
                            viewportCommitJob = null
                            // Preserve the exact final gesture position. MOVE events are
                            // frame-coalesced, while pointer-up performs one final commit
                            // before a possible fling starts from the same viewport.
                            commitPendingViewport()
                            isChartZooming = verticalZoomInsetPx > 0f
                            activeResizeBoundaryY = null
                            cancelLongPress()
                            // Hold sessions are promoted to Persistent on pointer-up above;
                            // do not clear them here. Pan/pinch routes clear Cross explicitly.
                        }
                        if (completedWithPan) {
                            val gesture = currentRenderConfig.value.gesture
                            val gestureEndViewport = viewportDuringGesture ?: currentChartState().viewport
                            val inertia = if (gesture.enableInertialPan) {
                                KlineInteractionMath.inertialPanSpec(
                                    velocityPxPerSecond = resolveKlineInertialVelocity(
                                        velocityPxPerSecond =
                                            completedPanVelocityX * gesture.effectivePanSensitivity,
                                        dragDeltaPx =
                                            gestureEndViewport.rightEdgeOffsetPx -
                                                (gestureStartViewport ?: gestureEndViewport).rightEdgeOffsetPx,
                                    ),
                                    tolerance = gesture.tolerance,
                                )
                            } else {
                                null
                            }
                            if (inertia == null) {
                                panRangeSmoothFactor = 1f
                                requestLoadMoreFor(gestureEndViewport)
                                gestureValueRangeRef.end(gestureValueRangeToken)
                                gestureValueRangeEpoch++
                            } else {
                                val gestureEndOffsetPx = gestureEndViewport.rightEdgeOffsetPx
                                // Keep this fling bounded by the data that existed when the
                                // pointer was released. A historical page may complete while
                                // the animation is still running; using the new series size
                                // would expand the boundary mid-flight and fling straight into
                                // the newly loaded page instead of preserving the load anchor.
                                val inertialPanCandleCount = currentChartState().series.size
                                requestLoadMoreFor(
                                    gestureEndViewport.copy(
                                        rightEdgeOffsetPx = gestureEndOffsetPx + inertia.distancePx,
                                    ),
                                )
                                inertialPanJob = this@outer.launch {
                                    try {
                                        animate(
                                            initialValue = 0f,
                                            targetValue = inertia.distancePx,
                                            animationSpec = tween(
                                                durationMillis = inertia.durationMillis,
                                                easing = gesture.tolerance.curve.toComposeEasing(),
                                            ),
                                        ) { animatedDistance, _ ->
                                            val progress = if (inertia.distancePx == 0f) {
                                                1f
                                            } else {
                                                abs(animatedDistance / inertia.distancePx).coerceIn(0f, 1f)
                                            }
                                            val tolerance = gesture.tolerance
                                            val convergence = tolerance.effectiveConvergenceRatio
                                            panRangeSmoothFactor = if (progress < convergence || convergence >= 1f) {
                                                tolerance.effectivePanSmoothFactor
                                            } else {
                                                tolerance.effectivePanSmoothFactor +
                                                    (1f - tolerance.effectivePanSmoothFactor) *
                                                    ((progress - convergence) / (1f - convergence))
                                            }
                                            val current = currentChartState()
                                            if (current.series.isEmpty) return@animate
                                            val old = viewportDuringGesture ?: gestureEndViewport
                                            val next = KlineInteractionMath.clamp(
                                                candleCount = inertialPanCandleCount,
                                                viewport = old.copy(
                                                    rightEdgeOffsetPx =
                                                        gestureEndOffsetPx + animatedDistance,
                                                ),
                                                constraints = gestureConstraints,
                                            )
                                            viewportDuringGesture = next
                                            if (next != old) currentOnViewportChange.value(next)
                                        }
                                    } finally {
                                        // Keep the value-range smoothing active for the whole
                                        // inertial flight. Resetting it immediately after launch
                                        // caused a second draw/layout jump, especially when the
                                        // offset was clamped at the right edge.
                                        panRangeSmoothFactor = 1f
                                        gestureValueRangeRef.end(gestureValueRangeToken)
                                        gestureValueRangeEpoch++
                                    }
                                }
                            }
                        } else {
                            gestureValueRangeRef.end(gestureValueRangeToken)
                            gestureValueRangeEpoch++
                        }
                }
            }
            }
        }
    val loadingMainRect = reportedLayout?.mainPane?.outerRect
    // A new measure-policy instance invalidates Layout measurement even when
    // only the horizontal viewport changed. Keep it stable until pane geometry
    // inputs actually change.
    val chartMeasurePolicy = remember(
        appliedPaneConfig,
        axisWidthPx,
        timeAxisHeightPx,
        densityScale,
        indicatorSubPaneSpecs,
        renderConfig.crosshair,
        crossPriceNodeHeightPx,
    ) {
        MeasurePolicy { measurables, constraints ->
            val width = (if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth)
                .coerceIn(constraints.minWidth, constraints.maxWidth)
            val desiredHeight = if (appliedPaneConfig.mode == KlineLayoutMode.Adapt) {
                resolveChartLayout(
                    canvasSize = Size(width.toFloat(), 0f),
                    axisWidthPx = axisWidthPx,
                    timeAxisHeightPx = timeAxisHeightPx,
                    densityScale = densityScale,
                    paneConfig = appliedPaneConfig,
                    subPaneSpecs = indicatorSubPaneSpecs,
                ).requiredHeightPx.roundToInt()
            } else if (constraints.hasBoundedHeight) {
                constraints.maxHeight
            } else {
                constraints.minHeight
            }
            val height = desiredHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
            val childConstraints = Constraints.fixed(width, height)
            val measuredLayout = resolveChartLayout(
                canvasSize = Size(width.toFloat(), height.toFloat()),
                axisWidthPx = axisWidthPx,
                timeAxisHeightPx = timeAxisHeightPx,
                densityScale = densityScale,
                paneConfig = appliedPaneConfig,
                subPaneSpecs = indicatorSubPaneSpecs,
            )
            val mainRect = measuredLayout.mainPane.outerRect
            val mainConstraints = Constraints.fixed(
                mainRect.width.roundToInt().coerceAtLeast(0),
                mainRect.height.roundToInt().coerceAtLeast(0),
            )
            val crossConfig = renderConfig.crosshair
            val crossStrokePadding = max(
                1f,
                crossConfig.stroke.widthPx * densityScale / 2f + 1f,
            )
            val crossVerticalConstraints = Constraints.fixed(
                kotlin.math.ceil(crossStrokePadding * 2f).toInt().coerceAtLeast(1),
                kotlin.math.ceil(measuredLayout.chartRect.height + crossStrokePadding * 2f)
                    .toInt()
                    .coerceAtLeast(1),
            )
            val crossHorizontalConstraints = Constraints.fixed(
                kotlin.math.ceil(measuredLayout.mainPane.plotRect.width + crossStrokePadding * 2f)
                    .toInt()
                    .coerceAtLeast(1),
                kotlin.math.ceil(crossStrokePadding * 2f).toInt().coerceAtLeast(1),
            )
            val crossPointPadding = (
                crossConfig.pointRadiusPx + crossConfig.pointBorderWidthPx / 2f
                ) * densityScale + 1f
            val crossPointSize = kotlin.math.ceil(crossPointPadding * 2f).toInt().coerceAtLeast(1)
            val crossPointConstraints = Constraints.fixed(crossPointSize, crossPointSize)
            val crossPriceConstraints = Constraints.fixed(
                kotlin.math.ceil(measuredLayout.mainPane.plotRect.width).toInt().coerceAtLeast(1),
                kotlin.math.ceil(crossPriceNodeHeightPx).toInt().coerceAtLeast(1),
            )
            val placeables = measurables.map { measurable ->
                val isMainSlot = measurable.layoutId == "main-background" ||
                    measurable.layoutId == "main-foreground"
                val childConstraintsForId = when {
                    isMainSlot -> mainConstraints
                    measurable.layoutId == "cross-vertical-node" -> crossVerticalConstraints
                    measurable.layoutId == "cross-horizontal-node" -> crossHorizontalConstraints
                    measurable.layoutId == "cross-point-node" -> crossPointConstraints
                    measurable.layoutId == "cross-price-node" -> crossPriceConstraints
                    measurable.layoutId == "vertical-zoom-exit" -> Constraints(
                        maxWidth = mainRect.width.roundToInt().coerceAtLeast(0),
                        maxHeight = mainRect.height.roundToInt().coerceAtLeast(0),
                    )
                    else -> childConstraints
                }
                Triple(measurable.measure(childConstraintsForId), isMainSlot, measurable.layoutId)
            }
            layout(width, height) {
                placeables.forEach { (placeable, isMainSlot, layoutId) ->
                    val x = when {
                        isMainSlot -> mainRect.left.roundToInt()
                        layoutId == "cross-horizontal-node" ->
                            (measuredLayout.mainPane.plotRect.left - crossStrokePadding).roundToInt()
                        layoutId == "cross-price-node" ->
                            measuredLayout.mainPane.plotRect.left.roundToInt()
                        layoutId == "vertical-zoom-exit" ->
                            (mainRect.right - placeable.width - 8f * densityScale).roundToInt()
                        else -> 0
                    }
                    val y = when {
                        isMainSlot -> mainRect.top.roundToInt()
                        layoutId == "cross-vertical-node" ->
                            (measuredLayout.chartRect.top - crossStrokePadding).roundToInt()
                        layoutId == "vertical-zoom-exit" ->
                            (mainRect.bottom - placeable.height - 8f * densityScale).roundToInt()
                        else -> 0
                    }
                    placeable.place(x, y)
                }
            }
        }
    }

    /**
     * Paints state that changes at pointer frequency. In the default layout
     * this runs in a transparent sibling Canvas, so Cross movement does not
     * invalidate candle, time-axis, sub-pane, drawing, or watermark content.
     */
    fun DrawScope.drawInteractionLayer(
        geometry: KlineInteractionDrawGeometry,
        inputPosition: Offset?,
        mainTipsAlreadyDrawn: Boolean,
        drawBuiltInPrimitives: Boolean,
        drawMainPriceLabel: Boolean,
    ) {
        val interactionState = geometry.state
        val layout = geometry.layout
        val plotRect = layout.mainPane.plotRect
        val range = geometry.valueRange
        val indicatorFrames = geometry.indicatorFrames
        val activeNativeCrosshair = inputPosition
            ?.takeIf { renderConfig.crosshair.enabled }
            ?.let { position ->
                resolveKlineIndicatorCrosshairContext(
                    inputPosition = position,
                    state = interactionState,
                    plotRect = plotRect,
                    renderConfig = renderConfig.crosshair,
                    crosshairRightPx = layout.mainPane.plotRect.right,
                )
            }

        fun drawMainTopTips(selection: KlineIndicatorTopTipsSelection) {
            drawKlineIndicatorTopTipsRenderPlan(
                plan = builtInTopTipsPlanCache.resolve(
                    frames = indicatorFrames.main,
                    chartLayout = layout,
                    selection = selection,
                    textMeasurer = textMeasurer,
                    stackMainTips = true,
                ),
                chartLayout = layout,
                selection = selection,
                textMeasurer = textMeasurer,
            )
        }

        fun drawSubTopTips(
            paneId: String,
            selection: KlineIndicatorTopTipsSelection,
        ) {
            drawKlineIndicatorTopTipsRenderPlan(
                plan = builtInTopTipsPlanCache.resolve(
                    frames = indicatorFrames.subByPane[paneId].orEmpty(),
                    chartLayout = layout,
                    selection = selection,
                    textMeasurer = textMeasurer,
                    stackMainTips = false,
                ),
                chartLayout = layout,
                selection = selection,
                textMeasurer = textMeasurer,
            )
        }

        if (activeNativeCrosshair == null) {
            val latest = KlineIndicatorTopTipsSelection.Latest(interactionState.series.latest)
            if (!mainTipsAlreadyDrawn) drawMainTopTips(latest)
            layout.subPanes.forEach { pane -> drawSubTopTips(pane.id, latest) }
            return
        }

        // Draw Tips before the tooltip card so the card remains the topmost
        // Cross object in the main pane.
        if (!mainTipsAlreadyDrawn) {
            drawMainTopTips(KlineIndicatorTopTipsSelection.Cross(activeNativeCrosshair))
        }
        val crossTooltipPresentation = resolveCachedCrossTooltipPresentation(
            crosshair = activeNativeCrosshair,
            state = interactionState,
            plotRect = plotRect,
            anchorRect = layout.mainPane.outerRect,
            config = renderConfig.crosshair,
            style = appliedStyle,
            densityScale = densityScale,
            timeLabelFormatter = appliedTimeLabelFormatter,
            provider = crossTooltipProvider,
            stableContentWidthPx = crossTooltipStableContentWidthRef.value,
        )
        val crossConfig = renderConfig.crosshair
        val effectiveRight = layout.mainPane.plotRect.right.coerceIn(plotRect.left, plotRect.right)
        val crossPosition = activeNativeCrosshair.position
        val rawPosition = activeNativeCrosshair.rawPosition

        if (drawBuiltInPrimitives) {
        val verticalKey = KlineCrossVerticalLayerKey(
            top = layout.chartRect.top,
            bottom = layout.chartRect.bottom,
            stroke = crossConfig.stroke,
            color = appliedStyle.crosshair,
        )
        val crossStrokePadding = max(
            1f,
            logicalPx(crossConfig.stroke.widthPx) / 2f + 1f,
        )
        val verticalLength = (layout.chartRect.bottom - layout.chartRect.top).coerceAtLeast(0f)
        val verticalLayerSize = IntSize(
            width = kotlin.math.ceil(crossStrokePadding * 2f).toInt().coerceAtLeast(1),
            height = kotlin.math.ceil(verticalLength + crossStrokePadding * 2f).toInt().coerceAtLeast(1),
        )
        if (crossVerticalLayerCache.needsRecord(verticalKey, verticalLayerSize)) {
            traceKlineSection("Kanvas.cross.vertical.record") {
                crossVerticalLayer.record(
                    density = this,
                    layoutDirection = layoutDirection,
                    size = verticalLayerSize,
                ) {
                    drawCrossVerticalLine(
                        x = verticalLayerSize.width / 2f,
                        top = crossStrokePadding,
                        bottom = crossStrokePadding + verticalLength,
                        style = appliedStyle,
                        config = crossConfig,
                    )
                }
            }
            crossVerticalLayerCache.markRecorded(verticalKey, verticalLayerSize)
        }
        crossVerticalLayer.translationX = crossPosition.x - verticalLayerSize.width / 2f
        crossVerticalLayer.translationY = layout.chartRect.top - crossStrokePadding
        drawLayer(crossVerticalLayer)

        val horizontalKey = KlineCrossHorizontalLayerKey(
            left = plotRect.left,
            right = effectiveRight,
            stroke = crossConfig.stroke,
            color = appliedStyle.crosshair,
        )
        val horizontalLength = (effectiveRight - plotRect.left).coerceAtLeast(0f)
        val horizontalLayerSize = IntSize(
            width = kotlin.math.ceil(horizontalLength + crossStrokePadding * 2f).toInt().coerceAtLeast(1),
            height = kotlin.math.ceil(crossStrokePadding * 2f).toInt().coerceAtLeast(1),
        )
        if (crossHorizontalLayerCache.needsRecord(horizontalKey, horizontalLayerSize)) {
            traceKlineSection("Kanvas.cross.horizontal.record") {
                crossHorizontalLayer.record(
                    density = this,
                    layoutDirection = layoutDirection,
                    size = horizontalLayerSize,
                ) {
                    drawCrossHorizontalLine(
                        y = horizontalLayerSize.height / 2f,
                        left = crossStrokePadding,
                        right = crossStrokePadding + horizontalLength,
                        style = appliedStyle,
                        config = crossConfig,
                    )
                }
            }
            crossHorizontalLayerCache.markRecorded(horizontalKey, horizontalLayerSize)
        }
        crossHorizontalLayer.translationX = plotRect.left - crossStrokePadding
        crossHorizontalLayer.translationY = rawPosition.y - horizontalLayerSize.height / 2f
        drawLayer(crossHorizontalLayer)

        if (crossConfig.pointRadiusPx > 0f) {
            val pointKey = KlineCrossPointLayerKey(crossConfig, appliedStyle)
            val pointPadding = logicalPx(crossConfig.pointRadiusPx) +
                logicalPx(crossConfig.pointBorderWidthPx) / 2f + 1f
            val pointLayerSize = IntSize(
                width = kotlin.math.ceil(pointPadding * 2f).toInt().coerceAtLeast(1),
                height = kotlin.math.ceil(pointPadding * 2f).toInt().coerceAtLeast(1),
            )
            if (crossPointLayerCache.needsRecord(pointKey, pointLayerSize)) {
                traceKlineSection("Kanvas.cross.point.record") {
                    crossPointLayer.record(
                        density = this,
                        layoutDirection = layoutDirection,
                        size = pointLayerSize,
                    ) {
                        drawCrossPoint(
                            position = Offset(pointLayerSize.width / 2f, pointLayerSize.height / 2f),
                            style = appliedStyle,
                            config = crossConfig,
                        )
                    }
                }
                crossPointLayerCache.markRecorded(pointKey, pointLayerSize)
            }
            crossPointLayer.translationX = crossPosition.x - pointLayerSize.width / 2f
            crossPointLayer.translationY = crossPosition.y - pointLayerSize.height / 2f
            drawLayer(crossPointLayer)
        }
        }

        val precision = interactionState.spec?.precision ?: KlineSpec.DefaultPrecision
        if (drawMainPriceLabel) {
        val crossValue = range.valueAt(rawPosition.y, plotRect)
        val priceText = formatPrice(crossValue, precision)
        val priceKey = KlineCrossPriceLayerKey(
            text = priceText,
            plotRect = plotRect,
            config = crossConfig,
            style = appliedStyle,
            textMeasurer = textMeasurer,
        )
        val priceLayerSize = crossPriceLayerCache.recordedSizeFor(priceKey) ?: run {
            val measuredSize = measureKlineTextAreaSize(
                text = priceText,
                config = crossConfig.ticksText,
                textMeasurer = textMeasurer,
                fallbackTextColor = appliedStyle.crossTextColor,
            )
            IntSize(
                width = kotlin.math.ceil(plotRect.width).toInt().coerceAtLeast(1),
                height = kotlin.math.ceil(measuredSize.height).toInt()
                    .coerceIn(1, kotlin.math.ceil(plotRect.height).toInt().coerceAtLeast(1)),
            )
        }
        if (crossPriceLayerCache.needsRecord(priceKey, priceLayerSize)) {
            traceKlineSection("Kanvas.cross.price.record") {
                crossPriceLayer.record(
                    density = this,
                    layoutDirection = layoutDirection,
                    size = priceLayerSize,
                ) {
                    drawCrossPriceLabel(
                        text = priceText,
                        y = priceLayerSize.height / 2f,
                        plotRect = Rect(
                            left = 0f,
                            top = 0f,
                            right = plotRect.width,
                            bottom = priceLayerSize.height.toFloat(),
                        ),
                        style = appliedStyle,
                        config = crossConfig,
                        textMeasurer = textMeasurer,
                    )
                }
            }
            crossPriceLayerCache.markRecorded(priceKey, priceLayerSize)
        }
        val priceLayerTop = (rawPosition.y - priceLayerSize.height / 2f).coerceIn(
            plotRect.top,
            (plotRect.bottom - priceLayerSize.height).coerceAtLeast(plotRect.top),
        )
        crossPriceLayer.translationX = plotRect.left
        crossPriceLayer.translationY = priceLayerTop
        drawLayer(crossPriceLayer)
        }

        val selection = KlineIndicatorTopTipsSelection.Cross(activeNativeCrosshair)
        layout.subPanes.forEach { pane ->
            drawSubPaneCrossValue(
                pane = pane,
                frames = indicatorFrames.subByPane[pane.id].orEmpty(),
                pointer = activeNativeCrosshair.inputPosition,
                style = appliedStyle,
                config = crossConfig,
                precision = precision,
                textMeasurer = textMeasurer,
            )
            drawIndicatorCross(
                frames = indicatorFrames.subByPane[pane.id].orEmpty(),
                layout = layout,
                crosshair = activeNativeCrosshair,
            )
            drawSubTopTips(paneId = pane.id, selection = selection)
        }
        drawIndicatorCross(
            frames = indicatorFrames.main,
            layout = layout,
            crosshair = activeNativeCrosshair,
        )

        crossTooltipPresentation?.let { presentation ->
            val selectionKey = KlineCrossSelectionLayerKey(
                candleIndex = activeNativeCrosshair.candleIndex,
                candle = activeNativeCrosshair.candle,
                previousCandle = activeNativeCrosshair.previousCandle,
                snappedX = activeNativeCrosshair.position.x,
                stateSpec = interactionState.spec,
                layout = layout,
                config = crossConfig,
                style = appliedStyle,
                tooltipPresentation = KlineIdentityRef(presentation),
                formatter = KlineIdentityRef(appliedTimeLabelFormatter),
                textMeasurer = textMeasurer,
            )
            val card = presentation.layout.cardBounds
            val tooltipPadding = max(
                1f,
                logicalPx(presentation.textArea?.borderWidthPx ?: 0f) / 2f + 1f,
            )
            val tooltipLeft = kotlin.math.floor(card.left - tooltipPadding).toInt()
            val tooltipTop = kotlin.math.floor(card.top - tooltipPadding).toInt()
            val tooltipRight = kotlin.math.ceil(card.right + tooltipPadding).toInt()
            val tooltipBottom = kotlin.math.ceil(card.bottom + tooltipPadding).toInt()
            val tooltipLayerSize = IntSize(
                width = (tooltipRight - tooltipLeft).coerceAtLeast(1),
                height = (tooltipBottom - tooltipTop).coerceAtLeast(1),
            )
            if (crossSelectionLayerCache.needsRecord(selectionKey, tooltipLayerSize)) {
                traceKlineSection("Kanvas.cross.tooltip.record") {
                    crossSelectionLayer.record(
                        density = this,
                        layoutDirection = layoutDirection,
                        size = tooltipLayerSize,
                    ) {
                        translate(
                            left = -tooltipLeft.toFloat(),
                            top = -tooltipTop.toFloat(),
                        ) {
                            drawKlineCrossTooltip(presentation)
                        }
                    }
                }
                crossSelectionLayerCache.markRecorded(selectionKey, tooltipLayerSize)
            }
            crossSelectionLayer.translationX = tooltipLeft.toFloat()
            crossSelectionLayer.translationY = tooltipTop.toFloat()
            drawLayer(crossSelectionLayer)
        }

        val selectedCandle = activeNativeCrosshair.candle
        layout.timePane?.takeIf {
            selectedCandle != null && interactionState.spec?.interval?.isValid != false
        }?.let { timePane ->
            val timeText = appliedTimeLabelFormatter(
                checkNotNull(selectedCandle),
                interactionState.spec?.interval,
            )
            val timeKey = KlineCrossTimeLayerKey(
                text = timeText,
                timeRect = timePane.plotRect,
                config = crossConfig,
                style = appliedStyle,
                textMeasurer = textMeasurer,
            )
            val timeLayerSize = crossTimeLayerCache.recordedSizeFor(timeKey) ?: run {
                val measuredSize = measureKlineTextAreaSize(
                    text = timeText,
                    config = crossConfig.ticksText,
                    textMeasurer = textMeasurer,
                    fallbackTextColor = appliedStyle.crossTextColor,
                )
                IntSize(
                    width = kotlin.math.ceil(measuredSize.width).toInt()
                        .coerceIn(1, kotlin.math.ceil(timePane.plotRect.width).toInt().coerceAtLeast(1)),
                    height = kotlin.math.ceil(measuredSize.height).toInt()
                        .coerceIn(1, kotlin.math.ceil(timePane.plotRect.height).toInt().coerceAtLeast(1)),
                )
            }
            if (crossTimeLayerCache.needsRecord(timeKey, timeLayerSize)) {
                traceKlineSection("Kanvas.cross.time.record") {
                    crossTimeLayer.record(
                        density = this,
                        layoutDirection = layoutDirection,
                        size = timeLayerSize,
                    ) {
                        drawCrossTimeLabelText(
                            text = timeText,
                            snappedX = timeLayerSize.width / 2f,
                            timeRect = Rect(
                                left = 0f,
                                top = 0f,
                                right = timeLayerSize.width.toFloat(),
                                bottom = timeLayerSize.height.toFloat(),
                            ),
                            style = appliedStyle,
                            textMeasurer = textMeasurer,
                            config = crossConfig,
                        )
                    }
                }
                crossTimeLayerCache.markRecorded(timeKey, timeLayerSize)
            }
            val timeLayerLeft = (activeNativeCrosshair.position.x - timeLayerSize.width / 2f).coerceIn(
                timePane.plotRect.left,
                (timePane.plotRect.right - timeLayerSize.width).coerceAtLeast(timePane.plotRect.left),
            )
            val timeLayerTop = (timePane.plotRect.center.y - timeLayerSize.height / 2f).coerceIn(
                timePane.plotRect.top,
                (timePane.plotRect.bottom - timeLayerSize.height).coerceAtLeast(timePane.plotRect.top),
            )
            crossTimeLayer.translationX = timeLayerLeft
            crossTimeLayer.translationY = timeLayerTop
            drawLayer(crossTimeLayer)
        }
    }

    Layout(
        modifier = chartModifier,
        measurePolicy = chartMeasurePolicy,
        content = {
        val slotContext = reportedLayout?.let { layout ->
            KlineChartSlotContext(state = state, layout = layout, style = appliedStyle)
        }
        if (slotContext != null && mainBackgroundContent != null) {
            Box(Modifier.layoutId("main-background")) {
                mainBackgroundContent(slotContext)
            }
        }
        Canvas(Modifier.fillMaxSize().layoutId("chart-canvas")) {
            // High-level controller viewport emissions are read in DrawScope,
            // so gesture frames invalidate Canvas without recomposing the
            // complete chart function.
            val state = liveChartState?.value ?: state
            // Capture the host's thread-safe invalidate epoch so asynchronous
            // stateful renderers can request a new Canvas frame without
            // mutating Compose state from DrawScope.
            if (lifecycleEpoch < 0L || gestureValueRangeEpoch < 0L) {
                if (!appliedPaneConfig.drawBelowTipsArea) interactionDrawGeometryRef.clear()
                return@Canvas
            }
            val baseLayout = reportedLayout?.takeIf {
                canvasSize.width.toFloat() == size.width && canvasSize.height.toFloat() == size.height
            } ?: resolveChartLayout(
                canvasSize = size,
                axisWidthPx = axisWidthPx,
                timeAxisHeightPx = timeAxisHeightPx,
                densityScale = densityScale,
                paneConfig = appliedPaneConfig,
                subPaneSpecs = indicatorSubPaneSpecs,
            )
            drawRect(appliedStyle.background)
            if (watermarkConfig?.layer == KanvasWatermarkLayer.BehindContent) {
                drawKanvasWatermark(watermarkConfig, baseLayout, textMeasurer)
            }
            val basePlotRect = baseLayout.mainPane.plotRect
            if (basePlotRect.width <= 0f || basePlotRect.height <= 0f || state.series.isEmpty) {
                if (watermarkConfig?.layer == KanvasWatermarkLayer.AboveContent) {
                    drawKanvasWatermark(watermarkConfig, baseLayout, textMeasurer)
                }
                if (!appliedPaneConfig.drawBelowTipsArea) interactionDrawGeometryRef.clear()
                return@Canvas
            }
            val hideMainIndicators = hideMainIndicatorsInLineChartMode && isLineLikeMainChart
            val exactBaseMainRenderRange = traceKlineSection("Kanvas.range.resolve") {
                mainRenderRangeCache.resolve(
                    state = state,
                    layout = baseLayout,
                    panePlan = indicatorPanePlan,
                    renderConfig = renderConfig,
                    hideMainIndicators = hideMainIndicators,
                    densityScale = densityScale,
                )
            } ?: run {
                if (!appliedPaneConfig.drawBelowTipsArea) interactionDrawGeometryRef.clear()
                return@Canvas
            }
            val viewportDrivenRangeUpdate = gestureValueRangeRef.hasViewportChanged(state.viewport)
            val seriesOnlyRealtimeRangeUpdate =
                rangeUpdateRef.realtimeMode &&
                    rangeUpdateRef.series === state.series &&
                    rangeUpdateRef.viewport == state.viewport
            val currentRangeSmoothFactor = when {
                viewportDrivenRangeUpdate -> panRangeSmoothFactor
                gestureValueRangeRef.isActive -> 1f
                seriesOnlyRealtimeRangeUpdate ->
                    min(panRangeSmoothFactor, renderConfig.latestCandleRangeSmoothFactor)
                else -> panRangeSmoothFactor
            }
            val baseMainRenderRange = exactBaseMainRenderRange.copy(
                valueRange = gestureValueRangeRef.resolve(
                    target = mainRangeSmoother.resolve(
                        target = exactBaseMainRenderRange.valueRange,
                        smoothFactor = currentRangeSmoothFactor,
                        resetKey = state.spec?.key,
                    ),
                    viewport = state.viewport,
                    freezeRealtimeTick = seriesOnlyRealtimeRangeUpdate,
                ),
            )
            val needsMainTipsPreflight = appliedPaneConfig.drawBelowTipsArea && !isChartZooming
            val baseIndicatorFrames = traceKlineSection("Kanvas.indicator.frames") {
                indicatorRenderFramesCache.resolve(
                    state = state,
                    layout = baseLayout,
                    panePlan = indicatorPanePlan,
                    paintRange = baseMainRenderRange.paintRange,
                    mainValueRange = baseMainRenderRange.valueRange,
                    style = appliedStyle,
                    densityScale = densityScale,
                    hideMainIndicators = hideMainIndicators,
                    sameValueExpansionRatios = renderConfig.sameValueRangeExpansionRatios,
                    subPaneRangeSmoothers = subPaneRangeSmoothers,
                    subPaneRangeResetKey = state.spec?.key,
                    subPaneRangeTransition = historicalRangeTransition,
                    // The preflight needs contexts for Top Tips measurement, not
                    // line Paths. Prepare the drawable geometry only after the
                    // final inset layout is known.
                    prepareComputedLines = !needsMainTipsPreflight,
                    includeSubPanes = !needsMainTipsPreflight,
                )
            }
            val baseCrosshair = if (needsMainTipsPreflight) {
                crosshairState.value
                    ?.takeIf { renderConfig.crosshair.enabled }
                    ?.let { inputPosition ->
                        resolveKlineIndicatorCrosshairContext(
                            inputPosition = inputPosition,
                            state = state,
                            plotRect = basePlotRect,
                            renderConfig = renderConfig.crosshair,
                            crosshairRightPx = baseLayout.mainPane.plotRect.right,
                        )
                    }
            } else {
                null
            }
            val firstTipsSelection = baseCrosshair?.let(KlineIndicatorTopTipsSelection::Cross)
                ?: KlineIndicatorTopTipsSelection.Latest(state.series.latest)
            val firstMainTipsPlan = if (needsMainTipsPreflight) {
                resolveKlineIndicatorTopTipsRenderPlan(
                    frames = baseIndicatorFrames.main,
                    chartLayout = baseLayout,
                    selection = firstTipsSelection,
                    textMeasurer = textMeasurer,
                    stackMainTips = true,
                )
            } else {
                null
            }
            val layout = firstMainTipsPlan
                ?.let { baseLayout.withMainTipsInset(it.totalClaimedHeightPx) }
                ?: baseLayout
            val plotRect = layout.mainPane.plotRect
            if (plotRect.width <= 0f || plotRect.height <= 0f) {
                if (!appliedPaneConfig.drawBelowTipsArea) interactionDrawGeometryRef.clear()
                return@Canvas
            }
            // Tips only shifts the vertical top of the main pane. Horizontal
            // visibility and source values are unchanged, so the base range
            // (and its one smoother update) is the final range as well.
            val mainRenderRange = baseMainRenderRange
            val paintRange = mainRenderRange.paintRange
            val range = mainRenderRange.valueRange
            val indicatorFrames = if (layout === baseLayout && !needsMainTipsPreflight) {
                baseIndicatorFrames
            } else {
                traceKlineSection("Kanvas.indicator.frames.final") {
                    indicatorRenderFramesCache.resolve(
                        state = state,
                        layout = layout,
                        panePlan = indicatorPanePlan,
                        paintRange = paintRange,
                        mainValueRange = range,
                        style = appliedStyle,
                        densityScale = densityScale,
                        hideMainIndicators = hideMainIndicators,
                        sameValueExpansionRatios = renderConfig.sameValueRangeExpansionRatios,
                        subPaneRangeSmoothers = subPaneRangeSmoothers,
                        subPaneRangeResetKey = state.spec?.key,
                        subPaneRangeTransition = historicalRangeTransition,
                        prepareComputedLines = true,
                    )
                }
            }
            if (!needsMainTipsPreflight) {
                interactionDrawGeometryRef.update(
                    KlineInteractionDrawGeometry(
                        state = state,
                        layout = layout,
                        valueRange = range,
                        indicatorFrames = indicatorFrames,
                    ),
                )
            }
            firstMainTipsPlan?.let { plan ->
                drawKlineIndicatorTopTipsRenderPlan(
                    plan = plan,
                    chartLayout = baseLayout,
                    selection = firstTipsSelection,
                    textMeasurer = textMeasurer,
                )
            }

            val precision = state.spec?.precision ?: KlineSpec.DefaultPrecision
            val canCacheWholeMainBody = indicatorFrames.main.all { frame ->
                frame.item.renderer.cachePolicy == KlineIndicatorCachePolicy.ImmutableInputs
            } && (mainChartRenderer == null ||
                mainChartRenderer.cachePolicy == KlineMainChartCachePolicy.ImmutableInputs)
            // The built-in candle pipeline never inherits a third-party
            // renderer's cache failure. Custom indicators are partitioned
            // into isolated cached and dynamic phases below.
            val canUseBuiltInMainPipeline = mainChartRenderer == null
            val panPaintBucket = if (canUseBuiltInMainPipeline) {
                resolveKlinePanPaintBucket(
                    currentPaintRange = paintRange,
                    candleCount = state.series.size,
                    plotWidthPx = plotRect.width,
                    candleStepPx = state.viewport.candleStepPx,
                )
            } else {
                KlinePanPaintBucket(paintRange = paintRange, horizontalOverscanPx = 0f)
            }
            val mainBodyPaintRange = panPaintBucket.paintRange
            val mainBodyHorizontalOverscanPx = panPaintBucket.horizontalOverscanPx
            val mainBodyIndicatorFrames = if (
                canUseBuiltInMainPipeline && mainBodyPaintRange != paintRange
            ) {
                traceKlineSection("Kanvas.indicator.frames.pan-bucket") {
                    translatableMainFramesCache.resolve(
                        state = state,
                        layout = layout,
                        panePlan = indicatorPanePlan,
                        paintRange = mainBodyPaintRange,
                        mainValueRange = range,
                        style = appliedStyle,
                        densityScale = densityScale,
                        hideMainIndicators = hideMainIndicators,
                        sameValueExpansionRatios = renderConfig.sameValueRangeExpansionRatios,
                        subPaneRangeSmoothers = null,
                        subPaneRangeResetKey = state.spec?.key,
                        subPaneRangeTransition = null,
                        prepareComputedLines = true,
                        includeSubPanes = false,
                    )
                }
            } else {
                indicatorFrames
            }
            fun KlineIndicatorRenderFrame.isBuiltInFastFrame(): Boolean =
                item.definition.paintMode == com.zhumeng.kanvas.core.IndicatorPaintMode.COMBINE &&
                    item.renderer.builtInComputedLineRendererOrNull() != null

            val builtInFastMainFrames = mainBodyIndicatorFrames.main.filter { it.isBuiltInFastFrame() }
            val isolatedMainFrames = indicatorFrames.main.filterNot { it.isBuiltInFastFrame() }
            val isolatedBelowFrames = isolatedMainFrames.filter {
                it.item.definition.zIndex < appliedCandleZIndex
            }
            val isolatedAboveFrames = isolatedMainFrames.filter {
                it.item.definition.zIndex >= appliedCandleZIndex
            }
            val fixedLayerSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
            val gridLayerKey = KlineMainGridLayerKey(
                plotRect = plotRect,
                style = appliedStyle,
                grid = renderConfig.grid,
                textMeasurer = textMeasurer,
            )
            val axisLayerKey = KlineMainAxisLayerKey(
                plotRect = plotRect,
                valueRange = range,
                style = appliedStyle,
                grid = renderConfig.grid,
                precision = precision,
                textMeasurer = textMeasurer,
            )
            if (mainGridLayerCache.needsRecord(gridLayerKey, fixedLayerSize)) {
                traceKlineSection("Kanvas.grid.layer.record") {
                    mainGridLayer.record(
                        density = this,
                        layoutDirection = layoutDirection,
                        size = fixedLayerSize,
                    ) {
                        drawGrid(
                            plotRect = plotRect,
                            style = appliedStyle,
                            config = renderConfig.grid,
                            textMeasurer = textMeasurer,
                            range = range,
                            precision = precision,
                            includeBottomAxisLabel = true,
                            drawYAxisTicks = false,
                        )
                    }
                }
                mainGridLayerCache.markRecorded(gridLayerKey, fixedLayerSize)
            }
            drawLayer(mainGridLayer)

            fun DrawScope.drawMainIndicatorItems(
                frames: List<KlineIndicatorRenderFrame>,
                horizontalClipOverscanPx: Float,
            ) {
                drawMainIndicatorFrames(
                    mainPane = layout.mainPane,
                    frames = frames,
                    style = appliedStyle,
                    grid = renderConfig.grid,
                    textMeasurer = textMeasurer,
                    precision = precision,
                    horizontalClipOverscanPx = horizontalClipOverscanPx,
                )
            }

            fun DrawScope.drawMainBody(
                bodyPaintRange: IndexRange,
                bodyFrames: List<KlineIndicatorRenderFrame>,
                horizontalClipOverscanPx: Float,
            ) {
                // Treat the candle body plus high/low marks or the line latest
                // point as one rendering phase. Keep them as one z-index unit
                // relative to Main indicators.
                fun DrawScope.drawCandleChartPhase() {
                    traceKlineSection("Kanvas.candle.draw") {
                        val mainChartContext = KlineMainChartDrawContext(
                            state = state,
                            paintRange = bodyPaintRange,
                            plotRect = plotRect,
                            valueRange = range,
                            style = appliedStyle,
                            densityScale = densityScale,
                        )
                        clipRect(
                            plotRect.left - horizontalClipOverscanPx,
                            plotRect.top,
                            plotRect.right + horizontalClipOverscanPx,
                            plotRect.bottom,
                        ) {
                            if (mainChartRenderer != null) {
                                mainChartRenderer.drawChart(this, mainChartContext)
                            } else {
                                when (appliedChartType) {
                                    is KlineChartType.Bar -> drawBars(
                                        state.series.candles,
                                        bodyPaintRange,
                                        plotRect,
                                        range,
                                        state.viewport,
                                        appliedChartType.style,
                                        appliedStyle,
                                        renderConfig.candle,
                                    )
                                    is KlineChartType.Line -> drawLineChart(
                                        state.series.candles,
                                        bodyPaintRange,
                                        plotRect,
                                        range,
                                        state.viewport,
                                        appliedChartType.style,
                                        appliedStyle,
                                        renderConfig.candle,
                                        appliedCandleGradients,
                                    )
                                }
                            }
                        }
                    }
                }

                if (hideMainIndicators) {
                    drawCandleChartPhase()
                } else {
                    val split = bodyFrames.indexOfFirst { frame ->
                        frame.item.definition.zIndex >= appliedCandleZIndex
                    }.let { index -> if (index < 0) bodyFrames.size else index }
                    drawMainIndicatorItems(bodyFrames.subList(0, split), horizontalClipOverscanPx)
                    drawCandleChartPhase()
                    drawMainIndicatorItems(
                        bodyFrames.subList(split, bodyFrames.size),
                        horizontalClipOverscanPx,
                    )
                }
            }
            val drawFullMainBody: DrawScope.() -> Unit = {
                drawMainBody(
                    bodyPaintRange = mainBodyPaintRange,
                    bodyFrames = mainBodyIndicatorFrames.main,
                    horizontalClipOverscanPx = mainBodyHorizontalOverscanPx,
                )
            }
            val mainBodyKey = KlineMainBodyLayerKey(
                state = state,
                layout = layout,
                renderRange = mainRenderRange,
                frames = indicatorFrames.main,
                chartType = appliedChartType,
                mainChartRenderer = mainChartRenderer,
                candleZIndex = appliedCandleZIndex,
                style = appliedStyle,
                renderConfig = renderConfig,
                candleOverlayConfig = appliedCandleOverlayConfig,
                candleGradients = appliedCandleGradients,
                densityScale = densityScale,
                hideMainIndicators = hideMainIndicators,
                textMeasurer = textMeasurer,
            )
            val layerOverscanInt = kotlin.math.ceil(mainBodyHorizontalOverscanPx.toDouble())
                .toInt()
                .coerceAtLeast(0)
            val mainLayerLeft = kotlin.math.floor(plotRect.left.toDouble()).toInt()
            val mainLayerTop = kotlin.math.floor(plotRect.top.toDouble()).toInt()
            val mainLayerRight = kotlin.math.ceil(plotRect.right.toDouble()).toInt()
            val mainLayerBottom = kotlin.math.ceil(plotRect.bottom.toDouble()).toInt()
            val mainBodySize = IntSize(
                width = (mainLayerRight - mainLayerLeft + layerOverscanInt * 2).coerceAtLeast(1),
                height = (mainLayerBottom - mainLayerTop).coerceAtLeast(1),
            )
            val fullTranslatableLayerSize = IntSize(
                width = fixedLayerSize.width + layerOverscanInt * 2,
                height = fixedLayerSize.height,
            )

            fun DrawScope.drawIsolatedIndicatorPhase(
                frames: List<KlineIndicatorRenderFrame>,
                layer: GraphicsLayer,
                cache: KlineRecordedLayerCache,
                traceName: String,
            ) {
                if (frames.isEmpty()) return
                val cacheable = frames.filter {
                    it.item.renderer.cachePolicy == KlineIndicatorCachePolicy.ImmutableInputs
                }
                val dynamic = frames.filter {
                    it.item.renderer.cachePolicy == KlineIndicatorCachePolicy.Never
                }
                if (cacheable.isNotEmpty()) {
                    val key = KlineCachedIndicatorLayerKey(
                        state = state,
                        layout = layout,
                        renderRange = mainRenderRange,
                        frames = cacheable,
                        style = appliedStyle,
                        grid = renderConfig.grid,
                        precision = precision,
                        textMeasurer = textMeasurer,
                    )
                    if (cache.needsRecord(key, fixedLayerSize)) {
                        traceKlineSection(traceName) {
                            layer.record(
                                density = this,
                                layoutDirection = layoutDirection,
                                size = fixedLayerSize,
                            ) {
                                drawMainIndicatorItems(cacheable, horizontalClipOverscanPx = 0f)
                            }
                        }
                        cache.markRecorded(key, fixedLayerSize)
                    }
                    drawLayer(layer)
                }
                if (dynamic.isNotEmpty()) {
                    // A Never renderer keeps its mutable draw semantics, but
                    // cannot invalidate the candle or immutable indicator nodes.
                    traceKlineSection("Kanvas.indicator.dynamic.draw") {
                        drawMainIndicatorItems(dynamic, horizontalClipOverscanPx = 0f)
                    }
                }
            }

            if (canUseBuiltInMainPipeline) {
                drawIsolatedIndicatorPhase(
                    frames = isolatedBelowFrames,
                    layer = cacheableIndicatorBelowLayer,
                    cache = cacheableIndicatorBelowLayerCache,
                    traceName = "Kanvas.indicator.cache.below.record",
                )
            }
            if (canUseBuiltInMainPipeline) {
                val layerRanges = resolveKlineRealtimeLayerRanges(
                    paintRange = mainBodyPaintRange,
                    candleCount = state.series.size,
                    lineLike = appliedChartType is KlineChartType.Line ||
                        builtInFastMainFrames.isNotEmpty(),
                )
                val historicalFrames = builtInFastMainFrames.map { frame ->
                    frame.copy(
                        context = frame.context.copy(paintRange = layerRanges.historical),
                        preparedLines = null,
                        preparedLineTranslationX = 0f,
                    )
                }
                val historicalKey = KlineHistoricalMainBodyLayerKey(
                    series = state.series,
                    frames = historicalFrames,
                    layout = layout,
                    renderRange = KlineMainRenderRange(layerRanges.historical, range),
                    candleWidthPx = state.viewport.candleWidthPx,
                    candleSpacingPx = state.viewport.candleSpacingPx,
                    chartType = appliedChartType,
                    candleZIndex = appliedCandleZIndex,
                    style = appliedStyle,
                    renderConfig = renderConfig,
                    candleOverlayConfig = appliedCandleOverlayConfig,
                    candleGradients = appliedCandleGradients,
                    densityScale = densityScale,
                    hideMainIndicators = hideMainIndicators,
                    precision = precision,
                    textMeasurer = textMeasurer,
                )
                var historicalTranslationX = historicalMainBodyLayerCache.translationFor(
                    key = historicalKey,
                    size = mainBodySize,
                    currentRightEdgeOffsetPx = state.viewport.rightEdgeOffsetPx,
                    maximumTranslationPx = mainBodyHorizontalOverscanPx,
                )
                if (historicalTranslationX == null) {
                    traceKlineSection("Kanvas.main.history.layer.record") {
                        historicalMainBodyLayer.record(
                            density = this,
                            layoutDirection = layoutDirection,
                            size = mainBodySize,
                        ) {
                            translate(
                                left = layerOverscanInt - mainLayerLeft.toFloat(),
                                top = -mainLayerTop.toFloat(),
                            ) {
                                drawMainBody(
                                    bodyPaintRange = layerRanges.historical,
                                    bodyFrames = historicalFrames,
                                    horizontalClipOverscanPx = mainBodyHorizontalOverscanPx,
                                )
                            }
                        }
                    }
                    historicalMainBodyLayerCache.markRecorded(
                        key = historicalKey,
                        size = mainBodySize,
                        rightEdgeOffsetPx = state.viewport.rightEdgeOffsetPx,
                    )
                    historicalTranslationX = 0f
                }
                historicalMainBodyLayer.translationX =
                    historicalTranslationX + mainLayerLeft - layerOverscanInt
                historicalMainBodyLayer.translationY = mainLayerTop.toFloat()
                drawLayer(historicalMainBodyLayer)

                if (!layerRanges.patch.isEmpty) {
                    val patchFrames = builtInFastMainFrames.map { frame ->
                        frame.copy(
                            context = frame.context.copy(paintRange = layerRanges.patch),
                            preparedLines = null,
                            preparedLineTranslationX = 0f,
                        )
                    }
                    val patchPaddingPx = max(
                        state.viewport.candleStepPx,
                        4f * densityScale,
                    )
                    val newestX = state.viewport.xForIndex(plotRect.right, 0.0) -
                        state.viewport.candleHalfStepPx
                    val oldestPatchIndex = layerRanges.patch.endExclusive - 1
                    val oldestPatchX = state.viewport.xForIndex(
                        plotRect.right,
                        oldestPatchIndex.toDouble(),
                    ) - state.viewport.candleHalfStepPx
                    val patchLeft = kotlin.math.floor(
                        min(newestX, oldestPatchX) - patchPaddingPx,
                    ).toInt()
                    val patchRight = kotlin.math.ceil(
                        max(newestX, oldestPatchX) + patchPaddingPx,
                    ).toInt()
                    val patchSize = IntSize(
                        width = (patchRight - patchLeft).coerceAtLeast(1),
                        height = (mainLayerBottom - mainLayerTop).coerceAtLeast(1),
                    )
                    val patchKey = KlineTranslatableMainBodyLayerKey(
                        series = state.series,
                        indicatorInputs = builtInFastMainFrames.map { frame ->
                            KlineIndicatorPatchInputKey(
                                definition = frame.item.definition,
                                renderer = KlineIdentityRef(frame.item.renderer),
                                output = KlineIdentityRef(frame.item.output),
                                colorIndex = frame.item.colorIndex,
                            )
                        },
                        layout = layout,
                        renderRange = KlineMainRenderRange(layerRanges.patch, range),
                        candleWidthPx = state.viewport.candleWidthPx,
                        candleSpacingPx = state.viewport.candleSpacingPx,
                        chartType = appliedChartType,
                        candleZIndex = appliedCandleZIndex,
                        style = appliedStyle,
                        renderConfig = renderConfig,
                        candleOverlayConfig = appliedCandleOverlayConfig,
                        candleGradients = appliedCandleGradients,
                        densityScale = densityScale,
                        hideMainIndicators = hideMainIndicators,
                        precision = precision,
                        textMeasurer = textMeasurer,
                    )
                    var patchTranslationX = realtimeMainPatchLayerCache.translationFor(
                        key = patchKey,
                        size = patchSize,
                        currentRightEdgeOffsetPx = state.viewport.rightEdgeOffsetPx,
                        maximumTranslationPx = Float.POSITIVE_INFINITY,
                    )
                    if (patchTranslationX == null) {
                        traceKlineSection("Kanvas.main.realtime.patch.record") {
                            realtimeMainPatchLayer.record(
                                density = this,
                                layoutDirection = layoutDirection,
                                size = patchSize,
                            ) {
                                translate(
                                    left = -patchLeft.toFloat(),
                                    top = -mainLayerTop.toFloat(),
                                ) {
                                    drawMainBody(
                                        bodyPaintRange = layerRanges.patch,
                                        bodyFrames = patchFrames,
                                        horizontalClipOverscanPx = patchPaddingPx,
                                    )
                                }
                            }
                        }
                        realtimeMainPatchLayerCache.markRecorded(
                            key = patchKey,
                            size = patchSize,
                            rightEdgeOffsetPx = state.viewport.rightEdgeOffsetPx,
                        )
                        patchTranslationX = 0f
                    }
                    // The patch's local display list is independent of its X
                    // anchor. During pan, RenderThread moves this narrow node;
                    // only a market-data change re-records its contents.
                    realtimeMainPatchLayer.translationX = patchLeft.toFloat()
                    realtimeMainPatchLayer.translationY = mainLayerTop.toFloat()
                    drawLayer(realtimeMainPatchLayer)
                }
            } else if (canCacheWholeMainBody) {
                var layerTranslationX = mainBodyLayerCache.translationFor(
                    key = mainBodyKey,
                    size = mainBodySize,
                    currentRightEdgeOffsetPx = state.viewport.rightEdgeOffsetPx,
                    maximumTranslationPx = mainBodyHorizontalOverscanPx,
                )
                if (layerTranslationX == null) {
                    traceKlineSection("Kanvas.main.layer.record") {
                        mainBodyLayer.record(
                            density = this,
                            layoutDirection = layoutDirection,
                            size = mainBodySize,
                        ) {
                            translate(
                                left = layerOverscanInt - mainLayerLeft.toFloat(),
                                top = -mainLayerTop.toFloat(),
                            ) {
                                drawFullMainBody()
                            }
                        }
                    }
                    mainBodyLayerCache.markRecorded(
                        key = mainBodyKey,
                        size = mainBodySize,
                        rightEdgeOffsetPx = state.viewport.rightEdgeOffsetPx,
                    )
                    layerTranslationX = 0f
                }
                mainBodyLayer.translationX = layerTranslationX + mainLayerLeft - layerOverscanInt
                mainBodyLayer.translationY = mainLayerTop.toFloat()
                drawLayer(mainBodyLayer)
            } else {
                // Custom renderers may rely on mutable draw-state observation.
                // Keep their per-frame execution semantics unless they opt in.
                traceKlineSection("Kanvas.main.body.draw") { drawFullMainBody() }
            }
            if (canUseBuiltInMainPipeline) {
                drawIsolatedIndicatorPhase(
                    frames = isolatedAboveFrames,
                    layer = cacheableIndicatorAboveLayer,
                    cache = cacheableIndicatorAboveLayerCache,
                    traceName = "Kanvas.indicator.cache.above.record",
                )
            }

            val drawCandleOverlay: DrawScope.() -> Unit = {
                val exactMainChartContext = KlineMainChartDrawContext(
                    state = state,
                    paintRange = paintRange,
                    plotRect = plotRect,
                    valueRange = range,
                    style = appliedStyle,
                    densityScale = densityScale,
                )
                if (mainChartRenderer != null) {
                    mainChartRenderer.drawOverlay(this, exactMainChartContext)
                } else if (appliedChartType is KlineChartType.Bar) {
                    drawHighLowMarks(
                        candles = state.series.candles,
                        range = paintRange,
                        plotRect = plotRect,
                        values = range,
                        viewport = state.viewport,
                        precision = precision,
                        style = appliedStyle,
                        config = appliedCandleOverlayConfig,
                        textMeasurer = textMeasurer,
                    )
                } else if (appliedChartType is KlineChartType.Line) {
                    drawLatestLinePoint(
                        latest = state.series.latest,
                        plotRect = plotRect,
                        values = range,
                        viewport = state.viewport,
                        style = appliedStyle,
                        config = appliedCandleOverlayConfig,
                    )
                }
            }
            val canCacheCandleOverlay = mainChartRenderer == null ||
                mainChartRenderer.cachePolicy == KlineMainChartCachePolicy.ImmutableInputs
            if (canCacheCandleOverlay) {
                val candleOverlayContent = when {
                    mainChartRenderer != null -> state
                    appliedChartType is KlineChartType.Bar ->
                        resolveKlinePriceExtremeSelection(state.series.candles, paintRange)
                    else -> state.series.latest
                }
                val candleOverlayLayerKey = KlineCandleOverlayLayerKey(
                    content = candleOverlayContent,
                    layout = layout,
                    renderRange = mainRenderRange,
                    chartType = appliedChartType,
                    mainChartRenderer = KlineIdentityRef(mainChartRenderer),
                    style = appliedStyle,
                    config = appliedCandleOverlayConfig,
                    densityScale = densityScale,
                    precision = precision,
                    textMeasurer = textMeasurer,
                )
                if (candleOverlayLayerCache.needsRecord(candleOverlayLayerKey, fixedLayerSize)) {
                    traceKlineSection("Kanvas.candle.overlay.layer.record") {
                        candleOverlayLayer.record(
                            density = this,
                            layoutDirection = layoutDirection,
                            size = fixedLayerSize,
                        ) { drawCandleOverlay() }
                    }
                    candleOverlayLayerCache.markRecorded(candleOverlayLayerKey, fixedLayerSize)
                }
                drawLayer(candleOverlayLayer)
            } else {
                traceKlineSection("Kanvas.candle.overlay.draw") { drawCandleOverlay() }
            }

            if (mainAxisLayerCache.needsRecord(axisLayerKey, fixedLayerSize)) {
                traceKlineSection("Kanvas.axis.layer.record") {
                    mainAxisLayer.record(
                        density = this,
                        layoutDirection = layoutDirection,
                        size = fixedLayerSize,
                    ) {
                        drawYAxisTicks(
                            plotRect = plotRect,
                            style = appliedStyle,
                            config = renderConfig.grid,
                            textMeasurer = textMeasurer,
                            range = range,
                            precision = precision,
                            includeBottomAxisLabel = true,
                            textArea = renderConfig.grid.ticksText,
                        )
                    }
                }
                mainAxisLayerCache.markRecorded(axisLayerKey, fixedLayerSize)
            }
            drawLayer(mainAxisLayer)

            fun drawMainOverlayPhase() {
                fun drawContent() {
                    if (orderMarkers.isNotEmpty()) {
                        val placements = resolveKlineOrderMarkerPlacements(
                            markerIndex = orderMarkerIndex,
                            candles = state.series.candles,
                            paintRange = paintRange,
                            plotRect = plotRect,
                            valueRange = range,
                            viewport = state.viewport,
                            config = orderMarkerConfig,
                            densityScale = densityScale,
                        )
                        val orderKey = KlineOrderMarkerLayerKey(
                            placements = placements,
                            orderMarkerConfig = orderMarkerConfig,
                            style = appliedStyle,
                            densityScale = densityScale,
                        )
                        if (builtInMainOverlayLayerCache.needsRecord(orderKey, fixedLayerSize)) {
                            traceKlineSection("Kanvas.orderMarkers.layer.record") {
                                builtInMainOverlayLayer.record(
                                    density = this,
                                    layoutDirection = layoutDirection,
                                    size = fixedLayerSize,
                                ) {
                                    drawOrderMarkerPlacements(
                                        placements = placements,
                                        config = orderMarkerConfig,
                                        densityScale = densityScale,
                                    )
                                }
                            }
                            builtInMainOverlayLayerCache.markRecorded(orderKey, fixedLayerSize)
                        }
                        drawLayer(builtInMainOverlayLayer)
                    }
                    val latest = state.series.latest
                    val interval = state.spec?.interval
                    val latestPriceKey = KlineLatestPriceLayerKey(
                        latest = latest,
                        interval = interval,
                        countdownText = resolveKlineCountdownText(latest, interval, countdownNowMillis),
                        plotRect = plotRect,
                        valueRange = range,
                        viewport = state.viewport,
                        precision = precision,
                        style = appliedStyle,
                        config = appliedCandleOverlayConfig,
                        textMeasurer = textMeasurer,
                    )
                    if (latestPriceLayerCache.needsRecord(latestPriceKey, fixedLayerSize)) {
                        traceKlineSection("Kanvas.latestPrice.layer.record") {
                            latestPriceLayer.record(
                                density = this,
                                layoutDirection = layoutDirection,
                                size = fixedLayerSize,
                            ) {
                                drawLatestPrice(
                                    latest = latest,
                                    interval = interval,
                                    nowMillis = countdownNowMillis,
                                    plotRect = plotRect,
                                    values = range,
                                    viewport = state.viewport,
                                    precision = precision,
                                    style = appliedStyle,
                                    config = appliedCandleOverlayConfig,
                                    textMeasurer = textMeasurer,
                                )
                            }
                        }
                        latestPriceLayerCache.markRecorded(latestPriceKey, fixedLayerSize)
                    }
                    drawLayer(latestPriceLayer)
                    traceKlineSection("Kanvas.indicator.overlay.dynamic.draw") {
                        drawIndicatorOverlays(
                            frames = indicatorFrames.main,
                            layout = layout,
                            policy = renderConfig.indicatorOverlay,
                        )
                    }
                }
                if (renderConfig.indicatorOverlay.allowOutsideMainRect) {
                    drawContent()
                } else {
                    clipRect(
                        layout.mainPane.outerRect.left,
                        layout.mainPane.outerRect.top,
                        layout.mainPane.outerRect.right,
                        layout.mainPane.outerRect.bottom,
                    ) {
                        drawContent()
                    }
                }
            }
            if (!renderConfig.indicatorOverlay.allowOutsideMainRect) {
                drawMainOverlayPhase()
            }
            val builtInSubFramesByPane = LinkedHashMap<String, List<KlineIndicatorRenderFrame>>()
            val cacheableCustomSubFramesByPane = LinkedHashMap<String, List<KlineIndicatorRenderFrame>>()
            val dynamicSubFramesByPane = LinkedHashMap<String, List<KlineIndicatorRenderFrame>>()
            indicatorFrames.subByPane.forEach { (paneId, frames) ->
                frames.filter {
                    it.item.renderer.builtInComputedLineRendererOrNull() != null ||
                        it.item.renderer.builtInVolumeRendererOrNull() != null
                }.takeIf(List<KlineIndicatorRenderFrame>::isNotEmpty)?.let { cacheable ->
                    builtInSubFramesByPane[paneId] = cacheable
                }
                frames.filter {
                    it.item.renderer.cachePolicy == KlineIndicatorCachePolicy.ImmutableInputs &&
                        it.item.renderer.builtInComputedLineRendererOrNull() == null &&
                        it.item.renderer.builtInVolumeRendererOrNull() == null
                }.takeIf(List<KlineIndicatorRenderFrame>::isNotEmpty)?.let { cacheable ->
                    cacheableCustomSubFramesByPane[paneId] = cacheable
                }
                frames.filter {
                    it.item.renderer.cachePolicy == KlineIndicatorCachePolicy.Never
                }.takeIf(List<KlineIndicatorRenderFrame>::isNotEmpty)?.let { dynamic ->
                    dynamicSubFramesByPane[paneId] = dynamic
                }
            }
            if (builtInSubFramesByPane.isNotEmpty()) {
                val historicalSubFrames = builtInSubFramesByPane.mapValues { (_, frames) ->
                    frames.map { frame ->
                        val paintRange = frame.context.paintRange
                        val historicalStart = min(
                            paintRange.endExclusive,
                            max(1, paintRange.startInclusive),
                        )
                        frame.copy(
                            context = frame.context.copy(
                                paintRange = if (historicalStart < paintRange.endExclusive) {
                                    IndexRange(historicalStart, paintRange.endExclusive)
                                } else {
                                    IndexRange.Empty
                                },
                            ),
                            preparedLines = null,
                            preparedLineTranslationX = 0f,
                        )
                    }
                }
                val cachedSubKey = KlineHistoricalSubIndicatorLayerKey(
                    series = state.series,
                    layout = layout,
                    frames = historicalSubFrames,
                    style = appliedStyle,
                )
                if (cachedSubIndicatorLayerCache.needsRecord(cachedSubKey, fixedLayerSize)) {
                    traceKlineSection("Kanvas.sub.history.layer.record") {
                        cachedSubIndicatorLayer.record(
                            density = this,
                            layoutDirection = layoutDirection,
                            size = fixedLayerSize,
                        ) {
                            layout.subPanes.forEach { pane ->
                                drawSubIndicatorFrames(
                                    pane = pane,
                                    frames = historicalSubFrames[pane.id].orEmpty(),
                                    style = appliedStyle,
                                )
                            }
                        }
                    }
                    cachedSubIndicatorLayerCache.markRecorded(cachedSubKey, fixedLayerSize)
                }
                drawLayer(cachedSubIndicatorLayer)

                val patchSubFrames = builtInSubFramesByPane.mapValues { (_, frames) ->
                    frames.map { frame ->
                        val endExclusive = if (
                            frame.item.renderer.builtInComputedLineRendererOrNull() != null
                        ) {
                            min(2, frame.context.state.series.size)
                        } else {
                            min(1, frame.context.state.series.size)
                        }
                        frame.copy(
                            context = frame.context.copy(paintRange = IndexRange(0, endExclusive)),
                            preparedLines = null,
                            preparedLineTranslationX = 0f,
                        )
                    }
                }
                val patchSubKey = KlineCachedSubIndicatorLayerKey(
                    state = state,
                    layout = layout,
                    frames = patchSubFrames,
                    style = appliedStyle,
                )
                if (realtimeSubIndicatorPatchLayerCache.needsRecord(patchSubKey, fixedLayerSize)) {
                    traceKlineSection("Kanvas.sub.realtime.patch.record") {
                        realtimeSubIndicatorPatchLayer.record(
                            density = this,
                            layoutDirection = layoutDirection,
                            size = fixedLayerSize,
                        ) {
                            layout.subPanes.forEach { pane ->
                                drawSubIndicatorFrames(
                                    pane = pane,
                                    frames = patchSubFrames[pane.id].orEmpty(),
                                    style = appliedStyle,
                                )
                            }
                        }
                    }
                    realtimeSubIndicatorPatchLayerCache.markRecorded(patchSubKey, fixedLayerSize)
                }
                drawLayer(realtimeSubIndicatorPatchLayer)
            }
            if (cacheableCustomSubFramesByPane.isNotEmpty()) {
                val customSubKey = KlineCachedSubIndicatorLayerKey(
                    state = state,
                    layout = layout,
                    frames = cacheableCustomSubFramesByPane,
                    style = appliedStyle,
                )
                if (cacheableCustomSubIndicatorLayerCache.needsRecord(customSubKey, fixedLayerSize)) {
                    traceKlineSection("Kanvas.sub.custom.cache.layer.record") {
                        cacheableCustomSubIndicatorLayer.record(
                            density = this,
                            layoutDirection = layoutDirection,
                            size = fixedLayerSize,
                        ) {
                            layout.subPanes.forEach { pane ->
                                drawSubIndicatorFrames(
                                    pane = pane,
                                    frames = cacheableCustomSubFramesByPane[pane.id].orEmpty(),
                                    style = appliedStyle,
                                )
                            }
                        }
                    }
                    cacheableCustomSubIndicatorLayerCache.markRecorded(customSubKey, fixedLayerSize)
                }
                drawLayer(cacheableCustomSubIndicatorLayer)
            }
            // Kanvas paints ordinary sub objects in their physical pane order;
            // Time therefore precedes sub charts in `middle` mode and follows
            // them in `bottom` mode. This matters when unclipped time labels
            // overlap an adjacent pane.
            val timeZIndex = builtInIndicators?.time?.zIndex ?: -1
            layout.paneOrder.drop(1).sortedWith(
                compareBy<KlinePaneLayout> { pane ->
                    if (pane.id == layout.timePane?.id) {
                        timeZIndex
                    } else {
                        indicatorPanePlan.subByPane[pane.id].orEmpty()
                            .minOfOrNull { it.definition.zIndex } ?: 0
                    }
                }.thenBy { pane -> layout.paneOrder.indexOf(pane) },
            ).forEach { pane ->
                if (pane.id == layout.timePane?.id) {
                    val timeAxisLayerKey = KlineTranslatableTimeAxisLayerKey(
                        series = state.series,
                        layout = layout,
                        paintRange = mainBodyPaintRange,
                        candleWidthPx = state.viewport.candleWidthPx,
                        candleSpacingPx = state.viewport.candleSpacingPx,
                        style = appliedStyle,
                        interval = state.spec?.interval,
                        config = appliedTimeAxisConfig,
                        formatter = KlineIdentityRef(appliedTimeLabelFormatter),
                        textMeasurer = textMeasurer,
                    )
                    var timeTranslationX = timeAxisLayerCache.translationFor(
                        key = timeAxisLayerKey,
                        size = fullTranslatableLayerSize,
                        currentRightEdgeOffsetPx = state.viewport.rightEdgeOffsetPx,
                        maximumTranslationPx = mainBodyHorizontalOverscanPx,
                    )
                    if (timeTranslationX == null) {
                        traceKlineSection("Kanvas.time.axis.layer.record") {
                            timeAxisLayer.record(
                                density = this,
                                layoutDirection = layoutDirection,
                                size = fullTranslatableLayerSize,
                            ) {
                                translate(left = layerOverscanInt.toFloat()) {
                                    drawTimeAxis(
                                        candles = state.series.candles,
                                        range = mainBodyPaintRange,
                                        chartRect = plotRect,
                                        timeRect = pane.plotRect,
                                        timeOuterRect = pane.outerRect,
                                        viewport = state.viewport,
                                        style = appliedStyle,
                                        textMeasurer = textMeasurer,
                                        interval = state.spec?.interval,
                                        config = appliedTimeAxisConfig.copy(clipToDrawableRect = false),
                                        formatter = appliedTimeLabelFormatter,
                                    )
                                }
                            }
                        }
                        timeAxisLayerCache.markRecorded(
                            key = timeAxisLayerKey,
                            size = fullTranslatableLayerSize,
                            rightEdgeOffsetPx = state.viewport.rightEdgeOffsetPx,
                        )
                        timeTranslationX = 0f
                    }
                    timeAxisLayer.translationX = timeTranslationX - layerOverscanInt
                    if (appliedTimeAxisConfig.clipToDrawableRect) {
                        clipRect(
                            pane.outerRect.left,
                            pane.outerRect.top,
                            pane.outerRect.right,
                            pane.outerRect.bottom,
                        ) {
                            drawLayer(timeAxisLayer)
                        }
                    } else {
                        drawLayer(timeAxisLayer)
                    }
                } else {
                    val frames = dynamicSubFramesByPane[pane.id].orEmpty()
                    traceKlineSection("Kanvas.sub.pane.draw") {
                        if (frames.isNotEmpty()) {
                            drawSubIndicatorFrames(
                                pane = pane,
                                frames = frames,
                                style = appliedStyle,
                            )
                        }
                        drawIndicatorOverlays(
                            frames = indicatorFrames.subByPane[pane.id].orEmpty(),
                            layout = layout,
                            policy = renderConfig.indicatorOverlay,
                        )
                    }
                }
            }
            if (renderConfig.indicatorOverlay.allowOutsideMainRect) {
                drawMainOverlayPhase()
            }
            drawKlinePaneDividers(
                layout = layout,
                color = appliedStyle.gridLine,
            )
            drawingController?.takeIf { drawingConfig.enabled && it.snapshot.visible }?.let { controller ->
                val snapshot = controller.snapshot
                val drawingSpace = DrawingCoordinateSpace(
                    series = state.series,
                    viewport = state.viewport,
                    plotRect = plotRect,
                    minValue = range.minimum,
                    maxValue = range.maximum,
                )
                snapshot.overlays.forEach { overlay ->
                    val tool = controller.toolFor(overlay.type) ?: return@forEach
                    val selected = when (val drawingState = snapshot.state) {
                        is DrawingState.Drawing -> drawingState.overlayId == overlay.id
                        is DrawingState.Editing -> drawingState.overlayId == overlay.id
                        DrawingState.Exited,
                        DrawingState.Prepared,
                        -> false
                    }
                    val pointer = snapshot.pointer.takeIf {
                        (snapshot.state as? DrawingState.Drawing)?.overlayId == overlay.id
                    }
                    if (!tool.isVisible(overlay, drawingSpace, pointer)) return@forEach
                    with(tool) {
                        draw(
                            overlay = overlay,
                            space = drawingSpace,
                            selected = selected,
                            pointer = pointer,
                            config = drawingConfig,
                            densityScale = densityScale,
                        )
                    }
                }
                val activeOverlayId = when (val drawingState = snapshot.state) {
                    is DrawingState.Drawing -> drawingState.overlayId
                    is DrawingState.Editing -> drawingState.overlayId
                    else -> null
                }
                val activeOverlay = snapshot.overlays.firstOrNull { it.id == activeOverlayId }
                if (activeOverlay != null) {
                    val ticksConfig = drawingConfig.ticksText.toKlineTextAreaRenderConfig()
                    val ticksColor = activeOverlay.line.color
                    val priceTickRects = ArrayList<Rect>(activeOverlay.points.size)
                    activeOverlay.points.forEach { point ->
                        val resolvedPoint = point ?: return@forEach
                        val position = drawingSpace.project(resolvedPoint) ?: return@forEach
                        priceTickRects += drawKlineTextArea(
                            text = "%.${state.spec?.precision ?: KlineSpec.DefaultPrecision}f".format(
                                Locale.US,
                                resolvedPoint.value,
                            ),
                            anchor = Offset(
                                plotRect.right - logicalPx(drawingConfig.ticksSpacingPx),
                                position.y,
                            ),
                            horizontalAnchor = KlineTextAreaHorizontalAnchor.End,
                            verticalAnchor = KlineTextAreaVerticalAnchor.Center,
                            config = ticksConfig,
                            textMeasurer = textMeasurer,
                            fallbackTextColor = Color.White,
                            fallbackBackground = ticksColor,
                            drawableRect = layout.mainPane.outerRect,
                        )
                    }
                    priceTickRects.sortBy(Rect::top)
                    for (index in 0 until priceTickRects.lastIndex) {
                        val upper = priceTickRects[index]
                        val lower = priceTickRects[index + 1]
                        if (lower.top > upper.bottom) {
                            drawRect(
                                color = ticksColor.copy(alpha = drawingConfig.ticksGapBackgroundOpacity),
                                topLeft = Offset(max(upper.left, lower.left), upper.bottom),
                                size = Size(
                                    min(upper.right, lower.right) - max(upper.left, lower.left),
                                    lower.top - upper.bottom,
                                ),
                            )
                        }
                    }
                    layout.timePane?.let { timePane ->
                        activeOverlay.points.forEach { point ->
                            val resolvedPoint = point ?: return@forEach
                            val position = drawingSpace.project(resolvedPoint) ?: return@forEach
                            drawKlineTextArea(
                                text = drawingTimeLabelFormatter.format(Date(resolvedPoint.timestampMillis)),
                                anchor = Offset(position.x, timePane.plotRect.top),
                                horizontalAnchor = KlineTextAreaHorizontalAnchor.Center,
                                verticalAnchor = KlineTextAreaVerticalAnchor.Top,
                                config = ticksConfig,
                                textMeasurer = textMeasurer,
                                fallbackTextColor = Color.White,
                                fallbackBackground = ticksColor,
                                drawableRect = timePane.outerRect,
                            )
                        }
                    }
                }
                val pointer = snapshot.pointer
                val pointerPoint = pointer?.let(drawingSpace::unproject)
                if (
                    pointer != null &&
                    pointerPoint != null &&
                    snapshot.state is DrawingState.Drawing
                ) {
                    val selectedId = (snapshot.state as DrawingState.Drawing).overlayId
                    val pointerColor = snapshot.overlays
                        .firstOrNull { it.id == selectedId }
                        ?.line
                        ?.color
                        ?: appliedStyle.crosshair
                    val crossLine = drawingConfig.crosshair
                    val pathEffect = if (crossLine.dashed) {
                        PathEffect.dashPathEffect(
                            floatArrayOf(logicalPx(crossLine.dashOnPx), logicalPx(crossLine.dashOffPx)),
                        )
                    } else {
                        null
                    }
                    val crossColor = crossLine.color.takeUnless { it == Color.Unspecified } ?: pointerColor
                    clipRect(plotRect.left, plotRect.top, plotRect.right, plotRect.bottom) {
                        drawLine(
                            color = crossColor,
                            start = Offset(plotRect.left, pointer.y),
                            end = Offset(plotRect.right, pointer.y),
                            strokeWidth = logicalPx(crossLine.strokeWidthPx),
                            pathEffect = pathEffect,
                        )
                        drawLine(
                            color = crossColor,
                            start = Offset(pointer.x, plotRect.top),
                            end = Offset(pointer.x, plotRect.bottom),
                            strokeWidth = logicalPx(crossLine.strokeWidthPx),
                            pathEffect = pathEffect,
                        )
                        val crossPoint = drawingConfig.crossPoint
                        val pointRadius = logicalPx(crossPoint.radiusPx)
                        val pointBorderWidth = logicalPx(crossPoint.borderWidthPx)
                        drawCircle(
                            color = crossPoint.borderColor ?: pointerColor,
                            radius = pointRadius,
                            center = pointer,
                        )
                        drawCircle(
                            color = crossPoint.color ?: appliedStyle.background,
                            radius = (pointRadius - pointBorderWidth).coerceAtLeast(0f),
                            center = pointer,
                        )
                    }
                    drawKlineTextArea(
                        text = "%.${state.spec?.precision ?: KlineSpec.DefaultPrecision}f".format(
                            Locale.US,
                            pointerPoint.value,
                        ),
                        anchor = Offset(
                            plotRect.right - logicalPx(drawingConfig.ticksSpacingPx),
                            pointer.y,
                        ),
                        horizontalAnchor = KlineTextAreaHorizontalAnchor.End,
                        verticalAnchor = KlineTextAreaVerticalAnchor.Center,
                        config = drawingConfig.ticksText.toKlineTextAreaRenderConfig(),
                        textMeasurer = textMeasurer,
                        fallbackTextColor = Color.White,
                        fallbackBackground = pointerColor,
                        drawableRect = layout.mainPane.outerRect,
                    )
                    layout.timePane?.let { timePane ->
                        drawKlineTextArea(
                            text = drawingTimeLabelFormatter.format(Date(pointerPoint.timestampMillis)),
                            anchor = Offset(pointer.x, timePane.plotRect.top),
                            horizontalAnchor = KlineTextAreaHorizontalAnchor.Center,
                            verticalAnchor = KlineTextAreaVerticalAnchor.Top,
                            config = drawingConfig.ticksText.toKlineTextAreaRenderConfig(),
                            textMeasurer = textMeasurer,
                            fallbackTextColor = Color.White,
                            fallbackBackground = pointerColor,
                            drawableRect = timePane.outerRect,
                        )
                    }
               }
           }
            if (needsMainTipsPreflight) {
                traceKlineSection("Kanvas.interaction.draw") {
                    drawInteractionLayer(
                        geometry = KlineInteractionDrawGeometry(
                            state = state,
                            layout = layout,
                            valueRange = range,
                            indicatorFrames = indicatorFrames,
                        ),
                        inputPosition = crosshairState.value,
                        mainTipsAlreadyDrawn = firstMainTipsPlan != null,
                        drawBuiltInPrimitives = true,
                        drawMainPriceLabel = true,
                    )
                }
            }
            drawPaneResizeAffordances(
                layout = layout,
                config = renderConfig.grid,
                style = appliedStyle,
                activeBoundaryY = activeResizeBoundaryY,
                mode = appliedPaneConfig.mode,
            )
            if (watermarkConfig?.layer == KanvasWatermarkLayer.AboveContent) {
                drawKanvasWatermark(watermarkConfig, layout, textMeasurer)
            }
        }
        reportedLayout?.takeIf { independentCrossNodesEnabled }?.let { crossLayout ->
            val crossConfig = renderConfig.crosshair
            val plotRect = crossLayout.mainPane.plotRect
            val strokePadding = max(
                1f,
                crossConfig.stroke.widthPx * densityScale / 2f + 1f,
            )
            val verticalNodeWidth = kotlin.math.ceil(strokePadding * 2f).toInt().coerceAtLeast(1)
            val horizontalNodeHeight = verticalNodeWidth
            Canvas(
                Modifier
                    .layoutId("cross-vertical-node")
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                        val position = crossNodePosition
                        alpha = if (position != null && crossConfig.enabled) 1f else 0f
                        translationX = (position?.snappedX ?: verticalNodeWidth / 2f) -
                            verticalNodeWidth / 2f
                    },
            ) {
                drawCrossVerticalLine(
                    x = size.width / 2f,
                    top = strokePadding,
                    bottom = size.height - strokePadding,
                    style = appliedStyle,
                    config = crossConfig,
                )
            }

            Canvas(
                Modifier
                    .layoutId("cross-horizontal-node")
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                        val position = crossNodePosition
                        alpha = if (position != null && crossConfig.enabled) 1f else 0f
                        translationY = (position?.rawY ?: horizontalNodeHeight / 2f) -
                            horizontalNodeHeight / 2f
                    },
            ) {
                drawCrossHorizontalLine(
                    y = size.height / 2f,
                    left = strokePadding,
                    right = size.width - strokePadding,
                    style = appliedStyle,
                    config = crossConfig,
                )
            }

            if (crossConfig.pointRadiusPx > 0f) {
                val pointBase = (
                    crossConfig.pointRadiusPx + crossConfig.pointBorderWidthPx / 2f
                    ) * densityScale + 1f
                Canvas(
                    Modifier
                        .layoutId("cross-point-node")
                        .graphicsLayer {
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                            val position = crossNodePosition
                            alpha = if (position != null && crossConfig.enabled) 1f else 0f
                            translationX = (position?.snappedX ?: pointBase) - size.width / 2f
                            translationY = (position?.rawY ?: pointBase) - size.height / 2f
                        },
                ) {
                    drawCrossPoint(
                        position = center,
                        style = appliedStyle,
                        config = crossConfig,
                    )
                }
            }

            val priceNodeHeight = kotlin.math.ceil(crossPriceNodeHeightPx).toInt().coerceAtLeast(1)
            Canvas(
                Modifier
                    .layoutId("cross-price-node")
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                        val position = crossNodePosition
                        val content = crossPriceNodeContent
                        alpha = if (
                            position != null && content != null && crossConfig.enabled
                        ) {
                            1f
                        } else {
                            0f
                        }
                        val halfHeight = (content?.heightPx ?: 0f) / 2f
                        val targetY = position?.rawY
                            ?.coerceIn(
                                plotRect.top + halfHeight,
                                (plotRect.bottom - halfHeight).coerceAtLeast(plotRect.top + halfHeight),
                            )
                            ?: priceNodeHeight / 2f
                        translationY = targetY - priceNodeHeight / 2f
                    },
            ) {
                val content = crossPriceNodeContent ?: return@Canvas
                drawCrossPriceLabel(
                    text = content.text,
                    y = size.height / 2f,
                    plotRect = Rect(0f, 0f, size.width, size.height),
                    style = appliedStyle,
                    config = crossConfig,
                    textMeasurer = textMeasurer,
                )
            }
        }
        if (!appliedPaneConfig.drawBelowTipsArea) {
            Canvas(Modifier.fillMaxSize().layoutId("chart-interaction")) {
                val interactionState = liveChartState?.value ?: state
                val geometry = interactionDrawGeometryRef.value
                    ?.takeIf { cached -> cached.state === interactionState }
                    ?: return@Canvas
                traceKlineSection("Kanvas.interaction.draw") {
                    drawInteractionLayer(
                        geometry = geometry,
                        inputPosition = quantizedCrossSelectionPosition,
                        mainTipsAlreadyDrawn = false,
                        drawBuiltInPrimitives = false,
                        drawMainPriceLabel = false,
                    )
                }
            }
        }
        if (slotContext != null && mainForegroundContent != null) {
            Box(Modifier.layoutId("main-foreground")) {
                mainForegroundContent(slotContext)
            }
        } else if (
            shouldShowKlineLoadingOverlay(state.loadingState, renderConfig.gesture.autoLoadMore) &&
            loadingMainRect != null
        ) {
            KlineLoadingOverlay(
                modifier = Modifier.layoutId("chart-overlay"),
                mainRect = loadingMainRect,
                config = renderConfig.loading,
                style = appliedStyle,
            )
        }
        if (isChartZooming) {
            Box(Modifier.layoutId("vertical-zoom-exit")) {
                if (verticalZoomExitContent != null) {
                    verticalZoomExitContent(::resetVerticalZoom)
                } else {
                    Canvas(
                        Modifier
                            .size(32.dp)
                            .semantics { contentDescription = "Exit vertical zoom" }
                            .clickable(onClick = ::resetVerticalZoom),
                    ) {
                        drawCircle(appliedStyle.tooltipBackground.copy(alpha = 0.88f))
                        val inset = size.minDimension * 0.31f
                        val stroke = max(1.5f, densityScale * 1.5f)
                        drawLine(
                            color = appliedStyle.textColor,
                            start = Offset(inset, inset),
                            end = Offset(size.width - inset, size.height - inset),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            color = appliedStyle.textColor,
                            start = Offset(size.width - inset, inset),
                            end = Offset(inset, size.height - inset),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
        }
        },
    )
}
