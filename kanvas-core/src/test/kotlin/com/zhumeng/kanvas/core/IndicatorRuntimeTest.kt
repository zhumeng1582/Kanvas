package com.zhumeng.kanvas.core

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IndicatorRuntimeTest {
    @Test
    fun `accurate mode uses retained decimal values and invalidates fast result reuse`() {
        val definition = IndicatorDefinition(
            key = IndicatorKey.computed("ma"),
            calculator = MovingAverage(listOf(3)),
        )
        val series = KlineSeries.of(
            listOf(
                accurateCandle(3L, "10000000000000000"),
                accurateCandle(2L, "1"),
                accurateCandle(1L, "-10000000000000000"),
            ),
        )
        val runtime = IndicatorRuntime()
        val fast = runtime.calculate(
            series = series,
            definitions = listOf(definition),
            computeMode = KlineComputeMode.Fast,
        )
        val accurate = runtime.calculate(
            previous = fast,
            series = series,
            definitions = listOf(definition),
            computeMode = KlineComputeMode.Accurate,
        )

        assertEquals(0.0, fast.output(definition.key)!!.column("ma_3")!![0])
        assertEquals(1.0 / 3.0, accurate.output(definition.key)!!.column("ma_3")!![0], 1e-15)
        assertEquals(KlineComputeMode.Accurate, accurate.computeMode)
    }

    @Test
    fun `moving average follows newest first order and supports multiple periods`() {
        val definition = IndicatorDefinition(
            key = IndicatorKey.computed("ma", "MA"),
            calculator = MovingAverage(listOf(2, 3)),
        )
        val series = seriesOfCloses(5.0, 4.0, 3.0, 2.0, 1.0)

        val output = IndicatorRuntime().calculate(series = series, definitions = listOf(definition))
            .output(definition.key)!!

        assertEquals(listOf(4.5, 3.5, 2.5, 1.5, Double.NaN), output.column("ma_2")!!.asList())
        assertEquals(listOf(4.0, 3.0, 2.0, Double.NaN, Double.NaN), output.column("ma_3")!!.asList())
    }

    private fun accurateCandle(timestamp: Long, close: String): KlineCandle {
        val value = BigDecimal(close)
        return KlineCandle.accurate(timestamp, value, value, value, value, BigDecimal.ONE)
    }

    @Test
    fun `moving average warmup is nan at oldest end of newest first series`() {
        val definition = IndicatorDefinition(
            key = IndicatorKey.computed("ma", "MA"),
            calculator = MovingAverage(listOf(3)),
        )
        val output = IndicatorRuntime().calculate(
            series = seriesOfCloses(4.0, 3.0, 2.0, 1.0),
            definitions = listOf(definition),
        ).output(definition.key)!!.column("ma_3")!!

        assertEquals(3.0, output[0])
        assertEquals(2.0, output[1])
        assertTrue(output[2].isNaN())
        assertTrue(output[3].isNaN())
    }

    @Test
    fun `volume output is aligned and newest first`() {
        val definition = IndicatorDefinition(
            key = IndicatorKey.computed("volume", "Volume"),
            placement = IndicatorPlacement.Sub(),
            calculator = Volume,
        )
        val series = KlineSeries.of(
            listOf(
                candle(timestamp = 300L, close = 3.0, volume = 30.0),
                candle(timestamp = 200L, close = 2.0, volume = 20.0),
                candle(timestamp = 100L, close = 1.0, volume = 10.0),
            ),
        )

        val output = IndicatorRuntime().calculate(series = series, definitions = listOf(definition))
            .output(definition.key)!!

        assertEquals(listOf(30.0, 20.0, 10.0), output.column(Volume.ColumnName)!!.asList())
    }

    @Test
    fun `runtime gives calculator old and new series plus prior output`() {
        val seenPreviousSeries = mutableListOf<KlineSeries?>()
        val seenPreviousOutput = mutableListOf<IndicatorOutput?>()
        val calculator = IndicatorCalculator { input ->
            seenPreviousSeries += input.previousSeries
            seenPreviousOutput += input.previousOutput
            IndicatorOutput.of(
                key = input.definition.key,
                seriesSize = input.series.size,
                columns = listOf(IndicatorColumn.of("close", input.series.candles.map(KlineCandle::close))),
            )
        }
        val definition = IndicatorDefinition(
            key = IndicatorKey.computed("test"),
            calculator = calculator,
        )
        val runtime = IndicatorRuntime()
        val oldSeries = seriesOfCloses(3.0, 2.0, 1.0)
        val first = runtime.calculate(series = oldSeries, definitions = listOf(definition))
        val newSeries = seriesOfCloses(4.0, 3.0, 2.0, 1.0)

        runtime.calculate(previous = first, series = newSeries, definitions = listOf(definition))

        assertEquals(listOf(null, oldSeries), seenPreviousSeries)
        assertNull(seenPreviousOutput[0])
        assertSame(first.output(definition.key), seenPreviousOutput[1])
    }

    @Test
    fun `unchanged input safely reuses immutable previous output`() {
        val definition = IndicatorDefinition(
            key = IndicatorKey.computed("ma"),
            calculator = MovingAverage(listOf(2)),
        )
        val runtime = IndicatorRuntime()
        val series = seriesOfCloses(3.0, 2.0, 1.0)
        val first = runtime.calculate(series = series, definitions = listOf(definition))
        val second = runtime.calculate(previous = first, series = series, definitions = listOf(definition))

        assertSame(first.output(definition.key), second.output(definition.key))
    }

    @Test
    fun `snapshot remains renderable while only open newest candle changes`() {
        val definition = IndicatorDefinition(
            key = IndicatorKey.computed("ma"),
            calculator = MovingAverage(listOf(2)),
        )
        val original = seriesOfCloses(3.0, 2.0, 1.0)
        val snapshot = IndicatorRuntime()
            .calculate(series = original, definitions = listOf(definition))
            .withSourceRevision(1L)
        val latest = checkNotNull(original.latest)
        val tickSeries = original.updateLatest(latest.copy(high = 5.0, close = 5.0)).series
        val nextSeries = tickSeries.updateLatest(
            latest.copy(timestampMillis = latest.timestampMillis + 1L, high = 6.0, close = 6.0),
        ).series

        assertTrue(snapshot.matches(KlineUiState(series = tickSeries, revision = 2L)))
        assertTrue(!snapshot.matches(KlineUiState(series = nextSeries, revision = 3L)))
    }

    @Test
    fun `typed native configuration change invalidates cached calculation`() {
        var calculations = 0
        val calculator = IndicatorCalculator { input ->
            calculations += 1
            IndicatorOutput.of(
                key = input.definition.key,
                seriesSize = input.series.size,
                columns = listOf(IndicatorColumn.of("value", input.series.candles.map(KlineCandle::close))),
            )
        }
        val initial = IndicatorDefinition(
            key = IndicatorKey.computed("configurable"),
            configuration = TestConfiguration(window = 7),
            calculator = calculator,
        )
        val changed = initial.copy(configuration = TestConfiguration(window = 25))
        val runtime = IndicatorRuntime()
        val series = seriesOfCloses(3.0, 2.0, 1.0)

        val first = runtime.calculate(series = series, definitions = listOf(initial))
        val second = runtime.calculate(previous = first, series = series, definitions = listOf(changed))

        assertEquals(2, calculations)
        assertTrue(first.output(initial.key) !== second.output(changed.key))
    }

    @Test
    fun `changed candle input creates a new result without changing the old output`() {
        val definition = IndicatorDefinition(
            key = IndicatorKey.computed("ma"),
            calculator = MovingAverage(listOf(2)),
        )
        val runtime = IndicatorRuntime()
        val oldSeries = seriesOfCloses(3.0, 2.0, 1.0)
        val first = runtime.calculate(series = oldSeries, definitions = listOf(definition))
        val firstValues = first.output(definition.key)!!.column("ma_2")!!.asList()

        val newSeries = seriesOfCloses(10.0, 3.0, 2.0, 1.0)
        val second = runtime.calculate(previous = first, series = newSeries, definitions = listOf(definition))

        assertEquals(listOf(2.5, 1.5, Double.NaN), firstValues)
        assertEquals(listOf(6.5, 2.5, 1.5, Double.NaN), second.output(definition.key)!!.column("ma_2")!!.asList())
        assertEquals(firstValues, first.output(definition.key)!!.column("ma_2")!!.asList())
    }

    @Test
    fun `output copies input arrays and never exposes mutable backing arrays`() {
        val source = doubleArrayOf(3.0, 2.0, 1.0)
        val column = IndicatorColumn.of("test", source)
        source[0] = 99.0
        val output = IndicatorOutput.of(IndicatorKey.computed("test"), 3, listOf(column))

        val inspectedValues = column.asList().toMutableList()
        inspectedValues[1] = 88.0
        val inspectedColumns = output.columns().toMutableList()
        inspectedColumns.clear()

        assertEquals(listOf(3.0, 2.0, 1.0), column.asList())
        assertEquals(listOf("test"), output.columnNames.toList())
        assertEquals(2.0, output.column("test")!![1])
    }

    @Test
    fun `indicator key distinguishes kind but ignores display label`() {
        val direct = IndicatorKey.direct("shared", "Direct")
        val computed = IndicatorKey.computed("shared", "Computed")
        val renamedComputed = IndicatorKey.computed("shared", "Renamed")

        assertNotEquals(direct, computed)
        assertEquals(computed, renamedComputed)
        assertEquals(2, setOf(direct, computed).size)
    }

    @Test
    fun `computed definition requires a calculator while direct output may be empty`() {
        assertFailsWith<IllegalArgumentException> {
            IndicatorDefinition(key = IndicatorKey.computed("missing"))
        }

        val direct = IndicatorDefinition(key = IndicatorKey.direct("candle"))
        val result = IndicatorRuntime().calculate(
            series = seriesOfCloses(2.0, 1.0),
            definitions = listOf(direct),
        )

        assertTrue(result.output(direct.key)!!.isEmpty)
    }

    private fun seriesOfCloses(vararg closes: Double): KlineSeries =
        KlineSeries.of(closes.mapIndexed { index, close ->
            candle(timestamp = (closes.size - index).toLong(), close = close, volume = close * 10.0)
        })

    private fun candle(timestamp: Long, close: Double, volume: Double): KlineCandle =
        KlineCandle(
            timestampMillis = timestamp,
            open = close,
            high = close,
            low = close,
            close = close,
            volume = volume,
        )

    private data class TestConfiguration(val window: Int) : IndicatorConfiguration
}
