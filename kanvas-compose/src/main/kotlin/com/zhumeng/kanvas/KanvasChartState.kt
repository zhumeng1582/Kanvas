/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhumeng.kanvas.core.IndicatorDefinition
import com.zhumeng.kanvas.core.IndicatorKey
import com.zhumeng.kanvas.core.IndicatorRegistrySnapshot
import com.zhumeng.kanvas.core.IndicatorRuntimeCoordinator
import com.zhumeng.kanvas.core.IndicatorRuntimeSnapshot
import com.zhumeng.kanvas.core.KlineCandle
import com.zhumeng.kanvas.core.KlineComputeMode
import com.zhumeng.kanvas.core.KlineController
import com.zhumeng.kanvas.core.KlineDataResult
import com.zhumeng.kanvas.core.KlineEvent
import com.zhumeng.kanvas.core.KlineIndicatorRefreshPolicy
import com.zhumeng.kanvas.core.KlineSeries
import com.zhumeng.kanvas.core.KlineSpec
import com.zhumeng.kanvas.core.KlineUiState
import com.zhumeng.kanvas.core.KlineViewport
import com.zhumeng.kanvas.core.KlineViewportConstraints
import com.zhumeng.kanvas.drawing.DrawingController
import com.zhumeng.kanvas.drawing.DrawingMagnifierConfig
import com.zhumeng.kanvas.drawing.DrawingRenderConfig
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Input ordering accepted by the high-level data APIs. */
enum class KanvasCandleOrder {
    NewestFirst,
    OldestFirst,
}

/**
 * Common chart configuration for the high-level [KanvasChart] overload.
 * Specialized slots and extension callbacks remain optional call arguments.
 */
data class KanvasChartConfig(
    val chartType: KlineChartType = KlineChartType.Bar(),
    val candleZIndex: Int = -1,
    val style: KlineChartStyle = KlineChartStyle(),
    val render: KlineChartRenderConfig = KlineChartRenderConfig(),
    val panes: KlinePaneRenderConfig = KlinePaneRenderConfig(),
    val builtInIndicators: KlineBuiltInIndicatorConfiguration? = null,
    val timeAxis: KlineTimeAxisRenderConfig = KlineTimeAxisRenderConfig(),
    val axisWidth: Dp = 64.dp,
    val timeAxisHeight: Dp = 15.dp,
    val orderMarkers: KlineOrderMarkerRenderConfig = KlineOrderMarkerRenderConfig(),
    val drawing: DrawingRenderConfig = DrawingRenderConfig(),
    val drawingMagnifier: DrawingMagnifierConfig = DrawingMagnifierConfig(),
    /** The high-level API returns to the latest edge after a confirmed double tap. */
    val resetToLatestOnDoubleTap: Boolean = true,
)

/** Optional observations for the high-level chart API. All behavior has useful defaults. */
data class KanvasChartCallbacks(
    val onLayoutChange: (KlineLayout) -> Unit = {},
    val onLoadMoreRequested: (showLoading: Boolean) -> Unit = {},
    val onCrosshairChange: (KlineCrosshair?) -> Unit = {},
    val onUnsupportedIndicators: (List<IndicatorDefinition>) -> Unit = {},
    val onDoubleTap: () -> Unit = {},
)

/**
 * Type-safe facade over indicator selection, parameter updates, and sub-pane order.
 * The lower-level registry/runtime remain implementation details of [KanvasChartState].
 */
