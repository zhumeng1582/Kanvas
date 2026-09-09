/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import com.zhumeng.kanvas.core.IndicatorRuntimeCoordinator
import com.zhumeng.kanvas.core.IndicatorCalculator
import com.zhumeng.kanvas.core.IndicatorDefinition
import com.zhumeng.kanvas.core.IndicatorKey
import com.zhumeng.kanvas.core.IndicatorOutput
import com.zhumeng.kanvas.core.KlineCandle
import com.zhumeng.kanvas.core.KlineController
import com.zhumeng.kanvas.core.KlineDataDisposition
import com.zhumeng.kanvas.core.KlineInterval
import com.zhumeng.kanvas.core.KlineSpec
import com.zhumeng.kanvas.core.KlineViewport
import com.zhumeng.kanvas.drawing.DrawingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KanvasChartStateTest {
    @Test
    fun `live facade viewport updates invalidate draw state without replacing composition state`() {
        val initial = com.zhumeng.kanvas.core.KlineUiState(
            viewport = KlineViewport(rightEdgeOffsetPx = 0f),
        )
        val live = KanvasLiveChartState(initial)
        val panned = initial.copy(viewport = initial.viewport.copy(rightEdgeOffsetPx = 40f))

        live.update(panned)

        assertTrue(live.compositionState.value === initial)
        assertTrue(live.drawState.value === panned)

        val revised = panned.copy(revision = 1L)
        live.update(revised)
        assertTrue(live.compositionState.value === revised)
    }

    @Test
    fun `facade normalizes input and completes the current historical request`() {
        val fixture = fixture()
        val spec = KlineSpec("BTC-USDT", KlineInterval.minutes(15), precision = 2)

        fixture.state.setMarket(
            spec = spec,
            candles = listOf(candle(100L), candle(200L), candle(300L)),
            order = KanvasCandleOrder.OldestFirst,
        )
        assertEquals(listOf(300L, 200L, 100L), timestamps(fixture.state))

        assertTrue(fixture.state.requestLoadMore())
        fixture.state.completeLoadMore(
            candles = listOf(candle(0L), candle(50L)),
            order = KanvasCandleOrder.OldestFirst,
            hasMoreOlder = false,
        )

        assertEquals(listOf(300L, 200L, 100L, 50L, 0L), timestamps(fixture.state))
        fixture.close()
    }

    @Test
    fun `load more observation runs only for an accepted request`() {
        val fixture = fixture()
        val observations = mutableListOf<Boolean>()

        assertFalse(fixture.state.dispatchLoadMoreRequest(false, observations::add))
        fixture.state.setMarket(
            spec = KlineSpec("BTC-USDT", KlineInterval.minutes(15), precision = 2),
            candles = listOf(candle(300L), candle(200L), candle(100L)),
        )

        assertTrue(fixture.state.dispatchLoadMoreRequest(false, observations::add))
        assertFalse(fixture.state.dispatchLoadMoreRequest(true, observations::add))
        assertEquals(listOf(false), observations)
        fixture.close()
    }

    @Test
    fun `indicator facade toggles and updates typed plugin configuration`() {
        val plugin = KlineMovingAverageIndicatorPlugin()
        val catalog = KlineIndicatorPluginCatalog.of(plugin.bind())
        val fixture = fixture(catalog)

        fixture.state.indicators.show(plugin.key)
        val updated = KlineMovingAverageIndicatorConfig(periods = listOf(5, 10, 20))
        fixture.state.indicators.update(plugin, updated)

        assertTrue(fixture.state.indicators.snapshot().isActive(plugin.key))
        assertEquals(updated, fixture.state.indicators.config(plugin))
        fixture.close()
    }

    @Test
    fun `prepared historical completion publishes aligned indicators with the candle page`() = runBlocking {
        val plugin = KlineMovingAverageIndicatorPlugin(
            defaultConfig = KlineMovingAverageIndicatorConfig(periods = listOf(2)),
        )
        val fixture = fixture(KlineIndicatorPluginCatalog.of(plugin.bind()))
        val spec = KlineSpec("BTC-USDT", KlineInterval.minutes(15), precision = 2)
        fixture.state.indicators.show(plugin.key)
        fixture.state.setMarket(
            spec = spec,
            candles = listOf(candle(300L), candle(200L), candle(100L)),
        )

        // Start pagination after the initial market/registry reconciliation.
        // Its asynchronous spec notification otherwise races this assertion.
        withTimeout(5_000L) {
            fixture.state.indicators.calculationState.first { snapshot ->
                snapshot?.matches(fixture.state.state.value, fixture.state.indicators.snapshot()) == true
            }
        }

        assertTrue(fixture.state.requestLoadMore())
        val result = fixture.state.completeLoadMorePrepared(
            candles = listOf(candle(50L), candle(0L)),
            hasMoreOlder = false,
        )

        assertEquals(KlineDataDisposition.Applied, result.disposition)
        val chart = fixture.state.state.value
        val registry = fixture.state.indicators.snapshot()
        val indicators = checkNotNull(fixture.state.indicators.calculationState.value)
        assertTrue(
            indicators.matches(chart, registry),
            "Chart revision=${chart.revision}, size=${chart.series.size}; " +
                "indicator revision=${indicators.sourceRevision}, size=${indicators.series.size}; " +
                "registry matches=${indicators.matches(registry)}",
        )
        assertEquals(chart.series.size, indicators.output(plugin.key)?.seriesSize)
        fixture.close()
    }

    @Test
    fun `prepared historical completion cancels without publishing after bounded contention`() = runBlocking {
        val calculationAttempts = AtomicInteger()
        lateinit var chartState: KanvasChartState
        val indicatorKey = IndicatorKey.computed("contended")
        val plugin = object : KlineIndicatorPlugin<KlineEmptyIndicatorPluginConfig> {
            override val key: IndicatorKey = indicatorKey
            override val defaultConfig: KlineEmptyIndicatorPluginConfig = KlineEmptyIndicatorPluginConfig

            override fun createDefinition(config: KlineEmptyIndicatorPluginConfig): IndicatorDefinition =
                IndicatorDefinition(
                    key = indicatorKey,
                    calculator = IndicatorCalculator { input ->
                        val current = chartState.state.value
                        if (current.series.size < input.series.size) {
                            calculationAttempts.incrementAndGet()
                            val latest = checkNotNull(current.series.latest)
                            chartState.updateLatest(latest.copy(close = latest.close + 0.01))
                        }
                        IndicatorOutput.empty(indicatorKey, input.series.size)
                    },
                )
        }
        val fixture = fixture(KlineIndicatorPluginCatalog.of(plugin.bind()))
        chartState = fixture.state
        fixture.state.indicators.show(indicatorKey)
        fixture.state.setMarket(
            spec = KlineSpec("BTC-USDT", KlineInterval.minutes(15), precision = 2),
            candles = listOf(candle(300L), candle(200L), candle(100L)),
        )
        assertTrue(fixture.state.requestLoadMore())

        val result = withTimeout(5_000L) {
            fixture.state.completeLoadMorePrepared(
                candles = listOf(candle(50L), candle(0L)),
                hasMoreOlder = false,
            )
        }

        assertEquals(KlineDataDisposition.IgnoredStalePreparedData, result.disposition)
        assertEquals(3, fixture.state.state.value.series.size)
        assertEquals(null, fixture.state.state.value.loadMoreRequestId)
        assertFalse(fixture.state.state.value.loadingState.isLoadMore)
        assertEquals(3, calculationAttempts.get())
        assertTrue(fixture.state.requestLoadMore())
        fixture.close()
    }

    private fun fixture(
        catalog: KlineIndicatorPluginCatalog = KlineIndicatorPluginCatalog.Empty,
    ): Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = KlineController()
        val runtime = catalog.createChartRuntime()
        val coordinator = IndicatorRuntimeCoordinator(
            controller = controller,
            registry = runtime.indicatorRegistry,
            scope = scope,
        )
        val indicators = KanvasIndicatorState(runtime, coordinator)
        return Fixture(
            state = KanvasChartState(
                controller = controller,
                indicators = indicators,
                drawingController = DrawingController(),
                defaultConfig = KanvasChartConfig(),
            ),
            runtime = runtime,
            coordinator = coordinator,
        )
    }

    private fun timestamps(state: KanvasChartState): List<Long> =
        state.state.value.series.candles.map(KlineCandle::timestampMillis)

    private fun candle(timestamp: Long): KlineCandle =
        KlineCandle(timestamp, 1.0, 2.0, 0.0, 1.0, 1.0)

    private data class Fixture(
        val state: KanvasChartState,
        val runtime: KlineIndicatorPluginChartRuntime,
        val coordinator: IndicatorRuntimeCoordinator,
    ) {
        fun close() {
            coordinator.close()
            runtime.close()
        }
    }
}
