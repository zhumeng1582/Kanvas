/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import kotlin.math.abs

data class KlinePaneResizeEntry(
    val id: String,
    val heightPx: Float,
    val minHeightPx: Float,
) {
    init {
        require(id.isNotBlank())
        require(heightPx.isFinite() && minHeightPx.isFinite() && heightPx >= 0f && minHeightPx >= 0f)
    }
}

data class KlinePaneResizeResult(
    val entries: List<KlinePaneResizeEntry>,
    val appliedDeltaPx: Float,
)

object KlinePaneResizeMath {
    /** Returns the upper pane index, including the last pane only in Adapt mode. */
    fun hitBoundary(
        panes: List<KlinePaneLayout>,
        y: Float,
        hitDistancePx: Float,
        mode: KlineLayoutMode,
    ): Int? {
        require(hitDistancePx.isFinite() && hitDistancePx >= 0f)
        val lastIndex = if (mode == KlineLayoutMode.Adapt) panes.lastIndex else panes.lastIndex - 1
        if (lastIndex < 0) return null
        return (0..lastIndex).minByOrNull { index -> abs(panes[index].outerRect.bottom - y) }
            ?.takeIf { index -> abs(panes[index].outerRect.bottom - y) <= hitDistancePx / 2f }
    }

    /**
     * Fixed mode redistributes height across the boundary. Adapt mode does the
     * same when a lower pane exists and changes total height at the last edge.
     */
    fun resize(
        entries: List<KlinePaneResizeEntry>,
        upperIndex: Int,
        requestedDeltaPx: Float,
        mode: KlineLayoutMode,
    ): KlinePaneResizeResult {
        require(requestedDeltaPx.isFinite())
        require(upperIndex in entries.indices)
        val lowerIndex = upperIndex + 1
        require(mode == KlineLayoutMode.Adapt || lowerIndex in entries.indices) {
            "Fixed resize requires a lower pane."
        }
        val upper = entries[upperIndex]
        var delta = requestedDeltaPx.coerceAtLeast(upper.minHeightPx - upper.heightPx)
        if (lowerIndex in entries.indices) {
            val lower = entries[lowerIndex]
            delta = delta.coerceAtMost(lower.heightPx - lower.minHeightPx)
        }
        if (delta == 0f) return KlinePaneResizeResult(entries, 0f)
        val resized = entries.toMutableList()
        resized[upperIndex] = upper.copy(heightPx = upper.heightPx + delta)
        if (lowerIndex in entries.indices) {
            val lower = entries[lowerIndex]
            resized[lowerIndex] = lower.copy(heightPx = lower.heightPx - delta)
        }
        return KlinePaneResizeResult(resized, delta)
    }
}
