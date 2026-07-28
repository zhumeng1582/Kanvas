/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.zhumeng.kanvas.core.IndicatorPlacement
import com.zhumeng.kanvas.core.KlineViewport
import com.zhumeng.kanvas.core.KlineViewportConstraints

/**
 * A Canvas line resolved from a Compose theme or explicit host configuration.
 *
 * Numeric dimensions are logical pixels. [KanvasChart] resolves them using
 * the current Compose density immediately before drawing.
 */
data class KlineStrokeStyle(
    /** Null delegates the color to [KlineChartStyle]'s matching theme color. */
    val color: Color? = null,
    val widthPx: Float = 1f,
    /** Empty means a solid line. Values alternate painted/gap distances. */
    val dashPatternPx: List<Float> = emptyList(),
    val paintStyle: KlinePaintStyle = KlinePaintStyle.Stroke,
    val blendMode: BlendMode = BlendMode.SrcOver,
    val isAntiAlias: Boolean = true,
) {
    init {
        require(widthPx >= 0f) { "Stroke width must not be negative." }
        require(dashPatternPx.all { it > 0f }) { "Dash pattern entries must be positive." }
    }
}

enum class KlinePaintStyle {
    Stroke,
    Fill,
}

/** Grid appearance and visibility. */
data class KlineGridRenderConfig(
    val show: Boolean = true,
    val horizontalShow: Boolean = true,
    val verticalShow: Boolean = true,
    val horizontalCount: Int = 5,
    val verticalCount: Int = 5,
    val horizontalStroke: KlineStrokeStyle = KlineStrokeStyle(),
    val verticalStroke: KlineStrokeStyle = KlineStrokeStyle(),
    val showYAxisTicks: Boolean = true,
    val ticksText: KlineTextAreaRenderConfig = KlineTextAreaRenderConfig(
        textAlign = KlineTextAlign.End,
        padding = KlinePanePadding(leftPx = 2f, rightPx = 2f),
    ),
    val allowDragIndicatorHeight: Boolean = false,
    val dragHitTestMinDistancePx: Float = 10f,
    val dragLine: KlineStrokeStyle = KlineStrokeStyle(
        widthPx = 2.5f,
        dashPatternPx = listOf(3f, 5f),
    ),
    val dragLineOpacity: Float = 0.1f,
    val draggingBackgroundOpacity: Float = 0.1f,
    val idleDragBackgroundOpacity: Float = 0f,
) {
    init {
        require(horizontalCount > 0) { "horizontalCount must be positive." }
        require(verticalCount > 0) { "verticalCount must be positive." }
        require(dragHitTestMinDistancePx.isFinite() && dragHitTestMinDistancePx >= 0f) {
            "Grid drag hit distance must be finite and nonnegative."
        }
        require(listOf(dragLineOpacity, draggingBackgroundOpacity, idleDragBackgroundOpacity).all {
            it.isFinite() && it in 0f..1f
        }) { "Grid drag opacity must be in [0, 1]." }
    }
}

/** Crosshair and tooltip settings; dimensions are logical pixels. */
data class KlineCrosshairRenderConfig(
    val enabled: Boolean = true,
    val stroke: KlineStrokeStyle = KlineStrokeStyle(dashPatternPx = listOf(3f, 3f)),
    val pointRadiusPx: Float = 3f,
    val pointBorderWidthPx: Float = 1f,
    val pointColor: Color? = null,
    val pointBorderColor: Color? = null,
    val ticksText: KlineTextAreaRenderConfig = KlineTextAreaRenderConfig(
        textAlign = KlineTextAlign.End,
        padding = KlinePanePadding(2f, 2f, 2f, 2f),
        borderWidthPx = 0.5f,
        borderRadius = KlineBorderRadius.all(2f),
    ),
    val ticksSpacingPx: Float = 1f,
    val showTooltip: Boolean = true,
    val tooltipMargin: KlinePanePadding = KlinePanePadding(leftPx = 10f, topPx = 10f, rightPx = 10f),
    val tooltipPadding: KlinePanePadding = KlinePanePadding(leftPx = 7f, topPx = 4f, rightPx = 7f, bottomPx = 4f),
    /** Non-null overrides tooltip typography, padding, background, and radius. */
    val tooltipTextArea: KlineTextAreaRenderConfig? = null,
    val tooltipSpacingPx: Float = 2f,
    val showLatestTipsInBlank: Boolean = true,
    val moveByCandleInBlank: Boolean = false,
    /** Extra logical-pixel hit target around the crosshair tooltip. */
    val tooltipHitTestMarginPx: Float = 0f,
) {
    init {
        require(pointRadiusPx >= 0f) { "Cross point radius must not be negative." }
        require(pointBorderWidthPx >= 0f) { "Cross point border width must not be negative." }
        require(ticksSpacingPx.isFinite() && ticksSpacingPx >= 0f) {
            "Cross tick spacing must be finite and nonnegative."
        }
        require(tooltipSpacingPx.isFinite() && tooltipSpacingPx >= 0f) {
            "Tooltip spacing must be finite and nonnegative."
        }
        require(tooltipHitTestMarginPx.isFinite() && tooltipHitTestMarginPx >= 0f) {
            "Tooltip hit-test margin must be finite and nonnegative."
        }
    }
}

