/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas.core

import java.math.MathContext

/**
 * Android-side example computed indicator. It calculates one close-price moving
 * average column per configured period. With newest-first candles, the value
 * at index `i` averages indices `i..i + period - 1` (the current candle and
 * its older history); cells without a complete history are [Double.NaN].
 */
class MovingAverage(periods: Iterable<Int>) : IndicatorCalculator {
    private val periodStorage: List<Int> = periods.toList()

    init {
        require(periodStorage.isNotEmpty()) { "MovingAverage requires at least one period" }
        require(periodStorage.all { it > 0 }) { "MovingAverage periods must be positive" }
        require(periodStorage.distinct().size == periodStorage.size) {
            "MovingAverage periods must be unique"
        }
    }

    /** A defensive copy; external mutations cannot change this calculator. */
    val periods: List<Int> get() = periodStorage.toList()

    override fun calculate(input: IndicatorCalculationInput): IndicatorOutput {
        incrementalLatestOutput(input, periodStorage.map(::columnName)) { name ->
            calculateLatestAverage(input.series, name.removePrefix("ma_").toInt(), input.computeMode)
        }?.let { return it }
        val columns = periodStorage.map { period ->
            IndicatorColumn.ofOwned(columnName(period), calculatePeriod(input.series, period, input.computeMode))
        }
        return IndicatorOutput.of(input.definition.key, input.series.size, columns)
    }

    override fun equals(other: Any?): Boolean =
        other is MovingAverage && periodStorage == other.periodStorage

    override fun hashCode(): Int = periodStorage.hashCode()

    override fun toString(): String = "MovingAverage(periods=$periodStorage)"

    companion object {
        fun columnName(period: Int): String = "ma_$period"

        internal fun calculatePeriod(
            series: KlineSeries,
            period: Int,
            computeMode: KlineComputeMode,
        ): DoubleArray {
            if (computeMode == KlineComputeMode.Accurate) {
                val result = DoubleArray(series.size) { Double.NaN }
                for (index in series.candles.indices) {
                    if (index + period > series.size) continue
                    var sum = java.math.BigDecimal.ZERO
                    for (sample in index until index + period) {
                        sum = sum.add(series[sample].exactClose())
                    }
                    result[index] = sum
                        .divide(java.math.BigDecimal.valueOf(period.toLong()), MathContext.DECIMAL128)
                        .toDouble()
                }
                return result
            }
            val result = DoubleArray(series.size) { Double.NaN }
            var sum = 0.0
            var nonFiniteCount = 0

            // Walk oldest to newest. The rolling window is always the current
            // index plus older values, which is the correct direction for a
            // newest-first series.
            for (index in (series.size - 1) downTo 0) {
                val added = series[index].close
                if (added.isFinite()) {
                    sum += added
                } else {
                    nonFiniteCount++
                }

                val removedIndex = index + period
                if (removedIndex < series.size) {
                    val removed = series[removedIndex].close
                    if (removed.isFinite()) {
                        sum -= removed
                    } else {
                        nonFiniteCount--
                    }
                }

                if (index + period <= series.size && nonFiniteCount == 0) {
                    result[index] = sum / period
                }
            }
            return result
        }
    }
}

/**
 * Android-side example volume output. It preserves the newest-first candle
 * ordering one-for-one and is suitable for a dedicated sub-pane renderer.
 */
object Volume : IndicatorCalculator {
    const val ColumnName: String = "volume"

    override fun calculate(input: IndicatorCalculationInput): IndicatorOutput {
        incrementalLatestOutput(input, listOf(ColumnName)) { input.series.latest?.volume ?: Double.NaN }
            ?.let { return it }
        return IndicatorOutput.of(
            key = input.definition.key,
            seriesSize = input.series.size,
            columns = listOf(
                IndicatorColumn.ofOwned(
                    name = ColumnName,
                    values = DoubleArray(input.series.size) { index -> input.series[index].volume },
                ),
            ),
        )
    }
}

