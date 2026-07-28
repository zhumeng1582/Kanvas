package com.zhumeng.kanvas.core

import kotlin.test.Test
import kotlin.test.assertEquals

class KlineViewportMathTest {
    @Test
    fun `bounds preserve configured blank and initial offsets`() {
        val viewport = KlineViewport(candleWidthPx = 7f, candleSpacingPx = 1f)
        val constraints = KlineViewportConstraints(
            plotWidthPx = 400f,
            minPaintBlankRate = 0.5f,
            firstCandleInitialOffsetPx = 80f,
        )

        val bounds = KlineViewportMath.bounds(candleCount = 100, viewport, constraints)

        assertEquals(-200f, bounds.minOffsetPx)
        assertEquals(600f, bounds.maxOffsetPx)
        assertEquals(-80f, bounds.initialOffsetPx)
        assertEquals(600f, bounds.clamp(800f))
        assertEquals(-200f, bounds.clamp(-300f))
    }

    @Test
    fun `short series keeps the initial left blank area`() {
        val viewport = KlineViewport(candleWidthPx = 7f, candleSpacingPx = 1f)
        val constraints = KlineViewportConstraints(plotWidthPx = 400f)

        val bounds = KlineViewportMath.bounds(candleCount = 10, viewport, constraints)

        assertEquals(-320f, bounds.minOffsetPx)
        assertEquals(-120f, bounds.maxOffsetPx)
        assertEquals(-320f, bounds.initialOffsetPx)
    }

    @Test
    fun `x mapping remains right anchored and visible range is half open`() {
        val viewport = KlineViewport(candleWidthPx = 7f, candleSpacingPx = 1f, rightEdgeOffsetPx = 16f)
        val constraints = KlineViewportConstraints(plotWidthPx = 80f)

        assertEquals(116f, viewport.xForIndex(plotRightPx = 100f, index = 0.0))
        assertEquals(92f, viewport.xForIndex(plotRightPx = 100f, index = 3.0))
        assertEquals(3.0, viewport.fractionalIndexAt(plotRightPx = 100f, xPx = 92f))
        assertEquals(100f, KlineViewportMath.startCandleX(100f, viewport))
        assertEquals(IndexRange(2, 12), KlineViewportMath.visibleRange(20, viewport, constraints))
    }

    @Test
    fun `move to index centers candle before bounds clamping`() {
        val viewport = KlineViewport(candleWidthPx = 7f, candleSpacingPx = 1f)

        assertEquals(4f, KlineViewportMath.offsetForCenteredIndex(5.0, viewport, plotWidthPx = 80f))
    }
}
