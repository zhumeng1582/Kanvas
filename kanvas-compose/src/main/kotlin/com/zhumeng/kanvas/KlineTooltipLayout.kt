/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

/** Which side of the main plot owns a Cross tooltip card. */
enum class KlineCrossTooltipSide {
    Left,
    Right,
}

/** Measured label/value boxes for one [KlineCrossTooltipItem]. */
data class KlineCrossTooltipItemMeasurement(
    val label: Size,
    val value: Size,
) {
    init {
        require(label.width.isFinite() && label.height.isFinite() && label.width >= 0f && label.height >= 0f) {
            "Tooltip label size must be finite and nonnegative."
        }
        require(value.width.isFinite() && value.height.isFinite() && value.width >= 0f && value.height >= 0f) {
            "Tooltip value size must be finite and nonnegative."
        }
    }
}

/** One immutable row placement resolved by [layoutKlineCrossTooltip]. */
data class KlineCrossTooltipRowLayout(
    val index: Int,
    /** The full interactive row width, including the card's horizontal padding. */
    val bounds: Rect,
    val labelTopLeft: Offset,
    val valueTopLeft: Offset,
)

/**
 * Pure Cross tooltip layout. The same value is used by Canvas rendering and
 * pointer hit testing; no mutable draw-time hit targets are required.
 */
data class KlineCrossTooltipLayout(
    val side: KlineCrossTooltipSide,
    val cardBounds: Rect,
    /** Content width without [KlinePanePadding]'s left/right padding. */
    val contentWidthPx: Float,
    /** Width measured before session-stability/minimum-width expansion. */
    val naturalContentWidthPx: Float,
    val rows: List<KlineCrossTooltipRowLayout>,
) {
    /** Resolves overlapping expanded row targets from front to back. */
    fun hitItemIndex(position: Offset, hitTestMarginPx: Float = 0f): Int? {
        require(hitTestMarginPx.isFinite() && hitTestMarginPx >= 0f) {
            "Tooltip hit-test margin must be finite and nonnegative."
        }
        return rows.asReversed().firstOrNull { row ->
            if (hitTestMarginPx == 0f) row.bounds.contains(position)
            else row.bounds.inflate(hitTestMarginPx).contains(position)
        }?.index
    }
}

/**
 * Resolves the two-column Cross tooltip geometry from already measured text.
 *
 * All arguments are physical Canvas pixels. Callers measure content first,
 * then pass a previous Cross-session [minContentWidthPx] to keep a changing
 * selected candle from making the card jump horizontally. [plotRect] is the
 * drawable rectangle that clamps the card, while [anchorRect] supplies the
 * outer-main-pane anchors. The return value is
 * deterministic and safe to recompute from a pointer handler.
 */
fun layoutKlineCrossTooltip(
    plotRect: Rect,
    crossX: Float,
    itemMeasurements: List<KlineCrossTooltipItemMeasurement>,
    padding: KlinePanePadding,
    margin: KlinePanePadding,
    columnSpacingPx: Float,
    minContentWidthPx: Float? = null,
    anchorRect: Rect = plotRect,
): KlineCrossTooltipLayout? {
    require(plotRect.hasFiniteCoordinates()) { "Tooltip drawable rect must be finite." }
    require(anchorRect.hasFiniteCoordinates()) { "Tooltip anchor rect must be finite." }
    require(crossX.isFinite()) { "Tooltip cross X must be finite." }
    require(columnSpacingPx.isFinite() && columnSpacingPx >= 0f) {
        "Tooltip column spacing must be finite and nonnegative."
    }
    require(minContentWidthPx == null || (minContentWidthPx.isFinite() && minContentWidthPx >= 0f)) {
        "Tooltip minimum content width must be finite and nonnegative."
    }
    if (
        itemMeasurements.isEmpty() ||
        plotRect.width <= 0f ||
        plotRect.height <= 0f ||
        anchorRect.width < 0f ||
        anchorRect.height < 0f
    ) {
        return null
    }

    val maxLabelWidth = itemMeasurements.maxOf { it.label.width }
    val maxValueWidth = itemMeasurements.maxOf { it.value.width }
    val naturalContentWidth = maxLabelWidth + columnSpacingPx + maxValueWidth
    // Choose a side from the plot midpoint, then anchor the card against the
    // outer main rectangle. Add the outer-left offset so this public geometry
    // helper remains correct when embedded in an offset parent.
    val sidePivotX = anchorRect.left + plotRect.width / 2f
    val side = if (crossX > sidePivotX) KlineCrossTooltipSide.Left else KlineCrossTooltipSide.Right
    val availableCardWidth = when (side) {
        KlineCrossTooltipSide.Left -> plotRect.right - (anchorRect.left + margin.leftPx)
        KlineCrossTooltipSide.Right -> (anchorRect.right - margin.rightPx) - plotRect.left
    }.coerceAtLeast(0f)
    val maxContentWidth = (availableCardWidth - padding.leftPx - padding.rightPx).coerceAtLeast(0f)
    // Preserve an unwrapped label column. Only constrain the content once the
    // remaining value column can still have positive width.
    val nonShrinkableWidth = maxLabelWidth + columnSpacingPx
    val requestedContentWidth = maxOf(naturalContentWidth, minContentWidthPx ?: 0f)
    val contentWidth = if (maxContentWidth > nonShrinkableWidth) {
        requestedContentWidth.coerceAtMost(maxContentWidth)
    } else {
        requestedContentWidth
    }
    val cardWidth = contentWidth + padding.leftPx + padding.rightPx
    val cardHeight = padding.topPx + padding.bottomPx + itemMeasurements.sumOf { measurement ->
        maxOf(measurement.label.height, measurement.value.height).toDouble()
    }.toFloat()
    val preferredLeft = when (side) {
        KlineCrossTooltipSide.Left -> anchorRect.left + margin.leftPx
        KlineCrossTooltipSide.Right -> anchorRect.right - margin.rightPx - cardWidth
    }
    val left = preferredLeft.coerceIn(
        plotRect.left,
        (plotRect.right - cardWidth).coerceAtLeast(plotRect.left),
    )
    // Clamp the origin rather than the full card height. A tall tooltip may
    // extend below a short drawable main pane rather than jumping upward.
    val top = (plotRect.top + margin.topPx).coerceIn(plotRect.top, plotRect.bottom)
    val cardBounds = Rect(left, top, left + cardWidth, top + cardHeight)
    var rowTop = cardBounds.top + padding.topPx
    val rows = itemMeasurements.mapIndexed { index, measurement ->
        val rowHeight = maxOf(measurement.label.height, measurement.value.height)
        val rowBottom = rowTop + rowHeight
        val layout = KlineCrossTooltipRowLayout(
            index = index,
            bounds = Rect(cardBounds.left, rowTop, cardBounds.right, rowBottom),
            labelTopLeft = Offset(
                x = cardBounds.left + padding.leftPx,
                y = rowTop + (rowHeight - measurement.label.height) / 2f,
            ),
            valueTopLeft = Offset(
                x = cardBounds.right - padding.rightPx - measurement.value.width,
                y = rowTop + (rowHeight - measurement.value.height) / 2f,
            ),
        )
        rowTop = rowBottom
        layout
    }
    return KlineCrossTooltipLayout(
        side = side,
        cardBounds = cardBounds,
        contentWidthPx = contentWidth,
        naturalContentWidthPx = naturalContentWidth,
        rows = rows,
    )
}

private fun Rect.hasFiniteCoordinates(): Boolean =
    left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()