/** Exponential moving average for newest-first candle series. */
class ExponentialMovingAverage(val period: Int) : IndicatorCalculator {
    init { require(period > 0) { "EMA period must be positive" } }
    override fun calculate(input: IndicatorCalculationInput): IndicatorOutput {
        val name = "ema_$period"
        incrementalLatestOutput(input, listOf(name)) { latestEma(input.series, input.previousOutput?.column(name), period) }
            ?.let { return it }
        return IndicatorOutput.of(input.definition.key, input.series.size, listOf(
            IndicatorColumn.ofOwned(name, calculate(input.series, period)),
        ))
    }

    companion object {
        fun calculate(series: KlineSeries, period: Int): DoubleArray {
            val result = DoubleArray(series.size) { Double.NaN }
            if (series.size < period) return result
            var sum = 0.0
            for (i in (series.size - 1) downTo (series.size - period)) sum += series[i].close
            var ema = sum / period
            result[series.size - period] = ema
            val alpha = 2.0 / (period + 1.0)
            for (i in (series.size - period - 1) downTo 0) {
                ema = series[i].close * alpha + ema * (1.0 - alpha)
                result[i] = ema
            }
            return result
        }
    }
}

class ExponentialMovingAverages(periods: Iterable<Int>) : IndicatorCalculator {
    private val periodStorage = periods.toList()
    init { require(periodStorage.isNotEmpty() && periodStorage.all { it > 0 }) }
    override fun calculate(input: IndicatorCalculationInput): IndicatorOutput {
        val names = periodStorage.map { "ema_$it" }
        incrementalLatestOutput(input, names) { name ->
            val period = name.removePrefix("ema_").toInt()
            latestEma(input.series, input.previousOutput?.column(name), period)
        }?.let { return it }
        return IndicatorOutput.of(input.definition.key, input.series.size, periodStorage.map { period ->
            IndicatorColumn.ofOwned("ema_$period", ExponentialMovingAverage.calculate(input.series, period))
        })
    }
}

class BollingerBands(val period: Int = 20, val deviation: Double = 2.0) : IndicatorCalculator {
    init { require(period > 0); require(deviation >= 0.0) }
    override fun calculate(input: IndicatorCalculationInput): IndicatorOutput {
        val names = listOf("boll_upper", "boll_mid", "boll_lower")
        val latest = latestBollinger(input.series, period, deviation)
        incrementalLatestOutput(input, names) { name ->
            when (name) {
                "boll_mid" -> latest.first
                "boll_upper" -> latest.second
                else -> latest.third
            }
        }?.let { return it }
        val middle = DoubleArray(input.series.size) { Double.NaN }
        val upper = DoubleArray(input.series.size) { Double.NaN }
        val lower = DoubleArray(input.series.size) { Double.NaN }
        for (i in 0..input.series.size - period) {
            var sum = 0.0
            for (j in i until i + period) sum += input.series[j].close
            val mean = sum / period
            var variance = 0.0
            for (j in i until i + period) { val d = input.series[j].close - mean; variance += d * d }
            val band = kotlin.math.sqrt(variance / period) * deviation
            middle[i] = mean; upper[i] = mean + band; lower[i] = mean - band
        }
        return IndicatorOutput.of(input.definition.key, input.series.size, listOf(
            IndicatorColumn.ofOwned("boll_upper", upper), IndicatorColumn.ofOwned("boll_mid", middle), IndicatorColumn.ofOwned("boll_lower", lower),
        ))
    }
}

