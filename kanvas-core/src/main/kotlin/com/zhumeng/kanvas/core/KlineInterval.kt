/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas.core

/** Time units supported by the chart interval model. */
enum class KlineTimeUnit(
    val approximateMillis: Long,
    val debugSuffix: String,
) {
    Microsecond(0L, "us"),
    Millisecond(1L, "ms"),
    Second(1_000L, "s"),
    Minute(60_000L, "m"),
    Hour(3_600_000L, "H"),
    Day(86_400_000L, "D"),
    Week(604_800_000L, "W"),
    Month(2_592_000_000L, "M"),
    Year(31_536_000_000L, "Y"),
    ;

    val code: String
        get() = name.replaceFirstChar { it.lowercase() }

    companion object {
        fun fromCode(value: String): KlineTimeUnit? = entries.firstOrNull { it.code == value }
    }
}

/** Platform-independent chart interval. */
data class KlineInterval(
    val multiplier: Int,
    val unit: KlineTimeUnit,
) {
    val approximateDurationMillis: Long
        get() = unit.approximateMillis * multiplier

    val isValid: Boolean
        get() = multiplier > 0 && unit != KlineTimeUnit.Microsecond

    val debugLabel: String
        get() = "$multiplier${unit.debugSuffix}"

    override fun toString(): String = "$multiplier${unit.code}"

    companion object {
        val Invalid = KlineInterval(0, KlineTimeUnit.Millisecond)
    }
}
