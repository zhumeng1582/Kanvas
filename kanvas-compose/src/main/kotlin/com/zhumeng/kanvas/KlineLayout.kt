/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.max

/** Determines whether pane heights grow or fit within fixed bounds. */
enum class KlineLayoutMode {
    /** The chart's requested height grows as sub panes are added. */
    Adapt,

    /** The chart keeps the available height and compresses sub panes if needed. */
    Fixed,
}

/** Placement of the built-in Time pane. */
enum class KlineTimePanePosition {
    /** Draw the time pane immediately below the main pane. */
    Middle,

    /** Draw the time pane after all ordinary sub panes. */
    Bottom,
}

/** Logical-pixel padding resolved at the Compose canvas boundary. */
data class KlinePanePadding(
    val leftPx: Float = 0f,
    val topPx: Float = 0f,
    val rightPx: Float = 0f,
    val bottomPx: Float = 0f,
) {
    init {
        require(
            leftPx.isFinite() &&
                topPx.isFinite() &&
                rightPx.isFinite() &&
                bottomPx.isFinite() &&
                leftPx >= 0f &&
                topPx >= 0f &&
                rightPx >= 0f &&
                bottomPx >= 0f,
        ) {
            "Pane padding must be finite and nonnegative."
        }
    }
}

/**
 * The main plot's horizontal coordinates after main pane padding has been
 * removed. The right price axis overlays the plot and does not consume width,
 * matching common exchange-chart layouts.
 */
internal data class KlineMainPlotHorizontalGeometry(
    val leftPx: Float,
    val rightPx: Float,
) {
    val widthPx: Float get() = rightPx - leftPx

    fun localX(canvasX: Float): Float = canvasX - leftPx
}

/**
 * Mirrors the horizontal part of [KlineLayoutEngine]'s main-pane layout. It
 * is shared by Canvas layout and viewport gesture math so pane padding cannot
 * desynchronize what is drawn and what pans.
 */
internal fun resolveMainPlotHorizontalGeometry(
    canvasWidthPx: Float,
    axisWidthPx: Float,
    mainPadding: KlinePanePadding,
    densityScale: Float,
): KlineMainPlotHorizontalGeometry {
    require(canvasWidthPx >= 0f) { "Canvas width must not be negative." }
    require(axisWidthPx >= 0f) { "Axis width must not be negative." }
    require(densityScale.isFinite() && densityScale > 0f) { "Density must be finite and positive." }
    val chartRight = canvasWidthPx
    val left = (mainPadding.leftPx * densityScale).coerceIn(0f, chartRight)
    val right = (chartRight - mainPadding.rightPx * densityScale).coerceIn(left, chartRight)
    return KlineMainPlotHorizontalGeometry(leftPx = left, rightPx = right)
}

/** Requested geometry for a main, sub, or time pane. */
data class KlinePaneSpec(
    val id: String,
    val preferredHeightPx: Float,
    val minHeightPx: Float = 0f,
    val padding: KlinePanePadding = KlinePanePadding(),
) {
    init {
        require(id.isNotBlank()) { "Pane id must not be blank." }
        require(preferredHeightPx >= 0f) { "Pane preferred height must not be negative." }
        require(minHeightPx >= 0f) { "Pane minimum height must not be negative." }
    }

    val normalizedPreferredHeightPx: Float get() = max(preferredHeightPx, minHeightPx)
}

/** Input to [KlineLayoutEngine]. All values are resolved canvas pixels. */
data class KlineLayoutSpec(
    val availableSize: Size,
    val axisWidthPx: Float,
    val mainPane: KlinePaneSpec,
    val subPanes: List<KlinePaneSpec> = emptyList(),
    val timePane: KlinePaneSpec? = null,
    val timePanePosition: KlineTimePanePosition = KlineTimePanePosition.Middle,
    val mode: KlineLayoutMode = KlineLayoutMode.Fixed,
) {
    init {
        require(availableSize.width >= 0f && availableSize.height >= 0f) {
            "Available size must not be negative."
        }
        require(axisWidthPx >= 0f) { "Axis width must not be negative." }
        require(subPanes.map(KlinePaneSpec::id).distinct().size == subPanes.size) {
            "Sub pane ids must be unique."
        }
        require(subPanes.none { it.id == mainPane.id || it.id == timePane?.id }) {
            "Pane ids must be unique across the layout."
        }
    }
}

/** Fully resolved rects for an individual pane. */
data class KlinePaneLayout(
    val id: String,
    val outerRect: Rect,
    val plotRect: Rect,
    val requestedHeightPx: Float,
    val resolvedHeightPx: Float,
)

