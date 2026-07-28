/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
