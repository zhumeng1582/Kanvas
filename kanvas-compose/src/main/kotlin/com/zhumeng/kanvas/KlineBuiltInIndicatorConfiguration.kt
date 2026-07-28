/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhumeng.kanvas.core.KlineInterval

/**
 * Type-safe configuration for the chart's built-in candle indicator.
 *
 * This is a native Compose model. Import adapters may project their own wire
 * formats into it, but the renderer has no dependency on those formats.
 */
data class KlineCandleIndicatorConfiguration(
    /** Main-pane z-index for the candle body. */
    val zIndex: Int = -1,
    val defaultChartType: KlineChartType = KlineChartType.Bar(),
    val minWidthLineType: KlineChartType.Line? = null,
    val intervalChartTypes: Map<KlineInterval, KlineChartType> = emptyMap(),
    val hideMainIndicatorsInLineChartMode: Boolean = true,
    /** Local candle colors; absent entries preserve [KlineChartStyle]. */
    val colorOverrides: KlineCandleColorOverrides = KlineCandleColorOverrides(),
    /** Candle marker and latest-price overlay settings. */
    val overlay: KlineCandleOverlayRenderConfig = KlineCandleOverlayRenderConfig(),
    /** Area fills used by normal and latest-price-relative up/down lines. */
    val gradients: KlineCandleGradientConfiguration = KlineCandleGradientConfiguration(),
) {
    /** Resolves interval-specific, minimum-width, then default chart type precedence. */
    fun resolveChartType(
        interval: KlineInterval?,
        candleWidthPx: Float,
        minCandleWidthPx: Float,
    ): KlineChartType = when {
        interval != null && intervalChartTypes[interval] != null -> checkNotNull(intervalChartTypes[interval])
        candleWidthPx <= minCandleWidthPx && minWidthLineType != null -> minWidthLineType
        else -> defaultChartType
    }

    companion object {
        /** Standard built-in candle defaults for an explicit host opt-in. */
        fun defaults(): KlineCandleIndicatorConfiguration = KlineCandleIndicatorConfiguration()
    }
}

/** Preset identifiers for configurable candle gradients. */
data class KlineGradientAlignment(
    val x: Float,
    val y: Float,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "Gradient alignment must be finite." }
    }

    companion object {
        val TopCenter = KlineGradientAlignment(0f, -1f)
        val BottomCenter = KlineGradientAlignment(0f, 1f)
    }
}

enum class KlineGradientTileMode {
    Clamp,
    Repeated,
    Mirror,
    Decal,
}

data class KlineLineGradientRenderConfig(
    val enabled: Boolean = true,
    val begin: KlineGradientAlignment = KlineGradientAlignment.TopCenter,
    val end: KlineGradientAlignment = KlineGradientAlignment.BottomCenter,
    val startAlpha: Float = 0.5f,
    val endAlpha: Float = 0f,
    val colors: List<Color>? = null,
    val stops: List<Float>? = listOf(0f, 1f),
    val tileMode: KlineGradientTileMode = KlineGradientTileMode.Decal,
) {
    init {
        require(startAlpha in 0f..1f && endAlpha in 0f..1f) {
            "Gradient alpha must be between zero and one."
        }
        require(colors == null || colors.size >= 2) { "A static gradient needs at least two colors." }
        require(stops == null || stops.all { it.isFinite() && it in 0f..1f }) {
            "Gradient stops must be finite and between zero and one."
        }
        require(stops == null || stops.zipWithNext().all { (first, second) -> first <= second }) {
            "Gradient stops must be ordered."
        }
        require(colors == null || stops == null || colors.size == stops.size) {
            "Gradient colors and stops must have the same size."
        }
    }
}

data class KlineCandleGradientConfiguration(
    val line: KlineLineGradientRenderConfig? = KlineLineGradientRenderConfig(),
    val bullish: KlineLineGradientRenderConfig? = KlineLineGradientRenderConfig(),
    val bearish: KlineLineGradientRenderConfig? = KlineLineGradientRenderConfig(
        begin = KlineGradientAlignment.BottomCenter,
        end = KlineGradientAlignment.TopCenter,
    ),
)

/** Nullable local candle colors; an explicit transparent color is retained. */
data class KlineCandleColorOverrides(
    val bullish: Color? = null,
    val bearish: Color? = null,
    val line: Color? = null,
)

internal fun KlineChartStyle.withCandleColorOverrides(
    overrides: KlineCandleColorOverrides,
): KlineChartStyle = copy(
    bullish = overrides.bullish ?: bullish,
    bearish = overrides.bearish ?: bearish,
    line = overrides.line ?: line,
)

/** Type-safe configuration for the chart's built-in time indicator. */
data class KlineTimeIndicatorConfiguration(
    val zIndex: Int = -1,
    val height: Dp = 15.dp,
    val position: KlineTimePanePosition = KlineTimePanePosition.Middle,
    val padding: KlinePanePadding = KlinePanePadding(),
    val text: KlineTextAreaRenderConfig = KlineTextAreaRenderConfig(
        textAlign = KlineTextAlign.Center,
        textWidthPx = 80f,
    ),
    val clipToDrawableRect: Boolean = false,
) {
    companion object {
        /** Standard built-in time-axis defaults for an explicit host opt-in. */
        fun defaults(): KlineTimeIndicatorConfiguration = KlineTimeIndicatorConfiguration()
    }
}

/**
 * Optional overrides for the chart's built-in candle and time indicators.
 *
 * Null entries deliberately leave the corresponding explicit chart arguments
 * unchanged. This lets hosts opt into only the native built-in settings they
 * own, regardless of where those settings originated.
 */
data class KlineBuiltInIndicatorConfiguration(
    val candle: KlineCandleIndicatorConfiguration? = null,
    val time: KlineTimeIndicatorConfiguration? = null,
) {
    companion object {
        /** Explicitly applies the standard candle and time built-in defaults. */
        fun defaults(): KlineBuiltInIndicatorConfiguration = KlineBuiltInIndicatorConfiguration(
            candle = KlineCandleIndicatorConfiguration.defaults(),
            time = KlineTimeIndicatorConfiguration.defaults(),
        )
    }
}