/**
 * Immutable layout consumed by Canvas renderers and the pointer router.
 *
 * `requiredHeightPx` is important in adapt mode: a parent that wants exact
 * adapt semantics should size the composable to this height.
 * `fitsAvailableSize` is false only when a fixed host supplies a canvas
 * smaller than `mainMin + subMin + time`.
 */
data class KlineLayout(
    val canvasRect: Rect,
    val chartRect: Rect,
    val axisRect: Rect,
    val mainPane: KlinePaneLayout,
    val subPanes: List<KlinePaneLayout>,
    val timePane: KlinePaneLayout?,
    val paneOrder: List<KlinePaneLayout>,
    val dividerYPositions: List<Float>,
    val requiredHeightPx: Float,
    val fitsAvailableSize: Boolean,
)

/**
 * Applies the optional main Tips inset after the Tips prepare pass.
 *
 * Pane outer geometry and all following panes remain stable; only the main
 * drawable chart rect moves down by the claimed Tips height.
 */
internal fun KlineLayout.withMainTipsInset(claimedHeightPx: Double): KlineLayout {
    require(claimedHeightPx.isFinite() && claimedHeightPx >= 0.0) {
        "Main Tips inset must be finite and nonnegative."
    }
    if (claimedHeightPx == 0.0) return this
    val old = mainPane
    val newTop = (old.plotRect.top.toDouble() + claimedHeightPx)
        .coerceAtMost(old.plotRect.bottom.toDouble())
        .toFloat()
    val adjustedMain = old.copy(
        plotRect = Rect(old.plotRect.left, newTop, old.plotRect.right, old.plotRect.bottom),
    )
    return copy(
        mainPane = adjustedMain,
        paneOrder = paneOrder.map { pane -> if (pane.id == old.id) adjustedMain else pane },
    )
}

/**
 * Deterministic pane ordering and fixed-mode sub-pane compression rules.
 * This class deliberately has no Compose state;
 * a renderer may safely cache the returned [KlineLayout] until its input
 * changes.
 */
object KlineLayoutEngine {
    fun resolve(spec: KlineLayoutSpec): KlineLayout {
        // The price-axis strip is an interaction/text overlay. Candles, grid,
        // indicators and time labels retain the full Canvas width underneath.
        val chartWidth = spec.availableSize.width
        val timeHeight = spec.timePane?.normalizedPreferredHeightPx ?: 0f
        val requestedMainHeight = spec.mainPane.normalizedPreferredHeightPx
        // Adapt mode uses each sub indicator's requested height.
        // `subMinHeight` participates only when Fixed mode redistributes space.
        val requestedSubHeights = spec.subPanes.map(KlinePaneSpec::preferredHeightPx)
        val minimumHeight = spec.mainPane.minHeightPx +
            timeHeight + spec.subPanes.sumOf { it.minHeightPx.toDouble() }.toFloat()
        val preferredHeight = requestedMainHeight + timeHeight + requestedSubHeights.sum()

        val resolvedCanvasHeight = when (spec.mode) {
            KlineLayoutMode.Adapt -> preferredHeight
            KlineLayoutMode.Fixed -> spec.availableSize.height
        }
        // A valid fixed host always uses the configured time height. If a
        // caller supplies an impossible size, clamp that pane as well so the
        // diagnostic layout still remains inside the physical canvas.
        val resolvedTimeHeight = when (spec.mode) {
            KlineLayoutMode.Adapt -> timeHeight
            KlineLayoutMode.Fixed -> timeHeight.coerceAtMost(resolvedCanvasHeight)
        }
        val fitsAvailableSize = spec.mode == KlineLayoutMode.Adapt || resolvedCanvasHeight >= minimumHeight
        val (resolvedMainHeight, resolvedSubHeights) = when (spec.mode) {
            KlineLayoutMode.Adapt -> requestedMainHeight to requestedSubHeights
            KlineLayoutMode.Fixed -> resolveFixedHeights(
                availableHeightPx = resolvedCanvasHeight,
                main = spec.mainPane,
                subs = spec.subPanes,
                timeHeightPx = resolvedTimeHeight,
            )
        }

        val canvasRect = Rect(0f, 0f, spec.availableSize.width, resolvedCanvasHeight)
        val chartRect = Rect(0f, 0f, chartWidth, resolvedCanvasHeight)
        val axisLeft = (spec.availableSize.width - spec.axisWidthPx)
            .coerceIn(0f, spec.availableSize.width)
        val axisRect = Rect(axisLeft, 0f, spec.availableSize.width, resolvedCanvasHeight)

        var y = 0f
        fun layoutPane(
            pane: KlinePaneSpec,
            height: Float,
            requestedHeight: Float = pane.preferredHeightPx,
        ): KlinePaneLayout {
            val outer = Rect(0f, y, chartWidth, (y + height).coerceAtLeast(y))
            y = outer.bottom
            return KlinePaneLayout(
                id = pane.id,
                outerRect = outer,
                plotRect = outer.inset(pane.padding),
                requestedHeightPx = requestedHeight,
                resolvedHeightPx = height,
            )
        }

        val main = layoutPane(spec.mainPane, resolvedMainHeight, requestedMainHeight)
        val orderedSubSpecs = when (spec.timePanePosition) {
            KlineTimePanePosition.Middle -> listOfNotNull(spec.timePane) + spec.subPanes
            KlineTimePanePosition.Bottom -> spec.subPanes + listOfNotNull(spec.timePane)
        }
        val heightById = buildMap {
            spec.subPanes.zip(resolvedSubHeights).forEach { (pane, height) -> put(pane.id, height) }
            spec.timePane?.let { put(it.id, resolvedTimeHeight) }
        }
        val laidOutSubs = orderedSubSpecs.map { pane ->
            layoutPane(
                pane,
                heightById.getValue(pane.id),
                if (pane.id == spec.timePane?.id) timeHeight else pane.preferredHeightPx,
            )
        }
        val time = laidOutSubs.firstOrNull { it.id == spec.timePane?.id }
        val normalSubs = laidOutSubs.filter { it.id != spec.timePane?.id }
        val paneOrder = listOf(main) + laidOutSubs
        val dividers = paneOrder.dropLast(1).map { it.outerRect.bottom }

        return KlineLayout(
            canvasRect = canvasRect,
            chartRect = chartRect,
            axisRect = axisRect,
            mainPane = main,
            subPanes = normalSubs,
            timePane = time,
            paneOrder = paneOrder,
            dividerYPositions = dividers,
            requiredHeightPx = preferredHeight,
            fitsAvailableSize = fitsAvailableSize,
        )
    }

