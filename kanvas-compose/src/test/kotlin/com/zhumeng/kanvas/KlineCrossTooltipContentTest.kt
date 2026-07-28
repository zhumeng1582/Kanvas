/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.zhumeng.kanvas.core.KlineCandle
import com.zhumeng.kanvas.core.KlineSpec
import com.zhumeng.kanvas.core.KlineInterval
import com.zhumeng.kanvas.core.KlineTimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KlineCrossTooltipContentTest {
    @Test
    fun `default tooltip includes market rows and optional turnover`() {
        val context = tooltipContext(
            candle = KlineCandle(
                timestampMillis = 1L,
                open = 10.0,
                high = 14.0,
                low = 9.0,
                close = 12.0,
                volume = 2_500.0,
                turnover = 12_500.0,
            ),
            previous = KlineCandle(0L, 8.0, 11.0, 7.0, 10.0, 1.0),
        )

        val rows = defaultKlineCrossTooltipItems(context, KlineChartStyle())

        assertEquals(
            listOf("Time", "Open", "High", "Low", "Close", "Chg", "%Chg", "Range", "Amount", "Turnover"),
            rows.map(KlineCrossTooltipItem::label),
        )
        assertEquals("12.00", rows.first { it.label == "Close" }.value)
        assertEquals("20.00%", rows.first { it.label == "%Chg" }.value)
        assertEquals("50.00%", rows.first { it.label == "Range" }.value)
        assertEquals("2.50K", rows.first { it.label == "Amount" }.value)
        assertEquals("12.50K", rows.first { it.label == "Turnover" }.value)
        assertEquals(KlineChartStyle().bullish, rows.first { it.label == "%Chg" }.valueStyle?.color)
    }

    @Test
    fun `default native tooltip omits absent turnover and uses absolute range without previous candle`() {
        val context = tooltipContext(
            candle = KlineCandle(1L, 10.0, 14.0, 9.0, 8.0, 20.0),
            previous = null,
        )

        val rows = defaultKlineCrossTooltipItems(context, KlineChartStyle())

        assertFalse(rows.any { it.label == "Turnover" })
        assertEquals("5.00", rows.first { it.label == "Range" }.value)
        assertEquals(KlineChartStyle().bearish, rows.first { it.label == "%Chg" }.valueStyle?.color)
    }

    @Test
    fun `actionable row consumes the layout hit and nonactionable row does not`() {
        val layout = checkNotNull(
            layoutKlineCrossTooltip(
                plotRect = Rect(0f, 0f, 100f, 100f),
                crossX = 90f,
                itemMeasurements = listOf(
                    KlineCrossTooltipItemMeasurement(Size(10f, 10f), Size(10f, 10f)),
                    KlineCrossTooltipItemMeasurement(Size(10f, 10f), Size(10f, 10f)),
                ),
                padding = KlinePanePadding(),
                margin = KlinePanePadding(),
                columnSpacingPx = 0f,
            ),
        )
        var calls = 0
        val items = listOf(
            KlineCrossTooltipItem("Idle", "0"),
            KlineCrossTooltipItem("Action", "1", onClick = { calls += 1 }),
        )

        assertFalse(dispatchKlineCrossTooltipItemTap(Offset(5f, 5f), layout, items, 0f))
        assertTrue(dispatchKlineCrossTooltipItemTap(Offset(5f, 15f), layout, items, 0f))
        assertEquals(1, calls)
    }

    @Test
    fun `expanded idle row cannot mask an earlier actionable row`() {
        val layout = checkNotNull(
            layoutKlineCrossTooltip(
                plotRect = Rect(0f, 0f, 100f, 100f),
                crossX = 90f,
                itemMeasurements = listOf(
                    KlineCrossTooltipItemMeasurement(Size(10f, 10f), Size(10f, 10f)),
                    KlineCrossTooltipItemMeasurement(Size(10f, 10f), Size(10f, 10f)),
                ),
                padding = KlinePanePadding(),
                margin = KlinePanePadding(),
                columnSpacingPx = 0f,
            ),
        )
        var calls = 0
        val items = listOf(
            KlineCrossTooltipItem("Action", "0", onClick = { calls += 1 }),
            KlineCrossTooltipItem("Idle", "1"),
        )

        // y=8 is inside row 0 and, after 3px inflation, also inside row 1.
        assertTrue(dispatchKlineCrossTooltipItemTap(Offset(5f, 8f), layout, items, 3f))
        assertEquals(1, calls)
    }

    private fun tooltipContext(
        candle: KlineCandle,
        previous: KlineCandle?,
    ): KlineCrossTooltipContext = KlineCrossTooltipContext(
        crosshair = KlineIndicatorCrosshairContext(
            rawPosition = Offset.Zero,
            position = Offset.Zero,
            candleIndex = 0,
            candle = candle,
            previousCandle = previous,
            inputPosition = Offset.Zero,
        ),
        spec = KlineSpec(
            symbol = "BTC-USDT",
            interval = KlineInterval(1, KlineTimeUnit.Minute),
            precision = 2,
        ),
        timeLabel = "2026/1/1",
    )
}
