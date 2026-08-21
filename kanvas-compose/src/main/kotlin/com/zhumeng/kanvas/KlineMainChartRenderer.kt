/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.zhumeng.kanvas.core.IndexRange
import com.zhumeng.kanvas.core.KlineUiState

/** Controls whether Kanvas may record a custom main renderer into a graphics layer. */
enum class KlineMainChartCachePolicy {
    /** Invoke the renderer for every draw invalidation. Safe for renderers that observe mutable state. */
    Never,

    /** Cache until one of [KlineMainChartDrawContext]'s immutable inputs changes. */
    ImmutableInputs,
}

/** Immutable geometry and market input supplied to a custom main-chart renderer. */
data class KlineMainChartDrawContext(
    val state: KlineUiState,
    val paintRange: IndexRange,
    val plotRect: Rect,
    val valueRange: KlineValueRange,
    val style: KlineChartStyle,
    val densityScale: Float,
) {
    /** Center X for a newest-first candle index. */
    fun xForIndex(index: Double): Float =
        state.viewport.xForIndex(plotRect.right, index) - state.viewport.candleHalfStepPx

    /** Canvas Y for one value in [valueRange]. */
    fun yForValue(value: Double): Float {
        val fraction = ((valueRange.maximum - value) / valueRange.span).toFloat()
        return plotRect.top + plotRect.height * fraction
    }
}

/**
 * Extensible main-price renderer for Heikin-Ashi, Renko, custom candles, or another price view.
 *
 * [drawChart] runs clipped to [KlineMainChartDrawContext.plotRect]. [drawOverlay] runs immediately
 * afterwards without that clip, matching the built-in high/low and latest-point phase. Both methods
 * execute on the UI draw path and must be deterministic, non-blocking, and allocation-conscious.
 *
 * The default cache policy preserves mutable draw-state observation. Opt into
 * [KlineMainChartCachePolicy.ImmutableInputs] only when output is fully determined by the context
 * and immutable renderer fields.
 */
interface KlineMainChartRenderer {
    val cachePolicy: KlineMainChartCachePolicy get() = KlineMainChartCachePolicy.Never

    /** Enables line-mode policies such as hiding combined main indicators when configured. */
    val isLineLike: Boolean get() = false

    fun drawChart(scope: DrawScope, context: KlineMainChartDrawContext)

    fun drawOverlay(scope: DrawScope, context: KlineMainChartDrawContext) = Unit
}