    /** Compresses fixed-mode sub panes while keeping input configs immutable. */
    private fun resolveFixedHeights(
        availableHeightPx: Float,
        main: KlinePaneSpec,
        subs: List<KlinePaneSpec>,
        timeHeightPx: Float,
    ): Pair<Float, List<Float>> {
        val availableSubHeight = availableHeightPx - main.minHeightPx - timeHeightPx
        // Keep raw indicator heights here. If even one is below the configured
        // minimum, redistribute all fixed panes together instead of silently
        // clamping only that one pane.
        val requested = subs.map(KlinePaneSpec::preferredHeightPx)
        val requestedTotal = requested.sum()
        val minimums = subs.map(KlinePaneSpec::minHeightPx)
        val allAtLeastMinimum = requested.zip(minimums).all { (height, minimum) -> height >= minimum }
        if (allAtLeastMinimum && requestedTotal <= availableSubHeight) {
            return (availableHeightPx - timeHeightPx - requestedTotal).coerceAtLeast(0f) to requested
        }

        val minimumTotal = minimums.sum()
        if (availableSubHeight <= minimumTotal) {
            // Invalid fixed inputs are still laid out without overlaps. Callers
            // can inspect fitsAvailableSize and reject the host constraint.
            val space = availableSubHeight.coerceAtLeast(0f)
            val compressed = if (minimumTotal <= 0f) {
                List(subs.size) { 0f }
            } else {
                minimums.map { minimum -> minimum * space / minimumTotal }
            }
            return (availableHeightPx - timeHeightPx - compressed.sum()).coerceAtLeast(0f) to compressed
        }

        val distributable = availableSubHeight - minimumTotal
        val flexible = requested.zip(minimums).map { (wanted, minimum) ->
            (wanted - minimum).coerceAtLeast(0f)
        }
        val flexibleTotal = flexible.sum()
        val heights = if (flexibleTotal <= 0f) {
            minimums
        } else {
            minimums.zip(flexible).map { (minimum, extra) ->
                minimum + distributable * extra / flexibleTotal
            }
        }
        return (availableHeightPx - timeHeightPx - heights.sum()).coerceAtLeast(0f) to heights
    }

    private fun Rect.inset(padding: KlinePanePadding): Rect {
        val left = (left + padding.leftPx).coerceAtMost(right)
        val top = (top + padding.topPx).coerceAtMost(bottom)
        val right = (right - padding.rightPx).coerceAtLeast(left)
        val bottom = (bottom - padding.bottomPx).coerceAtLeast(top)
        return Rect(left, top, right, bottom)
    }
}