/**
 * Applied `SettingConfig` stroke widths for Candle and line-chart painting.
 * Values use logical pixels and are resolved by [KanvasChart].
 */
data class KlineCandleRenderConfig(
    val candleLineWidthPx: Float = 1f,
    val hollowBarBorderWidthPx: Float = 1f,
) {
    init {
        require(candleLineWidthPx >= 0f) { "Candle line width must not be negative." }
        require(hollowBarBorderWidthPx >= 0f) { "Hollow-bar border width must not be negative." }
    }
}

/**
 * Loading indicator settings applied by the Compose overlay. Dimensions stay
 * in logical pixels until [KanvasChart] reaches its Canvas boundary.
 */
data class KlineLoadingRenderConfig(
    /** Diameter of the loading indicator in logical pixels. */
    val sizePx: Float = 26f,
    val strokeWidthPx: Float = 4f,
    /** Null delegates to [KlineChartStyle.tooltipBackground]. */
    val backgroundColor: Color? = null,
    /** Null delegates to [KlineChartStyle.textColor]. */
    val valueColor: Color? = null,
) {
    init {
        require(sizePx > 0f) { "Loading size must be positive." }
        require(strokeWidthPx > 0f) { "Loading stroke width must be positive." }
    }
}

/**
 * Render settings for the standalone Time pane. Dimensions are
 * logical pixels; labels may be formatted by the host at [KanvasChart].
 */
data class KlineTimeAxisRenderConfig(
    val text: KlineTextAreaRenderConfig = KlineTextAreaRenderConfig(
        textAlign = KlineTextAlign.Center,
        textWidthPx = 80f,
    ),
    val clipToDrawableRect: Boolean = false,
)

enum class KlineTextAlign {
    Start,
    Center,
    End,
    Left,
    Right,
    Justify,
}

data class KlineCornerRadius(
    val xPx: Float = 0f,
    val yPx: Float = xPx,
) {
    init {
        require(xPx.isFinite() && yPx.isFinite() && xPx >= 0f && yPx >= 0f) {
            "Corner radius must be finite and nonnegative."
        }
    }
}

data class KlineBorderRadius(
    val topLeft: KlineCornerRadius = KlineCornerRadius(),
    val topRight: KlineCornerRadius = KlineCornerRadius(),
    val bottomRight: KlineCornerRadius = KlineCornerRadius(),
    val bottomLeft: KlineCornerRadius = KlineCornerRadius(),
) {
    companion object {
        fun all(radiusPx: Float): KlineBorderRadius {
            val radius = KlineCornerRadius(radiusPx)
            return KlineBorderRadius(radius, radius, radius, radius)
        }
    }
}

