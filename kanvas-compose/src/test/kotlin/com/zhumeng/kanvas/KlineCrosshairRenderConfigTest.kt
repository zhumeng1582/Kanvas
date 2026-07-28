/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KlineCrosshairRenderConfigTest {
    @Test
    fun `tooltip hit-test margin is a finite nonnegative native logical-pixel value`() {
        assertEquals(6f, KlineCrosshairRenderConfig(tooltipHitTestMarginPx = 6f).tooltipHitTestMarginPx)
        assertFailsWith<IllegalArgumentException> {
            KlineCrosshairRenderConfig(tooltipHitTestMarginPx = -0.1f)
        }
        assertFailsWith<IllegalArgumentException> {
            KlineCrosshairRenderConfig(tooltipHitTestMarginPx = Float.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `tooltip geometry inputs reject nonfinite values before canvas layout`() {
        assertFailsWith<IllegalArgumentException> {
            KlineCrosshairRenderConfig(tooltipSpacingPx = Float.POSITIVE_INFINITY)
        }
        assertFailsWith<IllegalArgumentException> {
            KlinePanePadding(leftPx = Float.NaN)
        }
    }
}
