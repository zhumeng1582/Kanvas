/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas.core

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Immutable, renderer-neutral view of registered indicator declarations.
 *
 * `active` means the indicator participates in the current render tree.
 * `retained` means it was hidden while `keepAlive=true`: it is not
 * rendered or recalculated during regular candle updates, but an Android
 * renderer may keep its own business state until it is shown again.
 */
class IndicatorRegistrySnapshot internal constructor(
    /** Stable process-local identity; prevents two registries at generation 1 from matching. */
    val registryToken: Long,
    val generation: Long,
    /** Increments on a fresh [IndicatorRegistry.mount], even when keys/configuration repeat. */
    val mountEpoch: Long,
    /** Increments only when [IndicatorRegistry.notifySpecChanged] broadcasts a dependency change. */
    val specEpoch: Long,
    /** The old spec associated with the current [specEpoch]. Observe the epoch before using this value. */
    val latestSpecChangeOldSpec: KlineSpec?,
    registered: List<IndicatorDefinition>,
    activeMainKeys: List<IndicatorKey>,
    activeSubKeys: List<IndicatorKey>,
    retainedKeys: List<IndicatorKey>,
) {
    private val registeredStorage = registered.toList()
    private val definitionsByKey = registeredStorage.associateBy(IndicatorDefinition::key)
    private val activeMainStorage = activeMainKeys.toList()
    private val activeSubStorage = activeSubKeys.toList()
    private val retainedStorage = retainedKeys.toList()
    private val activeKeys = (activeMainStorage + activeSubStorage).toSet()

    init {
        require(definitionsByKey.size == registeredStorage.size) {
            "Indicator registry snapshot contains duplicate declaration keys"
        }
        require((activeMainStorage + activeSubStorage).distinct().size == activeMainStorage.size + activeSubStorage.size) {
            "Indicator registry snapshot contains duplicate active keys"
        }
        require(activeKeys.all(definitionsByKey::containsKey)) {
            "Indicator registry snapshot activates an unregistered key"
        }
        require(retainedStorage.distinct().size == retainedStorage.size) {
            "Indicator registry snapshot contains duplicate retained keys"
        }
        require(retainedStorage.all(definitionsByKey::containsKey)) {
            "Indicator registry snapshot retains an unregistered key"
        }
        require(retainedStorage.none(activeKeys::contains)) {
            "An indicator cannot be active and retained at the same time"
        }
        require(activeMainStorage.all { definitionsByKey.getValue(it).placement is IndicatorPlacement.Main }) {
            "Main active keys must have main placement"
        }
        require(activeSubStorage.all { definitionsByKey.getValue(it).placement is IndicatorPlacement.Sub }) {
            "Sub active keys must have sub placement"
        }
    }

    /** All declarations in registration/declaration order. */
    fun registeredDefinitions(): List<IndicatorDefinition> = registeredStorage.toList()

    fun definition(key: IndicatorKey): IndicatorDefinition? = definitionsByKey[key]

    fun isRegistered(key: IndicatorKey): Boolean = key in definitionsByKey

    fun isActive(key: IndicatorKey): Boolean = key in activeKeys

    fun isRetained(key: IndicatorKey): Boolean = key in retainedStorage

    /** Active main declarations in registration order; renderers sort z-index separately. */
    fun activeMainDefinitions(): List<IndicatorDefinition> =
        registeredStorage.filter { it.key in activeMainStorage }

    /** Active sub declarations in FIFO activation order. */
    fun activeSubDefinitions(): List<IndicatorDefinition> =
        activeSubStorage.map(definitionsByKey::getValue)

    /** Active declarations in registration order, suitable for deterministic calculations. */
    fun activeDefinitions(): List<IndicatorDefinition> =
        registeredStorage.filter { it.key in activeKeys }

    /** Hidden keep-alive declarations in the order they were hidden or evicted. */
    fun retainedDefinitions(): List<IndicatorDefinition> =
        retainedStorage.map(definitionsByKey::getValue)

    fun activeMainKeys(): List<IndicatorKey> = activeMainStorage.toList()

    fun activeSubKeys(): List<IndicatorKey> = activeSubStorage.toList()

    fun retainedKeys(): List<IndicatorKey> = retainedStorage.toList()

    /** Only active declarations participate in regular calculations. */
    fun calculationDefinitions(): List<IndicatorDefinition> = activeDefinitions()
}

