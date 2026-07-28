/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Immutable state published to renderers and host integrations. */
data class KlineUiState(
    val spec: KlineSpec? = null,
    val series: KlineSeries = KlineSeries.Empty,
    val viewport: KlineViewport = KlineViewport(),
    val viewportConstraints: KlineViewportConstraints? = null,
    val loadingState: KlineLoadingState = KlineLoadingState.None,
    val loadMoreRequestId: Long? = null,
    val loadMoreError: String? = null,
    val canLoadMoreOlder: Boolean = true,
    /** Increments only when a chart session reset must dismiss transient UI such as Cross. */
    val interactionEpoch: Long = 0L,
    val revision: Long = 0L,
)

sealed interface KlineEvent {
    /** The host must fetch a normalized page strictly before [beforeTimestampMillis]. */
    data class LoadMore(
        val spec: KlineSpec,
        val beforeTimestampMillis: Long,
        val requestId: Long = 0L,
    ) : KlineEvent
}

enum class KlineDataDisposition {
    Applied,
    CachedInactiveSpec,
    IgnoredInactiveSpec,
    IgnoredStaleLoadMore,
}

data class KlineDataResult(
    val disposition: KlineDataDisposition,
    val update: KlineSeriesUpdateResult? = null,
)

private data class CachedSeries(
    val series: KlineSeries,
    val viewport: KlineViewport,
)

/**
 * Thread-safe domain controller with no Android/Compose dependency.
 *
 * A canvas never fetches data itself: it observes [state] and the host reacts
 * to [events]. Switching or resetting a series increments [KlineUiState.revision]
 * so indicator jobs can reject stale work before publishing results.
 */
