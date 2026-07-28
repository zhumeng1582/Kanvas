/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import com.zhumeng.kanvas.core.KlineLoadingState
import com.zhumeng.kanvas.core.KlineViewport
import com.zhumeng.kanvas.core.KlineViewportConstraints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KlineInteractionMathTest {
    @Test
    fun `right anchor retains right blank offset while scale changes`() {
        val result = KlineInteractionMath.scaleToWidth(
            viewport = KlineViewport(candleWidthPx = 7f, candleSpacingPx = 1f, rightEdgeOffsetPx = -40f),
            newCandleWidthPx = 15f,
            focalX = 120f,
            plotWidthPx = 300f,
            anchor = KlineScaleAnchor.Right,
        )

        assertEquals(15f, result.candleWidthPx)
        assertEquals(-40f, result.rightEdgeOffsetPx)
    }

    @Test
    fun `left and middle anchors preserve their chart positions`() {
        val viewport = KlineViewport(candleWidthPx = 7f, candleSpacingPx = 1f, rightEdgeOffsetPx = -20f)
        val left = KlineInteractionMath.scaleToWidth(viewport, 15f, focalX = 100f, plotWidthPx = 300f, anchor = KlineScaleAnchor.Left)
        val middle = KlineInteractionMath.scaleToWidth(viewport, 15f, focalX = 100f, plotWidthPx = 300f, anchor = KlineScaleAnchor.Middle)

        assertEquals(260f, left.rightEdgeOffsetPx)
        assertEquals(160f, middle.rightEdgeOffsetPx)
    }

    @Test
    fun `auto anchor resolves from focal thirds`() {
        assertEquals(KlineScaleAnchor.Left, KlineInteractionMath.resolveAnchor(KlineScaleAnchor.Auto, 99f, 300f))
        assertEquals(KlineScaleAnchor.Middle, KlineInteractionMath.resolveAnchor(KlineScaleAnchor.Auto, 100f, 300f))
        assertEquals(KlineScaleAnchor.Right, KlineInteractionMath.resolveAnchor(KlineScaleAnchor.Auto, 201f, 300f))
    }

    @Test
    fun `focal anchor preserves the fractional candle under the finger`() {
        val old = KlineViewport(candleWidthPx = 7f, candleSpacingPx = 1f, rightEdgeOffsetPx = 35f)
        val focal = 120f
        val before = old.fractionalIndexAt(300f, focal)
        val scaled = KlineInteractionMath.scaleToWidth(old, 15f, focal, 300f, KlineScaleAnchor.Focal)
        val after = scaled.fractionalIndexAt(300f, focal)

        assertEquals(before, after, absoluteTolerance = 0.00001)
    }

    @Test
    fun `older edge request respects the prefetch threshold`() {
        val constraints = KlineViewportConstraints(plotWidthPx = 100f)
        val viewport = KlineViewport(candleWidthPx = 9f, candleSpacingPx = 1f, rightEdgeOffsetPx = 851f)

        assertTrue(
            KlineInteractionMath.isNearOlderEdge(
                candleCount = 100,
                viewport = viewport,
                constraints = constraints,
                loadMoreWhenNoEnoughCandles = 10,
            ),
        )
        assertFalse(
            KlineInteractionMath.isNearOlderEdge(
                candleCount = 100,
                viewport = viewport.copy(rightEdgeOffsetPx = 100f),
                constraints = constraints,
                loadMoreWhenNoEnoughCandles = 10,
            ),
        )
    }

    @Test
    fun `projected inertia prefetches before boundary and shows loading only at boundary`() {
        val constraints = KlineViewportConstraints(plotWidthPx = 100f)
        val viewport = KlineViewport(candleWidthPx = 9f, candleSpacingPx = 1f)

        assertEquals(
            KlineLoadMoreIntent.None,
            KlineInteractionMath.loadMoreIntent(
                candleCount = 100,
                projectedViewport = viewport.copy(rightEdgeOffsetPx = 700f),
                constraints = constraints,
                loadMoreWhenNoEnoughCandles = 10,
            ),
        )
        assertEquals(
            KlineLoadMoreIntent.Prefetch,
            KlineInteractionMath.loadMoreIntent(
                candleCount = 100,
                projectedViewport = viewport.copy(rightEdgeOffsetPx = 851f),
                constraints = constraints,
                loadMoreWhenNoEnoughCandles = 10,
            ),
        )
        assertEquals(
            KlineLoadMoreIntent.ShowLoading,
            KlineInteractionMath.loadMoreIntent(
                candleCount = 100,
                projectedViewport = viewport.copy(rightEdgeOffsetPx = 950f),
                constraints = constraints,
                loadMoreWhenNoEnoughCandles = 10,
            ),
        )
    }

    @Test
    fun `inertial pan keeps the historical boundary from gesture release`() {
        val constraints = KlineViewportConstraints(plotWidthPx = 400f)
        val viewport = KlineViewport(candleWidthPx = 9f, candleSpacingPx = 1f)
        val destination = viewport.copy(rightEdgeOffsetPx = 10_000f)

        val boundedAtRelease = KlineInteractionMath.clamp(
            candleCount = 100,
            viewport = destination,
            constraints = constraints,
        )
        val incorrectlyExpandedAfterLoad = KlineInteractionMath.clamp(
            candleCount = 200,
            viewport = destination,
            constraints = constraints,
        )

        assertEquals(800f, boundedAtRelease.rightEdgeOffsetPx)
        assertEquals(1_800f, incorrectlyExpandedAfterLoad.rightEdgeOffsetPx)
    }

    @Test
    fun `load more dispatch is edge triggered for each request`() {
        assertEquals(false, KlineLoadMoreIntent.Prefetch.dispatchFor(KlineLoadingState.None))
        assertEquals(null, KlineLoadMoreIntent.Prefetch.dispatchFor(KlineLoadingState.LoadMore))
        assertEquals(null, KlineLoadMoreIntent.Prefetch.dispatchFor(KlineLoadingState.LoadingMore))

        assertEquals(true, KlineLoadMoreIntent.ShowLoading.dispatchFor(KlineLoadingState.None))
        assertEquals(true, KlineLoadMoreIntent.ShowLoading.dispatchFor(KlineLoadingState.LoadMore))
        assertEquals(null, KlineLoadMoreIntent.ShowLoading.dispatchFor(KlineLoadingState.LoadingMore))
        assertEquals(null, KlineLoadMoreIntent.ShowLoading.dispatchFor(KlineLoadingState.InitLoading))
    }

    @Test
    fun `inertial pan uses distance factor and square-root duration`() {
        val tolerance = KlineGestureTolerance(maxDurationMillis = 2_000, distanceFactor = 0.8f)
        val spec = checkNotNull(KlineInteractionMath.inertialPanSpec(1_250f, tolerance))

        assertEquals(1_000f, spec.distancePx)
        assertEquals(632, spec.durationMillis)
        assertNull(KlineInteractionMath.inertialPanSpec(0f, tolerance))
        assertNull(KlineInteractionMath.inertialPanSpec(Float.NaN, tolerance))
        assertFailsWith<IllegalArgumentException> { KlineGestureTolerance(maxDurationMillis = 0) }
    }

    @Test
    fun `touch pan sensitivity defaults to twenty percent faster and remains bounded`() {
        assertEquals(1.2f, KlineGestureConfig().effectivePanSensitivity)
        assertEquals(0.5f, KlineGestureConfig(panSensitivity = 0.1f).effectivePanSensitivity)
        assertEquals(3f, KlineGestureConfig(panSensitivity = 10f).effectivePanSensitivity)
        assertFailsWith<IllegalArgumentException> {
            KlineGestureConfig(panSensitivity = Float.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            KlineGestureConfig(panSensitivity = 0f)
        }
    }

    @Test
    fun `release velocity cannot reverse the completed drag`() {
        assertEquals(0f, resolveKlineInertialVelocity(1_000f, -20f))
        assertEquals(0f, resolveKlineInertialVelocity(-1_000f, 20f))
        assertEquals(1_000f, resolveKlineInertialVelocity(1_000f, 20f))
        assertEquals(-1_000f, resolveKlineInertialVelocity(-1_000f, -20f))
    }
}
