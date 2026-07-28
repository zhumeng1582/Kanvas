/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KlinePaneResizeMathTest {
    @Test
    fun `fixed resize redistributes adjacent panes and respects minimums`() {
        val entries = listOf(
            KlinePaneResizeEntry("main", 200f, 100f),
            KlinePaneResizeEntry("volume", 80f, 30f),
        )
        val grown = KlinePaneResizeMath.resize(entries, 0, 20f, KlineLayoutMode.Fixed)
        assertEquals(listOf(220f, 60f), grown.entries.map(KlinePaneResizeEntry::heightPx))

        val clamped = KlinePaneResizeMath.resize(entries, 0, 100f, KlineLayoutMode.Fixed)
        assertEquals(50f, clamped.appliedDeltaPx)
        assertEquals(listOf(250f, 30f), clamped.entries.map(KlinePaneResizeEntry::heightPx))
    }

    @Test
    fun `adapt last edge changes only its upper pane`() {
        val entries = listOf(KlinePaneResizeEntry("volume", 60f, 30f))
        val result = KlinePaneResizeMath.resize(entries, 0, -100f, KlineLayoutMode.Adapt)
        assertEquals(30f, result.entries.single().heightPx)
        assertEquals(-30f, result.appliedDeltaPx)
    }

    @Test
    fun `boundary hit excludes fixed last pane`() {
        val panes = listOf(
            pane("main", 0f, 200f),
            pane("volume", 200f, 280f),
        )
        assertEquals(0, KlinePaneResizeMath.hitBoundary(panes, 203f, 10f, KlineLayoutMode.Fixed))
        assertNull(KlinePaneResizeMath.hitBoundary(panes, 280f, 10f, KlineLayoutMode.Fixed))
        assertEquals(1, KlinePaneResizeMath.hitBoundary(panes, 280f, 10f, KlineLayoutMode.Adapt))
    }

    private fun pane(id: String, top: Float, bottom: Float) = KlinePaneLayout(
        id = id,
        outerRect = Rect(0f, top, 100f, bottom),
        plotRect = Rect(0f, top, 100f, bottom),
        requestedHeightPx = bottom - top,
        resolvedHeightPx = bottom - top,
    )
}
