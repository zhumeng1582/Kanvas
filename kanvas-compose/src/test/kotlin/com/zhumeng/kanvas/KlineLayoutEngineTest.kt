/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KlineLayoutEngineTest {
    @Test
    fun `fixed layout leaves preferred sub heights intact when there is space`() {
        val layout = KlineLayoutEngine.resolve(
            KlineLayoutSpec(
                availableSize = Size(400f, 400f),
                axisWidthPx = 60f,
                mainPane = KlinePaneSpec("main", preferredHeightPx = 180f, minHeightPx = 120f),
                subPanes = listOf(KlinePaneSpec("volume", preferredHeightPx = 80f, minHeightPx = 30f)),
                timePane = KlinePaneSpec("time", preferredHeightPx = 24f),
            ),
        )

        assertTrue(layout.fitsAvailableSize)
        assertEquals(400f, layout.canvasRect.height)
        assertEquals(296f, layout.mainPane.resolvedHeightPx)
        assertEquals(80f, layout.subPanes.single().resolvedHeightPx)
        assertEquals(0f, layout.mainPane.outerRect.top)
        assertEquals(296f, layout.timePane!!.outerRect.top)
        assertEquals(320f, layout.subPanes.single().outerRect.top)
        assertEquals(400f, layout.chartRect.width)
        assertEquals(60f, layout.axisRect.width)
        assertEquals(340f, layout.axisRect.left)
        assertEquals(400f, layout.mainPane.plotRect.right)
    }

    @Test
    fun `fixed layout compresses sub panes proportionally above their minimum`() {
        val layout = KlineLayoutEngine.resolve(
            KlineLayoutSpec(
                availableSize = Size(300f, 230f),
                axisWidthPx = 40f,
                mainPane = KlinePaneSpec("main", preferredHeightPx = 160f, minHeightPx = 100f),
                subPanes = listOf(
                    KlinePaneSpec("one", preferredHeightPx = 100f, minHeightPx = 30f),
                    KlinePaneSpec("two", preferredHeightPx = 60f, minHeightPx = 30f),
                ),
                timePane = KlinePaneSpec("time", preferredHeightPx = 20f),
            ),
        )

        assertTrue(layout.fitsAvailableSize)
        assertEquals(100f, layout.mainPane.resolvedHeightPx)
        assertEquals(65f, layout.subPanes[0].resolvedHeightPx)
        assertEquals(45f, layout.subPanes[1].resolvedHeightPx)
        assertEquals(230f, layout.paneOrder.last().outerRect.bottom)
    }

    @Test
    fun `time pane placement follows the configured position`() {
        val middle = layoutWithTime(KlineTimePanePosition.Middle)
        val bottom = layoutWithTime(KlineTimePanePosition.Bottom)

        assertEquals(listOf("main", "time", "volume"), middle.paneOrder.map(KlinePaneLayout::id))
        assertEquals(listOf("main", "volume", "time"), bottom.paneOrder.map(KlinePaneLayout::id))
    }

    @Test
    fun `adapt layout reports requested growing height`() {
        val layout = KlineLayoutEngine.resolve(
            KlineLayoutSpec(
                availableSize = Size(300f, 100f),
                axisWidthPx = 40f,
                mainPane = KlinePaneSpec("main", preferredHeightPx = 160f, minHeightPx = 120f),
                subPanes = listOf(KlinePaneSpec("volume", preferredHeightPx = 70f, minHeightPx = 30f)),
                timePane = KlinePaneSpec("time", preferredHeightPx = 20f),
                mode = KlineLayoutMode.Adapt,
            ),
        )

        assertTrue(layout.fitsAvailableSize)
        assertEquals(250f, layout.requiredHeightPx)
        assertEquals(250f, layout.canvasRect.height)
        assertEquals(160f, layout.mainPane.resolvedHeightPx)
        assertEquals(70f, layout.subPanes.single().resolvedHeightPx)
    }

    @Test
    fun `adapt layout keeps a sub indicator raw height below its fixed minimum`() {
        val layout = KlineLayoutEngine.resolve(
            KlineLayoutSpec(
                availableSize = Size(300f, 100f),
                axisWidthPx = 40f,
                mainPane = KlinePaneSpec("main", preferredHeightPx = 100f, minHeightPx = 80f),
                subPanes = listOf(KlinePaneSpec("small", preferredHeightPx = 10f, minHeightPx = 30f)),
                timePane = KlinePaneSpec("time", preferredHeightPx = 20f),
                mode = KlineLayoutMode.Adapt,
            ),
        )

        assertEquals(130f, layout.requiredHeightPx)
        assertEquals(10f, layout.subPanes.single().resolvedHeightPx)
    }

    @Test
    fun `fixed layout redistributes all sub panes when one is below the shared minimum`() {
        val layout = KlineLayoutEngine.resolve(
            KlineLayoutSpec(
                availableSize = Size(300f, 400f),
                axisWidthPx = 40f,
                mainPane = KlinePaneSpec("main", preferredHeightPx = 160f, minHeightPx = 100f),
                subPanes = listOf(
                    KlinePaneSpec("short", preferredHeightPx = 10f, minHeightPx = 30f),
                    KlinePaneSpec("tall", preferredHeightPx = 100f, minHeightPx = 30f),
                ),
                timePane = KlinePaneSpec("time", preferredHeightPx = 20f),
            ),
        )

        assertEquals(100f, layout.mainPane.resolvedHeightPx)
        assertEquals(30f, layout.subPanes[0].resolvedHeightPx)
        assertEquals(250f, layout.subPanes[1].resolvedHeightPx)
    }

    @Test
    fun `undersized fixed host is reported and never creates overlapping panes`() {
        val layout = KlineLayoutEngine.resolve(
            KlineLayoutSpec(
                availableSize = Size(300f, 100f),
                axisWidthPx = 40f,
                mainPane = KlinePaneSpec("main", preferredHeightPx = 160f, minHeightPx = 80f),
                subPanes = listOf(KlinePaneSpec("volume", preferredHeightPx = 60f, minHeightPx = 40f)),
                timePane = KlinePaneSpec("time", preferredHeightPx = 20f),
            ),
        )

        assertFalse(layout.fitsAvailableSize)
        assertEquals(100f, layout.paneOrder.last().outerRect.bottom)
        assertTrue(layout.paneOrder.zipWithNext().all { (first, second) -> first.outerRect.bottom <= second.outerRect.top })
    }

    @Test
    fun `plot rect is safely clamped by pane padding`() {
        val layout = KlineLayoutEngine.resolve(
            KlineLayoutSpec(
                availableSize = Size(120f, 80f),
                axisWidthPx = 20f,
                mainPane = KlinePaneSpec(
                    id = "main",
                    preferredHeightPx = 80f,
                    padding = KlinePanePadding(leftPx = 70f, rightPx = 70f, topPx = 50f, bottomPx = 50f),
                ),
            ),
        )

        assertEquals(0f, layout.mainPane.plotRect.width)
        assertEquals(0f, layout.mainPane.plotRect.height)
    }

    @Test
    fun `main plot horizontal geometry overlays the axis and applies logical main padding`() {
        val geometry = resolveMainPlotHorizontalGeometry(
            canvasWidthPx = 400f,
            axisWidthPx = 60f,
            mainPadding = KlinePanePadding(leftPx = 7f, rightPx = 9f),
            densityScale = 2f,
        )

        assertEquals(14f, geometry.leftPx)
        assertEquals(382f, geometry.rightPx)
        assertEquals(368f, geometry.widthPx)
        assertEquals(100f, geometry.localX(114f))
    }

    @Test
    fun `main tips inset changes only the main drawable rect and clamps at its bottom`() {
        val layout = KlineLayoutEngine.resolve(
            KlineLayoutSpec(
                availableSize = Size(300f, 220f),
                axisWidthPx = 40f,
                mainPane = KlinePaneSpec(
                    id = "main",
                    preferredHeightPx = 140f,
                    padding = KlinePanePadding(topPx = 10f, bottomPx = 5f),
                ),
                subPanes = listOf(KlinePaneSpec("volume", preferredHeightPx = 60f)),
                timePane = KlinePaneSpec("time", preferredHeightPx = 20f),
            ),
        )

        val adjusted = layout.withMainTipsInset(30.0)
        assertEquals(layout.mainPane.outerRect, adjusted.mainPane.outerRect)
        assertEquals(layout.mainPane.plotRect.top + 30f, adjusted.mainPane.plotRect.top)
        assertEquals(layout.mainPane.plotRect.bottom, adjusted.mainPane.plotRect.bottom)
        assertEquals(adjusted.mainPane, adjusted.paneOrder.first())
        assertEquals(layout.subPanes, adjusted.subPanes)

        val clamped = layout.withMainTipsInset(10_000.0)
        assertEquals(0f, clamped.mainPane.plotRect.height)
        assertFailsWith<IllegalArgumentException> { layout.withMainTipsInset(Double.NaN) }
    }

    private fun layoutWithTime(position: KlineTimePanePosition): KlineLayout =
        KlineLayoutEngine.resolve(
            KlineLayoutSpec(
                availableSize = Size(300f, 220f),
                axisWidthPx = 40f,
                mainPane = KlinePaneSpec("main", preferredHeightPx = 120f, minHeightPx = 80f),
                subPanes = listOf(KlinePaneSpec("volume", preferredHeightPx = 60f, minHeightPx = 30f)),
                timePane = KlinePaneSpec("time", preferredHeightPx = 20f),
                timePanePosition = position,
            ),
        )
}
