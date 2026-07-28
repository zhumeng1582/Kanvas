/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Parabolic SAR with Wilder's acceleration factor. */
class ParabolicSar(
    val step: Double = 0.02,
    val maximum: Double = 0.2,
) : IndicatorCalculator {
    init {
        require(step.isFinite() && step > 0.0) { "SAR step must be finite and positive" }
        require(maximum.isFinite() && maximum >= step) { "SAR maximum must be finite and at least step" }
    }

    override fun calculate(input: IndicatorCalculationInput): IndicatorOutput {
        val previousState = input.previousOutput?.calculationState as? SarState
        if (input.canPatchLatest() && previousState != null && input.series.size >= 2) {
            val latest = sarAt(input.series, 0, previousState, step, maximum)
            return IndicatorOutput.ofCalculated(
                input.definition.key,
                input.series.size,
                listOf(checkNotNull(input.previousOutput.column(ColumnName)).withLatestValue(latest.sar)),
                previousState.withLatest(latest),
            )
        }
        val size = input.series.size
        val sar = DoubleArray(size) { Double.NaN }
        val extreme = DoubleArray(size) { Double.NaN }
        val factor = DoubleArray(size) { Double.NaN }
        val upTrend = DoubleArray(size) { Double.NaN }
        if (size > 0) {
            val oldest = size - 1
            val startsUp = oldest == 0 || input.series[oldest - 1].close >= input.series[oldest].close
            sar[oldest] = if (startsUp) input.series[oldest].low else input.series[oldest].high
            extreme[oldest] = if (startsUp) input.series[oldest].high else input.series[oldest].low
            factor[oldest] = step
            upTrend[oldest] = if (startsUp) 1.0 else 0.0
            val mutableState = SarMutableState(sar, extreme, factor, upTrend)
            for (index in oldest - 1 downTo 0) {
                val value = sarAt(input.series, index, mutableState, step, maximum)
                sar[index] = value.sar
                extreme[index] = value.extreme
                factor[index] = value.factor
                upTrend[index] = if (value.upTrend) 1.0 else 0.0
            }
        }
        val state = SarState(
            IndicatorColumn.ofOwned("_sar", sar),
            IndicatorColumn.ofOwned("_sar_extreme", extreme),
            IndicatorColumn.ofOwned("_sar_factor", factor),
            IndicatorColumn.ofOwned("_sar_up", upTrend),
        )
        return IndicatorOutput.ofCalculated(
            input.definition.key,
            size,
            listOf(IndicatorColumn.ofOwned(ColumnName, sar.copyOf())),
            state,
        )
    }

    companion object { const val ColumnName: String = "sar" }
}

/** Cumulative average price: cumulative turnover divided by cumulative volume. */
object AverageValueLine : IndicatorCalculator {
    const val ColumnName: String = "avl"

    override fun calculate(input: IndicatorCalculationInput): IndicatorOutput {
        val previousState = input.previousOutput?.calculationState as? AvlState
        if (input.canPatchLatest() && previousState != null && input.series.size >= 2) {
            val candle = input.series[0]
            val volume = previousState.volume.safe(1) + candle.volume
            val turnover = previousState.turnover.safe(1) + candle.resolvedTurnover()
            val value = if (volume > 0.0) turnover / volume else Double.NaN
            return IndicatorOutput.ofCalculated(
                input.definition.key,
                input.series.size,
                listOf(checkNotNull(input.previousOutput.column(ColumnName)).withLatestValue(value)),
                AvlState(
                    previousState.volume.withLatestValue(volume),
                    previousState.turnover.withLatestValue(turnover),
                ),
            )
        }
        val volume = DoubleArray(input.series.size)
        val turnover = DoubleArray(input.series.size)
        val average = DoubleArray(input.series.size) { Double.NaN }
        var cumulativeVolume = 0.0
        var cumulativeTurnover = 0.0
        for (index in input.series.size - 1 downTo 0) {
            val candle = input.series[index]
            cumulativeVolume += candle.volume
            cumulativeTurnover += candle.resolvedTurnover()
            volume[index] = cumulativeVolume
            turnover[index] = cumulativeTurnover
            if (cumulativeVolume > 0.0) average[index] = cumulativeTurnover / cumulativeVolume
        }
        return IndicatorOutput.ofCalculated(
            input.definition.key,
            input.series.size,
            listOf(IndicatorColumn.ofOwned(ColumnName, average)),
            AvlState(
                IndicatorColumn.ofOwned("_avl_volume", volume),
                IndicatorColumn.ofOwned("_avl_turnover", turnover),
            ),
        )
    }
}