/** One lifecycle notification emitted by [IndicatorRegistry]. */
sealed interface IndicatorRegistryEvent {
    val generation: Long

    data class Registered(
        val definition: IndicatorDefinition,
        override val generation: Long,
    ) : IndicatorRegistryEvent

    data class Updated(
        val previous: IndicatorDefinition,
        val definition: IndicatorDefinition,
        override val generation: Long,
    ) : IndicatorRegistryEvent

    data class Activated(
        val key: IndicatorKey,
        override val generation: Long,
    ) : IndicatorRegistryEvent

    data class Hidden(
        val key: IndicatorKey,
        val retained: Boolean,
        override val generation: Long,
    ) : IndicatorRegistryEvent

    data class Evicted(
        val key: IndicatorKey,
        val retained: Boolean,
        override val generation: Long,
    ) : IndicatorRegistryEvent

    data class Unregistered(
        val key: IndicatorKey,
        override val generation: Long,
    ) : IndicatorRegistryEvent

    /** Notifies both active and retained declarations of a chart-spec change. */
    data class SpecChanged(
        val keys: List<IndicatorKey>,
        /** The previous chart spec supplied to renderer lifecycle callbacks. */
        val oldSpec: KlineSpec?,
        override val generation: Long,
    ) : IndicatorRegistryEvent
}

/**
 * Thread-safe, renderer-neutral declaration and lifecycle registry.
 *
 * - Initial activation is persisted selection union `autoActivate`.
 * - `autoActivate` only shows an indicator; true -> false never hides it.
 * - A hidden `keepAlive` declaration becomes retained, never active.
 * - Normal candle calculations use only [IndicatorRegistrySnapshot.activeDefinitions].
 * - Sub indicators use a default four-entry FIFO queue; Time remains
 *   a system pane outside this registry.
 */