class Macd(val fastPeriod: Int = 12, val slowPeriod: Int = 26, val signalPeriod: Int = 9) : IndicatorCalculator {
    init { require(fastPeriod > 0 && slowPeriod > fastPeriod && signalPeriod > 0) }
    override fun calculate(input: IndicatorCalculationInput): IndicatorOutput {
        val previousState = input.previousOutput?.calculationState as? MacdIncrementalState
        if (input.canUpdateLatest() && previousState != null) {
            val fastValue = latestEma(input.series, previousState.fast, fastPeriod)
            val slowValue = latestEma(input.series, previousState.slow, slowPeriod)
            val lineValue = if (fastValue.isFinite() && slowValue.isFinite()) fastValue - slowValue else Double.NaN
            val previousSignal = input.previousOutput.column("signal")
            val signalValue = latestEmaValue(lineValue, previousSignal?.getOrNull(1), signalPeriod)
            val histogramValue = if (lineValue.isFinite() && signalValue.isFinite()) 2.0 * (lineValue - signalValue) else Double.NaN
            val columns = listOf(
                checkNotNull(input.previousOutput.column("macd")).withLatestValue(lineValue),
                checkNotNull(previousSignal).withLatestValue(signalValue),
                checkNotNull(input.previousOutput.column("histogram")).withLatestValue(histogramValue),
            )
            return IndicatorOutput.ofCalculated(
                input.definition.key,
                input.series.size,
                columns,
                MacdIncrementalState(
                    fast = previousState.fast.withLatestValue(fastValue),
                    slow = previousState.slow.withLatestValue(slowValue),
                ),
            )
        }
        val fast = ExponentialMovingAverage.calculate(input.series, fastPeriod)
        val slow = ExponentialMovingAverage.calculate(input.series, slowPeriod)
        val line = DoubleArray(input.series.size) { i -> if (fast[i].isFinite() && slow[i].isFinite()) fast[i] - slow[i] else Double.NaN }
        val signal = DoubleArray(input.series.size) { Double.NaN }
        var sum = 0.0; var count = 0
        for (i in (input.series.size - 1) downTo 0) {
            if (!line[i].isFinite()) continue
            count++; sum += line[i]
            if (count == signalPeriod) signal[i] = sum / signalPeriod
            else if (count > signalPeriod) {
                val previous = signal[i + 1]
                signal[i] = line[i] * (2.0 / (signalPeriod + 1)) + previous * (1.0 - 2.0 / (signalPeriod + 1))
            }
        }
        val histogram = DoubleArray(input.series.size) { i -> if (line[i].isFinite() && signal[i].isFinite()) 2.0 * (line[i] - signal[i]) else Double.NaN }
        return IndicatorOutput.ofCalculated(
            input.definition.key,
            input.series.size,
            listOf(
                IndicatorColumn.ofOwned("macd", line),
                IndicatorColumn.ofOwned("signal", signal),
                IndicatorColumn.ofOwned("histogram", histogram),
            ),
            MacdIncrementalState(
                fast = IndicatorColumn.ofOwned("_fast", fast),
                slow = IndicatorColumn.ofOwned("_slow", slow),
            ),
        )
    }
}

class Kdj(
    val period: Int = 9,
    val smoothing: Int = 3,
    val dSmoothing: Int = smoothing,
) : IndicatorCalculator {
    init { require(period > 0 && smoothing > 0 && dSmoothing > 0) }
    override fun calculate(input: IndicatorCalculationInput): IndicatorOutput {
        if (input.canUpdateLatest() && input.series.size >= period) {
            val previousK = input.previousOutput?.column("k")
            val previousD = input.previousOutput?.column("d")
            if (previousK != null && previousD != null) {
                val window = 0 until period
                val high = window.maxOf { input.series[it].high }
                val low = window.minOf { input.series[it].low }
                val rsv = if (high == low) 50.0 else (input.series[0].close - low) / (high - low) * 100.0
                val priorK = previousK.getOrNull(1).takeIf(Double::isFinite) ?: 50.0
                val priorD = previousD.getOrNull(1).takeIf(Double::isFinite) ?: 50.0
                val kValue = (priorK * (smoothing - 1) + rsv) / smoothing
                val dValue = (priorD * (dSmoothing - 1) + kValue) / dSmoothing
                return patchedLatestOutput(input, mapOf("k" to kValue, "d" to dValue, "j" to (3 * kValue - 2 * dValue)))
            }
        }
        val k = DoubleArray(input.series.size) { Double.NaN }; val d = DoubleArray(input.series.size) { Double.NaN }; val j = DoubleArray(input.series.size) { Double.NaN }
        var prevK = 50.0; var prevD = 50.0
        for (i in (input.series.size - period) downTo 0) {
            var high = Double.NEGATIVE_INFINITY; var low = Double.POSITIVE_INFINITY
            for (x in i until i + period) { high = maxOf(high, input.series[x].high); low = minOf(low, input.series[x].low) }
            val rsv = if (high == low) 50.0 else (input.series[i].close - low) / (high - low) * 100.0
            prevK = (prevK * (smoothing - 1) + rsv) / smoothing
            prevD = (prevD * (dSmoothing - 1) + prevK) / dSmoothing
            k[i] = prevK; d[i] = prevD; j[i] = 3 * prevK - 2 * prevD
        }
        return IndicatorOutput.of(input.definition.key, input.series.size, listOf(IndicatorColumn.ofOwned("k", k), IndicatorColumn.ofOwned("d", d), IndicatorColumn.ofOwned("j", j)))
    }
}

