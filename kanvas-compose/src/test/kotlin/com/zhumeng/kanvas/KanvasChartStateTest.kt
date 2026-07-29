/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import com.zhumeng.kanvas.core.IndicatorRuntimeCoordinator
import com.zhumeng.kanvas.core.KlineCandle
import com.zhumeng.kanvas.core.KlineController
import com.zhumeng.kanvas.core.KlineInterval
import com.zhumeng.kanvas.core.KlineSpec
import com.zhumeng.kanvas.drawing.DrawingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KanvasChartStateTest {
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