class IndicatorRegistry(
    private val subIndicatorCapacity: Int = DefaultSubIndicatorCapacity,
) {
    init {
        require(subIndicatorCapacity >= 0) { "Sub indicator capacity must not be negative" }
    }

    private val lock = Any()
    private val registryToken = nextRegistryToken.getAndIncrement()
    private val registrations = LinkedHashMap<IndicatorKey, IndicatorDefinition>()
    private val activeMain = LinkedHashSet<IndicatorKey>()
    private val activeSub = mutableListOf<IndicatorKey>()
    private val retained = LinkedHashSet<IndicatorKey>()
    private var generation = 0L
    private var mountEpoch = 0L
    private var specEpoch = 0L
    private var latestSpecChangeOldSpec: KlineSpec? = null

    private val mutableState = MutableStateFlow(snapshotLocked())
    private val mutableEvents = MutableSharedFlow<IndicatorRegistryEvent>(extraBufferCapacity = 32)

    /** Authoritative lifecycle state; renderers should reconcile against this snapshot. */
    val state: StateFlow<IndicatorRegistrySnapshot> = mutableState.asStateFlow()
    /**
     * Best-effort lifecycle hints. This flow has no replay and may drop events
     * for a slow/nonexistent collector; use [state] as the source of truth.
     */
    val events: SharedFlow<IndicatorRegistryEvent> = mutableEvents.asSharedFlow()

    fun snapshot(): IndicatorRegistrySnapshot = synchronized(lock) { mutableState.value }

    /**
     * Replaces all declarations as a fresh registry mount.
     *
     * [restoredActiveKeys] is applied first, in persisted order, followed by
     * newly declared `autoActivate` entries in declaration order. Unknown
     * persisted keys are ignored because no renderer or calculator was
     * registered for them.
     */
    fun mount(
        definitions: Iterable<IndicatorDefinition>,
        restoredActiveKeys: Iterable<IndicatorKey> = emptyList(),
    ): IndicatorRegistrySnapshot = synchronized(lock) {
        val definitionList = definitions.toList()
        require(definitionList.map(IndicatorDefinition::key).distinct().size == definitionList.size) {
            "Indicator declarations must not contain duplicate keys"
        }
        val oldKeys = registrations.values.map { definition -> definition.key }
        registrations.clear()
        definitionList.forEach { definition -> registrations[definition.key] = definition }
        activeMain.clear()
        activeSub.clear()
        retained.clear()
        mountEpoch += 1

        val requested = LinkedHashSet<IndicatorKey>()
        restoredActiveKeys.forEach(requested::add)
        definitionList.asSequence().filter(IndicatorDefinition::autoActivate).mapTo(requested, IndicatorDefinition::key)
        val events = mutableListOf<(Long) -> IndicatorRegistryEvent>()
        requested.forEach { key -> activateLocked(key, events) }

        publishLocked(
            buildList<(Long) -> IndicatorRegistryEvent> {
                oldKeys.forEach { key -> add { nextGeneration -> IndicatorRegistryEvent.Unregistered(key, nextGeneration) } }
                definitionList.forEach { definition ->
                    add { nextGeneration -> IndicatorRegistryEvent.Registered(definition, nextGeneration) }
                }
                addAll(events)
            },
            forceSnapshot = true,
        )
    }

    /** Registers a new declaration. Duplicate keys are rejected; use [upsert] for a rebuild. */
    fun register(definition: IndicatorDefinition): IndicatorRegistrySnapshot = synchronized(lock) {
        require(definition.key !in registrations) {
            "Indicator '${definition.key.id}' is already registered; use upsert to replace it"
        }
        registrations[definition.key] = definition
        val events = mutableListOf<(Long) -> IndicatorRegistryEvent>()
        events += { nextGeneration -> IndicatorRegistryEvent.Registered(definition, nextGeneration) }
        if (definition.autoActivate) activateLocked(definition.key, events)
        publishLocked(events)
    }

    /**
     * Inserts or updates a declaration while preserving manual hidden state.
     * A false -> true auto-activation transition shows a currently hidden
     * declaration; true -> false deliberately does not hide it.
     */
    fun upsert(definition: IndicatorDefinition): IndicatorRegistrySnapshot = synchronized(lock) {
        val previous = registrations[definition.key]
        if (previous == null) return@synchronized registerLocked(definition)
        if (previous == definition && previous.key.label == definition.key.label) return@synchronized mutableState.value

        val events = mutableListOf<(Long) -> IndicatorRegistryEvent>()
        registrations[definition.key] = definition
        events += { nextGeneration -> IndicatorRegistryEvent.Updated(previous, definition, nextGeneration) }
        canonicalizeResidencyKeyLocked(previous.key, definition.key)

        val placementChanged = previous.placement != definition.placement
        val wasActive = definition.key in activeMain || definition.key in activeSub
        if (placementChanged) {
            // Treat a main <-> sub move as removal from one placement and a
            // new declaration in the other. Detach the old renderer rather
            // than silently moving its state between pane types.
            activeMain.remove(definition.key)
            activeSub.remove(definition.key)
            retained.remove(definition.key)
            if (wasActive) {
                events += { nextGeneration -> IndicatorRegistryEvent.Hidden(definition.key, retained = false, nextGeneration) }
            }
            if (definition.autoActivate) activateLocked(definition.key, events)
        } else {
            if (definition.key in retained && !definition.keepAlive) retained.remove(definition.key)
            if (!previous.autoActivate && definition.autoActivate && !wasActive) {
                activateLocked(definition.key, events)
            }
        }
        publishLocked(events)
    }

    /** Shows a registered indicator. Returns the unchanged snapshot for no-op/unknown keys. */
    fun show(key: IndicatorKey): IndicatorRegistrySnapshot = synchronized(lock) {
        val events = mutableListOf<(Long) -> IndicatorRegistryEvent>()
        if (!activateLocked(key, events)) return@synchronized mutableState.value
        publishLocked(events)
    }

    /** Hides an active indicator, retaining it only when its declaration requests it. */
    fun hide(key: IndicatorKey): IndicatorRegistrySnapshot = synchronized(lock) {
        val definition = registrations[key] ?: return@synchronized mutableState.value
        val canonicalKey = definition.key
        val removed = activeMain.remove(canonicalKey) || activeSub.remove(canonicalKey)
        if (!removed) return@synchronized mutableState.value

        val keep = definition.keepAlive
        if (keep) retained.add(canonicalKey) else retained.remove(canonicalKey)
        publishLocked(
            listOf { nextGeneration -> IndicatorRegistryEvent.Hidden(canonicalKey, keep, nextGeneration) },
        )
    }

    /**
     * Moves one active sub indicator to [index] without hiding or recreating it.
     * The active-sub order is also the default physical pane order.
     */
    fun moveActiveSub(key: IndicatorKey, index: Int): IndicatorRegistrySnapshot = synchronized(lock) {
        require(index >= 0) { "Sub indicator index must not be negative" }
        val definition = registrations[key] ?: return@synchronized mutableState.value
        val canonicalKey = definition.key
        val oldIndex = activeSub.indexOf(canonicalKey)
        if (oldIndex < 0) return@synchronized mutableState.value
        val targetIndex = index.coerceAtMost(activeSub.lastIndex)
        if (oldIndex == targetIndex) return@synchronized mutableState.value
        activeSub.removeAt(oldIndex)
        activeSub.add(targetIndex, canonicalKey)
        publishLocked(emptyList(), forceSnapshot = true)
    }

    /** Removes the declaration and forcibly discards active or retained state. */
    fun unregister(key: IndicatorKey): IndicatorRegistrySnapshot = synchronized(lock) {
        val definition = registrations.remove(key) ?: return@synchronized mutableState.value
        val canonicalKey = definition.key
        activeMain.remove(canonicalKey)
        activeSub.remove(canonicalKey)
        retained.remove(canonicalKey)
        publishLocked(
            listOf { nextGeneration -> IndicatorRegistryEvent.Unregistered(canonicalKey, nextGeneration) },
        )
    }

    /** Force-disposes all declaration residency. */
    fun dispose(): IndicatorRegistrySnapshot = synchronized(lock) {
        if (registrations.isEmpty()) return@synchronized mutableState.value
        val removedKeys = registrations.values.map { definition -> definition.key }
        registrations.clear()
        activeMain.clear()
        activeSub.clear()
        retained.clear()
        publishLocked(
            removedKeys.map { key ->
                { nextGeneration: Long -> IndicatorRegistryEvent.Unregistered(key, nextGeneration) }
            },
        )
    }

    /** Restores persisted active selections without resetting already active declarations. */
    fun restoreActive(keys: Iterable<IndicatorKey>): IndicatorRegistrySnapshot = synchronized(lock) {
        val events = mutableListOf<(Long) -> IndicatorRegistryEvent>()
        keys.forEach { key -> activateLocked(key, events) }
        if (events.isEmpty()) mutableState.value else publishLocked(events)
    }

    /**
     * Emits the exact active + retained keys that must receive a chart-spec
     * lifecycle notification. A spec switch also
     * invalidates calculated output from the previous registry generation,
     * preventing an old asynchronous result from being rendered for the new
     * symbol/interval.
     */
    fun notifySpecChanged(oldSpec: KlineSpec? = null): List<IndicatorKey> = synchronized(lock) {
        val targets = LinkedHashSet<IndicatorKey>()
        targets += activeMain
        targets += activeSub
        targets += retained
        val values = targets.toList()
        if (values.isNotEmpty()) {
            generation += 1
            specEpoch += 1
            latestSpecChangeOldSpec = oldSpec
            mutableState.value = snapshotLocked()
            mutableEvents.tryEmit(IndicatorRegistryEvent.SpecChanged(values, oldSpec, generation))
        }
        values
    }

    private fun registerLocked(definition: IndicatorDefinition): IndicatorRegistrySnapshot {
        registrations[definition.key] = definition
        val events = mutableListOf<(Long) -> IndicatorRegistryEvent>()
        events += { nextGeneration -> IndicatorRegistryEvent.Registered(definition, nextGeneration) }
        if (definition.autoActivate) activateLocked(definition.key, events)
        return publishLocked(events)
    }

    private fun activateLocked(
        key: IndicatorKey,
        events: MutableList<(Long) -> IndicatorRegistryEvent>,
    ): Boolean {
        val definition = registrations[key] ?: return false
        val canonicalKey = definition.key
        if (canonicalKey in activeMain || canonicalKey in activeSub) return false
        if (definition.placement is IndicatorPlacement.Sub && subIndicatorCapacity == 0) return false
        retained.remove(canonicalKey)
        when (definition.placement) {
            IndicatorPlacement.Main -> activeMain += canonicalKey
            is IndicatorPlacement.Sub -> {
                if (activeSub.size >= subIndicatorCapacity) {
                    val evicted = activeSub.removeAt(0)
                    val retainEvicted = registrations.getValue(evicted).keepAlive
                    if (retainEvicted) retained += evicted else retained.remove(evicted)
                    events += { nextGeneration -> IndicatorRegistryEvent.Evicted(evicted, retainEvicted, nextGeneration) }
                }
                activeSub += canonicalKey
            }
        }
        events += { nextGeneration -> IndicatorRegistryEvent.Activated(canonicalKey, nextGeneration) }
        return true
    }

    /**
     * IndicatorKey equality intentionally ignores labels. Residency still
         * stores the latest declaration key so callers never receive a stale
         * display label after a plugin renames an indicator.
     */
    private fun canonicalizeResidencyKeyLocked(previous: IndicatorKey, replacement: IndicatorKey) {
        if (previous.kind != replacement.kind || previous.id != replacement.id || previous.label == replacement.label) return
        if (activeMain.any { key -> key == previous }) {
            val updated = activeMain.map { key -> if (key == previous) replacement else key }
            activeMain.clear()
            activeMain.addAll(updated)
        }
        activeSub.indices.forEach { index ->
            if (activeSub[index] == previous) activeSub[index] = replacement
        }
        if (retained.any { key -> key == previous }) {
            val updated = retained.map { key -> if (key == previous) replacement else key }
            retained.clear()
            retained.addAll(updated)
        }
    }

    private fun publishLocked(
        eventFactories: List<(Long) -> IndicatorRegistryEvent>,
        forceSnapshot: Boolean = false,
    ): IndicatorRegistrySnapshot {
        if (eventFactories.isEmpty() && !forceSnapshot) return mutableState.value
        generation += 1
        val snapshot = snapshotLocked()
        mutableState.value = snapshot
        eventFactories.forEach { factory -> mutableEvents.tryEmit(factory(generation)) }
        return snapshot
    }

    private fun snapshotLocked(): IndicatorRegistrySnapshot = IndicatorRegistrySnapshot(
        registryToken = registryToken,
        generation = generation,
        mountEpoch = mountEpoch,
        specEpoch = specEpoch,
        latestSpecChangeOldSpec = latestSpecChangeOldSpec,
        registered = registrations.values.toList(),
        activeMainKeys = activeMain.toList(),
        activeSubKeys = activeSub.toList(),
        retainedKeys = retained.toList(),
    )

    companion object {
        /** Default number of simultaneously active sub indicators. Time is not counted here. */
        const val DefaultSubIndicatorCapacity: Int = 4

        private val nextRegistryToken = AtomicLong(1L)
    }
}
