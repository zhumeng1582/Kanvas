/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Keeps CPU-heavy indicator work behind gesture and render threads. */
private object IndicatorCalculationDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        Dispatchers.Default.dispatch(context) {
            val thread = Thread.currentThread()
            val previousPriority = thread.priority
            try {
                // Android maps Java thread priorities onto scheduler niceness.
                // Failure is harmless on a restricted host/JVM.
                runCatching { thread.priority = Thread.MIN_PRIORITY }
                block.run()
            } finally {
                runCatching { thread.priority = previousPriority }
            }
        }
    }
}

/** Controls when an open newest candle is allowed to change indicator output. */
enum class KlineIndicatorRefreshPolicy {
    /** Recalculate after every candle revision, including every realtime tick. */
    EveryTick,

    /** Keep indicator values stable while replacing the open candle; recalculate when a new candle is inserted. */
    OnCandleBoundary,
}

/**
 * Binds [KlineController] candle revisions to [IndicatorRegistry] selection.
 *
 * Without this coordinator, a host would have to invoke
 * [IndicatorRuntime.calculate] as candle and registry snapshots change. This
 * coordinator makes the relationship explicit:
 *
 * - only active registry definitions are calculated;
 * - calculations run on [calculationDispatcher];
 * - [refreshPolicy] may keep output stable across open-candle ticks;
 * - a late result is checked against the controller revision and registry
 *   token/generation before publication, then tagged with that captured input
 *   so a renderer can reject a race that occurs immediately afterwards; and
 * - a symbol/interval key switch automatically broadcasts
 *   [IndicatorRegistry.notifySpecChanged] before recalculation.
 *
 * The caller owns [scope] and should call [close] when this binding is no
 * longer needed. Create at most one coordinator for one controller/registry
 * pair: two coordinators would both broadcast the same spec transition.
 *
 * The initial value of [state] is null while the first result is pending;
 * later calculations retain the last successful snapshot until replacement.
 * renderers must therefore retain active pane geometry without a computed
 * output. [IndicatorCalculator] is synchronous host code: it should finish
 * promptly and honour coroutine interruption where applicable. A calculator
 * that blocks indefinitely cannot be forcibly stopped by `collectLatest`.
 */
