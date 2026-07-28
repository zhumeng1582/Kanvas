/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import com.zhumeng.kanvas.core.IndicatorPaintMode
import com.zhumeng.kanvas.core.KlineCandle

/**
 * Optional renderer capability for indicator-owned labels above a pane.
 *
 * This is deliberately independent from [KlineCrossTooltipProvider]. Top
 * Tips belong to an individual indicator renderer and receive either the
 * latest candle or the active Cross selection. They do not use untyped JSON,
 * TooltipInfo, or a shared row-click model.
 *
 * [prepareTopTips] runs once per visible indicator in a Canvas frame. It must
 * be fast, deterministic, non-blocking, and free of lifecycle side effects.
 * The chart calls it serially for main indicators, so
 * [KlineIndicatorTopTipsPrepareContext.geometry]'s [KlineIndicatorTopTipsGeometry.tipsRect]
 * exposes the progressively shifted available Tips rectangle. Return null to
 * omit this item entirely. Return a non-null [KlineIndicatorTopTipsPrepared]
 * with [KlineIndicatorTopTipsPrepared.claimedHeightPx] set to null when the
 * item should draw but must not advance the next main indicator's Tips area.
 *
 * [drawTopTips] receives the exact prepared value and immutable placement
 * from the same frame. It must only draw; it must not repeat asynchronous work
 * or mutate Compose state. Stateful renderers retain their ordinary lifecycle
 * and can request a later frame through `invalidate()`.
 */
interface KlineIndicatorTopTipsRenderer {
    fun prepareTopTips(
        context: KlineIndicatorTopTipsPrepareContext,
    ): KlineIndicatorTopTipsPrepared?

    fun drawTopTips(
        scope: DrawScope,
        context: KlineIndicatorTopTipsDrawContext,
    )
}

/**
 * Renderer-owned immutable result from [KlineIndicatorTopTipsRenderer.prepareTopTips].
 *
 * A null [claimedHeightPx] allows this renderer to draw without reserving
 * vertical space. A finite,
 * nonnegative height reserves that much space for the next main Tips item;
 * it is never clipped to the drawable pane before being reported.
 */
interface KlineIndicatorTopTipsPrepared {
    val claimedHeightPx: Float?
}

/** Selection provided to an indicator Top Tips renderer. */
sealed interface KlineIndicatorTopTipsSelection {
    /** Ordinary paint pass, whose model is the latest candle. */
    data class Latest(
        val candle: KlineCandle?,
    ) : KlineIndicatorTopTipsSelection

    /**
     * Crosshair pass. [KlineIndicatorCrosshairContext.candle] is intentionally nullable:
     * blank-area Cross behavior must not silently fall back to the latest
     * candle unless the Cross resolver itself selected one.
     */
    data class Cross(
        val crosshair: KlineIndicatorCrosshairContext,
    ) : KlineIndicatorTopTipsSelection
}

/**
 * Indicator bounding geometry for one Top Tips preparation.
 *
 * All coordinates are physical Canvas pixels. [drawableRect], [chartRect],
 * [topRect], and [bottomRect] describe the indicator itself. [tipsRect] is
 * the currently available Tips region: for main indicators it is progressively
 * moved down by previous claimed heights; sub-pane indicators each start from
 * their own drawable rect.
 */
data class KlineIndicatorTopTipsGeometry(
    val drawableRect: Rect,
    val chartRect: Rect,
    val topRect: Rect,
    val bottomRect: Rect,
    val tipsRect: Rect,
)

/** Immutable input supplied before a renderer prepares its Top Tips content. */
data class KlineIndicatorTopTipsPrepareContext(
    val indicator: KlineIndicatorDrawContext,
    val selection: KlineIndicatorTopTipsSelection,
    val chartLayout: KlineLayout,
    val geometry: KlineIndicatorTopTipsGeometry,
    /** Shared Compose measurer for deterministic native text measurement. */
    val textMeasurer: TextMeasurer,
)

/**
 * One immutable Top Tips placement. [claimedRect] is informational and can
 * extend below [KlineIndicatorTopTipsGeometry.drawableRect] when a renderer
 * claims more height than remains in the Tips area. Renderers
 * should use [KlineIndicatorTopTipsGeometry.tipsRect] as their available region and do their own
 * clipping only when desired.
 */