@Stable
class KanvasIndicatorState internal constructor(
    internal val chartRuntime: KlineIndicatorPluginChartRuntime,
    internal val coordinator: IndicatorRuntimeCoordinator,
) {
    val state: StateFlow<IndicatorRegistrySnapshot> get() = chartRuntime.indicatorRegistry.state
    val calculationState: StateFlow<IndicatorRuntimeSnapshot?> get() = coordinator.state

    fun snapshot(): IndicatorRegistrySnapshot = chartRuntime.indicatorRegistry.snapshot()

    fun show(key: IndicatorKey): IndicatorRegistrySnapshot = chartRuntime.indicatorRegistry.show(key)

    fun show(plugin: KlineIndicatorPlugin<*>): IndicatorRegistrySnapshot = show(plugin.key)

    fun hide(key: IndicatorKey): IndicatorRegistrySnapshot = chartRuntime.indicatorRegistry.hide(key)

    fun hide(plugin: KlineIndicatorPlugin<*>): IndicatorRegistrySnapshot = hide(plugin.key)

    fun toggle(key: IndicatorKey): IndicatorRegistrySnapshot =
        if (snapshot().isActive(key)) hide(key) else show(key)

    fun toggle(plugin: KlineIndicatorPlugin<*>): IndicatorRegistrySnapshot = toggle(plugin.key)

    fun moveSubIndicator(key: IndicatorKey, index: Int): IndicatorRegistrySnapshot =
        chartRuntime.indicatorRegistry.moveActiveSub(key, index)

    fun moveSubIndicator(plugin: KlineIndicatorPlugin<*>, index: Int): IndicatorRegistrySnapshot =
        moveSubIndicator(plugin.key, index)

    fun update(binding: KlineIndicatorPluginBinding): IndicatorRegistrySnapshot {
        require(snapshot().isRegistered(binding.definition.key)) {
            "Indicator '${binding.definition.key.id}' is not part of this chart's plugin catalog"
        }
        return chartRuntime.indicatorRegistry.upsert(binding.definition)
    }

    fun <C : KlineIndicatorPluginConfig> update(
        plugin: KlineIndicatorPlugin<C>,
        config: C,
    ): IndicatorRegistrySnapshot = update(plugin.bind(config))

    fun <C : KlineIndicatorPluginConfig> update(
        plugin: KlineIndicatorPlugin<C>,
        transform: (C) -> C,
    ): IndicatorRegistrySnapshot {
        val current = requireNotNull(config(plugin)) {
            "Indicator '${plugin.key.id}' has no compatible registered configuration"
        }
        return update(plugin, transform(current))
    }

    @Suppress("UNCHECKED_CAST")
    fun <C : KlineIndicatorPluginConfig> config(plugin: KlineIndicatorPlugin<C>): C? =
        snapshot().definition(plugin.key)?.configuration as? C

    /** Advanced escape hatch that still preserves registry lifecycle semantics. */
    fun updateDefinition(
        key: IndicatorKey,
        transform: (IndicatorDefinition) -> IndicatorDefinition,
    ): IndicatorRegistrySnapshot {
        val current = requireNotNull(snapshot().definition(key)) {
            "Indicator '${key.id}' is not registered"
        }
        val updated = transform(current)
        require(updated.key == current.key) { "An indicator update must preserve its kind/id key" }
        return chartRuntime.indicatorRegistry.upsert(updated)
    }

    fun retryCalculation() = coordinator.retry()
}

/**
 * Stateful, lifecycle-safe entry point intended for application developers.
 * It owns data, viewport, loading, drawings, and the complete indicator runtime.
 */
@Stable
class KanvasChartState internal constructor(
    private val controller: KlineController,
    val indicators: KanvasIndicatorState,
    val drawingController: DrawingController,
    val defaultConfig: KanvasChartConfig,
) {
    val state: StateFlow<KlineUiState> get() = controller.state
    val events: SharedFlow<KlineEvent> get() = controller.events

    fun select(
        spec: KlineSpec,
        useCache: Boolean = true,
        restoreViewport: Boolean = false,
    ): KlineUiState = controller.select(spec, useCache, restoreViewport)

    fun setMarket(
        spec: KlineSpec,
        candles: List<KlineCandle>,
        order: KanvasCandleOrder = KanvasCandleOrder.NewestFirst,
    ): KlineDataResult {
        controller.select(spec, useCache = false)
        return controller.replaceAll(spec, candles.inOrder(order))
    }

    fun setData(
        candles: List<KlineCandle>,
        order: KanvasCandleOrder = KanvasCandleOrder.NewestFirst,
    ): KlineDataResult = controller.replaceAll(requireSpec(), candles.inOrder(order))

    /** Publishes a series that may have been validated on a background dispatcher. */
    fun setData(series: KlineSeries): KlineDataResult = controller.replaceAll(requireSpec(), series)

    fun updateLatest(candle: KlineCandle): KlineDataResult =
        controller.updateLatest(requireSpec(), candle)

    fun completeLoadMore(
        requestId: Long,
        candles: List<KlineCandle>,
        hasMoreOlder: Boolean = true,
        order: KanvasCandleOrder = KanvasCandleOrder.NewestFirst,
    ): KlineDataResult = controller.completeLoadMore(requestId, candles.inOrder(order), hasMoreOlder)

    /** Completes the currently pending request without making callers retain its token. */
    fun completeLoadMore(
        candles: List<KlineCandle>,
        hasMoreOlder: Boolean = true,
        order: KanvasCandleOrder = KanvasCandleOrder.NewestFirst,
    ): KlineDataResult {
        val requestId = requireNotNull(state.value.loadMoreRequestId) {
            "There is no pending Kanvas load-more request"
        }
        return completeLoadMore(requestId, candles, hasMoreOlder, order)
    }

    fun failLoadMore(requestId: Long, message: String): KlineUiState =
        controller.failLoadMore(requestId, message)

    fun failLoadMore(message: String): KlineUiState =
        controller.failLoadMore(
            requireNotNull(state.value.loadMoreRequestId) { "There is no pending Kanvas load-more request" },
            message,
        )

    fun requestLoadMore(showLoading: Boolean = false): Boolean = controller.requestLoadMore(showLoading)

    fun moveToLatest(): KlineUiState = controller.moveToInitialPosition()

    fun moveTo(timestampMillis: Long): KlineUiState? = controller.moveToDateTime(timestampMillis)

    fun updateViewport(viewport: KlineViewport): KlineUiState = controller.updateViewport(viewport)

    internal fun updateViewportConstraints(
        constraints: KlineViewportConstraints,
    ): KlineUiState = controller.updateViewportConstraints(constraints)

    private fun requireSpec(): KlineSpec = requireNotNull(state.value.spec) {
        "Select a KlineSpec or call setMarket before updating chart data"
    }
}

