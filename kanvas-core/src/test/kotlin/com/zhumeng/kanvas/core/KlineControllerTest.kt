package com.zhumeng.kanvas.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KlineControllerTest {
    @Test
    fun `host can seed the controller with density resolved viewport geometry`() {
        val controller = KlineController(
            initialViewport = KlineViewport(candleWidthPx = 21f, candleSpacingPx = 3f),
        )

        assertEquals(21f, controller.state.value.viewport.candleWidthPx)
        assertEquals(3f, controller.state.value.viewport.candleSpacingPx)
    }

    @Test
    fun `select reset and cache restore follow series identity`() {
        val controller = KlineController(cacheCapacity = 2)
        val constraints = KlineViewportConstraints(plotWidthPx = 400f)
        controller.updateViewportConstraints(constraints)
        val btc = spec("BTC-USDT")

        assertEquals(KlineLoadingState.InitLoading, controller.select(btc).loadingState)
        assertEquals(1L, controller.state.value.interactionEpoch)
        controller.replaceAll(btc, listOf(candle(300L), candle(200L), candle(100L)))
        assertEquals(-376f, controller.state.value.viewport.rightEdgeOffsetPx)
        assertEquals(KlineLoadingState.None, controller.state.value.loadingState)
        assertEquals(2L, controller.state.value.interactionEpoch)

        controller.select(spec("ETH-USDT"))
        controller.replaceAll(spec("ETH-USDT"), listOf(candle(20L)))
        val restored = controller.select(btc)

        assertEquals(KlineLoadingState.None, restored.loadingState)
        assertEquals(5L, restored.interactionEpoch)
        assertEquals(listOf(300L, 200L, 100L), restored.series.candles.map(KlineCandle::timestampMillis))
    }

    @Test
    fun `incremental data updates do not reset transient chart interaction`() {
        val controller = KlineController()
        val btc = spec("BTC-USDT")
        controller.select(btc)
        controller.replaceAll(btc, listOf(candle(300L)))
        val afterReset = controller.state.value.interactionEpoch

        controller.updateLatest(btc, candle(400L))

        assertEquals(afterReset, controller.state.value.interactionEpoch)
    }

    @Test
    fun `late result for inactive spec is ignored`() {
        val controller = KlineController()
        val btc = spec("BTC-USDT")
        val eth = spec("ETH-USDT")
        controller.select(btc)
        controller.select(eth)

        val result = controller.replaceAll(btc, listOf(candle(100L)))

        assertEquals(KlineDataDisposition.IgnoredInactiveSpec, result.disposition)
        assertTrue(controller.state.value.series.isEmpty)
    }

    @Test
    fun `inactive cached spec accepts updates and cache can be evicted explicitly`() {
        val controller = KlineController(cacheCapacity = 2)
        val btc = spec("BTC-USDT")
        val eth = spec("ETH-USDT")
        controller.select(btc)
        controller.replaceAll(btc, listOf(candle(100L)))
        controller.select(eth)

        val cachedUpdate = controller.updateLatest(btc, candle(200L))
        assertEquals(KlineDataDisposition.CachedInactiveSpec, cachedUpdate.disposition)
        assertEquals(listOf(200L, 100L), controller.select(btc).series.candles.map { it.timestampMillis })
        assertTrue(controller.evictCache(btc))
        assertFalse(btc.key in controller.cachedSpecKeys())
        controller.clearCache()
        assertTrue(controller.cachedSpecKeys().isEmpty())
    }

    @Test
    fun `cached series and cached paint offset are independently selectable`() {
        val controller = KlineController(cacheCapacity = 2)
        controller.updateViewportConstraints(KlineViewportConstraints(plotWidthPx = 100f))
        val btc = spec("BTC-USDT")
        val eth = spec("ETH-USDT")
        controller.select(btc)
        controller.replaceAll(btc, (1L..20L).reversed().map(::candle))
        controller.panBy(30f)
        val cachedOffset = controller.state.value.viewport.rightEdgeOffsetPx
        controller.select(eth)

        val resetOffset = controller.select(btc, useCache = true, useCachePaintDxOffset = false)
            .viewport.rightEdgeOffsetPx
        controller.select(eth)
        val restoredOffset = controller.select(btc, useCache = true, useCachePaintDxOffset = true)
            .viewport.rightEdgeOffsetPx

        assertTrue(resetOffset != cachedOffset)
        assertEquals(cachedOffset, restoredOffset)
    }

    @Test
    fun `load more emits oldest timestamp once until cleared`() = runBlocking {
        val controller = KlineController()
        val spec = spec("BTC-USDT")
        controller.select(spec)
        controller.replaceAll(spec, listOf(candle(300L), candle(200L), candle(100L)))
        val event = async(start = CoroutineStart.UNDISPATCHED) { controller.events.first() }

        assertTrue(controller.requestLoadMore())
        val load = assertIs<KlineEvent.LoadMore>(event.await())
        assertEquals(100L, load.beforeTimestampMillis)
        assertEquals(spec, load.spec)
        assertEquals(load.requestId, controller.state.value.loadMoreRequestId)
        assertFalse(controller.requestLoadMore())
        assertEquals(KlineLoadingState.LoadMore, controller.state.value.loadingState)
        assertFalse(controller.requestLoadMore(showLoading = true))
        assertEquals(KlineLoadingState.LoadingMore, controller.state.value.loadingState)

        controller.clearLoading()
        assertTrue(controller.requestLoadMore())
    }

    @Test
    fun `load more state and token are visible before its event`() = runBlocking {
        val controller = KlineController()
        val spec = spec("BTC-USDT")
        controller.select(spec)
        controller.replaceAll(spec, listOf(candle(300L), candle(200L), candle(100L)))
        val observedState = async(start = CoroutineStart.UNDISPATCHED) {
            val event = assertIs<KlineEvent.LoadMore>(controller.events.first())
            event to controller.state.value
        }

        assertTrue(controller.requestLoadMore())

        val (event, state) = observedState.await()
        assertEquals(KlineLoadingState.LoadMore, state.loadingState)
        assertEquals(event.requestId, state.loadMoreRequestId)
    }

    @Test
    fun `realtime ticks preserve an in-flight load more request`() = runBlocking {
        val controller = KlineController()
        val spec = spec("BTC-USDT")
        controller.select(spec)
        controller.replaceAll(spec, listOf(candle(300L), candle(200L), candle(100L)))
        val event = async(start = CoroutineStart.UNDISPATCHED) { controller.events.first() }

        assertTrue(controller.requestLoadMore(showLoading = true))
        val request = assertIs<KlineEvent.LoadMore>(event.await())
        controller.updateLatest(spec, candle(300L).copy(close = 301.0))
        controller.updateLatest(spec, candle(400L).copy(close = 302.0))

        val whileLoading = controller.state.value
        assertEquals(KlineLoadingState.LoadingMore, whileLoading.loadingState)
        assertEquals(request.requestId, whileLoading.loadMoreRequestId)
        assertFalse(controller.requestLoadMore())

        val completed = controller.completeLoadMore(
            requestId = request.requestId,
            incoming = listOf(candle(50L)),
        )
        assertEquals(KlineDataDisposition.Applied, completed.disposition)
        assertEquals(KlineLoadingState.None, controller.state.value.loadingState)
        assertEquals(null, controller.state.value.loadMoreRequestId)
        assertEquals(
            listOf(400L, 300L, 200L, 100L, 50L),
            controller.state.value.series.candles.map { it.timestampMillis },
        )
    }

    @Test
    fun `load more token rejects stale completion and records failure and exhaustion`() = runBlocking {
        val controller = KlineController()
        val spec = spec("BTC-USDT")
        controller.select(spec)
        controller.replaceAll(spec, listOf(candle(300L), candle(200L)))
        val firstEvent = async(start = CoroutineStart.UNDISPATCHED) { controller.events.first() }
        assertTrue(controller.requestLoadMore())
        val first = assertIs<KlineEvent.LoadMore>(firstEvent.await())

        assertEquals(
            KlineDataDisposition.IgnoredStaleLoadMore,
            controller.completeLoadMore(first.requestId + 1, listOf(candle(100L))).disposition,
        )
        controller.failLoadMore(first.requestId, "network")
        assertEquals("network", controller.state.value.loadMoreError)

        val secondEvent = async(start = CoroutineStart.UNDISPATCHED) { controller.events.first() }
        assertTrue(controller.requestLoadMore())
        val second = assertIs<KlineEvent.LoadMore>(secondEvent.await())
        controller.markLoadingMore(second.requestId)
        val completed = controller.completeLoadMore(
            requestId = second.requestId,
            incoming = listOf(candle(100L)),
            hasMoreOlder = false,
        )
        assertEquals(KlineDataDisposition.Applied, completed.disposition)
        assertFalse(controller.state.value.canLoadMoreOlder)
        assertFalse(controller.requestLoadMore())

        controller.resetLoadMoreAvailability()
        assertTrue(controller.state.value.canLoadMoreOlder)
    }

    @Test
    fun `completing load more keeps visible candles stationary at the older edge`() = runBlocking {
        val controller = KlineController(
            initialViewport = KlineViewport(candleWidthPx = 9f, candleSpacingPx = 1f),
        )
        val spec = spec("BTC-USDT")
        val constraints = KlineViewportConstraints(plotWidthPx = 400f)
        controller.updateViewportConstraints(constraints)
        controller.select(spec)
        controller.replaceAll(spec, (101L..200L).reversed().map(::candle))
        controller.panBy(10_000f)

        val viewportBefore = controller.state.value.viewport
        val oldestXBefore = viewportBefore.xForIndex(constraints.plotWidthPx, 99.0)
        val event = async(start = CoroutineStart.UNDISPATCHED) { controller.events.first() }
        assertTrue(controller.requestLoadMore(showLoading = true))
        val request = assertIs<KlineEvent.LoadMore>(event.await())

        controller.completeLoadMore(
            requestId = request.requestId,
            incoming = (1L..100L).reversed().map(::candle),
        )

        val viewportAfter = controller.state.value.viewport
        val oldestXAfter = viewportAfter.xForIndex(constraints.plotWidthPx, 99.0)
        assertEquals(viewportBefore.rightEdgeOffsetPx, viewportAfter.rightEdgeOffsetPx)
        assertEquals(oldestXBefore, oldestXAfter)
        assertTrue(
            viewportAfter.rightEdgeOffsetPx <
                KlineViewportMath.bounds(200, viewportAfter, constraints).maxOffsetPx,
        )
        assertEquals(KlineLoadingState.None, controller.state.value.loadingState)
    }

    @Test
    fun `pan is clamped by current geometry`() {
        val controller = KlineController()
        val spec = spec("BTC-USDT")
        controller.updateViewportConstraints(KlineViewportConstraints(plotWidthPx = 400f))
        controller.select(spec)
        controller.replaceAll(spec, (1..100).reversed().map { candle(it.toLong()) })

        controller.panBy(10_000f)

        assertEquals(600f, controller.state.value.viewport.rightEdgeOffsetPx)
    }

    @Test
    fun `move to date time centers nearest older candle and rejects unloaded dates`() {
        val controller = KlineController(initialViewport = KlineViewport(candleWidthPx = 9f, candleSpacingPx = 1f))
        val spec = spec("BTC-USDT")
        controller.updateViewportConstraints(KlineViewportConstraints(plotWidthPx = 100f))
        controller.select(spec)
        controller.replaceAll(spec, (1L..30L).reversed().map(::candle))

        val moved = checkNotNull(controller.moveToDateTime(20L))
        assertEquals(55f, moved.viewport.rightEdgeOffsetPx)
        assertEquals(null, controller.moveToDateTime(31L))
        assertEquals(null, controller.moveToDateTime(0L))
    }

    @Test
    fun `animated move to date time reaches the same clamped target`() = runBlocking {
        val controller = KlineController(initialViewport = KlineViewport(candleWidthPx = 9f, candleSpacingPx = 1f))
        val spec = spec("BTC-USDT")
        controller.updateViewportConstraints(KlineViewportConstraints(plotWidthPx = 100f))
        controller.select(spec)
        controller.replaceAll(spec, (1L..30L).reversed().map(::candle))

        val expected = controller.viewportForDateTime(20L)
        val moved = controller.animateMoveToDateTime(20L, durationMillis = 1, frameMillis = 16)
        assertEquals(expected, moved?.viewport)
    }

    @Test
    fun `move to initial position preserves scale and clears loading more`() {
        val controller = KlineController(
            initialViewport = KlineViewport(candleWidthPx = 12f, candleSpacingPx = 2f),
        )
        val spec = spec("BTC-USDT")
        controller.updateViewportConstraints(KlineViewportConstraints(plotWidthPx = 400f))
        controller.select(spec)
        controller.replaceAll(spec, (1..100).reversed().map { candle(it.toLong()) })
        controller.panBy(10_000f)
        assertTrue(controller.requestLoadMore())

        val moved = controller.moveToInitialPosition()

        assertEquals(-80f, moved.viewport.rightEdgeOffsetPx)
        assertEquals(12f, moved.viewport.candleWidthPx)
        assertEquals(2f, moved.viewport.candleSpacingPx)
        assertEquals(KlineLoadingState.None, moved.loadingState)
    }

    private fun spec(symbol: String): KlineSpec = KlineSpec(
        symbol = symbol,
        interval = KlineInterval(1, KlineTimeUnit.Hour),
    )

    private fun candle(timestampMillis: Long): KlineCandle = KlineCandle(
        timestampMillis = timestampMillis,
        open = 1.0,
        high = 2.0,
        low = 0.0,
        close = 1.0,
        volume = 1.0,
    )
}