/** SuperTrend based on Wilder-smoothed ATR. */
class SuperTrend(
    val atrPeriod: Int = 10,
    val multiplier: Double = 3.0,
) : IndicatorCalculator {
    init {
        require(atrPeriod > 0) { "SuperTrend ATR period must be positive" }
        require(multiplier.isFinite() && multiplier > 0.0) { "SuperTrend multiplier must be finite and positive" }
    }

    override fun calculate(input: IndicatorCalculationInput): IndicatorOutput {
        val previousState = input.previousOutput?.calculationState as? SuperTrendState
        if (input.canPatchLatest() && previousState != null && input.series.size >= atrPeriod) {
            val latest = superTrendAt(input.series, 0, atrPeriod, multiplier, previousState)
            val upValue = if (latest.upTrend) latest.value else Double.NaN
            val downValue = if (latest.upTrend) Double.NaN else latest.value
            return IndicatorOutput.ofCalculated(
                input.definition.key,
                input.series.size,
                listOf(
                    checkNotNull(input.previousOutput.column(UpColumn)).withLatestValue(upValue),
                    checkNotNull(input.previousOutput.column(DownColumn)).withLatestValue(downValue),
                    checkNotNull(input.previousOutput.column(ColumnName)).withLatestValue(latest.value),
                ),
                previousState.withLatest(latest),
            )
        }
        val size = input.series.size
        val values = DoubleArray(size) { Double.NaN }
        val upValues = DoubleArray(size) { Double.NaN }
        val downValues = DoubleArray(size) { Double.NaN }
        val atrValues = DoubleArray(size) { Double.NaN }
        val upper = DoubleArray(size) { Double.NaN }
        val lower = DoubleArray(size) { Double.NaN }
        val trendUp = DoubleArray(size) { Double.NaN }
        val oldestValid = size - atrPeriod
        if (oldestValid >= 0) {
            for (index in oldestValid downTo 0) {
                val atr = if (index == oldestValid) {
                    averageTrueRange(input.series, index, atrPeriod)
                } else {
                    val currentRange = trueRange(input.series, index)
                    if (currentRange.isFinite() && atrValues[index + 1].isFinite()) {
                        (atrValues[index + 1] * (atrPeriod - 1) + currentRange) / atrPeriod
                    } else {
                        Double.NaN
                    }
                }
                atrValues[index] = atr
                val middle = (input.series[index].high + input.series[index].low) / 2.0
                val basicUpper = middle + multiplier * atr
                val basicLower = middle - multiplier * atr
                if (index == oldestValid) {
                    upper[index] = basicUpper
                    lower[index] = basicLower
                    trendUp[index] = if (input.series[index].close >= middle) 1.0 else 0.0
                    values[index] = if (trendUp[index] == 1.0) lower[index] else upper[index]
                } else {
                    val previousClose = input.series[index + 1].close
                    upper[index] = if (basicUpper < upper[index + 1] || previousClose > upper[index + 1]) basicUpper else upper[index + 1]
                    lower[index] = if (basicLower > lower[index + 1] || previousClose < lower[index + 1]) basicLower else lower[index + 1]
                    val wasUp = trendUp[index + 1] == 1.0
                    val isUp = if (wasUp) input.series[index].close >= lower[index] else input.series[index].close > upper[index]
                    trendUp[index] = if (isUp) 1.0 else 0.0
                    values[index] = if (isUp) lower[index] else upper[index]
                }
                if (trendUp[index] == 1.0) upValues[index] = values[index] else downValues[index] = values[index]
            }
        }
        return IndicatorOutput.ofCalculated(
            input.definition.key,
            size,
            listOf(
                IndicatorColumn.ofOwned(UpColumn, upValues),
                IndicatorColumn.ofOwned(DownColumn, downValues),
                IndicatorColumn.ofOwned(ColumnName, values),
            ),
            SuperTrendState(
                IndicatorColumn.ofOwned("_super_atr", atrValues),
                IndicatorColumn.ofOwned("_super_upper", upper),
                IndicatorColumn.ofOwned("_super_lower", lower),
                IndicatorColumn.ofOwned("_super_up", trendUp),
            ),
        )
    }

    companion object {
        const val UpColumn: String = "super_up"
        const val DownColumn: String = "super_down"
        const val ColumnName: String = "super"
    }
}

