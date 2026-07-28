/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Rect
import com.zhumeng.kanvas.core.IndicatorDefinition
import com.zhumeng.kanvas.core.IndicatorColumn
import com.zhumeng.kanvas.core.IndicatorInsets
import com.zhumeng.kanvas.core.IndicatorKey
import com.zhumeng.kanvas.core.IndicatorOutput
import com.zhumeng.kanvas.core.IndicatorPaintMode
import com.zhumeng.kanvas.core.IndicatorPlacement
import com.zhumeng.kanvas.core.IndicatorRegistry
import com.zhumeng.kanvas.core.IndicatorRuntime
import com.zhumeng.kanvas.core.IndicatorRuntimeSnapshot
import com.zhumeng.kanvas.core.KlineCandle
import com.zhumeng.kanvas.core.KlineSeries
import com.zhumeng.kanvas.core.KlineUiState
import com.zhumeng.kanvas.core.KlineViewport
import com.zhumeng.kanvas.core.MovingAverage
import com.zhumeng.kanvas.core.Volume
import com.zhumeng.kanvas.core.IndexRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KlineIndicatorPanePlanTest {
    @Test
    fun `custom Direct renderer receives active declaration without a runtime snapshot`() {
        val direct = IndicatorDefinition(key = IndicatorKey.direct("trade_marks"))
        val registry = IndicatorRegistry()
        val selected = registry.mount(listOf(direct), restoredActiveKeys = listOf(direct.key))
        val series = series()
        val snapshot: IndicatorRuntimeSnapshot? = null
        val renderers = KlineIndicatorRendererRegistry(listOf(DirectTestRenderer))

        val plan = snapshot.resolveIndicatorPanePlan(KlineUiState(series = series), selected, renderers)

        assertEquals(listOf(direct.key), plan.mainCombine.map { item -> item.definition.key })
        assertEquals(null, plan.mainCombine.single().output)
        assertTrue(plan.unsupportedDefinitions.isEmpty())
    }

    @Test
    fun `stale computed output preserves active Direct and Computed renderer geometry`() {
        val direct = IndicatorDefinition(key = IndicatorKey.direct("trade_marks"))
        val computed = computed("pending", IndicatorPlacement.Main)
        val registry = IndicatorRegistry()
        val selected = registry.mount(
            definitions = listOf(direct, computed),
            restoredActiveKeys = listOf(direct.key, computed.key),
        )
        val series = series()
        val calculated = IndicatorRuntime().calculate(series = series, registry = selected)
        registry.notifySpecChanged()
        val staleRegistry = registry.snapshot()

        val plan = calculated.resolveIndicatorPanePlan(
            state = KlineUiState(series = series),
            registry = staleRegistry,
            renderers = KlineIndicatorRendererRegistry(
                listOf(DirectTestRenderer, KlineVolumeIndicatorRenderer, KlineComputedLineIndicatorRenderer),
            ),
        )

        assertEquals(listOf(direct.key, computed.key), plan.mainPaintOrder.map { it.definition.key })
        assertTrue(plan.mainPaintOrder.all { it.output == null })
        assertTrue(plan.unsupportedDefinitions.isEmpty())
    }

    @Test
    fun `pending Computed sub retains its Adapt pane for null and stale snapshots`() {
        val definition = computed("pending_sub", IndicatorPlacement.Sub()).copy(
            layoutHint = com.zhumeng.kanvas.core.IndicatorLayoutHint(height = 37f),
        )
        val registry = IndicatorRegistry()
        val selected = registry.mount(listOf(definition), restoredActiveKeys = listOf(definition.key))
        val state = KlineUiState(series = series())
        val missing: IndicatorRuntimeSnapshot? = null
        val calculated = IndicatorRuntime().calculate(series = state.series, registry = selected)
        registry.notifySpecChanged()
        val stale = registry.snapshot()

        listOf(
            missing.resolveIndicatorPanePlan(state, selected, KlineIndicatorRendererRegistry.Default),
            calculated.resolveIndicatorPanePlan(state, stale, KlineIndicatorRendererRegistry.Default),
        ).forEach { plan ->
            assertEquals(listOf("sub:computed:pending_sub"), plan.subByPane.keys.toList())
            assertEquals(null, plan.subByPane.values.single().single().output)
            assertTrue(plan.unsupportedDefinitions.isEmpty())
            val layout = resolveChartLayout(
                canvasSize = androidx.compose.ui.geometry.Size(500f, 100f),
                axisWidthPx = 30f,
                timeAxisHeightPx = 15f,
                densityScale = 1f,
                paneConfig = KlinePaneRenderConfig(mode = KlineLayoutMode.Adapt),
                subPaneSpecs = plan.subPaneSpecs,
            )
            assertEquals(37f, layout.subPanes.single().resolvedHeightPx)
            assertEquals(352f, layout.requiredHeightPx)
        }
    }

    @Test
    fun `output dependent custom renderer can reserve a pending pane explicitly`() {
        val definition = computed("pending_custom", IndicatorPlacement.Sub())
        val registry = IndicatorRegistry()
        val selected = registry.mount(listOf(definition), restoredActiveKeys = listOf(definition.key))
        val renderer = object : KlineIndicatorRenderer {
            override fun supports(definition: IndicatorDefinition, output: IndicatorOutput?): Boolean =
                definition.key.id == "pending_custom" && output != null

            override fun supportsPending(definition: IndicatorDefinition): Boolean =
                definition.key.id == "pending_custom"

            override fun draw(scope: DrawScope, context: KlineIndicatorDrawContext) = Unit
        }

        val plan = (null as IndicatorRuntimeSnapshot?).resolveIndicatorPanePlan(
            state = KlineUiState(series = series()),
            registry = selected,
            renderers = KlineIndicatorRendererRegistry(listOf(renderer)),
        )

        assertEquals(listOf("sub:computed:pending_custom"), plan.subByPane.keys.toList())
        assertEquals(null, plan.subByPane.values.single().single().output)
        assertTrue(plan.unsupportedDefinitions.isEmpty())
    }

    @Test
    fun `pending Computed without a null-output renderer is immediately unsupported`() {
        val definition = computed("unrendered_pending", IndicatorPlacement.Sub())
        val registry = IndicatorRegistry()
        val selected = registry.mount(listOf(definition), restoredActiveKeys = listOf(definition.key))

        val plan = (null as IndicatorRuntimeSnapshot?).resolveIndicatorPanePlan(
            state = KlineUiState(series = series()),
            registry = selected,
            renderers = KlineIndicatorRendererRegistry(emptyList()),
        )

        assertTrue(plan.subByPane.isEmpty())
        assertEquals(listOf(definition.key), plan.unsupportedDefinitions.map(IndicatorDefinition::key))
    }

    @Test
    fun `unsupported active declaration does not allocate an empty Canvas pane`() {
        val direct = IndicatorDefinition(key = IndicatorKey.direct("unrendered"), placement = IndicatorPlacement.Sub("direct"))
        val registry = IndicatorRegistry()
        val selected = registry.mount(listOf(direct), restoredActiveKeys = listOf(direct.key))
        val series = series()
        val snapshot = IndicatorRuntime().calculate(series = series, registry = selected)

        val plan = snapshot.resolveIndicatorPanePlan(
            state = KlineUiState(series = series),
            registry = selected,
            renderers = KlineIndicatorRendererRegistry(emptyList()),
        )

        assertTrue(plan.subByPane.isEmpty())
        assertEquals(listOf(direct.key), plan.unsupportedDefinitions.map(IndicatorDefinition::key))
    }

    @Test
    fun `main combine and alone declarations are separated before range layout`() {
        val combine = computed("combine", IndicatorPlacement.Main)
        val alone = computed(
            id = "alone",
            placement = IndicatorPlacement.Main,
            paintMode = IndicatorPaintMode.ALONE,
        )
        val registry = IndicatorRegistry()
        val selected = registry.mount(listOf(combine, alone), restoredActiveKeys = listOf(combine.key, alone.key))
        val series = series()
        val snapshot = IndicatorRuntime().calculate(series = series, registry = selected)

        val plan = snapshot.resolveIndicatorPanePlan(KlineUiState(series = series), selected, KlineIndicatorRendererRegistry.Default)

        assertEquals(listOf(combine.key), plan.mainCombine.map { item -> item.definition.key })
        assertEquals(listOf(alone.key), plan.mainAlone.map { item -> item.definition.key })
        assertEquals(listOf(combine.key), plan.mainCombineOutputs.map { output -> output.key })
    }

    @Test
    fun `Candle chart phase is globally ordered between combine and alone renderers`() {
        val combine = computed("combine_top", IndicatorPlacement.Main).copy(zIndex = 10)
        val alone = computed(
            id = "alone_bottom",
            placement = IndicatorPlacement.Main,
            paintMode = IndicatorPaintMode.ALONE,
        ).copy(zIndex = -10)
        val registry = IndicatorRegistry()
        val selected = registry.mount(
            listOf(combine, alone),
            restoredActiveKeys = listOf(combine.key, alone.key),
        )
        val series = series()
        val snapshot = IndicatorRuntime().calculate(series = series, registry = selected)

        val plan = snapshot.resolveIndicatorPanePlan(KlineUiState(series = series), selected, KlineIndicatorRendererRegistry.Default)

        assertEquals(listOf(alone.key, combine.key), plan.mainPaintOrder.map { it.definition.key })
        val paintOrder = resolveMainPaintOrder(plan.mainPaintOrder, candleZIndex = -1)
        assertEquals(listOf(alone.key), paintOrder.beforeCandle.map { it.definition.key })
        assertEquals(listOf(combine.key), paintOrder.afterCandle.map { it.definition.key })
    }

    @Test
    fun `main renderer equal to Candle z-index draws after the Candle chart phase`() {
        val below = computed("below_candle", IndicatorPlacement.Main).copy(zIndex = -2)
        val equal = computed("equal_candle", IndicatorPlacement.Main).copy(zIndex = -1)
        val above = computed("above_candle", IndicatorPlacement.Main).copy(zIndex = 0)
        val registry = IndicatorRegistry()
        val selected = registry.mount(
            listOf(below, equal, above),
            restoredActiveKeys = listOf(below.key, equal.key, above.key),
        )
        val state = KlineUiState(series = series())
        val plan = IndicatorRuntime().calculate(series = state.series, registry = selected)
            .resolveIndicatorPanePlan(state, selected, KlineIndicatorRendererRegistry.Default)

        val paintOrder = resolveMainPaintOrder(plan.mainPaintOrder, candleZIndex = -1)

        assertEquals(listOf(below.key), paintOrder.beforeCandle.map { it.definition.key })
        assertEquals(listOf(equal.key, above.key), paintOrder.afterCandle.map { it.definition.key })
    }

    @Test
    fun `registry FIFO order controls sub pane plan while default renderers select volume first`() {
        val first = computed("first", IndicatorPlacement.Sub("first-pane"))
        val volume = IndicatorDefinition(
            key = IndicatorKey.computed("volume"),
            placement = IndicatorPlacement.Sub("volume-pane"),
            calculator = Volume,
        )
        val registry = IndicatorRegistry()
        val selected = registry.mount(
            definitions = listOf(first, volume),
            restoredActiveKeys = listOf(volume.key, first.key),
        )
        val series = series()
        val snapshot = IndicatorRuntime().calculate(series = series, registry = selected)

        val plan = snapshot.resolveIndicatorPanePlan(KlineUiState(series = series), selected, KlineIndicatorRendererRegistry.Default)

        assertEquals(listOf("volume-pane", "first-pane"), plan.subByPane.keys.toList())
        assertSame(KlineVolumeIndicatorRenderer, plan.subByPane.getValue("volume-pane").single().renderer)
        assertSame(KlineComputedLineIndicatorRenderer, plan.subByPane.getValue("first-pane").single().renderer)
    }

    @Test
    fun `default Volume renderer does not claim arbitrary multi-column outputs`() {
        val definition = IndicatorDefinition(
            key = IndicatorKey.computed("volume_with_signal"),
            placement = IndicatorPlacement.Sub("volume-pane"),
            calculator = Volume,
        )
        val output = IndicatorOutput.of(
            key = definition.key,
            seriesSize = 2,
            columns = listOf(
                IndicatorColumn.of(Volume.ColumnName, listOf(10.0, 8.0)),
                IndicatorColumn.of("signal", listOf(3.0, 2.0)),
            ),
        )

        assertFalse(KlineVolumeIndicatorRenderer.supports(definition, output))
        assertTrue(KlineComputedLineIndicatorRenderer.supports(definition, output))
    }

    @Test
    fun `ordinary sub declarations receive separate panes by default`() {
        val first = computed("first_default_sub", IndicatorPlacement.Sub())
        val second = computed("second_default_sub", IndicatorPlacement.Sub())
        val registry = IndicatorRegistry()
        val selected = registry.mount(
            definitions = listOf(first, second),
            restoredActiveKeys = listOf(second.key, first.key),
        )
        val series = series()
        val snapshot = IndicatorRuntime().calculate(series = series, registry = selected)

        val plan = snapshot.resolveIndicatorPanePlan(KlineUiState(series = series), selected, KlineIndicatorRendererRegistry.Default)

        assertEquals(
            listOf("sub:computed:second_default_sub", "sub:computed:first_default_sub"),
            plan.subByPane.keys.toList(),
        )
        assertTrue(plan.subByPane.values.all { it.size == 1 })
    }

    @Test
    fun `sub pane resolves its indicator height and padding before host defaults`() {
        val definition = computed("sized_sub", IndicatorPlacement.Sub()).copy(
            layoutHint = com.zhumeng.kanvas.core.IndicatorLayoutHint(
                height = 70f,
                minHeight = 35f,
                padding = IndicatorInsets(left = 3f, top = 4f, right = 5f, bottom = 6f),
            ),
        )
        val registry = IndicatorRegistry()
        val selected = registry.mount(listOf(definition), restoredActiveKeys = listOf(definition.key))
        val series = series()
        val snapshot = IndicatorRuntime().calculate(series = series, registry = selected)
        val plan = snapshot.resolveIndicatorPanePlan(KlineUiState(series = series), selected, KlineIndicatorRendererRegistry.Default)

        val layout = resolveChartLayout(
            canvasSize = androidx.compose.ui.geometry.Size(800f, 800f),
            axisWidthPx = 64f,
            timeAxisHeightPx = 30f,
            densityScale = 2f,
            paneConfig = KlinePaneRenderConfig(),
            subPaneSpecs = plan.subPaneSpecs,
        )
        val pane = layout.subPanes.single()

        assertEquals(140f, pane.requestedHeightPx)
        assertEquals(140f, pane.resolvedHeightPx)
        assertEquals(pane.outerRect.left + 6f, pane.plotRect.left)
        assertEquals(pane.outerRect.top + 8f, pane.plotRect.top)
        assertEquals(pane.outerRect.right - 10f, pane.plotRect.right)
        assertEquals(pane.outerRect.bottom - 12f, pane.plotRect.bottom)
    }

    @Test
    fun `adapt chart layout keeps an indicator own height below the fixed minimum`() {
        val definition = computed("adapt_small", IndicatorPlacement.Sub()).copy(
            layoutHint = com.zhumeng.kanvas.core.IndicatorLayoutHint(height = 10f),
        )
        val registry = IndicatorRegistry()
        val selected = registry.mount(listOf(definition), restoredActiveKeys = listOf(definition.key))
        val series = series()
        val snapshot = IndicatorRuntime().calculate(series = series, registry = selected)
        val plan = snapshot.resolveIndicatorPanePlan(KlineUiState(series = series), selected, KlineIndicatorRendererRegistry.Default)

        val layout = resolveChartLayout(
            canvasSize = androidx.compose.ui.geometry.Size(800f, 100f),
            axisWidthPx = 64f,
            timeAxisHeightPx = 15f,
            densityScale = 1f,
            paneConfig = KlinePaneRenderConfig(mode = KlineLayoutMode.Adapt),
            subPaneSpecs = plan.subPaneSpecs,
        )

        assertEquals(10f, layout.subPanes.single().resolvedHeightPx)
        assertEquals(325f, layout.requiredHeightPx)
    }

    @Test
    fun `explicit shared pane merges geometry requirements instead of retaining the first declaration`() {
        val first = computed("shared_first", IndicatorPlacement.Sub("shared")).copy(
            layoutHint = com.zhumeng.kanvas.core.IndicatorLayoutHint(
                height = 40f,
                minHeight = 20f,
                padding = IndicatorInsets(left = 2f, top = 3f),
            ),
        )
        val second = computed("shared_second", IndicatorPlacement.Sub("shared")).copy(
            layoutHint = com.zhumeng.kanvas.core.IndicatorLayoutHint(
                height = 70f,
                minHeight = 30f,
                padding = IndicatorInsets(right = 4f, bottom = 5f),
            ),
        )
        val registry = IndicatorRegistry()
        val selected = registry.mount(
            definitions = listOf(first, second),
            restoredActiveKeys = listOf(first.key, second.key),
        )
        val snapshot = IndicatorRuntime().calculate(series = series(), registry = selected)

        val plan = snapshot.resolveIndicatorPanePlan(
            KlineUiState(series = series()),
            selected,
            KlineIndicatorRendererRegistry.Default,
        )
        val layout = resolveChartLayout(
            canvasSize = androidx.compose.ui.geometry.Size(300f, 300f),
            axisWidthPx = 20f,
            timeAxisHeightPx = 15f,
            densityScale = 1f,
            paneConfig = KlinePaneRenderConfig(mode = KlineLayoutMode.Adapt),
            subPaneSpecs = plan.subPaneSpecs,
        )
        val pane = layout.subPanes.single()

        assertEquals(70f, pane.resolvedHeightPx)
        assertEquals(2f, pane.plotRect.left)
        assertEquals(pane.outerRect.top + 3f, pane.plotRect.top)
        assertEquals(pane.outerRect.right - 4f, pane.plotRect.right)
        assertEquals(pane.outerRect.bottom - 5f, pane.plotRect.bottom)
    }

    @Test
    fun `shared pane preserves host fallback required by a null member hint`() {
        val inherited = computed("shared_inherited", IndicatorPlacement.Sub("shared_fallback"))
        val explicit = computed("shared_explicit", IndicatorPlacement.Sub("shared_fallback")).copy(
            layoutHint = com.zhumeng.kanvas.core.IndicatorLayoutHint(
                height = 10f,
                padding = IndicatorInsets.Zero,
            ),
        )
        val registry = IndicatorRegistry()
        val selected = registry.mount(
            definitions = listOf(inherited, explicit),
            restoredActiveKeys = listOf(inherited.key, explicit.key),
        )
        val state = KlineUiState(series = series())
        val plan = IndicatorRuntime().calculate(series = state.series, registry = selected)
            .resolveIndicatorPanePlan(state, selected, KlineIndicatorRendererRegistry.Default)
        val layout = resolveChartLayout(
            canvasSize = androidx.compose.ui.geometry.Size(300f, 200f),
            axisWidthPx = 20f,
            timeAxisHeightPx = 15f,
            densityScale = 1f,
            paneConfig = KlinePaneRenderConfig(mode = KlineLayoutMode.Adapt),
            subPaneSpecs = plan.subPaneSpecs,
        )
        val pane = layout.subPanes.single()

        assertEquals(60f, pane.resolvedHeightPx)
        assertEquals(pane.outerRect.top + 12f, pane.plotRect.top)
        assertEquals(pane.outerRect.left, pane.plotRect.left)
        assertEquals(pane.outerRect.right, pane.plotRect.right)
    }

    @Test
    fun `sub pane ids reserve the built-in main and time panes`() {
        assertFailsWith<IllegalArgumentException> { IndicatorPlacement.Sub("main") }
        assertFailsWith<IllegalArgumentException> { IndicatorPlacement.Sub("time") }
    }

    @Test
    fun `main standalone pane uses its own height and padding against the main drawable rect`() {
        val definition = computed(
            id = "alone_geometry",
            placement = IndicatorPlacement.Main,
            paintMode = IndicatorPaintMode.ALONE,
        ).copy(
            layoutHint = com.zhumeng.kanvas.core.IndicatorLayoutHint(
                height = 40f,
                padding = IndicatorInsets(left = 5f, top = 6f, right = 7f, bottom = 8f),
            ),
        )
        val item = KlineIndicatorRenderItem(
            definition = definition,
            output = null,
            renderer = DirectTestRenderer,
            declarationOrder = 0,
        )
        val main = KlinePaneLayout(
            id = "main",
            outerRect = Rect(0f, 0f, 300f, 260f),
            plotRect = Rect(10f, 20f, 280f, 240f),
            requestedHeightPx = 260f,
            resolvedHeightPx = 260f,
        )

        val alone = resolveMainAlonePane(main, item, densityScale = 2f)

        assertEquals(10f, alone.plotRect.left)
        assertEquals(286f, alone.plotRect.right)
        assertEquals(164f, alone.plotRect.top)
        assertEquals(244f, alone.plotRect.bottom)
        assertEquals(80f, alone.resolvedHeightPx)
        assertEquals(main.outerRect, alone.outerRect)
    }

    @Test
    fun `renderer-owned range keeps an External indicator drawable without a computed output`() {
        val external = IndicatorDefinition(key = IndicatorKey.external("orders"))
        val registry = IndicatorRegistry()
        val selected = registry.mount(listOf(external), restoredActiveKeys = listOf(external.key))
        val state = KlineUiState(series = series())
        val snapshot: IndicatorRuntimeSnapshot? = null
        val renderer = object : KlineIndicatorRenderer {
            override fun supports(
                definition: IndicatorDefinition,
                output: com.zhumeng.kanvas.core.IndicatorOutput?,
            ): Boolean = definition.key == external.key

            override fun visibleValueRange(context: KlineIndicatorRangeContext): KlineIndicatorValueRange =
                KlineIndicatorValueRange(1000.0, 2000.0)

            override fun draw(scope: DrawScope, context: KlineIndicatorDrawContext) = Unit
        }

        val plan = snapshot.resolveIndicatorPanePlan(
            state = state,
            registry = selected,
            renderers = KlineIndicatorRendererRegistry(listOf(renderer)),
        )
        val contributions = plan.mainCombine.rendererValueRanges(
            state = state,
            paintRange = IndexRange(0, state.series.size),
            viewport = KlineViewport(),
        )
        val range = state.series.candles.valueRangeIncluding(
            mainOutputs = emptyList(),
            paintRange = IndexRange(0, state.series.size),
            rendererRanges = contributions,
        )

        assertEquals(listOf(KlineIndicatorValueRange(1000.0, 2000.0)), contributions)
        assertTrue(range.maximum > 2000.0)
    }

    private fun computed(
        id: String,
        placement: IndicatorPlacement,
        paintMode: IndicatorPaintMode = IndicatorPaintMode.COMBINE,
    ): IndicatorDefinition = IndicatorDefinition(
        key = IndicatorKey.computed(id),
        placement = placement,
        paintMode = paintMode,
        calculator = MovingAverage(listOf(2)),
    )

    private fun series(): KlineSeries = KlineSeries.of(
        listOf(4.0, 3.0, 2.0, 1.0).mapIndexed { index, close ->
            KlineCandle(
                timestampMillis = (4 - index).toLong(),
                open = close,
                high = close,
                low = close,
                close = close,
                volume = close * 10.0,
            )
        },
    )

    private object DirectTestRenderer : KlineIndicatorRenderer {
        override fun supports(definition: IndicatorDefinition, output: com.zhumeng.kanvas.core.IndicatorOutput?): Boolean =
            definition.key.id == "trade_marks"

        override fun draw(scope: DrawScope, context: KlineIndicatorDrawContext) = Unit
    }
}
