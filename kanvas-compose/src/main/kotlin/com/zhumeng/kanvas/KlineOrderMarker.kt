/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import com.zhumeng.kanvas.core.IndexRange
import com.zhumeng.kanvas.core.KlineCandle
import com.zhumeng.kanvas.core.KlineViewport

/** The direction represented by an order marker on the main K-line pane. */
enum class KlineOrderSide {
    Buy,
    Sell,
}

/**
 * A compact order annotation anchored to one candle.
 *
 * [timestampMillis] must be the timestamp of the candle to annotate. Keeping
 * the anchor explicit makes markers stable while the viewport moves, zooms,
 * or prepends historical pages.
 */
data class KlineOrderMarker(
    val timestampMillis: Long,
    val side: KlineOrderSide,
)

/** Visual configuration for the built-in B/S order marker overlay. */
data class KlineOrderMarkerRenderConfig(
    val enabled: Boolean = true,
    val buyColor: Color = Color(0xFF16A085),
    val sellColor: Color = Color(0xFFE05A5A),
    val textColor: Color = Color.White,
    /** Side length of the square label in logical pixels. */
    val sizePx: Float = 18f,
    /** Height of the triangle pointer in logical pixels. */
    val pointerHeightPx: Float = 7f,
    /** Distance from the candle high/low in logical pixels. */
    val candleGapPx: Float = 4f,
    /** Distance between multiple same-side orders on one candle. */
    val stackGapPx: Float = 3f,
    val textSizeSp: Float = 10f,
)

internal data class KlineOrderMarkerPlacement(
    val marker: KlineOrderMarker,
    val center: Offset,
)

internal class KlineOrderMarkerIndex(markers: List<KlineOrderMarker>) {
    internal data class Entry(val ordinal: Int, val marker: KlineOrderMarker)

    private val byTimestamp: Map<Long, List<Entry>> = markers
        .mapIndexed { index, marker -> Entry(index, marker) }
        .groupBy { it.marker.timestampMillis }

    fun entriesAt(timestampMillis: Long): List<Entry> = byTimestamp[timestampMillis].orEmpty()
}

internal fun resolveKlineOrderMarkerPlacements(
    markers: List<KlineOrderMarker>,
    candles: List<KlineCandle>,
    paintRange: IndexRange,
    plotRect: Rect,
    valueRange: KlineValueRange,
    viewport: KlineViewport,
    config: KlineOrderMarkerRenderConfig,
    densityScale: Float,
): List<KlineOrderMarkerPlacement> {
    return resolveKlineOrderMarkerPlacements(
        markerIndex = KlineOrderMarkerIndex(markers),
        candles = candles,
        paintRange = paintRange,
        plotRect = plotRect,
        valueRange = valueRange,
        viewport = viewport,
        config = config,
        densityScale = densityScale,
    )
}

internal fun resolveKlineOrderMarkerPlacements(
    markerIndex: KlineOrderMarkerIndex,
    candles: List<KlineCandle>,
    paintRange: IndexRange,
    plotRect: Rect,
    valueRange: KlineValueRange,
    viewport: KlineViewport,
    config: KlineOrderMarkerRenderConfig,
    densityScale: Float,
): List<KlineOrderMarkerPlacement> {
    if (!config.enabled || candles.isEmpty() ||
        plotRect.width <= 0f || plotRect.height <= 0f || valueRange.span <= 0.0
    ) {
        return emptyList()
    }
    val start = paintRange.startInclusive.coerceIn(0, candles.size)
    val end = paintRange.endExclusive.coerceIn(start, candles.size)
    val halfSize = config.sizePx.coerceAtLeast(1f) * densityScale / 2f
    val pointerHeight = config.pointerHeightPx.coerceAtLeast(0f) * densityScale
    val candleGap = config.candleGapPx.coerceAtLeast(0f) * densityScale
    val stackStep = halfSize * 2f + pointerHeight + config.stackGapPx.coerceAtLeast(0f) * densityScale
    val stackCounts = mutableMapOf<Pair<Int, KlineOrderSide>, Int>()

    val indexedPlacements = buildList {
        for (index in start until end) {
            markerIndex.entriesAt(candles[index].timestampMillis).forEach { entry ->
                val marker = entry.marker
                val candle = candles[index]
                if (!candle.isRenderable) return@forEach
                val centerX = viewport.xForIndex(plotRect.right, index.toDouble()) - viewport.candleHalfStepPx
                if (centerX !in plotRect.left..plotRect.right) return@forEach
                val stackKey = index to marker.side
                val stackIndex = stackCounts.getOrDefault(stackKey, 0)
                stackCounts[stackKey] = stackIndex + 1
                val anchorY = when (marker.side) {
                    KlineOrderSide.Buy -> valueRange.yForOrderMarker(candle.low, plotRect) +
                        candleGap + pointerHeight + halfSize + stackIndex * stackStep
                    KlineOrderSide.Sell -> valueRange.yForOrderMarker(candle.high, plotRect) -
                        candleGap - pointerHeight - halfSize - stackIndex * stackStep
                }
                add(
                    entry.ordinal to KlineOrderMarkerPlacement(
                        marker = marker,
                        center = Offset(
                            x = centerX,
                            y = anchorY.coerceIn(plotRect.top + halfSize, plotRect.bottom - halfSize),
                        ),
                    ),
                )
            }
        }
    }
    return indexedPlacements.sortedBy { it.first }.map { it.second }
}

private fun KlineValueRange.yForOrderMarker(value: Double, plotRect: Rect): Float =
    plotRect.bottom - ((value - minimum) / span).toFloat() * plotRect.height