/** Reusable logical-pixel text-area styling. */
data class KlineTextAreaRenderConfig(
    val textColor: Color? = null,
    val fontSizeSp: Float = 10f,
    val fontFamily: String? = null,
    val fontStyle: FontStyle? = null,
    val fontWeight: FontWeight? = null,
    val lineHeightMultiplier: Float? = null,
    val textBaseline: String? = null,
    val textDecoration: TextDecoration? = null,
    val decorationColor: Color? = null,
    val decorationStyle: String? = null,
    val strutFontFamily: String? = null,
    val strutHeightMultiplier: Float? = null,
    val strutLeading: Float? = null,
    val strutFontWeight: FontWeight? = null,
    val strutFontStyle: FontStyle? = null,
    val forceStrutHeight: Boolean = false,
    val textAlign: KlineTextAlign = KlineTextAlign.Start,
    val textWidthPx: Float? = null,
    val minWidthPx: Float? = null,
    val maxWidthPx: Float? = null,
    val maxLines: Int = 1,
    val backgroundColor: Color? = null,
    val padding: KlinePanePadding = KlinePanePadding(),
    val borderColor: Color? = null,
    val borderWidthPx: Float = 0f,
    val borderRadius: KlineBorderRadius = KlineBorderRadius(),
) {
    init {
        require(fontSizeSp.isFinite() && fontSizeSp > 0f) { "Text font size must be finite and positive." }
        require(lineHeightMultiplier == null || (lineHeightMultiplier.isFinite() && lineHeightMultiplier > 0f)) {
            "Text line height must be finite and positive."
        }
        require(strutHeightMultiplier == null || (strutHeightMultiplier.isFinite() && strutHeightMultiplier > 0f))
        require(strutLeading == null || strutLeading.isFinite())
        require(listOfNotNull(textWidthPx, minWidthPx, maxWidthPx).all { it.isFinite() && it >= 0f }) {
            "Text-area widths must be finite and nonnegative."
        }
        require(minWidthPx == null || maxWidthPx == null || minWidthPx <= maxWidthPx) {
            "Text-area minimum width must not exceed maximum width."
        }
        require(maxLines > 0) { "Text-area max lines must be positive." }
        require(borderWidthPx.isFinite() && borderWidthPx >= 0f) {
            "Text-area border width must be finite and nonnegative."
        }
    }
}

/** Appearance of a Canvas price marker. */
data class KlinePriceMarkerRenderConfig(
    val show: Boolean = true,
    val spacingPx: Float = 0f,
    val line: KlineStrokeStyle = KlineStrokeStyle(widthPx = 0.5f),
    /** Only high/low price marks use this finite leader-line length. */
    val lineLengthPx: Float? = null,
    /** Reserved for the off-view latest-price marker's tap target. */
    val hitTestMarginPx: Float = 0f,
    val text: KlineTextAreaRenderConfig = KlineTextAreaRenderConfig(),
) {
    init {
        require(spacingPx >= 0f) { "Price-marker spacing must not be negative." }
        require(lineLengthPx == null || lineLengthPx >= 0f) { "Price-marker line length must not be negative." }
        require(hitTestMarginPx >= 0f) { "Price-marker hit-test margin must not be negative." }
    }
}

/** Appearance of the latest point on a line chart. */
data class KlinePointRenderConfig(
    val radiusPx: Float = 2f,
    val widthPx: Float = 0f,
    val color: Color? = null,
    val borderWidthPx: Float = 2f,
    val borderColor: Color? = null,
) {
    init {
        require(radiusPx >= 0f && widthPx >= 0f && borderWidthPx >= 0f) {
            "Point dimensions must not be negative."
        }
    }
}

/** Latest-price countdown appearance. */
data class KlineCountdownRenderConfig(
    val show: Boolean = true,
    val text: KlineTextAreaRenderConfig = KlineTextAreaRenderConfig(
        textAlign = KlineTextAlign.Center,
        padding = KlinePanePadding(0f, 2f, 0f, 2f),
        borderWidthPx = 0.5f,
        borderRadius = KlineBorderRadius.all(2f),
    ),
)

/** Applied Candle overlay subset: high/low marks, latest price, countdown, and line point. */
data class KlineCandleOverlayRenderConfig(
    val high: KlinePriceMarkerRenderConfig = KlinePriceMarkerRenderConfig(
        spacingPx = 2f,
        line = KlineStrokeStyle(widthPx = 0.5f),
        lineLengthPx = 20f,
    ),
    val low: KlinePriceMarkerRenderConfig = KlinePriceMarkerRenderConfig(
        spacingPx = 2f,
        line = KlineStrokeStyle(widthPx = 0.5f),
        lineLengthPx = 20f,
    ),
    val inViewPrice: KlinePriceMarkerRenderConfig = KlinePriceMarkerRenderConfig(
        spacingPx = 1f,
        line = KlineStrokeStyle(widthPx = 0.5f, dashPatternPx = listOf(3f, 3f)),
        text = KlineTextAreaRenderConfig(
            textAlign = KlineTextAlign.Center,
            padding = KlinePanePadding(2f, 2f, 2f, 2f),
            borderRadius = KlineBorderRadius.all(2f),
        ),
    ),
    val offViewPrice: KlinePriceMarkerRenderConfig = KlinePriceMarkerRenderConfig(
        spacingPx = 1f,
        line = KlineStrokeStyle(widthPx = 0.5f, dashPatternPx = listOf(3f, 3f)),
        hitTestMarginPx = 4f,
        text = KlineTextAreaRenderConfig(
            padding = KlinePanePadding(4f, 2f, 4f, 2f),
            borderRadius = KlineBorderRadius.all(10f),
        ),
    ),
    val showLatestPoint: Boolean = true,
    val latestPoint: KlinePointRenderConfig? = KlinePointRenderConfig(),
    val useCandleColorForLatestPriceBackground: Boolean = true,
    val countdown: KlineCountdownRenderConfig = KlineCountdownRenderConfig(),
)

