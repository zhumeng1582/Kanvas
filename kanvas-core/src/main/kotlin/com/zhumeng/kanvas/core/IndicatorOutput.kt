/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas.core

/**
 * One named numeric column of indicator output, indexed in the same
 * newest-first order as its [KlineSeries].
 *
 * The factory defensively copies its input and no raw array is ever returned.
 */
class IndicatorColumn private constructor(
    val name: String,
    private val values: DoubleArray,
    private val latestOverride: Double? = null,
) {
    init {
        require(name.isNotBlank()) { "Indicator output column name must not be blank" }
    }

    val size: Int get() = values.size

    operator fun get(index: Int): Double =
        if (index == 0 && latestOverride != null) latestOverride else values[index]

    /** Returns a fresh read-only view of the values for inspection or rendering. */
    fun asList(): List<Double> = List(size, ::get)

    override fun equals(other: Any?): Boolean =
        other is IndicatorColumn &&
            name == other.name &&
            size == other.size &&
            (0 until size).all { index -> this[index].toBits() == other[index].toBits() }

    override fun hashCode(): Int {
        var result = name.hashCode()
        for (index in 0 until size) result = 31 * result + this[index].hashCode()
        return result
    }

    override fun toString(): String = "IndicatorColumn(name=$name, size=$size)"

    companion object {
        fun of(name: String, values: DoubleArray): IndicatorColumn =
            IndicatorColumn(name, values.copyOf())

        fun of(name: String, values: Iterable<Double>): IndicatorColumn =
            IndicatorColumn(name, values.toList().toDoubleArray())

        /** Takes ownership of a newly calculated array without a redundant defensive copy. */
        internal fun ofOwned(name: String, values: DoubleArray): IndicatorColumn =
            IndicatorColumn(name, values)
    }

    /** Persistent O(1) replacement for an incrementally recalculated newest sample. */
    internal fun withLatestValue(value: Double): IndicatorColumn =
        IndicatorColumn(name = name, values = values, latestOverride = value)

    internal fun differsOnlyAtLatestFrom(previous: IndicatorColumn): Boolean =
        name == previous.name && size == previous.size && values === previous.values
}

/**
 * Immutable, aligned output of one indicator calculation.
 *
 * Every column has exactly [seriesSize] samples. Consumers can inspect values
 * through [column] or [columns], but cannot obtain the backing arrays.
 */
class IndicatorOutput private constructor(
    val key: IndicatorKey,
    val seriesSize: Int,
    private val columnStorage: List<IndicatorColumn>,
    internal val calculationState: Any? = null,
) {
    init {
        require(seriesSize >= 0) { "Indicator output series size must not be negative" }
        require(columnStorage.all { it.size == seriesSize }) {
            "Every indicator output column must align with the candle series"
        }
        require(columnStorage.map(IndicatorColumn::name).distinct().size == columnStorage.size) {
            "Indicator output column names must be unique"
        }
    }

    val isEmpty: Boolean get() = columnStorage.isEmpty()

    /** Number of immutable output columns, available without copying their container. */
    val columnCount: Int get() = columnStorage.size

    val columnNames: Set<String> get() = columnStorage.mapTo(linkedSetOf(), IndicatorColumn::name)

    fun column(name: String): IndicatorColumn? = columnStorage.firstOrNull { it.name == name }

    /** Indexed immutable column access for allocation-sensitive render loops. */
    fun columnAt(index: Int): IndicatorColumn = columnStorage[index]

    /** Returns an independent list; mutating it cannot affect this output. */
    fun columns(): List<IndicatorColumn> = columnStorage.toList()

    /**
     * Returns true when both outputs share all samples except index zero.
     *
     * Built-in incremental calculators preserve their historical column
     * storage during a live-candle replacement, making this check O(column
     * count). A false result is conservative and must invalidate a renderer's
     * historical cache.
     */
    fun differsOnlyAtLatestFrom(previous: IndicatorOutput?): Boolean {
        if (
            previous == null ||
            key != previous.key ||
            seriesSize != previous.seriesSize ||
            columnStorage.size != previous.columnStorage.size
        ) {
            return false
        }
        return columnStorage.indices.all { index ->
            columnStorage[index].differsOnlyAtLatestFrom(previous.columnStorage[index])
        }
    }

    override fun equals(other: Any?): Boolean =
        other is IndicatorOutput &&
            key == other.key &&
            seriesSize == other.seriesSize &&
            columnStorage == other.columnStorage

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + seriesSize
        result = 31 * result + columnStorage.hashCode()
        return result
    }

    override fun toString(): String =
        "IndicatorOutput(key=$key, seriesSize=$seriesSize, columns=${columnStorage.map(IndicatorColumn::name)})"

    companion object {
        fun empty(key: IndicatorKey, seriesSize: Int): IndicatorOutput =
            IndicatorOutput(key, seriesSize, emptyList())

        fun of(
            key: IndicatorKey,
            seriesSize: Int,
            columns: Iterable<IndicatorColumn>,
        ): IndicatorOutput = IndicatorOutput(key, seriesSize, columns.toList())

        /** Internal result factory for calculators that retain immutable incremental state. */
        internal fun ofCalculated(
            key: IndicatorKey,
            seriesSize: Int,
            columns: Iterable<IndicatorColumn>,
            calculationState: Any?,
        ): IndicatorOutput = IndicatorOutput(key, seriesSize, columns.toList(), calculationState)
    }
}
