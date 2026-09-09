/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KlineTextAreaRenderConfigTest {
    @Test
    fun `chart requests 120Hz by default and allows clearing the preference`() {
        assertEquals(120f, KlineChartRenderConfig().preferredFrameRate)
        assertEquals(0f, KlineChartRenderConfig(preferredFrameRate = 0f).preferredFrameRate)
        assertFailsWith<IllegalArgumentException> {
            KlineChartRenderConfig(preferredFrameRate = 361f)
        }
    }

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

    @Test
    fun `simple text selects native fast path with optional area decoration`() {
        assertTrue(KlineGridRenderConfig().ticksText.supportsNativeSimpleTextArea())
        assertTrue(
            KlineGridRenderConfig().ticksText
                .copy(backgroundColor = Color.Black, borderColor = Color.White, borderWidthPx = 1f)
                .supportsNativeSimpleTextArea(),
        )
        assertFalse(
            KlineGridRenderConfig().ticksText
                .copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                .supportsNativeSimpleTextArea(),
        )
    }

    @Test
    fun `unconstrained undecorated axis text selects the batched native path`() {
        assertTrue(KlineGridRenderConfig().ticksText.supportsNativeBatchedYAxisTicks())
        assertFalse(
            KlineTextAreaRenderConfig(textWidthPx = 48f)
                .supportsNativeBatchedYAxisTicks(),
        )
        assertFalse(
            KlineTextAreaRenderConfig(backgroundColor = Color.Black)
                .supportsNativeBatchedYAxisTicks(),
        )
    }

    @Test
    fun `price formatter reuses locale-stable fixed precision output`() {
        assertEquals("1234.50", formatPrice(1234.5, 2))
        assertEquals("1.24", formatPrice(1.235, 2))
        assertEquals("-0.00", formatPrice(-0.0, 2))
        assertEquals("2", formatPrice(1.6, 0))
    }

    @Test
    fun `price formatter matches platform rounding at boundaries and across magnitudes`() {
        val random = java.util.Random(120L)
        for (precision in 0..12) {
            val reference = java.text.DecimalFormat(
                if (precision == 0) "0" else "0." + "0".repeat(precision),
                java.text.DecimalFormatSymbols.getInstance(java.util.Locale.US),
            ).apply { roundingMode = java.math.RoundingMode.HALF_UP }
            val factor = Math.pow(10.0, precision.toDouble())
            val values = buildList {
                addAll(listOf(0.0, -0.0, 1.005, 2.675, Double.MIN_VALUE, Double.MAX_VALUE))
                repeat(2_000) {
                    add(Math.scalb(random.nextDouble() - 0.5, random.nextInt(120) - 60))
                    val tie = (random.nextInt(1_000_000) + 0.5) / factor
                    add(Math.nextDown(tie))
                    add(tie)
                    add(Math.nextUp(tie))
                }
            }
            for (value in values) {
                for (signed in listOf(value, -value)) {
                    assertEquals(reference.format(signed), formatPrice(signed, precision), "$signed / $precision")
                }
            }
        }
        assertEquals(formatPrice(1.234, 0), formatPrice(1.234, -1))
        assertEquals(formatPrice(1.234, 12), formatPrice(1.234, 13))
    }

    @Test
    fun `reused top tip formatter preserves rounding and clears previous text`() {
        val values = listOf(123456789.0, 2.675, -2.675, 1.005, -0.0, 0.0, 1e20, 0.1)
        repeat(2) {
            values.forEach { value ->
                assertEquals(
                    String.format(java.util.Locale.US, "%.2f", value),
                    formatKlineTopTipValue(value),
                )
            }
        }
    }
}
