/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

import androidx.compose.ui.graphics.Color

/** Supported candlestick and OHLC bar styles. */
enum class KlineBarStyle {
    AllSolid,
    AllHollow,
    UpHollow,
    DownHollow,
    Ohlc,
}

enum class KlineLineStyle {
    Normal,
    UpDown,
}

sealed interface KlineChartType {
    data class Bar(val style: KlineBarStyle = KlineBarStyle.AllSolid) : KlineChartType

    data class Line(val style: KlineLineStyle = KlineLineStyle.Normal) : KlineChartType
}

/**
 * Semantic theme values used by the Compose renderer.
 */
data class KlineChartStyle(
    val background: Color = Color(0xFF101722),
    val gridLine: Color = Color(0xFF273447),
    val bullish: Color = Color(0xFF2EC4B6),
    val bearish: Color = Color(0xFFFF6B6B),
    val line: Color = Color(0xFF4DB6FF),
    val crosshair: Color = Color(0xFF8AA0B8),
    val drawTool: Color = Color(0xFF4C8DFF),
    val markLine: Color = Color(0xFF3E92CC),
    /** Backward-compatible alias retained for callers using the earlier compact theme. */
    val latestPrice: Color = markLine,
    val latestPriceBackground: Color = Color(0xFF3E92CC),
    val lastPriceBackground: Color = Color(0xEE1A2533),
    val crossTextBackground: Color = Color(0xEE1A2533),
    val dragBackground: Color = markLine,
    val ticksTextColor: Color = Color(0xFFB9C6D5),
    /** Backward-compatible alias retained for callers using the earlier compact theme. */
    val axisText: Color = ticksTextColor,
    /** Primary chart text color; loading content falls back here. */
    val textColor: Color = axisText,
    val lastPriceTextColor: Color = axisText,
    val crossTextColor: Color = axisText,
    val tooltipTextColor: Color = axisText,
    val tooltipBackground: Color = Color(0xEE1A2533),
    /** Dedicated background for the latest-candle countdown. */
    val countdownBackground: Color = Color(0xFF273447),
    /** Default colors used by the bundled indicator examples. */
    val indicatorLines: List<Color> = listOf(
        Color(0xFFFFC857),
        Color(0xFFB388FF),
        Color(0xFF4DD0E1),
        Color(0xFFFF8A80),
    ),
    val volumeBullish: Color = Color(0x992EC4B6),
    val volumeBearish: Color = Color(0x99FF6B6B),
)
