/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import com.zhumeng.kanvas.core.IndexRange
import com.zhumeng.kanvas.core.IndicatorColumn
import com.zhumeng.kanvas.core.IndicatorKey
import com.zhumeng.kanvas.core.IndicatorOutput
import com.zhumeng.kanvas.core.KlineCandle
import kotlin.test.Test
import kotlin.test.assertEquals

class KlineMainValueRangeTest {
    @Test
    fun `combine main outputs expand the Candle value range only across the visible paint range`() {
        val candles = listOf(
            candle(timestamp = 3, high = 11.0, low = 9.0),
            candle(timestamp = 2, high = 12.0, low = 10.0),
            candle(timestamp = 1, high = 13.0, low = 11.0),
        )
        val output = IndicatorOutput.of(
            key = IndicatorKey.computed("outside"),
            seriesSize = candles.size,
            columns = listOf(IndicatorColumn.of("line", doubleArrayOf(20.0, 8.0, 100.0))),
        )

        val range = candles.subList(0, 2).valueRangeIncluding(
            mainOutputs = listOf(output),
            paintRange = IndexRange(0, 2),
        )

        // The common range adds its normal 5% visual padding after combining
        // OHLC and indicators; values outside the visible paint range remain
        // excluded.
        assertEquals(7.4, range.minimum, 0.000_001)
        assertEquals(20.6, range.maximum, 0.000_001)
    }

    @Test
    fun `identical values use configured expansion ratios`() {
        val candles = listOf(candle(timestamp = 1, high = 100.0, low = 100.0))

        val range = candles.valueRangeIncluding(
            mainOutputs = emptyList(),
            paintRange = IndexRange(0, 1),
            sameValueExpansionRatios = listOf(0.2f, 0.1f),
        )

        assertEquals(88.5, range.minimum, 0.000_01)
        assertEquals(121.5, range.maximum, 0.000_01)
    }

    @Test
    fun `Candle-only range remains stable when switching main indicators`() {
        val candles = listOf(
            candle(timestamp = 2, high = 12.0, low = 10.0),
            candle(timestamp = 1, high = 11.0, low = 9.0),
        )
        val output = IndicatorOutput.of(
            key = IndicatorKey.computed("outside"),
            seriesSize = candles.size,
            columns = listOf(IndicatorColumn.of("line", doubleArrayOf(30.0, 5.0))),
        )

        val withoutIndicator = candles.valueRangeIncluding(
            mainOutputs = emptyList(),
            paintRange = IndexRange(0, candles.size),
        )
        val withIgnoredIndicator = candles.valueRangeIncluding(
            mainOutputs = listOf(output),
            paintRange = IndexRange(0, candles.size),
            rendererRanges = listOf(KlineIndicatorValueRange(minimum = 1.0, maximum = 40.0)),
            includeMainIndicators = false,
        )

        assertEquals(withoutIndicator, withIgnoredIndicator)
    }

    private fun candle(timestamp: Long, high: Double, low: Double): KlineCandle = KlineCandle(
        timestampMillis = timestamp,
        open = (high + low) / 2,
        high = high,
        low = low,
        close = (high + low) / 2,
        volume = 1.0,
    )
}