/** On-balance volume. */
object OnBalanceVolume : IndicatorCalculator {
    const val ColumnName: String = "obv"

    override fun calculate(input: IndicatorCalculationInput): IndicatorOutput {
        if (input.canPatchLatest() && input.series.size >= 2) {
            val previous = input.previousOutput?.column(ColumnName)
            if (previous != null) {
                val value = previous.safe(1) + signedVolume(input.series[0], input.series[1])
                return IndicatorOutput.of(
                    input.definition.key,
                    input.series.size,
                    listOf(previous.withLatestValue(value)),
                )
            }
        }
        val values = calculateObv(input.series)
        return IndicatorOutput.of(
            input.definition.key,
            input.series.size,
            listOf(IndicatorColumn.ofOwned(ColumnName, values)),
        )
    }
}

/** OBV with optional moving-average overlays used by exchange-style panels. */
class OnBalanceVolumeWithAverages(
    val maPeriod: Int? = null,
    val emaPeriod: Int? = null,
) : IndicatorCalculator {
    init {
        require(maPeriod == null || maPeriod > 0)
        require(emaPeriod == null || emaPeriod > 0)
    }

    override fun calculate(input: IndicatorCalculationInput): IndicatorOutput {
        val obvValues = calculateObv(input.series)
        val columns = mutableListOf(IndicatorColumn.ofOwned(OnBalanceVolume.ColumnName, obvValues))
        maPeriod?.let { columns += IndicatorColumn.ofOwned("obv_ma_$it", simpleAverage(obvValues, it)) }
        emaPeriod?.let { columns += IndicatorColumn.ofOwned("obv_ema_$it", exponentialAverage(obvValues, it)) }
        return IndicatorOutput.of(input.definition.key, input.series.size, columns)
    }
}

/** Williams %R in the conventional [-100, 0] range. */
class WilliamsR(val period: Int = 14) : IndicatorCalculator {
    init { require(period > 0) { "Williams R period must be positive" } }

    override fun calculate(input: IndicatorCalculationInput): IndicatorOutput {
        val name = columnName(period)
        if (input.canPatchLatest()) {
            input.previousOutput?.column(name)?.let { previous ->
                return IndicatorOutput.of(
                    input.definition.key,
                    input.series.size,
                    listOf(previous.withLatestValue(williamsAt(input.series, 0, period))),
                )
            }
        }
        val values = rollingWilliams(input.series, period)
        return IndicatorOutput.of(input.definition.key, input.series.size, listOf(IndicatorColumn.ofOwned(name, values)))
    }

    companion object { fun columnName(period: Int): String = "wr_$period" }
}

/** Stochastic RSI with independently configurable RSI/window/K/D periods. */
class StochasticRsi(
    val rsiPeriod: Int = 14,
    val stochasticPeriod: Int = 14,
    val kPeriod: Int = 3,
    val dPeriod: Int = 3,
) : IndicatorCalculator {
    init {
        require(rsiPeriod > 0 && stochasticPeriod > 0 && kPeriod > 0 && dPeriod > 0) {
            "Stochastic RSI periods must be positive"
        }
    }

    override fun calculate(input: IndicatorCalculationInput): IndicatorOutput {
        val rsiValues = rollingRsi(input.series, rsiPeriod)
        val rsi = IndicatorColumn.ofOwned("_stoch_rsi", rsiValues)
        val stochasticValues = rollingStochastic(rsi, stochasticPeriod)
        val stochastic = IndicatorColumn.ofOwned("_stochastic", stochasticValues)
        val kValues = rollingAverage(stochastic, kPeriod)
        val k = IndicatorColumn.ofOwned(KColumn, kValues)
        val dValues = rollingAverage(k, dPeriod)
        return IndicatorOutput.ofCalculated(
            input.definition.key,
            input.series.size,
            listOf(k, IndicatorColumn.ofOwned(DColumn, dValues)),
            StochRsiState(rsi, stochastic),
        )
    }

    companion object {
        const val KColumn: String = "stoch_k"
        const val DColumn: String = "stoch_d"
    }
}

