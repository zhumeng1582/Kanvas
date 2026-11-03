/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import com.zhumeng.kanvas.core.IndexRange
import com.zhumeng.kanvas.core.IndicatorOutput
import com.zhumeng.kanvas.core.KlineSeries

/** Caches source extrema only; renderer callbacks and range padding remain live. */
internal class KlineMainRawRangeCache(private val capacity: Int = 4) {
    private data class Entry(
        val series: KlineSeries,
        val outputs: List<IndicatorOutput>,
        val range: IndexRange,
        val value: KlineRawValueRange?,
    )

    private val entries = ArrayList<Entry>(capacity)

    fun resolve(
        series: KlineSeries,
        outputs: List<IndicatorOutput>,
        range: IndexRange,
    ): KlineRawValueRange? {
        val hit = entries.indexOfLast { entry ->
            entry.range == range &&
                (entry.series === series ||
                    (0 !in range && series.differsOnlyAtLatestFrom(entry.series))) &&
                entry.outputs.size == outputs.size &&
                outputs.indices.all { entry.outputs[it] === outputs[it] }
        }
        if (hit >= 0) {
            val entry = entries.removeAt(hit)
            entries += entry
            return entry.value
        }
        val value = series.candles.rawValueRangeIncluding(
            candleRange = range,
            mainOutputs = outputs,
            paintRange = range,
        )
        if (entries.size == capacity) entries.removeAt(0)
        entries += Entry(series, outputs.toList(), range, value)
        return value
    }
}

/** Retains cache identities after resolution, never bypassing freshness or renderer selection. */
internal class KlineIndicatorPanePlanCache {
    private var previous: KlineIndicatorPanePlan? = null

    fun retain(plan: KlineIndicatorPanePlan): KlineIndicatorPanePlan {
        val old = previous
        // Output equality scans every sample. Only immutable input identities
        // prove reuse here; renderer identity also matters for custom equals().
        fun List<KlineIndicatorRenderItem>.sameInputs(other: List<KlineIndicatorRenderItem>): Boolean =
            size == other.size && indices.all { index ->
                val a = this[index]
                val b = other[index]
                a.definition === b.definition && a.output === b.output && a.renderer === b.renderer &&
                    a.declarationOrder == b.declarationOrder && a.colorIndex == b.colorIndex
            }
        if (old != null &&
            old.mainPaintOrder.sameInputs(plan.mainPaintOrder) &&
            old.subByPane.keys.toList() == plan.subByPane.keys.toList() &&
            old.subByPane.all { (id, items) -> items.sameInputs(plan.subByPane.getValue(id)) } &&
            old.subPaneLayoutHints == plan.subPaneLayoutHints &&
            old.unsupportedDefinitions == plan.unsupportedDefinitions
        ) {
            return old
        }
        previous = plan
        return plan
    }
}

internal data class KlineSubRenderPlan(
    val history: Map<String, List<KlineIndicatorRenderFrame>>,
    val patch: Map<String, List<KlineIndicatorRenderFrame>>,
    val custom: Map<String, List<KlineIndicatorRenderFrame>>,
    val dynamic: Map<String, List<KlineIndicatorRenderFrame>>,
)

/** Reuses partitioned contexts when a draw-only refresh retains the resolved frames. */
internal class KlineSubRenderPlanCache {
    private var source: Map<String, List<KlineIndicatorRenderFrame>>? = null
    private var cached: KlineSubRenderPlan? = null

    fun resolve(framesByPane: Map<String, List<KlineIndicatorRenderFrame>>): KlineSubRenderPlan {
        if (source === framesByPane) return checkNotNull(cached)
        val history = linkedMapOf<String, List<KlineIndicatorRenderFrame>>()
        val patch = linkedMapOf<String, List<KlineIndicatorRenderFrame>>()
        val custom = linkedMapOf<String, List<KlineIndicatorRenderFrame>>()
        val dynamic = linkedMapOf<String, List<KlineIndicatorRenderFrame>>()
        framesByPane.forEach { (paneId, frames) ->
            val historicalFrames = mutableListOf<KlineIndicatorRenderFrame>()
            val patchFrames = mutableListOf<KlineIndicatorRenderFrame>()
            val customFrames = mutableListOf<KlineIndicatorRenderFrame>()
            val dynamicFrames = mutableListOf<KlineIndicatorRenderFrame>()
            frames.forEach { frame ->
                val renderer = frame.item.renderer
                val isLine = renderer.builtInComputedLineRendererOrNull() != null
                if (isLine || renderer.builtInVolumeRendererOrNull() != null) {
                    val range = frame.context.paintRange
                    val start = maxOf(1, range.startInclusive)
                    historicalFrames += frame.copy(
                        context = frame.context.copy(
                            paintRange = if (start < range.endExclusive) {
                                IndexRange(start, range.endExclusive)
                            } else {
                                IndexRange.Empty
                            },
                        ),
                        preparedLines = null,
                        preparedLineTranslationX = 0f,
                    )
                    // Lines share index 1 with history to connect the newest segment.
                    patchFrames += frame.copy(
                        context = frame.context.copy(
                            paintRange = IndexRange(0, minOf(if (isLine) 2 else 1, frame.context.state.series.size)),
                        ),
                        preparedLines = null,
                        preparedLineTranslationX = 0f,
                    )
                } else if (renderer.cachePolicy == KlineIndicatorCachePolicy.ImmutableInputs) {
                    customFrames += frame
                }
                if (renderer.cachePolicy == KlineIndicatorCachePolicy.Never) dynamicFrames += frame
            }
            if (historicalFrames.isNotEmpty()) history[paneId] = historicalFrames
            if (patchFrames.isNotEmpty()) patch[paneId] = patchFrames
            if (customFrames.isNotEmpty()) custom[paneId] = customFrames
            if (dynamicFrames.isNotEmpty()) dynamic[paneId] = dynamicFrames
        }
        return KlineSubRenderPlan(history, patch, custom, dynamic).also {
            source = framesByPane
            cached = it
        }
    }
}
