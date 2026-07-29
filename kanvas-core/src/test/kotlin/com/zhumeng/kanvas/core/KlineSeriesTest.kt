package com.zhumeng.kanvas.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KlineSeriesTest {
    @Test
    fun `of accepts strictly newest first data without normalization`() {
        val candles = listOf(candle(300L), candle(200L), candle(100L))
        val series = KlineSeries.of(candles)

        assertEquals(candles, series.candles)
    }

    @Test
    fun `of rejects unordered or duplicate input`() {
        assertFailsWith<IllegalArgumentException> {
            KlineSeries.of(
                listOf(
                    candle(100L),
                    candle(300L),
                    candle(200L),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            KlineSeries.of(
                listOf(
                    candle(300L),
                    candle(200L),
                    candle(200L),
                ),
            )
        }
    }

    @Test
    fun `append older adds a normalized page without sorting or overlap reconciliation`() {
        val initial = KlineSeries.of(listOf(candle(500L), candle(400L), candle(300L)))

        val historical = initial.appendOlder(listOf(candle(200L), candle(100L)))

        assertEquals(listOf(500L, 400L, 300L, 200L, 100L), historical.series.candles.map(KlineCandle::timestampMillis))
        assertEquals(IndexRange(3, 5), historical.changedRange)
    }

    @Test
    fun `append older rejects overlap duplicate and malformed page ordering`() {
        val series = KlineSeries.of(listOf(candle(500L), candle(400L), candle(300L)))

        assertFailsWith<IllegalArgumentException> {
            series.appendOlder(listOf(candle(300L), candle(200L)))
        }
        assertFailsWith<IllegalArgumentException> {
            series.appendOlder(listOf(candle(100L), candle(200L)))
        }
        assertFailsWith<IllegalArgumentException> {
            series.appendOlder(listOf(candle(200L), candle(200L)))
        }
    }

    @Test
    fun `latest update replaces or prepends only at newest edge`() {
        val initial = KlineSeries.of(listOf(candle(300L), candle(200L), candle(100L)))
        val replaced = initial.updateLatest(candle(300L, close = 30.0))
        assertEquals(30.0, replaced.series.latest?.close)
        assertEquals(IndexRange(0, 1), replaced.changedRange)

        val prepended = replaced.series.updateLatest(candle(400L))
        assertEquals(listOf(400L, 300L, 200L, 100L), prepended.series.candles.map { it.timestampMillis })
        assertEquals(IndexRange(0, 4), prepended.changedRange)

        assertFailsWith<IllegalArgumentException> {
            prepended.series.updateLatest(candle(250L))
        }
    }

    @Test
    fun `many edge updates preserve random access and binary timestamp lookup`() {
        var series = KlineSeries.of((100L downTo 1L).map(::candle))
        repeat(1_000) { offset ->
            series = series.updateLatest(candle(101L + offset)).series
            // Exercise the common sequence of many realtime replacements
            // between candle boundaries without building wrapper chains.
            series = series.updateLatest(candle(101L + offset, close = offset.toDouble())).series
        }
        repeat(10) { page ->
            val newest = -page * 100L
            val incoming = (newest downTo newest - 99L).map(::candle)
            series = series.appendOlder(incoming).series
        }

        assertEquals(2_100, series.size)
        assertEquals(1_100L, series.latest?.timestampMillis)
        assertEquals(-999L, series.oldest?.timestampMillis)
        assertEquals(1_099L, series[1].timestampMillis)
        assertEquals(1_000, series.indexAtOrBefore(100L))
        assertTrue(series.candles is RandomAccess)
    }

    @Test
    fun `index and fractional timestamp mapping are stable across gaps`() {
        val series = KlineSeries.of(listOf(candle(500L), candle(400L), candle(100L)))

        assertEquals(1, series.indexAtOrBefore(450L))
        assertEquals(2, series.indexAtOrBefore(250L))
        assertEquals(0, series.indexAtOrBefore(500L))
        assertNull(series.indexAtOrBefore(501L))
        assertNull(series.indexAtOrBefore(99L))

        assertEquals(0.5, series.timestampToFractionalIndex(450L))
        assertEquals(1.5, series.timestampToFractionalIndex(250L))
        assertEquals(450L, series.fractionalIndexToTimestamp(0.5))
        assertEquals(250L, series.fractionalIndexToTimestamp(1.5))
        assertNull(series.fractionalIndexToTimestamp(-0.1))
        assertNull(series.fractionalIndexToTimestamp(3.0))
    }

    private fun candle(
        timestampMillis: Long,
        close: Double = timestampMillis.toDouble(),
    ): KlineCandle = KlineCandle(
        timestampMillis = timestampMillis,
        open = close - 1.0,
        high = close + 2.0,
        low = close - 2.0,
        close = close,
        volume = 10.0,
    )
}