private data class SarPoint(val sar: Double, val extreme: Double, val factor: Double, val upTrend: Boolean)

private interface SarStateView {
    val sar: IndicatorValueView
    val extreme: IndicatorValueView
    val factor: IndicatorValueView
    val upTrend: IndicatorValueView
}

private fun interface IndicatorValueView { operator fun get(index: Int): Double }

private data class SarState(
    private val sarColumn: IndicatorColumn,
    private val extremeColumn: IndicatorColumn,
    private val factorColumn: IndicatorColumn,
    private val upTrendColumn: IndicatorColumn,
) : SarStateView {
    override val sar = IndicatorValueView(sarColumn::get)
    override val extreme = IndicatorValueView(extremeColumn::get)
    override val factor = IndicatorValueView(factorColumn::get)
    override val upTrend = IndicatorValueView(upTrendColumn::get)

    fun withLatest(value: SarPoint): SarState = SarState(
        sarColumn.withLatestValue(value.sar),
        extremeColumn.withLatestValue(value.extreme),
        factorColumn.withLatestValue(value.factor),
        upTrendColumn.withLatestValue(if (value.upTrend) 1.0 else 0.0),
    )
}

private class SarMutableState(
    sarValues: DoubleArray,
    extremeValues: DoubleArray,
    factorValues: DoubleArray,
    upTrendValues: DoubleArray,
) : SarStateView {
    override val sar = IndicatorValueView(sarValues::get)
    override val extreme = IndicatorValueView(extremeValues::get)
    override val factor = IndicatorValueView(factorValues::get)
    override val upTrend = IndicatorValueView(upTrendValues::get)
}

private fun sarAt(series: KlineSeries, index: Int, state: SarStateView, step: Double, maximum: Double): SarPoint {
    val previous = index + 1
    val wasUp = state.upTrend[previous] == 1.0
    val priorExtreme = state.extreme[previous]
    val priorFactor = state.factor[previous]
    var candidate = state.sar[previous] + priorFactor * (priorExtreme - state.sar[previous])
    val candle = series[index]
    return if (wasUp) {
        candidate = min(candidate, series[previous].low)
        if (index + 2 < series.size) candidate = min(candidate, series[index + 2].low)
        if (candle.low < candidate) {
            SarPoint(priorExtreme, candle.low, step, false)
        } else {
            val nextExtreme = max(priorExtreme, candle.high)
            val nextFactor = if (candle.high > priorExtreme) min(maximum, priorFactor + step) else priorFactor
            SarPoint(candidate, nextExtreme, nextFactor, true)
        }
    } else {
        candidate = max(candidate, series[previous].high)
        if (index + 2 < series.size) candidate = max(candidate, series[index + 2].high)
        if (candle.high > candidate) {
            SarPoint(priorExtreme, candle.high, step, true)
        } else {
            val nextExtreme = min(priorExtreme, candle.low)
            val nextFactor = if (candle.low < priorExtreme) min(maximum, priorFactor + step) else priorFactor
            SarPoint(candidate, nextExtreme, nextFactor, false)
        }
    }
}

private data class AvlState(val volume: IndicatorColumn, val turnover: IndicatorColumn)

private data class SuperTrendPoint(
    val value: Double,
    val atr: Double,
    val upper: Double,
    val lower: Double,
    val upTrend: Boolean,
)

