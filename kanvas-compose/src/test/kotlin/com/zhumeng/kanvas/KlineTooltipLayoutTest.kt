/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KlineTooltipLayoutTest {
    @Test
    fun `two column layout uses maximum label and value widths with centered row text`() {
        val layout = checkNotNull(
            layoutKlineCrossTooltip(
                plotRect = Rect(0f, 0f, 200f, 100f),
                crossX = 180f,
                itemMeasurements = listOf(
                    KlineCrossTooltipItemMeasurement(label = Size(20f, 10f), value = Size(30f, 8f)),
                    KlineCrossTooltipItemMeasurement(label = Size(10f, 6f), value = Size(40f, 12f)),
                ),
                padding = KlinePanePadding(leftPx = 3f, topPx = 4f, rightPx = 5f, bottomPx = 6f),
                margin = KlinePanePadding(leftPx = 7f, topPx = 8f),
                columnSpacingPx = 2f,
            ),
        )

        assertEquals(KlineCrossTooltipSide.Left, layout.side)
        assertEquals(62f, layout.naturalContentWidthPx)
        assertEquals(70f, layout.cardBounds.width)
        assertEquals(Rect(7f, 8f, 77f, 40f), layout.cardBounds)
        assertEquals(Rect(7f, 12f, 77f, 22f), layout.rows[0].bounds)
        assertEquals(Offset(10f, 12f), layout.rows[0].labelTopLeft)
        assertEquals(Offset(42f, 13f), layout.rows[0].valueTopLeft)
        assertEquals(Rect(7f, 22f, 77f, 34f), layout.rows[1].bounds)
        assertEquals(Offset(10f, 25f), layout.rows[1].labelTopLeft)
        assertEquals(Offset(32f, 22f), layout.rows[1].valueTopLeft)
    }

    @Test
    fun `side choice clamps to plot and stable width is bounded by available value column`() {
        val measurements = listOf(KlineCrossTooltipItemMeasurement(Size(15f, 10f), Size(15f, 10f)))
        val left = checkNotNull(
            layoutKlineCrossTooltip(
                plotRect = Rect(0f, 0f, 50f, 30f),
                crossX = 49f,
                itemMeasurements = measurements,
                padding = KlinePanePadding(leftPx = 2f, rightPx = 2f),
                margin = KlinePanePadding(leftPx = 5f),
                columnSpacingPx = 3f,
                minContentWidthPx = 100f,
            ),
        )
        val right = checkNotNull(
            layoutKlineCrossTooltip(
                plotRect = Rect(0f, 0f, 50f, 30f),
                crossX = 1f,
                itemMeasurements = measurements,
                padding = KlinePanePadding(leftPx = 2f, rightPx = 2f),
                margin = KlinePanePadding(rightPx = 6f),
                columnSpacingPx = 3f,
            ),
        )

        assertEquals(KlineCrossTooltipSide.Left, left.side)
        assertEquals(41f, left.contentWidthPx)
        assertEquals(45f, left.cardBounds.width)
        assertEquals(5f, left.cardBounds.left)
        assertEquals(KlineCrossTooltipSide.Right, right.side)
        assertEquals(7f, right.cardBounds.left)
        assertEquals(44f, right.cardBounds.right)
    }

    @Test
    fun `outer main anchor controls side and preferred position while drawable plot clamps card`() {
        val measurements = listOf(KlineCrossTooltipItemMeasurement(Size(10f, 10f), Size(10f, 10f)))
        val drawable = Rect(110f, 20f, 210f, 100f)
        val outer = Rect(100f, 20f, 220f, 100f)
        val left = checkNotNull(
            layoutKlineCrossTooltip(
                plotRect = drawable,
                anchorRect = outer,
                // The half-width anchor is translated through the outer-left
                // origin: pivot = 100 + 100 / 2 = 150.
                crossX = 151f,
                itemMeasurements = measurements,
                padding = KlinePanePadding(),
                margin = KlinePanePadding(leftPx = 5f, rightPx = 6f),
                columnSpacingPx = 0f,
            ),
        )
        val right = checkNotNull(
            layoutKlineCrossTooltip(
                plotRect = drawable,
                anchorRect = outer,
                crossX = 149f,
                itemMeasurements = measurements,
                padding = KlinePanePadding(),
                margin = KlinePanePadding(leftPx = 5f, rightPx = 6f),
                columnSpacingPx = 0f,
            ),
        )

        assertEquals(KlineCrossTooltipSide.Left, left.side)
        // Preferred left is outer.left + margin = 105, then drawable clamps it.
        assertEquals(110f, left.cardBounds.left)
        assertEquals(KlineCrossTooltipSide.Right, right.side)
        // Preferred right is outer.right - margin = 214, then drawable clamps it.
        assertEquals(190f, right.cardBounds.left)
        assertEquals(210f, right.cardBounds.right)
    }

    @Test
    fun `vertical clamp preserves tooltip origin when card is taller than drawable pane`() {
        val layout = checkNotNull(
            layoutKlineCrossTooltip(
                plotRect = Rect(0f, 0f, 100f, 20f),
                crossX = 90f,
                itemMeasurements = List(3) {
                    KlineCrossTooltipItemMeasurement(Size(10f, 10f), Size(10f, 10f))
                },
                padding = KlinePanePadding(),
                margin = KlinePanePadding(topPx = 15f),
                columnSpacingPx = 0f,
            ),
        )

        assertEquals(15f, layout.cardBounds.top)
        assertEquals(45f, layout.cardBounds.bottom)
    }

    @Test
    fun `row hit testing expands logical margin and resolves overlap in reverse row order`() {
        val layout = checkNotNull(
            layoutKlineCrossTooltip(
                plotRect = Rect(0f, 0f, 100f, 100f),
                crossX = 90f,
                itemMeasurements = listOf(
                    KlineCrossTooltipItemMeasurement(Size(10f, 5f), Size(10f, 5f)),
                    KlineCrossTooltipItemMeasurement(Size(10f, 5f), Size(10f, 5f)),
                ),
                padding = KlinePanePadding(),
                margin = KlinePanePadding(),
                columnSpacingPx = 0f,
            ),
        )

        assertEquals(1, layout.hitItemIndex(Offset(5f, 5f), hitTestMarginPx = 1f))
        assertEquals(0, layout.hitItemIndex(Offset(5f, -0.5f), hitTestMarginPx = 1f))
        assertNull(layout.hitItemIndex(Offset(50f, 50f)))
    }
}
