/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas.core

import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KlinePerformanceSmokeTest {
    @Test
    fun `one hundred thousand normalized candles validate and map within smoke budget`() {
        lateinit var series: KlineSeries
        val elapsed = measureTimeMillis {
            series = KlineSeries.of(
                List(100_000) { index ->
                    val value = 10_000.0 + index
                    KlineCandle(
                        timestampMillis = 2_000_000_000_000L - index * 60_000L,
                        open = value,
                        high = value + 2.0,
                        low = value - 2.0,
                        close = value + 1.0,
                        volume = value,
                    )
                },
            )
            repeat(10_000) { index ->
                val candleIndex = index * 9
                val timestamp = series[candleIndex].timestampMillis
                assertEquals(candleIndex, series.indexAtOrBefore(timestamp))
                assertEquals(timestamp, series.fractionalIndexToTimestamp(candleIndex.toDouble()))
            }
        }
        assertTrue(elapsed < 5_000, "Large-series smoke budget exceeded: ${elapsed}ms")
    }

    @Test
    fun `multi period fast calculation stays within realtime smoke budget`() {
        val series = performanceSeries(100_000)
        val definition = IndicatorDefinition(
            key = IndicatorKey.computed("performance_ma"),
            calculator = MovingAverage(listOf(7, 25, 99)),
        )
        val elapsed = measureTimeMillis {
            val output = checkNotNull(definition.calculator).calculate(
                IndicatorCalculationInput(
                    definition = definition,
                    previousSeries = null,
                    series = series,
                    previousOutput = null,
                    computeMode = KlineComputeMode.Fast,
                ),
            )
            assertEquals(3, output.columns().size)
            assertEquals(series.size, output.seriesSize)
        }
        assertTrue(elapsed < 5_000, "Fast multi-indicator smoke budget exceeded: ${elapsed}ms")
    }

    @Test
    fun `accurate decimal calculation stays within background smoke budget`() {
        val series = performanceSeries(10_000)
        val definition = IndicatorDefinition(
            key = IndicatorKey.computed("performance_accurate_ma"),
            calculator = MovingAverage(listOf(7, 25, 99)),
        )
        val elapsed = measureTimeMillis {
            val output = checkNotNull(definition.calculator).calculate(
                IndicatorCalculationInput(
                    definition = definition,
                    previousSeries = null,
                    series = series,
                    previousOutput = null,
                    computeMode = KlineComputeMode.Accurate,
                ),
            )
            assertEquals(3, output.columns().size)
            assertEquals(series.size, output.seriesSize)
        }
        assertTrue(elapsed < 10_000, "Accurate calculation smoke budget exceeded: ${elapsed}ms")
    }

    @Test
    fun `advanced indicators calculate one hundred thousand candles within background budget`() {
        val series = performanceSeries(100_000)
        val definitions = listOf(
            ParabolicSar(),
            AverageValueLine,
            SuperTrend(),
            OnBalanceVolume,
            WilliamsR(),
            StochasticRsi(),
        ).mapIndexed { index, calculator ->
            IndicatorDefinition(
                key = IndicatorKey.computed("advanced_performance_$index"),
                calculator = calculator,
            )
        }

        val elapsed = measureTimeMillis {
            val snapshot = IndicatorRuntime().calculate(series = series, definitions = definitions)
            assertEquals(definitions.size, snapshot.outputs().size)
        }

        assertTrue(elapsed < 5_000, "Advanced indicator smoke budget exceeded: ${elapsed}ms")
    }

    @Test
    fun `realtime latest updates with built in indicators stay incremental`() {
        var series = performanceSeries(100_000)
        val definitions = listOf(
            MovingAverage(listOf(7, 25, 99)),
            ExponentialMovingAverages(listOf(7, 25, 99)),
            BollingerBands(),
            Macd(),
            Kdj(),
            Rsi(),
            Volume,
        ).mapIndexed { index, calculator ->
            IndicatorDefinition(
                key = IndicatorKey.computed("realtime_$index"),
                calculator = calculator,
            )
        }
        val runtime = IndicatorRuntime()
        var snapshot = runtime.calculate(series = series, definitions = definitions)

        val elapsed = measureTimeMillis {
            repeat(1_000) { update ->
                val latest = checkNotNull(series.latest)
                series = series.updateLatest(
                    latest.copy(close = latest.close + (update + 1) * 0.0001),
                ).series
                snapshot = runtime.calculate(
                    previous = snapshot,
                    series = series,
                    definitions = definitions,
                )
            }
        }

        assertEquals(series.size, snapshot.series.size)
        assertTrue(elapsed < 5_000, "Realtime incremental budget exceeded: ${elapsed}ms")
    }

    private fun performanceSeries(size: Int): KlineSeries =
        KlineSeries.of(
            List(size) { index ->
                val value = 10_000.0 + index
                KlineCandle(
                    timestampMillis = 2_000_000_000_000L - index * 60_000L,
                    open = value,
                    high = value + 2.0,
                    low = value - 2.0,
                    close = value + 1.0,
                    volume = value,
                )
            },
        )
}