/** Creates the standard third-party integration surface and owns all runtime cleanup. */
@Composable
fun rememberKanvasChartState(
    config: KanvasChartConfig = KanvasChartConfig(),
    indicatorCatalog: KlineIndicatorPluginCatalog = KlineIndicatorPluginCatalog.Empty,
    activeIndicatorKeys: Iterable<IndicatorKey> = emptyList(),
    subIndicatorCapacity: Int = com.zhumeng.kanvas.core.IndicatorRegistry.DefaultSubIndicatorCapacity,
    cacheCapacity: Int = KlineController.DefaultCacheCapacity,
    computeMode: KlineComputeMode = KlineComputeMode.Fast,
    indicatorRefreshPolicy: KlineIndicatorRefreshPolicy = KlineIndicatorRefreshPolicy.OnCandleBoundary,
    drawingController: DrawingController? = null,
    onIndicatorCalculationError: (Throwable) -> Unit = {},
    onRendererError: (IndicatorDefinition, Throwable) -> Unit = { _, _ -> },
): KanvasChartState {
    val density = LocalDensity.current.density
    // Hosts may replace or animate visual configuration. Preserve the data/viewport session;
    // the chart applies current physical constraints again on each layout pass.
    val controller = remember(density, cacheCapacity) {
        KlineController(
            cacheCapacity = cacheCapacity,
            initialViewport = config.render.viewport.initialViewport(density),
        )
    }
    val runtime = rememberKlineIndicatorPluginChartRuntime(
        catalog = indicatorCatalog,
        activeKeys = activeIndicatorKeys,
        subIndicatorCapacity = subIndicatorCapacity,
        onRendererError = onRendererError,
    )
    val scope = rememberCoroutineScope()
    val currentCalculationError = rememberUpdatedState(onIndicatorCalculationError)
    val coordinator = remember(controller, runtime.indicatorRegistry, scope, computeMode, indicatorRefreshPolicy) {
        IndicatorRuntimeCoordinator(
            controller = controller,
            registry = runtime.indicatorRegistry,
            scope = scope,
            computeMode = computeMode,
            refreshPolicy = indicatorRefreshPolicy,
            onCalculationError = { error -> currentCalculationError.value(error) },
        )
    }
    DisposableEffect(coordinator) {
        onDispose(coordinator::close)
    }
    val indicatorState = remember(runtime, coordinator) { KanvasIndicatorState(runtime, coordinator) }
    val resolvedDrawingController = remember(drawingController) { drawingController ?: DrawingController() }
    return remember(controller, indicatorState, resolvedDrawingController, config) {
        KanvasChartState(controller, indicatorState, resolvedDrawingController, config)
    }
}