class IndicatorRuntimeCoordinator(
    private val controller: KlineController,
    private val registry: IndicatorRegistry,
    private val scope: CoroutineScope,
    private val runtime: IndicatorRuntime = IndicatorRuntime(),
    private val computeMode: KlineComputeMode = KlineComputeMode.Fast,
    private val refreshPolicy: KlineIndicatorRefreshPolicy = KlineIndicatorRefreshPolicy.EveryTick,
    private val calculationDispatcher: CoroutineDispatcher = IndicatorCalculationDispatcher,
    private val onCalculationError: (Throwable) -> Unit = {},
) : AutoCloseable {
    private data class Input(
        val chart: KlineUiState,
        val registry: IndicatorRegistrySnapshot,
        val refreshEpoch: Long,
    )

    private val mutableState = MutableStateFlow<IndicatorRuntimeSnapshot?>(null)
    private val mutableError = MutableStateFlow<Throwable?>(null)
    private val refreshEpoch = MutableStateFlow(0L)
    private val resultLock = Any()
    private var previousResult: IndicatorRuntimeSnapshot? = null

    /** The latest successfully calculated/rebased snapshot, or null before first success/after an error. */
    val state: StateFlow<IndicatorRuntimeSnapshot?> = mutableState.asStateFlow()

    /** Latest calculation failure, cleared when a new calculation begins or succeeds. */
    val error: StateFlow<Throwable?> = mutableError.asStateFlow()

    /** Source-compatible descriptive alias for integrations that prefer `snapshot`. */
    val snapshot: StateFlow<IndicatorRuntimeSnapshot?> get() = state

    private val job: Job

    init {
        job = scope.launch {
            // Starting after a controller has already selected its first spec
            // is not a dependency *change*. A subsequent key transition is.
            var observedSpec: KlineSpec? = controller.state.value.spec
            var observedRefreshEpoch = refreshEpoch.value

            combine(controller.state, registry.state, refreshEpoch) { chart, registrySnapshot, refresh ->
                Input(chart = chart, registry = registrySnapshot, refreshEpoch = refresh)
            }.distinctUntilChanged { previous, next ->
                previous.chart.revision == next.chart.revision &&
                    previous.registry.registryToken == next.registry.registryToken &&
                    previous.registry.generation == next.registry.generation &&
                    previous.refreshEpoch == next.refreshEpoch
            }.collectLatest { input ->
                mutableError.value = null
                val forceRefresh = input.refreshEpoch != observedRefreshEpoch
                observedRefreshEpoch = input.refreshEpoch
                val specChanged = input.chart.spec?.key != observedSpec?.key
                if (specChanged) {
                    val oldSpec = observedSpec
                    observedSpec = input.chart.spec
                    // Registry state is authoritative. When an active or
                    // retained object exists, notify emits a newer snapshot;
                    // wait for that reconciliation before calculating.
                    if (registry.notifySpecChanged(oldSpec).isNotEmpty()) return@collectLatest
                }

                // The input registry may have been superseded synchronously
                // by notifySpecChanged above, so capture the current truth.
                val capturedRegistry = registry.snapshot()
                val capturedRevision = input.chart.revision
                try {
                    // A generation change can affect only one declaration.
                    // Keep the prior snapshot from this registry available so
                    // IndicatorRuntime can reuse every unchanged definition.
                    // Publication still requires the exact new generation.
                    val previousForCalculation = synchronized(resultLock) {
                        previousResult?.takeIf { previous -> previous.hasRegistryIdentity(capturedRegistry) }
                    }
                    // A prepared historical page may already have published
                    // the exact result before this StateFlow emission is
                    // collected. Do not calculate the expanded series twice.
                    if (!forceRefresh &&
                        previousForCalculation?.matches(capturedRegistry) == true &&
                        previousForCalculation.sourceRevision == capturedRevision &&
                        previousForCalculation.series.candles == input.chart.series.candles
                    ) {
                        return@collectLatest
                    }
                    if (!forceRefresh &&
                        refreshPolicy == KlineIndicatorRefreshPolicy.OnCandleBoundary &&
                        previousForCalculation != null &&
                        previousForCalculation.matches(capturedRegistry) &&
                        input.chart.series.differsOnlyAtLatestFrom(previousForCalculation.series)
                    ) {
                        val rebased = previousForCalculation.rebaseSeries(
                            series = input.chart.series,
                            sourceRevision = capturedRevision,
                        )
                        synchronized(resultLock) {
                            previousResult = rebased
                            mutableState.value = rebased
                        }
                        return@collectLatest
                    }
                    val calculated = withContext(calculationDispatcher) {
                        runtime.calculate(
                            previous = previousForCalculation,
                            series = input.chart.series,
                            registry = capturedRegistry,
                            computeMode = computeMode,
                        )
                    }.withSourceRevision(capturedRevision)

                    // Calculators are arbitrary host code and may not
                    // cooperate with cancellation. Validate after returning
                    // to the binding before allowing a stale result through.
                    if (controller.state.value.revision != capturedRevision) return@collectLatest
                    if (!calculated.matches(registry.snapshot())) return@collectLatest

                    synchronized(resultLock) {
                        previousResult = calculated
                        mutableState.value = calculated
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    mutableState.value = null
                    mutableError.value = error
                    try {
                        onCalculationError(error)
                    } catch (_: Throwable) {
                        // Diagnostics must not terminate the binding job.
                    }
                }
            }
        }
    }

    /** Re-runs the current controller/registry input after a transient calculation failure. */
    fun retry() {
        refreshEpoch.update { current -> current + 1L }
    }

    /**
     * Calculates a complete indicator snapshot before [series] is published by
     * the controller. Historical pagination can use this to avoid exposing a
     * new candle page for one or more frames before its indicator geometry is
     * ready.
     *
     * The returned snapshot is not published automatically. After committing
     * the matching controller state, call [publishPrepared] immediately.
     */
    suspend fun calculateAhead(
        series: KlineSeries,
        sourceRevision: Long,
    ): IndicatorRuntimeSnapshot {
        val capturedRegistry = registry.snapshot()
        val previous = synchronized(resultLock) {
            previousResult?.takeIf { result -> result.hasRegistryIdentity(capturedRegistry) }
        }
        return withContext(calculationDispatcher) {
            runtime.calculate(
                previous = previous,
                series = series,
                registry = capturedRegistry,
                computeMode = computeMode,
            )
        }.withSourceRevision(sourceRevision)
    }

    /**
     * Publishes an ahead-of-controller calculation only when both its candle
     * input and registry generation now match the current chart state.
     */
    fun publishPrepared(snapshot: IndicatorRuntimeSnapshot): Boolean {
        if (!snapshot.matches(controller.state.value, registry.snapshot())) return false
        synchronized(resultLock) {
            previousResult = snapshot
            mutableError.value = null
            mutableState.value = snapshot
        }
        return true
    }

    /** Stops observation; calculation jobs that honour cancellation are cancelled as well. */
    override fun close() {
        job.cancel()
    }
}
