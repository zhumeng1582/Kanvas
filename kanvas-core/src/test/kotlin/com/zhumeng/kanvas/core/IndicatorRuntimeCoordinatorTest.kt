/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndicatorRuntimeCoordinatorTest {
    @Test
    fun `registry update recalculates only the changed definition`() = runBlocking {
        val firstCalls = AtomicInteger(0)
        val secondCalls = AtomicInteger(0)
        fun definition(id: String, calls: AtomicInteger, zIndex: Int = 0): IndicatorDefinition =
            IndicatorDefinition(
                key = IndicatorKey.computed(id),
                zIndex = zIndex,
                calculator = IndicatorCalculator { input ->
                    calls.incrementAndGet()
                    IndicatorOutput.of(
                        key = input.definition.key,
                        seriesSize = input.series.size,
                        columns = listOf(
                            IndicatorColumn.of("close", input.series.candles.map(KlineCandle::close)),
                        ),
                    )
                },
            )

        val first = definition("first", firstCalls)
        val second = definition("second", secondCalls)
        val controller = KlineController()
        val selectedSpec = spec("BTC-USDT")
        controller.select(selectedSpec, useCache = false)
        controller.replaceAll(selectedSpec, candles(4.0, 3.0, 2.0))
        val registry = IndicatorRegistry().apply {
            mount(listOf(first, second), restoredActiveKeys = listOf(first.key, second.key))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = IndicatorRuntimeCoordinator(
            controller = controller,
            registry = registry,
            scope = scope,
            refreshPolicy = KlineIndicatorRefreshPolicy.OnCandleBoundary,
        )
        try {
            withTimeout(2_000) {
                coordinator.state.filterNotNull().first { it.matches(registry.snapshot()) }
            }
            assertEquals(1, firstCalls.get())
            assertEquals(1, secondCalls.get())

            val updatedRegistry = registry.upsert(second.copy(zIndex = 1))
            withTimeout(2_000) {
                coordinator.state.filterNotNull().first { it.matches(updatedRegistry) }
            }

            assertEquals(1, firstCalls.get())
            assertEquals(2, secondCalls.get())
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun `candle boundary policy keeps indicators stable across open candle ticks`() = runBlocking {
        val calls = AtomicInteger(0)
        val calculator = IndicatorCalculator { input ->
            calls.incrementAndGet()
            IndicatorOutput.of(
                key = input.definition.key,
                seriesSize = input.series.size,
                columns = listOf(IndicatorColumn.of("close", input.series.candles.map(KlineCandle::close))),
            )
        }
        val definition = IndicatorDefinition(IndicatorKey.computed("boundary"), calculator = calculator)
        val controller = KlineController()
        val selectedSpec = spec("BTC-USDT")
        controller.select(selectedSpec, useCache = false)
        controller.replaceAll(selectedSpec, candles(4.0, 3.0, 2.0))
        val registry = IndicatorRegistry().apply {
            mount(listOf(definition), restoredActiveKeys = listOf(definition.key))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = IndicatorRuntimeCoordinator(
            controller = controller,
            registry = registry,
            scope = scope,
            refreshPolicy = KlineIndicatorRefreshPolicy.OnCandleBoundary,
        )
        try {
            val initial = withTimeout(2_000) {
                coordinator.state.filterNotNull().first { it.sourceRevision == controller.state.value.revision }
            }
            val initialValues = initial.output(definition.key)!!.column("close")!!.asList()
            assertEquals(1, calls.get())

            val latest = checkNotNull(controller.state.value.series.latest)
            controller.updateLatest(
                selectedSpec,
                latest.copy(high = 40.0, close = 40.0),
            )
            val tickRevision = controller.state.value.revision
            val tick = withTimeout(2_000) {
                coordinator.state.filterNotNull().first { it.sourceRevision == tickRevision }
            }
            assertEquals(initialValues, tick.output(definition.key)!!.column("close")!!.asList())
            assertEquals(1, calls.get())

            controller.updateLatest(
                selectedSpec,
                latest.copy(timestampMillis = latest.timestampMillis + 1L, high = 41.0, close = 41.0),
            )
            val boundaryRevision = controller.state.value.revision
            val boundary = withTimeout(2_000) {
                coordinator.state.filterNotNull().first { it.sourceRevision == boundaryRevision }
            }
            assertEquals(2, calls.get())
            assertEquals(41.0, boundary.output(definition.key)!!.column("close")!![0])
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun `coordinator automatically broadcasts a spec change and publishes its new generation`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = KlineController()
        val definition = movingAverageDefinition()
        val registry = IndicatorRegistry().apply {
            mount(listOf(definition), restoredActiveKeys = listOf(definition.key))
        }
        val coordinator = IndicatorRuntimeCoordinator(
            controller = controller,
            registry = registry,
            scope = scope,
        )
        try {
            val firstSpec = spec("BTC-USDT")
            controller.select(firstSpec, useCache = false)
            controller.replaceAll(firstSpec, candles(4.0, 3.0, 2.0))
            withTimeout(2_000) {
                coordinator.state.filterNotNull().first { snapshot ->
                    snapshot.matches(registry.snapshot()) && snapshot.series == controller.state.value.series
                }
            }
            val beforeSwitch = registry.snapshot()

            val secondSpec = spec("ETH-USDT")
            controller.select(secondSpec, useCache = false)
            val afterSpecChange = withTimeout(2_000) {
                registry.state.first { snapshot -> snapshot.specEpoch > beforeSwitch.specEpoch }
            }
            controller.replaceAll(secondSpec, candles(30.0, 20.0, 10.0))
            val fresh = withTimeout(2_000) {
                coordinator.state.filterNotNull().first { snapshot ->
                    snapshot.matches(afterSpecChange) && snapshot.series == controller.state.value.series
                }
            }

            assertEquals(firstSpec, afterSpecChange.latestSpecChangeOldSpec)
            assertTrue(afterSpecChange.generation > beforeSwitch.generation)
            assertEquals(listOf(25.0, 15.0, Double.NaN), fresh.output(definition.key)!!.column("ma_2")!!.asList())
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun `late non cooperative calculation cannot publish after controller revision changes`() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val calculator = IndicatorCalculator { input ->
            if (calls.incrementAndGet() == 1) {
                started.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
            IndicatorOutput.of(
                key = input.definition.key,
                seriesSize = input.series.size,
                columns = listOf(IndicatorColumn.of("close", input.series.candles.map(KlineCandle::close))),
            )
        }
        val definition = IndicatorDefinition(
            key = IndicatorKey.computed("slow"),
            calculator = calculator,
        )
        val controller = KlineController()
        val firstSpec = spec("BTC-USDT")
        controller.select(firstSpec, useCache = false)
        controller.replaceAll(firstSpec, candles(4.0, 3.0, 2.0))
        val registry = IndicatorRegistry().apply {
            mount(listOf(definition), restoredActiveKeys = listOf(definition.key))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = IndicatorRuntimeCoordinator(controller, registry, scope)
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))

            val secondSpec = spec("ETH-USDT")
            controller.select(secondSpec, useCache = false)
            controller.replaceAll(secondSpec, candles(40.0, 30.0, 20.0))
            release.countDown()

            val fresh = withTimeout(2_000) {
                coordinator.state.filterNotNull().first { snapshot ->
                    snapshot.matches(registry.snapshot()) && snapshot.series == controller.state.value.series
                }
            }

            assertEquals(listOf(40.0, 30.0, 20.0), fresh.output(definition.key)!!.column("close")!!.asList())
            assertTrue(calls.get() >= 2)
        } finally {
            release.countDown()
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun `error clears stale output and retry survives a throwing diagnostics callback`() = runBlocking {
        val shouldFail = AtomicBoolean(false)
        val diagnostics = AtomicInteger(0)
        val calculator = IndicatorCalculator { input ->
            if (shouldFail.get()) error("transient")
            IndicatorOutput.of(
                key = input.definition.key,
                seriesSize = input.series.size,
                columns = listOf(IndicatorColumn.of("close", input.series.candles.map(KlineCandle::close))),
            )
        }
        val definition = IndicatorDefinition(
            key = IndicatorKey.computed("retrying"),
            calculator = calculator,
        )
        val controller = KlineController()
        val selectedSpec = spec("BTC-USDT")
        controller.select(selectedSpec, useCache = false)
        controller.replaceAll(selectedSpec, candles(4.0, 3.0, 2.0))
        val registry = IndicatorRegistry().apply {
            mount(listOf(definition), restoredActiveKeys = listOf(definition.key))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = IndicatorRuntimeCoordinator(
            controller = controller,
            registry = registry,
            scope = scope,
            onCalculationError = {
                diagnostics.incrementAndGet()
                error("diagnostics must not terminate coordinator")
            },
        )
        try {
            val first = withTimeout(2_000) {
                coordinator.state.filterNotNull().first { snapshot ->
                    snapshot.matches(registry.snapshot()) && snapshot.series == controller.state.value.series
                }
            }
            assertEquals(controller.state.value.revision, first.sourceRevision)

            shouldFail.set(true)
            controller.replaceAll(selectedSpec, candles(40.0, 30.0, 20.0))
            withTimeout(2_000) { coordinator.error.filterNotNull().first() }
            assertNull(coordinator.state.value)
            assertEquals(1, diagnostics.get())

            shouldFail.set(false)
            coordinator.retry()
            val retried = withTimeout(2_000) {
                coordinator.state.filterNotNull().first { snapshot ->
                    snapshot.matches(registry.snapshot()) && snapshot.series == controller.state.value.series
                }
            }
            assertEquals(listOf(40.0, 30.0, 20.0), retried.output(definition.key)!!.column("close")!!.asList())
            assertNull(coordinator.error.value)
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    private fun movingAverageDefinition(): IndicatorDefinition = IndicatorDefinition(
        key = IndicatorKey.computed("ma"),
        calculator = MovingAverage(listOf(2)),
    )

    private fun spec(symbol: String): KlineSpec = KlineSpec(
        symbol = symbol,
        interval = KlineInterval(1, KlineTimeUnit.Hour),
    )

    private fun candles(vararg closes: Double): List<KlineCandle> = closes.mapIndexed { index, close ->
        KlineCandle(
            timestampMillis = (closes.size - index).toLong(),
            open = close,
            high = close,
            low = close,
            close = close,
            volume = close * 10.0,
        )
    }
}
