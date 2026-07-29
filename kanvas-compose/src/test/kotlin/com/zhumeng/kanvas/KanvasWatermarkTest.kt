/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KanvasWatermarkTest {
    @Test
    fun `center placement produces exactly one item`() {
        val centers = resolveKanvasWatermarkCenters(
            bounds = Rect(10f, 20f, 210f, 120f),
            itemSize = Size(80f, 24f),
            placement = KanvasWatermarkPlacement.Center,
            horizontalSpacingPx = 40f,
            verticalSpacingPx = 30f,
        )

        assertEquals(listOf(Rect(10f, 20f, 210f, 120f).center), centers)
    }

    @Test
    fun `tiled placement stays within intersecting rows and staggers them`() {
        val centers = resolveKanvasWatermarkCenters(
            bounds = Rect(0f, 0f, 300f, 140f),
            itemSize = Size(80f, 20f),
            placement = KanvasWatermarkPlacement.Tiled,
            horizontalSpacingPx = 20f,
            verticalSpacingPx = 20f,
        )

        assertTrue(centers.size > 3)
        assertEquals(40f, centers.first().x)
        assertTrue(centers.any { it.y == 50f && it.x != 40f })
        assertTrue(centers.all { it.x + 40f > 0f && it.x - 40f < 300f })
    }

    @Test
    fun `target resolves main and dynamic sub pane bounds`() {
        val main = KlinePaneLayout("main", Rect(0f, 0f, 300f, 200f), Rect(8f, 12f, 292f, 196f), 200f, 200f)
        val volume = KlinePaneLayout("volume", Rect(0f, 200f, 300f, 280f), Rect(8f, 204f, 292f, 276f), 80f, 80f)
        val layout = KlineLayout(
            canvasRect = Rect(0f, 0f, 300f, 300f),
            chartRect = Rect(0f, 0f, 300f, 300f),
            axisRect = Rect.Zero,
            mainPane = main,
            subPanes = listOf(volume),
            timePane = null,
            paneOrder = listOf(main, volume),
            dividerYPositions = listOf(200f),
            requiredHeightPx = 300f,
            fitsAvailableSize = true,
        )

        assertEquals(main.plotRect, resolveKanvasWatermarkBounds(layout, KanvasWatermarkTarget.MainPane))
        assertEquals(
            volume.plotRect,
            resolveKanvasWatermarkBounds(layout, KanvasWatermarkTarget.SubPane("volume")),
        )
        assertNull(resolveKanvasWatermarkBounds(layout, KanvasWatermarkTarget.SubPane("missing")))
    }

    @Test
    fun `configuration rejects unsafe visual values`() {
        assertFailsWith<IllegalArgumentException> {
            KanvasWatermarkConfig(
                content = KanvasWatermarkContent.Text("Kanvas"),
                alpha = 1.1f,
            )
        }
        assertFailsWith<IllegalArgumentException> { KanvasWatermarkContent.Text(" ") }
    }
}
