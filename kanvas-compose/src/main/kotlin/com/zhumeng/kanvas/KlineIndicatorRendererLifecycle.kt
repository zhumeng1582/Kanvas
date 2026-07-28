/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.zhumeng.kanvas.core.IndicatorDefinition
import com.zhumeng.kanvas.core.IndicatorKey
import com.zhumeng.kanvas.core.IndicatorOutput
import com.zhumeng.kanvas.core.IndicatorRegistrySnapshot
import com.zhumeng.kanvas.core.KlineUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-Chart owner for [KlineStatefulIndicatorRenderer] instances.
 *
 * [reconcile] must be called from a Compose side effect with the authoritative
 * [IndicatorRegistrySnapshot]. It provides these lifecycle guarantees:
 * active declarations attach, hidden keep-alive declarations detach but retain
 * their instance, and all other removals dispose. It never invokes lifecycle
 * code from Canvas drawing.
 *
 * Lifecycle callbacks are serialized. A callback may safely request another
 * reconciliation or close this host: nested reconciliation is deferred until
 * the current pass is complete, and closing during a callback prevents the
 * partially-created instance from becoming drawable.
 */
class KlineIndicatorRendererLifecycleHost(
    private val rendererRegistry: KlineIndicatorRendererRegistry,
    private val onRendererError: (IndicatorDefinition, Throwable) -> Unit = { _, _ -> },
) : KlineIndicatorRendererResolver, AutoCloseable {
    private data class Entry(
        var definition: IndicatorDefinition,
        val factory: KlineStatefulIndicatorRendererFactory,
        val renderer: KlineStatefulIndicatorRenderer,
        var attached: Boolean,
    )

    private data class FailedFactory(
        val factory: KlineStatefulIndicatorRendererFactory,
        val definition: IndicatorDefinition,
    )

    private data class Desired(
        val definition: IndicatorDefinition,
        val active: Boolean,
    )

    private data class RegistryVersion(
        val token: Long,
        val mountEpoch: Long,
        val generation: Long,
    )

    private data class ReconcileRequest(
        val state: KlineUiState,
        val registry: IndicatorRegistrySnapshot?,
    )

    private data class CreationResult(
        val entry: Entry?,
        /** True only for a newly observed failure that needs one redraw. */
        val failureRecorded: Boolean,
    )

    private data class ResolutionError(
        val definition: IndicatorDefinition,
        val error: Throwable,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<IndicatorKey, Entry>()
    private val failures = mutableMapOf<IndicatorKey, FailedFactory>()
    private val pendingResolutionErrors = mutableListOf<ResolutionError>()
    private val mutableInvalidationEpoch = MutableStateFlow(0L)

    /** Increments after lifecycle reconciliation or when a renderer invokes `invalidate()`. */
    val invalidationEpoch: StateFlow<Long> = mutableInvalidationEpoch.asStateFlow()

    private var closed = false
    private var reconciling = false
    private var deferredReconciliation: ReconcileRequest? = null
    private var lastRegistryToken: Long? = null
    private var lastMountEpoch: Long? = null
    private var lastSpecEpoch: Long? = null
    /** Spec represented by [lastSpecEpoch], deliberately not every chart frame. */
    private var lastSpecEpochState: com.zhumeng.kanvas.core.KlineSpec? = null
    private var lastState: KlineUiState? = null
    private var lastRegistry: IndicatorRegistrySnapshot? = null
    /** Only entries reconciled to this exact registry version may be drawn. */
    private var readyRegistryVersion: RegistryVersion? = null
    /** Spec for which lifecycle callbacks have completed at [readyRegistryVersion]. */
    private var readyChartSpec: com.zhumeng.kanvas.core.KlineSpec? = null

    /**
     * Reconciles instances from the complete snapshot rather than its
     * best-effort event stream. Passing null tears down existing instances,
     * because there is no longer a lifecycle source that can keep them valid.
     */
    fun reconcile(
        state: KlineUiState,
        registry: IndicatorRegistrySnapshot?,
    ) {
        synchronized(lock) {
            if (closed) return
            if (reconciling) {
                // Plugin callbacks may re-enter. Keep only the newest complete
                // snapshot; all intermediate registry states are superseded.
                deferredReconciliation = ReconcileRequest(state, registry)
                return
            }

            reconciling = true
            var request: ReconcileRequest? = ReconcileRequest(state, registry)
            try {
                while (request != null && !closed) {
                    deferredReconciliation = null
                    reconcileLocked(request.state, request.registry)
                    request = deferredReconciliation
                }
            } finally {
                reconciling = false
                deferredReconciliation = null
            }
        }
    }

    private fun reconcileLocked(
        state: KlineUiState,
        registry: IndicatorRegistrySnapshot?,
    ) {
        if (closed) return
        if (registry == null) {
            val changed = disposeAllLocked(state, null, detach = true)
            // Null is a real lifecycle boundary, not merely a temporarily
            // absent frame. A prior failure must not poison a new registry.
            failures.clear()
            clearTrackingLocked()
            readyRegistryVersion = null
            if (changed) bumpInvalidationLocked()
            return
        }

        // StateFlow/Compose can deliver an already superseded snapshot. Never
        // regress instances to an earlier mount/generation on the same
        // registry token.
        if (isOlderThanLastRegistryLocked(registry)) return

        val changedRegistry = lastRegistryToken != null && lastRegistryToken != registry.registryToken
        val freshMount = !changedRegistry && lastMountEpoch != null && registry.mountEpoch > lastMountEpoch!!
        val firstRegistryObservation = lastRegistryToken == null || changedRegistry || freshMount
        var changed = false
        if (changedRegistry || freshMount) {
            readyRegistryVersion = null
            changed = disposeAllLocked(state, registry, detach = true) || changed
            failures.clear()
            if (closed) return
        }

        // New instances are initialized with the current spec and do not
        // receive a synthetic second callback. Keep the spec associated with
        // the *last processed spec epoch*, not merely the last chart frame:
        // Compose may deliver chart state B before the coordinator publishes
        // registry epoch B, and StateFlow can coalesce A -> B -> C.
        val specEpochAdvanced = !firstRegistryObservation &&
            lastRegistryToken == registry.registryToken &&
            lastMountEpoch == registry.mountEpoch &&
            lastSpecEpoch != null && registry.specEpoch > lastSpecEpoch!!
        val dispatchSpecChange = specEpochAdvanced && isSpecTransitionVisible(state)
        // A host may first reconcile before its controller selects a spec.
        // In that bootstrap case the registry event still carries the best
        // available old value; once a state has been observed, preserve that
        // tracked baseline so coalesced events remain A -> C rather than B -> C.
        val oldSpec = lastSpecEpochState ?: registry.latestSpecChangeOldSpec
        val preExistingKeys = entries.keys.toSet()
        val createdKeys = mutableSetOf<IndicatorKey>()

        val desired = LinkedHashMap<IndicatorKey, Desired>()
        registry.activeMainDefinitions().forEach { definition ->
            desired[definition.key] = Desired(definition = definition, active = true)
        }
        registry.activeSubDefinitions().forEach { definition ->
            desired[definition.key] = Desired(definition = definition, active = true)
        }
        registry.retainedDefinitions().forEach { definition ->
            desired[definition.key] = Desired(definition = definition, active = false)
        }

        entries.keys.toList().filterNot(desired::containsKey).forEach { key ->
            val entry = entries.remove(key) ?: return@forEach
            changed = disposeEntryLocked(entry, state, registry, detach = true) || changed
            if (closed) return
        }

        desired.forEach { (key, residency) ->
            if (closed) return
            val factory = resolveFactoryLocked(residency.definition)
            if (closed) return
            var entry = entries[key]
            if (factory == null) {
                if (entry != null) {
                    entries.remove(key)
                    changed = disposeEntryLocked(entry, state, registry, detach = true) || changed
                }
                return@forEach
            }

            val placementChanged = entry?.definition?.placement != residency.definition.placement
            val factoryChanged = entry != null && entry.factory !== factory
            if (entry != null && (placementChanged || factoryChanged)) {
                entries.remove(key)
                changed = disposeEntryLocked(entry, state, registry, detach = true) || changed
                if (closed) return
                entry = null
            }

            if (entry == null) {
                val created = createEntryLocked(residency.definition, factory, state, registry)
                changed = changed || created.failureRecorded
                entry = created.entry ?: return@forEach
                if (closed) return
                entries[key] = entry
                createdKeys += key
                changed = true
            } else if (definitionChanged(entry.definition, residency.definition)) {
                val previous = entry.definition
                val updated = safelyLocked(residency.definition) {
                    entry.renderer.onUpdate(
                        KlineIndicatorUpdateContext(
                            previous = previous,
                            current = residency.definition,
                            state = state,
                            registry = registry,
                            invalidate = ::invalidate,
                        ),
                    )
                }
                if (!updated) {
                    changed = faultEntryLocked(key, entry, residency.definition, state, registry) || changed
                    return@forEach
                }
                if (closed) return
                entry.definition = residency.definition
                failures.remove(key)
                changed = true
            }

            if (residency.active && !entry.attached) {
                val attached = safelyLocked(entry.definition) {
                    entry.renderer.onAttach(lifecycleContext(entry.definition, state, registry))
                }
                if (!attached) {
                    changed = faultEntryLocked(key, entry, entry.definition, state, registry) || changed
                    return@forEach
                }
                if (closed) return
                entry.attached = true
                changed = true
            } else if (!residency.active && entry.attached) {
                val detached = safelyLocked(entry.definition) {
                    entry.renderer.onDetach(lifecycleContext(entry.definition, state, registry))
                }
                if (!detached) {
                    changed = faultEntryLocked(key, entry, entry.definition, state, registry) || changed
                    return@forEach
                }
                if (closed) return
                entry.attached = false
                changed = true
            }
        }

        if (dispatchSpecChange) {
            entries.toMap().forEach { (key, entry) ->
                if (key !in preExistingKeys || key in createdKeys || key !in desired) return@forEach
                val notified = safelyLocked(entry.definition) {
                    entry.renderer.onSpecChanged(
                        KlineIndicatorSpecChangeContext(
                            oldSpec = oldSpec,
                            newSpec = state.spec,
                            definition = entry.definition,
                            state = state,
                            registry = registry,
                            invalidate = ::invalidate,
                        ),
                    )
                }
                if (!notified) {
                    changed = faultEntryLocked(key, entry, entry.definition, state, registry) || changed
                    return@forEach
                }
                if (closed) return
                changed = true
            }
        }

        if (closed) return
        lastRegistryToken = registry.registryToken
        lastMountEpoch = registry.mountEpoch
        // Only a pre-existing instance that actually survived this pass needs
        // the delayed dependency callback. Removed/replaced instances and new
        // ones are already disposed/initialized against the current state, so
        // an empty registry must not leave a later show permanently pending.
        val hasSurvivingPreExistingInstance = entries.keys.any { key ->
            key in preExistingKeys && key !in createdKeys
        }
        val waitingForSpecNotification = !firstRegistryObservation &&
            hasSurvivingPreExistingInstance &&
            ((specEpochAdvanced && !dispatchSpecChange) ||
                (!specEpochAdvanced && !sameSpecDependency(state.spec, lastSpecEpochState)))
        val acceptsCurrentSpecWithoutNotification =
            !waitingForSpecNotification && !sameSpecDependency(state.spec, lastSpecEpochState)
        if (firstRegistryObservation || dispatchSpecChange || acceptsCurrentSpecWithoutNotification) {
            lastSpecEpoch = registry.specEpoch
            lastSpecEpochState = state.spec
        }
        lastState = state
        lastRegistry = registry
        readyRegistryVersion = registry.version()
        readyChartSpec = if (waitingForSpecNotification) lastSpecEpochState else state.spec
        if (changed) bumpInvalidationLocked()
    }

    /**
     * Returns a stable per-key instance when one exists. Before the first
     * side-effect reconciliation, a matching stateful factory gets a no-op
     * placeholder so its pending Computed pane remains allocated for one frame.
     */
    override fun resolve(
        definition: IndicatorDefinition,
        output: IndicatorOutput?,
    ): KlineIndicatorRenderer? = synchronized(lock) {
        resolveLocked(definition, output, state = null, registry = lastRegistry)
    }

    override fun resolve(
        definition: IndicatorDefinition,
        output: IndicatorOutput?,
        registry: IndicatorRegistrySnapshot?,
    ): KlineIndicatorRenderer? = synchronized(lock) {
        resolveLocked(definition, output, state = null, registry = registry)
    }

    override fun resolve(
        definition: IndicatorDefinition,
        output: IndicatorOutput?,
        state: KlineUiState,
        registry: IndicatorRegistrySnapshot?,
    ): KlineIndicatorRenderer? = synchronized(lock) {
        resolveLocked(definition, output, state, registry)
    }

    private fun resolveLocked(
        definition: IndicatorDefinition,
        output: IndicatorOutput?,
        state: KlineUiState?,
        registry: IndicatorRegistrySnapshot?,
    ): KlineIndicatorRenderer? {
        if (closed) return resolveFallbackForComposition(definition, output)

        val expectedVersion = registry?.version()
        if (expectedVersion != null &&
            (expectedVersion != readyRegistryVersion ||
                (state != null && !sameSpecDependency(state.spec, readyChartSpec)))
        ) {
            // The composition belongs to a new registry state but SideEffect
            // has not yet run. Never hand Canvas an old instance that could be
            // disposed/replaced by that imminent reconciliation.
            return pendingOrFallbackLocked(definition, output)
        }

        entries[definition.key]
            ?.takeIf { entry -> !definitionChanged(entry.definition, definition) }
            ?.let { entry -> return entry.renderer }
        return pendingOrFallbackLocked(definition, output)
    }

    private fun pendingOrFallbackLocked(
        definition: IndicatorDefinition,
        output: IndicatorOutput?,
    ): KlineIndicatorRenderer? {
        // `resolve` can be called during composition. Factory matching must
        // therefore be pure/non-throwing; defer diagnostics to SideEffect's
        // reconcile path instead of invoking user callbacks while composing.
        val factory = resolveFactoryForComposition(definition)
        if (closed) return resolveFallbackForComposition(definition, output)
        val failure = failures[definition.key]
        return if (factory != null && !isCurrentFailure(failure, factory, definition)) {
            PendingStatefulIndicatorRenderer
        } else {
            resolveFallbackForComposition(definition, output)
        }
    }

    /** Closing disposes instances without requiring a separate detach callback. */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            deferredReconciliation = null
            // Clear before invoking plugin code so a reentrant close/dispose
            // cannot recursively dispose the same instance.
            disposeAllLocked(lastState, lastRegistry, detach = false)
            failures.clear()
            clearTrackingLocked()
            readyRegistryVersion = null
            // Manual close can happen while a Chart remains composed; wake it
            // so the resolver falls back to a stateless renderer or null
            // instead of retaining a no-op pending pane.
            bumpInvalidationLocked()
        }
    }

    /**
     * Delivers matcher failures captured while composition was resolving a
     * fallback renderer. [KanvasChart] calls this from SideEffect so a host
     * diagnostics callback never writes Compose state during composition.
     */
    internal fun dispatchPendingResolutionErrors() {
        val errors = synchronized(lock) {
            val captured = pendingResolutionErrors.toList()
            pendingResolutionErrors.clear()
            captured
        }
        errors.forEach { (definition, error) -> reportError(definition, error) }
    }

    private fun createEntryLocked(
        definition: IndicatorDefinition,
        factory: KlineStatefulIndicatorRendererFactory,
        state: KlineUiState,
        registry: IndicatorRegistrySnapshot,
    ): CreationResult {
        if (closed) return CreationResult(entry = null, failureRecorded = false)
        val previousFailure = failures[definition.key]
        if (isCurrentFailure(previousFailure, factory, definition)) {
            return CreationResult(entry = null, failureRecorded = false)
        }
        val renderer = try {
            factory.create(definition)
        } catch (error: Throwable) {
            if (closed) return CreationResult(entry = null, failureRecorded = false)
            failures[definition.key] = FailedFactory(factory, definition)
            reportErrorLocked(definition, error)
            return CreationResult(entry = null, failureRecorded = !closed)
        }
        if (closed) {
            disposeUnattachedRendererLocked(renderer, definition, state, registry)
            return CreationResult(entry = null, failureRecorded = false)
        }

        val entry = Entry(definition = definition, factory = factory, renderer = renderer, attached = false)
        val initialized = safelyLocked(definition) {
            renderer.onInit(lifecycleContext(definition, state, registry))
        }
        if (!initialized || closed) {
            disposeUnattachedRendererLocked(renderer, definition, state, registry)
            if (!closed) failures[definition.key] = FailedFactory(factory, definition)
            return CreationResult(entry = null, failureRecorded = !closed)
        }

        failures.remove(definition.key)
        return CreationResult(entry = entry, failureRecorded = false)
    }

    private fun disposeUnattachedRendererLocked(
        renderer: KlineStatefulIndicatorRenderer,
        definition: IndicatorDefinition,
        state: KlineUiState,
        registry: IndicatorRegistrySnapshot,
    ) {
        safelyLocked(definition) {
            renderer.onDispose(disposeContext(definition, state, registry))
        }
    }

    /**
     * A lifecycle callback that fails leaves plugin-owned state unknowable.
     * Drop the entry before calling dispose so a reentrant close cannot dispose
     * it twice, then cache the failure until the declaration/factory changes.
     */
    private fun faultEntryLocked(
        key: IndicatorKey,
        entry: Entry,
        failureDefinition: IndicatorDefinition,
        state: KlineUiState,
        registry: IndicatorRegistrySnapshot,
    ): Boolean {
        if (closed || entries.remove(key) !== entry) return false
        failures[key] = FailedFactory(entry.factory, failureDefinition)
        safelyLocked(entry.definition) {
            entry.renderer.onDispose(disposeContext(entry.definition, state, registry))
        }
        return true
    }

    private fun disposeAllLocked(
        state: KlineUiState?,
        registry: IndicatorRegistrySnapshot?,
        detach: Boolean,
    ): Boolean {
        if (entries.isEmpty()) return false
        val toDispose = entries.values.toList()
        entries.clear()
        toDispose.forEach { entry ->
            disposeEntryLocked(entry, state, registry, detach)
        }
        return true
    }

    private fun disposeEntryLocked(
        entry: Entry,
        state: KlineUiState?,
        registry: IndicatorRegistrySnapshot?,
        detach: Boolean,
    ): Boolean {
        if (detach && entry.attached && state != null && registry != null) {
            entry.attached = false
            safelyLocked(entry.definition) {
                entry.renderer.onDetach(lifecycleContext(entry.definition, state, registry))
            }
        }
        safelyLocked(entry.definition) {
            entry.renderer.onDispose(disposeContext(entry.definition, state, registry))
        }
        return true
    }

    private fun lifecycleContext(
        definition: IndicatorDefinition,
        state: KlineUiState,
        registry: IndicatorRegistrySnapshot,
    ): KlineIndicatorLifecycleContext = KlineIndicatorLifecycleContext(
        definition = definition,
        state = state,
        registry = registry,
        invalidate = ::invalidate,
    )

    private fun disposeContext(
        definition: IndicatorDefinition,
        state: KlineUiState?,
        registry: IndicatorRegistrySnapshot?,
    ): KlineIndicatorDisposeContext = KlineIndicatorDisposeContext(
        definition = definition,
        state = state,
        registry = registry,
        invalidate = ::invalidate,
    )

    private fun resolveFactoryLocked(definition: IndicatorDefinition): KlineStatefulIndicatorRendererFactory? = try {
        rendererRegistry.resolveStatefulFactory(definition)
    } catch (error: Throwable) {
        reportErrorLocked(definition, error)
        null
    }

    private fun resolveFactoryForComposition(
        definition: IndicatorDefinition,
    ): KlineStatefulIndicatorRendererFactory? = try {
        rendererRegistry.resolveStatefulFactory(definition)
    } catch (_: Throwable) {
        null
    }

    private fun resolveFallbackForComposition(
        definition: IndicatorDefinition,
        output: IndicatorOutput?,
    ): KlineIndicatorRenderer? = try {
        rendererRegistry.resolve(definition, output)
    } catch (error: Throwable) {
        pendingResolutionErrors += ResolutionError(definition, error)
        null
    }

    private fun safelyLocked(definition: IndicatorDefinition, action: () -> Unit): Boolean = try {
        action()
        true
    } catch (error: Throwable) {
        reportErrorLocked(definition, error)
        false
    }

    private fun reportErrorLocked(definition: IndicatorDefinition, error: Throwable) {
        reportError(definition, error)
    }

    private fun reportError(definition: IndicatorDefinition, error: Throwable) {
        try {
            onRendererError(definition, error)
        } catch (_: Throwable) {
            // A diagnostics callback must not take down the chart lifecycle.
        }
    }

    private fun invalidate() {
        synchronized(lock) {
            if (!closed) bumpInvalidationLocked()
        }
    }

    private fun bumpInvalidationLocked() {
        mutableInvalidationEpoch.value = mutableInvalidationEpoch.value + 1L
    }

    private fun isOlderThanLastRegistryLocked(registry: IndicatorRegistrySnapshot): Boolean {
        if (lastRegistryToken != registry.registryToken) return false
        val previousMountEpoch = lastMountEpoch ?: return false
        if (registry.mountEpoch != previousMountEpoch) return registry.mountEpoch < previousMountEpoch
        val previous = lastRegistry ?: return false
        return registry.generation < previous.generation ||
            (registry.generation == previous.generation && registry.specEpoch < previous.specEpoch)
    }

    private fun isCurrentFailure(
        failure: FailedFactory?,
        factory: KlineStatefulIndicatorRendererFactory,
        definition: IndicatorDefinition,
    ): Boolean = failure?.factory === factory && !definitionChanged(failure.definition, definition)

    /** A new dependency epoch is not dispatchable until its new chart spec is visible. */
    private fun isSpecTransitionVisible(state: KlineUiState): Boolean =
        !sameSpecDependency(state.spec, lastSpecEpochState)

    /** Dependency transitions follow symbol/interval keys, not presentation-only spec fields. */
    private fun sameSpecDependency(
        first: com.zhumeng.kanvas.core.KlineSpec?,
        second: com.zhumeng.kanvas.core.KlineSpec?,
    ): Boolean = first?.key == second?.key

    private fun IndicatorRegistrySnapshot.version(): RegistryVersion = RegistryVersion(
        token = registryToken,
        mountEpoch = mountEpoch,
        generation = generation,
    )

    private fun clearTrackingLocked() {
        lastRegistryToken = null
        lastMountEpoch = null
        lastSpecEpoch = null
        lastSpecEpochState = null
        lastState = null
        lastRegistry = null
        readyChartSpec = null
    }

    private fun definitionChanged(previous: IndicatorDefinition, current: IndicatorDefinition): Boolean =
        previous != current || previous.key.label != current.key.label

    private object PendingStatefulIndicatorRenderer : KlineIndicatorRenderer {
        override fun supports(definition: IndicatorDefinition, output: IndicatorOutput?): Boolean = false

        override fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope, context: KlineIndicatorDrawContext) = Unit
    }
}

/**
 * Creates a host owned by this composition and disposes it exactly once when
 * the Chart leaves composition or the renderer registry is replaced.
 */
@Composable
fun rememberKlineIndicatorRendererLifecycleHost(
    rendererRegistry: KlineIndicatorRendererRegistry,
    onRendererError: (IndicatorDefinition, Throwable) -> Unit = { _, _ -> },
): KlineIndicatorRendererLifecycleHost {
    val currentOnRendererError = rememberUpdatedState(onRendererError)
    val host = remember(rendererRegistry) {
        KlineIndicatorRendererLifecycleHost(rendererRegistry) { definition, error ->
            currentOnRendererError.value(definition, error)
        }
    }
    DisposableEffect(host) {
        onDispose(host::close)
    }
    return host
}

/** Reads the host invalidation signal without making callers manage a Flow collector. */
@Composable
internal fun KlineIndicatorRendererLifecycleHost?.observeInvalidationEpoch(): Long {
    if (this == null) return 0L
    return invalidationEpoch.collectAsState().value
}
