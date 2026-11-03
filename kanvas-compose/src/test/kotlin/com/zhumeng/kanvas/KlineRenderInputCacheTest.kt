/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import com.zhumeng.kanvas.core.IndexRange
import com.zhumeng.kanvas.core.IndicatorColumn
import com.zhumeng.kanvas.core.IndicatorDefinition
import com.zhumeng.kanvas.core.IndicatorKey
import com.zhumeng.kanvas.core.IndicatorOutput
import com.zhumeng.kanvas.core.KlineCandle
import com.zhumeng.kanvas.core.KlineSeries
import com.zhumeng.kanvas.core.MovingAverage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class KlineRenderInputCacheTest {
    @Test
    fun `offscreen latest ticks reuse historical extrema across new output containers`() {
        val cache = KlineMainRawRangeCache()
        var series = series()
        val output = output(30.0)
        val range = IndexRange(1, 3)
        val first = cache.resolve(series, listOf(output), range)
        repeat(20) { tick ->
            series = series.updateLatest(series[0].copy(high = 100.0 + tick)).series
            assertSame(first, cache.resolve(series, listOf(output), range))
        }
    }

    @Test
    fun `visible latest extrema expand and shrink without stale cache values`() {
        val cache = KlineMainRawRangeCache()
        val initial = series()
        val range = IndexRange(0, 3)
        val first = cache.resolve(initial, emptyList(), range)
        val expanded = initial.updateLatest(initial[0].copy(high = 500.0)).series
        assertEquals(500.0, cache.resolve(expanded, emptyList(), range)?.maximum)
        val shrunk = expanded.updateLatest(initial[0]).series
        assertEquals(first, cache.resolve(shrunk, emptyList(), range))
    }

    @Test
    fun `history corrections new candles and appended pages invalidate source identity`() {
        val cache = KlineMainRawRangeCache()
        val initial = series()
        val range = IndexRange(1, 3)
        val first = cache.resolve(initial, emptyList(), range)
        val corrected = KlineSeries.of(initial.candles.mapIndexed { index, candle ->
            if (index == 1) candle.copy(low = 1.0) else candle
        })
        assertEquals(1.0, cache.resolve(corrected, emptyList(), range)?.minimum)
        val rebuilt = KlineSeries.of(initial.candles)
        assertNotSame(first, cache.resolve(rebuilt, emptyList(), range))
        val prepended = initial.updateLatest(initial[0].copy(timestampMillis = 4)).series
        assertEquals(
            expected(prepended, emptyList(), range),
            cache.resolve(prepended, emptyList(), range),
        )
        val appended = initial.appendOlder(listOf(initial[2].copy(timestampMillis = 0, low = 2.0))).series
        assertEquals(2.0, cache.resolve(appended, emptyList(), IndexRange(1, 4))?.minimum)
    }

    @Test
    fun `changed indicator output and visibility refresh extrema even in history`() {
        val cache = KlineMainRawRangeCache()
        val series = series()
        val range = IndexRange(1, 3)
        assertEquals(30.0, cache.resolve(series, listOf(output(30.0)), range)?.maximum)
        assertEquals(80.0, cache.resolve(series, listOf(output(80.0)), range)?.maximum)
        assertEquals(expected(series, emptyList(), range), cache.resolve(series, emptyList(), range))
        assertNull(cache.resolve(KlineSeries.Empty, emptyList(), IndexRange.Empty))
    }

    @Test
    fun `cached and uncached ranges agree through mixed data and viewport changes`() {
        val cache = KlineMainRawRangeCache(capacity = 2)
        var series = series()
        repeat(30) { step ->
            series = when (step % 3) {
                0 -> series.updateLatest(series[0].copy(high = 20.0 + step)).series
                1 -> series.updateLatest(series[0].copy(timestampMillis = series[0].timestampMillis + 1)).series
                else -> KlineSeries.of(series.candles.mapIndexed { index, candle ->
                    if (index == 2) candle.copy(low = 5.0) else candle
                })
            }
            for (start in 0 until series.size) {
                val range = IndexRange(start, minOf(series.size, start + 3))
                assertEquals(expected(series, emptyList(), range), cache.resolve(series, emptyList(), range))
            }
        }
    }

    @Test
    fun `identical resolved plans retain item and pane identities`() {
        val cache = KlineIndicatorPanePlanCache()
        val item = item()
        val first = plan(item)
        assertSame(first, cache.retain(first))
        val retained = cache.retain(plan(item.copy()))
        assertSame(first, retained)
        assertSame(first.subByPane.getValue("a"), retained.subByPane.getValue("a"))
    }

    @Test
    fun `new output identity pending output and definition updates cannot reuse a plan`() {
        val cache = KlineIndicatorPanePlanCache()
        val item = item()
        var previous = cache.retain(plan(item))
        // Numerically equal fresh output must not trigger a deep column comparison.
        listOf(item.copy(output = output(30.0)), item.copy(output = null), item.copy(colorIndex = 2),
            item.copy(definition = item.definition.copy(zIndex = 10)),
        ).forEach { changed ->
            val next = cache.retain(plan(changed))
            assertNotSame(previous, next)
            previous = next
        }
    }

    @Test
    fun `sub pane reordering and fresh invalidation cache preserve lifecycle semantics`() {
        val item = item()
        val cache = KlineIndicatorPanePlanCache()
        val first = cache.retain(plan(item))
        val reordered = first.copy(subByPane = linkedMapOf("b" to listOf(item), "a" to listOf(item)))
        assertSame(reordered, cache.retain(reordered))
        val fresh = plan(item)
        assertSame(fresh, KlineIndicatorPanePlanCache().retain(fresh))
    }

    private fun expected(series: KlineSeries, outputs: List<IndicatorOutput>, range: IndexRange) =
        series.candles.rawValueRangeIncluding(range, outputs, range)

    private fun series() = KlineSeries.of(List(3) { index ->
        KlineCandle((3 - index).toLong(), 10.0, 12.0 + index, 8.0 - index, 11.0, 1.0)
    })

    private fun output(value: Double) = IndicatorOutput.of(
        key = IndicatorKey.computed("test"),
        seriesSize = 3,
        columns = listOf(IndicatorColumn.of("line", doubleArrayOf(value, value, value))),
    )

    private fun item() = KlineIndicatorRenderItem(
        definition = IndicatorDefinition(IndicatorKey.computed("test"), calculator = MovingAverage(listOf(2))),
        output = output(30.0),
        renderer = KlineComputedLineIndicatorRenderer,
        declarationOrder = 0,
    )

    private fun plan(item: KlineIndicatorRenderItem) = KlineIndicatorPanePlan(
        mainPaintOrder = listOf(item),
        subByPane = linkedMapOf("a" to listOf(item), "b" to listOf(item)),
        subPaneLayoutHints = linkedMapOf("a" to listOf(item.definition.layoutHint), "b" to listOf(item.definition.layoutHint)),
        unsupportedDefinitions = emptyList(),
    )
}
