/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.zhumeng.kanvas.core.IndicatorDefinition
import com.zhumeng.kanvas.core.IndicatorKey
import com.zhumeng.kanvas.core.IndicatorOutput
import com.zhumeng.kanvas.core.IndicatorPlacement
import com.zhumeng.kanvas.core.KlineUiState
import com.zhumeng.kanvas.core.MovingAverage
import com.zhumeng.kanvas.core.AverageValueLine
import com.zhumeng.kanvas.core.OnBalanceVolumeWithAverages
import com.zhumeng.kanvas.core.RelativeStrengthIndexes
import com.zhumeng.kanvas.core.ParabolicSar
import com.zhumeng.kanvas.core.StochasticRsi
import com.zhumeng.kanvas.core.SuperTrend
import com.zhumeng.kanvas.core.WilliamsR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KlineIndicatorPluginTest {
    @Test
    fun `native binding stores typed config and creates MA definition`() {
        val plugin = KlineMovingAverageIndicatorPlugin(id = "ma", label = "Moving Average")
        val config = KlineMovingAverageIndicatorConfig(
            periods = listOf(5, 21),
            placement = IndicatorPlacement.Main,
            zIndex = 3,
            lineStyles = listOf(
                KlineIndicatorLineStyle(Color.Yellow, 2f),
                KlineIndicatorLineStyle(Color.Magenta, 3f),
            ),
        )

        val binding = plugin.bind(config)

        assertEquals(plugin.key, binding.definition.key)
        assertEquals(config, binding.definition.configuration)
        assertEquals(config, binding.definition.requirePluginConfig<KlineMovingAverageIndicatorConfig>())
        assertEquals("5,21", binding.definition.parameters["periods"])
        assertEquals("2.0,3.0", binding.definition.parameters["lineWidths"])
        assertEquals(config.lineStyles, binding.definition.requirePluginConfig<KlineMovingAverageIndicatorConfig>().lineStyles)
        assertEquals(MovingAverage(listOf(5, 21)), binding.definition.calculator)
        assertSame(KlineComputedLineIndicatorRenderer, binding.renderer)
    }

    @Test
    fun `indicator line style rejects invalid widths`() {
        assertFailsWith<IllegalArgumentException> { KlineIndicatorLineStyle(widthPx = 0f) }
        assertFailsWith<IllegalArgumentException> { KlineIndicatorLineStyle(widthPx = Float.NaN) }
    }

    @Test
    fun `advanced plugins bind calculators placements and typed parameters`() {
        val sar = KlineSarIndicatorPlugin().bind(KlineSarIndicatorConfig(step = 0.03, maximum = 0.24))
        val avl = KlineAvlIndicatorPlugin().bind()
        val superTrend = KlineSuperTrendIndicatorPlugin().bind(KlineSuperTrendIndicatorConfig(7, 2.5))
        val obv = KlineObvIndicatorPlugin().bind(KlineObvIndicatorConfig(maPeriod = 7, emaPeriod = 10))
        val rsi = KlineRsiIndicatorPlugin().bind(KlineRsiIndicatorConfig(periods = listOf(6, 14, 24)))
        val wr = KlineWrIndicatorPlugin().bind(KlineSinglePeriodIndicatorConfig(21, IndicatorPlacement.Sub("wr")))
        val stoch = KlineStochasticRsiIndicatorPlugin().bind(KlineStochasticRsiIndicatorConfig(12, 10, 4, 2))

        assertTrue(sar.definition.calculator is ParabolicSar)
        assertSame(KlineSarIndicatorRenderer, sar.renderer)
        assertEquals("0.03", sar.definition.parameters["step"])
        assertTrue(avl.definition.calculator is AverageValueLine)
        assertEquals(IndicatorPlacement.Main, avl.definition.placement)
        assertTrue(superTrend.definition.calculator is SuperTrend)
        assertEquals("2.5", superTrend.definition.parameters["multiplier"])
        assertTrue(obv.definition.calculator is OnBalanceVolumeWithAverages)
        assertEquals(7, (obv.definition.calculator as OnBalanceVolumeWithAverages).maPeriod)
        assertEquals(listOf(6, 14, 24), (rsi.definition.calculator as RelativeStrengthIndexes).periods)
        assertEquals(IndicatorPlacement.Sub("obv"), obv.definition.placement)
        assertTrue(wr.definition.calculator is WilliamsR)
        assertTrue(stoch.definition.calculator is StochasticRsi)
        assertEquals("12,10,4,2", stoch.definition.parameters["periods"])

        val catalog = KlineIndicatorPluginCatalog.of(sar, avl, superTrend, obv, wr, stoch)
        assertEquals(6, catalog.definitions.size)
    }

    @Test
    fun `binding rejects a plugin that returns another kind or id`() {
        val plugin = object : KlineIndicatorPlugin<KlineEmptyIndicatorPluginConfig> {
            override val key: IndicatorKey = IndicatorKey.computed("expected")
            override val defaultConfig: KlineEmptyIndicatorPluginConfig = KlineEmptyIndicatorPluginConfig

            override fun createDefinition(config: KlineEmptyIndicatorPluginConfig): IndicatorDefinition =
                IndicatorDefinition(
                    key = IndicatorKey.computed("wrong"),
                    calculator = MovingAverage(listOf(2)),
                )
        }

        assertFailsWith<IllegalArgumentException> { plugin.bind() }
    }

    @Test
    fun `catalog rejects duplicate keys and scopes greedy renderer and factory`() {
        val firstKey = IndicatorKey.direct("first")
        val secondKey = IndicatorKey.direct("second")
        val nativeRenderer = GreedyRenderer()
        val nativeFactory = GreedyFactory()
        val firstPlugin = simplePlugin(firstKey, nativeRenderer, nativeFactory)
        val secondPlugin = simplePlugin(secondKey)
        val first = firstPlugin.bind()
        val second = secondPlugin.bind()

        assertFailsWith<IllegalArgumentException> {
            KlineIndicatorPluginCatalog.of(first, first)
        }

        val fallbackRenderer = GreedyRenderer()
        val fallbackFactory = GreedyFactory()
        val registry = KlineIndicatorPluginCatalog.of(first, second).createRendererRegistry(
            fallback = KlineIndicatorRendererRegistry(
                renderers = listOf(fallbackRenderer),
                statefulFactories = listOf(fallbackFactory),
            ),
        )

        val scopedRenderer = assertNotNull(registry.resolve(first.definition, output = null))
        assertTrue(scopedRenderer is KlineIndicatorTopTipsRenderer)
        assertSame(fallbackRenderer, registry.resolve(second.definition, output = null))
        assertNotNull(registry.resolveStatefulFactory(first.definition))
        assertSame(fallbackFactory, registry.resolveStatefulFactory(second.definition))
    }

    @Test
    fun `native runtime preserves stateful instance across typed config update`() {
        val events = mutableListOf<String>()
        val plugin = object : KlineIndicatorPlugin<StatefulConfig> {
            override val key: IndicatorKey = IndicatorKey.direct("stateful")
            override val defaultConfig: StatefulConfig = StatefulConfig(1)

            override fun createDefinition(config: StatefulConfig): IndicatorDefinition =
                IndicatorDefinition(key = key, keepAlive = true)

            override fun createStatefulRendererFactory(): KlineStatefulIndicatorRendererFactory =
                object : KlineStatefulIndicatorRendererFactory {
                    override fun supports(definition: IndicatorDefinition): Boolean = true

                    override fun create(definition: IndicatorDefinition): KlineStatefulIndicatorRenderer =
                        RecordingStatefulRenderer(events)
                }
        }
        val catalog = KlineIndicatorPluginCatalog.of(plugin.bind())
        val runtime = catalog.createChartRuntime(activeKeys = listOf(plugin.key))
        try {
            val initial = runtime.indicatorRegistry.snapshot()
            runtime.indicatorRendererLifecycleHost.reconcile(KlineUiState(), initial)
            val first = runtime.indicatorRendererLifecycleHost.resolve(
                initial.definition(plugin.key)!!,
                output = null,
            )

            val updatedSnapshot = runtime.indicatorRegistry.upsert(plugin.bind(StatefulConfig(2)).definition)
            runtime.indicatorRendererLifecycleHost.reconcile(KlineUiState(), updatedSnapshot)
            val second = runtime.indicatorRendererLifecycleHost.resolve(
                updatedSnapshot.definition(plugin.key)!!,
                output = null,
            )
            val hidden = runtime.indicatorRegistry.hide(plugin.key)
            runtime.indicatorRendererLifecycleHost.reconcile(KlineUiState(), hidden)
            val shown = runtime.indicatorRegistry.show(plugin.key)
            runtime.indicatorRendererLifecycleHost.reconcile(KlineUiState(), shown)

            assertSame(first, second)
            assertEquals(
                listOf("init:1", "attach:1", "update:1->2", "detach:2", "attach:2"),
                events,
            )
        } finally {
            runtime.close()
        }
        assertEquals("dispose:2", events.last())
    }

    private fun simplePlugin(
        key: IndicatorKey,
        renderer: KlineIndicatorRenderer? = null,
        factory: KlineStatefulIndicatorRendererFactory? = null,
    ): KlineIndicatorPlugin<KlineEmptyIndicatorPluginConfig> =
        object : KlineIndicatorPlugin<KlineEmptyIndicatorPluginConfig> {
            override val key: IndicatorKey = key
            override val defaultConfig: KlineEmptyIndicatorPluginConfig = KlineEmptyIndicatorPluginConfig

            override fun createDefinition(config: KlineEmptyIndicatorPluginConfig): IndicatorDefinition =
                IndicatorDefinition(key = key)

            override fun createRenderer(): KlineIndicatorRenderer? = renderer

            override fun createStatefulRendererFactory(): KlineStatefulIndicatorRendererFactory? = factory
        }

    private data class StatefulConfig(val revision: Int) : KlineIndicatorPluginConfig

    private class GreedyRenderer : KlineIndicatorRenderer, KlineIndicatorTopTipsRenderer {
        override fun supports(definition: IndicatorDefinition, output: IndicatorOutput?): Boolean = true

        override fun draw(scope: DrawScope, context: KlineIndicatorDrawContext) = Unit

        override fun prepareTopTips(
            context: KlineIndicatorTopTipsPrepareContext,
        ): KlineIndicatorTopTipsPrepared = error("Capability probe must not prepare Tips")

        override fun drawTopTips(
            scope: DrawScope,
            context: KlineIndicatorTopTipsDrawContext,
        ) = Unit
    }

    private class GreedyFactory : KlineStatefulIndicatorRendererFactory {
        override fun supports(definition: IndicatorDefinition): Boolean = true

        override fun create(definition: IndicatorDefinition): KlineStatefulIndicatorRenderer =
            RecordingStatefulRenderer(mutableListOf())
    }

    private class RecordingStatefulRenderer(
        private val events: MutableList<String>,
    ) : KlineStatefulIndicatorRenderer {
        private var revision: Int? = null

        override fun supports(definition: IndicatorDefinition, output: IndicatorOutput?): Boolean = true

        override fun draw(scope: DrawScope, context: KlineIndicatorDrawContext) = Unit

        override fun onInit(context: KlineIndicatorLifecycleContext) {
            revision = context.definition.requirePluginConfig<StatefulConfig>().revision
            events += "init:$revision"
        }

        override fun onAttach(context: KlineIndicatorLifecycleContext) {
            events += "attach:$revision"
        }

        override fun onUpdate(context: KlineIndicatorUpdateContext) {
            val old = context.previous.requirePluginConfig<StatefulConfig>().revision
            revision = context.current.requirePluginConfig<StatefulConfig>().revision
            events += "update:$old->$revision"
        }

        override fun onDetach(context: KlineIndicatorLifecycleContext) {
            events += "detach:$revision"
        }

        override fun onDispose(context: KlineIndicatorDisposeContext) {
            events += "dispose:$revision"
        }
    }
}
