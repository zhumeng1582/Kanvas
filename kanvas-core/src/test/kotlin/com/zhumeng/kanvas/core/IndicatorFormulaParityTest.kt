package com.zhumeng.kanvas.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IndicatorFormulaParityTest {
    @Test
    fun `RSI uses Wilder smoothing and returns neutral value for a flat market`() {
        val chronological = listOf(
            44.34, 44.09, 44.15, 43.61, 44.33, 44.83, 45.10, 45.42,
            45.84, 46.08, 45.89, 46.03, 45.61, 46.28, 46.28,
        )
        val output = calculate(Rsi(14), series(chronological.reversed()))
        assertEquals(70.464135, output.column("rsi_14")!![0], 0.000001)

        val flat = calculate(Rsi(5), series(List(12) { 100.0 }))
        assertEquals(50.0, flat.column("rsi_5")!![0], 0.0)
    }

    @Test
    fun `MACD histogram uses the exchange double-DIF-minus-DEA convention`() {
        val output = calculate(Macd(), series(List(80) { index -> 100.0 + index * 0.7 + (index % 5) }))
        val dif = output.column("macd")!!
        val dea = output.column("signal")!!
        val histogram = output.column("histogram")!!
        for (index in 0 until output.seriesSize) {
            if (dif[index].isFinite() && dea[index].isFinite()) {
                assertEquals(2.0 * (dif[index] - dea[index]), histogram[index], 1e-12)
            }
        }
    }

    @Test
    fun `KDJ applies independent K and D smoothing periods`() {
        val candles = List(40) { index -> 100.0 + kotlin.math.sin(index / 3.0) * 8.0 + index * 0.2 }
        val fastD = calculate(Kdj(period = 9, smoothing = 3, dSmoothing = 2), series(candles))
        val slowD = calculate(Kdj(period = 9, smoothing = 3, dSmoothing = 7), series(candles))
        assertTrue(abs(fastD.column("d")!![0] - slowD.column("d")!![0]) > 0.0001)
    }

    @Test
    fun `BOLL columns follow UP MB DN style order`() {
        val output = calculate(BollingerBands(period = 5), series(List(12) { it.toDouble() + 10.0 }))
        assertEquals(listOf("boll_upper", "boll_mid", "boll_lower"), output.columns().map(IndicatorColumn::name))
    }

    @Test
    fun `SuperTrend exposes mutually exclusive rising and falling segments`() {
        val closes = List(100) { index -> 100.0 + kotlin.math.sin(index / 5.0) * 12.0 }
        val output = calculate(SuperTrend(atrPeriod = 7), series(closes))
        val up = output.column(SuperTrend.UpColumn)!!
        val down = output.column(SuperTrend.DownColumn)!!
        for (index in 0 until output.seriesSize) {
            assertTrue(!(up[index].isFinite() && down[index].isFinite()))
        }
        assertTrue(up.asList().any(Double::isFinite))
        assertTrue(down.asList().any(Double::isFinite))
    }

    @Test
    fun `OBV moving-average options produce real overlay columns`() {
        val output = calculate(
            OnBalanceVolumeWithAverages(maPeriod = 5, emaPeriod = 7),
            series(List(30) { index -> 100.0 + kotlin.math.sin(index / 2.0) * 5.0 }),
        )
        assertEquals(listOf("obv", "obv_ma_5", "obv_ema_7"), output.columns().map(IndicatorColumn::name))
        assertTrue(output.column("obv_ma_5")!![0].isFinite())
        assertTrue(output.column("obv_ema_7")!![0].isFinite())
    }

    private fun calculate(calculator: IndicatorCalculator, series: KlineSeries): IndicatorOutput {
        val definition = IndicatorDefinition(IndicatorKey.computed("formula"), calculator = calculator)
        return checkNotNull(IndicatorRuntime().calculate(series = series, definitions = listOf(definition)).output(definition.key))
    }

    private fun series(newestFirstCloses: List<Double>): KlineSeries = KlineSeries.of(
        newestFirstCloses.mapIndexed { index, close ->
            KlineCandle(
                timestampMillis = 2_000_000_000_000L - index * 60_000L,
                open = close,
                high = close + 2.0,
                low = close - 2.0,
                close = close,
                volume = 100.0 + index,
            )
        },
    )
}