private data class SuperTrendState(
    val atr: IndicatorColumn,
    val upper: IndicatorColumn,
    val lower: IndicatorColumn,
    val upTrend: IndicatorColumn,
) {
    fun withLatest(point: SuperTrendPoint): SuperTrendState = SuperTrendState(
        atr.withLatestValue(point.atr),
        upper.withLatestValue(point.upper),
        lower.withLatestValue(point.lower),
        upTrend.withLatestValue(if (point.upTrend) 1.0 else 0.0),
    )
}

private fun superTrendAt(
    series: KlineSeries,
    index: Int,
    period: Int,
    multiplier: Double,
    state: SuperTrendState,
): SuperTrendPoint {
    val currentRange = trueRange(series, index)
    val previousAtr = state.atr.safe(index + 1)
    val atr = if (currentRange.isFinite() && previousAtr.isFinite()) {
        (previousAtr * (period - 1) + currentRange) / period
    } else {
        Double.NaN
    }
    if (!atr.isFinite() || index + 1 >= series.size) {
        return SuperTrendPoint(Double.NaN, Double.NaN, Double.NaN, Double.NaN, false)
    }
    val middle = (series[index].high + series[index].low) / 2.0
    val basicUpper = middle + multiplier * atr
    val basicLower = middle - multiplier * atr
    val previousUpper = state.upper.safe(index + 1)
    val previousLower = state.lower.safe(index + 1)
    val previousClose = series[index + 1].close
    val upper = if (basicUpper < previousUpper || previousClose > previousUpper) basicUpper else previousUpper
    val lower = if (basicLower > previousLower || previousClose < previousLower) basicLower else previousLower
    val wasUp = state.upTrend.safe(index + 1) == 1.0
    val isUp = if (wasUp) series[index].close >= lower else series[index].close > upper
    return SuperTrendPoint(if (isUp) lower else upper, atr, upper, lower, isUp)
}

private data class StochRsiState(val rsi: IndicatorColumn, val stochastic: IndicatorColumn)

private fun trueRange(series: KlineSeries, index: Int): Double {
    val candle = series[index]
    val olderClose = if (index + 1 < series.size) series[index + 1].close else candle.close
    return max(candle.high - candle.low, max(abs(candle.high - olderClose), abs(candle.low - olderClose)))
}

private fun averageTrueRange(series: KlineSeries, start: Int, period: Int): Double {
    if (start < 0 || start + period > series.size) return Double.NaN
    var sum = 0.0
    for (index in start until start + period) sum += trueRange(series, index)
    return sum / period
}

private fun signedVolume(current: KlineCandle, older: KlineCandle): Double = when {
    current.close > older.close -> current.volume
    current.close < older.close -> -current.volume
    else -> 0.0
}

private fun calculateObv(series: KlineSeries): DoubleArray {
    val values = DoubleArray(series.size)
    for (index in series.size - 2 downTo 0) {
        values[index] = values[index + 1] + signedVolume(series[index], series[index + 1])
    }
    return values
}

private fun simpleAverage(values: DoubleArray, period: Int): DoubleArray {
    val result = DoubleArray(values.size) { Double.NaN }
    var sum = 0.0
    for (index in values.lastIndex downTo 0) {
        sum += values[index]
        val removed = index + period
        if (removed < values.size) sum -= values[removed]
        if (index + period <= values.size) result[index] = sum / period
    }
    return result
}

private fun exponentialAverage(values: DoubleArray, period: Int): DoubleArray {
    val result = DoubleArray(values.size) { Double.NaN }
    if (values.size < period) return result
    val oldestValid = values.size - period
    var ema = 0.0
    for (index in oldestValid until values.size) ema += values[index]
    ema /= period
    result[oldestValid] = ema
    val alpha = 2.0 / (period + 1.0)
    for (index in oldestValid - 1 downTo 0) {
        ema = values[index] * alpha + ema * (1.0 - alpha)
        result[index] = ema
    }
    return result
}

private fun williamsAt(series: KlineSeries, start: Int, period: Int): Double {
    if (start < 0 || start + period > series.size) return Double.NaN
    var high = Double.NEGATIVE_INFINITY
    var low = Double.POSITIVE_INFINITY
    for (index in start until start + period) {
        high = max(high, series[index].high)
        low = min(low, series[index].low)
    }
    val span = high - low
    return if (span == 0.0) 0.0 else (high - series[start].close) / span * -100.0
}

