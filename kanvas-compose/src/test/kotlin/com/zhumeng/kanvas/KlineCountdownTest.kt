/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.graphics.Color
import com.zhumeng.kanvas.core.KlineCandle
import com.zhumeng.kanvas.core.KlineInterval
import com.zhumeng.kanvas.core.KlineTimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class KlineCountdownTest {
    private val candle = KlineCandle(
        timestampMillis = 1_000_000L,
        open = 1.0,
        high = 1.0,
        low = 1.0,
        close = 1.0,
        volume = 1.0,
    )

    @Test
    fun `countdown formats minute hour and day durations`() {
        assertEquals(
            "10:09",
            resolveKlineCountdownText(
                candle = candle,
                interval = KlineInterval(11, KlineTimeUnit.Minute),
                nowMillis = candle.timestampMillis + 51_000L,
            ),
        )
        assertEquals(
            "05:05:10",
            resolveKlineCountdownText(
                candle = candle,
                interval = KlineInterval(6, KlineTimeUnit.Hour),
                nowMillis = candle.timestampMillis + 54L * 60L * 1_000L + 50_000L,
            ),
        )
        assertEquals(
            "31D:01H",
            resolveKlineCountdownText(
                candle = candle,
                interval = KlineInterval(32, KlineTimeUnit.Day),
                nowMillis = candle.timestampMillis + 23L * 60L * 60L * 1_000L,
            ),
        )
    }

    @Test
    fun `countdown hides one-second invalid expired and missing inputs`() {
        assertNull(resolveKlineCountdownText(candle, KlineInterval(1, KlineTimeUnit.Second), candle.timestampMillis))
        assertNull(resolveKlineCountdownText(candle, KlineInterval.Invalid, candle.timestampMillis))
        assertNull(
            resolveKlineCountdownText(
                candle,
                KlineInterval(1, KlineTimeUnit.Minute),
                candle.timestampMillis + 60_000L,
            ),
        )
        assertNull(resolveKlineCountdownText(null, KlineInterval(1, KlineTimeUnit.Minute), candle.timestampMillis))
        assertNull(resolveKlineCountdownText(candle, null, candle.timestampMillis))
    }

    @Test
    fun `countdown floors remaining duration below one second`() {
        assertEquals(
            "00:00",
            resolveKlineCountdownText(
                candle = candle,
                interval = KlineInterval(2, KlineTimeUnit.Second),
                nowMillis = candle.timestampMillis + 1_999L,
            ),
        )
    }

    @Test
    fun `countdown config rejects invalid dimensions`() {
        assertFailsWith<IllegalArgumentException> {
            KlineCountdownRenderConfig(text = KlineTextAreaRenderConfig(borderWidthPx = -1f))
        }
        assertFailsWith<IllegalArgumentException> {
            KlineCountdownRenderConfig(text = KlineTextAreaRenderConfig(fontSizeSp = 0f))
        }
        assertEquals(KlineTextAlign.Center, KlineCountdownRenderConfig().text.textAlign)
        assertEquals(2f, KlineCountdownRenderConfig().text.padding.topPx)
        assertEquals(Color(0xFF273447), KlineChartStyle().countdownBackground)
    }
}
