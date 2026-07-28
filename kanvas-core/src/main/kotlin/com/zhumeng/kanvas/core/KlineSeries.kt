/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas.core

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Immutable O(1) view used for the overwhelmingly common real-time operation:
 * replacing the still-open newest candle. Repeated replacements are flattened
 * onto the original tail so lookup never builds a delegation chain.
 */
private class LatestReplacedCandleList(
    source: List<KlineCandle>,
    private val latest: KlineCandle,
) : AbstractList<KlineCandle>(), RandomAccess {
    val tailSource: List<KlineCandle> =
        (source as? LatestReplacedCandleList)?.tailSource ?: source

    override val size: Int get() = tailSource.size

    override fun get(index: Int): KlineCandle {
        if (index !in indices) throw IndexOutOfBoundsException("index=$index, size=$size")
        return if (index == 0) latest else tailSource[index]
    }
}

/** Result of one explicit data operation on a [KlineSeries]. */
data class KlineSeriesUpdateResult(
    val series: KlineSeries,
    val changedRange: IndexRange,
) {
    val changed: Boolean get() = !changedRange.isEmpty
}

/**
 * Immutable newest-first candle collection.
 *
 * Core does not sort, de-duplicate, or reconcile overlapping market-data
 * pages. Exchange adapters must normalize those concerns before
 * passing candles to Core.
 */
class KlineSeries private constructor(
    val candles: List<KlineCandle>,
) {
    val size: Int get() = candles.size

    val isEmpty: Boolean get() = candles.isEmpty()

    val latest: KlineCandle? get() = candles.firstOrNull()

    val oldest: KlineCandle? get() = candles.lastOrNull()

    operator fun get(index: Int): KlineCandle = candles[index]

    /** Appends one already-normalized historical page without sorting or overlap reconciliation. */
    fun appendOlder(incoming: List<KlineCandle>): KlineSeriesUpdateResult {
        if (incoming.isEmpty()) return KlineSeriesUpdateResult(this, IndexRange.Empty)
        requireStrictNewestFirst(incoming, "Older candle page")
        val currentOldest = oldest
        require(currentOldest == null || incoming.first().timestampMillis < currentOldest.timestampMillis) {
            "Older candle page must be strictly older than the current oldest candle"
        }
        val start = size
        val appended = KlineSeries(candles + incoming)
        return KlineSeriesUpdateResult(appended, IndexRange(start, appended.size))
    }

    /**
     * Applies one real-time candle at the newest edge.
     *
     * The candle may replace the current latest timestamp or prepend the next
     * timestamp. Historical or middle corrections belong to the data source.
     */
    fun updateLatest(candle: KlineCandle): KlineSeriesUpdateResult {
        val currentLatest = latest
        if (currentLatest == null) {
            return KlineSeriesUpdateResult(KlineSeries(listOf(candle)), IndexRange(0, 1))
        }
        require(candle.timestampMillis >= currentLatest.timestampMillis) {
            "Latest candle update must not be older than the current latest candle"
        }
        if (candle.timestampMillis == currentLatest.timestampMillis) {
            if (candle == currentLatest) return KlineSeriesUpdateResult(this, IndexRange.Empty)
            val updated = LatestReplacedCandleList(candles, candle)
            return KlineSeriesUpdateResult(KlineSeries(updated), IndexRange(0, 1))
        }
        val updated = KlineSeries(listOf(candle) + candles)
        return KlineSeriesUpdateResult(updated, IndexRange(0, updated.size))
    }

    /** Returns the candle at `timestamp`, or the next older real candle. */
    fun indexAtOrBefore(timestampMillis: Long): Int? {
        if (candles.isEmpty() || timestampMillis > candles.first().timestampMillis || timestampMillis < candles.last().timestampMillis) {
            return null
        }

        var low = 0
        var high = candles.lastIndex
        while (low < high) {
            val middle = low + ((high - low) ushr 1)
            if (candles[middle].timestampMillis <= timestampMillis) {
                high = middle
            } else {
                low = middle + 1
            }
        }
        return low
    }

    /**
     * Returns a fractional newest-first index for an in-range timestamp. It
     * is used by drawing persistence so a timestamp can be projected back to
     * an X coordinate without retaining a stale pixel position.
     */
    fun timestampToFractionalIndex(timestampMillis: Long): Double? {
        val olderIndex = indexAtOrBefore(timestampMillis) ?: return null
        val older = candles[olderIndex]
        if (older.timestampMillis == timestampMillis || olderIndex == 0) return olderIndex.toDouble()

        val newerIndex = olderIndex - 1
        val newer = candles[newerIndex]
        val span = newer.timestampMillis - older.timestampMillis
        if (span <= 0L) return olderIndex.toDouble()
        val fraction = (newer.timestampMillis - timestampMillis).toDouble() / span.toDouble()
        return newerIndex + fraction
    }

    /** Inverse of [timestampToFractionalIndex] within a candle-sized tolerance. */
    fun fractionalIndexToTimestamp(index: Double): Long? {
        if (candles.isEmpty() || index < 0.0 || index > candles.lastIndex.toDouble()) return null
        val newerIndex = floor(index).toInt()
        val olderIndex = ceil(index).toInt()
        if (newerIndex == olderIndex) return candles[newerIndex].timestampMillis

        val fraction = index - newerIndex
        val newerTimestamp = candles[newerIndex].timestampMillis
        val olderTimestamp = candles[olderIndex].timestampMillis
        return (newerTimestamp + (olderTimestamp - newerTimestamp) * fraction).toLong()
    }

    companion object {
        val Empty: KlineSeries = KlineSeries(emptyList())

        /** Creates a series from host-normalized, strictly newest-first candles. */
        fun of(candles: List<KlineCandle>): KlineSeries {
            if (candles.isEmpty()) return Empty
            requireStrictNewestFirst(candles, "KlineSeries")
            return KlineSeries(candles.toList())
        }

        private fun requireStrictNewestFirst(
            candles: List<KlineCandle>,
            label: String,
        ) {
            for (index in 1 until candles.size) {
                require(candles[index - 1].timestampMillis > candles[index].timestampMillis) {
                    "$label candles must be strictly newest-first without duplicate timestamps"
                }
            }
        }
    }

    /** True when both snapshots share all historical candles and only index zero may differ. */
    internal fun differsOnlyAtLatestFrom(previous: KlineSeries?): Boolean {
        if (previous == null || size != previous.size || size == 0) return false
        if (candles === previous.candles) return true
        val currentTail = (candles as? LatestReplacedCandleList)?.tailSource ?: candles
        val previousTail = (previous.candles as? LatestReplacedCandleList)?.tailSource ?: previous.candles
        return currentTail === previousTail
    }
}
