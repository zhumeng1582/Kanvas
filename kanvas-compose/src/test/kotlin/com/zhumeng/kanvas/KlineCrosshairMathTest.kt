/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.zhumeng.kanvas.core.IndicatorDefinition
import com.zhumeng.kanvas.core.IndicatorKey
import com.zhumeng.kanvas.core.IndicatorPlacement
import com.zhumeng.kanvas.core.IndicatorRegistry
import com.zhumeng.kanvas.core.IndicatorRuntimeSnapshot
import com.zhumeng.kanvas.core.KlineCandle
import com.zhumeng.kanvas.core.KlineSeries
import com.zhumeng.kanvas.core.KlineUiState
import com.zhumeng.kanvas.core.KlineViewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KlineCrosshairMathTest {
    private val viewport = KlineViewport(candleWidthPx = 8f, candleSpacingPx = 2f)

    @Test
    fun `blank cross snaps to the nearest candle when blank options are disabled`() {
        val result = resolveKlineCrosshairX(
            viewport = viewport,
            plotRightPx = 300f,
            rawX = 320f,
            candleCount = 3,
            moveByCandleInBlank = false,
            showLatestTipsInBlank = false,
        )

        assertEquals(0, result.candleIndex)
        assertEquals(295f, result.snappedX)
    }

    @Test
    fun `blank cross keeps the vertical line on the latest candle`() {
        val result = resolveKlineCrosshairX(
            viewport = viewport,
            plotRightPx = 300f,
            rawX = 320f,
            candleCount = 3,
            moveByCandleInBlank = false,
            showLatestTipsInBlank = true,
        )

        assertEquals(0, result.candleIndex)
        assertEquals(295f, result.snappedX)
    }

    @Test
    fun `move by candle snaps blank cross to nearest edge candle`() {
        val rightBlank = resolveKlineCrosshairX(
            viewport = viewport,
            plotRightPx = 300f,
            rawX = 320f,
            candleCount = 3,
            moveByCandleInBlank = true,
            showLatestTipsInBlank = false,
        )
        val leftBlank = resolveKlineCrosshairX(
            viewport = viewport,
            plotRightPx = 300f,
            rawX = -20f,
            candleCount = 3,
            moveByCandleInBlank = true,
            showLatestTipsInBlank = false,
        )

        assertEquals(0, rightBlank.candleIndex)
        assertEquals(295f, rightBlank.snappedX)
        assertEquals(2, leftBlank.candleIndex)
        assertEquals(275f, leftBlank.snappedX)
    }

    @Test
    fun `native crosshair context retains the original pointer and older candle neighbour`() {
        val candles = listOf(4.0, 3.0, 2.0).mapIndexed { index, close ->
            KlineCandle(
                timestampMillis = (4 - index).toLong(),
                open = close,
                high = close,
                low = close,
                close = close,
                volume = close * 10.0,
            )
        }
        val state = KlineUiState(
            series = KlineSeries.of(candles),
            viewport = viewport,
        )

        val context = checkNotNull(
            resolveKlineIndicatorCrosshairContext(
                inputPosition = Offset(295f, 150f),
                state = state,
                plotRect = Rect(0f, 0f, 300f, 100f),
                renderConfig = KlineCrosshairRenderConfig(),
            ),
        )

        assertEquals(Offset(295f, 150f), context.inputPosition)
        assertEquals(Offset(295f, 100f), context.rawPosition)
        assertEquals(Offset(295f, 100f), context.position)
        assertEquals(0, context.candleIndex)
        assertEquals(candles[0], context.candle)
        assertEquals(candles[1], context.previousCandle)
    }

    @Test
    fun `native crosshair context clamps to the chart side of the price axis`() {
        val state = KlineUiState(
            series = KlineSeries.of(
                listOf(4.0, 3.0, 2.0).mapIndexed { index, close ->
                    KlineCandle(
                        timestampMillis = (4 - index).toLong(),
                        open = close,
                        high = close,
                        low = close,
                        close = close,
                        volume = close * 10.0,
                    )
                },
            ),
            viewport = viewport,
        )

        val context = checkNotNull(
            resolveKlineIndicatorCrosshairContext(
                inputPosition = Offset(340f, 50f),
                state = state,
                plotRect = Rect(0f, 0f, 300f, 100f),
                renderConfig = KlineCrosshairRenderConfig(),
                crosshairRightPx = 240f,
            ),
        )

        assertEquals(Offset(240f, 50f), context.rawPosition)
        assertEquals(275f, context.position.x)
        assertEquals(50f, context.position.y)
    }

    @Test
    fun `crosshair context keeps the earlier positional constructor source compatible`() {
        val raw = Offset(12f, 34f)
        val context = KlineIndicatorCrosshairContext(
            raw,
            Offset(56f, 34f),
            0,
            null,
            null,
        )

        assertEquals(raw, context.inputPosition)
    }

    @Test
    fun `crosshair callback samples the same renderer-expanded main range as Canvas`() {
        val external = IndicatorDefinition(key = IndicatorKey.external("orders"), placement = IndicatorPlacement.Main)
        val registry = IndicatorRegistry()
        val selected = registry.mount(listOf(external), restoredActiveKeys = listOf(external.key))
        val state = KlineUiState(
            series = KlineSeries.of(
                listOf(4.0, 3.0, 2.0, 1.0).mapIndexed { index, close ->
                    KlineCandle(
                        timestampMillis = (4 - index).toLong(),
                        open = close,
                        high = close,
                        low = close,
                        close = close,
                        volume = close * 10.0,
                    )
                },
            ),
            viewport = KlineViewport(candleWidthPx = 8f, candleSpacingPx = 2f),
        )
        val renderer = object : KlineIndicatorRenderer {
            override fun supports(
                definition: IndicatorDefinition,
                output: com.zhumeng.kanvas.core.IndicatorOutput?,
            ): Boolean = definition.key == external.key

            override fun draw(scope: DrawScope, context: KlineIndicatorDrawContext) = Unit

            override fun visibleValueRange(context: KlineIndicatorRangeContext): KlineIndicatorValueRange =
                KlineIndicatorValueRange(1000.0, 2000.0)
        }
        val snapshot: IndicatorRuntimeSnapshot? = null
        val plan = snapshot.resolveIndicatorPanePlan(
            state = state,
            registry = selected,
            renderers = KlineIndicatorRendererRegistry(listOf(renderer)),
        )
        val plotRect = Rect(0f, 0f, 100f, 100f)
        val mainRange = checkNotNull(
            resolveKlineMainRenderRange(
                state = state,
                plotRect = plotRect,
                renderConfig = KlineChartRenderConfig(),
                indicatorPanePlan = plan,
                hideMainIndicators = false,
                densityScale = 1f,
            ),
        )

        val crosshair = checkNotNull(
            Offset(50f, 50f).toKlineCrosshair(
                state = state,
                plotRect = plotRect,
                renderConfig = KlineChartRenderConfig(),
                valueRange = mainRange.valueRange,
            ),
        )
        val expected = mainRange.valueRange.minimum + mainRange.valueRange.span / 2.0

        assertEquals(expected, checkNotNull(crosshair.value), 1e-9)
        assertTrue(checkNotNull(crosshair.value) > 900.0)
    }
}