/** Standard chart entry point: state owns all controller and indicator plumbing. */
@Composable
fun KanvasChart(
    state: KanvasChartState,
    modifier: Modifier = Modifier,
    config: KanvasChartConfig = state.defaultConfig,
    callbacks: KanvasChartCallbacks = KanvasChartCallbacks(),
    orderMarkers: List<KlineOrderMarker> = emptyList(),
    crossTooltipProvider: KlineCrossTooltipProvider? = null,
    timeLabelFormatter: KlineTimeLabelFormatter? = null,
    mainBackgroundContent: (@Composable (KlineChartSlotContext) -> Unit)? = null,
    mainForegroundContent: (@Composable (KlineChartSlotContext) -> Unit)? = null,
    verticalZoomHitRect: Rect? = null,
    verticalZoomExitContent: (@Composable (onExit: () -> Unit) -> Unit)? = null,
) {
    val chart by state.state.collectAsState()
    val indicatorRegistry by state.indicators.state.collectAsState()
    val indicatorOutput by state.indicators.calculationState.collectAsState()
    KanvasChart(
        state = chart,
        onViewportChange = state::updateViewport,
        modifier = modifier,
        onViewportConstraintsChange = { constraints ->
            // Keep physical layout details inside the facade.
            val current = state.state.value
            if (current.viewportConstraints != constraints) {
                state.updateViewportConstraints(constraints)
            }
        },
        onLayoutChange = callbacks.onLayoutChange,
        onLoadMoreRequested = { showLoading ->
            state.requestLoadMore(showLoading)
            callbacks.onLoadMoreRequested(showLoading)
        },
        onMoveToInitialPosition = state::moveToLatest,
        onCrosshairChange = callbacks.onCrosshairChange,
        chartType = config.chartType,
        candleZIndex = config.candleZIndex,
        style = config.style,
        renderConfig = config.render,
        orderMarkers = orderMarkers,
        orderMarkerConfig = config.orderMarkers,
        indicatorSnapshot = indicatorOutput,
        indicatorRegistrySnapshot = indicatorRegistry,
        indicatorRendererRegistry = state.indicators.chartRuntime.rendererRegistry,
        indicatorRendererLifecycleHost = state.indicators.chartRuntime.indicatorRendererLifecycleHost,
        onUnsupportedIndicators = callbacks.onUnsupportedIndicators,
        paneConfig = config.panes,
        builtInIndicators = config.builtInIndicators,
        timeAxisConfig = config.timeAxis,
        timeLabelFormatter = timeLabelFormatter,
        axisWidth = config.axisWidth,
        timeAxisHeight = config.timeAxisHeight,
        crossTooltipProvider = crossTooltipProvider,
        mainBackgroundContent = mainBackgroundContent,
        mainForegroundContent = mainForegroundContent,
        drawingController = state.drawingController,
        drawingConfig = config.drawing,
        drawingMagnifierConfig = config.drawingMagnifier,
        verticalZoomHitRect = verticalZoomHitRect,
        onDoubleTap = {
            if (config.resetToLatestOnDoubleTap) state.moveToLatest()
            callbacks.onDoubleTap()
        },
        verticalZoomExitContent = verticalZoomExitContent,
    )
}

/**
 * Minimal data-driven entry point. Use [rememberKanvasChartState] directly for
 * realtime updates, pagination, indicators, drawings, or viewport control.
 */
@Composable
fun KanvasChart(
    candles: List<KlineCandle>,
    modifier: Modifier = Modifier,
    spec: KlineSpec = KlineSpec("kanvas", com.zhumeng.kanvas.core.KlineInterval.Invalid),
    order: KanvasCandleOrder = KanvasCandleOrder.NewestFirst,
    config: KanvasChartConfig = KanvasChartConfig(),
    callbacks: KanvasChartCallbacks = KanvasChartCallbacks(),
) {
    val state = rememberKanvasChartState(config = config)
    androidx.compose.runtime.LaunchedEffect(state, spec, candles, order) {
        val current = state.state.value
        if (current.spec?.key != spec.key) {
            state.setMarket(spec, candles, order)
        } else if (current.series.candles != candles.inOrder(order)) {
            state.setData(candles, order)
        }
    }
    KanvasChart(state = state, modifier = modifier, config = config, callbacks = callbacks)
}

private fun List<KlineCandle>.inOrder(order: KanvasCandleOrder): List<KlineCandle> =
    when (order) {
        KanvasCandleOrder.NewestFirst -> this
        KanvasCandleOrder.OldestFirst -> asReversed()
    }
