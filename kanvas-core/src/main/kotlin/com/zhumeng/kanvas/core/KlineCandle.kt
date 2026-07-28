/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas.core

import java.math.BigDecimal

enum class KlineComputeMode {
    Fast,
    Accurate,
}

/** Optional exact decimal wire values retained alongside render-hot doubles. */
data class KlineExactCandleValues(
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: BigDecimal,
    val turnover: BigDecimal? = null,
)

/**
 * Normalized OHLCV input for the renderer.
 *
 * A [KlineSeries] always stores these records newest first. `Double` is the
 * default hot-path representation; adapters that receive decimal strings must
 * parse them before constructing this model. Exact [java.math.BigDecimal]
 * indicator policies are introduced separately and never change this wire
 * model's timestamp ordering contract.
 */
data class KlineCandle(
    val timestampMillis: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val turnover: Double? = null,
    val tradeCount: Int? = null,
    val confirmed: Boolean = true,
    val exactValues: KlineExactCandleValues? = null,
) {
    /** Rendering is skipped for invalid feed values instead of crashing Canvas. */
    val isRenderable: Boolean
        get() = open.isFinite() && high.isFinite() && low.isFinite() && close.isFinite() && volume.isFinite()

    fun exactOpen(): BigDecimal = exactValues?.open ?: BigDecimal.valueOf(open)
    fun exactHigh(): BigDecimal = exactValues?.high ?: BigDecimal.valueOf(high)
    fun exactLow(): BigDecimal = exactValues?.low ?: BigDecimal.valueOf(low)
    fun exactClose(): BigDecimal = exactValues?.close ?: BigDecimal.valueOf(close)
    fun exactVolume(): BigDecimal = exactValues?.volume ?: BigDecimal.valueOf(volume)
    fun exactTurnover(): BigDecimal? = exactValues?.turnover ?: turnover?.let(BigDecimal::valueOf)

    companion object {
        fun accurate(
            timestampMillis: Long,
            open: BigDecimal,
            high: BigDecimal,
            low: BigDecimal,
            close: BigDecimal,
            volume: BigDecimal,
            turnover: BigDecimal? = null,
            tradeCount: Int? = null,
            confirmed: Boolean = true,
        ): KlineCandle = KlineCandle(
            timestampMillis = timestampMillis,
            open = open.toDouble(),
            high = high.toDouble(),
            low = low.toDouble(),
            close = close.toDouble(),
            volume = volume.toDouble(),
            turnover = turnover?.toDouble(),
            tradeCount = tradeCount,
            confirmed = confirmed,
            exactValues = KlineExactCandleValues(open, high, low, close, volume, turnover),
        )
    }
}
