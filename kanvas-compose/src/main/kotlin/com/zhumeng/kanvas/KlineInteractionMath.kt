/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import com.zhumeng.kanvas.core.KlineLoadingState
import com.zhumeng.kanvas.core.KlineViewport
import com.zhumeng.kanvas.core.KlineViewportConstraints
import com.zhumeng.kanvas.core.KlineViewportMath
import kotlin.math.roundToInt

/** Horizontal anchors used while scaling candle width. */
enum class KlineScaleAnchor {
    Auto,
    Left,
    Middle,
    Right,

    /** Keep the candle under the exact touch centroid stationary. */
    Focal,
}

/**
 * Gesture settings applied by [KanvasChart].
 */
data class KlineGestureConfig(
    val enableLongPress: Boolean = true,
    val enableInertialPan: Boolean = true,
    val tolerance: KlineGestureTolerance = KlineGestureTolerance(),
    /** Compose-native horizontal drag multiplier. `1f` is physical 1:1 tracking. */
    val panSensitivity: Float = 1.2f,
    val enableScale: Boolean = true,
    val scaleAnchor: KlineScaleAnchor = KlineScaleAnchor.Auto,
    val scaleSpeed: Float = 10f,
    val autoLoadMore: Boolean = true,
    val loadMoreWhenNoEnoughDistancePx: Float? = null,
    val loadMoreWhenNoEnoughCandles: Int = 60,
    val supportKeyboardShortcuts: Boolean = true,
    val enableZoom: Boolean = false,
    val zoomStartMinDistancePx: Float = 5f,
    val zoomSpeed: Float = 1f,
    val isManualSetZoomRect: Boolean = false,
) {
    init {
        require(loadMoreWhenNoEnoughDistancePx == null || loadMoreWhenNoEnoughDistancePx >= 0f) {
            "loadMoreWhenNoEnoughDistancePx must not be negative."
        }
        require(loadMoreWhenNoEnoughCandles >= 0) {
            "loadMoreWhenNoEnoughCandles must not be negative."
        }
        require(panSensitivity.isFinite() && panSensitivity > 0f) {
            "panSensitivity must be finite and positive."
        }
        require(zoomStartMinDistancePx.isFinite() && zoomStartMinDistancePx >= 0f) {
            "zoomStartMinDistancePx must be finite and nonnegative."
        }
        require(zoomSpeed.isFinite() && zoomSpeed > 0f) { "zoomSpeed must be finite and positive." }
    }

    /** Prevents configured scale speed from becoming ineffective or extreme. */
    val effectiveScaleSpeed: Float get() = scaleSpeed.coerceIn(1f, 30f)

    /** Keeps host overrides useful without allowing an accidentally extreme drag multiplier. */
    val effectivePanSensitivity: Float get() = panSensitivity.coerceIn(0.5f, 3f)
}

data class KlineGestureTolerance(
    val maxDurationMillis: Int = 3_000,
    val distanceFactor: Float = 0.8f,
    val curve: String = "easeOutCubic",
    /**
     * Main-pane Y-range smoothing during a pan. The native default is exact
     * convergence so reaching a viewport boundary cannot cause a post-gesture
     * vertical expansion; hosts may opt into interpolation explicitly.
     */
    val panSmoothFactor: Float = 1f,
    val convergenceRatio: Float = 0.85f,
) {
    init {
        require(maxDurationMillis > 0) { "Inertial-pan max duration must be positive." }
        require(distanceFactor.isFinite() && distanceFactor >= 0f) {
            "Inertial-pan distance factor must be finite and nonnegative."
        }
        require(panSmoothFactor.isFinite() && convergenceRatio.isFinite()) {
            "Gesture smoothing factors must be finite."
        }
    }

    val effectivePanSmoothFactor: Float get() = panSmoothFactor.coerceIn(0.1f, 1f)
    val effectiveConvergenceRatio: Float get() = convergenceRatio.coerceIn(0f, 1f)
}

data class KlineInertialPanSpec(
    val distancePx: Float,
    val durationMillis: Int,
)

internal enum class KlineLoadMoreIntent {
    None,
    Prefetch,
    ShowLoading,
}