data class KlineIndicatorTopTipsPlacement(
    val geometry: KlineIndicatorTopTipsGeometry,
    val prepared: KlineIndicatorTopTipsPrepared,
    val claimedRect: Rect?,
)

/** Immutable draw input paired with the same result from the prepare phase. */
data class KlineIndicatorTopTipsDrawContext(
    val indicator: KlineIndicatorDrawContext,
    val selection: KlineIndicatorTopTipsSelection,
    val chartLayout: KlineLayout,
    val placement: KlineIndicatorTopTipsPlacement,
    val textMeasurer: TextMeasurer,
)

/**
 * Pure layout of already measured main Top Tips heights.
 *
 * This utility is also the reference used by the renderer planner. A null
 * entry models a prepared Tips item that returns no Size: it keeps its own
 * available rect but does not move the next item. Heights are accumulated
 * without clamping, while each following [availableRects] top is clamped to
 * the drawable bottom.
 */
data class KlineIndicatorTopTipsStackLayout(
    val availableRects: List<Rect>,
    val claimedRects: List<Rect?>,
    /** Raw, unclamped sum of all non-null claimed heights. */
    val totalClaimedHeightPx: Double,
)

fun layoutKlineIndicatorTopTipsStack(
    drawableRect: Rect,
    claimedHeightsPx: Iterable<Float?>,
): KlineIndicatorTopTipsStackLayout {
    require(drawableRect.hasFiniteCoordinates()) { "Tips drawable rect must be finite." }
    require(drawableRect.right >= drawableRect.left && drawableRect.bottom >= drawableRect.top) {
        "Tips drawable rect must not be inverted."
    }
    var accumulated = 0.0
    val available = mutableListOf<Rect>()
    val claimed = mutableListOf<Rect?>()
    claimedHeightsPx.forEach { height ->
        require(height == null || (height.isFinite() && height >= 0f)) {
            "Tips claimed height must be finite and nonnegative."
        }
        val top = (drawableRect.top.toDouble() + accumulated)
            .coerceAtMost(drawableRect.bottom.toDouble())
            .toFloat()
        val tipsRect = Rect(drawableRect.left, top, drawableRect.right, drawableRect.bottom)
        available += tipsRect
        claimed += height?.let { value ->
            Rect(
                left = drawableRect.left,
                top = top,
                right = drawableRect.right,
                bottom = (top.toDouble() + value.toDouble()).toFloat(),
            )
        }
        if (height != null) accumulated += height.toDouble()
    }
    return KlineIndicatorTopTipsStackLayout(
        availableRects = available,
        claimedRects = claimed,
        totalClaimedHeightPx = accumulated,
    )
}

/** Internal renderer/frame pairing retained only for a single Canvas pass. */
internal data class KlineIndicatorTopTipsRenderEntry(
    val frame: KlineIndicatorRenderFrame,
    val placement: KlineIndicatorTopTipsPlacement,
)

/** Internal plan shared by the prepare and draw portions of one Canvas pass. */
internal data class KlineIndicatorTopTipsRenderPlan(
    val entries: List<KlineIndicatorTopTipsRenderEntry>,
    val totalClaimedHeightPx: Double,
)

/**
 * Runs native Top Tips preparation in the exact order supplied by [frames].
 *
 * Main frames set [stackMainTips] to true, which feeds each renderer the
 * remaining rect after preceding claimed heights. Sub frames set it false so
 * every renderer receives its own full drawable rect; a repeated sub pane id
 * does not turn those independent renderers into a main-style Tips stack.
 */
