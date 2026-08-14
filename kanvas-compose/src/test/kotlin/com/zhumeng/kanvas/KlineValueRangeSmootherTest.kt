/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import com.zhumeng.kanvas.core.KlineCandle
import com.zhumeng.kanvas.core.KlineLoadingState
import com.zhumeng.kanvas.core.KlineSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KlineValueRangeSmootherTest {
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
