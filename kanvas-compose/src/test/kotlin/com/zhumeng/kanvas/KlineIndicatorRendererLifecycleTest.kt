/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.graphics.drawscope.DrawScope
import com.zhumeng.kanvas.core.IndicatorDefinition
import com.zhumeng.kanvas.core.IndicatorKey
import com.zhumeng.kanvas.core.IndicatorParameters
import com.zhumeng.kanvas.core.IndicatorPlacement
import com.zhumeng.kanvas.core.IndicatorRegistry
import com.zhumeng.kanvas.core.IndicatorRuntimeSnapshot
import com.zhumeng.kanvas.core.KlineInterval
import com.zhumeng.kanvas.core.KlineSpec
import com.zhumeng.kanvas.core.KlineTimeUnit
import com.zhumeng.kanvas.core.KlineUiState
import com.zhumeng.kanvas.core.MovingAverage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KlineIndicatorRendererLifecycleTest {
    private val state = KlineUiState()

    @Test
    fun `non keep alive renderer is recreated after hide and show`() {
        val events = mutableListOf<String>()
        val factory = RecordingFactory(events)
        val definition = IndicatorDefinition(key = IndicatorKey.direct("marker"))
        val registry = IndicatorRegistry()
        val mounted = registry.mount(listOf(definition), restoredActiveKeys = listOf(definition.key))
        val host = KlineIndicatorRendererLifecycleHost(
            KlineIndicatorRendererRegistry(statefulFactories = listOf(factory)),
        )
        try {
            host.reconcile(state, mounted)
            val first = host.resolve(definition, output = null)
            val hidden = registry.hide(definition.key)
            host.reconcile(state, hidden)
            val shown = registry.show(definition.key)
            host.reconcile(state, shown)
            val second = host.resolve(definition, output = null)

            assertEquals(
                listOf(
                    "create:1", "1:init:marker", "1:attach:marker", "1:detach:marker", "1:dispose:marker",
                    "create:2", "2:init:marker", "2:attach:marker",
                ),
                events,
            )
            assertTrue(first !== second)
        } finally {
            host.close()
        }
        assertEquals("2:dispose:marker", events.last())
    }

    @Test
    fun `stateful factory retains a pending Computed sub pane before first lifecycle effect`() {
        val events = mutableListOf<String>()
        val factory = object : KlineStatefulIndicatorRendererFactory {
            override fun supports(definition: IndicatorDefinition): Boolean = definition.key.id == "pending_factory"

            override fun create(definition: IndicatorDefinition): KlineStatefulIndicatorRenderer =
                RecordingFactory(events).create(definition)
        }
        val definition = IndicatorDefinition(
            key = IndicatorKey.computed("pending_factory"),
            placement = IndicatorPlacement.Sub(),
            calculator = MovingAverage(listOf(2)),
        )
        val registry = IndicatorRegistry()
        val selected = registry.mount(listOf(definition), restoredActiveKeys = listOf(definition.key))
        val host = KlineIndicatorRendererLifecycleHost(
            KlineIndicatorRendererRegistry(statefulFactories = listOf(factory)),
        )
        try {
            val missing: IndicatorRuntimeSnapshot? = null
            val plan = missing.resolveIndicatorPanePlan(state, selected, host)

            assertEquals(listOf("sub:computed:pending_factory"), plan.subByPane.keys.toList())
            assertTrue(plan.unsupportedDefinitions.isEmpty())
            host.reconcile(state, selected)
            assertTrue(host.resolve(definition, output = null) is KlineStatefulIndicatorRenderer)
        } finally {
            host.close()
        }
    }

    @Test
    fun `retained External renderer updates and receives spec callback without reinitializing`() {
        val events = mutableListOf<String>()
        val factory = RecordingFactory(events)
        val definition = IndicatorDefinition(key = IndicatorKey.external("orders", "Orders"))
        val registry = IndicatorRegistry()
        val mounted = registry.mount(listOf(definition))
        val oldSpec = KlineSpec("BTC-USDT", KlineInterval(1, KlineTimeUnit.Hour))
        val newSpec = KlineSpec("ETH-USDT", KlineInterval(1, KlineTimeUnit.Hour))
        val oldState = state.copy(spec = oldSpec)
        val newState = state.copy(spec = newSpec)
        val host = KlineIndicatorRendererLifecycleHost(
            KlineIndicatorRendererRegistry(statefulFactories = listOf(factory)),
        )
        try {
            host.reconcile(oldState, mounted)
            val first = host.resolve(definition, output = null)
            val hidden = registry.hide(definition.key)
            host.reconcile(oldState, hidden)
            val updatedDefinition = definition.copy(parameters = IndicatorParameters.of("source" to "book"))
            val updated = registry.upsert(updatedDefinition)
            host.reconcile(oldState, updated)
            val beforeSpecChange = registry.snapshot()
            registry.notifySpecChanged(oldSpec)
            val afterSpecChange = registry.snapshot()
            host.reconcile(newState, afterSpecChange)
            val shown = registry.show(definition.key)
            host.reconcile(newState, shown)
            val second = host.resolve(updatedDefinition, output = null)
            val removed = registry.unregister(definition.key)
            host.reconcile(state, removed)

            assertSame(first, second)
            assertEquals(
                listOf(
                    "create:1", "1:init:orders", "1:attach:orders", "1:detach:orders",
                    "1:update:orders->orders", "1:spec:BTC-USDT->ETH-USDT", "1:attach:orders",
                    "1:detach:orders", "1:dispose:orders",
                ),
                events,
            )
            assertTrue(beforeSpecChange.specEpoch < afterSpecChange.specEpoch)
        } finally {
            host.close()
        }
    }

    @Test
    fun `label update retains instance while placement change and fresh mount recreate it`() {
        val events = mutableListOf<String>()
        val factory = RecordingFactory(events)
        val initial = IndicatorDefinition(key = IndicatorKey.direct("moving", "Original"))
        val registry = IndicatorRegistry()
        val mounted = registry.mount(listOf(initial), restoredActiveKeys = listOf(initial.key))
        val host = KlineIndicatorRendererLifecycleHost(
            KlineIndicatorRendererRegistry(statefulFactories = listOf(factory)),
        )
        try {
            host.reconcile(state, mounted)
            val original = host.resolve(initial, output = null)
            val renamed = initial.copy(key = IndicatorKey.direct("moving", "Renamed"))
            val renamedSnapshot = registry.upsert(renamed)
            host.reconcile(state, renamedSnapshot)
            assertSame(original, host.resolve(renamed, output = null))

            val moved = renamed.copy(placement = IndicatorPlacement.Sub("moving-pane"), autoActivate = true)
            val movedSnapshot = registry.upsert(moved)
            host.reconcile(state, movedSnapshot)
            val movedRenderer = host.resolve(moved, output = null)
            assertTrue(movedRenderer !== original)

            val remounted = registry.mount(listOf(moved), restoredActiveKeys = listOf(moved.key))
            host.reconcile(state, remounted)
            val remountedRenderer = host.resolve(moved, output = null)
            assertTrue(remountedRenderer !== movedRenderer)

            assertEquals(
                listOf(
                    "create:1", "1:init:moving", "1:attach:moving", "1:update:moving->moving",
                    "1:detach:moving", "1:dispose:moving", "create:2", "2:init:moving", "2:attach:moving",
                    "2:detach:moving", "2:dispose:moving", "create:3", "3:init:moving", "3:attach:moving",
                ),
                events,
            )
        } finally {
            host.close()
        }
    }

    @Test
    fun `init failure invalidates once and then resolves as unsupported`() {
        val definition = IndicatorDefinition(key = IndicatorKey.direct("init_failure"))
        val registry = IndicatorRegistry()
        val mounted = registry.mount(listOf(definition), restoredActiveKeys = listOf(definition.key))
        var createCount = 0
        var disposeCount = 0
        val errors = mutableListOf<Throwable>()
        val factory = object : KlineStatefulIndicatorRendererFactory {
            override fun supports(definition: IndicatorDefinition): Boolean =
                definition.key.id == "init_failure"

            override fun create(definition: IndicatorDefinition): KlineStatefulIndicatorRenderer {
                createCount += 1
                return testRenderer(
                    onInit = { throw IllegalStateException("init failed") },
                    onDispose = { disposeCount += 1 },
                )
            }
        }
        val host = KlineIndicatorRendererLifecycleHost(
            rendererRegistry = KlineIndicatorRendererRegistry(
                renderers = emptyList(),
                statefulFactories = listOf(factory),
            ),
            onRendererError = { _, error -> errors += error },
        )
        try {
            host.reconcile(state, mounted)

            assertEquals(1, createCount)
            assertEquals(1, disposeCount)
            assertEquals(1, errors.size)
            assertEquals(1L, host.invalidationEpoch.value)
            assertNull(host.resolve(definition, output = null, state = state, registry = mounted))

            val missing: IndicatorRuntimeSnapshot? = null
            val plan = missing.resolveIndicatorPanePlan(state, mounted, host)
            assertEquals(listOf(definition.key), plan.unsupportedDefinitions.map(IndicatorDefinition::key))

            host.reconcile(state, mounted)
            assertEquals(1, createCount)
            assertEquals(1L, host.invalidationEpoch.value)
        } finally {
            host.close()
        }
    }

    @Test
    fun `close drops stateful resolution and ignores a retained invalidate callback`() {
        val definition = IndicatorDefinition(key = IndicatorKey.direct("close"))
        val registry = IndicatorRegistry()
        val mounted = registry.mount(listOf(definition), restoredActiveKeys = listOf(definition.key))
        var invalidate: (() -> Unit)? = null
        val factory = object : KlineStatefulIndicatorRendererFactory {
            override fun supports(definition: IndicatorDefinition): Boolean = definition.key.id == "close"

            override fun create(definition: IndicatorDefinition): KlineStatefulIndicatorRenderer = testRenderer(
                onInit = { context -> invalidate = context.invalidate },
            )
        }
        val host = KlineIndicatorRendererLifecycleHost(
            KlineIndicatorRendererRegistry(renderers = emptyList(), statefulFactories = listOf(factory)),
        )
        host.reconcile(state, mounted)
        val attached = checkNotNull(host.resolve(definition, output = null, state = state, registry = mounted))
        assertTrue(attached is KlineStatefulIndicatorRenderer)

        val beforeClose = host.invalidationEpoch.value
        host.close()
        val afterClose = host.invalidationEpoch.value

        assertEquals(beforeClose + 1L, afterClose)
        assertNull(host.resolve(definition, output = null, state = state, registry = mounted))
        assertNull(host.resolve(definition, output = null))
        checkNotNull(invalidate).invoke()
        assertEquals(afterClose, host.invalidationEpoch.value)
    }

    @Test
    fun `fresh mount resolver never exposes the prior instance before reconciliation`() {
        val events = mutableListOf<String>()
        val factory = RecordingFactory(events)
        val definition = IndicatorDefinition(key = IndicatorKey.direct("marker"))
        val registry = IndicatorRegistry()
        val firstMount = registry.mount(listOf(definition), restoredActiveKeys = listOf(definition.key))
        val host = KlineIndicatorRendererLifecycleHost(
            KlineIndicatorRendererRegistry(renderers = emptyList(), statefulFactories = listOf(factory)),
        )
        try {
            host.reconcile(state, firstMount)
            val oldRenderer = checkNotNull(
                host.resolve(definition, output = null, state = state, registry = firstMount),
            )

            val freshMount = registry.mount(listOf(definition), restoredActiveKeys = listOf(definition.key))
            val beforeEffect = host.resolve(definition, output = null, state = state, registry = freshMount)

            assertTrue(beforeEffect != null)
            assertTrue(beforeEffect !== oldRenderer)
            assertTrue(beforeEffect !is KlineStatefulIndicatorRenderer)

            host.reconcile(state, freshMount)
            val recreated = checkNotNull(
                host.resolve(definition, output = null, state = state, registry = freshMount),
            )
            assertTrue(recreated is KlineStatefulIndicatorRenderer)
            assertTrue(recreated !== oldRenderer)
        } finally {
            host.close()
        }
    }

    @Test
    fun `spec notification waits for state when registry arrives first`() {
        val events = mutableListOf<String>()
        val factory = RecordingFactory(events)
        val definition = IndicatorDefinition(key = IndicatorKey.direct("marker"))
        val registry = IndicatorRegistry()
        val mounted = registry.mount(listOf(definition), restoredActiveKeys = listOf(definition.key))
        val a = spec("A")
        val b = spec("B")
        val host = KlineIndicatorRendererLifecycleHost(
            KlineIndicatorRendererRegistry(renderers = emptyList(), statefulFactories = listOf(factory)),
        )
        try {
            host.reconcile(state.copy(spec = a), mounted)
            registry.notifySpecChanged(a)
            val registryB = registry.snapshot()

            host.reconcile(state.copy(spec = a), registryB)
            assertTrue(events.none { ":spec:" in it })

            host.reconcile(state.copy(spec = b), registryB)
            assertEquals(listOf("1:spec:A->B"), events.filter { ":spec:" in it })
        } finally {
            host.close()
        }
    }

    @Test
    fun `spec notification waits for registry when state arrives first`() {
        val events = mutableListOf<String>()
        val factory = RecordingFactory(events)
        val definition = IndicatorDefinition(key = IndicatorKey.direct("marker"))
        val registry = IndicatorRegistry()
        val mounted = registry.mount(listOf(definition), restoredActiveKeys = listOf(definition.key))
        val a = spec("A")
        val b = spec("B")
        val host = KlineIndicatorRendererLifecycleHost(
            KlineIndicatorRendererRegistry(renderers = emptyList(), statefulFactories = listOf(factory)),
        )
        try {
            host.reconcile(state.copy(spec = a), mounted)
            host.reconcile(state.copy(spec = b), mounted)
            assertTrue(events.none { ":spec:" in it })

            registry.notifySpecChanged(a)
            host.reconcile(state.copy(spec = b), registry.snapshot())
            assertEquals(listOf("1:spec:A->B"), events.filter { ":spec:" in it })
        } finally {
            host.close()
        }
    }

    @Test
    fun `coalesced spec notifications dispatch from the last delivered state to the latest state`() {
        val events = mutableListOf<String>()
        val factory = RecordingFactory(events)
        val definition = IndicatorDefinition(key = IndicatorKey.direct("marker"))
        val registry = IndicatorRegistry()
        val mounted = registry.mount(listOf(definition), restoredActiveKeys = listOf(definition.key))
        val a = spec("A")
        val b = spec("B")
        val c = spec("C")
        val host = KlineIndicatorRendererLifecycleHost(
            KlineIndicatorRendererRegistry(renderers = emptyList(), statefulFactories = listOf(factory)),
        )
        try {
            host.reconcile(state.copy(spec = a), mounted)
            registry.notifySpecChanged(a)
            registry.notifySpecChanged(b)

            host.reconcile(state.copy(spec = c), registry.snapshot())
            assertEquals(listOf("1:spec:A->C"), events.filter { ":spec:" in it })
        } finally {
            host.close()
        }
    }

    @Test
    fun `same dependency precision change keeps the ready renderer instead of returning pending`() {
        val events = mutableListOf<String>()
        val factory = RecordingFactory(events)
        val definition = IndicatorDefinition(key = IndicatorKey.direct("marker"))
        val registry = IndicatorRegistry()
        val mounted = registry.mount(listOf(definition), restoredActiveKeys = listOf(definition.key))
        val base = spec("BTC", precision = 4)
        val precisionOnly = base.copy(precision = 8)
        val host = KlineIndicatorRendererLifecycleHost(
            KlineIndicatorRendererRegistry(renderers = emptyList(), statefulFactories = listOf(factory)),
        )
        try {
            host.reconcile(state.copy(spec = base), mounted)
            val ready = checkNotNull(host.resolve(definition, output = null, state = state.copy(spec = base), registry = mounted))

            val beforeReconcile = host.resolve(
                definition,
                output = null,
                state = state.copy(spec = precisionOnly),
                registry = mounted,
            )
            assertSame(ready, beforeReconcile)

            host.reconcile(state.copy(spec = precisionOnly), mounted)
            assertSame(
                ready,
                host.resolve(definition, output = null, state = state.copy(spec = precisionOnly), registry = mounted),
            )
            assertTrue(events.none { ":spec:" in it })
        } finally {
            host.close()
        }
    }

    @Test
    fun `onInit reentrant close disposes the unattached renderer without leaking it`() {
        val definition = IndicatorDefinition(key = IndicatorKey.direct("close_from_init"))
        val registry = IndicatorRegistry()
        val mounted = registry.mount(listOf(definition), restoredActiveKeys = listOf(definition.key))
        lateinit var host: KlineIndicatorRendererLifecycleHost
        var createCount = 0
        var initCount = 0
        var attachCount = 0
        var disposeCount = 0
        val factory = object : KlineStatefulIndicatorRendererFactory {
            override fun supports(definition: IndicatorDefinition): Boolean = definition.key.id == "close_from_init"

            override fun create(definition: IndicatorDefinition): KlineStatefulIndicatorRenderer {
                createCount += 1
                return testRenderer(
                    onInit = {
                        initCount += 1
                        host.close()
                    },
                    onAttach = { attachCount += 1 },
                    onDispose = { disposeCount += 1 },
                )
            }
        }
        host = KlineIndicatorRendererLifecycleHost(
            KlineIndicatorRendererRegistry(renderers = emptyList(), statefulFactories = listOf(factory)),
        )

        host.reconcile(state, mounted)

        assertEquals(1, createCount)
        assertEquals(1, initCount)
        assertEquals(0, attachCount)
        assertEquals(1, disposeCount)
        assertNull(host.resolve(definition, output = null, state = state, registry = mounted))
    }

    @Test
    fun `factory matcher closing host prevents renderer creation`() {
        val definition = IndicatorDefinition(key = IndicatorKey.direct("close_from_supports"))
        val registry = IndicatorRegistry()
        val mounted = registry.mount(listOf(definition), restoredActiveKeys = listOf(definition.key))
        lateinit var host: KlineIndicatorRendererLifecycleHost
        var supportsCount = 0
        var createCount = 0
        val factory = object : KlineStatefulIndicatorRendererFactory {
            override fun supports(definition: IndicatorDefinition): Boolean {
                supportsCount += 1
                host.close()
                return true
            }

            override fun create(definition: IndicatorDefinition): KlineStatefulIndicatorRenderer {
                createCount += 1
                return testRenderer()
            }
        }
        host = KlineIndicatorRendererLifecycleHost(
            KlineIndicatorRendererRegistry(renderers = emptyList(), statefulFactories = listOf(factory)),
        )

        host.reconcile(state, mounted)

        assertEquals(1, supportsCount)
        assertEquals(0, createCount)
        assertNull(host.resolve(definition, output = null, state = state, registry = mounted))
    }

    @Test
    fun `onUpdate failure drops the old renderer and does not retry the same definition`() {
        val initial = IndicatorDefinition(key = IndicatorKey.direct("update_failure"))
        val updated = initial.copy(parameters = IndicatorParameters.of("period" to "20"))
        val registry = IndicatorRegistry()
        val mounted = registry.mount(listOf(initial), restoredActiveKeys = listOf(initial.key))
        var createCount = 0
        var updateCount = 0
        var disposeCount = 0
        val factory = object : KlineStatefulIndicatorRendererFactory {
            override fun supports(definition: IndicatorDefinition): Boolean =
                definition.key.id == "update_failure"

            override fun create(definition: IndicatorDefinition): KlineStatefulIndicatorRenderer {
                createCount += 1
                return testRenderer(
                    onUpdate = {
                        updateCount += 1
                        throw IllegalStateException("update failed")
                    },
                    onDispose = { disposeCount += 1 },
                )
            }
        }
        val host = KlineIndicatorRendererLifecycleHost(
            KlineIndicatorRendererRegistry(renderers = emptyList(), statefulFactories = listOf(factory)),
        )
        try {
            host.reconcile(state, mounted)
            val oldRenderer = checkNotNull(
                host.resolve(initial, output = null, state = state, registry = mounted),
            )
            assertTrue(oldRenderer is KlineStatefulIndicatorRenderer)

            val updatedSnapshot = registry.upsert(updated)
            host.reconcile(state, updatedSnapshot)

            assertNull(host.resolve(updated, output = null, state = state, registry = updatedSnapshot))
            assertEquals(1, createCount)
            assertEquals(1, updateCount)
            assertEquals(1, disposeCount)

            host.reconcile(state, updatedSnapshot)
            assertEquals(1, createCount)
            assertEquals(1, updateCount)
            assertEquals(1, disposeCount)
        } finally {
            host.close()
        }
        assertEquals(1, disposeCount)
    }

    @Test
    fun `onAttach failure drops a retained renderer and does not retry the same definition`() {
        val definition = IndicatorDefinition(key = IndicatorKey.external("attach_failure"))
        val registry = IndicatorRegistry()
        val mounted = registry.mount(listOf(definition))
        var createCount = 0
        var attachCount = 0
        var disposeCount = 0
        val factory = object : KlineStatefulIndicatorRendererFactory {
            override fun supports(definition: IndicatorDefinition): Boolean =
                definition.key.id == "attach_failure"

            override fun create(definition: IndicatorDefinition): KlineStatefulIndicatorRenderer {
                createCount += 1
                return testRenderer(
                    onAttach = {
                        attachCount += 1
                        if (attachCount == 2) throw IllegalStateException("attach failed")
                    },
                    onDispose = { disposeCount += 1 },
                )
            }
        }
        val host = KlineIndicatorRendererLifecycleHost(
            KlineIndicatorRendererRegistry(renderers = emptyList(), statefulFactories = listOf(factory)),
        )
        try {
            host.reconcile(state, mounted)
            val oldRenderer = checkNotNull(
                host.resolve(definition, output = null, state = state, registry = mounted),
            )
            assertTrue(oldRenderer is KlineStatefulIndicatorRenderer)

            val hidden = registry.hide(definition.key)
            host.reconcile(state, hidden)
            val shown = registry.show(definition.key)
            host.reconcile(state, shown)

            assertNull(host.resolve(definition, output = null, state = state, registry = shown))
            assertEquals(1, createCount)
            assertEquals(2, attachCount)
            assertEquals(1, disposeCount)

            host.reconcile(state, shown)
            assertEquals(1, createCount)
            assertEquals(2, attachCount)
            assertEquals(1, disposeCount)
        } finally {
            host.close()
        }
        assertEquals(1, disposeCount)
    }

    @Test
    fun `onDetach failure drops the old renderer and does not retry the same definition`() {
        val definition = IndicatorDefinition(key = IndicatorKey.external("detach_failure"))
        val registry = IndicatorRegistry()
        val mounted = registry.mount(listOf(definition))
        var createCount = 0
        var detachCount = 0
        var disposeCount = 0
        val factory = object : KlineStatefulIndicatorRendererFactory {
            override fun supports(definition: IndicatorDefinition): Boolean =
                definition.key.id == "detach_failure"

            override fun create(definition: IndicatorDefinition): KlineStatefulIndicatorRenderer {
                createCount += 1
                return testRenderer(
                    onDetach = {
                        detachCount += 1
                        throw IllegalStateException("detach failed")
                    },
                    onDispose = { disposeCount += 1 },
                )
            }
        }
        val host = KlineIndicatorRendererLifecycleHost(
            KlineIndicatorRendererRegistry(renderers = emptyList(), statefulFactories = listOf(factory)),
        )
        try {
            host.reconcile(state, mounted)
            val oldRenderer = checkNotNull(
                host.resolve(definition, output = null, state = state, registry = mounted),
            )
            assertTrue(oldRenderer is KlineStatefulIndicatorRenderer)

            val hidden = registry.hide(definition.key)
            host.reconcile(state, hidden)

            assertNull(host.resolve(definition, output = null, state = state, registry = hidden))
            assertEquals(1, createCount)
            assertEquals(1, detachCount)
            assertEquals(1, disposeCount)

            host.reconcile(state, hidden)
            assertEquals(1, createCount)
            assertEquals(1, detachCount)
            assertEquals(1, disposeCount)
        } finally {
            host.close()
        }
        assertEquals(1, disposeCount)
    }

    @Test
    fun `onSpecChanged failure drops the old renderer and does not retry the same definition`() {
        val definition = IndicatorDefinition(key = IndicatorKey.direct("spec_failure"))
        val registry = IndicatorRegistry()
        val mounted = registry.mount(listOf(definition), restoredActiveKeys = listOf(definition.key))
        val specA = spec("A")
        val specB = spec("B")
        val stateA = state.copy(spec = specA)
        val stateB = state.copy(spec = specB)
        var createCount = 0
        var specChangeCount = 0
        var disposeCount = 0
        val factory = object : KlineStatefulIndicatorRendererFactory {
            override fun supports(definition: IndicatorDefinition): Boolean =
                definition.key.id == "spec_failure"

            override fun create(definition: IndicatorDefinition): KlineStatefulIndicatorRenderer {
                createCount += 1
                return testRenderer(
                    onSpecChanged = {
                        specChangeCount += 1
                        throw IllegalStateException("spec change failed")
                    },
                    onDispose = { disposeCount += 1 },
                )
            }
        }
        val host = KlineIndicatorRendererLifecycleHost(
            KlineIndicatorRendererRegistry(renderers = emptyList(), statefulFactories = listOf(factory)),
        )
        try {
            host.reconcile(stateA, mounted)
            val oldRenderer = checkNotNull(
                host.resolve(definition, output = null, state = stateA, registry = mounted),
            )
            assertTrue(oldRenderer is KlineStatefulIndicatorRenderer)

            registry.notifySpecChanged(specA)
            val specChanged = registry.snapshot()
            host.reconcile(stateB, specChanged)

            assertNull(host.resolve(definition, output = null, state = stateB, registry = specChanged))
            assertEquals(1, createCount)
            assertEquals(1, specChangeCount)
            assertEquals(1, disposeCount)

            host.reconcile(stateB, specChanged)
            assertEquals(1, createCount)
            assertEquals(1, specChangeCount)
            assertEquals(1, disposeCount)
        } finally {
            host.close()
        }
        assertEquals(1, disposeCount)
    }

    @Test
    fun `empty registry spec transition accepts a later stateful show without pending resolution`() {
        val registry = IndicatorRegistry()
        val empty = registry.mount(emptyList())
        val stateA = state.copy(spec = spec("A"))
        val stateB = state.copy(spec = spec("B"))
        val definition = IndicatorDefinition(key = IndicatorKey.direct("show_after_empty"))
        var createCount = 0
        val factory = object : KlineStatefulIndicatorRendererFactory {
            override fun supports(definition: IndicatorDefinition): Boolean =
                definition.key.id == "show_after_empty"

            override fun create(definition: IndicatorDefinition): KlineStatefulIndicatorRenderer {
                createCount += 1
                return testRenderer()
            }
        }
        val host = KlineIndicatorRendererLifecycleHost(
            KlineIndicatorRendererRegistry(renderers = emptyList(), statefulFactories = listOf(factory)),
        )
        try {
            host.reconcile(stateA, empty)
            host.reconcile(stateB, empty)

            registry.register(definition)
            val shown = registry.show(definition.key)
            assertEquals(empty.specEpoch, shown.specEpoch)
            host.reconcile(stateB, shown)

            val resolved = host.resolve(
                definition,
                output = null,
                state = stateB,
                registry = shown,
            )
            assertTrue(resolved is KlineStatefulIndicatorRenderer)
            assertEquals(1, createCount)
        } finally {
            host.close()
        }
    }

    @Test
    fun `unloading the last active renderer at B accepts a later stateful show without pending resolution`() {
        val oldDefinition = IndicatorDefinition(key = IndicatorKey.direct("active_at_a"))
        val newDefinition = IndicatorDefinition(key = IndicatorKey.direct("shown_at_b"))
        val registry = IndicatorRegistry()
        val mountedAtA = registry.mount(
            listOf(oldDefinition),
            restoredActiveKeys = listOf(oldDefinition.key),
        )
        val specA = spec("A")
        val specB = spec("B")
        val stateA = state.copy(spec = specA)
        val stateB = state.copy(spec = specB)
        var createCount = 0
        var disposeCount = 0
        val factory = object : KlineStatefulIndicatorRendererFactory {
            override fun supports(definition: IndicatorDefinition): Boolean =
                definition.key.id in setOf("active_at_a", "shown_at_b")

            override fun create(definition: IndicatorDefinition): KlineStatefulIndicatorRenderer {
                createCount += 1
                return testRenderer(onDispose = { disposeCount += 1 })
            }
        }
        val host = KlineIndicatorRendererLifecycleHost(
            KlineIndicatorRendererRegistry(renderers = emptyList(), statefulFactories = listOf(factory)),
        )
        try {
            host.reconcile(stateA, mountedAtA)
            assertTrue(
                host.resolve(oldDefinition, output = null, state = stateA, registry = mountedAtA) is
                    KlineStatefulIndicatorRenderer,
            )

            val emptyAtB = registry.unregister(oldDefinition.key)
            assertTrue(registry.notifySpecChanged(specA).isEmpty())
            val emptyAtBAfterNoTargetNotification = registry.snapshot()
            assertEquals(emptyAtB.specEpoch, emptyAtBAfterNoTargetNotification.specEpoch)
            host.reconcile(stateB, emptyAtBAfterNoTargetNotification)
            assertEquals(1, disposeCount)

            registry.register(newDefinition)
            val shownAtB = registry.show(newDefinition.key)
            assertEquals(emptyAtBAfterNoTargetNotification.specEpoch, shownAtB.specEpoch)
            host.reconcile(stateB, shownAtB)

            val resolved = host.resolve(
                newDefinition,
                output = null,
                state = stateB,
                registry = shownAtB,
            )
            assertTrue(resolved is KlineStatefulIndicatorRenderer)
            assertEquals(2, createCount)
        } finally {
            host.close()
        }
        assertEquals(2, disposeCount)
    }

    private class RecordingFactory(
        private val events: MutableList<String>,
    ) : KlineStatefulIndicatorRendererFactory {
        private var nextInstance = 0

        override fun supports(definition: IndicatorDefinition): Boolean =
            definition.key.id in setOf("marker", "orders", "moving")

        override fun create(definition: IndicatorDefinition): KlineStatefulIndicatorRenderer {
            val id = ++nextInstance
            events += "create:$id"
            return object : KlineStatefulIndicatorRenderer {
                override fun supports(
                    definition: IndicatorDefinition,
                    output: com.zhumeng.kanvas.core.IndicatorOutput?,
                ): Boolean = true

                override fun draw(scope: DrawScope, context: KlineIndicatorDrawContext) = Unit

                override fun onInit(context: KlineIndicatorLifecycleContext) {
                    events += "$id:init:${context.definition.key.id}"
                }

                override fun onAttach(context: KlineIndicatorLifecycleContext) {
                    events += "$id:attach:${context.definition.key.id}"
                }

                override fun onUpdate(context: KlineIndicatorUpdateContext) {
                    events += "$id:update:${context.previous.key.id}->${context.current.key.id}"
                }

                override fun onSpecChanged(context: KlineIndicatorSpecChangeContext) {
                    events += "$id:spec:${context.oldSpec?.symbol}->${context.newSpec?.symbol}"
                }

                override fun onDetach(context: KlineIndicatorLifecycleContext) {
                    events += "$id:detach:${context.definition.key.id}"
                }

                override fun onDispose(context: KlineIndicatorDisposeContext) {
                    events += "$id:dispose:${context.definition.key.id}"
                }
            }
        }
    }

    private fun spec(symbol: String, precision: Int = KlineSpec.DefaultPrecision): KlineSpec =
        KlineSpec(symbol, KlineInterval(1, KlineTimeUnit.Hour), precision = precision)

    private fun testRenderer(
        onInit: (KlineIndicatorLifecycleContext) -> Unit = {},
        onAttach: (KlineIndicatorLifecycleContext) -> Unit = {},
        onUpdate: (KlineIndicatorUpdateContext) -> Unit = {},
        onSpecChanged: (KlineIndicatorSpecChangeContext) -> Unit = {},
        onDetach: (KlineIndicatorLifecycleContext) -> Unit = {},
        onDispose: (KlineIndicatorDisposeContext) -> Unit = {},
    ): KlineStatefulIndicatorRenderer = object : KlineStatefulIndicatorRenderer {
        override fun supports(definition: IndicatorDefinition, output: com.zhumeng.kanvas.core.IndicatorOutput?): Boolean = true

        override fun draw(scope: DrawScope, context: KlineIndicatorDrawContext) = Unit

        override fun onInit(context: KlineIndicatorLifecycleContext) = onInit(context)

        override fun onAttach(context: KlineIndicatorLifecycleContext) = onAttach(context)

        override fun onUpdate(context: KlineIndicatorUpdateContext) = onUpdate(context)

        override fun onSpecChanged(context: KlineIndicatorSpecChangeContext) = onSpecChanged(context)

        override fun onDetach(context: KlineIndicatorLifecycleContext) = onDetach(context)

        override fun onDispose(context: KlineIndicatorDisposeContext) = onDispose(context)
    }
}
