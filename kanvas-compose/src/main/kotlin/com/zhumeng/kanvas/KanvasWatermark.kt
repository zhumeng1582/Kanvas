/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Content rendered by [KanvasWatermarkConfig]. */
sealed interface KanvasWatermarkContent {
    /** Native text watermark with type-safe Compose text styling. */
    data class Text(
        val value: String,
        val color: Color = Color.Gray,
        val fontSize: TextUnit = 28.sp,
        val fontWeight: FontWeight = FontWeight.Medium,
    ) : KanvasWatermarkContent {
        init {
            require(value.isNotBlank()) { "Watermark text must not be blank" }
            require(fontSize.isSp && fontSize.value > 0f) { "Watermark font size must be positive sp" }
        }
    }

    /** Bitmap watermark. Omit [height] to preserve the bitmap aspect ratio. */
    data class Image(
        val bitmap: ImageBitmap,
        val width: Dp = 96.dp,
        val height: Dp? = null,
    ) : KanvasWatermarkContent {
        init {
            require(width.value.isFinite() && width.value > 0f) { "Watermark image width must be positive" }
            require(height == null || height.value.isFinite() && height.value > 0f) {
                "Watermark image height must be positive"
            }
        }
    }
}

/** Physical chart region receiving a watermark. */
sealed interface KanvasWatermarkTarget {
    /** The complete chart, including main, sub, and time panes. */
    data object WholeChart : KanvasWatermarkTarget

    /** Only the drawable main-pane plot. */
    data object MainPane : KanvasWatermarkTarget

    /** Only the drawable area of the named dynamic sub pane. */
    data class SubPane(val id: String) : KanvasWatermarkTarget {
        init {
            require(id.isNotBlank()) { "Watermark sub-pane id must not be blank" }
        }
    }
}

enum class KanvasWatermarkPlacement {
    Center,
    Tiled,
}

enum class KanvasWatermarkLayer {
    /** Above the chart background and below grid, candles, indicators, and drawings. */
    BehindContent,

    /** Above chart content but below the Compose loading overlay. */
    AboveContent,
}

/**
 * Native, pointer-transparent watermark configuration.
 *
 * A nullable config on [KanvasChartConfig] keeps watermarks disabled by default.
 */
data class KanvasWatermarkConfig(
    val content: KanvasWatermarkContent,
    val target: KanvasWatermarkTarget = KanvasWatermarkTarget.MainPane,
    val placement: KanvasWatermarkPlacement = KanvasWatermarkPlacement.Center,
    val layer: KanvasWatermarkLayer = KanvasWatermarkLayer.BehindContent,
    val alpha: Float = 0.12f,
    val rotationDegrees: Float = -20f,
    /** Empty space between neighboring items when [placement] is tiled. */
    val horizontalSpacing: Dp = 72.dp,
    /** Empty space between neighboring items when [placement] is tiled. */
    val verticalSpacing: Dp = 56.dp,
) {
    init {
        require(alpha.isFinite() && alpha in 0f..1f) { "Watermark alpha must be between 0 and 1" }
        require(rotationDegrees.isFinite()) { "Watermark rotation must be finite" }
        require(horizontalSpacing.value.isFinite() && horizontalSpacing.value >= 0f) {
            "Watermark horizontal spacing must not be negative"
        }
        require(verticalSpacing.value.isFinite() && verticalSpacing.value >= 0f) {
            "Watermark vertical spacing must not be negative"
        }
    }
}

internal fun resolveKanvasWatermarkBounds(
    layout: KlineLayout,
    target: KanvasWatermarkTarget,
): Rect? = when (target) {
    KanvasWatermarkTarget.WholeChart -> layout.chartRect
    KanvasWatermarkTarget.MainPane -> layout.mainPane.plotRect
    is KanvasWatermarkTarget.SubPane -> layout.subPanes.firstOrNull { it.id == target.id }?.plotRect
}

