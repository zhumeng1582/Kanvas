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
    val latest: KlineCandle,
) : AbstractList<KlineCandle>(), RandomAccess {
    val tailSource: List<KlineCandle> =
        (source as? LatestReplacedCandleList)?.tailSource ?: source

    override val size: Int get() = tailSource.size

    override fun get(index: Int): KlineCandle {
        if (index !in indices) throw IndexOutOfBoundsException("index=$index, size=$size")
        return if (index == 0) latest else tailSource[index]
    }
}

/**
 * Immutable segmented storage for structural updates at either edge.
 *
 * A market candle boundary and a historical page used to copy the complete
 * series through `listOf(candle) + candles` / `candles + incoming`. Keeping
 * immutable segments makes both operations proportional to the segment count
 * instead of the candle count while preserving random-access List semantics.
 */
private class SegmentedCandleList private constructor(
    private val segments: List<List<KlineCandle>>,
    private val cumulativeSizes: IntArray,
) : AbstractList<KlineCandle>(), RandomAccess {
    override val size: Int = cumulativeSizes.lastOrNull() ?: 0

    override fun get(index: Int): KlineCandle {
        if (index !in indices) throw IndexOutOfBoundsException("index=$index, size=$size")
        var low = 0
        var high = cumulativeSizes.lastIndex
        while (low < high) {
            val middle = low + ((high - low) ushr 1)
            if (index < cumulativeSizes[middle]) high = middle else low = middle + 1
        }
        val segmentStart = if (low == 0) 0 else cumulativeSizes[low - 1]
        return segments[low][index - segmentStart]
    }

    fun prepend(candle: KlineCandle): SegmentedCandleList {
        val first = segments.firstOrNull()
        val nextSegments = if (first != null && first.size < PrefixSegmentCapacity) {
            listOf(listOf(candle) + first) + segments.drop(1)
        } else {
            listOf(listOf(candle)) + segments
        }
        return create(nextSegments)
    }

    fun append(page: List<KlineCandle>): SegmentedCandleList =
        create(segments + listOf(page))

    private fun replaceLatest(candle: KlineCandle): SegmentedCandleList {
        val first = segments.first()
        val nextSegments = if (first.size <= PrefixSegmentCapacity) {
            val replaced = first.toMutableList().apply { this[0] = candle }
            listOf(replaced) + segments.drop(1)
        } else {
            // Do not copy a large original snapshot merely to replace index 0.
            listOf(listOf(candle), first.subList(1, first.size)) + segments.drop(1)
        }
        return create(nextSegments)
    }

    companion object {
        private const val PrefixSegmentCapacity = 64

        fun from(source: List<KlineCandle>): SegmentedCandleList =
            when (source) {
                is SegmentedCandleList -> source
                is LatestReplacedCandleList -> from(source.tailSource).replaceLatest(source.latest)
                else -> create(listOf(source))
            }

        private fun create(segments: List<List<KlineCandle>>): SegmentedCandleList {
            val nonEmpty = segments.filter(List<KlineCandle>::isNotEmpty)
            var total = 0
            val cumulative = IntArray(nonEmpty.size) { index ->
                total += nonEmpty[index].size
                total
            }
            return SegmentedCandleList(nonEmpty, cumulative)
        }
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
        // Copy only the incoming page. Existing immutable storage is retained.
        val appended = KlineSeries(
            SegmentedCandleList.from(candles).append(incoming.toList()),
        )
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
        val updated = KlineSeries(SegmentedCandleList.from(candles).prepend(candle))
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
