/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import com.zhumeng.kanvas.core.KlineLoadingState
import com.zhumeng.kanvas.core.KlineSeries

internal class KlineValueRangeSmoother {
    private var key: Any? = null
    private var current: KlineValueRange? = null
    private var transitionKey: Any? = null
    private var transitionStart: KlineValueRange? = null

    fun resolve(
        target: KlineValueRange,
        smoothFactor: Float,
        resetKey: Any?,
        transition: KlineValueRangeTransition? = null,
    ): KlineValueRange {
        if (key != resetKey || current == null) {
            key = resetKey
            current = target
            transitionKey = transition?.key
            transitionStart = target
            return target
        }

        val previous = checkNotNull(current)
        if (transition != null) {
            if (transition.key != transitionKey) {
                transitionKey = transition.key
                transitionStart = previous
            }
            val start = transitionStart ?: previous
            val progress = transition.progress.coerceIn(0f, 1f).toDouble()
            return KlineValueRange(
                minimum = start.minimum + (target.minimum - start.minimum) * progress,
                maximum = start.maximum + (target.maximum - start.maximum) * progress,
            ).also { current = it }
        }

        val factor = smoothFactor.coerceIn(0.1f, 1f).toDouble()
        if (factor >= 1.0) {
            current = target
            return target
        }
        return KlineValueRange(
            minimum = previous.minimum + (target.minimum - previous.minimum) * factor,
            maximum = previous.maximum + (target.maximum - previous.maximum) * factor,
        ).also { current = it }
    }
}

internal data class KlineValueRangeTransition(
    val key: Any,
    val progress: Float,
)

internal class KlinePaneValueRangeSmoothers {
    private val smoothers = mutableMapOf<String, KlineValueRangeSmoother>()

    fun resolve(
        paneId: String,
        target: KlineValueRange,
        resetKey: Any?,
        transition: KlineValueRangeTransition?,
    ): KlineValueRange = smoothers.getOrPut(paneId, ::KlineValueRangeSmoother).resolve(
        target = target,
        smoothFactor = 1f,
        resetKey = resetKey,
        transition = transition,
    )

    fun retain(paneIds: Collection<String>) {
        smoothers.keys.retainAll(paneIds.toSet())
    }
}

/** True only for a completed request that retained the old newest-first prefix. */
internal fun shouldAnimateHistoricalRangeTransition(
    previousSeries: KlineSeries?,
    series: KlineSeries,
    previousLoadingState: KlineLoadingState,
    loadingState: KlineLoadingState,
): Boolean {
    if (
        previousSeries == null ||
        previousSeries.isEmpty ||
        !previousLoadingState.isLoadMore ||
        loadingState.isLoadMore ||
        series.size <= previousSeries.size
    ) {
        return false
    }
    return series.latest == previousSeries.latest &&
        series[previousSeries.size - 1] == previousSeries.oldest
}
