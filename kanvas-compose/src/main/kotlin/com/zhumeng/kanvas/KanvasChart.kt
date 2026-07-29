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

    private val date = SimpleDateFormat("yyyy/M/d", locale)

    private val minute = SimpleDateFormat("M/d HH:mm", locale)

    private val second = SimpleDateFormat("HH:mm:ss", locale)

    fun format(candle: KlineCandle, interval: KlineInterval?): String {
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
        return formatter.format(Date(candle.timestampMillis))
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
    val backgroundColor: Color,
    val textArea: KlineTextAreaRenderConfig?,
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

private class KlineRangeUpdateRef(
    var series: KlineSeries? = null,
    var viewport: KlineViewport? = null,
    var realtimeMode: Boolean = false,
)
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
    if (state.series.isEmpty || plotRect.width <= 0f || plotRect.height <= 0f) return null
    val constraints = state.viewportConstraints
        ?: renderConfig.viewport.constraints(plotRect.width, densityScale)
    val visible = KlineViewportMath.visibleRange(state.series.size, state.viewport, constraints)
    val paintRange = IndexRange(
        (visible.startInclusive - 1).coerceAtLeast(0),
        (visible.endExclusive + 1).coerceAtMost(state.series.size),
    )
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
    val valueRange = state.series.candles
        .subList(paintRange.startInclusive, paintRange.endExclusive)
        .valueRangeIncluding(
            mainOutputs = mainOutputsForRange,
            paintRange = paintRange,
            rendererRanges = mainRendererRanges,
            sameValueExpansionRatios = renderConfig.sameValueRangeExpansionRatios,
            includeMainIndicators = renderConfig.includeMainIndicatorsInValueRange,
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
    val orderMarkerIndex = remember(orderMarkers) { KlineOrderMarkerIndex(orderMarkers) }
    val defaultTimeLabelFormatter = remember { DefaultKlineTimeLabelFormatter(Locale.getDefault()) }
    val drawingTimeLabelFormatter = remember { SimpleDateFormat("M/d HH:mm", Locale.getDefault()) }
    val appliedTimeLabelFormatter: KlineTimeLabelFormatter = timeLabelFormatter
        ?: { candle, interval -> defaultTimeLabelFormatter.format(candle, interval) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var crosshair by remember { mutableStateOf<Offset?>(null) }
    val crosshairSessionRef = remember { CrosshairSessionRef() }
    var isChartZooming by remember { mutableStateOf(false) }
    var verticalZoomInsetPx by remember { mutableStateOf(0f) }
    var verticalZoomShiftPx by remember { mutableStateOf(0f) }
    var hoverInVerticalZoomTarget by remember { mutableStateOf(false) }
    var paneHeightOverridesPx by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var activeResizeBoundaryY by remember { mutableStateOf<Float?>(null) }
    var panRangeSmoothFactor by remember { mutableStateOf(1f) }
    val mainRangeSmoother = remember { KlineValueRangeSmoother() }
    val rangeUpdateRef = remember { KlineRangeUpdateRef() }
    val previousRangeSeries = rangeUpdateRef.series
    val seriesChanged = previousRangeSeries !== state.series
    val viewportChanged = rangeUpdateRef.viewport != state.viewport
    val latestCandleOnlyUpdate = seriesChanged &&
        previousRangeSeries != null &&
        previousRangeSeries.size == state.series.size &&
        previousRangeSeries.latest?.timestampMillis == state.series.latest?.timestampMillis
    val realtimeRangeMode = when {
        latestCandleOnlyUpdate -> true
        seriesChanged || viewportChanged -> false
        else -> rangeUpdateRef.realtimeMode
    }
    val rangeSmoothFactor = if (realtimeRangeMode) {
        min(panRangeSmoothFactor, renderConfig.latestCandleRangeSmoothFactor)
    } else {
        panRangeSmoothFactor
    }
    SideEffect {
        rangeUpdateRef.series = state.series
        rangeUpdateRef.viewport = state.viewport
        rangeUpdateRef.realtimeMode = realtimeRangeMode
    }
    val focusRequester = remember { FocusRequester() }
    val composeDensity = androidx.compose.ui.platform.LocalDensity.current
    // Tooltip width is stored in physical pixels. Reset its Cross-session
    // cache whenever density or accessibility font scale changes so an old
    // pixel width cannot distort freshly measured text.
    var crossTooltipStableContentWidthPx by remember(composeDensity.density, composeDensity.fontScale) {
        mutableStateOf<Float?>(null)
    }
    val axisWidthPx = with(composeDensity) { axisWidth.toPx() }
    val densityScale = composeDensity.density
    val appliedChartType = builtInIndicators?.candle?.resolveChartType(
        interval = state.spec?.interval,
        candleWidthPx = state.viewport.candleWidthPx,
        minCandleWidthPx = renderConfig.viewport.resolvedMinCandleWidthPx(densityScale),
    ) ?: chartType
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
    val indicatorPanePlan = indicatorSnapshot.resolveIndicatorPanePlan(
        state = state,
        registry = indicatorRegistrySnapshot,
        renderers = activeRendererResolver,
    )
    val appliedPaneConfig = basePaneConfig.copy(
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
    // Pointer input must not restart after every StateFlow viewport update. It
    // reads these remembered holders during the gesture instead.
    val currentState = rememberUpdatedState(state)
    val currentRenderConfig = rememberUpdatedState(renderConfig)
    val currentIndicatorSnapshot = rememberUpdatedState(indicatorSnapshot)
    val currentIndicatorRegistrySnapshot = rememberUpdatedState(indicatorRegistrySnapshot)
    val currentIndicatorRendererResolver = rememberUpdatedState(activeRendererResolver)
    val currentOnUnsupportedIndicators = rememberUpdatedState(onUnsupportedIndicators)
    val currentOnLayoutChange = rememberUpdatedState(onLayoutChange)
    val currentPaneConfig = rememberUpdatedState(appliedPaneConfig)
    val currentChartType = rememberUpdatedState(appliedChartType)
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
    var countdownNowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
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
    val reportedLayout = canvasSize.takeIf { it.width > 0 && it.height > 0 }?.let { size ->
        resolveChartLayout(
            canvasSize = Size(size.width.toFloat(), size.height.toFloat()),
            axisWidthPx = axisWidthPx,
            timeAxisHeightPx = timeAxisHeightPx,
            densityScale = densityScale,
            paneConfig = appliedPaneConfig,
            subPaneSpecs = indicatorPanePlan.subPaneSpecs,
        )
    }
    // A parent may replace its callback after the layout has stabilized; send
    // the current value to the new observer as well as on geometry changes.
    LaunchedEffect(reportedLayout, currentOnLayoutChange.value) {
        reportedLayout?.let(currentOnLayoutChange.value)
    }

    fun updateCrosshair(position: Offset?) {
        crosshair = position
        if (position == null) {
            crosshairSessionRef.value = null
            crossTooltipStableContentWidthPx = null
        }
        val current = currentState.value
        val activeAndLayout = if (position != null && canvasSize != IntSize.Zero) {
            val active = currentIndicatorSnapshot.value.resolveIndicatorPanePlan(
                state = current,
                registry = currentIndicatorRegistrySnapshot.value,
                renderers = currentIndicatorRendererResolver.value,
            )
            val currentAxisWidth = currentAxisWidthPx.value
            val currentTimeAxisHeight = currentTimeAxisHeightPx.value
            val currentDensity = currentDensityScale.value
            active to resolveChartLayout(
                canvasSize = Size(canvasSize.width.toFloat(), canvasSize.height.toFloat()),
                axisWidthPx = currentAxisWidth,
                timeAxisHeightPx = currentTimeAxisHeight,
                densityScale = currentDensity,
                paneConfig = currentPaneConfig.value,
                subPaneSpecs = active.subPaneSpecs,
            )
        } else {
            null
        }
        val layout = activeAndLayout?.second
        val mainRange = activeAndLayout?.let { (active, resolvedLayout) ->
            resolveKlineMainRenderRange(
                state = current,
                plotRect = resolvedLayout.mainPane.plotRect,
                renderConfig = currentRenderConfig.value,
                indicatorPanePlan = active,
                hideMainIndicators = currentHideMainIndicatorsInLineChartMode.value &&
                    currentChartType.value is KlineChartType.Line,
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

    fun resolveCurrentLayout(current: KlineUiState): KlineLayout? {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return null
        val active = currentIndicatorSnapshot.value.resolveIndicatorPanePlan(
            state = current,
            registry = currentIndicatorRegistrySnapshot.value,
            renderers = currentIndicatorRendererResolver.value,
        )
        return resolveChartLayout(
            canvasSize = Size(canvasSize.width.toFloat(), canvasSize.height.toFloat()),
            axisWidthPx = currentAxisWidthPx.value,
            timeAxisHeightPx = currentTimeAxisHeightPx.value,
            densityScale = currentDensityScale.value,
            paneConfig = currentPaneConfig.value,
            subPaneSpecs = active.subPaneSpecs,
        )
    }

    fun resolveDrawingSpace(current: KlineUiState): DrawingCoordinateSpace? {
        val layout = resolveCurrentLayout(current) ?: return null
        val active = currentIndicatorSnapshot.value.resolveIndicatorPanePlan(
            state = current,
            registry = currentIndicatorRegistrySnapshot.value,
            renderers = currentIndicatorRendererResolver.value,
        )
        val mainRange = resolveKlineMainRenderRange(
            state = current,
            plotRect = layout.mainPane.plotRect,
            renderConfig = currentRenderConfig.value,
            indicatorPanePlan = active,
            hideMainIndicators = currentHideMainIndicatorsInLineChartMode.value &&
                currentChartType.value is KlineChartType.Line,
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
    /** Rebuilds Cross tooltip geometry for both Canvas stability tracking and pointer routing. */

    fun resolveCurrentCrossTooltipPresentation(): KlineCrossTooltipPresentation? {
        val position = crosshair ?: return null
        val current = currentState.value
        val layout = resolveCurrentLayout(current) ?: return null
        val config = currentRenderConfig.value.crosshair
        val crosshairContext = resolveKlineIndicatorCrosshairContext(
            inputPosition = position,
            state = current,
            plotRect = layout.mainPane.plotRect,
            renderConfig = config,
            crosshairRightPx = layout.mainPane.plotRect.right,
        ) ?: return null
        return resolveKlineCrossTooltipPresentation(
            crosshair = crosshairContext,
            state = current,
            plotRect = layout.mainPane.plotRect,
            anchorRect = layout.mainPane.outerRect,
            config = config,
            style = currentStyle.value,
            textMeasurer = textMeasurer,
            densityScale = currentDensityScale.value,
            timeLabelFormatter = currentTimeLabelFormatter.value,
            provider = currentCrossTooltipProvider.value,
            stableContentWidthPx = crossTooltipStableContentWidthPx,
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
        val active = currentIndicatorSnapshot.value.resolveIndicatorPanePlan(
            state = current,
            registry = currentIndicatorRegistrySnapshot.value,
            renderers = currentIndicatorRendererResolver.value,
        )
        val density = currentDensityScale.value
        val layout = resolveChartLayout(
            canvasSize = Size(canvasSize.width.toFloat(), canvasSize.height.toFloat()),
            axisWidthPx = currentAxisWidthPx.value,
            timeAxisHeightPx = currentTimeAxisHeightPx.value,
            densityScale = density,
            paneConfig = currentPaneConfig.value,
            subPaneSpecs = active.subPaneSpecs,
        )
        val hideMain = currentHideMainIndicatorsInLineChartMode.value &&
            currentChartType.value is KlineChartType.Line
        val mainRange = resolveKlineMainRenderRange(
            state = current,
            plotRect = layout.mainPane.plotRect,
            renderConfig = currentRenderConfig.value,
            indicatorPanePlan = active,
            hideMainIndicators = hideMain,
            densityScale = density,
        ) ?: return null
        return layout to resolveKlineIndicatorRenderFrames(
            state = current,
            layout = layout,
            indicatorPanePlan = active,
            paintRange = mainRange.paintRange,
            mainValueRange = mainRange.valueRange,
            style = currentStyle.value,
            densityScale = density,
            hideMainIndicators = hideMain,
            sameValueExpansionRatios = currentRenderConfig.value.sameValueRangeExpansionRatios,
        )
    }
    /** Dispatches confirmed pointer taps before the Cross persistent-toggle route. */

    fun dispatchCurrentIndicatorTap(position: Offset): Boolean {
        val current = currentState.value
        val (layout, frames) = resolveCurrentIndicatorFrames(current) ?: return false
        val activeCrosshair = crosshair?.let { crossPosition ->
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
        val current = currentState.value
        if (current.series.isEmpty) return null
        val layout = resolveCurrentLayout(current) ?: return null
        val plotRect = layout.mainPane.plotRect
        if (plotRect.width <= 0f || plotRect.height <= 0f) return null
        val density = currentDensityScale.value
        val active = currentIndicatorSnapshot.value.resolveIndicatorPanePlan(
            state = current,
            registry = currentIndicatorRegistrySnapshot.value,
            renderers = currentIndicatorRendererResolver.value,
        )
        val range = resolveKlineMainRenderRange(
            state = current,
            plotRect = plotRect,
            renderConfig = currentRenderConfig.value,
            indicatorPanePlan = active,
            hideMainIndicators = currentHideMainIndicatorsInLineChartMode.value &&
                currentChartType.value is KlineChartType.Line,
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
        val current = currentState.value
        if (current.series.isEmpty) return
        val layout = resolveCurrentLayout(current) ?: return
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
            resolveDrawingSpace(currentState.value)?.let { space ->
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
        val current = currentState.value
        val layout = resolveCurrentLayout(current) ?: return
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
    // Kanvas keeps the widest content observed in one Cross session. Do this
    // after composition, never from DrawScope, so Canvas and pointer routes
    // can deterministically re-resolve the same immutable geometry.
    LaunchedEffect(
        crosshair,
        state,
        renderConfig.crosshair,
        crossTooltipProvider,
        appliedStyle,
        appliedTimeLabelFormatter,
        canvasSize,
        composeDensity.density,
        composeDensity.fontScale,
    ) {
        if (crosshair == null) {
            crossTooltipStableContentWidthPx = null
        } else {
            resolveCurrentCrossTooltipPresentation()?.layout?.contentWidthPx?.let { contentWidth ->
                crossTooltipStableContentWidthPx = max(crossTooltipStableContentWidthPx ?: 0f, contentWidth)
            }
        }
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
            val current = currentState.value
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
                            val layout = resolveCurrentLayout(currentState.value)
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
                            val layout = resolveCurrentLayout(currentState.value)
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
                    val stateAtDown = currentState.value
                    if (
                        shouldShowKlineLoadingOverlay(
                            stateAtDown.loadingState,
                            currentRenderConfig.value.gesture.autoLoadMore,
                        )
                    ) {
                        return@also
                    }
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

                        fun updateViewport(
                            pan: Offset,
                            zoom: Float,
                            focal: Offset,
                            scaleOnly: Boolean,
                        ): KlineViewport? {
                            val current = currentState.value
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
                            if (next != old) currentOnViewportChange.value(next)
                            return next
                        }

                        fun requestLoadMoreFor(projectedViewport: KlineViewport) {
                            val current = currentState.value
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
                            resolveDrawingSpace(currentState.value)
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
                        resolveCurrentLayout(currentState.value)?.let { startLayout ->
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
                                            val space = resolveDrawingSpace(currentState.value)
                                            when (controller?.snapshot?.state) {
                                                is DrawingState.Drawing -> {
                                                    val point = space?.let {
                                                        controller.snap(
                                                            position = latestPosition,
                                                            space = it,
                                                            candles = currentState.value.series.candles,
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
                                            requestLoadMoreFor(viewportDuringGesture ?: currentState.value.viewport)
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
                                        val space = resolveDrawingSpace(currentState.value)
                                        controller?.updatePointer(latestPosition)
                                        if (
                                            controller?.snapshot?.state is DrawingState.Editing &&
                                            space != null
                                        ) {
                                            controller.snap(
                                                position = latestPosition,
                                                space = space,
                                                candles = currentState.value.series.candles,
                                                minDistancePx =
                                                    currentDrawingConfig.value.magnetDistancePx *
                                                        currentDensityScale.value,
                                            )?.let(controller::moveSelectedPoint)
                                        }
                                        consumePositionChanges(event)
                                    }
                                    PointerGestureOwner.DrawingMove -> {
                                        val controller = currentDrawingController.value
                                        val space = resolveDrawingSpace(currentState.value)
                                        val currentPoint = space?.unproject(latestPosition)
                                        val previousPoint = drawingMoveLastPoint
                                        if (controller != null && currentPoint != null && previousPoint != null) {
                                            controller.moveSelectedBy(
                                                deltaTimestampMillis =
                                                    currentPoint.timestampMillis - previousPoint.timestampMillis,
                                                deltaValue = currentPoint.value - previousPoint.value,
                                            )
                                            drawingMoveLastPoint = currentPoint
                                            controller.updatePointer(latestPosition)
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
                                            val layout = resolveCurrentLayout(currentState.value)
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
                            isChartZooming = verticalZoomInsetPx > 0f
                            activeResizeBoundaryY = null
                            cancelLongPress()
                            // Hold sessions are promoted to Persistent on pointer-up above;
                            // do not clear them here. Pan/pinch routes clear Cross explicitly.
                        }
                        if (completedWithPan) {
                            val gesture = currentRenderConfig.value.gesture
                            val gestureEndViewport = viewportDuringGesture ?: currentState.value.viewport
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
                            } else {
                                val gestureEndOffsetPx = gestureEndViewport.rightEdgeOffsetPx
                                // Keep this fling bounded by the data that existed when the
                                // pointer was released. A historical page may complete while
                                // the animation is still running; using the new series size
                                // would expand the boundary mid-flight and fling straight into
                                // the newly loaded page instead of preserving the load anchor.
                                val inertialPanCandleCount = currentState.value.series.size
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
                                            val current = currentState.value
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
                                    }
                                }
                            }
                        }
                }
            }
            }
        }
    val loadingMainRect = canvasSize.takeIf { size -> size.width > 0 && size.height > 0 }?.let { size ->
        resolveChartLayout(
            canvasSize = Size(size.width.toFloat(), size.height.toFloat()),
            axisWidthPx = axisWidthPx,
            timeAxisHeightPx = timeAxisHeightPx,
            densityScale = densityScale,
            paneConfig = appliedPaneConfig,
            subPaneSpecs = indicatorPanePlan.subPaneSpecs,
        ).mainPane.outerRect
    }
    Layout(
        modifier = chartModifier,
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
            // Capture the host's thread-safe invalidate epoch so asynchronous
            // stateful renderers can request a new Canvas frame without
            // mutating Compose state from DrawScope.
            if (lifecycleEpoch < 0L) return@Canvas
            val baseLayout = resolveChartLayout(
                canvasSize = size,
                axisWidthPx = axisWidthPx,
                timeAxisHeightPx = timeAxisHeightPx,
                densityScale = densityScale,
                paneConfig = appliedPaneConfig,
                subPaneSpecs = indicatorPanePlan.subPaneSpecs,
            )
            drawRect(appliedStyle.background)
            val basePlotRect = baseLayout.mainPane.plotRect
            if (basePlotRect.width <= 0f || basePlotRect.height <= 0f || state.series.isEmpty) {
                return@Canvas
            }
            val hideMainIndicators = hideMainIndicatorsInLineChartMode && appliedChartType is KlineChartType.Line
            val exactBaseMainRenderRange = resolveKlineMainRenderRange(
                state = state,
                plotRect = basePlotRect,
                renderConfig = renderConfig,
                indicatorPanePlan = indicatorPanePlan,
                hideMainIndicators = hideMainIndicators,
                densityScale = densityScale,
            ) ?: return@Canvas
            val baseMainRenderRange = exactBaseMainRenderRange.copy(
                valueRange = mainRangeSmoother.resolve(
                    target = exactBaseMainRenderRange.valueRange,
                    smoothFactor = rangeSmoothFactor,
                    resetKey = state.spec?.key,
                ),
            )
            val baseIndicatorFrames = resolveKlineIndicatorRenderFrames(
                state = state,
                layout = baseLayout,
                indicatorPanePlan = indicatorPanePlan,
                paintRange = baseMainRenderRange.paintRange,
                mainValueRange = baseMainRenderRange.valueRange,
                style = appliedStyle,
                densityScale = densityScale,
                hideMainIndicators = hideMainIndicators,
                sameValueExpansionRatios = renderConfig.sameValueRangeExpansionRatios,
            )
            val baseCrosshair = crosshair
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
            val firstTipsSelection = baseCrosshair?.let(KlineIndicatorTopTipsSelection::Cross)
                ?: KlineIndicatorTopTipsSelection.Latest(state.series.latest)
            val firstMainTipsPlan = if (appliedPaneConfig.drawBelowTipsArea && !isChartZooming) {
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
            if (plotRect.width <= 0f || plotRect.height <= 0f) return@Canvas
            val mainRenderRange = if (layout === baseLayout) {
                baseMainRenderRange
            } else {
                resolveKlineMainRenderRange(
                    state = state,
                    plotRect = plotRect,
                    renderConfig = renderConfig,
                    indicatorPanePlan = indicatorPanePlan,
                    hideMainIndicators = hideMainIndicators,
                    densityScale = densityScale,
                )?.let { exact ->
                    exact.copy(
                        valueRange = mainRangeSmoother.resolve(
                            target = exact.valueRange,
                            smoothFactor = rangeSmoothFactor,
                            resetKey = state.spec?.key,
                        ),
                    )
                } ?: return@Canvas
            }
            val paintRange = mainRenderRange.paintRange
            val range = mainRenderRange.valueRange
            val indicatorFrames = if (layout === baseLayout) {
                baseIndicatorFrames
            } else {
                resolveKlineIndicatorRenderFrames(
                    state = state,
                    layout = layout,
                    indicatorPanePlan = indicatorPanePlan,
                    paintRange = paintRange,
                    mainValueRange = range,
                    style = appliedStyle,
                    densityScale = densityScale,
                    hideMainIndicators = hideMainIndicators,
                    sameValueExpansionRatios = renderConfig.sameValueRangeExpansionRatios,
                )
            }
            // Resolve this once so ordinary and Cross Top Tips cannot disagree
            // about blank-area selection or whether Cross is actually enabled.
            // A retained pointer while Cross rendering is disabled remains an
            // ordinary/latest Tips frame.
            val activeNativeCrosshair = crosshair
                ?.takeIf { renderConfig.crosshair.enabled }
                ?.let { inputPosition ->
                    resolveKlineIndicatorCrosshairContext(
                        inputPosition = inputPosition,
                        state = state,
                        plotRect = plotRect,
                        renderConfig = renderConfig.crosshair,
                        crosshairRightPx = layout.mainPane.plotRect.right,
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

            fun drawMainTopTips(selection: KlineIndicatorTopTipsSelection) {
                drawKlineIndicatorTopTipsRenderPlan(
                    plan = resolveKlineIndicatorTopTipsRenderPlan(
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
                    plan = resolveKlineIndicatorTopTipsRenderPlan(
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
            drawGrid(
                plotRect = plotRect,
                style = appliedStyle,
                config = renderConfig.grid,
                textMeasurer = textMeasurer,
                range = range,
                precision = state.spec?.precision ?: KlineSpec.DefaultPrecision,
                includeBottomAxisLabel = true,
                drawYAxisTicks = false,
            )
            // Treat the candle body plus high/low marks or the line latest
            // point as one rendering phase.
            // Keep them as one z-index unit relative to Main indicators.
            // Latest-price markers stay below as Candle's separate overlay
            // phase, intentionally above the chart body layer.

            fun drawCandleChartPhase() {
                clipRect(plotRect.left, plotRect.top, plotRect.right, plotRect.bottom) {
                    when (appliedChartType) {
                        is KlineChartType.Bar -> drawBars(
                            state.series.candles,
                            paintRange,
                            plotRect,
                            range,
                            state.viewport,
                            appliedChartType.style,
                            appliedStyle,
                            renderConfig.candle,
                        )
                        is KlineChartType.Line -> drawLineChart(
                            state.series.candles,
                            paintRange,
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
                if (appliedChartType is KlineChartType.Bar) {
                    drawHighLowMarks(
                        candles = state.series.candles,
                        range = paintRange,
                        plotRect = plotRect,
                        values = range,
                        viewport = state.viewport,
                        precision = state.spec?.precision ?: KlineSpec.DefaultPrecision,
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
                drawYAxisTicks(
                    plotRect = plotRect,
                    style = appliedStyle,
                    config = renderConfig.grid,
                    textMeasurer = textMeasurer,
                    range = range,
                    precision = state.spec?.precision ?: KlineSpec.DefaultPrecision,
                    includeBottomAxisLabel = true,
                    textArea = renderConfig.grid.ticksText,
                )
            }

            fun drawMainItems(frames: List<KlineIndicatorRenderFrame>) {
                drawMainIndicatorFrames(
                    mainPane = layout.mainPane,
                    frames = frames,
                    style = appliedStyle,
                    grid = renderConfig.grid,
                    textMeasurer = textMeasurer,
                    precision = state.spec?.precision ?: KlineSpec.DefaultPrecision,
                )
            }
            if (hideMainIndicators) {
                drawCandleChartPhase()
            } else {
                val split = indicatorFrames.main.indexOfFirst { frame ->
                    frame.item.definition.zIndex >= appliedCandleZIndex
                }.let { index -> if (index < 0) indicatorFrames.main.size else index }
                drawMainItems(indicatorFrames.main.subList(0, split))
                drawCandleChartPhase()
                drawMainItems(indicatorFrames.main.subList(split, indicatorFrames.main.size))
           }

            fun drawMainOverlayPhase() {

                fun drawContent() {
                    drawOrderMarkers(
                        markerIndex = orderMarkerIndex,
                        candles = state.series.candles,
                        paintRange = paintRange,
                        plotRect = plotRect,
                        values = range,
                        viewport = state.viewport,
                        config = orderMarkerConfig,
                        textMeasurer = textMeasurer,
                        densityScale = densityScale,
                    )
                    drawLatestPrice(
                        latest = state.series.latest,
                        interval = state.spec?.interval,
                        nowMillis = countdownNowMillis,
                        plotRect = plotRect,
                        values = range,
                        viewport = state.viewport,
                        precision = state.spec?.precision ?: KlineSpec.DefaultPrecision,
                        style = appliedStyle,
                        config = appliedCandleOverlayConfig,
                        textMeasurer = textMeasurer,
                    )
                    drawIndicatorOverlays(
                        frames = indicatorFrames.main,
                        layout = layout,
                        policy = renderConfig.indicatorOverlay,
                    )
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
                    drawTimeAxis(
                        candles = state.series.candles,
                        range = paintRange,
                        chartRect = plotRect,
                        timeRect = pane.plotRect,
                        timeOuterRect = pane.outerRect,
                        viewport = state.viewport,
                        style = appliedStyle,
                        textMeasurer = textMeasurer,
                        interval = state.spec?.interval,
                        config = appliedTimeAxisConfig,
                        formatter = appliedTimeLabelFormatter,
                    )
                } else {
                    val frames = indicatorFrames.subByPane[pane.id].orEmpty()
                    drawSubIndicatorFrames(
                        pane = pane,
                        frames = frames,
                        style = appliedStyle,
                    )
                    if (activeNativeCrosshair == null) {
                        // Sub objects own independent Tips rects, including
                        // Android's optional shared physical sub pane.
                        drawSubTopTips(
                            paneId = pane.id,
                            selection = KlineIndicatorTopTipsSelection.Latest(state.series.latest),
                        )
                    }
                    drawIndicatorOverlays(
                        frames = indicatorFrames.subByPane[pane.id].orEmpty(),
                        layout = layout,
                        policy = renderConfig.indicatorOverlay,
                    )
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
                    val priceTickRects = activeOverlay.points.mapNotNull { point ->
                        val resolvedPoint = point ?: return@mapNotNull null
                        val position = drawingSpace.project(resolvedPoint) ?: return@mapNotNull null
                        drawKlineTextArea(
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
                    }.sortedBy(Rect::top)
                    priceTickRects.zipWithNext().forEach { (upper, lower) ->
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
            activeNativeCrosshair?.let { resolvedCrosshair ->
                // Draw main top tips before the crosshair tooltip so the tooltip
                // card overlays the indicator labels rather than being obscured by them.
                if (firstMainTipsPlan == null) {
                    drawMainTopTips(KlineIndicatorTopTipsSelection.Cross(resolvedCrosshair))
                }
                val crossResult = drawCrosshair(
                    crosshair = resolvedCrosshair,
                    state = state,
                    plotRect = plotRect,
                    tooltipAnchorRect = layout.mainPane.outerRect,
                    verticalRect = layout.chartRect,
                    crosshairRightPx = layout.mainPane.plotRect.right,
                    values = range,
                    style = appliedStyle,
                    config = renderConfig.crosshair,
                    textMeasurer = textMeasurer,
                    crossTooltipProvider = crossTooltipProvider,
                    stableTooltipContentWidthPx = crossTooltipStableContentWidthPx,
                    timeLabelFormatter = appliedTimeLabelFormatter,
                )
                val candle = crossResult?.candle
                layout.timePane?.takeIf { candle != null }?.let { timePane ->
                    drawCrossTimeLabel(
                        candle = checkNotNull(candle),
                        snappedX = checkNotNull(crossResult).snappedX,
                        timeRect = timePane.plotRect,
                        style = appliedStyle,
                        textMeasurer = textMeasurer,
                        interval = state.spec?.interval,
                        config = renderConfig.crosshair,
                        formatter = appliedTimeLabelFormatter,
                    )
                }
                crossResult?.context?.let { nativeCrosshair ->
                    // Paint the built-in Cross first, then Time, physical sub
                    // panes, and finally main indicator Cross
                    // decorations. Top Tips follow each object's Cross pass;
                    // main frames are aggregated in their existing z order.
                    val selection = KlineIndicatorTopTipsSelection.Cross(nativeCrosshair)
                    layout.subPanes.forEach { pane ->
                        drawSubPaneCrossValue(
                            pane = pane,
                            frames = indicatorFrames.subByPane[pane.id].orEmpty(),
                            pointer = nativeCrosshair.inputPosition,
                            style = appliedStyle,
                            config = renderConfig.crosshair,
                            precision = state.spec?.precision ?: KlineSpec.DefaultPrecision,
                            textMeasurer = textMeasurer,
                        )
                        drawIndicatorCross(
                            frames = indicatorFrames.subByPane[pane.id].orEmpty(),
                            layout = layout,
                            crosshair = nativeCrosshair,
                        )
                        drawSubTopTips(paneId = pane.id, selection = selection)
                    }
                    drawIndicatorCross(
                        frames = indicatorFrames.main,
                        layout = layout,
                        crosshair = nativeCrosshair,
                    )
                }
            }
            if (activeNativeCrosshair == null && firstMainTipsPlan == null) {
                drawMainTopTips(KlineIndicatorTopTipsSelection.Latest(state.series.latest))
            }
            drawPaneResizeAffordances(
                layout = layout,
                config = renderConfig.grid,
                style = appliedStyle,
                activeBoundaryY = activeResizeBoundaryY,
                mode = appliedPaneConfig.mode,
            )
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
    ) { measurables, constraints ->
        val width = (if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth)
            .coerceIn(constraints.minWidth, constraints.maxWidth)
        val desiredHeight = if (appliedPaneConfig.mode == KlineLayoutMode.Adapt) {
            resolveChartLayout(
                canvasSize = Size(width.toFloat(), 0f),
                axisWidthPx = axisWidthPx,
                timeAxisHeightPx = timeAxisHeightPx,
                densityScale = densityScale,
                paneConfig = appliedPaneConfig,
                subPaneSpecs = indicatorPanePlan.subPaneSpecs,
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
            subPaneSpecs = indicatorPanePlan.subPaneSpecs,
        )
        val mainRect = measuredLayout.mainPane.outerRect
        val mainConstraints = Constraints.fixed(
            mainRect.width.roundToInt().coerceAtLeast(0),
            mainRect.height.roundToInt().coerceAtLeast(0),
        )
        val placeables = measurables.map { measurable ->
            val isMainSlot = measurable.layoutId == "main-background" || measurable.layoutId == "main-foreground"
            val childConstraintsForId = when {
                isMainSlot -> mainConstraints
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
                    layoutId == "vertical-zoom-exit" ->
                        (mainRect.right - placeable.width - 8f * densityScale).roundToInt()
                    else -> 0
                }
                val y = when {
                    isMainSlot -> mainRect.top.roundToInt()
                    layoutId == "vertical-zoom-exit" ->
                        (mainRect.bottom - placeable.height - 8f * densityScale).roundToInt()
                    else -> 0
                }
                placeable.place(x, y)
            }
        }
    }
}
