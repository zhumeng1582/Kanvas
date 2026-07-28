/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas.core

/** A half-open index interval: [startInclusive, endExclusive). */
data class IndexRange(
    val startInclusive: Int,
    val endExclusive: Int,
) {
    init {
        require(startInclusive >= 0) { "startInclusive must be >= 0" }
        require(endExclusive >= startInclusive) { "endExclusive must be >= startInclusive" }
    }

    val isEmpty: Boolean get() = startInclusive == endExclusive

    val length: Int get() = endExclusive - startInclusive

    operator fun contains(index: Int): Boolean = index in startInclusive until endExclusive

    fun clampTo(size: Int): IndexRange {
        require(size >= 0) { "size must be >= 0" }
        return IndexRange(
            startInclusive = startInclusive.coerceIn(0, size),
            endExclusive = endExclusive.coerceIn(0, size).coerceAtLeast(startInclusive.coerceIn(0, size)),
        )
    }

    companion object {
        val Empty = IndexRange(0, 0)
    }
}