/**
 * Edge-triggered load-more dispatch for silent prefetch and visible loading.
 * `null` means the host must not be called again for the current request.
 */
internal fun KlineLoadMoreIntent.dispatchFor(
    loadingState: KlineLoadingState,
): Boolean? = when (this) {
    KlineLoadMoreIntent.None -> null
    KlineLoadMoreIntent.Prefetch ->
        if (loadingState.isLoadMore || loadingState == KlineLoadingState.InitLoading) null else false
    KlineLoadMoreIntent.ShowLoading -> when (loadingState) {
        KlineLoadingState.None -> true
        // This call only promotes the existing silent request. The controller
        // keeps its token and deliberately emits no second data event.
        KlineLoadingState.LoadMore -> true
        KlineLoadingState.InitLoading,
        KlineLoadingState.LoadingMore,
        -> null
    }
}

/** Rejects a noisy release velocity that would send inertia against the drag. */
internal fun resolveKlineInertialVelocity(
    velocityPxPerSecond: Float,
    dragDeltaPx: Float,
): Float {
    if (!velocityPxPerSecond.isFinite() || !dragDeltaPx.isFinite()) return 0f
    return if (kotlin.math.abs(dragDeltaPx) > 0.5f && velocityPxPerSecond * dragDeltaPx < 0f) {
        0f
    } else {
        velocityPxPerSecond
    }
}

/**
 * Pixel math for the Android gesture router.
 *
 * Left, middle, and right keep a stable chart-relative anchor;
 * [KlineScaleAnchor.Focal] keeps the exact touch centroid stable.
 */
object KlineInteractionMath {
    fun inertialPanSpec(
        velocityPxPerSecond: Float,
        tolerance: KlineGestureTolerance,
    ): KlineInertialPanSpec? {
        if (!velocityPxPerSecond.isFinite()) return null
        val distance = velocityPxPerSecond * tolerance.distanceFactor
        if (kotlin.math.abs(distance) < 0.000_001f) return null
        val duration = (
            kotlin.math.sqrt(kotlin.math.max(1f, kotlin.math.abs(distance))) *
                tolerance.maxDurationMillis / 100f
            ).roundToInt().coerceIn(0, tolerance.maxDurationMillis)
        return duration.takeIf { it > 1 }?.let { KlineInertialPanSpec(distance, it) }
    }

    fun resolveAnchor(
        requested: KlineScaleAnchor,
        focalX: Float,
        plotWidthPx: Float,
    ): KlineScaleAnchor = when (requested) {
        KlineScaleAnchor.Auto -> when {
            focalX < plotWidthPx / 3f -> KlineScaleAnchor.Left
            focalX > plotWidthPx * 2f / 3f -> KlineScaleAnchor.Right
            else -> KlineScaleAnchor.Middle
        }

        else -> requested
    }

    /**
     * Applies one incremental pinch update. `zoomDelta` is the scale factor
     * minus one from a pointer event, so `0f` means no scale change.
     */
    fun scaleByGesture(
        viewport: KlineViewport,
        zoomDelta: Float,
        focalX: Float,
        plotWidthPx: Float,
        minCandleWidthPx: Float,
        maxCandleWidthPx: Float,
        scaleSpeed: Float = 10f,
        anchor: KlineScaleAnchor = KlineScaleAnchor.Auto,
    ): KlineViewport {
        require(plotWidthPx >= 0f) { "plotWidthPx must not be negative." }
        require(minCandleWidthPx > 0f) { "minCandleWidthPx must be positive." }
        require(maxCandleWidthPx >= minCandleWidthPx) { "maxCandleWidthPx must be >= minCandleWidthPx." }
        require(scaleSpeed > 0f) { "scaleSpeed must be positive." }

        val newWidth = (viewport.candleWidthPx + zoomDelta * scaleSpeed)
            .coerceIn(minCandleWidthPx, maxCandleWidthPx)
        return scaleToWidth(
            viewport = viewport,
            newCandleWidthPx = newWidth,
            focalX = focalX,
            plotWidthPx = plotWidthPx,
            anchor = anchor,
        )
    }