internal fun resolveKlineIndicatorTopTipsRenderPlan(
    frames: Iterable<KlineIndicatorRenderFrame>,
    chartLayout: KlineLayout,
    selection: KlineIndicatorTopTipsSelection,
    textMeasurer: TextMeasurer,
    stackMainTips: Boolean,
): KlineIndicatorTopTipsRenderPlan {
    var accumulated = 0.0
    val entries = mutableListOf<KlineIndicatorTopTipsRenderEntry>()
    frames.forEach { frame ->
        val renderer = frame.item.renderer as? KlineIndicatorTopTipsRenderer ?: return@forEach
        val baseGeometry = resolveKlineIndicatorTopTipsBaseGeometry(frame)
        val tipsTop = if (stackMainTips) {
            (baseGeometry.drawableRect.top.toDouble() + accumulated)
                .coerceAtMost(baseGeometry.drawableRect.bottom.toDouble())
                .toFloat()
        } else {
            baseGeometry.drawableRect.top
        }
        val geometry = baseGeometry.copy(
            tipsRect = Rect(
                baseGeometry.drawableRect.left,
                tipsTop,
                baseGeometry.drawableRect.right,
                baseGeometry.drawableRect.bottom,
            ),
        )
        val prepared = renderer.prepareTopTips(
            KlineIndicatorTopTipsPrepareContext(
                indicator = frame.context,
                selection = selection,
                chartLayout = chartLayout,
                geometry = geometry,
                textMeasurer = textMeasurer,
            ),
        ) ?: return@forEach
        val claimedHeight = prepared.claimedHeightPx
        require(claimedHeight == null || (claimedHeight.isFinite() && claimedHeight >= 0f)) {
            "Top Tips claimed height must be finite and nonnegative."
        }
        val placement = KlineIndicatorTopTipsPlacement(
            geometry = geometry,
            prepared = prepared,
            claimedRect = claimedHeight?.let { height ->
                Rect(
                    left = geometry.tipsRect.left,
                    top = geometry.tipsRect.top,
                    right = geometry.tipsRect.right,
                    bottom = (geometry.tipsRect.top.toDouble() + height.toDouble()).toFloat(),
                )
            },
        )
        entries += KlineIndicatorTopTipsRenderEntry(frame = frame, placement = placement)
        if (stackMainTips && claimedHeight != null) accumulated += claimedHeight.toDouble()
    }
    return KlineIndicatorTopTipsRenderPlan(
        entries = entries,
        totalClaimedHeightPx = accumulated,
    )
}

/** Draws a plan prepared by [resolveKlineIndicatorTopTipsRenderPlan]. */
internal fun DrawScope.drawKlineIndicatorTopTipsRenderPlan(
    plan: KlineIndicatorTopTipsRenderPlan,
    chartLayout: KlineLayout,
    selection: KlineIndicatorTopTipsSelection,
    textMeasurer: TextMeasurer,
) {
    plan.entries.forEach { entry ->
        (entry.frame.item.renderer as? KlineIndicatorTopTipsRenderer)?.drawTopTips(
            scope = this,
            context = KlineIndicatorTopTipsDrawContext(
                indicator = entry.frame.context,
                selection = selection,
                chartLayout = chartLayout,
                placement = entry.placement,
                textMeasurer = textMeasurer,
            ),
        )
    }
}

/** Resolves indicator-local bounding rects before the moving Tips rect. */
private fun resolveKlineIndicatorTopTipsBaseGeometry(
    frame: KlineIndicatorRenderFrame,
): KlineIndicatorTopTipsGeometry {
    val pane = frame.context.pane
    val drawable = pane.outerRect
    val (topRect, bottomRect) = when (frame.item.definition.paintMode) {
        IndicatorPaintMode.ALONE -> {
            // An ALONE item's pane.plotRect is bottom-aligned by its own
            // height, so outer→plot differences cannot recover top/bottom
            // padding. Recreate the indicator-owned bounding rects.
            val padding = frame.item.definition.layoutHint.padding
                ?.toPanePadding()
                ?.toCanvasPixels(frame.context.densityScale)
                ?: KlinePanePadding()
            val left = (drawable.left + padding.leftPx).coerceAtMost(drawable.right)
            val right = (drawable.right - padding.rightPx).coerceAtLeast(left)
            val topBottom = (drawable.top + padding.topPx).coerceAtMost(drawable.bottom)
            val bottomTop = (drawable.bottom - padding.bottomPx).coerceAtLeast(topBottom)
            Rect(left, drawable.top, right, topBottom) to
                Rect(left, bottomTop, right, drawable.bottom)
        }

        IndicatorPaintMode.COMBINE -> {
            // Main combine and physical sub panes expose their actual resolved
            // host geometry. A shared sub pane intentionally shares it.
            Rect(pane.plotRect.left, drawable.top, pane.plotRect.right, pane.plotRect.top) to
                Rect(pane.plotRect.left, pane.plotRect.bottom, pane.plotRect.right, drawable.bottom)
        }
    }
    return KlineIndicatorTopTipsGeometry(
        drawableRect = drawable,
        chartRect = pane.plotRect,
        topRect = topRect,
        bottomRect = bottomRect,
        tipsRect = drawable,
    )
}

private fun Rect.hasFiniteCoordinates(): Boolean =
    left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()
