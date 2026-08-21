/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import com.zhumeng.kanvas.core.KlineCandle
import com.zhumeng.kanvas.core.KlineLoadingState
import com.zhumeng.kanvas.core.KlineSeries
import com.zhumeng.kanvas.core.KlineViewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KlineValueRangeSmootherTest {
    @Test
    fun `viewport change wins when a realtime tick arrives during pan`() {
        assertFalse(
            resolveKlineRealtimeRangeMode(
                viewportChanged = true,
                latestCandleOnlyUpdate = true,
                seriesChanged = true,
                previousMode = true,
            ),
        )
        assertTrue(
            resolveKlineRealtimeRangeMode(
                viewportChanged = false,
                latestCandleOnlyUpdate = true,
                seriesChanged = true,
                previousMode = false,
            ),
        )
    }

    @Test
    fun `pan range interpolates and exact end frame converges`() {
        val smoother = KlineValueRangeSmoother()
        assertEquals(KlineValueRange(0.0, 10.0), smoother.resolve(KlineValueRange(0.0, 10.0), 1f, "BTC"))
        val interpolated = smoother.resolve(KlineValueRange(10.0, 20.0), 0.15f, "BTC")
        assertEquals(1.5, interpolated.minimum, 1e-6)
        assertEquals(11.5, interpolated.maximum, 1e-6)
        assertEquals(KlineValueRange(10.0, 20.0), smoother.resolve(KlineValueRange(10.0, 20.0), 1f, "BTC"))
    }

    @Test
    fun `gesture isolates series ticks but applies every viewport range immediately`() {
        val rangeRef = KlineGestureValueRangeRef()
        val initialViewport = KlineViewport(rightEdgeOffsetPx = 0f)
        val movedViewport = initialViewport.copy(rightEdgeOffsetPx = 40f)
        val movedAgainViewport = initialViewport.copy(rightEdgeOffsetPx = 80f)
        assertEquals(
            KlineValueRange(100.0, 200.0),
            rangeRef.resolve(
                KlineValueRange(100.0, 200.0),
                initialViewport,
                freezeRealtimeTick = false,
            ),
        )
        val token = rangeRef.begin(initialViewport)

        // Same viewport: a realtime series tick cannot move the Y axis.
        assertEquals(
            KlineValueRange(100.0, 200.0),
            rangeRef.resolve(
                KlineValueRange(90.0, 210.0),
                initialViewport,
                freezeRealtimeTick = true,
            ),
        )
        // Same viewport but a structural series update (load more/new candle)
        // must update immediately rather than waiting for pointer-up.
        assertEquals(
            KlineValueRange(50.0, 250.0),
            rangeRef.resolve(
                KlineValueRange(50.0, 250.0),
                initialViewport,
                freezeRealtimeTick = false,
            ),
        )
        // Every viewport move adopts its newly calculated range immediately.
        assertEquals(
            KlineValueRange(50.0, 150.0),
            rangeRef.resolve(
                KlineValueRange(50.0, 150.0),
                movedViewport,
                freezeRealtimeTick = false,
            ),
        )
        assertEquals(
            KlineValueRange(75.0, 250.0),
            rangeRef.resolve(
                KlineValueRange(75.0, 250.0),
                movedAgainViewport,
                freezeRealtimeTick = false,
            ),
        )

        rangeRef.end(token)
        assertEquals(
            KlineValueRange(75.0, 250.0),
            rangeRef.resolve(
                KlineValueRange(75.0, 250.0),
                movedAgainViewport,
                freezeRealtimeTick = false,
            ),
        )
        assertEquals(
            KlineValueRange(80.0, 220.0),
            rangeRef.resolve(
                KlineValueRange(80.0, 220.0),
                movedAgainViewport,
                freezeRealtimeTick = false,
            ),
        )
    }

    @Test
    fun `latest candle range factor validates public bounds`() {
        assertEquals(0.18f, KlineChartRenderConfig(latestCandleRangeSmoothFactor = 0.18f).latestCandleRangeSmoothFactor)
        assertFailsWith<IllegalArgumentException> {
            KlineChartRenderConfig(latestCandleRangeSmoothFactor = 0.09f)
        }
        assertFailsWith<IllegalArgumentException> {
            KlineChartRenderConfig(latestCandleRangeSmoothFactor = 1.01f)
        }
    }

    @Test
    fun `historical page transition starts at displayed range and converges exactly`() {
        val smoother = KlineValueRangeSmoother()
        val previous = KlineValueRange(100.0, 200.0)
        val target = KlineValueRange(50.0, 250.0)
        assertEquals(previous, smoother.resolve(previous, 1f, "BTC"))

        assertEquals(
            previous,
            smoother.resolve(
                target = target,
                smoothFactor = 1f,
                resetKey = "BTC",
                transition = KlineValueRangeTransition("page-1", 0f),
            ),
        )
        assertEquals(
            KlineValueRange(75.0, 225.0),
            smoother.resolve(
                target = target,
                smoothFactor = 1f,
                resetKey = "BTC",
                transition = KlineValueRangeTransition("page-1", 0.5f),
            ),
        )
        assertEquals(target, smoother.resolve(target, 1f, "BTC"))
    }

    @Test
    fun `only a completed older-page append enables range transition`() {
        val previous = KlineSeries.of(listOf(candle(300L), candle(200L), candle(100L)))
        val appended = previous.appendOlder(listOf(candle(50L))).series

        assertTrue(
            shouldAnimateHistoricalRangeTransition(
                previousSeries = previous,
                series = appended,
                previousLoadingState = KlineLoadingState.LoadingMore,
                loadingState = KlineLoadingState.None,
            ),
        )
        assertFalse(
            shouldAnimateHistoricalRangeTransition(
                previousSeries = previous,
                series = appended,
                previousLoadingState = KlineLoadingState.None,
                loadingState = KlineLoadingState.None,
            ),
        )
        assertFalse(
            shouldAnimateHistoricalRangeTransition(
                previousSeries = previous,
                series = previous.updateLatest(candle(400L)).series,
                previousLoadingState = KlineLoadingState.LoadingMore,
                loadingState = KlineLoadingState.None,
            ),
        )
    }

    private fun candle(timestamp: Long): KlineCandle =
        KlineCandle(timestamp, 1.0, 2.0, 0.0, 1.0, 1.0)
}
