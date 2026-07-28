/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.zhumeng.kanvas.core.IndicatorInsets
import com.zhumeng.kanvas.core.IndicatorKey
import com.zhumeng.kanvas.core.IndicatorLayoutHint
import com.zhumeng.kanvas.core.IndicatorPlacement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KlineSubPaneRenderConfigTest {
    @Test
    fun `sub pane configuration rejects system pane ids`() {
        assertFailsWith<IllegalArgumentException> { KlineSubPaneRenderConfig(id = "main") }
        assertFailsWith<IllegalArgumentException> { KlineSubPaneRenderConfig(id = "time") }
    }

    @Test
    fun `default Sub placement exposes its generated id for a named override`() {
        val key = IndicatorKey.computed("macd")
        val placement = IndicatorPlacement.Sub()
        val paneId = placement.resolvedPaneId(key)
        val override = KlineSubPaneRenderConfig(
            id = paneId,
            preferredHeight = 47.dp,
            minHeight = 11.dp,
            padding = KlinePanePadding(leftPx = 3f, topPx = 2f),
        )

        val layout = resolveChartLayout(
            canvasSize = Size(300f, 100f),
            axisWidthPx = 20f,
            timeAxisHeightPx = 15f,
            densityScale = 1f,
            paneConfig = KlinePaneRenderConfig(
                mode = KlineLayoutMode.Adapt,
                subPanes = listOf(override),
            ),
            subPaneSpecs = listOf(
                KlineIndicatorSubPaneSpec(
                    id = paneId,
                    layoutHints = listOf(
                        IndicatorLayoutHint(
                            height = 20f,
                            padding = IndicatorInsets.Zero,
                        ),
                    ),
                ),
            ),
        )

        assertEquals("sub:computed:macd", paneId)
        assertEquals(47f, layout.subPanes.single().resolvedHeightPx)
        assertEquals(3f, layout.subPanes.single().plotRect.left)
        assertEquals(layout.subPanes.single().outerRect.top + 2f, layout.subPanes.single().plotRect.top)
    }
}