class Rsi(val period: Int = 14) : IndicatorCalculator {
    init { require(period > 0) }
    override fun calculate(input: IndicatorCalculationInput): IndicatorOutput {
        val name = "rsi_$period"
        val previousState = input.previousOutput?.calculationState as? RsiIncrementalState
        if (input.canUpdateLatest() && previousState != null && input.series.size > period) {
            val delta = input.series[0].close - input.series[1].close
            val priorGain = previousState.averageGain.getOrNull(1)
            val priorLoss = previousState.averageLoss.getOrNull(1)
            if (delta.isFinite() && priorGain.isFinite() && priorLoss.isFinite()) {
                val averageGain = (priorGain * (period - 1) + maxOf(delta, 0.0)) / period
                val averageLoss = (priorLoss * (period - 1) + maxOf(-delta, 0.0)) / period
                return IndicatorOutput.ofCalculated(
                    input.definition.key,
                    input.series.size,
                    listOf(checkNotNull(input.previousOutput.column(name)).withLatestValue(rsiValue(averageGain, averageLoss))),
                    RsiIncrementalState(
                        previousState.averageGain.withLatestValue(averageGain),
                        previousState.averageLoss.withLatestValue(averageLoss),
                    ),
                )
            }
        }
        val calculated = calculateWilderRsi(input.series, period)
        return IndicatorOutput.ofCalculated(
            input.definition.key,
            input.series.size,
            listOf(IndicatorColumn.ofOwned(name, calculated.values)),
            RsiIncrementalState(
                IndicatorColumn.ofOwned("_rsi_gain", calculated.averageGain),
                IndicatorColumn.ofOwned("_rsi_loss", calculated.averageLoss),
            ),
        )
    }
}

/** Multiple Wilder RSI curves sharing one calculation definition. */
class RelativeStrengthIndexes(periods: Iterable<Int>) : IndicatorCalculator {
    private val periodStorage = periods.toList()

    init {
        require(periodStorage.isNotEmpty() && periodStorage.all { it > 0 })
        require(periodStorage.distinct().size == periodStorage.size)
    }

    val periods: List<Int> get() = periodStorage.toList()

    override fun calculate(input: IndicatorCalculationInput): IndicatorOutput = IndicatorOutput.of(
        input.definition.key,
        input.series.size,
        periodStorage.map { period ->
            IndicatorColumn.ofOwned("rsi_$period", calculateWilderRsi(input.series, period).values)
        },
    )
}

private data class RsiIncrementalState(
    val averageGain: IndicatorColumn,
    val averageLoss: IndicatorColumn,
)

internal data class WilderRsiResult(
    val values: DoubleArray,
    val averageGain: DoubleArray,
    val averageLoss: DoubleArray,
)

internal fun calculateWilderRsi(series: KlineSeries, period: Int): WilderRsiResult {
    val values = DoubleArray(series.size) { Double.NaN }
    val averageGain = DoubleArray(series.size) { Double.NaN }
    val averageLoss = DoubleArray(series.size) { Double.NaN }
    if (series.size <= period) return WilderRsiResult(values, averageGain, averageLoss)

    val oldestValid = series.size - period - 1
    var gainSum = 0.0
    var lossSum = 0.0
    for (index in oldestValid until oldestValid + period) {
        val delta = series[index].close - series[index + 1].close
        if (!delta.isFinite()) return WilderRsiResult(values, averageGain, averageLoss)
        if (delta >= 0.0) gainSum += delta else lossSum -= delta
    }
    averageGain[oldestValid] = gainSum / period
    averageLoss[oldestValid] = lossSum / period
    values[oldestValid] = rsiValue(averageGain[oldestValid], averageLoss[oldestValid])

    for (index in oldestValid - 1 downTo 0) {
        val delta = series[index].close - series[index + 1].close
        if (!delta.isFinite()) continue
        averageGain[index] = (averageGain[index + 1] * (period - 1) + maxOf(delta, 0.0)) / period
        averageLoss[index] = (averageLoss[index + 1] * (period - 1) + maxOf(-delta, 0.0)) / period
        values[index] = rsiValue(averageGain[index], averageLoss[index])
    }
    return WilderRsiResult(values, averageGain, averageLoss)
}

