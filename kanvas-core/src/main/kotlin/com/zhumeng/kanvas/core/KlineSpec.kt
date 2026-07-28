/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas.core

/**
 * Request and identity data for one K-line series.
 *
 * `limit` and `label` are request/presentation details and do not distinguish
 * an existing series.
 */
class KlineSpec(
    val symbol: String,
    val interval: KlineInterval,
    val limit: Int = DefaultLimit,
    val precision: Int = DefaultPrecision,
    val fromMillis: Long? = null,
    val toMillis: Long? = null,
    val label: String? = null,
) {
    init {
        require(limit > 0) { "limit must be > 0" }
        require(precision >= 0) { "precision must be >= 0" }
    }

    val key: String get() = "$symbol-$interval"

    val rangeKey: String get() = "$key-$fromMillis-$toMillis"

    fun initial(): KlineSpec = copy(fromMillis = null, toMillis = null)

    fun copy(
        symbol: String = this.symbol,
        interval: KlineInterval = this.interval,
        limit: Int = this.limit,
        precision: Int = this.precision,
        fromMillis: Long? = this.fromMillis,
        toMillis: Long? = this.toMillis,
        label: String? = this.label,
    ): KlineSpec = KlineSpec(symbol, interval, limit, precision, fromMillis, toMillis, label)

    override fun equals(other: Any?): Boolean =
        other is KlineSpec &&
            symbol == other.symbol &&
            interval == other.interval &&
            fromMillis == other.fromMillis &&
            toMillis == other.toMillis &&
            precision == other.precision

    override fun hashCode(): Int {
        var result = symbol.hashCode()
        result = 31 * result + interval.hashCode()
        result = 31 * result + (fromMillis?.hashCode() ?: 0)
        result = 31 * result + (toMillis?.hashCode() ?: 0)
        result = 31 * result + precision
        return result
    }

    override fun toString(): String =
        "KlineSpec($symbol-$label, $interval, $limit, $precision, $fromMillis, $toMillis)"

    companion object {
        const val DefaultLimit: Int = 200
        /** Default price precision when a host does not specify one. */
        const val DefaultPrecision: Int = 4
    }
}

enum class KlineLoadingState {
    None,
    InitLoading,
    LoadMore,
    LoadingMore,
    ;

    /** True only for states that require a visible loading indicator. */
    val showLoading: Boolean get() = this == InitLoading || this == LoadingMore

    val isLoadMore: Boolean get() = this == LoadMore || this == LoadingMore
}
