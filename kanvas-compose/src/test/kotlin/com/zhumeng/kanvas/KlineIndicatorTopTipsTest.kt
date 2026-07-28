/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KlineIndicatorTopTipsTest {
    @Test
    fun `main tips stack in paint order and null height does not move the next item`() {
        val drawable = Rect(10f, 20f, 110f, 80f)

        val layout = layoutKlineIndicatorTopTipsStack(
            drawableRect = drawable,
            claimedHeightsPx = listOf(12f, null, 8f),
        )

        assertEquals(
            listOf(
                drawable,
                Rect(10f, 32f, 110f, 80f),
                Rect(10f, 32f, 110f, 80f),
            ),
            layout.availableRects,
        )
        assertEquals(
            listOf(
                Rect(10f, 20f, 110f, 32f),
                null,
                Rect(10f, 32f, 110f, 40f),
            ),
            layout.claimedRects,
        )
        assertEquals(20.0, layout.totalClaimedHeightPx)
    }

    @Test
    fun `available top clamps to bottom but claimed height and total remain raw`() {
        val drawable = Rect(0f, 5f, 100f, 25f)

        val layout = layoutKlineIndicatorTopTipsStack(
            drawableRect = drawable,
            claimedHeightsPx = listOf(30f, 7f),
        )

        assertEquals(
            listOf(
                drawable,
                Rect(0f, 25f, 100f, 25f),
            ),
            layout.availableRects,
        )
        assertEquals(
            listOf(
                Rect(0f, 5f, 100f, 35f),
                Rect(0f, 25f, 100f, 32f),
            ),
            layout.claimedRects,
        )
        assertEquals(37.0, layout.totalClaimedHeightPx)
    }

    @Test
    fun `zero height keeps the same available rect and produces an empty claim`() {
        val drawable = Rect(0f, 0f, 40f, 20f)

        val layout = layoutKlineIndicatorTopTipsStack(
            drawableRect = drawable,
            claimedHeightsPx = listOf(0f, 5f),
        )

        assertEquals(listOf(drawable, drawable), layout.availableRects)
        assertEquals(Rect(0f, 0f, 40f, 0f), layout.claimedRects.first())
        assertEquals(5.0, layout.totalClaimedHeightPx)
    }

    @Test
    fun `invalid geometry and claimed heights fail before producing a plan`() {
        assertFailsWith<IllegalArgumentException> {
            layoutKlineIndicatorTopTipsStack(
                drawableRect = Rect(Float.NaN, 0f, 10f, 10f),
                claimedHeightsPx = emptyList(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            layoutKlineIndicatorTopTipsStack(
                drawableRect = Rect(10f, 0f, 0f, 10f),
                claimedHeightsPx = emptyList(),
            )
        }
        listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                layoutKlineIndicatorTopTipsStack(
                    drawableRect = Rect(0f, 0f, 10f, 10f),
                    claimedHeightsPx = listOf(invalid),
                )
            }
        }
    }
}