class KlineController(
    private val cacheCapacity: Int = DefaultCacheCapacity,
    initialViewport: KlineViewport = KlineViewport(),
) {
    init {
        require(cacheCapacity >= 0) { "cacheCapacity must be >= 0" }
    }

    private val lock = Any()
    private val cache = object : LinkedHashMap<String, CachedSeries>(cacheCapacity + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedSeries>?): Boolean =
            size > cacheCapacity
    }

    private val mutableState = MutableStateFlow(KlineUiState(viewport = initialViewport))
    private val mutableEvents = MutableSharedFlow<KlineEvent>(extraBufferCapacity = 16)
    private var nextLoadMoreRequestId = 1L

    val state: StateFlow<KlineUiState> = mutableState.asStateFlow()
    val events: SharedFlow<KlineEvent> = mutableEvents.asSharedFlow()

    /** Selects a symbol/interval, restoring its LRU cache when available. */
    fun select(
        spec: KlineSpec,
        useCache: Boolean = true,
        useCachePaintDxOffset: Boolean = false,
    ): KlineUiState = synchronized(lock) {
        val previous = mutableState.value
        val cached = if (useCache) cache[spec.key] else null
        val nextSeries = cached?.series ?: KlineSeries.Empty
        val nextViewport = cached?.viewport?.takeIf { useCachePaintDxOffset } ?: previous.viewport
        val nextConstraints = previous.viewportConstraints
        val adjustedViewport = resetOrClampViewport(
            series = nextSeries,
            viewport = nextViewport,
            constraints = nextConstraints,
            reset = cached == null || !useCachePaintDxOffset,
        )
        val next = KlineUiState(
            spec = spec,
            series = nextSeries,
            viewport = adjustedViewport,
            viewportConstraints = nextConstraints,
            loadingState = if (cached == null) KlineLoadingState.InitLoading else KlineLoadingState.None,
            loadMoreRequestId = null,
            loadMoreError = null,
            canLoadMoreOlder = true,
            interactionEpoch = previous.interactionEpoch + 1,
            revision = previous.revision + 1,
        )
        mutableState.value = next
        next
    }

    /** Replaces one chart session with a complete, host-normalized snapshot. */
    fun replaceAll(
        spec: KlineSpec,
        candles: List<KlineCandle>,
    ): KlineDataResult = synchronized(lock) {
        val current = mutableState.value
        if (current.spec?.key != spec.key) {
            val cached = cache[spec.key]
                ?: return KlineDataResult(KlineDataDisposition.IgnoredInactiveSpec)
            val series = KlineSeries.of(candles)
            val update = KlineSeriesUpdateResult(
                series = series,
                changedRange = IndexRange(0, maxOf(cached.series.size, series.size)),
            )
            cache[spec.key] = cached.copy(series = series)
            return KlineDataResult(KlineDataDisposition.CachedInactiveSpec, update)
        }

        val series = KlineSeries.of(candles)
        val nextViewport = resetOrClampViewport(
            series = series,
            viewport = current.viewport,
            constraints = current.viewportConstraints,
            reset = true,
        )
        val next = current.copy(
            series = series,
            viewport = nextViewport,
            loadingState = KlineLoadingState.None,
            loadMoreRequestId = null,
            loadMoreError = null,
            canLoadMoreOlder = true,
            interactionEpoch = current.interactionEpoch + 1,
            revision = current.revision + 1,
        )
        mutableState.value = next
        rememberCurrent(next)
        KlineDataResult(
            KlineDataDisposition.Applied,
            KlineSeriesUpdateResult(series, IndexRange(0, maxOf(current.series.size, series.size))),
        )
    }

    /** Applies one real-time candle at the newest edge without sorting or middle-history reconciliation. */
    fun updateLatest(
        spec: KlineSpec,
        candle: KlineCandle,
    ): KlineDataResult = synchronized(lock) {
        val current = mutableState.value
        if (current.spec?.key != spec.key) {
            val cached = cache[spec.key]
                ?: return KlineDataResult(KlineDataDisposition.IgnoredInactiveSpec)
            val update = cached.series.updateLatest(candle)
            cache[spec.key] = cached.copy(series = update.series)
            return KlineDataResult(KlineDataDisposition.CachedInactiveSpec, update)
        }
        val update = current.series.updateLatest(candle)
        if (!update.changed) return KlineDataResult(KlineDataDisposition.Applied, update)
        val next = current.copy(
            series = update.series,
            viewport = resetOrClampViewport(
                update.series,
                current.viewport,
                current.viewportConstraints,
                reset = false,
            ),
            // A market tick is independent of historical pagination. Preserve
            // its state and request token until the host completes or fails it.
            revision = current.revision + 1,
        )
        mutableState.value = next
        rememberCurrent(next)
        KlineDataResult(KlineDataDisposition.Applied, update)
    }

    /** Updates viewport geometry after Compose measures its plot rect. */
    fun updateViewportConstraints(constraints: KlineViewportConstraints): KlineUiState = synchronized(lock) {
        val current = mutableState.value
        val next = current.copy(
            viewportConstraints = constraints,
            viewport = resetOrClampViewport(current.series, current.viewport, constraints, reset = false),
        )
        mutableState.value = next
        rememberCurrent(next)
        next
    }

    /** Applies a caller-computed viewport while enforcing the source bounds. */
    fun updateViewport(viewport: KlineViewport): KlineUiState = synchronized(lock) {
        val current = mutableState.value
        val next = current.copy(
            viewport = resetOrClampViewport(current.series, viewport, current.viewportConstraints, reset = false),
        )
        mutableState.value = next
        rememberCurrent(next)
        next
    }

    fun panBy(deltaPx: Float): KlineUiState = synchronized(lock) {
        val current = mutableState.value
        updateViewport(current.viewport.copy(rightEdgeOffsetPx = current.viewport.rightEdgeOffsetPx + deltaPx))
    }

    /** Computes a centered move target without mutating controller state. */
    fun viewportForDateTime(timestampMillis: Long): KlineViewport? = synchronized(lock) {
        val current = mutableState.value
        val constraints = current.viewportConstraints ?: return null
        val index = current.series.indexAtOrBefore(timestampMillis) ?: return null
        val targetOffset = KlineViewportMath.offsetForCenteredIndex(
            index = index.toDouble(),
            viewport = current.viewport,
            plotWidthPx = constraints.plotWidthPx,
        )
        val bounds = KlineViewportMath.bounds(current.series.size, current.viewport, constraints)
        current.viewport.copy(rightEdgeOffsetPx = bounds.clamp(targetOffset))
    }

    /** Moves immediately; hosts can animate toward [viewportForDateTime] through updateViewport. */
    fun moveToDateTime(timestampMillis: Long): KlineUiState? = synchronized(lock) {
        val target = viewportForDateTime(timestampMillis) ?: return null
        val current = mutableState.value
        val next = current.copy(
            viewport = target,
            interactionEpoch = current.interactionEpoch + 1,
        )
        mutableState.value = next
        rememberCurrent(next)
        next
    }

    /**
     * Animated counterpart of [moveToDateTime]. Core remains
     * UI-toolkit independent and publishes ordinary viewport snapshots.
     */
    suspend fun animateMoveToDateTime(
        timestampMillis: Long,
        durationMillis: Int = 300,
        frameMillis: Long = 16L,
    ): KlineUiState? {
        require(durationMillis >= 0)
        require(frameMillis > 0)
        val target = viewportForDateTime(timestampMillis) ?: return null
        if (durationMillis == 0) return moveToDateTime(timestampMillis)
        val start = state.value.viewport
        val frames = (durationMillis / frameMillis).toInt().coerceAtLeast(1)
        repeat(frames) { index ->
            val linear = (index + 1f) / frames
            val eased = 1f - (1f - linear) * (1f - linear) * (1f - linear)
            updateViewport(
                start.copy(
                    candleWidthPx = start.candleWidthPx + (target.candleWidthPx - start.candleWidthPx) * eased,
                    candleSpacingPx =
                        start.candleSpacingPx + (target.candleSpacingPx - start.candleSpacingPx) * eased,
                    rightEdgeOffsetPx =
                        start.rightEdgeOffsetPx + (target.rightEdgeOffsetPx - start.rightEdgeOffsetPx) * eased,
                ),
            )
            if (index < frames - 1) delay(frameMillis)
        }
        return state.value
    }

    /** Returns to the initial right-edge offset and clears pending load-more state. */
    fun moveToInitialPosition(): KlineUiState = synchronized(lock) {
        val current = mutableState.value
        val constraints = current.viewportConstraints
        val offset = if (constraints == null) current.viewport.rightEdgeOffsetPx else {
            KlineViewportMath.bounds(current.series.size, current.viewport, constraints).initialOffsetPx
        }
        val next = current.copy(
            viewport = current.viewport.copy(rightEdgeOffsetPx = offset),
            loadingState = KlineLoadingState.None,
            loadMoreRequestId = null,
            loadMoreError = null,
        )
        mutableState.value = next
        rememberCurrent(next)
        next
    }

    /** Emits a single older-data request until the host returns data or clears loading. */
    fun requestLoadMore(showLoading: Boolean = false): Boolean = synchronized(lock) {
        val current = mutableState.value
        val spec = current.spec ?: return false
        val oldest = current.series.oldest ?: return false
        if (current.loadingState.isLoadMore) {
            if (showLoading && current.loadingState == KlineLoadingState.LoadMore) {
                mutableState.value = current.copy(loadingState = KlineLoadingState.LoadingMore)
            }
            return false
        }
        if (!current.canLoadMoreOlder) return false

        val requestId = nextLoadMoreRequestId++
        val event = KlineEvent.LoadMore(
            spec = spec,
            beforeTimestampMillis = oldest.timestampMillis,
            requestId = requestId,
        )
        // Publish the in-flight state before the host can observe and complete
        // the request. Emitting first leaves a
        // race where a fast completion is rejected as stale because its token
        // has not reached state yet.
        mutableState.value = current.copy(
            loadingState = if (showLoading) KlineLoadingState.LoadingMore else KlineLoadingState.LoadMore,
            loadMoreRequestId = requestId,
            loadMoreError = null,
        )
        if (!mutableEvents.tryEmit(event)) {
            mutableState.value = current
            return false
        }
        true
    }

    fun markLoadingMore(requestId: Long? = null): KlineUiState = synchronized(lock) {
        val current = mutableState.value
        if (requestId != null && requestId != current.loadMoreRequestId) return current
        val next = current.copy(loadingState = KlineLoadingState.LoadingMore)
        mutableState.value = next
        next
    }

    fun completeLoadMore(
        requestId: Long,
        incoming: List<KlineCandle>,
        hasMoreOlder: Boolean = true,
    ): KlineDataResult = synchronized(lock) {
        val current = mutableState.value
        if (current.loadMoreRequestId != requestId) {
            return KlineDataResult(KlineDataDisposition.IgnoredStaleLoadMore)
        }
        val update = current.series.appendOlder(incoming)
        val next = current.copy(
            series = update.series,
            // Historical pages are appended to the newest-first series, so
            // every existing candle keeps its index. Keeping the same offset
            // therefore keeps the content under the user's finger stationary
            // and lets the new page fill the historical-side blank space.
            viewport = resetOrClampViewport(
                update.series,
                current.viewport,
                current.viewportConstraints,
                reset = false,
            ),
            loadingState = KlineLoadingState.None,
            loadMoreRequestId = null,
            loadMoreError = null,
            canLoadMoreOlder = hasMoreOlder,
            revision = current.revision + if (update.changed) 1 else 0,
        )
        mutableState.value = next
        rememberCurrent(next)
        KlineDataResult(KlineDataDisposition.Applied, update)
    }

    fun failLoadMore(requestId: Long, message: String): KlineUiState = synchronized(lock) {
        require(message.isNotBlank()) { "Load-more failure message must not be blank." }
        val current = mutableState.value
        if (current.loadMoreRequestId != requestId) return current
        val next = current.copy(
            loadingState = KlineLoadingState.None,
            loadMoreRequestId = null,
            loadMoreError = message,
        )
        mutableState.value = next
        next
    }

    fun resetLoadMoreAvailability(): KlineUiState = synchronized(lock) {
        val current = mutableState.value
        val next = current.copy(canLoadMoreOlder = true, loadMoreError = null)
        mutableState.value = next
        next
    }

    fun clearLoading(): KlineUiState = synchronized(lock) {
        val current = mutableState.value
        val next = current.copy(
            loadingState = KlineLoadingState.None,
            loadMoreRequestId = null,
            loadMoreError = null,
        )
        mutableState.value = next
        next
    }

    /** Evicts one cached chart session without affecting the active state. */
    fun evictCache(spec: KlineSpec): Boolean = evictCache(spec.key)

    fun evictCache(specKey: String): Boolean = synchronized(lock) {
        cache.remove(specKey) != null
    }

    /** Clears inactive and active-session cache snapshots; live state remains displayed. */
    fun clearCache() = synchronized(lock) {
        cache.clear()
    }

    /** Oldest-to-newest LRU order, intended for diagnostics and deterministic tests. */
    fun cachedSpecKeys(): List<String> = synchronized(lock) { cache.keys.toList() }

    private fun resetOrClampViewport(
        series: KlineSeries,
        viewport: KlineViewport,
        constraints: KlineViewportConstraints?,
        reset: Boolean,
    ): KlineViewport {
        if (constraints == null) return viewport
        val bounds = KlineViewportMath.bounds(series.size, viewport, constraints)
        val offset = if (reset) bounds.initialOffsetPx else bounds.clamp(viewport.rightEdgeOffsetPx)
        return viewport.copy(rightEdgeOffsetPx = offset)
    }

    private fun rememberCurrent(state: KlineUiState) {
        val spec = state.spec ?: return
        if (cacheCapacity == 0) return
        cache[spec.key] = CachedSeries(state.series, state.viewport)
    }

    companion object {
        const val DefaultCacheCapacity: Int = 3
    }
}
