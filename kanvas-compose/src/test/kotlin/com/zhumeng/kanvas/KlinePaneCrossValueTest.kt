/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KlinePaneCrossValueTest {
    @Test
    fun `sub pane cross resolves its own value range only inside the pane`() {
        val pane = Rect(10f, 100f, 210f, 200f)
        val range = KlineValueRange(minimum = 0.0, maximum = 1_000.0)

        assertEquals(750.0, resolveKlinePaneCrossValue(Offset(100f, 125f), pane, range))
        assertEquals(10.0, checkNotNull(resolveKlinePaneCrossValue(Offset(209f, 199f), pane, range)), 0.0001)
        assertNull(resolveKlinePaneCrossValue(Offset(210f, 200f), pane, range))
        assertNull(resolveKlinePaneCrossValue(Offset(100f, 99f), pane, range))
        assertNull(resolveKlinePaneCrossValue(Offset(211f, 150f), pane, range))
    }
}