private fun rollingWilliams(series: KlineSeries, period: Int): DoubleArray {
    val values = DoubleArray(series.size) { Double.NaN }
    val maximums = java.util.ArrayDeque<Int>()
    val minimums = java.util.ArrayDeque<Int>()
    var nonFiniteCount = 0
    for (end in series.candles.indices) {
        val candle = series[end]
        if (!candle.high.isFinite() || !candle.low.isFinite()) {
            nonFiniteCount++
        } else {
            while (maximums.isNotEmpty() && series[maximums.peekLast()].high <= candle.high) maximums.removeLast()
            while (minimums.isNotEmpty() && series[minimums.peekLast()].low >= candle.low) minimums.removeLast()
            maximums.addLast(end)
            minimums.addLast(end)
        }
        if (end >= period) {
            val removed = end - period
            val removedCandle = series[removed]
            if (!removedCandle.high.isFinite() || !removedCandle.low.isFinite()) nonFiniteCount--
            if (maximums.peekFirst() == removed) maximums.removeFirst()
            if (minimums.peekFirst() == removed) minimums.removeFirst()
        }
        if (end >= period - 1) {
            val start = end - period + 1
            if (nonFiniteCount == 0 && series[start].close.isFinite()) {
                val high = series[maximums.peekFirst()].high
                val low = series[minimums.peekFirst()].low
                val span = high - low
                values[start] = if (span == 0.0) 0.0 else (high - series[start].close) / span * -100.0
            }
        }
    }
    return values
}

private fun rollingRsi(series: KlineSeries, period: Int): DoubleArray {
    return calculateWilderRsi(series, period).values
}

private fun rollingStochastic(column: IndicatorColumn, period: Int): DoubleArray {
    val values = DoubleArray(column.size) { Double.NaN }
    val maximums = java.util.ArrayDeque<Int>()
    val minimums = java.util.ArrayDeque<Int>()
    var nonFiniteCount = 0
    for (end in 0 until column.size) {
        val value = column[end]
        if (!value.isFinite()) {
            nonFiniteCount++
        } else {
            while (maximums.isNotEmpty() && column[maximums.peekLast()] <= value) maximums.removeLast()
            while (minimums.isNotEmpty() && column[minimums.peekLast()] >= value) minimums.removeLast()
            maximums.addLast(end)
            minimums.addLast(end)
        }
        if (end >= period) {
            val removed = end - period
            if (!column[removed].isFinite()) nonFiniteCount--
            if (maximums.peekFirst() == removed) maximums.removeFirst()
            if (minimums.peekFirst() == removed) minimums.removeFirst()
        }
        if (end >= period - 1) {
            val start = end - period + 1
            if (nonFiniteCount == 0) {
                val minimum = column[minimums.peekFirst()]
                val maximum = column[maximums.peekFirst()]
                val span = maximum - minimum
                values[start] = if (span == 0.0) 0.0 else (column[start] - minimum) / span * 100.0
            }
        }
    }
    return values
}

private fun rollingAverage(column: IndicatorColumn, period: Int): DoubleArray {
    val values = DoubleArray(column.size) { Double.NaN }
    var sum = 0.0
    var nonFiniteCount = 0
    for (end in 0 until column.size) {
        val added = column[end]
        if (added.isFinite()) sum += added else nonFiniteCount++
        if (end >= period) {
            val removed = column[end - period]
            if (removed.isFinite()) sum -= removed else nonFiniteCount--
        }
        if (end >= period - 1 && nonFiniteCount == 0) {
            values[end - period + 1] = (sum / period).coerceIn(0.0, 100.0)
        }
    }
    return values
}

private fun IndicatorCalculationInput.canPatchLatest(): Boolean =
    previousOutput?.seriesSize == series.size && series.differsOnlyAtLatestFrom(previousSeries)

private fun IndicatorColumn.safe(index: Int): Double = if (index in 0 until size) this[index] else Double.NaN

private fun KlineCandle.resolvedTurnover(): Double = turnover ?: close * volume
