/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.zhumeng.kanvas.core.IndexRange
import com.zhumeng.kanvas.core.IndicatorDefinition
import com.zhumeng.kanvas.core.IndicatorKey
import com.zhumeng.kanvas.core.KlineUiState
import com.zhumeng.kanvas.core.KlineViewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KlineIndicatorHookDispatchTest {
    @Test
    fun `tap dispatch is main first and short circuits before sub panes`() {
        val events = mutableListOf<String>()
        val layout = layout(subOrder = listOf("sub"))
        val first = frame("main_first", layout.mainPane, RecordingTapRenderer("main_first", events, consume = false), 0)
        val second = frame("main_second", layout.mainPane, RecordingTapRenderer("main_second", events, consume = true), 1)
        val sub = frame("sub", layout.subPanes.single(), RecordingTapRenderer("sub", events, consume = true), 2)

        val consumed = dispatchKlineIndicatorTap(
            position = Offset(25f, 25f),
            frames = KlineIndicatorRenderFrames(
                main = listOf(first, second),
                subByPane = linkedMapOf("sub" to listOf(sub)),
            ),
            layout = layout,
            crosshair = null,
        )

        assertTrue(consumed)
        assertEquals(listOf("main_first", "main_second"), events)
    }

    @Test
    fun `tap dispatch follows physical sub-pane order rather than map insertion`() {
        val events = mutableListOf<String>()
        val layout = layout(subOrder = listOf("first", "second"))
        val first = frame("first", layout.subPanes[0], RecordingTapRenderer("first", events, consume = false), 0)
        val second = frame("second", layout.subPanes[1], RecordingTapRenderer("second", events, consume = false), 1)

        val consumed = dispatchKlineIndicatorTap(
            position = Offset(25f, 25f),
            frames = KlineIndicatorRenderFrames(
                main = emptyList(),
                subByPane = linkedMapOf("second" to listOf(second), "first" to listOf(first)),
            ),
            layout = layout,
            crosshair = null,
        )

        assertFalse(consumed)
        assertEquals(listOf("first", "second"), events)
    }

    private fun frame(
        id: String,
        pane: KlinePaneLayout,
        renderer: KlineIndicatorRenderer,
        order: Int,
    ): KlineIndicatorRenderFrame {
        val definition = IndicatorDefinition(key = IndicatorKey.direct(id))
        val item = KlineIndicatorRenderItem(
            definition = definition,
            output = null,
            renderer = renderer,
            declarationOrder = order,
        )
        return KlineIndicatorRenderFrame(
            item = item,
            context = KlineIndicatorDrawContext(
                state = KlineUiState(),
                definition = definition,
                output = null,
                pane = pane,
                paintRange = IndexRange(0, 0),
                valueRange = KlineValueRange(0.0, 1.0),
                viewport = KlineViewport(),
                style = KlineChartStyle(),
                colorIndex = order,
                densityScale = 1f,
            ),
        )
    }

    private fun layout(subOrder: List<String>): KlineLayout {
        val main = pane("main", top = 0f, bottom = 100f)
        val subs = subOrder.mapIndexed { index, id ->
            pane(id, top = 100f + index * 50f, bottom = 150f + index * 50f)
        }
        return KlineLayout(
            canvasRect = Rect(0f, 0f, 100f, 100f + subs.size * 50f),
            chartRect = Rect(0f, 0f, 100f, 100f + subs.size * 50f),
            axisRect = Rect(100f, 0f, 120f, 100f + subs.size * 50f),
            mainPane = main,
            subPanes = subs,
            timePane = null,
            paneOrder = listOf(main) + subs,
            dividerYPositions = emptyList(),
            requiredHeightPx = 100f + subs.size * 50f,
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

    private class RecordingTapRenderer(
        private val id: String,
        private val events: MutableList<String>,
        private val consume: Boolean,
    ) : KlineIndicatorRenderer, KlineIndicatorTapHandler {
        override fun supports(
            definition: IndicatorDefinition,
            output: com.zhumeng.kanvas.core.IndicatorOutput?,
        ): Boolean = true

        override fun draw(scope: DrawScope, context: KlineIndicatorDrawContext) = Unit

        override fun onTap(context: KlineIndicatorTapContext): Boolean {
            events += id
            return consume
        }
    }
}
