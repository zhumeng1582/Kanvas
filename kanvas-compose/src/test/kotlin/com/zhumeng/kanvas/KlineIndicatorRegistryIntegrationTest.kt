/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import com.zhumeng.kanvas.core.IndicatorDefinition
import com.zhumeng.kanvas.core.IndicatorKey
import com.zhumeng.kanvas.core.IndicatorPlacement
import com.zhumeng.kanvas.core.IndicatorRegistry
import com.zhumeng.kanvas.core.IndicatorRuntime
import com.zhumeng.kanvas.core.KlineCandle
import com.zhumeng.kanvas.core.KlineSeries
import com.zhumeng.kanvas.core.KlineUiState
import com.zhumeng.kanvas.core.MovingAverage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KlineIndicatorRegistryIntegrationTest {
    @Test
    fun `compose consumes only output matching the current registry generation`() {
        val definition = indicator("ma", IndicatorPlacement.Main)
        val registry = IndicatorRegistry()
        val selected = registry.mount(listOf(definition), restoredActiveKeys = listOf(definition.key))
        val series = series()
        val calculated = IndicatorRuntime().calculate(series = series, registry = selected)
        val state = KlineUiState(series = series)

        assertEquals(
            listOf(definition.key),
            calculated.resolveIndicatorPanePlan(state, selected, KlineIndicatorRendererRegistry.Default)
                .mainCombineOutputs
                .map { output -> output.key },
        )

        val hidden = registry.hide(definition.key)
        val staleResolution = calculated.resolveIndicatorPanePlan(
            state,
            hidden,
            KlineIndicatorRendererRegistry.Default,
        )

        assertTrue(staleResolution.mainCombine.isEmpty())
        assertTrue(staleResolution.subByPane.isEmpty())
    }

    @Test
    fun `registry sub activation order controls Compose pane order`() {
        val firstRegistered = indicator("first", IndicatorPlacement.Sub("first-pane"))
        val secondRegistered = indicator("second", IndicatorPlacement.Sub("second-pane"))
        val registry = IndicatorRegistry()
        val selected = registry.mount(
            definitions = listOf(firstRegistered, secondRegistered),
            restoredActiveKeys = listOf(secondRegistered.key, firstRegistered.key),
        )
        val series = series()
        val calculated = IndicatorRuntime().calculate(series = series, registry = selected)

        val outputs = calculated.resolveIndicatorPanePlan(
            KlineUiState(series = series),
            selected,
            KlineIndicatorRendererRegistry.Default,
        )

        assertEquals(listOf("second-pane", "first-pane"), outputs.subByPane.keys.toList())
    }

    private fun indicator(id: String, placement: IndicatorPlacement): IndicatorDefinition = IndicatorDefinition(
        key = IndicatorKey.computed(id),
        placement = placement,
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
                volume = close,
            )
        },
    )
}
