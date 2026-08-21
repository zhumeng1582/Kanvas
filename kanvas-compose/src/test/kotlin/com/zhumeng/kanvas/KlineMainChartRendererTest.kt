/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.geometry.Rect
import com.zhumeng.kanvas.core.IndexRange
import com.zhumeng.kanvas.core.KlineCandle
import com.zhumeng.kanvas.core.KlineSeries
import com.zhumeng.kanvas.core.KlineUiState
import com.zhumeng.kanvas.core.KlineViewport
import kotlin.test.Test
import kotlin.test.assertEquals

class KlineMainChartRendererTest {
    @Test
    fun `custom renderer context maps newest first indexes and values`() {
        val viewport = KlineViewport(candleWidthPx = 8f, candleSpacingPx = 2f)
        val state = KlineUiState(
            series = KlineSeries.of(
                listOf(
                    KlineCandle(2L, 10.0, 12.0, 8.0, 11.0, 1.0),
                    KlineCandle(1L, 9.0, 11.0, 7.0, 10.0, 1.0),
                ),
            ),
            viewport = viewport,
        )
        val context = KlineMainChartDrawContext(
            state = state,
            paintRange = IndexRange(0, 2),
            plotRect = Rect(0f, 10f, 100f, 110f),
            valueRange = KlineValueRange(0.0, 20.0),
            style = KlineChartStyle(),
            densityScale = 1f,
        )

        assertEquals(viewport.xForIndex(100f, 1.0) - viewport.candleHalfStepPx, context.xForIndex(1.0))
        assertEquals(60f, context.yForValue(10.0))
    }
}
