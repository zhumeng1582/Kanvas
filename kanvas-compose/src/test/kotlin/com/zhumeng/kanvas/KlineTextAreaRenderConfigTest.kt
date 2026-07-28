/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KlineTextAreaRenderConfigTest {
    @Test
    fun `price marker defaults preserve in-view and off-view geometry`() {
        val overlay = KlineCandleOverlayRenderConfig()

        assertEquals(KlineTextAlign.Center, overlay.inViewPrice.text.textAlign)
        assertEquals(KlinePanePadding(2f, 2f, 2f, 2f), overlay.inViewPrice.text.padding)
        assertEquals(KlineCornerRadius(2f), overlay.inViewPrice.text.borderRadius.topLeft)
        assertEquals(KlinePanePadding(4f, 2f, 4f, 2f), overlay.offViewPrice.text.padding)
        assertEquals(KlineCornerRadius(10f), overlay.offViewPrice.text.borderRadius.bottomRight)
    }

    @Test
    fun `text area rejects contradictory or non-finite dimensions`() {
        assertFailsWith<IllegalArgumentException> {
            KlineTextAreaRenderConfig(minWidthPx = 20f, maxWidthPx = 10f)
        }
        assertFailsWith<IllegalArgumentException> {
            KlineTextAreaRenderConfig(fontSizeSp = Float.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            KlineTextAreaRenderConfig(maxLines = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            KlineCornerRadius(-1f)
        }
    }
}
