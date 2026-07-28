/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas.core

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** Pixel-only state shared by all panes in a chart. */
data class KlineViewport(
    val candleWidthPx: Float = 7f,
    val candleSpacingPx: Float = 1f,
    val rightEdgeOffsetPx: Float = 0f,
) {
    init {
        require(candleWidthPx > 0f) { "candleWidthPx must be > 0" }
        require(candleSpacingPx >= 0f) { "candleSpacingPx must be >= 0" }
    }

    val candleStepPx: Float get() = candleWidthPx + candleSpacingPx

    val candleHalfStepPx: Float get() = candleStepPx / 2f

    fun xForIndex(plotRightPx: Float, index: Double): Float =
        plotRightPx + rightEdgeOffsetPx - index.toFloat() * candleStepPx

    fun fractionalIndexAt(plotRightPx: Float, xPx: Float): Double =
        ((plotRightPx + rightEdgeOffsetPx - xPx) / candleStepPx).toDouble()
}

/** Constraints used to calculate horizontal viewport bounds. */
data class KlineViewportConstraints(
    val plotWidthPx: Float,
    val minPaintBlankRate: Float = 0.5f,
    val firstCandleInitialOffsetPx: Float = 80f,
    val alwaysCalculateScreenOfCandlesIfEnough: Boolean = false,
) {
    init {
        require(plotWidthPx >= 0f) { "plotWidthPx must be >= 0" }
    }

    val minPaintBlankWidthPx: Float
        get() = plotWidthPx * minPaintBlankRate.coerceIn(0f, 0.9f)
}

data class KlineViewportBounds(
    val minOffsetPx: Float,
    val maxOffsetPx: Float,
    val initialOffsetPx: Float,
) {
    fun clamp(offsetPx: Float): Float = offsetPx.coerceIn(minOffsetPx, maxOffsetPx)
}

/**
 * Pure viewport formulas shared by controllers and renderers.
 * No Compose, density, or mutable UI state is allowed here.
 */
object KlineViewportMath {
    fun bounds(
        candleCount: Int,
        viewport: KlineViewport,
        constraints: KlineViewportConstraints,
    ): KlineViewportBounds {
        require(candleCount >= 0) { "candleCount must be >= 0" }
        val dataWidth = candleCount * viewport.candleStepPx
        val plotWidth = constraints.plotWidthPx
        val blankWidth = constraints.minPaintBlankWidthPx
        // Allow the newest candle to move left by the configured blank width.
        // This preserves the native chart behavior: a left swipe reveals a
        // bounded empty region on the right, then stops at that boundary.
        val minOffset = min(dataWidth - plotWidth, -blankWidth)
        val maxOffset = dataWidth - (plotWidth - blankWidth)
        val initialOffset = min(dataWidth - plotWidth, -constraints.firstCandleInitialOffsetPx)
        return KlineViewportBounds(minOffset, maxOffset, initialOffset.coerceIn(minOffset, maxOffset))
    }

    /** Resolves the first candle position using plot-right as the anchor. */
    fun startCandleX(plotRightPx: Float, viewport: KlineViewport): Float =
        when {
            viewport.rightEdgeOffsetPx == 0f -> plotRightPx
            viewport.rightEdgeOffsetPx > 0f -> plotRightPx + viewport.rightEdgeOffsetPx % viewport.candleStepPx
            else -> plotRightPx + viewport.rightEdgeOffsetPx
        }

    /**
     * Produces a half-open, clamped visible range. The renderer adds one
     * overscan candle; this method intentionally describes the scale
     * range only.
     */
    fun visibleRange(
        candleCount: Int,
        viewport: KlineViewport,
        constraints: KlineViewportConstraints,
    ): IndexRange {
        if (candleCount == 0 || constraints.plotWidthPx <= 0f) return IndexRange.Empty

        val step = viewport.candleStepPx
        val raw = if (viewport.rightEdgeOffsetPx > 0f) {
            val start = floor(viewport.rightEdgeOffsetPx / step).toInt().coerceAtLeast(0)
            val diff = viewport.rightEdgeOffsetPx % step
            val count = ((constraints.plotWidthPx + diff) / step).roundToInt().coerceAtLeast(0)
            IndexRange(start, start + count)
        } else {
            val screenCount = kotlin.math.ceil(constraints.plotWidthPx / step).toInt()
            val count = if (constraints.alwaysCalculateScreenOfCandlesIfEnough) {
                screenCount
            } else {
                val offsetIndex = (kotlin.math.abs(viewport.rightEdgeOffsetPx) / step).roundToInt()
                (screenCount - offsetIndex).coerceAtLeast(0)
            }
            IndexRange(0, count)
        }
        return raw.clampTo(candleCount)
    }

    /** Centered target offset for an index, before bounds clamping. */
    fun offsetForCenteredIndex(
        index: Double,
        viewport: KlineViewport,
        plotWidthPx: Float,
    ): Float = index.toFloat() * viewport.candleStepPx + viewport.candleHalfStepPx - plotWidthPx / 2f
}
