package com.zhumeng.kanvas.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AdvancedIndicatorsTest {
    @Test
    fun `AVL OBV and Williams R follow newest-first candles`() {
        val series = KlineSeries.of(
            listOf(
                candle(2L, close = 12.0, high = 13.0, low = 11.0, volume = 2.0, turnover = 24.0),
                candle(1L, close = 10.0, high = 11.0, low = 9.0, volume = 1.0, turnover = 10.0),
            ),
        )

        val avl = calculate(AverageValueLine, series).column(AverageValueLine.ColumnName)!!
        val obv = calculate(OnBalanceVolume, series).column(OnBalanceVolume.ColumnName)!!
        val wr = calculate(WilliamsR(2), series).column(WilliamsR.columnName(2))!!

        assertEquals(10.0, avl[1])
        assertEquals(34.0 / 3.0, avl[0])
        assertEquals(0.0, obv[1])
        assertEquals(2.0, obv[0])
        assertEquals(-25.0, wr[0])
    }

    @Test
    fun `stateful trend and oscillator outputs become finite`() {
        val series = waveSeries(120)
        val sar = calculate(ParabolicSar(), series).column(ParabolicSar.ColumnName)!!
        val superTrend = calculate(SuperTrend(), series).column(SuperTrend.ColumnName)!!
        val stoch = calculate(StochasticRsi(), series)

        assertTrue(sar.asList().all(Double::isFinite))
        assertTrue(superTrend.asList().any(Double::isFinite))
        assertTrue(stoch.column(StochasticRsi.KColumn)!!.asList().filter(Double::isFinite).all { it in 0.0..100.0 })
        assertTrue(stoch.column(StochasticRsi.DColumn)!!.asList().filter(Double::isFinite).all { it in 0.0..100.0 })
    }

    @Test
    fun `advanced indicators latest replacement equals full recalculation`() {
        val original = waveSeries(160)
        val latest = checkNotNull(original.latest)
        val updated = original.updateLatest(
            latest.copy(
                high = latest.high + 6.0,
                low = latest.low - 4.0,
                close = latest.close + 2.5,
                volume = latest.volume + 300.0,
                turnover = (latest.turnover ?: latest.close * latest.volume) + 50_000.0,
            ),
        ).series
        val calculators = listOf(
            ParabolicSar(),
            AverageValueLine,
            SuperTrend(),
            OnBalanceVolume,
            WilliamsR(),
            StochasticRsi(),
        )

        calculators.forEachIndexed { index, calculator ->
            val definition = IndicatorDefinition(IndicatorKey.computed("advanced_$index"), calculator = calculator)
            val runtime = IndicatorRuntime()
            val previous = runtime.calculate(series = original, definitions = listOf(definition))
            val incremental = runtime.calculate(previous, updated, listOf(definition))
            val fresh = runtime.calculate(series = updated, definitions = listOf(definition))
            assertEquals(fresh.output(definition.key), incremental.output(definition.key), calculator.toString())
        }
    }

    @Test
    fun `advanced indicator parameters are validated`() {
        assertFailsWith<IllegalArgumentException> { ParabolicSar(step = 0.0) }
        assertFailsWith<IllegalArgumentException> { ParabolicSar(step = 0.2, maximum = 0.1) }
        assertFailsWith<IllegalArgumentException> { SuperTrend(atrPeriod = 0) }
        assertFailsWith<IllegalArgumentException> { WilliamsR(0) }
        assertFailsWith<IllegalArgumentException> { StochasticRsi(dPeriod = 0) }
    }

    private fun calculate(calculator: IndicatorCalculator, series: KlineSeries): IndicatorOutput {
        val definition = IndicatorDefinition(IndicatorKey.computed("test"), calculator = calculator)
        return IndicatorRuntime().calculate(series = series, definitions = listOf(definition)).output(definition.key)!!
    }

    private fun waveSeries(size: Int): KlineSeries = KlineSeries.of(
        List(size) { index ->
            val base = 10_000.0 + kotlin.math.sin(index / 5.0) * 120.0 + index * 0.7
            candle(
                timestamp = 2_000_000_000_000L - index * 60_000L,
                close = base,
                high = base + 15.0 + index % 4,
                low = base - 14.0 - index % 3,
                volume = 100.0 + index,
                turnover = base * (100.0 + index),
            )
        },
    )

    private fun candle(
        timestamp: Long,
        close: Double,
        high: Double,
        low: Double,
        volume: Double,
        turnover: Double?,
    ): KlineCandle = KlineCandle(
        timestampMillis = timestamp,
        open = close - 1.0,
        high = high,
        low = low,
        close = close,
        volume = volume,
        turnover = turnover,
    )
}