    fun scaleToWidth(
        viewport: KlineViewport,
        newCandleWidthPx: Float,
        focalX: Float,
        plotWidthPx: Float,
        anchor: KlineScaleAnchor = KlineScaleAnchor.Auto,
    ): KlineViewport {
        require(newCandleWidthPx > 0f) { "newCandleWidthPx must be positive." }
        require(plotWidthPx >= 0f) { "plotWidthPx must not be negative." }
        if (newCandleWidthPx == viewport.candleWidthPx) return viewport

        val factor = (newCandleWidthPx + viewport.candleSpacingPx) / viewport.candleStepPx
        val resolved = resolveAnchor(anchor, focalX, plotWidthPx)
        val oldOffset = viewport.rightEdgeOffsetPx
        val offset = when (resolved) {
            KlineScaleAnchor.Right -> if (oldOffset <= 0f) oldOffset else oldOffset * factor
            KlineScaleAnchor.Left -> (plotWidthPx + oldOffset) * factor - plotWidthPx
            KlineScaleAnchor.Middle,
            KlineScaleAnchor.Auto -> {
                if (oldOffset <= 0f) {
                    val rightDistance = plotWidthPx - focalX
                    (rightDistance + oldOffset) * factor - rightDistance
                } else {
                    val halfWidth = plotWidthPx / 2f
                    (halfWidth + oldOffset) * factor - halfWidth
                }
            }

            KlineScaleAnchor.Focal -> {
                val oldIndex = viewport.fractionalIndexAt(plotWidthPx, focalX)
                focalX + oldIndex.toFloat() * (newCandleWidthPx + viewport.candleSpacingPx) - plotWidthPx
            }
        }
        return viewport.copy(candleWidthPx = newCandleWidthPx, rightEdgeOffsetPx = offset)
    }

    /** Applies configured viewport bounds after a pan or scale operation. */
    fun clamp(
        candleCount: Int,
        viewport: KlineViewport,
        constraints: KlineViewportConstraints,
    ): KlineViewport {
        val bounds = KlineViewportMath.bounds(candleCount, viewport, constraints)
        return viewport.copy(rightEdgeOffsetPx = bounds.clamp(viewport.rightEdgeOffsetPx))
    }

    /** Same load-more early-trigger condition used by `checkAndLoadMoreCandlesWhenPanEnd`. */
    fun isNearOlderEdge(
        candleCount: Int,
        viewport: KlineViewport,
        constraints: KlineViewportConstraints,
        loadMoreWhenNoEnoughDistancePx: Float? = null,
        loadMoreWhenNoEnoughCandles: Int = 60,
    ): Boolean {
        require(loadMoreWhenNoEnoughCandles >= 0) { "loadMoreWhenNoEnoughCandles must not be negative." }
        val threshold = loadMoreWhenNoEnoughDistancePx
            ?: loadMoreWhenNoEnoughCandles * viewport.candleStepPx
        val bounds = KlineViewportMath.bounds(candleCount, viewport, constraints)
        return viewport.rightEdgeOffsetPx > bounds.maxOffsetPx - threshold
    }

    /**
     * Predicts loading behavior before inertia starts:
     * prefetch inside the configured buffer and show loading only when the
     * projected destination reaches the hard historical boundary.
     */
    internal fun loadMoreIntent(
        candleCount: Int,
        projectedViewport: KlineViewport,
        constraints: KlineViewportConstraints,
        loadMoreWhenNoEnoughDistancePx: Float? = null,
        loadMoreWhenNoEnoughCandles: Int = 60,
    ): KlineLoadMoreIntent {
        require(loadMoreWhenNoEnoughCandles >= 0) { "loadMoreWhenNoEnoughCandles must not be negative." }
        val threshold = loadMoreWhenNoEnoughDistancePx
            ?: loadMoreWhenNoEnoughCandles * projectedViewport.candleStepPx
        val bounds = KlineViewportMath.bounds(candleCount, projectedViewport, constraints)
        val destination = projectedViewport.rightEdgeOffsetPx
        if (destination <= bounds.maxOffsetPx - threshold) return KlineLoadMoreIntent.None
        return if (destination >= bounds.maxOffsetPx) {
            KlineLoadMoreIntent.ShowLoading
        } else {
            KlineLoadMoreIntent.Prefetch
        }
    }
}
