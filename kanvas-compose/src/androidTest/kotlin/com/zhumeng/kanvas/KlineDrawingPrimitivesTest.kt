/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KlineDrawingPrimitivesTest {
    @Test
    fun priceFormattingMatchesAndroidDecimalFormat() {
        val random = Random(120L)
        for (precision in 0..12) {
            val reference = DecimalFormat(
                if (precision == 0) "0" else "0." + "0".repeat(precision),
                DecimalFormatSymbols.getInstance(Locale.US),
            ).apply { roundingMode = RoundingMode.HALF_UP }
            val factor = Math.pow(10.0, precision.toDouble())
            val values = buildList {
                addAll(listOf(0.0, -0.0, 1.005, 2.675, Double.MIN_VALUE, Double.MAX_VALUE))
                repeat(500) {
                    add(Math.scalb(random.nextDouble() - 0.5, random.nextInt(120) - 60))
                    val tie = (random.nextInt(1_000_000) + 0.5) / factor
                    add(Math.nextDown(tie))
                    add(tie)
                    add(Math.nextUp(tie))
                }
            }
            for (value in values) {
                for (signed in listOf(value, -value)) {
                    assertEquals("$signed / $precision", reference.format(signed), formatPrice(signed, precision))
                }
            }
        }
    }

    @Test
    fun primitiveTextBackgroundMatchesPathRendering() {
        val ellipse = KlineCornerRadius(4f, 12f)
        val radii = listOf(
            KlineBorderRadius(),
            KlineBorderRadius.all(2f),
            KlineBorderRadius.all(10f),
            KlineBorderRadius(ellipse, ellipse, ellipse, ellipse),
            KlineBorderRadius.all(200f),
            KlineBorderRadius(topLeft = ellipse, bottomRight = KlineCornerRadius(10f)),
        )
        val rect = Rect(16f, 16f, 180f, 80f)
        for (radius in radii) {
            val config = KlineTextAreaRenderConfig(
                backgroundColor = Color(0x884488CC),
                borderColor = Color(0xAAFF4422),
                borderWidthPx = 2f,
                borderRadius = radius,
            )
            fun render(reference: Boolean): ImageBitmap {
                val bitmap = ImageBitmap(200, 100)
                CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(200f, 100f)) {
                    if (reference) {
                        fun KlineCornerRadius.corner() = CornerRadius(xPx, yPx)
                        val path = Path().apply {
                            addRoundRect(
                                RoundRect(rect, radius.topLeft.corner(), radius.topRight.corner(),
                                    radius.bottomRight.corner(), radius.bottomLeft.corner()),
                            )
                        }
                        drawPath(path, checkNotNull(config.backgroundColor))
                        drawPath(path, checkNotNull(config.borderColor), style = Stroke(config.borderWidthPx))
                    } else {
                        drawKlineTextAreaBackground(rect, config, null, null)
                    }
                }
                return bitmap
            }
            val expected = render(true).toPixelMap()
            val actual = render(false).toPixelMap()
            for (y in 0 until 100) {
                for (x in 0 until 200) {
                    assertEquals("$radius at ($x, $y)", expected[x, y], actual[x, y])
                }
            }
        }
    }
}
