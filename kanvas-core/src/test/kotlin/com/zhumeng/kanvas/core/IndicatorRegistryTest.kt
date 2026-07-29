/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IndicatorRegistryTest {
    @Test
    fun `mount restores persisted selections then auto activates only registered definitions`() {
        val mainSelected = definition("main_selected", IndicatorPlacement.Main)
        val mainAuto = definition("main_auto", IndicatorPlacement.Main, autoActivate = true)
        val subSelected = definition("sub_selected", IndicatorPlacement.Sub("sub_selected"))
        val subAuto = definition("sub_auto", IndicatorPlacement.Sub("sub_auto"), autoActivate = true)
        val unknown = IndicatorKey.computed("unknown")
        val registry = IndicatorRegistry()

        val snapshot = registry.mount(
            definitions = listOf(mainSelected, mainAuto, subSelected, subAuto),
            restoredActiveKeys = listOf(subSelected.key, mainSelected.key, unknown, mainSelected.key),
        )

        assertEquals(listOf(mainSelected.key, mainAuto.key), snapshot.activeMainDefinitions().map(IndicatorDefinition::key))
        assertEquals(listOf(subSelected.key, subAuto.key), snapshot.activeSubKeys())
        assertFalse(snapshot.isRegistered(unknown))
        assertFalse(snapshot.isActive(unknown))
    }

    @Test
    fun `auto activate only reacts to false to true and preserves manual hide`() {
        val hidden = definition("ma", IndicatorPlacement.Main)
        val registry = IndicatorRegistry()
        registry.register(hidden)

        val firstAuto = registry.upsert(hidden.copy(autoActivate = true))
        assertTrue(firstAuto.isActive(hidden.key))

        val hiddenAgain = registry.hide(hidden.key)
        assertFalse(hiddenAgain.isActive(hidden.key))

        val sameAutoAfterManualHide = registry.upsert(hidden.copy(autoActivate = true, zIndex = 9))
        assertFalse(sameAutoAfterManualHide.isActive(hidden.key))

        val falseDoesNotChangeManualState = registry.upsert(hidden.copy(autoActivate = false, zIndex = 10))
        assertFalse(falseDoesNotChangeManualState.isActive(hidden.key))

        val secondFalseToTrue = registry.upsert(hidden.copy(autoActivate = true, zIndex = 11))
        assertTrue(secondFalseToTrue.isActive(hidden.key))
    }

    @Test
    fun `hidden keep alive is retained but excluded from calculation until shown again`() {
        val definition = definition("kept", IndicatorPlacement.Main, keepAlive = true)
        val registry = IndicatorRegistry()
        val mounted = registry.mount(listOf(definition), restoredActiveKeys = listOf(definition.key))
        val runtime = IndicatorRuntime()
        val initial = runtime.calculate(series = seriesOfCloses(3.0, 2.0, 1.0), registry = mounted)

        val hidden = registry.hide(definition.key)
        val whileHidden = runtime.calculate(
            previous = initial,
            series = seriesOfCloses(4.0, 3.0, 2.0, 1.0),
            registry = hidden,
        )

        assertTrue(hidden.isRetained(definition.key))
        assertFalse(hidden.isActive(definition.key))
        assertTrue(hidden.calculationDefinitions().isEmpty())
        assertTrue(whileHidden.outputs().isEmpty())
        assertTrue(whileHidden.matches(hidden))

        val shown = registry.show(definition.key)
        val afterShow = runtime.calculate(
            previous = whileHidden,
            series = seriesOfCloses(4.0, 3.0, 2.0, 1.0),
            registry = shown,
        )

        assertTrue(shown.isActive(definition.key))
        assertFalse(shown.isRetained(definition.key))
        assertEquals(listOf("ma_2"), afterShow.output(definition.key)?.columnNames?.toList())
        assertTrue(afterShow.matches(shown))
        assertFalse(initial.matches(shown))
    }

    @Test
    fun `external declarations default to keep alive`() {
        val definition = IndicatorDefinition(key = IndicatorKey.external("trade"))
        val registry = IndicatorRegistry()
        val mounted = registry.mount(listOf(definition))

        val hidden = registry.hide(definition.key)

        assertTrue(definition.keepAlive)
        assertTrue(definition.autoActivate)
        assertTrue(mounted.isActive(definition.key))
        assertTrue(hidden.isRetained(definition.key))
    }

    @Test
    fun `moving a declaration across panes detaches it unless new declaration auto activates`() {
        val main = definition("moving", IndicatorPlacement.Main, keepAlive = true)
        val registry = IndicatorRegistry()
        registry.mount(listOf(main), restoredActiveKeys = listOf(main.key))

        val movedInactive = registry.upsert(main.copy(placement = IndicatorPlacement.Sub("moved")))

        assertFalse(movedInactive.isActive(main.key))
        assertFalse(movedInactive.isRetained(main.key))

        val movedAuto = registry.upsert(
            main.copy(
                placement = IndicatorPlacement.Sub("moved"),
                autoActivate = true,
            ),
        )

        assertEquals(listOf(main.key), movedAuto.activeSubKeys())
    }

    @Test
    fun `residency and reused runtime output adopt the latest declaration label`() {
        val persistedKey = IndicatorKey.computed("ma", "Kanvas MA")
        val kotlinDefinition = definition("ma", IndicatorPlacement.Main).copy(
            key = IndicatorKey.computed("ma", "Kotlin MA"),
        )
        val registry = IndicatorRegistry()
        val initialRegistry = registry.mount(listOf(kotlinDefinition), restoredActiveKeys = listOf(persistedKey))
        val runtime = IndicatorRuntime()
        val series = seriesOfCloses(3.0, 2.0, 1.0)
        val first = runtime.calculate(series = series, registry = initialRegistry)

        assertEquals("Kotlin MA", initialRegistry.activeMainKeys().single().label)

        val renamed = kotlinDefinition.copy(key = IndicatorKey.computed("ma", "Renamed MA"))
        val updatedRegistry = registry.upsert(renamed)
        val reused = runtime.calculate(previous = first, series = series, registry = updatedRegistry)

        assertEquals("Renamed MA", updatedRegistry.activeMainKeys().single().label)
        assertEquals("Renamed MA", reused.output(renamed.key)?.key?.label)
    }

    @Test
    fun `sub indicators use default capacity and FIFO eviction`() {
        val definitions = (1..5).map { index ->
            definition(
                id = "sub_$index",
                placement = IndicatorPlacement.Sub("pane_$index"),
                keepAlive = index == 1,
            )
        }
        val registry = IndicatorRegistry()
        val mounted = registry.mount(definitions, restoredActiveKeys = definitions.map(IndicatorDefinition::key))

        assertEquals(
            definitions.drop(1).map(IndicatorDefinition::key),
            mounted.activeSubKeys(),
        )
        assertEquals(listOf(definitions.first().key), mounted.retainedKeys())

        val reshown = registry.show(definitions.first().key)
        assertEquals(
            listOf(definitions[2].key, definitions[3].key, definitions[4].key, definitions[0].key),
            reshown.activeSubKeys(),
        )
        assertTrue(reshown.isRetained(definitions[1].key).not())
    }

    @Test
    fun `active sub indicators can be reordered without changing residency`() {
        val definitions = (1..3).map { index ->
            definition("ordered_$index", IndicatorPlacement.Sub("pane_$index"))
        }
        val registry = IndicatorRegistry()
        registry.mount(definitions, restoredActiveKeys = definitions.map(IndicatorDefinition::key))

        val moved = registry.moveActiveSub(definitions[2].key, 0)

        assertEquals(
            listOf(definitions[2].key, definitions[0].key, definitions[1].key),
            moved.activeSubKeys(),
        )
        assertTrue(definitions.all { moved.isActive(it.key) })
    }

    @Test
    fun `unregister forcibly clears active and retained state`() {
        val definition = definition("gone", IndicatorPlacement.Main, keepAlive = true)
        val registry = IndicatorRegistry()
        registry.mount(listOf(definition), restoredActiveKeys = listOf(definition.key))
        registry.hide(definition.key)

        val removed = registry.unregister(definition.key)

        assertFalse(removed.isRegistered(definition.key))
        assertFalse(removed.isActive(definition.key))
        assertFalse(removed.isRetained(definition.key))
    }

    @Test
    fun `spec change reaches active and retained definitions once`() {
        val active = definition("active", IndicatorPlacement.Main)
        val retained = definition("retained", IndicatorPlacement.Sub("retained"), keepAlive = true)
        val registry = IndicatorRegistry()
        registry.mount(listOf(active, retained), restoredActiveKeys = listOf(active.key, retained.key))
        registry.hide(retained.key)

        val before = registry.snapshot()
        val oldSpec = KlineSpec(symbol = "BTC-USDT", interval = KlineInterval(1, KlineTimeUnit.Hour))
        val notified = registry.notifySpecChanged(oldSpec)
        val after = registry.snapshot()

        assertEquals(listOf(active.key, retained.key), notified)
        assertTrue(after.generation > before.generation)
        assertEquals(before.specEpoch + 1, after.specEpoch)
        assertEquals(oldSpec, after.latestSpecChangeOldSpec)
        assertFalse(IndicatorRuntime().calculate(series = seriesOfCloses(2.0, 1.0), registry = before).matches(after))
    }

    @Test
    fun `fresh mount advances epoch even when the declaration set repeats`() {
        val definition = definition("mounted", IndicatorPlacement.Main)
        val registry = IndicatorRegistry()
        val first = registry.mount(listOf(definition), restoredActiveKeys = listOf(definition.key))
        val second = registry.mount(listOf(definition), restoredActiveKeys = listOf(definition.key))

        assertEquals(first.mountEpoch + 1, second.mountEpoch)
        assertTrue(second.generation > first.generation)
    }

    @Test
    fun `empty fresh mount still publishes its new epoch`() {
        val registry = IndicatorRegistry()
        val initial = registry.snapshot()

        val remounted = registry.mount(emptyList())

        assertEquals(initial.mountEpoch + 1, remounted.mountEpoch)
        assertTrue(remounted.generation > initial.generation)
    }

    @Test
    fun `paint mode and layout hint retain indicator-owned geometry`() {
        val definition = definition(
            id = "alone",
            placement = IndicatorPlacement.Main,
            paintMode = IndicatorPaintMode.ALONE,
            layoutHint = IndicatorLayoutHint(
                height = 120f,
                minHeight = 36f,
                padding = IndicatorInsets(left = 1f, top = 2f, right = 3f, bottom = 4f),
            ),
        )

        assertEquals(IndicatorPaintMode.ALONE, definition.paintMode)
        assertEquals(120f, definition.layoutHint.resolvedHeight)
        assertEquals(36f, definition.layoutHint.minHeight)
        assertEquals(IndicatorInsets(left = 1f, top = 2f, right = 3f, bottom = 4f), definition.layoutHint.padding)
    }

    @Test
    fun `dispose clears all declaration residency`() {
        val definition = definition("dispose", IndicatorPlacement.Main, keepAlive = true)
        val registry = IndicatorRegistry()
        registry.mount(listOf(definition), restoredActiveKeys = listOf(definition.key))

        val disposed = registry.dispose()

        assertTrue(disposed.registeredDefinitions().isEmpty())
        assertTrue(disposed.activeDefinitions().isEmpty())
        assertTrue(disposed.retainedDefinitions().isEmpty())
    }

    @Test
    fun `runtime snapshot never matches a different registry with the same generation`() {
        val definition = definition("identity", IndicatorPlacement.Main)
        val firstRegistry = IndicatorRegistry()
        val secondRegistry = IndicatorRegistry()
        val first = firstRegistry.mount(listOf(definition), restoredActiveKeys = listOf(definition.key))
        val second = secondRegistry.mount(listOf(definition), restoredActiveKeys = listOf(definition.key))

        val output = IndicatorRuntime().calculate(series = seriesOfCloses(2.0, 1.0), registry = first)

        assertEquals(first.generation, second.generation)
        assertFalse(output.matches(second))
    }

    private fun definition(
        id: String,
        placement: IndicatorPlacement,
        autoActivate: Boolean = false,
        keepAlive: Boolean = false,
        paintMode: IndicatorPaintMode = IndicatorPaintMode.COMBINE,
        layoutHint: IndicatorLayoutHint = IndicatorLayoutHint(),
    ): IndicatorDefinition = IndicatorDefinition(
        key = IndicatorKey.computed(id),
        placement = placement,
        autoActivate = autoActivate,
        keepAlive = keepAlive,
        paintMode = paintMode,
        layoutHint = layoutHint,
        calculator = MovingAverage(listOf(2)),
    )

    private fun seriesOfCloses(vararg closes: Double): KlineSeries = KlineSeries.of(
        closes.mapIndexed { index, close ->
            KlineCandle(
                timestampMillis = (closes.size - index).toLong(),
                open = close,
                high = close,
                low = close,
                close = close,
                volume = close,
            )
        },
    )
}