private fun rsiValue(averageGain: Double, averageLoss: Double): Double = when {
    averageGain == 0.0 && averageLoss == 0.0 -> 50.0
    averageLoss == 0.0 -> 100.0
    else -> 100.0 - 100.0 / (1.0 + averageGain / averageLoss)
}

private data class MacdIncrementalState(
    val fast: IndicatorColumn,
    val slow: IndicatorColumn,
)

private fun IndicatorCalculationInput.canUpdateLatest(): Boolean =
    previousOutput?.seriesSize == series.size && series.differsOnlyAtLatestFrom(previousSeries)

private fun incrementalLatestOutput(
    input: IndicatorCalculationInput,
    columnNames: List<String>,
    calculate: (String) -> Double,
): IndicatorOutput? {
    if (!input.canUpdateLatest()) return null
    val previous = input.previousOutput ?: return null
    val values = columnNames.associateWith(calculate)
    if (columnNames.any { previous.column(it) == null }) return null
    return patchedLatestOutput(input, values)
}

private fun patchedLatestOutput(
    input: IndicatorCalculationInput,
    values: Map<String, Double>,
): IndicatorOutput {
    val previous = checkNotNull(input.previousOutput)
    val columns = previous.columns().map { column ->
        values[column.name]?.let(column::withLatestValue) ?: column
    }
    return IndicatorOutput.ofCalculated(
        key = input.definition.key,
        seriesSize = input.series.size,
        columns = columns,
        calculationState = previous.calculationState,
    )
}

private fun calculateLatestAverage(
    series: KlineSeries,
    period: Int,
    computeMode: KlineComputeMode,
): Double {
    if (series.size < period) return Double.NaN
    if (computeMode == KlineComputeMode.Accurate) {
        var sum = java.math.BigDecimal.ZERO
        for (index in 0 until period) sum = sum.add(series[index].exactClose())
        return sum.divide(java.math.BigDecimal.valueOf(period.toLong()), MathContext.DECIMAL128).toDouble()
    }
    var sum = 0.0
    for (index in 0 until period) {
        val value = series[index].close
        if (!value.isFinite()) return Double.NaN
        sum += value
    }
    return sum / period
}

private fun latestEma(
    series: KlineSeries,
    previousColumn: IndicatorColumn?,
    period: Int,
): Double {
    if (series.size < period) return Double.NaN
    if (series.size == period || previousColumn?.getOrNull(1)?.isFinite() != true) {
        return calculateLatestAverage(series, period, KlineComputeMode.Fast)
    }
    return latestEmaValue(series[0].close, previousColumn[1], period)
}

private fun latestEmaValue(value: Double, previous: Double?, period: Int): Double {
    if (!value.isFinite() || previous?.isFinite() != true) return Double.NaN
    val alpha = 2.0 / (period + 1.0)
    return value * alpha + previous * (1.0 - alpha)
}

private fun latestBollinger(
    series: KlineSeries,
    period: Int,
    deviation: Double,
): Triple<Double, Double, Double> {
    if (series.size < period) return Triple(Double.NaN, Double.NaN, Double.NaN)
    var sum = 0.0
    for (index in 0 until period) sum += series[index].close
    val mean = sum / period
    var variance = 0.0
    for (index in 0 until period) {
        val delta = series[index].close - mean
        variance += delta * delta
    }
    val band = kotlin.math.sqrt(variance / period) * deviation
    return Triple(mean, mean + band, mean - band)
}

private fun IndicatorColumn.getOrNull(index: Int): Double =
    if (index in 0 until size) this[index] else Double.NaN
