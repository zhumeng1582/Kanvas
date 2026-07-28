/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.text.TextStyle
import com.zhumeng.kanvas.core.KlineCandle
import com.zhumeng.kanvas.core.KlineSpec
import java.util.Locale
import kotlin.math.abs

/**
 * Kotlin/Compose-native input supplied when a Cross tooltip needs its rows.
 *
 * The context deliberately exposes normalized native candles rather than a
 * serialized record or JSON payload. [timeLabel] has already been formatted with
 * the chart's runtime [KlineTimeLabelFormatter], so a custom provider can
 * reuse the same interval and locale policy as the Time axis.
 */
data class KlineCrossTooltipContext(
    val crosshair: KlineIndicatorCrosshairContext,
    val spec: KlineSpec?,
    val timeLabel: String,
) {
    val candle: KlineCandle? get() = crosshair.candle

    /** The older neighbouring candle (`index + 1`) used by the range row. */
    val previousCandle: KlineCandle? get() = crosshair.previousCandle
}

/**
 * One two-column native Cross tooltip row.
 *
 * A non-null [onClick] makes the row a pointer target while Cross is visible.
 * The layout engine never invokes this callback; [KanvasChart] invokes it
 * only after its pure layout has resolved a matching row hit target.
 */
data class KlineCrossTooltipItem(
    val label: String,
    val value: String,
    val labelStyle: TextStyle? = null,
    val valueStyle: TextStyle? = null,
    val onClick: (() -> Unit)? = null,
)

/**
 * Supplies native Cross tooltip rows for the selected candle.
 *
 * Implementations run on the UI path for both painting and confirmed-tap hit
 * testing, so they must be deterministic, fast, and free of side effects.
 */
fun interface KlineCrossTooltipProvider {
    /** Returning an empty list deliberately hides the built-in tooltip card. */
    fun provide(context: KlineCrossTooltipContext): List<KlineCrossTooltipItem>
}

/**
 * Invokes the matching actionable row, if any. This small dispatcher keeps
 * pointer priority testable while [layoutKlineCrossTooltip] stays pure.
 */
internal fun dispatchKlineCrossTooltipItemTap(
    position: androidx.compose.ui.geometry.Offset,
    layout: KlineCrossTooltipLayout,
    items: List<KlineCrossTooltipItem>,
    hitTestMarginPx: Float,
): Boolean {
    require(hitTestMarginPx.isFinite() && hitTestMarginPx >= 0f) {
        "Tooltip hit-test margin must be finite and nonnegative."
    }
    // Only actionable rows install tap targets. Filter before
    // returning so an expanded, later non-action row cannot mask an earlier
    // actionable row at their shared edge.
    layout.rows.asReversed().forEach { row ->
        val contains = if (hitTestMarginPx == 0f) {
            row.bounds.contains(position)
        } else {
            row.bounds.inflate(hitTestMarginPx).contains(position)
        }
        val callback = items.getOrNull(row.index)?.onClick
        if (contains && callback != null) {
            callback()
            return true
        }
    }
    return false
}

/**
 * Default Time/O/H/L/C/Chg/%Chg/Range/Amount/Turnover tooltip. Hosts needing
 * business labels, formatting, or row
 * actions should supply [KlineCrossTooltipProvider] instead.
 */
fun defaultKlineCrossTooltipItems(
    context: KlineCrossTooltipContext,
    style: KlineChartStyle,
): List<KlineCrossTooltipItem> {
    val candle = context.candle ?: return emptyList()
    val precision = context.spec?.precision ?: KlineSpec.DefaultPrecision
    val change = candle.close - candle.open
    val changeRate = if (candle.open == 0.0) 0.0 else change / candle.open
    val range = candle.high - candle.low
    val rangeValue = context.previousCandle
        ?.close
        ?.takeIf { it != 0.0 }
        ?.let { previousClose -> formatKlineTooltipPercent(range / previousClose) }
        ?: formatKlineTooltipPrice(range, precision)
    val changeRateStyle = when {
        change > 0.0 -> TextStyle(color = style.bullish)
        change < 0.0 -> TextStyle(color = style.bearish)
        else -> null
    }
    return buildList {
        add(KlineCrossTooltipItem(label = "Time", value = context.timeLabel))
        add(KlineCrossTooltipItem(label = "Open", value = formatKlineTooltipPrice(candle.open, precision)))
        add(KlineCrossTooltipItem(label = "High", value = formatKlineTooltipPrice(candle.high, precision)))
        add(KlineCrossTooltipItem(label = "Low", value = formatKlineTooltipPrice(candle.low, precision)))
        add(KlineCrossTooltipItem(label = "Close", value = formatKlineTooltipPrice(candle.close, precision)))
        add(KlineCrossTooltipItem(label = "Chg", value = formatKlineTooltipPrice(change, precision)))
        add(
            KlineCrossTooltipItem(
                label = "%Chg",
                value = formatKlineTooltipPercent(changeRate),
                valueStyle = changeRateStyle,
            ),
        )
        add(KlineCrossTooltipItem(label = "Range", value = rangeValue))
        add(KlineCrossTooltipItem(label = "Amount", value = formatKlineTooltipAmount(candle.volume)))
        candle.turnover
            ?.takeIf(Double::isFinite)
            ?.let { turnover -> add(KlineCrossTooltipItem(label = "Turnover", value = formatKlineTooltipAmount(turnover))) }
    }
}

internal fun formatKlineTooltipPrice(value: Double, precision: Int): String =
    if (value.isFinite()) {
        String.format(Locale.US, "%.${precision.coerceIn(0, 12)}f", value)
    } else {
        "—"
    }

internal fun formatKlineTooltipPercent(value: Double): String =
    if (value.isFinite()) String.format(Locale.US, "%.2f%%", value * 100.0) else "—"

/** Compact native amount formatting; apps may replace it with a provider. */
internal fun formatKlineTooltipAmount(value: Double): String {
    if (!value.isFinite()) return "—"
    val magnitude = abs(value)
    val (scaled, suffix) = when {
        magnitude >= 1_000_000_000_000.0 -> value / 1_000_000_000_000.0 to "T"
        magnitude >= 1_000_000_000.0 -> value / 1_000_000_000.0 to "B"
        magnitude >= 1_000_000.0 -> value / 1_000_000.0 to "M"
        magnitude >= 1_000.0 -> value / 1_000.0 to "K"
        else -> value to ""
    }
    return String.format(Locale.US, "%.2f%s", scaled, suffix)
}
