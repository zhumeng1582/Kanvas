/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.zhumeng.kanvas.core.IndexRange
import com.zhumeng.kanvas.core.IndicatorDefinition
import com.zhumeng.kanvas.core.IndicatorKey
import com.zhumeng.kanvas.core.IndicatorLayoutHint
import com.zhumeng.kanvas.core.IndicatorPaintMode
import com.zhumeng.kanvas.core.IndicatorPlacement
import com.zhumeng.kanvas.core.KlineUiState
import com.zhumeng.kanvas.core.MovingAverage
import com.zhumeng.kanvas.core.Volume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KlineIndicatorRenderFrameTest {
    @Test
    fun `render frames preserve main paint order and physical sub-pane order`() {
        val mainCombine = item(
            id = "main_combine",
            placement = IndicatorPlacement.Main,
            paintMode = IndicatorPaintMode.COMBINE,
            order = 0,
        )
        val mainAlone = item(
            id = "main_alone",
            placement = IndicatorPlacement.Main,
            paintMode = IndicatorPaintMode.ALONE,
            order = 1,
            layoutHint = IndicatorLayoutHint(height = 20f),
        )
        val firstSub = item("first", IndicatorPlacement.Sub("first"), order = 2)
        val secondSub = item("second", IndicatorPlacement.Sub("second"), order = 3)
        val layout = layout()
        val plan = KlineIndicatorPanePlan(
            mainPaintOrder = listOf(mainCombine, mainAlone),
            // Deliberately reverse logical map insertion: frame resolution must
            // follow the physical layout supplied to Canvas.
            subByPane = linkedMapOf("second" to listOf(secondSub), "first" to listOf(firstSub)),
            subPaneLayoutHints = linkedMapOf(
                "second" to listOf(IndicatorLayoutHint()),
                "first" to listOf(IndicatorLayoutHint()),
            ),
            unsupportedDefinitions = emptyList(),
        )

        val frames = resolveKlineIndicatorRenderFrames(
            state = KlineUiState(),
            layout = layout,
            indicatorPanePlan = plan,
            paintRange = IndexRange(0, 0),
            mainValueRange = KlineValueRange(1.0, 10.0),
            style = KlineChartStyle(),
            densityScale = 1f,
            hideMainIndicators = false,
        )

        assertEquals(listOf("main_combine", "main_alone"), frames.main.map { it.item.definition.key.id })
        assertEquals("main", frames.main[0].context.pane.id)
        assertEquals("main-alone:COMPUTED:main_alone", frames.main[1].context.pane.id)
        assertEquals(80f, frames.main[1].context.pane.plotRect.top)
        assertEquals(listOf("first", "second"), frames.subByPane.keys.toList())
        assertEquals("first", frames.subByPane.getValue("first").single().item.definition.key.id)
        assertEquals("second", frames.subByPane.getValue("second").single().item.definition.key.id)
    }

    @Test
    fun `volume pane semantic follows calculator rather than a renderer concrete type`() {
        val volume = item(
            id = "native_volume",
            placement = IndicatorPlacement.Sub("volume"),
            order = 0,
            calculator = Volume,
        )
        val nonVolume = item(
            id = "moving_average",
            placement = IndicatorPlacement.Sub("volume"),
            order = 1,
        )

        assertTrue(listOf(volume).isVolumeOnlyPane())
        assertFalse(listOf(volume, nonVolume).isVolumeOnlyPane())
    }

    @Test
    fun `zero-size panes do not become overlay cross or tap hook candidates`() {
        val main = item("main", IndicatorPlacement.Main, order = 0)
        val sub = item("sub", IndicatorPlacement.Sub("sub"), order = 1)
        val zeroMain = pane("main", 0f, 0f)
        val zeroSub = pane("sub", 0f, 0f)
        val layout = KlineLayout(
            canvasRect = Rect.Zero,
            chartRect = Rect.Zero,
            axisRect = Rect.Zero,
            mainPane = zeroMain,
            subPanes = listOf(zeroSub),
            timePane = null,
            paneOrder = listOf(zeroMain, zeroSub),
            dividerYPositions = emptyList(),
            requiredHeightPx = 0f,
            fitsAvailableSize = true,
        )
        val plan = KlineIndicatorPanePlan(
            mainPaintOrder = listOf(main),
            subByPane = linkedMapOf("sub" to listOf(sub)),
            subPaneLayoutHints = linkedMapOf("sub" to listOf(IndicatorLayoutHint())),
            unsupportedDefinitions = emptyList(),
        )

        val frames = resolveKlineIndicatorRenderFrames(
            state = KlineUiState(),
            layout = layout,
            indicatorPanePlan = plan,
            paintRange = IndexRange(0, 0),
            mainValueRange = KlineValueRange(1.0, 10.0),
            style = KlineChartStyle(),
            densityScale = 1f,
            hideMainIndicators = false,
        )

        assertTrue(frames.main.isEmpty())
        assertTrue(frames.subByPane.isEmpty())
    }

    private fun item(
        id: String,
        placement: IndicatorPlacement,
        order: Int,
        paintMode: IndicatorPaintMode = IndicatorPaintMode.COMBINE,
        layoutHint: IndicatorLayoutHint = IndicatorLayoutHint(),
        calculator: com.zhumeng.kanvas.core.IndicatorCalculator = MovingAverage(listOf(2)),
    ): KlineIndicatorRenderItem {
        val definition = IndicatorDefinition(
            key = IndicatorKey.computed(id),
            placement = placement,
            paintMode = paintMode,
            layoutHint = layoutHint,
            calculator = calculator,
        )
        return KlineIndicatorRenderItem(
            definition = definition,
            output = null,
            renderer = TestRenderer,
            declarationOrder = order,
        )
    }

    private fun layout(): KlineLayout {
        val main = pane("main", 0f, 100f)
        val first = pane("first", 100f, 150f)
        val second = pane("second", 150f, 200f)
        return KlineLayout(
            canvasRect = Rect(0f, 0f, 120f, 200f),
            chartRect = Rect(0f, 0f, 100f, 200f),
            axisRect = Rect(100f, 0f, 120f, 200f),
            mainPane = main,
            subPanes = listOf(first, second),
            timePane = null,
            paneOrder = listOf(main, first, second),
            dividerYPositions = emptyList(),
            requiredHeightPx = 200f,
            fitsAvailableSize = true,
        )
    }

    private fun pane(id: String, top: Float, bottom: Float): KlinePaneLayout = KlinePaneLayout(
        id = id,
        outerRect = Rect(0f, top, 100f, bottom),
        plotRect = Rect(0f, top, 100f, bottom),
        requestedHeightPx = bottom - top,
        resolvedHeightPx = bottom - top,
    )

    private object TestRenderer : KlineIndicatorRenderer {
        override fun supports(
            definition: IndicatorDefinition,
            output: com.zhumeng.kanvas.core.IndicatorOutput?,
        ): Boolean = true

        override fun draw(scope: DrawScope, context: KlineIndicatorDrawContext) = Unit
    }
}