/**
 * Clipping policy for the main overlay phase (latest price plus main-indicator
 * overlays). Sub-indicator overlays are an explicit Kotlin extension and
 * remain responsible for their own clipping.
 */
data class KlineIndicatorOverlayRenderConfig(
    /**
     * When true, draw the whole main overlay phase after all sub panes,
     * allowing a main indicator overlay to extend outside the
     * main rect. False keeps that phase immediately after the main chart.
     */
    val allowOutsideMainRect: Boolean = true,
)

/**
 * Applied `SettingConfig` values that govern the visible viewport.
 *
 * These numeric dimensions use logical pixels, whereas a Compose
 * [androidx.compose.foundation.Canvas] operates in physical pixels. Keep the
 * density-independent values here and call the density-aware
 * helpers when constructing core viewport state or constraints.
 */
data class KlineViewportRenderConfig(
    val candleWidthPx: Float = 7f,
    val candleSpacingPx: Float = 1f,
    val minCandleWidthPx: Float = 1f,
    val maxCandleWidthPx: Float = 40f,
    val minPaintBlankRate: Float = 0.5f,
    val firstCandleInitialOffsetPx: Float = 80f,
    val alwaysCalculateScreenOfCandlesIfEnough: Boolean = false,
) {
    init {
        require(candleWidthPx > 0f) { "Candle width must be positive." }
        require(candleSpacingPx >= 0f) { "Candle spacing must not be negative." }
        require(minCandleWidthPx > 0f) { "Minimum candle width must be positive." }
        require(maxCandleWidthPx >= minCandleWidthPx) { "Maximum candle width must be >= minimum." }
    }

    fun initialViewport(
        densityScale: Float = 1f,
        rightEdgeOffsetPx: Float = 0f,
    ): KlineViewport = KlineViewport(
        candleWidthPx = resolvedCandleWidthPx(densityScale),
        candleSpacingPx = resolvedCandleSpacingPx(densityScale),
        rightEdgeOffsetPx = rightEdgeOffsetPx,
    )

    fun constraints(
        plotWidthPx: Float,
        densityScale: Float = 1f,
    ): KlineViewportConstraints = KlineViewportConstraints(
        plotWidthPx = plotWidthPx,
        minPaintBlankRate = minPaintBlankRate,
        firstCandleInitialOffsetPx = firstCandleInitialOffsetPx * densityScale.requirePositiveDensity(),
        alwaysCalculateScreenOfCandlesIfEnough = alwaysCalculateScreenOfCandlesIfEnough,
    )

    fun resolvedCandleWidthPx(densityScale: Float): Float =
        candleWidthPx.coerceIn(minCandleWidthPx, maxCandleWidthPx) * densityScale.requirePositiveDensity()

    fun resolvedCandleSpacingPx(densityScale: Float): Float =
        candleSpacingPx * densityScale.requirePositiveDensity()

    fun resolvedMinCandleWidthPx(densityScale: Float): Float =
        minCandleWidthPx * densityScale.requirePositiveDensity()

    fun resolvedMaxCandleWidthPx(densityScale: Float): Float =
        maxCandleWidthPx * densityScale.requirePositiveDensity()
}

private fun Float.requirePositiveDensity(): Float {
    require(isFinite() && this > 0f) { "densityScale must be finite and positive." }
    return this
}

