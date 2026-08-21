/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.zhumeng.kanvas.core.IndexRange
import com.zhumeng.kanvas.core.IndicatorColumn
import com.zhumeng.kanvas.core.IndicatorDefinition
import com.zhumeng.kanvas.core.IndicatorKey
import com.zhumeng.kanvas.core.IndicatorLayoutHint
import com.zhumeng.kanvas.core.IndicatorOutput
import com.zhumeng.kanvas.core.IndicatorPaintMode
import com.zhumeng.kanvas.core.IndicatorPlacement
import com.zhumeng.kanvas.core.KlineUiState
import com.zhumeng.kanvas.core.KlineViewport
import com.zhumeng.kanvas.core.MovingAverage
import com.zhumeng.kanvas.core.Volume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
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
    fun `volume pane range interpolates when an older page becomes visible`() {
        val smoothers = KlinePaneValueRangeSmoothers()
        val layout = layout()

        fun range(values: List<Double>, transition: KlineValueRangeTransition?): KlineValueRange {
            val volume = item(
                id = "volume",
                placement = IndicatorPlacement.Sub("first"),
                order = 0,
                calculator = Volume,
                values = values,
            )
            val plan = KlineIndicatorPanePlan(
                mainPaintOrder = emptyList(),
                subByPane = linkedMapOf("first" to listOf(volume)),
                subPaneLayoutHints = linkedMapOf("first" to listOf(IndicatorLayoutHint())),
                unsupportedDefinitions = emptyList(),
            )
            return resolveKlineIndicatorRenderFrames(
                state = KlineUiState(),
                layout = layout,
                indicatorPanePlan = plan,
                paintRange = IndexRange(0, values.size),
                mainValueRange = KlineValueRange(1.0, 10.0),
                style = KlineChartStyle(),
                densityScale = 1f,
                hideMainIndicators = false,
                subPaneRangeSmoothers = smoothers,
                subPaneRangeResetKey = "BTC",
                subPaneRangeTransition = transition,
            ).subByPane.getValue("first").single().context.valueRange
        }

        val before = range(listOf(10.0, 20.0), transition = null)
        assertEquals(KlineValueRange(-1.0, 21.0), before)
        assertEquals(
            before,
            range(
                values = listOf(10.0, 20.0, 100.0),
                transition = KlineValueRangeTransition("page-1", 0f),
            ),
        )
        assertEquals(
            KlineValueRange(-3.0, 63.0),
            range(
                values = listOf(10.0, 20.0, 100.0),
                transition = KlineValueRangeTransition("page-1", 0.5f),
            ),
        )
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

    @Test
    fun `computed line paths are translated across sub-candle pan updates`() {
        val cache = KlinePreparedIndicatorLineCache()
        val identity = Any()
        var producerCalls = 0

        fun geometry(offsetPx: Float) = cache.resolve(
            identity = identity,
            range = IndexRange(0, 3),
            plotRect = Rect(0f, 0f, 100f, 100f),
            valueRange = KlineValueRange(0.0, 4.0),
            viewport = KlineViewport(rightEdgeOffsetPx = offsetPx),
            style = KlineChartStyle(),
        ) {
            producerCalls++
            emptyList()
        }

        val first = geometry(offsetPx = 10f)
        val shifted = geometry(offsetPx = 14f)

        assertEquals(1, producerCalls)
        assertSame(first.lines, shifted.lines)
        assertEquals(4f, shifted.translationX)
    }

    @Test
    fun `computed indicator path tiles overlap endpoints and preserve gaps`() {
        val values = List(20) { index -> if (index == 10) Double.NaN else index.toDouble() }
        val column = IndicatorColumn.of("value", values)

        assertEquals(
            listOf(
                IndexRange(0, 9),
                IndexRange(8, 10),
                IndexRange(11, 20),
            ),
            resolveKlineIndicatorPathRanges(column, IndexRange(0, values.size)),
        )
    }

    private fun item(
        id: String,
        placement: IndicatorPlacement,
        order: Int,
        paintMode: IndicatorPaintMode = IndicatorPaintMode.COMBINE,
        layoutHint: IndicatorLayoutHint = IndicatorLayoutHint(),
        calculator: com.zhumeng.kanvas.core.IndicatorCalculator = MovingAverage(listOf(2)),
        values: List<Double>? = null,
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
            output = values?.let { samples ->
                IndicatorOutput.of(
                    key = definition.key,
                    seriesSize = samples.size,
                    columns = listOf(IndicatorColumn.of(Volume.ColumnName, samples)),
                )
            },
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