internal fun resolveKanvasWatermarkCenters(
    bounds: Rect,
    itemSize: Size,
    placement: KanvasWatermarkPlacement,
    horizontalSpacingPx: Float,
    verticalSpacingPx: Float,
): List<Offset> = buildList {
    forEachKanvasWatermarkCenter(
        bounds = bounds,
        itemSize = itemSize,
        placement = placement,
        horizontalSpacingPx = horizontalSpacingPx,
        verticalSpacingPx = verticalSpacingPx,
        action = ::add,
    )
}

private inline fun forEachKanvasWatermarkCenter(
    bounds: Rect,
    itemSize: Size,
    placement: KanvasWatermarkPlacement,
    horizontalSpacingPx: Float,
    verticalSpacingPx: Float,
    action: (Offset) -> Unit,
) {
    if (bounds.width <= 0f || bounds.height <= 0f || itemSize.width <= 0f || itemSize.height <= 0f) {
        return
    }
    if (placement == KanvasWatermarkPlacement.Center) {
        action(bounds.center)
        return
    }

    val stepX = itemSize.width + horizontalSpacingPx.coerceAtLeast(0f)
    val stepY = itemSize.height + verticalSpacingPx.coerceAtLeast(0f)
    var y = bounds.top + itemSize.height / 2f
    var row = 0
    while (y - itemSize.height / 2f < bounds.bottom) {
        val stagger = if (row % 2 == 0) 0f else stepX / 2f
        var x = bounds.left + itemSize.width / 2f - stagger
        while (x - itemSize.width / 2f < bounds.right) {
            if (x + itemSize.width / 2f > bounds.left) action(Offset(x, y))
            x += stepX
        }
        y += stepY
        row++
    }
}

internal fun DrawScope.drawKanvasWatermark(
    config: KanvasWatermarkConfig,
    layout: KlineLayout,
    textMeasurer: TextMeasurer,
) {
    if (config.alpha == 0f) return
    val bounds = resolveKanvasWatermarkBounds(layout, config.target) ?: return
    val prepared = prepareKanvasWatermark(config.content, textMeasurer)
    clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) {
        forEachKanvasWatermarkCenter(
            bounds = bounds,
            itemSize = prepared.size,
            placement = config.placement,
            horizontalSpacingPx = config.horizontalSpacing.toPx(),
            verticalSpacingPx = config.verticalSpacing.toPx(),
        ) { center ->
            rotate(config.rotationDegrees, center) {
                val topLeft = center - Offset(prepared.size.width / 2f, prepared.size.height / 2f)
                when (prepared) {
                    is PreparedKanvasWatermark.Text -> drawText(
                        textLayoutResult = prepared.layout,
                        topLeft = topLeft,
                        alpha = config.alpha,
                    )
                    is PreparedKanvasWatermark.Image -> drawImage(
                        image = prepared.bitmap,
                        dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
                        dstSize = IntSize(
                            prepared.size.width.roundToInt().coerceAtLeast(1),
                            prepared.size.height.roundToInt().coerceAtLeast(1),
                        ),
                        alpha = config.alpha,
                    )
                }
            }
        }
    }
}

private sealed interface PreparedKanvasWatermark {
    val size: Size

    data class Text(val layout: TextLayoutResult) : PreparedKanvasWatermark {
        override val size: Size = Size(layout.size.width.toFloat(), layout.size.height.toFloat())
    }

    data class Image(
        val bitmap: ImageBitmap,
        override val size: Size,
    ) : PreparedKanvasWatermark
}

private fun DrawScope.prepareKanvasWatermark(
    content: KanvasWatermarkContent,
    textMeasurer: TextMeasurer,
): PreparedKanvasWatermark = when (content) {
    is KanvasWatermarkContent.Text -> PreparedKanvasWatermark.Text(
        textMeasurer.measure(
            text = AnnotatedString(content.value),
            style = TextStyle(
                color = content.color,
                fontSize = content.fontSize,
                fontWeight = content.fontWeight,
            ),
            maxLines = 1,
        ),
    )
    is KanvasWatermarkContent.Image -> {
        val widthPx = content.width.toPx()
        val heightPx = content.height?.toPx()
            ?: widthPx * content.bitmap.height.toFloat() / content.bitmap.width.coerceAtLeast(1).toFloat()
        PreparedKanvasWatermark.Image(content.bitmap, Size(widthPx, heightPx))
    }
}
