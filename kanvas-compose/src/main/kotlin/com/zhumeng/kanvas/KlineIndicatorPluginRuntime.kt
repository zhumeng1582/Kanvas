/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.zhumeng.kanvas.core.IndicatorDefinition
import com.zhumeng.kanvas.core.IndicatorKey
import com.zhumeng.kanvas.core.IndicatorRegistry

/**
 * Coherent chart-side ownership for a [KlineIndicatorPluginCatalog].
 *
 * Keep these three objects together whenever a native plugin supplies a
 * stateful renderer factory. The registry supplies lifecycle residency, the
 * renderer registry resolves stable plugin renderers, and the lifecycle host
 * gives factories their init/attach/update/detach/dispose ordering.
 */
class KlineIndicatorPluginChartRuntime internal constructor(
    val indicatorRegistry: IndicatorRegistry,
    val rendererRegistry: KlineIndicatorRendererRegistry,
    val indicatorRendererLifecycleHost: KlineIndicatorRendererLifecycleHost,
) : AutoCloseable {
    /** Releases stateful renderers before disposing the registry this runtime owns. */
    override fun close() {
        indicatorRendererLifecycleHost.close()
        indicatorRegistry.dispose()
    }
}

/**
 * Materializes Core residency and Compose rendering ownership for one native
 * catalog. Non-Compose callers own [KlineIndicatorPluginChartRuntime.close].
 *
 * The runtime deliberately does not own [com.zhumeng.kanvas.core.IndicatorRuntimeCoordinator]:
 * a controller and coroutine scope remain host-owned.
 */
fun KlineIndicatorPluginCatalog.createChartRuntime(
    activeKeys: Iterable<IndicatorKey> = emptyList(),
    subIndicatorCapacity: Int = IndicatorRegistry.DefaultSubIndicatorCapacity,
    fallback: KlineIndicatorRendererRegistry = KlineIndicatorRendererRegistry.Default,
    onRendererError: (IndicatorDefinition, Throwable) -> Unit = { _, _ -> },
): KlineIndicatorPluginChartRuntime {
    val rendererRegistry = createRendererRegistry(fallback)
    return KlineIndicatorPluginChartRuntime(
        indicatorRegistry = mountIndicatorRegistry(
            activeKeys = activeKeys,
            subIndicatorCapacity = subIndicatorCapacity,
        ),
        rendererRegistry = rendererRegistry,
        indicatorRendererLifecycleHost = KlineIndicatorRendererLifecycleHost(rendererRegistry, onRendererError),
    )
}

/**
 * Compose-owned native plugin runtime. Keep [catalog] and [fallback] stable
 * (normally with `remember`) so unrelated recompositions retain stateful
 * renderer instances. To change one plugin config in place, call
 * `runtime.indicatorRegistry.upsert(plugin.bind(newConfig).definition)`;
 * the lifecycle host will deliver `onUpdate` on its next chart composition.
 */
@Composable
fun rememberKlineIndicatorPluginChartRuntime(
    catalog: KlineIndicatorPluginCatalog,
    activeKeys: Iterable<IndicatorKey> = emptyList(),
    subIndicatorCapacity: Int = IndicatorRegistry.DefaultSubIndicatorCapacity,
    fallback: KlineIndicatorRendererRegistry = KlineIndicatorRendererRegistry.Default,
    onRendererError: (IndicatorDefinition, Throwable) -> Unit = { _, _ -> },
): KlineIndicatorPluginChartRuntime {
    // Compose keys must not retain an externally mutable iterable.
    val activeKeyList = activeKeys.toList()
    val currentOnRendererError = rememberUpdatedState(onRendererError)
    val runtime = remember(catalog, activeKeyList, subIndicatorCapacity, fallback) {
        catalog.createChartRuntime(
            activeKeys = activeKeyList,
            subIndicatorCapacity = subIndicatorCapacity,
            fallback = fallback,
            onRendererError = { definition, error -> currentOnRendererError.value(definition, error) },
        )
    }
    DisposableEffect(runtime) {
        onDispose(runtime::close)
    }
    return runtime
}