/** Render-time configuration accepted by [KanvasChart] and built by the host. */
data class KlineChartRenderConfig(
    val viewport: KlineViewportRenderConfig = KlineViewportRenderConfig(),
    val candle: KlineCandleRenderConfig = KlineCandleRenderConfig(),
    val loading: KlineLoadingRenderConfig = KlineLoadingRenderConfig(),
    val candleOverlay: KlineCandleOverlayRenderConfig = KlineCandleOverlayRenderConfig(),
    /** Enables the latest-price countdown timer. */
    val autoStartLastPriceCountdownTimer: Boolean = true,
    val indicatorOverlay: KlineIndicatorOverlayRenderConfig = KlineIndicatorOverlayRenderConfig(),
    /**
     * Upper and lower expansion ratios used when every visible value is
     * identical.
     */
    val sameValueRangeExpansionRatios: List<Float> = listOf(0.1f, 0.05f),
    /**
     * Whether COMBINE main-indicator values expand the shared price axis.
     * Disable this to keep Candle scaling stable when switching main indicators;
     * indicator segments outside the visible OHLC range are clipped by the pane.
     */
    val includeMainIndicatorsInValueRange: Boolean = true,
    /**
     * Interpolation factor used only when the same open newest candle is
     * replaced. `1` applies its new auto-range immediately; smaller values
     * reduce vertical jitter without delaying pan/zoom range changes.
     */
    val latestCandleRangeSmoothFactor: Float = 1f,
    val grid: KlineGridRenderConfig = KlineGridRenderConfig(),
    val gesture: KlineGestureConfig = KlineGestureConfig(),
    val crosshair: KlineCrosshairRenderConfig = KlineCrosshairRenderConfig(),
) {
    init {
        require(latestCandleRangeSmoothFactor in 0.1f..1f) {
            "Latest-candle range smooth factor must be between 0.1 and 1."
        }
    }
}

/**
 * Per-sub-pane sizing override. Unknown user-pane ids remain safe for future
 * indicator plugins; `main` and `time` are reserved for system panes.
 *
 * For [IndicatorPlacement.Sub] with its default `paneId`, use
 * [IndicatorPlacement.Sub.resolvedPaneId] with the indicator key. The
 * `default` string is a placement sentinel, not the generated Canvas-pane id.
 */
data class KlineSubPaneRenderConfig(
    val id: String,
    /** Default sub-indicator height. */
    val preferredHeight: Dp = 60.dp,
    val minHeight: Dp = 30.dp,
    /** Default sub-indicator top padding. */
    val padding: KlinePanePadding = KlinePanePadding(topPx = 12f),
) {
    init {
        require(id.isNotBlank()) { "Sub pane id must not be blank." }
        require(id !in IndicatorPlacement.SystemPaneIds) {
            "Sub pane id '$id' is reserved for a system pane."
        }
        require(preferredHeight.value >= 0f && minHeight.value >= 0f) {
            "Sub pane dimensions must not be negative."
        }
    }
}

/**
 * Layout controls for Canvas indicator panes. `Adapt` reports its requested
 * physical geometry through [KanvasChart]'s `onLayoutChange`; the host
 * must apply `KlineLayout.requiredHeightPx` to its parent measurement. The
 * default `Fixed` mode is suitable for normal fill/weight layouts.
 */
data class KlinePaneRenderConfig(
    val mode: KlineLayoutMode = KlineLayoutMode.Fixed,
    val mainPreferredHeight: Dp = 300.dp,
    val mainMinHeight: Dp = 80.dp,
    /** Logical inset applied inside the main pane. */
    val mainPadding: KlinePanePadding = KlinePanePadding(),
    /** Moves the main plot below indicator Tips after their prepare pass. */
    val drawBelowTipsArea: Boolean = false,
    /** Places Time immediately below Main by default. */
    val timePosition: KlineTimePanePosition = KlineTimePanePosition.Middle,
    /** Logical inset applied inside the Time pane. */
    val timePadding: KlinePanePadding = KlinePanePadding(),
    val defaultSubPaneHeight: Dp = 60.dp,
    val defaultSubPaneMinHeight: Dp = 30.dp,
    val defaultSubPanePadding: KlinePanePadding = KlinePanePadding(topPx = 12f),
    val subPanes: List<KlineSubPaneRenderConfig> = emptyList(),
) {
    init {
        require(mainPreferredHeight.value >= 0f && mainMinHeight.value >= 0f) {
            "Main pane dimensions must not be negative."
        }
        require(defaultSubPaneHeight.value >= 0f && defaultSubPaneMinHeight.value >= 0f) {
            "Default sub pane dimensions must not be negative."
        }
        require(subPanes.map(KlineSubPaneRenderConfig::id).distinct().size == subPanes.size) {
            "Sub pane configuration ids must be unique."
        }
    }

    fun explicitPaneFor(id: String): KlineSubPaneRenderConfig? =
        subPanes.firstOrNull { it.id == id }

    fun paneFor(id: String): KlineSubPaneRenderConfig =
        explicitPaneFor(id)
            ?: KlineSubPaneRenderConfig(
                id = id,
                preferredHeight = defaultSubPaneHeight,
                minHeight = defaultSubPaneMinHeight,
                padding = defaultSubPanePadding,
            )
}
