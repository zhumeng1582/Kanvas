/*
 * Copyright 2026
 * SPDX-License-Identifier: Apache-2.0
 */

package com.zhumeng.kanvas

internal class KlineValueRangeSmoother {
    private var key: Any? = null
    private var current: KlineValueRange? = null

    fun resolve(target: KlineValueRange, smoothFactor: Float, resetKey: Any?): KlineValueRange {
        val factor = smoothFactor.coerceIn(0.1f, 1f).toDouble()
        if (factor >= 1.0 || key != resetKey || current == null) {
            key = resetKey
            current = target
            return target
        }
        val previous = checkNotNull(current)
        return KlineValueRange(
            minimum = previous.minimum + (target.minimum - previous.minimum) * factor,
            maximum = previous.maximum + (target.maximum - previous.maximum) * factor,
        ).also { current = it }
    }
}
