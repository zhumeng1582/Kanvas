/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas.core

import kotlin.test.Test
import kotlin.test.assertEquals

class IndicatorIncrementalCalculationTest {
    @Test
    fun `latest replacement incremental results equal full recalculation`() {
        val calculators = listOf(
            MovingAverage(listOf(7, 25, 99)),
            ExponentialMovingAverages(listOf(7, 25, 99)),
            BollingerBands(),
            Macd(),
            Kdj(),
            Rsi(),
            Volume,
            ParabolicSar(),
            AverageValueLine,
            SuperTrend(),
            OnBalanceVolume,
            WilliamsR(),
            StochasticRsi(),
        )
        val original = series(200)
        val latest = checkNotNull(original.latest)
        val updated = original.updateLatest(
            latest.copy(
                high = latest.high + 3.0,
                low = latest.low - 2.0,
                close = latest.close + 1.25,
                volume = latest.volume + 500.0,
            ),
        ).series

        calculators.forEachIndexed { index, calculator ->
            val definition = IndicatorDefinition(
                key = IndicatorKey.computed("incremental_$index"),
                calculator = calculator,
            )
            val runtime = IndicatorRuntime()
            val previous = runtime.calculate(series = original, definitions = listOf(definition))
            val incremental = runtime.calculate(
                previous = previous,
                series = updated,
                definitions = listOf(definition),
            )
            val fresh = runtime.calculate(series = updated, definitions = listOf(definition))

            assertEquals(fresh.output(definition.key), incremental.output(definition.key), calculator.toString())
        }
    }

    @Test
    fun `accurate moving average latest replacement equals full recalculation`() {
        val definition = IndicatorDefinition(
            key = IndicatorKey.computed("accurate_incremental"),
            calculator = MovingAverage(listOf(7, 25)),
        )
        val original = series(100)
        val updated = original.updateLatest(
            checkNotNull(original.latest).copy(close = 10_001.123456789),
        ).series
        val runtime = IndicatorRuntime()
        val previous = runtime.calculate(
            series = original,
            definitions = listOf(definition),
            computeMode = KlineComputeMode.Accurate,
        )
        val incremental = runtime.calculate(previous, updated, listOf(definition), KlineComputeMode.Accurate)
        val fresh = runtime.calculate(series = updated, definitions = listOf(definition), computeMode = KlineComputeMode.Accurate)

        assertEquals(fresh.output(definition.key), incremental.output(definition.key))
    }

    private fun series(size: Int): KlineSeries = KlineSeries.of(
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
